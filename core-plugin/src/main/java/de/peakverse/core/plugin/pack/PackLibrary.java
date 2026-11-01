package de.peakverse.core.plugin.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import de.peakverse.core.api.pack.PackPaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Die Pack-Bibliothek: mehrere fertige Resource-Packs (ZIP), von denen genau eines das Server-Pack ist. Ein Pack wird in Stuecken hochgeladen
 * (die Web-Anfragen sind klein), beim Abschluss geprueft (Aufbau, pack.mcmeta, Grenzen) und bei Bedarf repariert (ein ZIP, das alles in einem
 * Unterordner enthaelt). Bukkit-frei und mit austauschbarer Uhr testbar. Dateien: {@code <id>.zip} und {@code index.json} im Bibliotheksordner.
 */
public final class PackLibrary {
    public static final long MAX_BYTES = 250L * 1024 * 1024;
    public static final int MAX_ENTRIES = 20_000;
    public static final int MAX_PACKS = 30;
    public static final int MAX_CHUNK = 6 * 1024 * 1024;
    private static final long UPLOAD_TTL_MS = 30 * 60_000L;
    private static final int MAX_UPLOADS = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Ein Pack in der Bibliothek. */
    public static final class Entry {
        public String id;
        public String name;
        public String note = "";
        public String file = "";
        public String sha256;
        public long size;
        public long added;
        /** Hauptzahl der pack_format-Angabe im Pack, {@code -1}, wenn keine da ist. */
        public int format = -1;
        public String description = "";
        public int files;
        public int textures;
        public int sounds;
        public int models;
        public boolean icon;
        public boolean repaired;
        public List<String> warnings = new ArrayList<>();
    }

    /** Ergebnis der Pruefung eines ZIPs. {@code prefix}: Unterordner, in dem das Pack steckt ({@code ""} = direkt im ZIP). */
    record Inspection(String prefix, int format, String description, int files, int textures, int sounds, int models, boolean icon, List<String> warnings, boolean backslashes) { }

    private static final class Upload {
        final Path part;
        final String name;
        final long total;
        final long started;
        long written;
        int next;

        Upload(Path part, String name, long total, long started) {
            this.part = part;
            this.name = name;
            this.total = total;
            this.started = started;
        }
    }

    private final Path dir;
    private final LongSupplier clock;
    private final Logger logger;
    private final SecureRandom random = new SecureRandom();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Upload> uploads = new HashMap<>();

    public PackLibrary(Path dir, LongSupplier clock, Logger logger) {
        this.dir = dir;
        this.clock = clock;
        this.logger = logger;
    }

    // ---- Lesen und Speichern ----

