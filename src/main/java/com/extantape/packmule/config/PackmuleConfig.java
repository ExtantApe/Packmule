package com.extantape.packmule.config;

import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** config.yml, checked. A config with any error is never used: the caller keeps the last good one, or sends nothing. */
public record PackmuleConfig(
        Map<String, PackSource> packs,
        Map<String, List<String>> worlds,
        Messages messages,
        int answerTimeoutSeconds,
        int downloadRetries,
        int checkTimeoutSeconds) {

    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern BUILD_FILE = Pattern.compile("([0-9a-fA-F]{40})\\.zip");

    /**
     * Where a pack lives and how its hash is known. Exactly one of sha1 and sha1Url is set, except for a
     * malformed url, which has neither and is always reported dead.
     */
    public record PackSource(String name, String url, String sha1, String sha1Url) {
        public boolean movingAddress() {
            return sha1Url != null;
        }
    }

    /**
     * What players see, in MiniMessage. {@code <seconds>} in no-answer is the timeout. Defaults match config.yml;
     * a message left out of the config uses its default, so an old config still reads well.
     */
    public record Messages(String prompt, String declined, String noAnswer, String downloadFailed, String unavailable) {
        public static final Messages DEFAULTS = new Messages(
                "This world has its own resource pack. Choose <b>Proceed</b> to download it and keep playing.",
                "<b>This server needs its resource packs to play.</b><newline><newline>"
                        + "The download was declined. Rejoin and choose <b>Proceed</b> when asked.<newline><newline>"
                        + "<gray>Not asked at all? Your game is set to refuse server packs. In Multiplayer, select this server, "
                        + "choose Edit, and set Server Resource Packs to Prompt or Enabled.</gray>",
                "<b>The resource pack prompt timed out.</b><newline><newline>"
                        + "It wasn't answered within <seconds> seconds. Rejoin and choose <b>Proceed</b> when asked.",
                "<yellow>This world's resource pack couldn't be downloaded, so some things may look different. "
                        + "Rejoin to try again.</yellow>",
                "<yellow>Part of this world's resource pack isn't available right now, so some things may look different. "
                        + "Staff have been told.</yellow>");
    }

    public record Result(PackmuleConfig config, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public static boolean isSha1(String value) {
        return value != null && SHA1.matcher(value).matches();
    }

    /** The SHA-1 in a per-build address's file name, lowercased, or null. */
    public static String sha1FromAddress(String url) {
        String path;
        try {
            path = URI.create(url).getPath();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (path == null) return null;
        String file = path.substring(path.lastIndexOf('/') + 1);
        var matcher = BUILD_FILE.matcher(file);
        return matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** The per-build address next to a moving one: same folder, file named after the hash. */
    public static String buildAddress(String movingUrl, String sha1) {
        URI uri = URI.create(movingUrl);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String folder = path.substring(0, path.lastIndexOf('/') + 1);
        String start = uri.getScheme() + "://" + uri.getRawAuthority();
        return start + folder + sha1 + ".zip" + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    /** Null when the address is usable, otherwise why not. */
    public static String addressProblem(String url) {
        if (url == null || url.isBlank()) return "it's empty";
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return "it isn't a valid address";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return "it doesn't start with http:// or https://";
        if (uri.getHost() == null || uri.getHost().isBlank()) return "it has no host name";
        return null;
    }

    public static Result parse(ConfigurationSection root) {
        List<String> errors = new ArrayList<>();
        Map<String, PackSource> packs = new LinkedHashMap<>();
        ConfigurationSection packSection = root.getConfigurationSection("packs");
        if (root.contains("packs") && packSection == null) errors.add("packs must be a map of pack names.");
        if (packSection != null) {
            for (String name : packSection.getKeys(false)) {
                ConfigurationSection pack = packSection.getConfigurationSection(name);
                if (pack == null) {
                    errors.add("Pack '" + name + "' must be a map with a url.");
                    continue;
                }
                PackSource source = parsePack(name, pack, errors);
                if (source != null) packs.put(name, source);
            }
        }

        Map<String, List<String>> worlds = new LinkedHashMap<>();
        ConfigurationSection worldSection = root.getConfigurationSection("worlds");
        if (root.contains("worlds") && worldSection == null) errors.add("worlds must be a map of world names to lists of packs.");
        if (worldSection != null) {
            for (String world : worldSection.getKeys(false)) {
                Object value = worldSection.get(world);
                if (!(value instanceof List<?> list)) {
                    errors.add("World '" + world + "' must have a list of pack names, like [main] or [] for none.");
                    continue;
                }
                List<String> names = new ArrayList<>();
                for (Object item : list) {
                    String packName = String.valueOf(item);
                    if (!(item instanceof String)) {
                        errors.add("World '" + world + "' lists '" + packName + "', which isn't a pack name.");
                    } else if (packSection == null || !packSection.contains(packName)) {
                        errors.add("World '" + world + "' lists pack '" + packName + "', which isn't defined under packs.");
                    } else if (names.contains(packName)) {
                        errors.add("World '" + world + "' lists pack '" + packName + "' twice.");
                    } else {
                        names.add(packName);
                    }
                }
                worlds.put(world, Collections.unmodifiableList(names));
            }
        }

        int answer = intSetting(root, "answer-timeout-seconds", 60, 5, 600, errors);
        int retries = intSetting(root, "download-retries", 2, 0, 10, errors);
        int timeout = intSetting(root, "check-timeout-seconds", 10, 1, 120, errors);
        Messages d = Messages.DEFAULTS;
        Messages messages = new Messages(
                root.getString("messages.prompt", d.prompt()),
                root.getString("messages.declined", d.declined()),
                root.getString("messages.no-answer", d.noAnswer()),
                root.getString("messages.download-failed", d.downloadFailed()),
                root.getString("messages.unavailable", d.unavailable()));

        PackmuleConfig config = new PackmuleConfig(Collections.unmodifiableMap(packs), Collections.unmodifiableMap(worlds),
                messages, answer, retries, timeout);
        return new Result(config, List.copyOf(errors));
    }

    private static PackSource parsePack(String name, ConfigurationSection pack, List<String> errors) {
        String url = pack.getString("url");
        if (url == null || url.isBlank()) {
            errors.add("Pack '" + name + "' has no url.");
            return null;
        }
        url = url.trim();
        // A malformed address is a dead link, not a broken config: the link check reports it and the rest still works.
        if (addressProblem(url) != null) return new PackSource(name, url, null, null);
        String sha1 = pack.getString("sha1");
        String sha1Url = pack.getString("sha1-url");
        if (sha1 != null) sha1 = sha1.trim().toLowerCase(Locale.ROOT);
        if (sha1Url != null) sha1Url = sha1Url.trim();
        String fromName = sha1FromAddress(url);

        if (sha1 != null && sha1Url != null) {
            errors.add("Pack '" + name + "' has both sha1 and sha1-url. Keep one.");
            return null;
        }
        if (sha1 != null) {
            if (!isSha1(sha1)) {
                errors.add("Pack '" + name + "': sha1 must be 40 hex characters.");
                return null;
            }
            if (fromName != null && !fromName.equals(sha1)) {
                errors.add("Pack '" + name + "': sha1 doesn't match the hash in the url's file name.");
                return null;
            }
            return new PackSource(name, url, sha1, null);
        }
        if (sha1Url != null) return new PackSource(name, url, null, sha1Url); // A bad one is reported by the check.
        if (fromName != null) return new PackSource(name, url, fromName, null);
        errors.add("Pack '" + name + "' needs a hash: use a per-build url ending in <sha1>.zip, or add sha1 or sha1-url.");
        return null;
    }

    private static int intSetting(ConfigurationSection root, String key, int fallback, int min, int max, List<String> errors) {
        if (!root.contains(key)) return fallback;
        Object value = root.get(key);
        if (!(value instanceof Integer number) || number < min || number > max) {
            errors.add(key + " must be a whole number from " + min + " to " + max + ".");
            return fallback;
        }
        return number;
    }
}
