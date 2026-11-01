package de.peakverse.core.plugin.perm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rollen und Spieler-Daten von PeakCore ({@code roles.json}, {@code players.json}). Bukkit-frei und mit austauschbarer Uhr testbar.
 * Wer keinen Eintrag hat, bleibt unberuehrt: es gelten genau die Rechte, die Server und Rechte-Plugin ohnehin vergeben (wichtig fuer die Alpha-Daten).
 */
public final class PermStore {
    /** Eine Rolle: Rechte, Web-Zugang und welche Web-Seiten sie sieht ({@code *} = alle). */
    public static final class Role {
        public String id;
        public String nameDe;
        public String nameEn;
        public Set<String> nodes = new HashSet<>();
        public boolean web;
        public Set<String> modules = new HashSet<>();
        public boolean builtin;

        Role() { }

        Role(String id, String de, String en, boolean web, boolean builtin, Collection<String> nodes, Collection<String> modules) {
            this.id = id;
            this.nameDe = de;
            this.nameEn = en;
            this.web = web;
            this.builtin = builtin;
            this.nodes = new HashSet<>(nodes);
            this.modules = new HashSet<>(modules);
        }
    }

    public static final class Note {
        public long time;
        public String admin;
        public String text;
    }

    /** Verwarnung oder Sperre; {@code scope} ist ein Modul ({@code peaksound}), {@code web} oder {@code all}. {@code until == 0}: dauerhaft. */
    public static final class Sanction {
        public String id;
        public String scope;
        public String reason;
        public String admin;
        public long time;
        public long until;
        public boolean warning;

        public boolean active(long now) {
            return !warning && (until == 0 || until > now);
        }
    }

    public static final class Entry {
        public String name = "";
        public String role = "";
        public Set<String> grants = new HashSet<>();
        public Set<String> denies = new HashSet<>();
        public List<Note> notes = new ArrayList<>();
        public List<Sanction> sanctions = new ArrayList<>();
    }

    /** Was ein Spieler im Web darf. */
    public record Grant(boolean all, Set<String> modules) {
        public boolean allows(String module) {
            // Die Spieler-Seite darf nur, wer ohnehin alles darf: sonst koennte man sich selbst Rechte geben
            return all || (!module.equals("players") && !module.equals("update") && modules.contains(module));
        }
    }

    public static final String WEB_SCOPE = "web";
    public static final String ALL_SCOPE = "all";
    public static final int MAX_NOTES = 200;
    public static final int MAX_SANCTIONS = 200;
    public static final int MAX_TEXT = 500;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path rolesFile;
    private final Path playersFile;
    private final LongSupplier clock;
    private final Logger logger;
    private final Map<String, Role> roles = new LinkedHashMap<>();
    private final Map<UUID, Entry> players = new HashMap<>();

    public PermStore(Path dataFolder, LongSupplier clock, Logger logger) {
        this.rolesFile = dataFolder.resolve("roles.json");
        this.playersFile = dataFolder.resolve("players.json");
        this.clock = clock;
        this.logger = logger;
    }

    // ---- Laden und Speichern ----

