package com.extantape.packmule.check;

import com.extantape.packmule.config.PackmuleConfig.Check;
import com.extantape.packmule.config.PackmuleConfig.PackSource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A pack behind a players-only gate (D219). A made-up gate on 127.0.0.1 admits only the right token, like Leaf
 * Juice's; a second server stands in for the public address and must never see the token.
 */
class GatedCheckTest {
    private static final String TOKEN = "ab".repeat(32);
    private HttpServer gate, outside;
    private String gateBase, outsideBase;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final List<String> outsideTokens = new CopyOnWriteArrayList<>();
    private final LinkChecker checker = new LinkChecker(Duration.ofSeconds(2));
    @TempDir Path dir;

    @BeforeEach
    void start() throws Exception {
        gate = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        gate.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/redirect")) {
                    exchange.getResponseHeaders().add("Location", outsideBase + "/caught");
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
                byte[] body = files.get(path);
                if (!TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Pack-Check-Token")) || body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        gate.start();
        outside = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        outside.createContext("/", exchange -> {
            try (exchange) {
                String t = exchange.getRequestHeaders().getFirst("X-Pack-Check-Token");
                if (t != null) outsideTokens.add(t);
                exchange.sendResponseHeaders(404, -1);
            }
        });
        outside.start();
        gateBase = "http://127.0.0.1:" + gate.getAddress().getPort() + "/";
        outsideBase = "http://127.0.0.1:" + outside.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        gate.stop(0);
        outside.stop(0);
    }

    private Path tokenFile(String text, String mode) throws Exception {
        Path f = dir.resolve("server.token");
        Files.writeString(f, text, StandardCharsets.US_ASCII);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString(mode));
        return f;
    }

    private String put(String name) throws Exception {
        byte[] body = ("PK-fake-" + name).getBytes(StandardCharsets.UTF_8);
        String h = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(body));
        files.put("/tok/" + h + ".zip", body);
        return h;
    }

    private PackSource gated(String h, Path token) {
        String pub = "https://join.example.invalid/zz/";
        return new PackSource("zz", pub + "tok/" + h + ".zip", h, null, new Check(pub, gateBase, token.toString()));
    }

    @Test
    void checksAtTheLocalAddressWithTheTokenAndPlayersKeepThePublicOne() throws Exception {
        String h = put("a");
        var outcome = checker.check(gated(h, tokenFile(TOKEN + "\n", "rw-------")), null);
        assertTrue(outcome.live(), outcome.deadReason());
        assertEquals("https://join.example.invalid/zz/tok/" + h + ".zip", outcome.url());
    }

    @Test
    void withoutTheCheckBlockTheGateRefusesTheCheck() throws Exception {
        String h = put("a");
        var outcome = checker.check(new PackSource("zz", gateBase + "tok/" + h + ".zip", h, null), null);
        assertFalse(outcome.live());
    }

    @Test
    void aWrongTokenIsDead() throws Exception {
        String h = put("a");
        var outcome = checker.check(gated(h, tokenFile("cd".repeat(32), "rw-------")), null);
        assertFalse(outcome.live());
        assertFalse(outcome.deadReason().contains("cd".repeat(32)));
    }

    @Test
    void aTokenFileOthersCanReadIsRefusedAndNothingIsSent() throws Exception {
        String h = put("a");
        var outcome = checker.check(gated(h, tokenFile(TOKEN, "rw-r--r--")), null);
        assertFalse(outcome.live());
        assertTrue(outcome.deadReason().contains("mode 600"), outcome.deadReason());
        assertFalse(outcome.deadReason().contains(TOKEN));
    }

    @Test
    void aMissingOrMalformedTokenFileIsRefused() throws Exception {
        String h = put("a");
        var missing = checker.check(gated(h, dir.resolve("nope.token")), null);
        assertFalse(missing.live());
        assertTrue(missing.deadReason().contains("doesn't exist"), missing.deadReason());
        var shortToken = checker.check(gated(h, tokenFile("abc", "rw-------")), null);
        assertFalse(shortToken.live());
        assertTrue(shortToken.deadReason().contains("doesn't hold a token"), shortToken.deadReason());
    }

    @Test
    void aLinkedTokenFileIsRefused() throws Exception {
        String h = put("a");
        Path real = tokenFile(TOKEN, "rw-------");
        Path link = dir.resolve("link.token");
        Files.createSymbolicLink(link, real);
        var outcome = checker.check(gated(h, link), null);
        assertFalse(outcome.live());
        assertTrue(outcome.deadReason().contains("isn't a plain file"), outcome.deadReason());
    }

    @Test
    void theTokenNeverFollowsARedirect() throws Exception {
        Path token = tokenFile(TOKEN, "rw-------");
        String pub = "https://join.example.invalid/zz/";
        var source = new PackSource("zz", pub + "redirect", "e".repeat(40), null, new Check(pub, gateBase, token.toString()));
        var outcome = checker.check(source, null);
        assertFalse(outcome.live());
        assertTrue(outsideTokens.isEmpty(), "the token reached another address");
    }

    @Test
    void addressesOutsideThePublicPrefixGoWithoutTheToken() throws Exception {
        Path token = tokenFile(TOKEN, "rw-------");
        String h = "f".repeat(40);
        var source = new PackSource("zz", outsideBase + "/other/" + h + ".zip", h, null,
                new Check("https://join.example.invalid/zz/", gateBase, token.toString()));
        checker.check(source, null);
        assertTrue(outsideTokens.isEmpty(), "the token went to an address outside the public prefix");
    }
}
