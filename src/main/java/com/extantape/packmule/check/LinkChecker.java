package com.extantape.packmule.check;

import com.extantape.packmule.config.PackmuleConfig;
import com.extantape.packmule.config.PackmuleConfig.PackSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Finds out what a pack's hash is and which address serves it, by downloading it. Plain Java, no server
 * needed, so it can run off the main thread and be tested on its own.
 */
public final class LinkChecker {
    /** Larger than any pack a client will take, so a misconfigured link can't stream forever. */
    static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final int MAX_HASH_FILE = 4096;

    private final Duration timeout;
    private final Duration deadline;

    public LinkChecker(Duration timeout) {
        this(timeout, Duration.ofMinutes(3));
    }

    LinkChecker(Duration timeout, Duration deadline) {
        this.timeout = timeout;
        this.deadline = deadline;
    }

    /**
     * What to send for a pack. A live pack has an address and hash. A dead one has neither, only a reason.
     * Notes are things worth logging even when the pack works, such as a broken hash file with a kept hash.
     */
    public record Outcome(String name, String url, String sha1, boolean buildAddress, String deadReason,
                          List<String> notes, String freshSha1) {
        public boolean live() {
            return deadReason == null;
        }
    }

    /**
     * Checks one pack. {@code lastGood} is the hash last confirmed for a moving address, or null; it's used
     * when the hash file can't be read.
     */
    public Outcome check(PackSource source, String lastGood) {
        List<String> notes = new ArrayList<>();
        String addressProblem = PackmuleConfig.addressProblem(source.url());
        if (addressProblem != null) return dead(source, "bad url '" + source.url() + "', because " + addressProblem, notes);
        String sha1;
        String fresh = null;
        if (!source.movingAddress()) {
            sha1 = source.sha1();
        } else {
            String read;
            try {
                read = readHashFile(source.sha1Url());
            } catch (CheckFailed e) {
                read = null;
                notes.add("Couldn't read its hash file " + source.sha1Url() + ": " + e.getMessage() + ".");
            }
            if (read != null) {
                sha1 = read;
                fresh = read;
            } else if (lastGood != null) {
                sha1 = lastGood;
                notes.add("Keeping the last good hash, " + lastGood + ".");
            } else {
                return dead(source, "its hash file can't be read and there's no earlier hash to fall back on", notes);
            }
        }

        List<String> candidates = new ArrayList<>();
        if (source.movingAddress()) candidates.add(PackmuleConfig.buildAddress(source.url(), sha1));
        candidates.add(source.url());

        String reason = null;
        for (int i = 0; i < candidates.size(); i++) {
            String url = candidates.get(i);
            try {
                String got = downloadSha1(url);
                if (got.equals(sha1)) {
                    boolean build = source.movingAddress() ? i == 0 : PackmuleConfig.sha1FromAddress(url) != null;
                    if (source.movingAddress() && i > 0) {
                        notes.add("No per-build address at " + candidates.get(0) + ", so players get the moving address.");
                    }
                    return new Outcome(source.name(), url, sha1, build, null, List.copyOf(notes), fresh);
                }
                reason = "wrong hash: " + url + " serves " + got + ", expected " + sha1;
            } catch (CheckFailed e) {
                reason = e.getMessage() + " (" + url + ")";
            }
        }
        // A moving address whose hash file was fine but whose file didn't match: don't remember the hash.
        return dead(source, reason, notes);
    }

    private static Outcome dead(PackSource source, String reason, List<String> notes) {
        return new Outcome(source.name(), null, null, false, reason, List.copyOf(notes), null);
    }

    /** The first 40-hex word of the hash file, lowercased. sha1sum's "hash  filename" form works too. */
    String readHashFile(String url) throws CheckFailed {
        byte[] body = fetch(url, MAX_HASH_FILE, null);
        String text = new String(body, StandardCharsets.UTF_8).trim();
        String first = text.isEmpty() ? "" : text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!PackmuleConfig.isSha1(first)) throw new CheckFailed("it doesn't hold a SHA-1");
        return first;
    }

    String downloadSha1(String url) throws CheckFailed {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        fetch(url, MAX_BYTES, digest);
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Downloads into the digest, or returns the body when there's no digest. */
    private byte[] fetch(String url, long limit, MessageDigest digest) throws CheckFailed {
        String problem = PackmuleConfig.addressProblem(url);
        if (problem != null) throw new CheckFailed("bad address, because " + problem);
        HttpURLConnection connection = null;
        long started = System.nanoTime();
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout((int) timeout.toMillis());
            connection.setReadTimeout((int) timeout.toMillis());
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Packmule");
            int code = connection.getResponseCode();
            if (code != 200) throw new CheckFailed("HTTP " + code);
            var buffer = new java.io.ByteArrayOutputStream();
            long total = 0;
            byte[] chunk = new byte[64 * 1024];
            try (InputStream in = connection.getInputStream()) {
                for (int n; (n = in.read(chunk)) != -1; ) {
                    total += n;
                    if (total > limit) throw new CheckFailed("more than " + (limit / 1024 / 1024 > 0 ? limit / 1024 / 1024 + " MB" : limit + " bytes"));
                    if (System.nanoTime() - started > deadline.toNanos()) throw new CheckFailed("download took longer than " + deadline.toSeconds() + " seconds");
                    if (digest != null) digest.update(chunk, 0, n);
                    else buffer.write(chunk, 0, n);
                }
            }
            return buffer.toByteArray();
        } catch (SocketTimeoutException e) {
            throw new CheckFailed("timed out after " + timeout.toSeconds() + " seconds");
        } catch (java.net.UnknownHostException e) {
            throw new CheckFailed("unknown host " + e.getMessage());
        } catch (java.net.ConnectException e) {
            throw new CheckFailed("can't connect");
        } catch (IOException | IllegalArgumentException e) {
            throw new CheckFailed(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static final class CheckFailed extends Exception {
        CheckFailed(String message) {
            super(message);
        }
    }
}
