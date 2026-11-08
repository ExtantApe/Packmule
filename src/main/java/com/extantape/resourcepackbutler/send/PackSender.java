package com.extantape.resourcepackbutler.send;

import com.extantape.resourcepackbutler.ResourcePackButler;
import com.extantape.resourcepackbutler.check.LinkChecker.Outcome;
import com.extantape.resourcepackbutler.config.ButlerConfig;
import com.extantape.resourcepackbutler.send.PackPlan.Pack;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps each player's Butler packs matching their world, and handles what the client says back. Runs on the
 * main thread only. Never touches a pack ID it didn't send, so other plugins' packs stay where they are.
 */
public final class PackSender implements Listener {
    private static final long RETRY_DELAY_TICKS = 40;

    private final ResourcePackButler plugin;
    private final Map<UUID, PlayerPacks> players = new HashMap<>();
    private long generations;

    public PackSender(ResourcePackButler plugin) {
        this.plugin = plugin;
    }

    /** One pack as this player's client has it, as far as Butler knows. */
    private static final class Sent {
        final Pack pack;
        int retries;
        long generation;
        boolean answered;
        boolean finished;
        boolean gaveUp;
        boolean resentAfterDiscard;

        Sent(Pack pack) {
            this.pack = pack;
        }
    }

    private static final class PlayerPacks {
        final List<Sent> sent = new ArrayList<>();
        /** Packs Butler took off before the client finished with them; the client reports those as discarded. */
        final Map<UUID, Integer> expectDiscard = new HashMap<>();
        boolean kicked;

        Sent find(UUID id) {
            for (Sent s : sent) if (s.pack.id().equals(id)) return s;
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        // The player is moved after this event. Changing worlds also fires a world change; this covers the rest.
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) sync(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        players.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        UUID id = event.getID();
        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        if (Bukkit.isPrimaryThread()) handleStatus(player, id, status);
        else Bukkit.getScheduler().runTask(plugin, () -> handleStatus(player, id, status));
    }

    /** Brings one player's Butler packs in line with their current world. Does nothing when they already match. */
    public void sync(Player player) {
        if (!player.isOnline()) return;
        PlayerPacks state = players.computeIfAbsent(player.getUniqueId(), k -> new PlayerPacks());
        if (state.kicked) return;
        List<Pack> wanted = wanted(player.getWorld().getName());
        List<Pack> sent = state.sent.stream().map(s -> s.pack).toList();
        PackPlan.Change change = PackPlan.between(sent, wanted);
        if (change.none()) return;
        removeTail(player, state, change.keep());
        List<Sent> added = new ArrayList<>();
        for (Pack pack : change.send()) {
            Sent s = new Sent(pack);
            state.sent.add(s);
            added.add(s);
        }
        push(player, state, added);
    }

