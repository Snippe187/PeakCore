package de.peakverse.core.plugin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.peakverse.core.api.web.WebResponse;
import de.peakverse.core.plugin.perm.PermStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Web-Zugang mit Rollen: nur erlaubte Seiten, die Spieler-Seite nur mit Voll-Zugang, Sitzung an die IP gebunden. */
class WebHostRolesTest {
    private HttpServer server;
    private WebAuth auth;
    private WebHost host;
    private final AtomicReference<PermStore.Grant> grant = new AtomicReference<>(new PermStore.Grant(false, Set.of("demo")));
    private final UUID player = UUID.randomUUID();
    private final HttpClient http = HttpClient.newHttpClient();
    private String base;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        auth = new WebAuth(System::currentTimeMillis);
        host = new WebHost(auth, new AdminLog(dir.resolve("log.jsonl"), System::currentTimeMillis), new LiveHub(),
                (java.util.function.Function<UUID, PermStore.Grant>) (id -> grant.get()), new WebHost.MainThread() {
                    public <T> T call(java.util.concurrent.Callable<T> task) throws Exception {
                        return task.call();
                    }
                }, false, "test", Logger.getLogger("test"));
        for (String id : new String[] {"demo", "secret", "players"}) {
            var m = host.registry(id);
            m.panel(id, id, "export default function mount(){}");
            m.route("hi", r -> WebResponse.text(id));
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/", host);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String cookie(String ip) {
        return "peak_session=" + auth.redeem(auth.issueToken(player, "Neo"), ip).orElseThrow().id();
    }

    private int status(String path, String cookie) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private String body(String path, String cookie) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void roleSeesOnlyItsModules() throws Exception {
        String c = cookie("127.0.0.1");
        assertEquals(200, status("/api/demo/hi", c));
        assertEquals(403, status("/api/secret/hi", c));
        assertEquals(404, status("/m/secret/panel.js", c));
        assertEquals(200, status("/m/demo/panel.js", c));
        String session = body("/api/session", c);
        assertTrue(session.contains("\"id\":\"demo\""));
        assertFalse(session.contains("\"id\":\"secret\""));
    }

    @Test
    void playersPageNeedsFullAccess() throws Exception {
        grant.set(new PermStore.Grant(false, Set.of("demo", "players")));
        assertEquals(403, status("/api/players/hi", cookie("127.0.0.1")), "ein Modul-Recht 'players' reicht nicht");
        grant.set(new PermStore.Grant(true, Set.of()));
        assertEquals(200, status("/api/players/hi", cookie("127.0.0.1")));
        assertEquals(200, status("/api/secret/hi", cookie("127.0.0.1")));
    }

    @Test
    void sessionIsBoundToTheLoginIp() throws Exception {
        String foreign = cookie("10.9.9.9");
        assertEquals(401, status("/api/demo/hi", foreign), "Anfrage von einer anderen IP als beim Login");
        assertEquals(401, status("/api/demo/hi", foreign), "die Sitzung ist danach beendet");
        host.setBindIp(false);
        assertEquals(200, status("/api/demo/hi", cookie("10.9.9.9")), "ohne Bindung geht es von jeder IP");
    }

    @Test
    void removedAccessEndsTheSessionAtOnce() throws Exception {
        String c = cookie("127.0.0.1");
        assertEquals(200, status("/api/demo/hi", c));
        grant.set(null);
        assertEquals(401, status("/api/demo/hi", c));
        grant.set(new PermStore.Grant(false, Set.of("demo")));
        assertEquals(401, status("/api/demo/hi", c), "die Sitzung wurde widerrufen");
    }
}
