package de.peakverse.core.plugin.perm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.peakverse.core.api.web.WebRegistry;
import de.peakverse.core.api.web.WebRequest;
import de.peakverse.core.api.web.WebResponse;
import de.peakverse.core.plugin.web.AdminLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;

/**
 * Web-Schnittstelle der Spieler-Seite ({@code /api/players/...}): Spieler, Rollen, Rechte je Modul, Notizen, Verwarnungen und Sperren.
 * Nur fuer Operatoren und Rollen mit Voll-Zugang erreichbar (sonst koennte man sich selbst Rechte geben); alle Zugriffe laufen auf dem Haupt-Thread.
 */
public final class PlayersWeb {
    private static final Gson GSON = new Gson();
    private static final int MAX_LIST = 1000;

    private final WebRegistry web;
    private final PermStore store;
    private final PermissionService perms;
    private final AdminLog log;
    private final Supplier<List<String>> webModules;

    public PlayersWeb(WebRegistry web, PermStore store, PermissionService perms, AdminLog log, Supplier<List<String>> webModules) {
        this.web = web;
        this.store = store;
        this.perms = perms;
        this.log = log;
        this.webModules = webModules;
    }

    public void register(String script) {
        web.panel("Spieler", "Players", script);
        web.route("meta", r -> web.onMain(() -> WebResponse.json(meta())));
        web.route("list", r -> web.onMain(() -> WebResponse.json(list())));
        web.route("player/*", this::player);
        web.route("roles", this::saveRole);
        web.route("roles/*", this::deleteRole);
        web.route("plugins", this::plugins);
    }

    // ---- Plugins, deren Rechte mitverwaltet werden ----

    private String pluginsJson() {
        JsonArray a = new JsonArray();
        List<String> managed = perms.managedPlugins();
        for (org.bukkit.plugin.Plugin pl : Bukkit.getPluginManager().getPlugins()) {
            if (pl.getName().equals("PeakCore")) continue;
            JsonObject o = new JsonObject();
            o.addProperty("name", pl.getName());
            o.addProperty("count", PermissionService.permsOf(pl).size());
            o.addProperty("managed", managed.stream().anyMatch(x -> x.equalsIgnoreCase(pl.getName())));
            a.add(o);
        }
        return GSON.toJson(a);
    }

    private WebResponse plugins(WebRequest r) throws Exception {
        if (r.method().equals("POST")) {
            JsonObject o = GSON.fromJson(r.text(), JsonObject.class);
            String name = text(o, "name");
            boolean on = o.has("on") && o.get("on").getAsBoolean();
            WebResponse res = web.onMain(() -> {
                perms.setManaged(name, on);
                return WebResponse.json(pluginsJson());
            });
            record(r.admin(), "Rechte-Verwaltung fuer Plugin " + name + (on ? " eingeschaltet" : " ausgeschaltet"));
            web.changed("players", r.admin());
            return res;
        }
        return web.onMain(() -> WebResponse.json(pluginsJson()));
    }

    // ---- Lesen ----

    private String meta() {
        JsonObject o = new JsonObject();
        JsonArray roles = new JsonArray();
        for (PermStore.Role r : store.roles()) roles.add(roleJson(r));
        o.add("roles", roles);
        JsonArray mods = new JsonArray();
        webModules.get().forEach(mods::add);
        o.add("modules", mods);
        JsonArray catalog = new JsonArray();
        for (Permission p : perms.catalog()) {
            JsonObject c = new JsonObject();
            c.addProperty("node", p.getName());
            c.addProperty("module", PermissionService.moduleOf(p.getName()));
            c.addProperty("desc", p.getDescription() == null ? "" : p.getDescription());
            c.addProperty("default", p.getDefault().name());
            catalog.add(c);
        }
        o.add("catalog", catalog);
        return GSON.toJson(o);
    }

    private static JsonObject roleJson(PermStore.Role r) {
        JsonObject j = new JsonObject();
        j.addProperty("id", r.id);
        j.addProperty("nameDe", r.nameDe);
        j.addProperty("nameEn", r.nameEn);
        j.addProperty("web", r.web);
        j.addProperty("builtin", r.builtin);
        JsonArray nodes = new JsonArray();
        r.nodes.stream().sorted().forEach(nodes::add);
        j.add("nodes", nodes);
        JsonArray mods = new JsonArray();
        r.modules.stream().sorted().forEach(mods::add);
        j.add("modules", mods);
        return j;
    }