    /**
     * After the first check at startup: anyone who joined before it finished, and so is missing packs, gets
     * them. Players whose packs differ any other way are left alone until they change worlds or rejoin.
     */
    public void fillMissing() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerPacks state = players.get(player.getUniqueId());
            List<Pack> wanted = wanted(player.getWorld().getName());
            if (state == null) {
                sync(player);
                continue;
            }
            int j = 0;
            boolean subset = true;
            for (Sent s : state.sent) {
                while (j < wanted.size() && !wanted.get(j).sameAs(s.pack)) j++;
                if (j == wanted.size()) {
                    subset = false;
                    break;
                }
                j++;
            }
            if (subset) sync(player);
        }
    }

    /** What Butler is sending a player, for /butler status. */
    public List<String> describe(Player player) {
        PlayerPacks state = players.get(player.getUniqueId());
        if (state == null || state.sent.isEmpty()) return List.of("nothing");
        List<String> out = new ArrayList<>();
        for (Sent s : state.sent) {
            String where = s.gaveUp ? "gave up after " + s.retries + " retries" : s.finished ? "loaded" : s.answered ? "loading" : "waiting for an answer";
            out.add(s.pack.name() + " (" + s.pack.sha1().substring(0, 8) + ", " + where + ")");
        }
        return out;
    }

    public void clear() {
        players.clear();
    }

    private List<Pack> wanted(String world) {
        ButlerConfig config = plugin.config();
        if (config == null) return List.of();
        List<String> names = config.worlds().get(world);
        if (names == null) return List.of();
        List<Pack> packs = new ArrayList<>();
        for (String name : names) {
            Outcome outcome = plugin.outcome(name);
            if (outcome == null) continue; // Still being checked.
            if (!outcome.live()) {
                plugin.warnAdmins("dead:" + name + ":" + world, "World '" + world + "' isn't getting pack '" + name
                        + "' because its link is dead: " + outcome.deadReason() + ". Run /butler recheck once it's fixed.");
                continue;
            }
            packs.add(new Pack(PackPlan.idFor(name), name, outcome.url(), outcome.sha1()));
        }
        return packs;
    }

    private void removeTail(Player player, PlayerPacks state, int keep) {
        if (state.sent.size() <= keep) return;
        List<Sent> tail = new ArrayList<>(state.sent.subList(keep, state.sent.size()));
        state.sent.subList(keep, state.sent.size()).clear();
        UUID first = tail.get(0).pack.id();
        UUID[] rest = tail.stream().skip(1).map(s -> s.pack.id()).toArray(UUID[]::new);
        for (Sent s : tail) if (!s.finished) state.expectDiscard.merge(s.pack.id(), 1, Integer::sum);
        player.removeResourcePacks(first, rest);
    }

    private void push(Player player, PlayerPacks state, List<Sent> packs) {
        if (packs.isEmpty()) return;
        ButlerConfig config = plugin.config();
        List<ResourcePackInfo> infos = new ArrayList<>();
        Map<Sent, Long> asked = new HashMap<>();
        for (Sent s : packs) {
            s.generation = ++generations;
            s.answered = false;
            s.finished = false;
            s.gaveUp = false;
            asked.put(s, s.generation);
            infos.add(ResourcePackInfo.resourcePackInfo(s.pack.id(), URI.create(s.pack.url()), s.pack.sha1()));
        }
        ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                .packs(infos)
                .replace(false) // Never clear packs other plugins sent.
                .required(true);
        if (config != null && !config.prompt().isBlank()) request.prompt(Component.text(config.prompt()));
        player.sendResourcePacks(request.build());

        long timeoutTicks = (config == null ? 60 : config.answerTimeoutSeconds()) * 20L;
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PlayerPacks now = players.get(uuid);
            if (now != state || state.kicked || !player.isOnline()) return;
            for (var entry : asked.entrySet()) {
                Sent s = entry.getKey();
                if (state.sent.contains(s) && s.generation == entry.getValue() && !s.answered) {
                    kick(player, state, plugin.config() == null ? "" : plugin.config().noAnswerMessage());
                    return;
                }
            }
        }, timeoutTicks);
    }

    private void handleStatus(Player player, UUID id, PlayerResourcePackStatusEvent.Status status) {
        PlayerPacks state = players.get(player.getUniqueId());
        if (state == null || !player.isOnline()) return;
        if (status == PlayerResourcePackStatusEvent.Status.DISCARDED) {
            Integer expected = state.expectDiscard.get(id);
            if (expected != null) {
                if (expected <= 1) state.expectDiscard.remove(id);
                else state.expectDiscard.put(id, expected - 1);
                return;
            }
        }
        Sent s = state.find(id);
        if (s == null || state.kicked) return; // Not Butler's, or no longer wanted.
        switch (status) {
            case ACCEPTED, DOWNLOADED -> s.answered = true;
            case SUCCESSFULLY_LOADED -> {
                s.answered = true;
                s.finished = true;
            }
            case DECLINED -> {
                s.answered = true;
                kick(player, state, plugin.config() == null ? "" : plugin.config().declinedMessage());
            }
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                s.answered = true;
                s.finished = true;
                int max = plugin.config() == null ? 0 : plugin.config().downloadRetries();
                if (s.retries < max) {
                    s.retries++;
                    long generation = s.generation;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (players.get(player.getUniqueId()) == state && state.sent.contains(s) && s.generation == generation && !state.kicked) {
                            resendFrom(player, state, s);
                        }
                    }, RETRY_DELAY_TICKS);
                } else {
                    s.gaveUp = true;
                    plugin.warnAdmins("failed:" + s.pack.name() + ":" + player.getWorld().getName(),
                            player.getName() + " is in world '" + player.getWorld().getName() + "' without pack '" + s.pack.name()
                                    + "': the download failed " + (s.retries + 1) + " times (" + status + "). Check the host for " + s.pack.url());
                }
            }
            case DISCARDED -> {
                // Something else took our pack off. Put it back once, never in a loop.
                if (!s.resentAfterDiscard) {
                    s.resentAfterDiscard = true;
                    long generation = s.generation;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (players.get(player.getUniqueId()) == state && state.sent.contains(s) && s.generation == generation && !state.kicked) {
                            resendFrom(player, state, s);
                        }
                    }, RETRY_DELAY_TICKS);
                }
            }
            default -> { }
        }
    }

    /** Sends a pack again, along with every Butler pack after it, so their order holds. */
    private void resendFrom(Player player, PlayerPacks state, Sent from) {
        if (!player.isOnline()) return;
        int index = state.sent.indexOf(from);
        if (index < 0) return;
        List<Sent> tail = new ArrayList<>(state.sent.subList(index, state.sent.size()));
        removeTail(player, state, index);
        state.sent.addAll(tail);
        push(player, state, tail);
    }

    private void kick(Player player, PlayerPacks state, String message) {
        if (state.kicked) return;
        state.kicked = true;
        player.kick(Component.text(message));
    }
}
