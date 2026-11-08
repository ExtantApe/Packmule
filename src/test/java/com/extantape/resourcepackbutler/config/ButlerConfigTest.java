package com.extantape.resourcepackbutler.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ButlerConfigTest {
    private static final String H = "0123456789abcdef0123456789abcdef01234567";

    private static ButlerConfig.Result parse(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return ButlerConfig.parse(config);
    }

    @Test
    void readsAllThreeKindsOfPack() throws Exception {
        var result = parse("""
                packs:
                  build: {url: "https://h/t/%s.zip"}
                  moving: {url: "https://h/t/pack.zip", sha1-url: "https://h/t/pack.zip.sha1"}
                  fixed: {url: "https://h/x.zip", sha1: "%s"}
                worlds:
                  w1: [build, fixed]
                  w2: []
                """.formatted(H.toUpperCase(), H));
        assertTrue(result.ok(), result.errors().toString());
        var packs = result.config().packs();
        assertEquals(H, packs.get("build").sha1());
        assertTrue(packs.get("moving").movingAddress());
        assertEquals(H, packs.get("fixed").sha1());
        assertEquals(List.of("build", "fixed"), result.config().worlds().get("w1"));
        assertEquals(List.of(), result.config().worlds().get("w2"));
        assertEquals(60, result.config().answerTimeoutSeconds());
        assertEquals(2, result.config().downloadRetries());
    }

    @Test
    void rejectsBrokenEntries() throws Exception {
        var result = parse("""
                packs:
                  nohash: {url: "https://h/pack.zip"}
                  badurl: {url: "ftp://h/x.zip", sha1: "%s"}
                  both: {url: "https://h/a.zip", sha1: "%s", sha1-url: "https://h/a.sha1"}
                  mismatch: {url: "https://h/%s.zip", sha1: "%s"}
                  short: {url: "https://h/a.zip", sha1: "abc"}
                worlds:
                  w1: [missing]
                  w2: main
                  w3: [nohash, nohash]
                answer-timeout-seconds: lots
                """.formatted(H, H, H, "f".repeat(40)));
        assertFalse(result.ok());
        String all = String.join("\n", result.errors());
        assertFalse(all.contains("'badurl'"), "a malformed url is a dead link, not a config error");
        for (String part : List.of("'nohash' needs a hash", "both sha1 and sha1-url", "'mismatch'", "'short'",
                "'missing'", "World 'w2'", "twice", "answer-timeout-seconds")) {
            assertTrue(all.contains(part), "missing error about " + part + " in:\n" + all);
        }
    }

    @Test
    void buildAddressSitsNextToTheMovingOne() {
        assertEquals("https://h:8080/tok/" + H + ".zip", ButlerConfig.buildAddress("https://h:8080/tok/pack.zip", H));
        assertEquals(H, ButlerConfig.sha1FromAddress("https://h/tok/" + H + ".zip"));
        assertNull(ButlerConfig.sha1FromAddress("https://h/tok/pack.zip"));
        assertNotNull(ButlerConfig.addressProblem("not a url"));
        assertNotNull(ButlerConfig.addressProblem("https:///nohost"));
        assertNull(ButlerConfig.addressProblem("http://127.0.0.1:9/x.zip"));
    }

    @Test
    void shippedConfigMatchesTheDefaultsAndRenders() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            shipped.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        var result = ButlerConfig.parse(shipped);
        assertTrue(result.ok(), result.errors().toString());
        assertEquals(ButlerConfig.Messages.DEFAULTS, result.config().messages(), "config.yml and Messages.DEFAULTS have drifted apart");

        var mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        var seconds = net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("seconds", "60");
        var m = result.config().messages();
        for (String text : List.of(m.prompt(), m.declined(), m.noAnswer(), m.downloadFailed(), m.unavailable())) {
            String rendered = plain.serialize(mm.deserialize(text, seconds));
            assertFalse(rendered.contains("<"), "unrendered tag in: " + rendered);
        }
        assertTrue(plain.serialize(mm.deserialize(m.noAnswer(), seconds)).contains("within 60 seconds"));
    }

    @Test
    void leftOutMessagesUseTheirDefaults() throws Exception {
        var result = parse("messages:\n  prompt: \"Custom\"\n");
        assertTrue(result.ok());
        assertEquals("Custom", result.config().messages().prompt());
        assertEquals(ButlerConfig.Messages.DEFAULTS.declined(), result.config().messages().declined());
    }
}
