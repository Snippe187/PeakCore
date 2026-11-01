package de.peakverse.core.plugin.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateCheckerTest {
    private HttpServer server;
    private String base;
    private byte[] jar = "PRETEND-JAR".getBytes(StandardCharsets.UTF_8);
    private String sha;
    private boolean withSha = true;
    private boolean withJar = true;

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @BeforeEach
    void setUp() throws Exception {
        sha = sha256(jar);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/repos/o/r/releases/latest", ex -> {
            String assets = (withJar ? "{\"name\":\"PeakCore-0.2.0.jar\",\"browser_download_url\":\"" + base + "/dl/PeakCore-0.2.0.jar\",\"size\":11}" : "")
                    + (withJar && withSha ? "," : "") + (withSha ? "{\"name\":\"PeakCore-0.2.0.jar.sha256\",\"browser_download_url\":\"" + base + "/dl/sha\"}" : "");
            reply(ex, 200, "{\"tag_name\":\"v0.2.0\",\"name\":\"Zweite\",\"body\":\"Neu: X\",\"html_url\":\"https://github.com/o/r/releases/tag/v0.2.0\",\"assets\":[" + assets + "]}");
        });
        server.createContext("/repos/o/leer/releases/latest", ex -> reply(ex, 404, "{}"));
        server.createContext("/dl/PeakCore-0.2.0.jar", ex -> reply(ex, 200, jar));
        server.createContext("/dl/sha", ex -> reply(ex, 200, (sha + "  PeakCore-0.2.0.jar\n").getBytes(StandardCharsets.UTF_8)));
        server.start();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        reply(ex, code, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, byte[] body) throws IOException {
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void readsTheLatestRelease() throws Exception {
        UpdateChecker.Release r = new UpdateChecker(base, "o/r").latest();
        assertEquals("0.2.0", r.version());
        assertEquals("PeakCore-0.2.0.jar", r.jarName());
        assertEquals("Neu: X", r.notes());
        assertTrue(r.shaUrl().endsWith("/dl/sha"));
    }

    @Test
    void downloadVerifiesTheChecksumAndReplacesTheTarget(@TempDir Path dir) throws Exception {
        UpdateChecker c = new UpdateChecker(base, "o/r");
        Path target = dir.resolve("update/PeakCore-0.1.0.jar");
        c.download(c.latest(), target);
        assertArrayEquals(jar, Files.readAllBytes(target));
        assertFalse(Files.exists(dir.resolve("update/PeakCore-0.1.0.jar.part")));
    }

    @Test
    void wrongChecksumInstallsNothing(@TempDir Path dir) throws Exception {
        sha = "0".repeat(64);
        UpdateChecker c = new UpdateChecker(base, "o/r");
        Path target = dir.resolve("PeakCore.jar");
        assertThrows(IOException.class, () -> c.download(c.latest(), target));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("PeakCore.jar.part")));
    }

    @Test
    void releaseWithoutChecksumIsRefused(@TempDir Path dir) throws Exception {
        withSha = false;
        UpdateChecker c = new UpdateChecker(base, "o/r");
        assertThrows(IOException.class, () -> c.download(c.latest(), dir.resolve("x.jar")));
    }

    @Test
    void releaseWithoutJarAndEmptyRepoAreReported() {
        withJar = false;
        assertThrows(IOException.class, () -> new UpdateChecker(base, "o/r").latest());
        assertThrows(IOException.class, () -> new UpdateChecker(base, "o/leer").latest());
    }

    @Test
    void foreignHostsAndBadReposAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new UpdateChecker(base, "../etc"));
        UpdateChecker.Release evil = new UpdateChecker.Release("9", "x", "", "PeakCore-9.jar", "https://evil.example.com/a.jar", "https://evil.example.com/a.sha256", 1, "");
        assertThrows(IOException.class, () -> new UpdateChecker("https://api.github.com", "o/r").download(evil, Path.of("x.jar")));
    }

    @Test
    void versionsCompareLikeNumbers() {
        assertTrue(UpdateChecker.compare("0.1.10", "0.1.9") > 0);
        assertTrue(UpdateChecker.compare("0.2.0", "0.1.99") > 0);
        assertEquals(0, UpdateChecker.compare("1.0", "1.0.0"));
        assertTrue(UpdateChecker.compare("1.0.0-beta", "1.0.0") < 0);
        assertTrue(UpdateChecker.compare("0.1.0", "0.1.1") < 0);
    }
}
