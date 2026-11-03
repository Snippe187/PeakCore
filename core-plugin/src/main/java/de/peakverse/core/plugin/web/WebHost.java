package de.peakverse.core.plugin.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import de.peakverse.core.api.web.WebHandler;
import de.peakverse.core.api.web.WebRegistry;
import de.peakverse.core.api.web.WebRequest;
import de.peakverse.core.api.web.WebResponse;
import de.peakverse.core.plugin.perm.PermStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Die Web-Oberflaeche im Core: statische Seite, Einmal-Link-Anmeldung, Sitzungen, Modul-Seiten und -Schnittstellen, Live-Meldungen, Protokoll.
 * Alles ausser den statischen Dateien und {@code /login} verlangt eine Sitzung; Aenderungen verlangen zusaetzlich den Header {@code X-Peak: 1}
 * (schuetzt vor Anfragen von fremden Webseiten). Beruehrt Bukkit nur ueber {@link MainThread} und {@code isAdmin}.
 */
public final class WebHost implements HttpHandler {
    public static final int MAX_BODY = 8 * 1024 * 1024;
    private static final Gson GSON = new Gson();
    private static final Set<String> STATIC = Set.of("index.html", "app.css", "app.js", "favicon.svg");
    private static final String COOKIE = "peak_session";

    /** Fuehrt Code auf dem Haupt-Thread des Servers aus. */
    public interface MainThread {
        <T> T call(Callable<T> task) throws Exception;
    }

    private record Route(String pattern, WebHandler handler) {
        boolean matches(String path) {
            return pattern.endsWith("/*") ? path.equals(pattern.substring(0, pattern.length() - 2)) || path.startsWith(pattern.substring(0, pattern.length() - 1))
                    : pattern.equals(path);
        }
    }

    private static final class Module implements WebRegistry {
        final String id;
        final WebHost host;
        volatile String titleDe;
        volatile String titleEn;
        volatile String script;
        final List<Route> routes = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile long rev = 1;
        volatile String lastBy = "";

        Module(String id, WebHost host) {
            this.id = id;
            this.host = host;
        }

        public boolean enabled() { return true; }

        public void panel(String de, String en, String js) {
            this.titleDe = de;
            this.titleEn = en;
            this.script = js;
        }

        public void route(String pattern, WebHandler handler) {
            routes.add(new Route(pattern, handler));
        }

        public void changed(String event, String admin) {
            rev++;
            lastBy = admin == null ? "" : admin;
            JsonObject o = new JsonObject();
            o.addProperty("module", id);
            o.addProperty("event", event);
            o.addProperty("by", lastBy);
            o.addProperty("rev", rev);
            host.hub.send("changed", GSON.toJson(o));
        }

        public void log(String admin, String action) {
            host.writeLog(admin, id, action);
        }

        public <T> T onMain(Callable<T> task) throws Exception {
            return host.main.call(task);
        }
    }

    private final WebAuth auth;
    private final AdminLog log;
    private final LiveHub hub;
    private final Function<UUID, PermStore.Grant> access;
    private volatile boolean bindIp = true;
    private final MainThread main;
    private final Logger logger;
    private final boolean secureCookie;
    private final String version;
    private final Map<String, Module> modules = new ConcurrentHashMap<>();

    /** Einfache Fassung: wer der Pruefung genuegt, darf alles (so arbeitet es nur mit Operatoren). */
    public WebHost(WebAuth auth, AdminLog log, LiveHub hub, Predicate<UUID> stillAdmin, MainThread main, boolean secureCookie, String version, Logger logger) {
        this(auth, log, hub, (Function<UUID, PermStore.Grant>) (id -> stillAdmin.test(id) ? new PermStore.Grant(true, Set.of()) : null), main, secureCookie, version, logger);
    }

    /** Mit Rollen: {@code access} liefert, was der Spieler im Web darf, oder {@code null} (kein Zugang). */
    public WebHost(WebAuth auth, AdminLog log, LiveHub hub, Function<UUID, PermStore.Grant> access, MainThread main, boolean secureCookie, String version, Logger logger) {
        this.auth = auth;
        this.log = log;
        this.hub = hub;
        this.access = access;
        this.main = main;
        this.secureCookie = secureCookie;
        this.version = version;
        this.logger = logger;
    }