    private String list() {
        Map<UUID, JsonObject> rows = new LinkedHashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) rows.put(p.getUniqueId(), row(p.getUniqueId(), p.getName(), true, p.isOp(), p.getLastLogin()));
        for (var e : store.all().entrySet()) {
            if (rows.containsKey(e.getKey())) continue;
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            rows.put(e.getKey(), row(e.getKey(), nameOf(e.getKey(), e.getValue().name), false, op.isOp(), op.getLastSeen()));
        }
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (rows.size() >= MAX_LIST) break;
            if (rows.containsKey(op.getUniqueId()) || op.getName() == null) continue;
            rows.put(op.getUniqueId(), row(op.getUniqueId(), op.getName(), false, op.isOp(), op.getLastSeen()));
        }
        JsonArray a = new JsonArray();
        rows.values().forEach(a::add);
        return GSON.toJson(a);
    }

    private JsonObject row(UUID id, String name, boolean online, boolean op, long lastSeen) {
        JsonObject o = new JsonObject();
        PermStore.Entry e = store.entry(id);
        o.addProperty("uuid", id.toString());
        o.addProperty("name", name);
        o.addProperty("online", online);
        o.addProperty("op", op);
        o.addProperty("role", e == null ? "" : e.role);
        o.addProperty("lastSeen", lastSeen);
        o.addProperty("sanctioned", e != null && store.sanctioned(id, PermStore.ALL_SCOPE) || (e != null && e.sanctions.stream().anyMatch(s -> s.active(System.currentTimeMillis()))));
        o.addProperty("notes", e == null ? 0 : e.notes.size());
        return o;
    }

    private static String nameOf(UUID id, String stored) {
        if (stored != null && !stored.isBlank()) return stored;
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : id.toString();
    }

    private WebResponse player(WebRequest r) throws Exception {
        String[] p = r.path().split("/"); // player/<uuid>[/<aktion>[/<kennung>]]
        UUID id;
        try {
            id = UUID.fromString(p[1]);
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Ungueltige Spieler-Kennung");
        }
        if (p.length == 2 && r.method().equals("GET")) return web.onMain(() -> WebResponse.json(detail(id)));
        if (p.length == 3 && r.method().equals("POST")) {
            JsonObject o = GSON.fromJson(r.text(), JsonObject.class);
            switch (p[2]) {
                case "role" -> {
                    String role = text(o, "role");
                    return change(r, id, "Rolle gesetzt: " + role, name -> store.setRole(id, name, role));
                }
                case "perm" -> {
                    String node = text(o, "node"), mode = text(o, "mode");
                    return change(r, id, "Recht " + node + " = " + mode, name -> store.setPermission(id, name, node, mode));
                }
                case "note" -> {
                    String t = text(o, "text");
                    return change(r, id, "Notiz hinzugefuegt", name -> store.addNote(id, name, r.admin(), t));
                }
                case "warn" -> {
                    String reason = text(o, "reason");
                    return change(r, id, "Verwarnung: " + reason, name -> store.addSanction(id, name, r.admin(), PermStore.ALL_SCOPE, reason, 0, true));
                }
                case "sanction" -> {
                    String scope = text(o, "scope").toLowerCase(Locale.ROOT), reason = text(o, "reason");
                    long hours = o.has("hours") ? o.get("hours").getAsLong() : 0;
                    return change(r, id, "Sperre " + scope + (hours == 0 ? " (dauerhaft)" : " (" + hours + " h)") + ": " + reason, name -> store.addSanction(id, name, r.admin(), scope, reason, hours, false));
                }
                default -> {
                    return WebResponse.error(404, "Unbekannte Aktion");
                }
            }
        }
        if (p.length == 4 && r.method().equals("DELETE")) {
            switch (p[2]) {
                case "note" -> {
                    int index = Integer.parseInt(p[3]);
                    return change(r, id, "Notiz geloescht", name -> store.removeNote(id, index));
                }
                case "sanction" -> {
                    return change(r, id, "Sperre/Verwarnung aufgehoben", name -> store.removeSanction(id, p[3]));
                }
                default -> {
                    return WebResponse.error(404, "Unbekannte Aktion");
                }
            }
        }
        return WebResponse.error(404, "Unbekannte Adresse");
    }

    private interface Edit {
        void run(String name);
    }

    /** Aendern auf dem Haupt-Thread, Rechte neu setzen, protokollieren und die neue Ansicht liefern. */
    private WebResponse change(WebRequest r, UUID id, String what, Edit edit) throws Exception {
        WebResponse res = web.onMain(() -> {
            String name = nameOf(id, store.entry(id) == null ? null : store.entry(id).name);
            Player online = Bukkit.getPlayer(id);
            if (online != null) name = online.getName();
            edit.run(name);
            if (online != null) perms.apply(online);
            return WebResponse.json(detail(id));
        });
        String name = nameOf(id, store.entry(id) == null ? null : store.entry(id).name);
        record(r.admin(), "Spieler " + name + ": " + what);
        web.changed("players", r.admin());
        return res;
    }

    private void record(String admin, String what) {
        web.log(admin, what);
        Bukkit.getLogger().info("[PeakCore/Web] " + admin + ": " + what);
    }

    private String detail(UUID id) {
        PermStore.Entry e = store.entry(id);
        Player online = Bukkit.getPlayer(id);
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        String name = online != null ? online.getName() : nameOf(id, e == null ? null : e.name);
        JsonObject o = new JsonObject();
        o.addProperty("uuid", id.toString());
        o.addProperty("name", name);
        o.addProperty("online", online != null);
        o.addProperty("op", op.isOp());
        o.addProperty("role", e == null ? "" : e.role);
        o.addProperty("lastSeen", online != null ? System.currentTimeMillis() : op.getLastSeen());
        PermStore.Grant g = store.webGrant(id, op.isOp());
        JsonObject w = new JsonObject();
        w.addProperty("access", g != null);
        w.addProperty("all", g != null && g.all());
        JsonArray wm = new JsonArray();
        if (g != null && !g.all()) g.modules().stream().sorted().forEach(wm::add);
        w.add("modules", wm);
        o.add("web", w);
        List<String> names = perms.catalogNames();
        Map<String, Boolean> eff = store.effective(id, names);
        PermStore.Role role = e == null ? null : store.role(e.role);
        java.util.Set<String> fromRole = role == null ? java.util.Set.of() : PermStore.expand(role.nodes, names);
        JsonArray rights = new JsonArray();
        for (Permission p : perms.catalog()) {
            String node = p.getName();
            JsonObject j = new JsonObject();
            j.addProperty("node", node);
            j.addProperty("module", PermissionService.moduleOf(node));
            j.addProperty("desc", p.getDescription() == null ? "" : p.getDescription());
            String mode = e != null && e.denies.contains(node) ? "deny" : e != null && e.grants.contains(node) ? "allow" : "inherit";
            j.addProperty("mode", mode);
            j.addProperty("fromRole", fromRole.contains(node));
            boolean value = online != null ? online.hasPermission(node) : eff.getOrDefault(node, p.getDefault().getValue(op.isOp()));
            j.addProperty("effective", value);
            j.addProperty("sanctioned", eff.containsKey(node) && !eff.get(node) && e != null && e.sanctions.stream().anyMatch(s -> s.active(System.currentTimeMillis())));
            rights.add(j);
        }
        o.add("rights", rights);
        JsonArray notes = new JsonArray();
        JsonArray sanctions = new JsonArray();
        if (e != null) {
            for (int i = 0; i < e.notes.size(); i++) {
                PermStore.Note n = e.notes.get(i);
                JsonObject j = new JsonObject();
                j.addProperty("index", i);
                j.addProperty("time", n.time);
                j.addProperty("admin", n.admin);
                j.addProperty("text", n.text);
                notes.add(j);
            }
            long now = System.currentTimeMillis();
            for (PermStore.Sanction s : e.sanctions) {
                JsonObject j = new JsonObject();
                j.addProperty("id", s.id);
                j.addProperty("scope", s.scope);
                j.addProperty("reason", s.reason);
                j.addProperty("admin", s.admin);
                j.addProperty("time", s.time);
                j.addProperty("until", s.until);
                j.addProperty("warning", s.warning);
                j.addProperty("active", s.active(now));
                sanctions.add(j);
            }
        }
        o.add("notes", notes);
        o.add("sanctions", sanctions);
        JsonArray history = new JsonArray();
        try {
            for (JsonObject line : log.recent(500)) {
                if (line.toString().contains(name)) history.add(line);
            }
        } catch (java.io.IOException ignored) {
            // kein Protokoll lesbar
        }
        o.add("history", history);
        return GSON.toJson(o);
    }

    // ---- Rollen ----

    private WebResponse saveRole(WebRequest r) throws Exception {
        if (!r.method().equals("POST")) return WebResponse.error(405, "POST");
        JsonObject o = GSON.fromJson(r.text(), JsonObject.class);
        String id = text(o, "id");
        List<String> nodes = list(o, "nodes"), modules = list(o, "modules");
        boolean webAccess = o.has("web") && o.get("web").getAsBoolean();
        WebResponse res = web.onMain(() -> {
            store.putRole(id, text(o, "nameDe"), o.has("nameEn") ? o.get("nameEn").getAsString() : "", nodes, webAccess, modules);
            perms.applyAll();
            return WebResponse.json(meta());
        });
        record(r.admin(), "Rolle gespeichert: " + id);
        web.changed("players", r.admin());
        return res;
    }

    private WebResponse deleteRole(WebRequest r) throws Exception {
        if (!r.method().equals("DELETE")) return WebResponse.error(405, "DELETE");
        String id = r.path().substring("roles/".length());
        WebResponse res = web.onMain(() -> {
            store.deleteRole(id);
            perms.applyAll();
            return WebResponse.json(meta());
        });
        record(r.admin(), "Rolle geloescht: " + id);
        web.changed("players", r.admin());
        return res;
    }

    private static List<String> list(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) o.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
        return out;
    }

    private static String text(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) throw new IllegalArgumentException("Feld fehlt: " + key);
        return o.get(key).getAsString().strip();
    }
}
