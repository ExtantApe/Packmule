package com.extantape.resourcepackbutler.check;

import com.extantape.resourcepackbutler.config.ButlerConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The last hash confirmed for each moving address, in last-good-hashes.yml, so a broken hash file after a
 * restart still has something to fall back on. Keyed by the hash file's address: a new address starts over.
 */
public final class HashStore {
    private final Path file;
    private final Map<String, String> hashes = new HashMap<>();

    public HashStore(Path file) {
        this.file = file;
    }

    /** Returns a problem to log, or null. An unreadable file is treated as empty. */
    public synchronized String load() {
        hashes.clear();
        if (!Files.exists(file)) return null;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (IOException | InvalidConfigurationException e) {
            return "Couldn't read " + file.getFileName() + ", so no earlier hashes are known: " + e.getMessage();
        }
        for (String key : yaml.getKeys(false)) {
            var section = yaml.getConfigurationSection(key);
            if (section == null) continue;
            String address = section.getString("sha1-url");
            String sha1 = section.getString("sha1");
            if (address != null && ButlerConfig.isSha1(sha1)) hashes.put(address, sha1);
        }
        return null;
    }

    public synchronized String get(String sha1Url) {
        return hashes.get(sha1Url);
    }

    /** Remembers a confirmed hash. Returns a problem to log, or null. */
    public synchronized String put(String sha1Url, String sha1) {
        if (sha1.equals(hashes.get(sha1Url))) return null;
        hashes.put(sha1Url, sha1);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(java.util.List.of(
                "Written by Resource Pack Butler: the last hash each hash file gave that matched its pack.",
                "Safe to delete; it's only used when a hash file can't be read."));
        int i = 0;
        for (var entry : hashes.entrySet()) {
            String key = "hash-" + (i++);
            yaml.set(key + ".sha1-url", entry.getKey());
            yaml.set(key + ".sha1", entry.getValue());
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, yaml.saveToString());
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return null;
        } catch (IOException e) {
            return "Couldn't save " + file.getFileName() + ": " + e.getMessage();
        }
    }
}
