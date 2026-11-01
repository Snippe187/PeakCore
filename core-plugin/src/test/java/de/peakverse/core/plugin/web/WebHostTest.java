package de.peakverse.core.plugin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.peakverse.core.api.web.WebRegistry;
import de.peakverse.core.api.web.WebResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebHostTest {
    private HttpServer server;
    private WebAuth auth;
    private WebHost host;
    private WebRegistry mod;
    private final AtomicBoolean admin = new AtomicBoolean(true);
    private final UUID player = UUID.randomUUID();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private String base;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        auth = new WebAuth(System::currentTimeMillis);
        LiveHub hub = new LiveHub();
        host = new WebHost(auth, new AdminLog(dir.resolve("log.jsonl"), System::currentTimeMillis), hub, (java.util.function.Predicate<java.util.UUID>) id -> admin.get(), new WebHost.MainThread() {
            public <T> T call(java.util.concurrent.Callable<T> task) throws Exception {
                return task.call();
            }
        }, false, "test", Logger.getLogger("test"));
        mod = host.registry("demo");
        mod.panel("Demo", "Demo", "export default function mount(root){ root.textContent='hi'; }");
        mod.route("echo", r -> WebResponse.text(r.method() + ":" + r.text() + ":" + r.admin()));
        mod.route("boom", r -> { throw new IllegalArgumentException("kaputt"); });
        mod.route("files/*", r -> WebResponse.text(r.path()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/", host);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String login() throws Exception {
        String token = auth.issueToken(player, "Neo");
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/login?t=" + token)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, r.statusCode());
        String cookie = r.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"));
        return cookie.substring(0, cookie.indexOf(';'));
    }

    private HttpResponse<String> call(String method, String path, String cookie, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (cookie != null) b.header("Cookie", cookie);
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void staticPageIsPublicButApiNeedsLogin() throws Exception {
        assertEquals(200, call("GET", "/", null, null).statusCode());
        assertTrue(call("GET", "/app.js", null, null).body().contains("PeakVerse"));
        assertEquals(401, call("GET", "/api/session", null, null).statusCode());
        assertEquals(401, call("GET", "/api/demo/echo", "peak_session=erfunden", null).statusCode());
        assertEquals(404, call("GET", "/etc/passwd", null, null).statusCode());
    }

    @Test
    void invalidAndReusedLoginLinksAreRefused() throws Exception {
        assertEquals(403, call("GET", "/login?t=falsch", null, null).statusCode());
        String token = auth.issueToken(player, "Neo");
        assertEquals(302, call("GET", "/login?t=" + token, null, null).statusCode());
        assertEquals(403, call("GET", "/login?t=" + token, null, null).statusCode());
    }

    @Test
    void sessionInfoListsModulesWithPanels() throws Exception {
        String c = login();
        HttpResponse<String> r = call("GET", "/api/session", c, null);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"name\":\"Neo\"") && r.body().contains("\"id\":\"demo\""));
        assertTrue(call("GET", "/m/demo/panel.js", c, null).body().contains("mount"));
        assertEquals(401, call("GET", "/m/demo/panel.js", null, null).statusCode());
    }

    @Test
    void writesNeedTheHeaderAndReachTheModuleWithTheAdminName() throws Exception {
        String c = login();
        assertEquals(403, call("POST", "/api/demo/echo", c, "x").statusCode(), "ohne X-Peak");
        HttpResponse<String> r = call("POST", "/api/demo/echo", c, "hallo", "X-Peak", "1");
        assertEquals("POST:hallo:Neo", r.body());
        assertEquals(400, call("POST", "/api/demo/boom", c, "", "X-Peak", "1").statusCode());
        assertTrue(call("POST", "/api/demo/boom", c, "", "X-Peak", "1").body().contains("kaputt"));
        assertEquals(404, call("GET", "/api/demo/nix", c, null).statusCode());
        assertEquals(404, call("GET", "/api/gibtsnicht/x", c, null).statusCode());
        assertEquals("files/a/b", call("GET", "/api/demo/files/a/b", c, null).body());
    }

    @Test
    void staleRevisionIsAConflictUnlessForced() throws Exception {
        String c = login();
        long rev = Long.parseLong(call("GET", "/api/demo/echo", c, null).headers().firstValue("X-Rev").orElseThrow());
        mod.changed("x", "Anna");
        HttpResponse<String> stale = call("POST", "/api/demo/echo", c, "a", "X-Peak", "1", "X-Rev", Long.toString(rev));
        assertEquals(409, stale.statusCode());
        assertTrue(stale.body().contains("Anna") && stale.body().contains("\"conflict\":true"));
        assertEquals(200, call("POST", "/api/demo/echo", c, "a", "X-Peak", "1", "X-Rev", Long.toString(rev), "X-Force", "1").statusCode());
        long fresh = Long.parseLong(stale.headers().firstValue("X-Rev").orElseThrow());
        assertEquals(200, call("POST", "/api/demo/echo", c, "a", "X-Peak", "1", "X-Rev", Long.toString(fresh)).statusCode());
    }

    @Test
    void demotedOperatorLosesTheSessionImmediately() throws Exception {
        String c = login();
        assertEquals(200, call("GET", "/api/session", c, null).statusCode());
        admin.set(false);
        assertEquals(401, call("GET", "/api/session", c, null).statusCode());
        admin.set(true);
        assertEquals(401, call("GET", "/api/session", c, null).statusCode(), "Sitzung bleibt beendet");
    }

    @Test
    void oversizedBodyIsRejected() throws Exception {
        String c = login();
        String big = "x".repeat(WebHost.MAX_BODY + 10);
        assertEquals(413, call("POST", "/api/demo/echo", c, big, "X-Peak", "1").statusCode());
    }

    @Test
    void logAndLiveEventsWork() throws Exception {
        String c = login();
        mod.log("Neo", "Test-Aktion");
        assertTrue(call("GET", "/api/log?n=5", c, null).body().contains("Test-Aktion"));

        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/events")).header("Cookie", c).build();
        HttpResponse<java.io.InputStream> sse = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, sse.statusCode());
        BufferedReader reader = new BufferedReader(new InputStreamReader(sse.body()));
        assertTrue(reader.readLine().startsWith(":"));
        reader.readLine();
        mod.changed("entries", "Anna");
        assertEquals("event: changed", reader.readLine());
        String data = reader.readLine();
        assertTrue(data.contains("\"by\":\"Anna\"") && data.contains("\"module\":\"demo\""));
        sse.body().close();
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        String c = login();
        assertEquals(200, call("POST", "/api/logout", c, "", "X-Peak", "1").statusCode());
        assertEquals(401, call("GET", "/api/session", c, null).statusCode());
    }
}

