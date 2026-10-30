package de.peakverse.core.plugin.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackWebTest {
    private static final String SHA = "a".repeat(40);
    private final HttpClient client = HttpClient.newHttpClient();
    private PackWebServer web;
    private byte[] content;
    private Path dir;

    @BeforeEach
    void start(@TempDir Path tmp) throws IOException {
        dir = tmp;
        content = new byte[5000];
        for (int i = 0; i < content.length; i++) content[i] = (byte) i;
        Files.write(dir.resolve("pack-" + SHA + ".zip"), content);
        Files.writeString(dir.resolve("geheim.txt"), "nicht ausliefern");
        web = new PackWebServer(dir, "127.0.0.1", 0, Logger.getAnonymousLogger());
        web.start();
    }

    @AfterEach
    void stop() {
        web.stop();
    }

    private HttpResponse<byte[]> send(String method, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path)).method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void servesTheBuiltPack() throws Exception {
        HttpResponse<byte[]> r = send("GET", "/pack/" + SHA + ".zip");
        assertEquals(200, r.statusCode());
        assertArrayEquals(content, r.body());
        assertEquals("application/zip", r.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void headReportsTheSizeWithoutBody() throws Exception {
        HttpResponse<byte[]> r = send("HEAD", "/pack/" + SHA + ".zip");
        assertEquals(200, r.statusCode());
        assertEquals(0, r.body().length);
        assertEquals("5000", r.headers().firstValue("Content-Length").orElse(""));
    }

    @Test
    void unknownOrMalformedPathsAre404() throws Exception {
        for (String p : new String[] {"/", "/pack/", "/pack/" + "b".repeat(40) + ".zip", "/pack/" + SHA.toUpperCase() + ".zip", "/pack/" + SHA, "/geheim.txt",
                "/pack/../geheim.txt", "/pack/%2e%2e/geheim.txt", "/pack/" + "a".repeat(39) + ".zip", "/pack/" + SHA + ".zip/x"}) {
            assertEquals(404, send("GET", p).statusCode(), p);
        }
    }

    @Test
    void onlyGetAndHeadAreAllowed() throws Exception {
        assertEquals(405, send("POST", "/pack/" + SHA + ".zip").statusCode());
        assertEquals(405, send("DELETE", "/pack/" + SHA + ".zip").statusCode());
    }

    @Test
    void takesPacksBuiltLaterWithoutRestart() throws Exception {
        String later = "c".repeat(40);
        assertEquals(404, send("GET", "/pack/" + later + ".zip").statusCode());
        Files.write(dir.resolve("pack-" + later + ".zip"), new byte[] {1, 2, 3});
        HttpResponse<byte[]> r = send("GET", "/pack/" + later + ".zip");
        assertEquals(200, r.statusCode());
        assertArrayEquals(new byte[] {1, 2, 3}, r.body());
    }
    @Test
    void portInUseFailsCleanly(@TempDir Path dir) {
        PackWebServer second = new PackWebServer(dir, "127.0.0.1", web.port(), Logger.getAnonymousLogger());
        assertThrows(IOException.class, second::start);
        assertFalse(second.running());
    }

    @Test
    void stopClosesThePort() {
        web.stop();
        assertFalse(web.running());
        assertThrows(Exception.class, () -> send("GET", "/pack/" + SHA + ".zip"));
    }
    @Test
    void urlRules() {
        assertEquals("http://h:8123/pack/" + SHA + ".zip", PackUrls.urlFor(SHA, "http://h:8123/", null));
        assertEquals("https://h/pack/" + SHA + ".zip", PackUrls.urlFor(SHA, "https://h//", ""));
        assertEquals("https://cdn.example/p/" + SHA + ".zip", PackUrls.urlFor(SHA, "http://ignoriert", "https://cdn.example/p/{sha1}.zip"));
        assertNull(PackUrls.urlFor(SHA, "", ""));
        assertNull(PackUrls.urlFor(SHA, null, null));
        assertThrows(IllegalArgumentException.class, () -> PackUrls.urlFor(SHA, "ftp://h", null));
        assertThrows(IllegalArgumentException.class, () -> PackUrls.urlFor(SHA, "h:8123", null));
        assertThrows(IllegalArgumentException.class, () -> PackUrls.urlFor(SHA, null, "https://cdn.example/pack.zip"));
    }
}
