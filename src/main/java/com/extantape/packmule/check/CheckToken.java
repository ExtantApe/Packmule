package com.extantape.packmule.check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the pack-check token for a gated pack (D219). The file must be a plain file, not a link, owned by the user
 * the server runs as, readable by nobody else, and hold exactly 64 hex characters. Anything else is refused, so a
 * gated pack fails its check instead of going out with a token someone else could have read or swapped.
 * The token itself never appears in a message.
 */
final class CheckToken {
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{64}");
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private CheckToken() {
    }

    static String read(String file) throws LinkChecker.CheckFailed {
        String shown = "check token file " + file;
        Path path;
        try {
            path = Path.of(file.startsWith("~/") ? System.getProperty("user.home") + file.substring(1) : file);
        } catch (RuntimeException e) {
            throw new LinkChecker.CheckFailed(shown + " isn't a valid path");
        }
        try {
            PosixFileAttributes a = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile()) throw new LinkChecker.CheckFailed(shown + " isn't a plain file");
            if (!a.owner().getName().equals(System.getProperty("user.name"))) throw new LinkChecker.CheckFailed(shown + " belongs to another user");
            if (!OWNER_ONLY.containsAll(a.permissions())) throw new LinkChecker.CheckFailed(shown + " can be read by others; it must be mode 600");
            if (a.size() > 256) throw new LinkChecker.CheckFailed(shown + " doesn't hold a token");
        } catch (UnsupportedOperationException e) {
            throw new LinkChecker.CheckFailed(shown + " can't be checked on this file system");
        } catch (java.nio.file.NoSuchFileException e) {
            throw new LinkChecker.CheckFailed(shown + " doesn't exist");
        } catch (IOException e) {
            throw new LinkChecker.CheckFailed(shown + " can't be read");
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.US_ASCII).strip();
        } catch (IOException | RuntimeException e) {
            throw new LinkChecker.CheckFailed(shown + " can't be read");
        }
        if (!TOKEN.matcher(text).matches()) throw new LinkChecker.CheckFailed(shown + " doesn't hold a token");
        return text;
    }
}