    public synchronized void load() {
        entries.clear();
        Path index = dir.resolve("index.json");
        if (!Files.isRegularFile(index)) return;
        try {
            List<Entry> list = GSON.fromJson(Files.readString(index, StandardCharsets.UTF_8), new TypeToken<List<Entry>>() { }.getType());
            if (list != null) for (Entry e : list) if (e != null && e.id != null && e.id.matches("[0-9a-f]{8}") && Files.isRegularFile(zipPath(e.id))) entries.add(e);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "pack-library/index.json konnte nicht gelesen werden (die ZIP-Dateien bleiben liegen)", e);
        }
    }

    private void save() throws IOException {
        Files.createDirectories(dir);
        Path tmp = dir.resolve("index.json.tmp");
        Files.writeString(tmp, GSON.toJson(entries), StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized List<Entry> list() {
        return List.copyOf(entries);
    }

    public synchronized Entry get(String id) {
        return entries.stream().filter(e -> e.id.equals(id)).findFirst().orElse(null);
    }

    public Path zipPath(String id) {
        if (!id.matches("[0-9a-f]{8}")) throw new IllegalArgumentException("Ungueltige Pack-Kennung");
        return dir.resolve(id + ".zip");
    }

    /** Das Vorschaubild ({@code pack.png}) des Packs oder {@code null}. */
    public byte[] icon(String id) throws IOException {
        Entry e = get(id);
        if (e == null || !e.icon) return null;
        try (ZipFile zip = new ZipFile(zipPath(id).toFile())) {
            ZipEntry z = zip.getEntry("pack.png");
            if (z == null || z.getSize() > 4 * 1024 * 1024) return null;
            try (InputStream in = zip.getInputStream(z)) {
                return in.readAllBytes();
            }
        }
    }

    // ---- Hochladen in Stuecken ----

    /** Beginnt einen Upload. @return die Upload-Kennung */
    public synchronized String beginUpload(String fileName, long totalSize) throws IOException {
        purgeUploads();
        if (totalSize <= 0 || totalSize > MAX_BYTES) throw new IllegalArgumentException("Das Pack darf hoechstens " + MAX_BYTES / 1024 / 1024 + " MB gross sein.");
        if (entries.size() >= MAX_PACKS) throw new IllegalArgumentException("Die Bibliothek ist voll (hoechstens " + MAX_PACKS + " Packs). Loesche zuerst ein altes.");
        if (uploads.size() >= MAX_UPLOADS) throw new IllegalArgumentException("Es laufen schon zu viele Uploads. Bitte kurz warten.");
        Files.createDirectories(dir.resolve("uploading"));
        byte[] b = new byte[9];
        random.nextBytes(b);
        String id = HexFormat.of().formatHex(b);
        Path part = dir.resolve("uploading").resolve(id + ".part");
        Files.deleteIfExists(part);
        uploads.put(id, new Upload(part, cleanName(fileName), totalSize, clock.getAsLong()));
        return id;
    }

    /** Haengt das naechste Stueck an (in der richtigen Reihenfolge, Nummer ab 0). */
    public synchronized void chunk(String uploadId, int index, byte[] data) throws IOException {
        Upload u = uploads.get(uploadId);
        if (u == null) throw new IllegalArgumentException("Dieser Upload ist abgelaufen. Bitte noch einmal von vorn.");
        if (index != u.next) throw new IllegalArgumentException("Stueck " + index + " kam zur falschen Zeit (erwartet " + u.next + ").");
        if (data.length == 0 || data.length > MAX_CHUNK) throw new IllegalArgumentException("Ungueltige Stueck-Groesse.");
        if (u.written + data.length > u.total) {
            abort(uploadId);
            throw new IllegalArgumentException("Es kamen mehr Daten an als angekuendigt.");
        }
        Files.write(u.part, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        u.written += data.length;
        u.next++;
    }

    public synchronized void abort(String uploadId) {
        Upload u = uploads.remove(uploadId);
        if (u != null) {
            try {
                Files.deleteIfExists(u.part);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Upload-Datei konnte nicht geloescht werden", e);
            }
        }
    }

    private void purgeUploads() {
        long now = clock.getAsLong();
        for (String id : List.copyOf(uploads.keySet())) if (now - uploads.get(id).started > UPLOAD_TTL_MS) abort(id);
    }

    /** Schliesst den Upload ab: prueft, repariert wenn noetig und nimmt das Pack in die Bibliothek auf. */
    public synchronized Entry finish(String uploadId) throws IOException {
        Upload u = uploads.remove(uploadId);
        if (u == null) throw new IllegalArgumentException("Dieser Upload ist abgelaufen. Bitte noch einmal von vorn.");
        try {
            if (u.written != u.total) throw new IllegalArgumentException("Der Upload ist unvollstaendig (" + u.written + " von " + u.total + " Bytes).");
            return addFile(u.part, u.name);
        } finally {
            Files.deleteIfExists(u.part);
        }
    }

    /** Nimmt eine fertige ZIP-Datei auf (prueft und repariert sie); die Quelldatei bleibt unberuehrt. */
    public synchronized Entry addFile(Path source, String fileName) throws IOException {
        if (entries.size() >= MAX_PACKS) throw new IllegalArgumentException("Die Bibliothek ist voll (hoechstens " + MAX_PACKS + " Packs).");
        Inspection in;
        try {
            in = inspect(source);
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException("Das ist kein ZIP-Archiv oder es ist beschaedigt.");
        }
        byte[] b = new byte[4];
        random.nextBytes(b);
        String id = HexFormat.of().formatHex(b);
        Path target = zipPath(id);
        Files.createDirectories(dir);
        if (in.prefix().isEmpty() && !in.backslashes()) Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        else repair(source, in.prefix(), target);
        String sha = sha256(target);
        for (Entry e : entries) {
            if (e.sha256 != null && e.sha256.equals(sha)) {
                Files.deleteIfExists(target);
                throw new IllegalArgumentException("Dieses Pack ist schon in der Bibliothek (" + e.name + ").");
            }
        }
        Entry e = new Entry();
        e.id = id;
        e.name = cleanName(fileName).replaceAll("(?i)\\.zip$", "");
        if (e.name.isBlank()) e.name = "Pack " + id;
        e.file = cleanName(fileName);
        e.sha256 = sha;
        e.size = Files.size(target);
        e.added = clock.getAsLong();
        e.format = in.format();
        e.description = in.description();
        e.files = in.files();
        e.textures = in.textures();
        e.sounds = in.sounds();
        e.models = in.models();
        e.icon = in.icon();
        e.repaired = !in.prefix().isEmpty() || in.backslashes();
        e.warnings = new ArrayList<>(in.warnings());
        if (in.backslashes()) e.warnings.add(0, "Das ZIP hatte Pfade mit Rueckwaerts-Schraegstrichen (typisch fuer manche Windows-Programme), Minecraft kann das nicht lesen. Sie wurden automatisch korrigiert.");
        if (!in.prefix().isEmpty()) e.warnings.add(0, "Das ZIP enthielt alles in einem Unterordner (" + in.prefix() + "). Er wurde automatisch entfernt.");
        entries.add(e);
        save();
        return e;
    }

    // ---- Verwalten ----

    public synchronized Entry rename(String id, String name, String note) throws IOException {
        Entry e = get(id);
        if (e == null) throw new IllegalArgumentException("Dieses Pack gibt es nicht.");
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 60) throw new IllegalArgumentException("Der Name muss 1 bis 60 Zeichen lang sein.");
        if (note != null && note.length() > 300) throw new IllegalArgumentException("Die Notiz darf hoechstens 300 Zeichen lang sein.");
        e.name = n;
        e.note = note == null ? "" : note.strip();
        save();
        return e;
    }

    public synchronized void delete(String id) throws IOException {
        Entry e = get(id);
        if (e == null) throw new IllegalArgumentException("Dieses Pack gibt es nicht.");
        entries.remove(e);
        Files.deleteIfExists(zipPath(id));
        save();
    }

    /** Pfade des Packs, die auch ein Modul liefert (Texturen des Moduls ersetzen sie dann im fertigen Pack). */
    public List<String[]> conflicts(String id, Map<String, String> modulePathOwners, int limit) throws IOException {
        List<String[]> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipPath(id).toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements() && out.size() < limit) {
                ZipEntry z = en.nextElement();
                String owner = z.isDirectory() ? null : modulePathOwners.get(z.getName());
                if (owner != null) out.add(new String[] {z.getName(), owner});
            }
        }
        return out;
    }

    // ---- Pruefen und Reparieren ----

    private static String cleanName(String fileName) {
        String n = fileName == null ? "" : fileName.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}<>\"|?*]", "").strip();
        return n.length() > 80 ? n.substring(0, 80) : n;
    }

    /** Prueft ein ZIP: Aufbau, Grenzen, pack.mcmeta. @throws IllegalArgumentException mit einer verstaendlichen Meldung, wenn es kein brauchbares Pack ist */
    static Inspection inspect(Path zipFile) throws IOException {
        List<String> names = new ArrayList<>();
        Map<String, ZipEntry> byName = new HashMap<>();
        boolean backslashes = false;
        long declared = 0;
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry z = en.nextElement();
                if (z.isDirectory()) continue;
                if (names.size() >= MAX_ENTRIES) throw new IllegalArgumentException("Das Pack hat mehr als " + MAX_ENTRIES + " Dateien.");
                if (z.getSize() > 64L * 1024 * 1024) throw new IllegalArgumentException("Die Datei " + z.getName() + " ist ueber 64 MB gross.");
                declared += Math.max(0, z.getSize());
                if (declared > MAX_BYTES) throw new IllegalArgumentException("Entpackt ist das Pack groesser als " + MAX_BYTES / 1024 / 1024 + " MB.");
                String norm = z.getName().replace('\\', '/');
                if (!norm.equals(z.getName())) backslashes = true;
                names.add(norm);
                byName.put(norm, z);
            }
            if (names.isEmpty()) throw new IllegalArgumentException("Das ZIP ist leer.");
            String prefix = "";
            if (names.stream().noneMatch(n -> n.startsWith("assets/"))) {
                TreeSet<String> tops = new TreeSet<>();
                for (String n : names) {
                    int slash = n.indexOf('/');
                    if (slash > 0 && n.startsWith(n.substring(0, slash) + "/assets/")) tops.add(n.substring(0, slash + 1));
                }
                if (tops.size() == 1) prefix = tops.first();
                else if (tops.size() > 1) throw new IllegalArgumentException("Im ZIP liegen mehrere Packs nebeneinander (" + String.join(", ", tops) + "). Lade nur eines hoch.");
                else throw new IllegalArgumentException("Das ist kein Resource-Pack: es gibt keinen Ordner assets/. (Ein Mod- oder Plugin-Jar? Das gehoert nicht hierher.)");
            }
            List<String> warnings = new ArrayList<>();
            int files = 0, textures = 0, sounds = 0, models = 0, skipped = 0;
            boolean icon = false;
            for (String full : names) {
                if (!full.startsWith(prefix)) continue;
                String n = full.substring(prefix.length());
                if (n.equals("pack.png")) {
                    icon = true;
                    continue;
                }
                if (n.equals("pack.mcmeta")) continue;
                if (!n.startsWith("assets/")) continue;
                try {
                    PackPaths.validate(n);
                } catch (IllegalArgumentException e) {
                    if (skipped++ < 3) warnings.add("Wird nicht uebernommen (ungueltiger Pfad): " + n);
                    continue;
                }
                files++;
                if (n.contains("/textures/") && n.endsWith(".png")) textures++;
                else if (n.endsWith(".ogg")) sounds++;
                else if (n.contains("/models/") && n.endsWith(".json")) models++;
            }
            if (skipped > 3) warnings.add("... und " + (skipped - 3) + " weitere Dateien mit ungueltigem Pfad.");
            if (files == 0) throw new IllegalArgumentException("Im Pack liegen keine brauchbaren Dateien unter assets/.");

            int format = -1;
            String description = "";
            ZipEntry meta = byName.get(prefix + "pack.mcmeta");
            if (meta == null) {
                warnings.add("pack.mcmeta fehlt. Das ist kein Problem: der Server setzt eine eigene.");
            } else if (meta.getSize() > 1024 * 1024) {
                warnings.add("pack.mcmeta ist ungewoehnlich gross und wurde nicht gelesen.");
            } else {
                try (InputStream in = zip.getInputStream(meta)) {
                    JsonObject root = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject pack = root.getAsJsonObject("pack");
                    if (pack != null) {
                        format = formatOf(pack);
                        description = descriptionOf(pack.get("description"));
                    }
                    if (format < 0) warnings.add("In der pack.mcmeta steht kein pack_format.");
                } catch (RuntimeException e) {
                    warnings.add("pack.mcmeta ist kein gueltiges JSON. Das stoert nicht, der Server setzt eine eigene.");
                }
            }
            return new Inspection(prefix, format, description, files, textures, sounds, models, icon, warnings, backslashes);
        }
    }

    private static int formatOf(JsonObject pack) {
        for (String key : new String[] {"pack_format", "min_format"}) {
            if (!pack.has(key)) continue;
            JsonElement e = pack.get(key);
            try {
                if (e.isJsonArray() && !e.getAsJsonArray().isEmpty()) return e.getAsJsonArray().get(0).getAsInt();
                if (e.isJsonPrimitive()) return e.getAsInt();
            } catch (RuntimeException ignored) {
                // naechster Schluessel
            }
        }
        return -1;
    }

    private static String descriptionOf(JsonElement d) {
        if (d == null || d.isJsonNull()) return "";
        StringBuilder sb = new StringBuilder();
        collectText(d, sb);
        String s = sb.toString().replaceAll("§.", "").replaceAll("\\s+", " ").strip();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static void collectText(JsonElement e, StringBuilder sb) {
        if (e.isJsonPrimitive()) sb.append(e.getAsString());
        else if (e.isJsonArray()) e.getAsJsonArray().forEach(x -> collectText(x, sb));
        else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("text")) collectText(o.get("text"), sb);
            if (o.has("extra")) collectText(o.get("extra"), sb);
        }
    }

    /** Schreibt das ZIP ohne den Unterordner {@code prefix} neu. */
    private static void repair(Path source, String prefix, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (ZipFile zip = new ZipFile(source.toFile()); OutputStream os = Files.newOutputStream(tmp); ZipOutputStream out = new ZipOutputStream(os)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry z = en.nextElement();
                String full = z.getName().replace('\\', '/');
                if (z.isDirectory() || !full.startsWith(prefix)) continue;
                String name = full.substring(prefix.length());
                if (name.isEmpty()) continue;
                out.putNextEntry(new ZipEntry(name));
                try (InputStream in = zip.getInputStream(z)) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) d.update(buf, 0, n);
            return HexFormat.of().formatHex(d.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
