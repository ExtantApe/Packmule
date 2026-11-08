package com.extantape.resourcepackbutler.command;

import com.extantape.resourcepackbutler.ResourcePackButler;
import com.extantape.resourcepackbutler.check.LinkChecker.Outcome;
import com.extantape.resourcepackbutler.config.ButlerConfig;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** /butler recheck, reload and status [player], registered through Paper's Brigadier command API. */
public final class ButlerCommand {
    private static final String RECHECK = "resourcepackbutler.recheck";
    private static final String RELOAD = "resourcepackbutler.reload";
    private static final String STATUS = "resourcepackbutler.status";

    private final ResourcePackButler plugin;

    public ButlerCommand(ResourcePackButler plugin) {
        this.plugin = plugin;
    }

    public void register(Commands commands) {
        var root = Commands.literal("butler")
                .requires(source -> has(source, RECHECK) || has(source, RELOAD) || has(source, STATUS))
                .executes(ctx -> reply(ctx.getSource().getSender(), "Usage: /butler <recheck|reload|status [player]>"))
                .then(Commands.literal("recheck").requires(source -> has(source, RECHECK))
                        .executes(ctx -> recheck(ctx.getSource().getSender())))
                .then(Commands.literal("reload").requires(source -> has(source, RELOAD))
                        .executes(ctx -> reload(ctx.getSource().getSender())))
                .then(Commands.literal("status").requires(source -> has(source, STATUS))
                        .executes(ctx -> status(ctx.getSource().getSender()))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String typed = builder.getRemainingLowerCase();
                                    plugin.getServer().getOnlinePlayers().stream().map(Player::getName)
                                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> playerStatus(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player")))));
        commands.register(root.build(), "Re-check pack links, reload the config, or show what Butler is sending.",
                List.of("resourcepackbutler", "rpb"));
    }

    private static boolean has(CommandSourceStack source, String permission) {
        return source.getSender().hasPermission(permission);
    }

    private static int reply(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text));
        return Command.SINGLE_SUCCESS;
    }

    private int recheck(CommandSender sender) {
        if (plugin.config() == null) {
            sender.sendMessage(Component.text("config.yml has problems, so there's nothing to check. Fix it and run /butler reload.", NamedTextColor.RED));
            return 0;
        }
        reply(sender, "Re-checking every pack link. Players online keep their packs until they change worlds or rejoin.");
        plugin.startCheck(sender, false);
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandSender sender) {
        List<String> errors = plugin.reload();
        if (errors.isEmpty()) {
            return reply(sender, "Reloaded, and re-checking links. Players online keep their packs until they change worlds or rejoin.");
        }
        sender.sendMessage(Component.text("config.yml has problems, so nothing changed:", NamedTextColor.RED));
        for (String error : errors) sender.sendMessage(Component.text(" - " + error, NamedTextColor.RED));
        return 0;
    }

    private int playerStatus(CommandSender sender, String name) {
        Player player = plugin.getServer().getPlayerExact(name);
        if (player == null) {
            sender.sendMessage(Component.text(name + " isn't online.", NamedTextColor.RED));
            return 0;
        }
        return reply(sender, player.getName() + " in '" + player.getWorld().getName() + "': " + String.join(", ", plugin.sender().describe(player)));
    }

    private int status(CommandSender sender) {
        ButlerConfig config = plugin.config();
        if (config == null) {
            sender.sendMessage(Component.text("No config loaded: config.yml has problems. Fix it and run /butler reload.", NamedTextColor.RED));
            return 0;
        }
        reply(sender, "Packs" + (plugin.checking() ? " (a check is running)" : "") + ":");
        for (String name : config.packs().keySet()) {
            Component line = switch (plugin.outcome(name)) {
                case null -> Component.text(" " + name + ": not checked yet", NamedTextColor.YELLOW);
                case Outcome o when o.live() -> Component.text(" " + name + ": live, " + o.sha1()
                        + (o.buildAddress() ? ", per-build address" : ""), NamedTextColor.GREEN);
                case Outcome o -> Component.text(" " + name + ": DEAD, " + o.deadReason(), NamedTextColor.RED);
            };
            sender.sendMessage(line);
        }
        reply(sender, "Worlds:");
        config.worlds().forEach((world, packs) -> reply(sender, " " + world + ": " + (packs.isEmpty() ? "none" : String.join(", ", packs))));
        return Command.SINGLE_SUCCESS;
    }
}
