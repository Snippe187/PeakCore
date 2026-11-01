package de.peakverse.core.client.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModUpdaterTest {
    private HttpServer server;
    private String base;
    private final byte[] jar = "PRETEND-MOD".getBytes(StandardCharsets.UTF_8);
    private String sha;
    private boolean withSha = true;

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @BeforeEach
    void setUp() throws Exception {
        sha = sha256(jar);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/repos/o/r/releases/latest", ex -> {
            String assets = "{\"name\":\"PeakCore-0.2.0.jar\",\"browser_download_url\":\"" + base + "/dl/plugin\"},"
                    + "{\"name\":\"PeakCore-Mod-0.2.0.jar\",\"browser_download_url\":\"" + base + "/dl/core\"},"
                    + "{\"name\":\"PeakSound-Mod-0.2.0.jar\",\"browser_download_url\":\"" + base + "/dl/sound\"},"
                    + "{\"name\":\"PeakSound-Mod-0.2.0.jar.sha256\",\"browser_download_url\":\"" + base + "/dl/sha\"}"
                    + (withSha ? ",{\"name\":\"PeakCore-Mod-0.2.0.jar.sha256\",\"browser_download_url\":\"" + base + "/dl/sha\"}" : "");
            reply(ex, 200, "{\"tag_name\":\"v0.2.0\",\"html_url\":\"https://github.com/o/r/releases/tag/v0.2.0\",\"assets\":[" + assets + "]}");
        });
        server.createContext("/repos/o/leer/releases/latest", ex -> reply(ex, 404, "{}"));
        server.createContext("/dl/core", ex -> reply(ex, 200, jar));
        server.createContext("/dl/sound", ex -> reply(ex, 200, jar));
        server.createContext("/dl/sha", ex -> reply(ex, 200, (sha + "  x.jar\n").getBytes(StandardCharsets.UTF_8)));
        server.start();
    }

    private static void reply(HttpExchange ex, int code, String body) throws IOException {
        reply(ex, code, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange ex, int code, byte[] body) throws IOException {
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void findsOnlyNewerModsThatAreInstalled() throws Exception {
        ModUpdater u = new ModUpdater(base, "o/r");
        List<ModUpdater.Available> a = u.check(Map.of("peakcore", "0.1.0", "peaksound", "0.2.0", "texturepeak", "0.1.0"));
        assertEquals(1, a.size());
        assertEquals("peakcore", a.get(0).modId());
        assertEquals("0.2.0", a.get(0).asset().version());
        assertEquals("PeakCore-Mod-0.2.0.jar", a.get(0).asset().fileName());
        assertTrue(u.check(Map.of("peakcore", "0.2.0", "peaksound", "0.2.0")).isEmpty());
        assertEquals(2, u.check(Map.of("peakcore", "0.1.0", "peaksound", "0.1.9")).size());
    }

    @Test
    void pluginJarIsNotTakenForAMod() throws Exception {
        for (ModUpdater.Available a : new ModUpdater(base, "o/r").check(Map.of("peakcore", "0.0.1"))) assertTrue(a.asset().fileName().contains("-Mod-"));
    }

    @Test
    void stageVerifiesTheChecksum(@TempDir Path dir) throws Exception {
        ModUpdater u = new ModUpdater(base, "o/r");
        ModUpdater.Asset asset = u.check(Map.of("peakcore", "0.1.0")).get(0).asset();
        Path f = u.stage(asset, dir.resolve("stage"));
        assertArrayEquals(jar, Files.readAllBytes(f));
        assertFalse(Files.exists(dir.resolve("stage/PeakCore-Mod-0.2.0.jar.part")));
    }

    @Test
    void wrongChecksumInstallsNothing(@TempDir Path dir) throws Exception {
        ModUpdater u = new ModUpdater(base, "o/r");
        ModUpdater.Asset asset = u.check(Map.of("peakcore", "0.1.0")).get(0).asset();
        sha = "0".repeat(64);
        assertThrows(IOException.class, () -> u.stage(asset, dir));
        assertFalse(Files.exists(dir.resolve("PeakCore-Mod-0.2.0.jar")));
        assertFalse(Files.exists(dir.resolve("PeakCore-Mod-0.2.0.jar.part")));
    }

    @Test
    void releaseWithoutChecksumIsRefused(@TempDir Path dir) throws Exception {
        withSha = false;
        ModUpdater u = new ModUpdater(base, "o/r");
        ModUpdater.Asset asset = u.check(Map.of("peakcore", "0.1.0")).get(0).asset();
        assertThrows(IOException.class, () -> u.stage(asset, dir));
    }

    @Test
    void emptyRepoForeignHostsAndBadReposAreRefused() {
        assertThrows(IOException.class, () -> new ModUpdater(base, "o/leer").check(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ModUpdater(base, "../etc"));
        ModUpdater.Asset evil = new ModUpdater.Asset("peakcore", "9", "PeakCore-Mod-9.jar", "https://evil.example.com/a.jar", "https://evil.example.com/a.sha256");
        assertThrows(IOException.class, () -> new ModUpdater("https://api.github.com", "o/r").stage(evil, Path.of("x")));
        ModUpdater.Asset sneaky = new ModUpdater.Asset("peakcore", "9", "../../x.jar", base + "/dl/core", base + "/dl/sha");
        assertThrows(IOException.class, () -> new ModUpdater(base, "o/r").stage(sneaky, Path.of("x")));
    }

    @Test
    void swapScriptsWaitForTheGameAndOnlyTouchTheGivenFiles() {
        List<ModUpdater.Swap> swaps = List.of(new ModUpdater.Swap(Path.of("C:\\mods\\PeakCore-Mod-0.1.0.jar"), Path.of("C:\\stage\\PeakCore-Mod-0.2.0.jar")));
        String win = ModUpdater.swapScript(4242, swaps, true);
        assertTrue(win.contains("Wait-Process -Id 4242"));
        assertTrue(win.contains("Remove-Item -LiteralPath 'C:\\mods\\PeakCore-Mod-0.1.0.jar'"));
        assertTrue(win.contains("-Destination 'C:\\mods\\PeakCore-Mod-0.2.0.jar'"));
        String sh = ModUpdater.swapScript(4242, List.of(new ModUpdater.Swap(Path.of("/m/it's.jar"), Path.of("/s/new.jar"))), false);
        assertTrue(sh.contains("kill -0 4242"));
        assertTrue(sh.contains("it'\\''s.jar'"));
    }

    @Test
    void versionsCompareLikeNumbers() {
        assertTrue(ModUpdater.compare("0.1.10", "0.1.9") > 0);
        assertEquals(0, ModUpdater.compare("1.0", "1.0.0"));
        assertTrue(ModUpdater.compare("1.0.0-beta", "1.0.0") < 0);
    }
}