    /** Sitzung an die IP binden, mit der man sich angemeldet hat (Standard: ja). */
    public void setBindIp(boolean bind) {
        this.bindIp = bind;
    }

    /** Wer sich per Koppeln anmelden darf und wie der Spieler im Spiel benachrichtigt wird (beides laeuft auf dem Haupt-Thread). */
    public interface PairHooks {
        /** Der Spieler mit diesem Namen, wenn er online ist und Zugang zur Web-Oberflaeche hat; sonst {@code null}. */
        Candidate lookup(String name);

        /** Fragt den Spieler im Spiel, ob er das neue Geraet erlaubt. */
        void ask(Candidate player, PairingService.Request request);

        record Candidate(UUID id, String name) { }
    }

    private static final String DEVICE_COOKIE = "peak_device";
    private static final long DEVICE_COOKIE_SECONDS = 10L * 365 * 86_400;
    private volatile DeviceStore devices;
    private volatile PairingService pairing;
    private volatile PairHooks pairHooks;
    private volatile int deviceExpireDays;

    /** Schaltet das Koppeln von Geraeten (Handy) ein. {@code expireDays}: nach so vielen Tagen ohne Benutzung verfaellt ein Geraet, 0 = nie. */
    public void enablePairing(DeviceStore devices, PairingService pairing, PairHooks hooks, int expireDays) {
        this.devices = devices;
        this.pairing = pairing;
        this.pairHooks = hooks;
        this.deviceExpireDays = Math.max(0, expireDays);
    }

    /** Kennungen aller Module mit Web-Seite (fuer die Rollen). */
    public List<String> moduleIds() {
        return modules.values().stream().filter(m -> m.script != null).map(m -> m.id).sorted().toList();
    }

    /** Das Register fuer ein Modul (immer dasselbe je ID). */
    public WebRegistry registry(String moduleId) {
        return modules.computeIfAbsent(moduleId, id -> new Module(id, this));
    }

    private void writeLog(String admin, String module, String action) {
        try {
            log.append(admin, module, action);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Web-Protokoll konnte nicht geschrieben werden", e);
        }
    }

    // ---- Anfragen ----

    @Override
    public void handle(HttpExchange ex) throws IOException {
        boolean keepOpen = false;
        try {
            keepOpen = route(ex);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Fehler in der Web-Oberflaeche", e);
            try {
                send(ex, WebResponse.error(500, "Interner Fehler"));
            } catch (IOException ignored) {
                // Verbindung schon weg
            }
        } finally {
            if (!keepOpen) ex.close();
        }
    }

