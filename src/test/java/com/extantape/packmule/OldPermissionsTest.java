package com.extantape.packmule;

import org.bukkit.permissions.Permissible;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OldPermissionsTest {

    /** A Permissible that has exactly the given nodes. */
    private static Permissible with(String... nodes) {
        Set<String> has = Set.of(nodes);
        return (Permissible) Proxy.newProxyInstance(Permissible.class.getClassLoader(), new Class<?>[]{Permissible.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> args[0] instanceof String s && has.contains(s);
                    case "toString" -> "someone";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void theNewNodeWorksAndLogsNothing() {
        List<String> log = new ArrayList<>();
        assertTrue(new OldPermissions(log::add).allowed(with("packmule.status"), "packmule.status"));
        assertTrue(log.isEmpty());
    }

    @Test
    void theOldNodeStillWorksAndLogsOnce() {
        List<String> log = new ArrayList<>();
        OldPermissions perms = new OldPermissions(log::add);
        Permissible old = with("resourcepackbutler.recheck");
        assertTrue(perms.allowed(old, "packmule.recheck"));
        assertTrue(perms.allowed(old, "packmule.recheck"));
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("grant packmule.recheck"), log.get(0));
    }

    @Test
    void anOldNodeOnlyCountsForItsOwnNewNode() {
        OldPermissions perms = new OldPermissions(line -> {});
        assertFalse(perms.allowed(with("resourcepackbutler.status"), "packmule.reload"));
        assertFalse(perms.allowed(with(), "packmule.status"));
    }
}
