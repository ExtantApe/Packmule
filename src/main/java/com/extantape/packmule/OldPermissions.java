package com.extantape.packmule;

import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permissible;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Permission checks that still accept the names from before the rename to Packmule (2026-11-09), for one release
 * (0.2.x): {@code resourcepackbutler.recheck} counts as {@code packmule.recheck}, and so on. Each old name logs once
 * per sender, saying what to grant instead.
 */
public final class OldPermissions {
    private final Consumer<String> warn;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public OldPermissions(Consumer<String> warn) { this.warn = warn; }

    public boolean allowed(Permissible who, String node) {
        if (who.hasPermission(node)) return true;
        if (!node.startsWith("packmule.")) return false;
        String old = "resourcepackbutler." + node.substring("packmule.".length());
        if (!who.hasPermission(old)) return false;
        String name = who instanceof CommandSender cs ? cs.getName() : String.valueOf(who);
        if (warned.add(name + " " + old))
            warn.accept(name + " uses the old permission " + old + ". It still works in 0.2.x but goes in the next release: grant " + node + " instead.");
        return true;
    }
}
