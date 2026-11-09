package com.extantape.packmule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MigrationTest {

    @Test
    void copiesTheOldFolderWhenTheNewOneIsMissing(@TempDir Path plugins) throws Exception {
        Path old = plugins.resolve("ResourcePackButler");
        Files.createDirectories(old.resolve("sub"));
        Files.writeString(old.resolve("config.yml"), "profiles: {}\n");
        Files.writeString(old.resolve("changes.log"), "{\"a\":1}\n");
        Files.writeString(old.resolve("sub/x.json"), "[]");

        String msg = Migration.copyIfMissing(old, plugins.resolve("Packmule"));

        assertNotNull(msg);
        assertEquals("profiles: {}\n", Files.readString(plugins.resolve("Packmule/config.yml")));
        assertEquals("{\"a\":1}\n", Files.readString(plugins.resolve("Packmule/changes.log")));
        assertEquals("[]", Files.readString(plugins.resolve("Packmule/sub/x.json")));
        assertTrue(Files.exists(old.resolve("config.yml")), "the old folder is left as a backup");
        assertFalse(Files.exists(plugins.resolve("Packmule.migrating")));
    }

    @Test
    void neverOverwritesAnExistingNewFolder(@TempDir Path plugins) throws Exception {
        Files.createDirectories(plugins.resolve("ResourcePackButler"));
        Files.writeString(plugins.resolve("ResourcePackButler/config.yml"), "old");
        Files.createDirectories(plugins.resolve("Packmule"));
        Files.writeString(plugins.resolve("Packmule/config.yml"), "new");

        assertNull(Migration.copyIfMissing(plugins.resolve("ResourcePackButler"), plugins.resolve("Packmule")));
        assertEquals("new", Files.readString(plugins.resolve("Packmule/config.yml")));
    }

    @Test
    void doesNothingOnAFreshServer(@TempDir Path plugins) {
        assertNull(Migration.copyIfMissing(plugins.resolve("ResourcePackButler"), plugins.resolve("Packmule")));
        assertFalse(Files.exists(plugins.resolve("Packmule")));
    }

    @Test
    void clearsAHalfFinishedCopyFromACrash(@TempDir Path plugins) throws Exception {
        Files.createDirectories(plugins.resolve("ResourcePackButler"));
        Files.writeString(plugins.resolve("ResourcePackButler/config.yml"), "real");
        Files.createDirectories(plugins.resolve("Packmule.migrating"));
        Files.writeString(plugins.resolve("Packmule.migrating/junk"), "half");

        assertNotNull(Migration.copyIfMissing(plugins.resolve("ResourcePackButler"), plugins.resolve("Packmule")));
        assertEquals("real", Files.readString(plugins.resolve("Packmule/config.yml")));
        assertFalse(Files.exists(plugins.resolve("Packmule/junk")));
    }
}
