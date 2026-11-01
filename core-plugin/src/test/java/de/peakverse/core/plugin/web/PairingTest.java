package de.peakverse.core.plugin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.peakverse.core.plugin.perm.PermStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Geraete koppeln: Anfrage, Bestaetigung im Spiel, dauerhafter Schluessel, Sperren. */
class PairingTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final UUID neo = UUID.randomUUID();

    // ---------- DeviceStore ----------

    @Test
    void deviceKeysAreCheckedByHashAndCanBeRevoked(@TempDir Path dir) throws Exception {
        DeviceStore s = new DeviceStore(dir.resolve("d.json"), now::get, Logger.getAnonymousLogger());
        String key = s.issue(neo, "Neo", "1.2.3.4", "Android, Chrome");
        assertTrue(s.authenticate(key, "5.6.7.8", 0).isPresent());
        assertEquals("5.6.7.8", s.list().get(0).lastIp);
        String file = Files.readString(dir.resolve("d.json"));
        assertFalse(file.contains(key.substring(key.indexOf('.') + 1)), "der Schluessel selbst steht nicht in der Datei");
        assertTrue(s.authenticate(key + "x", "x", 0).isEmpty());
        assertTrue(s.authenticate("kaputt", "x", 0).isEmpty());
        assertTrue(s.authenticate(null, "x", 0).isEmpty());
        assertTrue(s.authenticate("a.b", "x", 0).isEmpty());

        DeviceStore again = new DeviceStore(dir.resolve("d.json"), now::get, Logger.getAnonymousLogger());
        again.load();
        assertTrue(again.authenticate(key, "x", 0).isPresent(), "ueberlebt einen Neustart");
        assertTrue(again.revoke(again.list().get(0).id));
        assertTrue(again.authenticate(key, "x", 0).isEmpty());
        assertFalse(again.revoke("gibtsnicht"));
    }

    @Test
    void unusedDevicesExpireOnlyIfConfigured(@TempDir Path dir) {
        DeviceStore s = new DeviceStore(dir.resolve("d.json"), now::get, Logger.getAnonymousLogger());
        String key = s.issue(neo, "Neo", "ip", "x");
        now.addAndGet(400L * 86_400_000L);
        assertTrue(s.authenticate(key, "ip", 0).isPresent(), "0 = nie");
        now.addAndGet(31L * 86_400_000L);
        assertTrue(s.authenticate(key, "ip", 30).isEmpty(), "31 Tage ungenutzt bei 30 Tagen Frist");
        assertTrue(s.list().isEmpty());
    }

    @Test
    void onlyTenDevicesPerPlayerAndRevokeAll(@TempDir Path dir) {
        DeviceStore s = new DeviceStore(dir.resolve("d.json"), now::get, Logger.getAnonymousLogger());
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < DeviceStore.MAX_PER_PLAYER + 3; i++) {
            now.incrementAndGet();
            keys.add(s.issue(neo, "Neo", "ip", "x"));
        }
        assertEquals(DeviceStore.MAX_PER_PLAYER, s.listFor(neo).size());
        assertTrue(s.authenticate(keys.get(0), "ip", 0).isEmpty(), "das aelteste ist weg");
        assertTrue(s.authenticate(keys.get(keys.size() - 1), "ip", 0).isPresent());
        s.issue(UUID.randomUUID(), "Andere", "ip", "x");
        assertEquals(DeviceStore.MAX_PER_PLAYER, s.revokeAll(neo));
        assertEquals(1, s.list().size());
    }

    // ---------- PairingService ----------

    @Test
    void approveNeedsTheRightPlayerAndCodeAndWorksOnce() {
        PairingService p = new PairingService(now::get);
        PairingService.Request r = p.start(neo, "Neo", "1.1.1.1", "Android");
        assertNotNull(r);
        assertEquals(4, r.code().length());
        assertEquals(PairingService.State.PENDING, p.poll(r.id()));
        assertFalse(p.approve(UUID.randomUUID(), r.code()), "ein anderer Spieler kann nicht bestaetigen");
        assertFalse(p.approve(neo, "xxxx"));
        assertNull(p.claim(r.id()), "noch nicht bestaetigt");
        assertTrue(p.approve(neo, r.code()));
        assertEquals(PairingService.State.APPROVED, p.poll(r.id()));
        assertNotNull(p.claim(r.id()));
        assertNull(p.claim(r.id()), "nur einmal abholbar");
        assertEquals(PairingService.State.EXPIRED, p.poll(r.id()));
    }

    @Test
    void oneRequestPerPlayerCooldownAfterDenyAndExpiry() {
        PairingService p = new PairingService(now::get);
        PairingService.Request r = p.start(neo, "Neo", "1.1.1.1", "x");
        assertNull(p.start(neo, "Neo", "2.2.2.2", "x"), "schon eine offene Anfrage");
        assertTrue(p.deny(neo));
        assertEquals(PairingService.State.DENIED, p.poll(r.id()));
        assertNull(p.start(neo, "Neo", "3.3.3.3", "x"), "Pause nach dem Ablehnen");
        now.addAndGet(PairingService.COOLDOWN_MS + 1);
        PairingService.Request r2 = p.start(neo, "Neo", "4.4.4.4", "x");
        assertNotNull(r2);
        now.addAndGet(PairingService.TTL_MS + 1);
        assertEquals(PairingService.State.EXPIRED, p.poll(r2.id()));
        assertNull(p.start(neo, "Neo", "5.5.5.5", "x"), "Pause nach Ablauf");
    }

    @Test
    void perIpLimit() {
        PairingService p = new PairingService(now::get);
        for (int i = 0; i < PairingService.MAX_STARTS_PER_MINUTE; i++) assertNotNull(p.start(UUID.randomUUID(), "n", "9.9.9.9", "x"));
        assertNull(p.start(UUID.randomUUID(), "n", "9.9.9.9", "x"));
        assertNotNull(p.start(UUID.randomUUID(), "n", "8.8.8.8", "x"), "andere IP ist nicht betroffen");
        now.addAndGet(61_000);
        assertNotNull(p.start(UUID.randomUUID(), "n", "9.9.9.9", "x"));
    }

    // ---------- Ganzer Weg ueber HTTP ----------

    private HttpServer server;
    private String base;
    private WebHost host;
    private DeviceStore devices;
    private PairingService pairing;
    private final AtomicReference<PermStore.Grant> grant = new AtomicReference<>(new PermStore.Grant(true, Set.of()));
    private final List<PairingService.Request> asked = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private boolean online = true;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        WebAuth auth = new WebAuth(now::get);
        host = new WebHost(auth, new AdminLog(dir.resolve("log.jsonl"), now::get), new LiveHub(), (java.util.function.Function<UUID, PermStore.Grant>) (id -> grant.get()),
                new WebHost.MainThread() {
                    public <T> T call(java.util.concurrent.Callable<T> task) throws Exception {
                        return task.call();
                    }
                }, false, "test", Logger.getAnonymousLogger());
        devices = new DeviceStore(dir.resolve("devices.json"), now::get, Logger.getAnonymousLogger());
        pairing = new PairingService(now::get);
        host.enablePairing(devices, pairing, new WebHost.PairHooks() {
            public Candidate lookup(String name) {
                return online && name.equalsIgnoreCase("Neo") ? new Candidate(neo, "Neo") : null;
            }

            public void ask(Candidate player, PairingService.Request request) {
                asked.add(request);
            }
        }, 0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/", host);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("X-Peak", "1").header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/120")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (cookie != null) b.header("Cookie", cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String json, String name) {
        int i = json.indexOf("\"" + name + "\":\"");
        return i < 0 ? null : json.substring(i + name.length() + 4, json.indexOf('"', i + name.length() + 4));
    }

    private static String cookies(HttpResponse<?> r) {
        StringBuilder sb = new StringBuilder();
        for (String c : r.headers().allValues("Set-Cookie")) sb.append(sb.length() > 0 ? "; " : "").append(c.split(";")[0]);
        return sb.toString();
    }

    @Test
    void phoneGetsInOnlyAfterTheGameConfirms() throws Exception {
        assertEquals(401, get("/api/session", null).statusCode());
        HttpResponse<String> start = post("/pair/start", "{\"name\":\"Neo\"}");
        assertEquals(200, start.statusCode());
        String id = field(start.body(), "id"), code = field(start.body(), "code");
        assertEquals(1, asked.size(), "der Spieler im Spiel wird gefragt");
        assertEquals(code, asked.get(0).code(), "dieser Code steht auf dem Handy und im Spiel");
        assertEquals("Android, Chrome", asked.get(0).label());

        assertTrue(get("/pair/poll?id=" + id, null).body().contains("\"pending\""));
        assertEquals(401, get("/api/session", null).statusCode());
        assertTrue(pairing.approve(neo, code));
        HttpResponse<String> done = get("/pair/poll?id=" + id, null);
        assertTrue(done.body().contains("\"approved\""));
        String cookie = cookies(done);
        assertTrue(cookie.contains("peak_device=") && cookie.contains("peak_session="));
        assertTrue(done.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("peak_device=") && c.contains("HttpOnly") && c.contains("Max-Age=315360000")));
        assertEquals(200, get("/api/session", cookie).statusCode());
        assertTrue(get("/pair/poll?id=" + id, null).body().contains("\"expired\""), "die Anfrage ist verbraucht");
        assertEquals(1, devices.list().size());
    }

    @Test
    void deviceKeyAloneLogsInAgainWithoutSession() throws Exception {
        String id = field(post("/pair/start", "{\"name\":\"Neo\"}").body(), "id");
        pairing.approve(neo, asked.get(0).code());
        String cookie = cookies(get("/pair/poll?id=" + id, null));
        String deviceOnly = cookie.substring(cookie.indexOf("peak_device="), cookie.indexOf(";", cookie.indexOf("peak_device=")) < 0 ? cookie.length() : cookie.indexOf(";", cookie.indexOf("peak_device=")));
        HttpResponse<String> r = get("/api/session", deviceOnly.split(";")[0]);
        assertEquals(200, r.statusCode(), "neue Sitzung allein durch den Geraete-Schluessel");
        assertTrue(r.body().contains("\"name\":\"Neo\""));
        assertTrue(r.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("peak_session=")));
        assertEquals(401, get("/api/session", "peak_device=falsch.schluessel").statusCode());
    }

    @Test
    void losingAccessLocksTheDevicesOut() throws Exception {
        String id = field(post("/pair/start", "{\"name\":\"Neo\"}").body(), "id");
        pairing.approve(neo, asked.get(0).code());
        String device = cookies(get("/pair/poll?id=" + id, null));
        device = device.substring(device.indexOf("peak_device="));
        device = device.contains(";") ? device.substring(0, device.indexOf(";")) : device;
        assertEquals(200, get("/api/session", device).statusCode());
        grant.set(null);
        assertEquals(401, get("/api/session", device).statusCode());
        assertTrue(devices.list().isEmpty(), "die Geraete des Spielers sind gesperrt");
        grant.set(new PermStore.Grant(true, Set.of()));
        assertEquals(401, get("/api/session", device).statusCode(), "auch mit neuem OP bleibt der alte Schluessel tot");
    }

    @Test
    void unknownOrOfflinePlayerLooksTheSameAndNobodyIsAsked() throws Exception {
        HttpResponse<String> real = post("/pair/start", "{\"name\":\"Neo\"}");
        HttpResponse<String> nobody = post("/pair/start", "{\"name\":\"Fremder\"}");
        assertEquals(200, nobody.statusCode());
        assertEquals(real.body().length(), nobody.body().length());
        assertEquals(1, asked.size(), "nur der echte Spieler bekommt eine Frage");
        String id = field(nobody.body(), "id");
        assertTrue(get("/pair/poll?id=" + id, null).body().contains("\"pending\""));
        assertFalse(pairing.approve(UUID.randomUUID(), field(nobody.body(), "code")));
        online = false;
        now.addAndGet(PairingService.TTL_MS + PairingService.COOLDOWN_MS + 5000);
        assertEquals(200, post("/pair/start", "{\"name\":\"Neo\"}").statusCode(), "offline: gleiche Antwort");
        assertEquals(1, asked.size());
    }

    @Test
    void badInputAndMissingHeaderAreRefused() throws Exception {
        assertEquals(400, post("/pair/start", "{\"name\":\"../etc\"}").statusCode());
        assertEquals(400, post("/pair/start", "kein json").statusCode());
        assertEquals(400, post("/pair/start", "{}").statusCode());
        HttpResponse<String> noHeader = http.send(HttpRequest.newBuilder(URI.create(base + "/pair/start")).POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Neo\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noHeader.statusCode());
        assertEquals(429, get("/pair/poll", null).statusCode());
    }

    @Test
    void deviceListRevokeAndLogout() throws Exception {
        String id = field(post("/pair/start", "{\"name\":\"Neo\"}").body(), "id");
        pairing.approve(neo, asked.get(0).code());
        String both = cookies(get("/pair/poll?id=" + id, null));
        HttpResponse<String> list = get("/api/devices", both);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("\"current\":true") && list.body().contains("Android, Chrome"));
        String devId = field(list.body(), "id");
        HttpResponse<String> del = http.send(HttpRequest.newBuilder(URI.create(base + "/api/devices/" + devId)).header("Cookie", both).header("X-Peak", "1").DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, del.statusCode());
        assertTrue(devices.list().isEmpty());

        String id2 = field(post("/pair/start", "{\"name\":\"Neo\"}").body(), "id");
        // zweite Anfrage braucht erst die Pause nach dem ersten Abschluss nicht: nur Ablehnen/Ablauf loesen sie aus
        pairing.approve(neo, asked.get(1).code());
        String both2 = cookies(get("/pair/poll?id=" + id2, null));
        HttpResponse<String> out = http.send(HttpRequest.newBuilder(URI.create(base + "/api/logout")).header("Cookie", both2).header("X-Peak", "1").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, out.statusCode());
        assertTrue(out.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("peak_device=;") && c.contains("Max-Age=0")));
        assertTrue(devices.list().isEmpty(), "Abmelden vergisst das Geraet auch auf dem Server");
    }

    @Test
    void deviceLabelsAreShortAndHarmless() {
        assertEquals("iPhone, Safari", WebHost.deviceLabel("Mozilla/5.0 (iPhone; CPU iPhone OS 17) AppleWebKit Safari/604.1"));
        assertEquals("Windows, Edge", WebHost.deviceLabel("Mozilla/5.0 (Windows NT 10.0) Chrome/120 Safari/537 Edg/120"));
        assertEquals("Unbekanntes Geraet", WebHost.deviceLabel(null));
        assertEquals("Geraet", WebHost.deviceLabel("<script>alert(1)</script>"));
    }
}