    public synchronized void load() {
        roles.clear();
        players.clear();
        try {
            if (Files.isRegularFile(rolesFile)) {
                try (Reader r = Files.newBufferedReader(rolesFile)) {
                    List<Role> list = GSON.fromJson(r, new TypeToken<List<Role>>() { }.getType());
                    if (list != null) for (Role role : list) if (validId(role.id)) roles.put(role.id, role);
                }
            }
            if (Files.isRegularFile(playersFile)) {
                try (Reader r = Files.newBufferedReader(playersFile)) {
                    Map<String, Entry> map = GSON.fromJson(r, new TypeToken<Map<String, Entry>>() { }.getType());
                    if (map != null) {
                        for (var e : map.entrySet()) {
                            try {
                                players.put(UUID.fromString(e.getKey()), e.getValue());
                            } catch (IllegalArgumentException ignored) {
                                logger.warning("players.json: ungueltige Kennung uebersprungen: " + e.getKey());
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "Rollen/Spieler konnten nicht gelesen werden (die Dateien bleiben unveraendert)", e);
        }
        addBuiltIns();
    }

    /** Fertige Rollen: fehlen sie, werden sie angelegt; eigene Aenderungen an ihnen bleiben erhalten. */
    private void addBuiltIns() {
        roles.putIfAbsent("player", new Role("player", "Spieler", "Player", false, true, List.of(), List.of()));
        roles.putIfAbsent("dj", new Role("dj", "DJ", "DJ", true, true, List.of("peaksound.library.manage.any", "peaksound.limit.unlimited"), List.of("peaksound")));
        roles.putIfAbsent("texture-team", new Role("texture-team", "Texturen-Team", "Texture team", true, true, List.of(), List.of("texturepeak")));
        roles.putIfAbsent("scoreboard", new Role("scoreboard", "Scoreboard", "Scoreboard", true, true, List.of(), List.of("peakboard")));
        roles.putIfAbsent("admin", new Role("admin", "Admin", "Admin", true, true, List.of("peakcore.admin", "peaksound.admin", "peaksound.library.manage.any", "peaksound.limit.unlimited"), List.of("*")));
    }

    public synchronized void save() {
        try {
            Files.createDirectories(rolesFile.getParent());
            writeAtomic(rolesFile, GSON.toJson(new ArrayList<>(roles.values())));
            Map<String, Entry> map = new LinkedHashMap<>();
            players.forEach((k, v) -> map.put(k.toString(), v));
            writeAtomic(playersFile, GSON.toJson(map));
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Rollen/Spieler konnten nicht gespeichert werden", e);
        }
    }

    private static void writeAtomic(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp)) {
            w.write(text);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    public static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9][a-z0-9_-]{0,31}");
    }

    // ---- Rollen ----

    public synchronized List<Role> roles() {
        return new ArrayList<>(roles.values());
    }

    public synchronized Role role(String id) {
        return roles.get(id);
    }

    /** Legt eine Rolle an oder aendert sie. Fertige Rollen lassen sich aendern, aber nicht loeschen. */
    public synchronized Role putRole(String id, String nameDe, String nameEn, Collection<String> nodes, boolean web, Collection<String> modules) {
        if (!validId(id)) throw new IllegalArgumentException("Ungueltige Rollen-Kennung (nur a-z, 0-9, - und _).");
        if (nameDe == null || nameDe.isBlank() || nameDe.length() > 40) throw new IllegalArgumentException("Der Name der Rolle fehlt oder ist zu lang.");
        for (String n : nodes) if (!n.matches("[a-z0-9_.*-]{1,80}")) throw new IllegalArgumentException("Ungueltiges Recht: " + n);
        for (String m : modules) if (!m.matches("[a-z0-9_*-]{1,32}")) throw new IllegalArgumentException("Ungueltiges Modul: " + m);
        Role old = roles.get(id);
        Role r = new Role(id, nameDe.strip(), nameEn == null || nameEn.isBlank() ? nameDe.strip() : nameEn.strip(), web, old != null && old.builtin, nodes, modules);
        roles.put(id, r);
        save();
        return r;
    }

    public synchronized void deleteRole(String id) {
        Role r = roles.get(id);
        if (r == null) throw new IllegalArgumentException("Diese Rolle gibt es nicht.");
        if (r.builtin) throw new IllegalArgumentException("Fertige Rollen lassen sich nicht loeschen.");
        roles.remove(id);
        for (Entry e : players.values()) if (id.equals(e.role)) e.role = "";
        save();
    }

    // ---- Spieler ----

    public synchronized Entry entry(UUID id) {
        return players.get(id);
    }

    public synchronized boolean has(UUID id) {
        return players.containsKey(id);
    }

    public synchronized Map<UUID, Entry> all() {
        return new HashMap<>(players);
    }

    private Entry edit(UUID id, String name) {
        Entry e = players.computeIfAbsent(id, k -> new Entry());
        if (name != null && !name.isBlank()) e.name = name;
        return e;
    }

    public synchronized void setRole(UUID id, String name, String roleId) {
        if (roleId != null && !roleId.isEmpty() && !roles.containsKey(roleId)) throw new IllegalArgumentException("Diese Rolle gibt es nicht.");
        edit(id, name).role = roleId == null ? "" : roleId;
        save();
    }

    /** {@code mode}: {@code allow}, {@code deny} oder {@code inherit} (Eintrag entfernen). */
    public synchronized void setPermission(UUID id, String name, String node, String mode) {
        if (!node.matches("[a-z0-9_.*-]{1,80}")) throw new IllegalArgumentException("Ungueltiges Recht: " + node);
        Entry e = edit(id, name);
        e.grants.remove(node);
        e.denies.remove(node);
        switch (mode) {
            case "allow" -> e.grants.add(node);
            case "deny" -> e.denies.add(node);
            case "inherit" -> { }
            default -> throw new IllegalArgumentException("Unbekannter Modus: " + mode);
        }
        save();
    }

    public synchronized void addNote(UUID id, String name, String admin, String text) {
        text = clean(text);
        if (text.isEmpty()) throw new IllegalArgumentException("Die Notiz ist leer.");
        Entry e = edit(id, name);
        if (e.notes.size() >= MAX_NOTES) throw new IllegalArgumentException("Zu viele Notizen (hoechstens " + MAX_NOTES + ").");
        Note n = new Note();
        n.time = clock.getAsLong();
        n.admin = admin;
        n.text = text;
        e.notes.add(n);
        save();
    }

    public synchronized void removeNote(UUID id, int index) {
        Entry e = players.get(id);
        if (e == null || index < 0 || index >= e.notes.size()) throw new IllegalArgumentException("Diese Notiz gibt es nicht.");
        e.notes.remove(index);
        save();
    }

    /** Verwarnung ({@code warning}) oder Sperre mit Ablauf in Stunden ({@code hours == 0}: dauerhaft). */
    public synchronized Sanction addSanction(UUID id, String name, String admin, String scope, String reason, long hours, boolean warning) {
        reason = clean(reason);
        if (reason.isEmpty()) throw new IllegalArgumentException("Bitte einen Grund angeben.");
        if (!scope.equals(WEB_SCOPE) && !scope.equals(ALL_SCOPE) && !scope.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("Ungueltiger Bereich: " + scope);
        if (hours < 0 || hours > 24 * 365) throw new IllegalArgumentException("Die Dauer muss zwischen 0 (dauerhaft) und 8760 Stunden liegen.");
        Entry e = edit(id, name);
        if (e.sanctions.size() >= MAX_SANCTIONS) throw new IllegalArgumentException("Zu viele Eintraege (hoechstens " + MAX_SANCTIONS + ").");
        Sanction s = new Sanction();
        s.id = Long.toString(clock.getAsLong(), 36) + Integer.toString(e.sanctions.size(), 36);
        s.scope = scope;
        s.reason = reason;
        s.admin = admin;
        s.time = clock.getAsLong();
        s.until = warning || hours == 0 ? 0 : clock.getAsLong() + hours * 3_600_000L;
        s.warning = warning;
        e.sanctions.add(s);
        save();
        return s;
    }

    public synchronized void removeSanction(UUID id, String sanctionId) {
        Entry e = players.get(id);
        if (e == null || !e.sanctions.removeIf(s -> s.id.equals(sanctionId))) throw new IllegalArgumentException("Diesen Eintrag gibt es nicht.");
        save();
    }

    public synchronized void rememberName(UUID id, String name) {
        Entry e = players.get(id);
        if (e != null && name != null && !name.equals(e.name)) {
            e.name = name;
            save();
        }
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.strip().replaceAll("[\\p{Cntrl}&&[^\\n]]", "");
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    // ---- Auswertung ----

    /** Alle Muster ({@code peaksound.*}) gegen den Katalog aufloesen. */
    static Set<String> expand(Collection<String> patterns, Collection<String> catalog) {
        Set<String> out = new HashSet<>();
        for (String p : patterns) {
            if (p.equals("*")) out.addAll(catalog);
            else if (p.endsWith(".*")) {
                String prefix = p.substring(0, p.length() - 1);
                for (String n : catalog) if (n.startsWith(prefix)) out.add(n);
            } else out.add(p);
        }
        return out;
    }

    /**
     * Die Rechte, die PeakCore diesem Spieler gibt oder nimmt ({@code true}/{@code false}). Reihenfolge: Rolle und Einzelrechte erlauben,
     * Einzel-Verbote und aktive Sperren verbieten (Verbot gewinnt). Ohne Eintrag: leer, also nichts veraendern.
     */
    public synchronized Map<String, Boolean> effective(UUID id, Collection<String> catalog) {
        Entry e = players.get(id);
        Map<String, Boolean> out = new HashMap<>();
        if (e == null) return out;
        long now = clock.getAsLong();
        Role role = roles.get(e.role);
        if (role != null) for (String n : expand(role.nodes, catalog)) out.put(n, true);
        for (String n : expand(e.grants, catalog)) out.put(n, true);
        for (String n : expand(e.denies, catalog)) out.put(n, false);
        for (Sanction s : e.sanctions) {
            if (!s.active(now) || s.scope.equals(WEB_SCOPE)) continue;
            for (String n : catalog) if (s.scope.equals(ALL_SCOPE) || n.startsWith(s.scope + ".")) out.put(n, false);
        }
        return out;
    }

    public synchronized boolean sanctioned(UUID id, String scope) {
        Entry e = players.get(id);
        if (e == null) return false;
        long now = clock.getAsLong();
        return e.sanctions.stream().anyMatch(s -> s.active(now) && (s.scope.equals(scope) || s.scope.equals(ALL_SCOPE)));
    }

    /** Web-Zugang: Operatoren alles, sonst nur mit einer Rolle mit Web-Zugang und ohne aktive Web- oder Gesamt-Sperre; {@code null}: kein Zugang. */
    public synchronized Grant webGrant(UUID id, boolean op) {
        if (op) return new Grant(true, Set.of());
        Entry e = players.get(id);
        if (e == null) return null;
        Role role = roles.get(e.role);
        if (role == null || !role.web || sanctioned(id, WEB_SCOPE)) return null;
        boolean all = role.modules.contains("*");
        return new Grant(all, all ? Set.of() : Set.copyOf(role.modules));
    }
}

