package com.extantape.resourcepackbutler.command;

import com.extantape.resourcepackbutler.ResourcePackButler;
import com.extantape.resourcepackbutler.check.LinkChecker.Outcome;
import com.extantape.resourcepackbutler.config.ButlerConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandExecutor;

import java.util.List;

/** /butler recheck, reload and status. */
public final class ButlerCommand implements CommandExecutor, TabCompleter {
    private final ResourcePackButler plugin;

    public ButlerCommand(ResourcePackButler plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "recheck" -> {
                if (!allowed(sender, "resourcepackbutler.recheck")) return true;
                if (plugin.config() == null) {
                    sender.sendMessage(Component.text("config.yml has problems, so there's nothing to check. Fix it and run /butler reload.", NamedTextColor.RED));
                    return true;
                }
                sender.sendMessage(Component.text("Re-checking every pack link. Players online keep their packs until they change worlds or rejoin."));
                plugin.startCheck(sender, false);
            }
            case "reload" -> {
                if (!allowed(sender, "resourcepackbutler.reload")) return true;
                List<String> errors = plugin.reload();
                if (errors.isEmpty()) {
                    sender.sendMessage(Component.text("Reloaded, and re-checking links. Players online keep their packs until they change worlds or rejoin."));
                } else {
                    sender.sendMessage(Component.text("config.yml has problems, so nothing changed:", NamedTextColor.RED));
                    for (String error : errors) sender.sendMessage(Component.text(" - " + error, NamedTextColor.RED));
                }
            }
            case "status" -> {
                if (!allowed(sender, "resourcepackbutler.status")) return true;
                status(sender, args.length > 1 ? args[1] : null);
            }
            default -> sender.sendMessage(Component.text("Usage: /" + label + " <recheck|reload|status [player]>"));
        }
        return true;
    }

    private void status(CommandSender sender, String playerName) {
        ButlerConfig config = plugin.config();
        if (config == null) {
            sender.sendMessage(Component.text("No config loaded: config.yml has problems. Fix it and run /butler reload.", NamedTextColor.RED));
            return;
        }
        if (playerName != null) {
            Player player = plugin.getServer().getPlayerExact(playerName);
            if (player == null) {
                sender.sendMessage(Component.text(playerName + " isn't online.", NamedTextColor.RED));
            } else {
                sender.sendMessage(Component.text(player.getName() + " in '" + player.getWorld().getName() + "': "
                        + String.join(", ", plugin.sender().describe(player))));
            }
            return;
        }
        sender.sendMessage(Component.text("Packs" + (plugin.checking() ? " (a check is running)" : "") + ":"));
        for (String name : config.packs().keySet()) {
            Outcome outcome = plugin.outcome(name);
            Component line;
            if (outcome == null) line = Component.text(" " + name + ": not checked yet", NamedTextColor.YELLOW);
            else if (outcome.live()) line = Component.text(" " + name + ": live, " + outcome.sha1()
                    + (outcome.buildAddress() ? ", per-build address" : ""), NamedTextColor.GREEN);
            else line = Component.text(" " + name + ": DEAD, " + outcome.deadReason(), NamedTextColor.RED);
            sender.sendMessage(line);
        }
        sender.sendMessage(Component.text("Worlds:"));
        config.worlds().forEach((world, packs) ->
                sender.sendMessage(Component.text(" " + world + ": " + (packs.isEmpty() ? "none" : String.join(", ", packs)))));
    }

    private static boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.sendMessage(Component.text("You don't have permission for that.", NamedTextColor.RED));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("recheck", "reload", "status").stream().filter(s -> s.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("status")) {
            return plugin.getServer().getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(java.util.Locale.ROOT).startsWith(args[1].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        return List.of();
    }
}