    /** @return {@code true}, wenn die Verbindung offen bleibt (Live-Meldungen) */
    private boolean route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getRawPath();
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        if (path.equals("/") || path.equals("/index.html")) {
            sendStatic(ex, "index.html");
            return false;
        }
        if (STATIC.contains(path.substring(1))) {
            sendStatic(ex, path.substring(1));
            return false;
        }
        if (path.equals("/login")) {
            login(ex, query.get("t"));
            return false;
        }
        if (path.equals("/pair/start") || path.equals("/pair/poll")) {
            pair(ex, path, method, query);
            return false;
        }
        if (!path.startsWith("/api/") && !path.startsWith("/m/")) {
            send(ex, WebResponse.error(404, "Nicht gefunden"));
            return false;
        }
        WebAuth.Session session = auth.session(cookie(ex, COOKIE)).orElse(null);
        if (session == null) session = deviceLogin(ex);
        PermStore.Grant grant = session == null ? null : access.apply(session.player());
        boolean wrongIp = session != null && bindIp && session.ip() != null && !session.ip().equals(remoteIp(ex));
        if (session == null || grant == null || wrongIp) {
            if (session != null) auth.revokeAll(session.player());
            send(ex, WebResponse.error(401, "Nicht angemeldet. Im Spiel /peakcore web (oder /peakweb) eingeben."));
            return false;
        }
        boolean write = !method.equals("GET") && !method.equals("HEAD");
        if (write && !"1".equals(ex.getRequestHeaders().getFirst("X-Peak"))) {
            send(ex, WebResponse.error(403, "Header X-Peak fehlt"));
            return false;
        }
        if (path.startsWith("/m/")) {
            panelScript(ex, path, grant);
            return false;
        }
        String api = path.substring("/api/".length());
        switch (api) {
            case "session" -> send(ex, WebResponse.json(sessionInfo(session, grant)));
            case "log" -> {
                int n = clamp(query.get("n"), 1, 500, 100);
                try {
                    JsonArray a = new JsonArray();
                    log.recent(n).forEach(a::add);
                    send(ex, WebResponse.json(GSON.toJson(a)));
                } catch (IOException e) {
                    send(ex, WebResponse.error(500, "Protokoll nicht lesbar"));
                }
            }
            case "logout" -> {
                auth.logout(cookie(ex, COOKIE));
                // Abmelden heisst auch: dieses Geraet vergisst seinen Schluessel (sonst kaeme es beim naechsten Besuch von allein wieder rein)
                String key = cookie(ex, DEVICE_COOKIE);
                if (key != null && devices != null && key.indexOf('.') > 0) devices.revoke(key.substring(0, key.indexOf('.')));
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                ex.getResponseHeaders().add("Set-Cookie", DEVICE_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                send(ex, WebResponse.ok());
            }
            case "devices" -> deviceList(ex, session, grant, method);
            case "events" -> {
                if (!hub.attach(ex, session.name())) {
                    send(ex, WebResponse.error(503, "Zu viele offene Seiten"));
                    return false;
                }
                return true;
            }
            default -> {
                if (api.startsWith("devices/")) deviceRevoke(ex, session, grant, method, api.substring("devices/".length()));
                else moduleApi(ex, session, grant, method, api, query);
            }
        }
        return false;
    }

    // ---- Geraete koppeln (Handy) ----

    /** Meldet ein Geraet mit seinem gespeicherten Schluessel an, wenn es einen hat und der Spieler noch Zugang hat. */
    private WebAuth.Session deviceLogin(HttpExchange ex) {
        DeviceStore store = devices;
        String key = cookie(ex, DEVICE_COOKIE);
        if (store == null || key == null) return null;
        String ip = remoteIp(ex);
        if (auth.isBlocked(ip)) return null;
        var found = store.authenticate(key, ip, deviceExpireDays);
        if (found.isEmpty()) {
            auth.recordFailure(ip);
            return null;
        }
        DeviceStore.Device d = found.get();
        UUID player;
        try {
            player = UUID.fromString(d.player);
        } catch (IllegalArgumentException e) {
            store.revoke(d.id);
            return null;
        }
        if (access.apply(player) == null) {
            // Der Spieler ist kein Operator mehr und hat keine Rolle mit Web-Zugang: alle seine Geraete sind damit gesperrt
            store.revokeAll(player);
            return null;
        }
        WebAuth.Session s = auth.createSession(player, d.name, ip);
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + s.id() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + auth.sessionTtlMs() / 1000 + (secureCookie ? "; Secure" : ""));
        return s;
    }

    private static boolean validName(String n) {
        return n != null && n.matches("[A-Za-z0-9_.]{3,20}");
    }

    /** Kurzer, harmloser Name fuer das Geraet aus dem User-Agent, z. B. "Android, Chrome". */
    static String deviceLabel(String ua) {
        if (ua == null) return "Unbekanntes Geraet";
        String os = ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad" : ua.contains("Android") ? "Android" : ua.contains("Windows") ? "Windows"
                : ua.contains("Mac OS") ? "Mac" : ua.contains("Linux") ? "Linux" : "Geraet";
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("OPR/") || ua.contains("Opera") ? "Opera" : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Chrome/") || ua.contains("CriOS") ? "Chrome" : ua.contains("Safari/") ? "Safari" : "";
        return browser.isEmpty() ? os : os + ", " + browser;
    }

    private void pair(HttpExchange ex, String path, String method, Map<String, String> query) throws IOException {
        PairingService service = pairing;
        PairHooks hooks = pairHooks;
        if (service == null || hooks == null || devices == null) {
            send(ex, WebResponse.error(404, "Koppeln ist aus"));
            return;
        }
        String ip = remoteIp(ex);
        if (path.equals("/pair/start")) {
            if (!method.equals("POST") || !"1".equals(ex.getRequestHeaders().getFirst("X-Peak"))) {
                send(ex, WebResponse.error(403, "Nur POST mit Header X-Peak"));
                return;
            }
            String name;
            try {
                JsonObject body = GSON.fromJson(new String(readBody(ex.getRequestBody()), StandardCharsets.UTF_8), JsonObject.class);
                name = body != null && body.has("name") ? body.get("name").getAsString().trim() : "";
            } catch (RuntimeException e) {
                name = "";
            }
            if (!validName(name)) {
                send(ex, WebResponse.error(400, "Das ist kein gueltiger Minecraft-Name."));
                return;
            }
            String label = deviceLabel(ex.getRequestHeaders().getFirst("User-Agent"));
            final String playerName = name;
            PairHooks.Candidate who;
            try {
                who = main.call(() -> hooks.lookup(playerName));
            } catch (Exception e) {
                logger.log(Level.WARNING, "Koppeln: Spieler-Suche fehlgeschlagen", e);
                send(ex, WebResponse.error(500, "Interner Fehler"));
                return;
            }
            // Antwort immer gleich, auch wenn es den Spieler nicht gibt oder er keinen Zugang hat: so kann niemand Namen durchprobieren
            PairingService.Request r = who == null ? null : service.start(who.id(), who.name(), ip, label);
            if (r == null) r = service.start(UUID.randomUUID(), playerName, ip, label);
            if (r == null) {
                send(ex, WebResponse.error(429, "Zu viele Versuche. Bitte warte eine Minute."));
                return;
            }
            if (who != null && r.player().equals(who.id())) {
                final PairingService.Request real = r;
                final PairHooks.Candidate target = who;
                try {
                    main.call(() -> {
                        hooks.ask(target, real);
                        return null;
                    });
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Koppeln: Nachricht an den Spieler fehlgeschlagen", e);
                }
                writeLog(who.name(), "core", "Geraet will sich koppeln (" + label + ", " + ip + ")");
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("code", r.code());
            o.addProperty("seconds", PairingService.TTL_MS / 1000);
            send(ex, WebResponse.json(GSON.toJson(o)));
            return;
        }
        // /pair/poll?id=...
        String id = query.get("id");
        if (id == null || id.length() > 64 || auth.isBlocked(ip)) {
            send(ex, WebResponse.error(429, "Zu viele Anfragen"));
            return;
        }
        PairingService.State state = service.poll(id);
        JsonObject o = new JsonObject();
        if (state == PairingService.State.APPROVED) {
            PairingService.Request r = service.claim(id);
            if (r == null) {
                state = PairingService.State.EXPIRED;
            } else {
                String key = devices.issue(r.player(), r.name(), ip, r.label());
                WebAuth.Session s = auth.createSession(r.player(), r.name(), ip);
                ex.getResponseHeaders().add("Set-Cookie", DEVICE_COOKIE + "=" + key + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + DEVICE_COOKIE_SECONDS + (secureCookie ? "; Secure" : ""));
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + s.id() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + auth.sessionTtlMs() / 1000 + (secureCookie ? "; Secure" : ""));
                writeLog(r.name(), "core", "Geraet gekoppelt (" + r.label() + ", " + ip + ")");
            }
        } else if (state == PairingService.State.EXPIRED) {
            auth.recordFailure(ip);
        }
        o.addProperty("state", state.name().toLowerCase());
        send(ex, WebResponse.json(GSON.toJson(o)));
    }

    private void deviceList(HttpExchange ex, WebAuth.Session session, PermStore.Grant grant, String method) throws IOException {
        DeviceStore store = devices;
        if (store == null || !method.equals("GET")) {
            send(ex, WebResponse.error(store == null ? 404 : 405, store == null ? "Koppeln ist aus" : "GET"));
            return;
        }
        String key = cookie(ex, DEVICE_COOKIE);
        String currentId = key != null && key.indexOf('.') > 0 ? key.substring(0, key.indexOf('.')) : "";
        JsonArray a = new JsonArray();
        for (DeviceStore.Device d : grant.all() ? store.list() : store.listFor(session.player())) {
            JsonObject j = new JsonObject();
            j.addProperty("id", d.id);
            j.addProperty("name", d.name);
            j.addProperty("label", d.label);
            j.addProperty("ip", d.lastIp);
            j.addProperty("created", d.created);
            j.addProperty("lastUsed", d.lastUsed);
            j.addProperty("current", d.id.equals(currentId));
            a.add(j);
        }
        send(ex, WebResponse.json(GSON.toJson(a)));
    }

    private void deviceRevoke(HttpExchange ex, WebAuth.Session session, PermStore.Grant grant, String method, String id) throws IOException {
        DeviceStore store = devices;
        if (store == null || !method.equals("DELETE")) {
            send(ex, WebResponse.error(store == null ? 404 : 405, store == null ? "Koppeln ist aus" : "DELETE"));
            return;
        }
        boolean mine = store.listFor(session.player()).stream().anyMatch(d -> d.id.equals(id));
        if (!mine && !grant.all()) {
            send(ex, WebResponse.error(403, "Das ist nicht dein Geraet."));
            return;
        }
        if (!store.revoke(id)) {
            send(ex, WebResponse.error(404, "Unbekanntes Geraet"));
            return;
        }
        writeLog(session.name(), "core", "Geraet gesperrt (" + id + ")");
        send(ex, WebResponse.ok());
    }

    private void moduleApi(HttpExchange ex, WebAuth.Session session, PermStore.Grant grant, String method, String api, Map<String, String> query) throws IOException {
        int slash = api.indexOf('/');
        String id = slash < 0 ? api : api.substring(0, slash);
        String rest = slash < 0 ? "" : api.substring(slash + 1);
        if (!grant.allows(id)) {
            send(ex, WebResponse.error(403, "Dafuer hast du keine Berechtigung."));
            return;
        }
        Module m = modules.get(id);
        if (m == null) {
            send(ex, WebResponse.error(404, "Unbekanntes Modul"));
            return;
        }
        Route route = m.routes.stream().filter(r -> r.matches(rest)).findFirst().orElse(null);
        if (route == null) {
            send(ex, WebResponse.error(404, "Unbekannte Adresse"));
            return;
        }
        boolean write = !method.equals("GET") && !method.equals("HEAD");
        if (write) {
            String claimed = ex.getRequestHeaders().getFirst("X-Rev");
            boolean force = "1".equals(ex.getRequestHeaders().getFirst("X-Force"));
            if (claimed != null && !force) {
                long known = parseLong(claimed);
                if (known < m.rev) {
                    JsonObject o = new JsonObject();
                    o.addProperty("error", "Ein anderer Administrator hat inzwischen etwas geaendert (" + m.lastBy + ").");
                    o.addProperty("conflict", true);
                    o.addProperty("by", m.lastBy);
                    o.addProperty("rev", m.rev);
                    ex.getResponseHeaders().set("X-Rev", Long.toString(m.rev));
                    send(ex, new WebResponse(409, "application/json; charset=utf-8", GSON.toJson(o).getBytes(StandardCharsets.UTF_8)));
                    return;
                }
            }
        }
        byte[] body;
        try {
            body = write ? readBody(ex.getRequestBody()) : new byte[0];
        } catch (IOException tooBig) {
            send(ex, WebResponse.error(413, "Anfrage zu gross (hoechstens " + MAX_BODY / 1024 / 1024 + " MB)"));
            return;
        }
        WebResponse response;
        try {
            response = route.handler().handle(new WebRequest(method, rest, query, body, session.name()));
        } catch (IllegalArgumentException e) {
            response = WebResponse.error(400, e.getMessage() == null ? "Ungueltige Eingabe" : e.getMessage());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Fehler in der Web-Schnittstelle von " + id, e);
            response = WebResponse.error(500, "Interner Fehler");
        }
        ex.getResponseHeaders().set("X-Rev", Long.toString(m.rev));
        send(ex, response);
    }

    private String sessionInfo(WebAuth.Session s, PermStore.Grant grant) {
        JsonObject o = new JsonObject();
        o.addProperty("name", s.name());
        o.addProperty("version", version);
        JsonArray mods = new JsonArray();
        modules.values().stream().filter(m -> m.script != null && grant.allows(m.id)).sorted((a, b) -> a.id.compareTo(b.id)).forEach(m -> {
            JsonObject j = new JsonObject();
            j.addProperty("id", m.id);
            j.addProperty("titleDe", m.titleDe);
            j.addProperty("titleEn", m.titleEn);
            j.addProperty("rev", m.rev);
            mods.add(j);
        });
        o.add("modules", mods);
        JsonArray admins = new JsonArray();
        hub.admins().forEach(admins::add);
        o.add("online", admins);
        return GSON.toJson(o);
    }

    private void panelScript(HttpExchange ex, String path, PermStore.Grant grant) throws IOException {
        // /m/<modul>/panel.js
        String[] parts = path.split("/");
        Module m = parts.length == 4 && parts[3].equals("panel.js") && grant.allows(parts[2]) ? modules.get(parts[2]) : null;
        if (m == null || m.script == null) {
            send(ex, WebResponse.error(404, "Keine Seite"));
            return;
        }
        send(ex, new WebResponse(200, "text/javascript; charset=utf-8", m.script.getBytes(StandardCharsets.UTF_8)));
    }

    private void login(HttpExchange ex, String token) throws IOException {
        String ip = remoteIp(ex);
        var session = auth.redeem(token, ip);
        if (session.isEmpty() && auth.session(cookie(ex, COOKIE)).isPresent()) {
            // Der Link wurde schon benutzt (z. B. doppelt geoeffnet), dieser Browser ist aber angemeldet: einfach weiter zur Startseite
            ex.getResponseHeaders().set("Location", "/");
            ex.sendResponseHeaders(302, -1);
            return;
        }
        if (session.isEmpty()) {
            send(ex, new WebResponse(403, "text/html; charset=utf-8",
                    "<!doctype html><meta charset=utf-8><title>PeakVerse</title><body style=\"font-family:sans-serif;background:#12141a;color:#e8e8ee;padding:2rem\"><h1>Link ungueltig</h1><p>Der Link ist abgelaufen oder wurde schon benutzt. Im Spiel <code>/peakcore web</code> eingeben, um einen neuen zu bekommen.</p></body>".getBytes(StandardCharsets.UTF_8)));
            return;
        }
        writeLog(session.get().name(), "core", "Angemeldet");
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + session.get().id() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + auth.sessionTtlMs() / 1000 + (secureCookie ? "; Secure" : ""));
        ex.getResponseHeaders().set("Location", "/");
        ex.sendResponseHeaders(302, -1);
    }

    private static String remoteIp(HttpExchange ex) {
        // localhost kann je Anfrage als 127.0.0.1 oder ::1 ankommen: beides ist derselbe Rechner
        java.net.InetAddress a = ex.getRemoteAddress().getAddress();
        return a.isLoopbackAddress() ? "loopback" : a.getHostAddress();
    }

    private void sendStatic(HttpExchange ex, String name) throws IOException {
        try (InputStream in = WebHost.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                send(ex, WebResponse.error(404, "Datei fehlt im Jar: " + name));
                return;
            }
            String type = name.endsWith(".html") ? "text/html; charset=utf-8" : name.endsWith(".css") ? "text/css; charset=utf-8"
                    : name.endsWith(".js") ? "text/javascript; charset=utf-8" : "image/svg+xml";
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            send(ex, new WebResponse(200, type, in.readAllBytes()));
        }
    }

    private void send(HttpExchange ex, WebResponse r) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", r.contentType());
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", h.getFirst("Cache-Control") == null ? "no-store" : h.getFirst("Cache-Control"));
        if (ex.getRequestMethod().equals("HEAD")) {
            h.set("Content-Length", Integer.toString(r.body().length));
            ex.sendResponseHeaders(r.status(), -1);
            return;
        }
        ex.sendResponseHeaders(r.status(), r.body().length == 0 ? -1 : r.body().length);
        if (r.body().length > 0) ex.getResponseBody().write(r.body());
    }

    // ---- Hilfen ----

    static byte[] readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        for (int n = in.read(buf); n >= 0; n = in.read(buf)) {
            if (out.size() + n > MAX_BODY) throw new IOException("zu gross");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            map.putIfAbsent(k, v);
        }
        return map;
    }

    static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String h : headers) {
            for (String part : h.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static int clamp(String s, int min, int max, int fallback) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(s)));
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

}


