package de.peakverse.core.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.peakverse.core.api.web.WebRegistry;
import de.peakverse.core.api.web.WebResponse;
import de.peakverse.core.plugin.pack.PackBase;
import de.peakverse.core.plugin.pack.PackCustom;
import de.peakverse.core.plugin.pack.PackLibrary;
import de.peakverse.core.plugin.pack.PackService;
import de.peakverse.core.plugin.pack.PackState;
import de.peakverse.core.plugin.pack.PackWebServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * Pack-Verwaltung in der Web-Oberflaeche (Modul "pack"): Status, Dateiliste mit Vorschau, Basis-Pack hochladen, Bauen, Testmodus, Tester,
 * Veroeffentlichen und Zurueckrollen. Gleiche Regeln wie {@code /peakcore pack ...}.
 */
final class PackWeb {
    private static final Gson GSON = new Gson();
    private static final int MAX_FILES_LISTED = 30000;
    private static final long MAX_PREVIEW = 16L * 1024 * 1024;

    private final WebRegistry web;
    private final PackService packs;
    private final PackSender sender;
    private final PackWebServer server;
    private final Path folder;
    private final Path baseTarget;
    private final Consumer<String> setBaseFile;

    private final Path customDir;
    private final PackLibrary library;
    private final Supplier<String> baseFile;
    private final IntSupplier serverFormat;
    private final Path dataFolder;

    PackWeb(WebRegistry web, PackService packs, PackSender sender, PackWebServer server, Path packFolder, Path baseTarget, Path customDir, Consumer<String> setBaseFile,
            PackLibrary library, Supplier<String> baseFile, IntSupplier serverFormat, Path dataFolder) {
        this.library = library;
        this.baseFile = baseFile;
        this.serverFormat = serverFormat;
        this.dataFolder = dataFolder;
        this.customDir = customDir;
        this.web = web;
        this.packs = packs;
        this.sender = sender;
        this.server = server;
        this.folder = packFolder;
        this.baseTarget = baseTarget;
        this.setBaseFile = setBaseFile;
    }

    void register(String script, WebRegistry areas, String areasScript) {
        web.panel("Pack", "Pack", script);
        web.route("status", r -> WebResponse.json(web.onMain(this::status)));
        web.route("files", r -> WebResponse.json(files(r.query().get("variant"))));
        web.route("file", r -> file(r.query().get("variant"), r.query().get("path")));
        web.route("rebuild", r -> mutate(web, r.admin(), "Pack gebaut", () -> {
            PackService.Status s = packs.rebuild();
            if (s.pointersChanged() && sender.enabled() && sender.currentUrl() != null) sender.sendAll(Bukkit.getOnlinePlayers());
        }));
        web.route("publish", r -> mutate(web, r.admin(), "Test-Pack veroeffentlicht", () -> {
            packs.publish();
            if (sender.enabled() && sender.currentUrl() != null) sender.sendAll(Bukkit.getOnlinePlayers());
        }));
        web.route("rollback", r -> {
            JsonObject o = GSON.fromJson(r.text(), JsonObject.class);
            String variant = o.get("variant").getAsString();
            int n = o.get("n").getAsInt();
            return mutate(web, r.admin(), "Pack zurueckgerollt: " + variant + " #" + n, () -> {
                packs.rollback(variant, n);
                if (sender.enabled() && sender.currentUrl() != null) sender.sendAll(Bukkit.getOnlinePlayers());
            });
        });
        web.route("testmode", r -> {
            boolean on = GSON.fromJson(r.text(), JsonObject.class).get("on").getAsBoolean();
            return mutate(web, r.admin(), "Testmodus " + (on ? "an" : "aus"), () -> {
                packs.setTestMode(on);
                if (!on && sender.enabled() && sender.currentUrl() != null) sender.sendAll(Bukkit.getOnlinePlayers());
            });
        });
        web.route("tester", r -> {
            JsonObject o = GSON.fromJson(r.text(), JsonObject.class);
            String name = o.get("name").getAsString();
            boolean add = o.get("add").getAsBoolean();
            return mutate(web, r.admin(), "Tester " + (add ? "hinzugefuegt" : "entfernt") + ": " + name, () -> {
                OfflinePlayer p = Bukkit.getPlayerExact(name) != null ? Bukkit.getPlayerExact(name) : Bukkit.getOfflinePlayerIfCached(name);
                if (p == null) throw new IllegalArgumentException("Spieler '" + name + "' ist dem Server nicht bekannt");
                if (add) packs.addTester(p.getUniqueId());
                else packs.removeTester(p.getUniqueId());
                if (p.isOnline() && sender.enabled()) sender.send(p.getPlayer());
            });
        });
        areas.panel("Bereiche", "Areas", areasScript);
        areas.route("custom", r -> WebResponse.json(customList()));
        areas.route("custom/*", r -> {
            String path = r.path().substring("custom/".length());
            Path target = customTarget(path);
            switch (r.method()) {
                case "GET" -> {
                    if (!Files.isRegularFile(target)) return WebResponse.error(404, "Datei nicht vorhanden");
                    return WebResponse.bytes(path.endsWith(".png") ? "image/png" : path.endsWith(".ogg") ? "audio/ogg" : "application/octet-stream", Files.readAllBytes(target));
                }
                case "PUT" -> {
                    checkContent(path, r.body());
                    return mutate(areas, r.admin(), "Eigene Datei gespeichert: " + path + " (" + r.body().length + " Bytes)", () -> {
                        Files.createDirectories(target.getParent());
                        Files.write(target, r.body());
                        packs.rebuild();
                        if (sender.enabled() && sender.currentUrl() != null) sender.sendAll(Bukkit.getOnlinePlayers());
                    });
                }
                case "DELETE" -> {
                    return mutate(areas, r.admin(), "Eigene Datei geloescht: " + path, () -> {
                        Files.deleteIfExists(target);
                        packs.rebuild();
                        if (sender.enabled() && sender.currentUrl() != null) sender.sendAll(Bukkit.getOnlinePlayers());
                    });
                }
                default -> {
                    return WebResponse.error(405, "GET, PUT oder DELETE");
                }
            }
        });
        web.route("base", r -> {
            if (r.method().equals("DELETE")) {
                return mutate(web, r.admin(), "Basis-Pack entfernt", () -> {
                    setBaseFile.accept("");
                    Files.deleteIfExists(baseTarget);
                    packs.rebuild();
                });
            }
            if (!r.method().equals("POST")) return WebResponse.error(405, "POST oder DELETE");
            Path tmp = Files.createTempFile(baseTarget.getParent(), "base-upload", ".zip");
            try {
                Files.write(tmp, r.body());
                try {
                    PackBase.Loaded loaded = PackBase.load(tmp); // prueft Aufbau und Grenzen, bevor etwas ersetzt wird
                    if (loaded.files().isEmpty()) throw new IllegalArgumentException("Das ZIP enthaelt keine Dateien unter assets/.");
                } catch (IOException e) {
                    throw new IllegalArgumentException("Kein brauchbares Resource-Pack: " + e.getMessage());
                }
                Files.move(tmp, baseTarget, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return mutate(web, r.admin(), "Basis-Pack hochgeladen (" + r.body().length + " Bytes)", () -> {
                setBaseFile.accept(baseTarget.getFileName().toString());
                packs.rebuild();
                if (sender.enabled() && sender.currentUrl() != null) sender.sendAll(Bukkit.getOnlinePlayers());
            });
        });
        registerLibrary();
    }

    // ---- Pack-Bibliothek: mehrere Packs, eines davon ist das Server-Pack ----

    private static final String LIB_PREFIX = "pack-library/";

    private void registerLibrary() {
        migrateLegacyBase();
        web.route("library", r -> {
            if (!r.method().equals("GET")) return WebResponse.error(405, "GET");
            return WebResponse.json(libraryJson());
        });
        web.route("library/begin", r -> {
            if (!r.method().equals("POST")) return WebResponse.error(405, "POST");
            JsonObject o = GSON.fromJson(r.text(), JsonObject.class);
            String id = library.beginUpload(o.get("name").getAsString(), o.get("size").getAsLong());
            JsonObject out = new JsonObject();
            out.addProperty("id", id);
            out.addProperty("chunk", PackLibrary.MAX_CHUNK - 64 * 1024);
            return WebResponse.json(GSON.toJson(out));
        });
        web.route("library/chunk", r -> {
            if (!r.method().equals("POST")) return WebResponse.error(405, "POST");
            String id = r.query().get("id");
            int n;
            try {
                n = Integer.parseInt(r.query().get("n"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Stueck-Nummer fehlt");
            }
            library.chunk(id == null ? "" : id, n, r.body());
            return WebResponse.ok();
        });
        web.route("library/abort", r -> {
            JsonObject o = GSON.fromJson(r.text(), JsonObject.class);
            library.abort(o.get("id").getAsString());
            return WebResponse.ok();
        });
        web.route("library/finish", r -> {
            if (!r.method().equals("POST")) return WebResponse.error(405, "POST");
            JsonObject o = GSON.fromJson(r.text(), JsonObject.class);
            PackLibrary.Entry e = library.finish(o.get("id").getAsString());
            web.log(r.admin(), "Pack in die Bibliothek hochgeladen: " + e.name + " (" + e.size + " Bytes)");
            web.changed("pack", r.admin());
            return WebResponse.json(libraryJson());
        });
        web.route("library/*", r -> {
            String[] p = r.path().split("/");
            if (p.length < 2) return WebResponse.error(404, "Unbekannte Adresse");
            String id = p[1];
            String action = p.length > 2 ? p[2] : "";
            PackLibrary.Entry e = library.get(id);
            if (e == null) return WebResponse.error(404, "Dieses Pack gibt es nicht.");
            switch (r.method() + " " + action) {
                case "GET icon" -> {
                    byte[] icon = library.icon(id);
                    return icon == null ? WebResponse.error(404, "Kein Vorschaubild") : WebResponse.bytes("image/png", icon);
                }
                case "GET download" -> {
                    if (e.size > MAX_DOWNLOAD) return WebResponse.error(413, "Das Pack ist zu gross fuer den Download im Browser (ueber " + MAX_DOWNLOAD / 1024 / 1024 + " MB). Die Datei liegt in plugins/PeakCore/pack-library/" + id + ".zip");
                    return WebResponse.bytes("application/zip", Files.readAllBytes(library.zipPath(id)));
                }
                case "GET conflicts" -> {
                    Map<String, String> mine = web.onMain(packs::contributedPaths);
                    var list = library.conflicts(id, mine, 200);
                    JsonArray a = new JsonArray();
                    for (String[] c : list) {
                        JsonObject j = new JsonObject();
                        j.addProperty("path", c[0]);
                        j.addProperty("owner", c[1]);
                        a.add(j);
                    }
                    return WebResponse.json(GSON.toJson(a));
                }
                case "POST rename" -> {
                    JsonObject o = GSON.fromJson(r.text(), JsonObject.class);
                    library.rename(id, o.get("name").getAsString(), o.has("note") ? o.get("note").getAsString() : "");
                    web.log(r.admin(), "Pack umbenannt: " + o.get("name").getAsString());
                    web.changed("pack", r.admin());
                    return WebResponse.json(libraryJson());
                }
                case "POST activate" -> {
                    return activate(r.admin(), e);
                }
                case "DELETE " -> {
                    if (id.equals(activeLibraryId())) throw new IllegalArgumentException("Das ist das aktive Server-Pack. Aktiviere zuerst ein anderes (oder entferne das Basis-Pack).");
                    library.delete(id);
                    web.log(r.admin(), "Pack aus der Bibliothek geloescht: " + e.name);
                    web.changed("pack", r.admin());
                    return WebResponse.json(libraryJson());
                }
                default -> {
                    return WebResponse.error(405, "Diese Aktion gibt es nicht");
                }
            }
        });
    }

    private static final long MAX_DOWNLOAD = 100L * 1024 * 1024;

    /** Macht ein Pack aus der Bibliothek zum Server-Pack, baut neu und schickt es sofort an alle Spieler online. Geht beim Bauen etwas schief, bleibt das alte Pack aktiv. */
    private WebResponse activate(String admin, PackLibrary.Entry e) throws Exception {
        String previous = baseFile.get();
        web.onMain(() -> {
            setBaseFile.accept(LIB_PREFIX + e.id + ".zip");
            try {
                packs.rebuild();
            } catch (IOException | RuntimeException ex) {
                setBaseFile.accept(previous == null ? "" : previous);
                try {
                    packs.rebuild();
                } catch (IOException | RuntimeException again) {
                    // das alte Pack ging auch nicht mehr: nichts weiter zu tun
                }
                throw new IllegalArgumentException("Das Pack konnte nicht eingebaut werden (" + ex.getMessage() + "). Das bisherige Server-Pack bleibt aktiv.");
            }
            if (sender.enabled() && sender.currentUrl() != null) sender.sendAll(Bukkit.getOnlinePlayers());
            return null;
        });
        web.log(admin, "Server-Pack gewechselt: " + e.name);
        web.changed("pack", admin);
        return WebResponse.json(libraryJson());
    }

    private String activeLibraryId() {
        String f = baseFile.get();
        if (f == null || !f.startsWith(LIB_PREFIX) || !f.endsWith(".zip")) return "";
        return f.substring(LIB_PREFIX.length(), f.length() - 4);
    }

    private String libraryJson() throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("active", activeLibraryId());
        o.addProperty("legacy", !baseFile.get().isBlank() && activeLibraryId().isEmpty());
        o.addProperty("serverFormat", serverFormat.getAsInt());
        o.addProperty("maxMb", PackLibrary.MAX_BYTES / 1024 / 1024);
        o.addProperty("maxPacks", PackLibrary.MAX_PACKS);
        JsonArray list = new JsonArray();
        for (PackLibrary.Entry e : library.list()) list.add(GSON.toJsonTree(e));
        o.add("packs", list);
        return GSON.toJson(o);
    }

    /** Ein Basis-Pack, das vor der Bibliothek hochgeladen wurde ({@code base-pack.zip}), wird einmal in die Bibliothek uebernommen. */
    private void migrateLegacyBase() {
        try {
            String f = baseFile.get();
            if (f != null && f.equals(baseTarget.getFileName().toString()) && Files.isRegularFile(baseTarget)) {
                PackLibrary.Entry e = library.addFile(baseTarget, "Basis-Pack (bisher).zip");
                setBaseFile.accept(LIB_PREFIX + e.id + ".zip");
                Files.deleteIfExists(baseTarget);
            }
        } catch (IOException | RuntimeException ex) {
            // schon uebernommen (gleiche Pruefsumme) oder nicht lesbar: das alte Verhalten bleibt, nichts geht verloren
        }
    }

    /** Zieldatei im Ordner der eigenen Dateien; nie ausserhalb davon. */
    private Path customTarget(String path) {
        PackCustom.checkPath(path);
        Path target = customDir.resolve(path).normalize();
        if (!target.startsWith(customDir.normalize())) throw new IllegalArgumentException("Ungueltiger Pfad");
        return target;
    }

    /** Kaputtes JSON in einer Sprach-, Schrift- oder Modelldatei wuerde das Pack beim Spieler beschaedigen: schon hier abweisen. */
    private static void checkContent(String path, byte[] data) {
        if (data.length == 0) throw new IllegalArgumentException("Die Datei ist leer.");
        if (data.length > PackCustom.MAX_FILE_BYTES) throw new IllegalArgumentException("Die Datei ist zu gross.");
        if (path.endsWith(".json") || path.endsWith(".mcmeta")) {
            try {
                com.google.gson.JsonParser.parseString(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Kein gueltiges JSON: " + e.getMessage());
            }
        }
        if (path.endsWith(".png") && !(data.length > 8 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G')) throw new IllegalArgumentException("Keine PNG-Datei.");
        if (path.endsWith(".ogg") && !(data.length > 4 && data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S')) throw new IllegalArgumentException("Keine OGG-Datei.");
    }

    private String customList() throws IOException {
        JsonArray list = new JsonArray();
        if (Files.isDirectory(customDir)) {
            try (var walk = Files.walk(customDir)) {
                for (Path p : walk.filter(Files::isRegularFile).sorted().limit(MAX_FILES_LISTED).toList()) {
                    JsonObject j = new JsonObject();
                    j.addProperty("path", customDir.relativize(p).toString().replace('\\', '/'));
                    j.addProperty("size", Files.size(p));
                    list.add(j);
                }
            }
        }
        return GSON.toJson(list);
    }

    private interface Action {
        void run() throws Exception;
    }

    /** Fuehrt eine Aenderung auf dem Haupt-Thread aus, protokolliert sie, meldet sie den anderen Seiten und liefert den neuen Status. */
    private WebResponse mutate(WebRegistry reg, String admin, String what, Action action) throws Exception {
        String json = reg.onMain(() -> {
            action.run();
            return status();
        });
        reg.log(admin, what);
        reg.changed("pack", admin);
        return WebResponse.json(json);
    }

    private String status() {
        JsonObject o = new JsonObject();
        PackService.Status s = packs.status();
        o.addProperty("built", s != null);
        o.addProperty("testMode", packs.testMode());
        o.addProperty("serverRunning", server.running());
        o.addProperty("sending", sender.enabled());
        String url = null;
        try {
            url = sender.currentUrl();
        } catch (IllegalArgumentException e) {
            url = null;
        }
        o.addProperty("url", url);
        String activeFile = baseFile.get();
        o.addProperty("hasBase", activeFile != null && !activeFile.isBlank() && Files.isRegularFile(dataFolder.resolve(activeFile)));
        JsonArray variants = new JsonArray();
        for (String v : PackState.VARIANTS) {
            JsonObject j = new JsonObject();
            j.addProperty("variant", v);
            j.addProperty("live", packs.liveSha(v));
            j.addProperty("test", packs.testSha(v));
            if (s != null && s.variants().get(v) != null) {
                j.addProperty("size", s.variants().get(v).size());
                j.addProperty("files", s.variants().get(v).fileCount());
            }
            JsonArray versions = new JsonArray();
            packs.versions(v).stream().limit(10).forEach(e -> {
                JsonObject x = new JsonObject();
                x.addProperty("n", e.n);
                x.addProperty("sha1", e.sha1);
                x.addProperty("size", e.size);
                x.addProperty("builtAt", e.builtAt);
                x.addProperty("available", Files.isRegularFile(folder.resolve("pack-" + e.sha1 + ".zip")));
                versions.add(x);
            });
            j.add("versions", versions);
            variants.add(j);
        }
        o.add("variants", variants);
        JsonArray testers = new JsonArray();
        for (String id : packs.testers()) {
            OfflinePlayer p = Bukkit.getOfflinePlayer(UUID.fromString(id));
            testers.add(p.getName() != null ? p.getName() : id);
        }
        o.add("testers", testers);
        JsonArray problems = new JsonArray();
        if (s != null) {
            s.problems().forEach(problems::add);
            o.addProperty("pngSavedBytes", s.pngSavedBytes());
            o.addProperty("baseFiles", s.baseFiles());
            o.addProperty("mergedFiles", s.mergedFiles());
            JsonObject by = new JsonObject();
            s.filesByModule().forEach(by::addProperty);
            o.add("filesByModule", by);
        }
        o.add("problems", problems);
        return GSON.toJson(o);
    }

    private Path zipOf(String variant) {
        String v = variant == null ? PackState.STANDARD : variant;
        if (!PackState.VARIANTS.contains(v)) throw new IllegalArgumentException("Unbekannte Variante");
        String sha = packs.liveSha(v);
        if (sha == null) throw new IllegalArgumentException("Das Pack wurde noch nicht gebaut.");
        Path zip = folder.resolve("pack-" + sha + ".zip");
        if (!Files.isRegularFile(zip)) throw new IllegalArgumentException("Die Pack-Datei fehlt.");
        return zip;
    }

    private String files(String variant) throws IOException {
        JsonArray list = new JsonArray();
        try (ZipFile zip = new ZipFile(zipOf(variant).toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements() && list.size() < MAX_FILES_LISTED) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                JsonObject j = new JsonObject();
                j.addProperty("path", e.getName());
                j.addProperty("size", e.getSize());
                list.add(j);
            }
        }
        return GSON.toJson(list);
    }

    private WebResponse file(String variant, String path) throws IOException {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("Pfad fehlt");
        try (ZipFile zip = new ZipFile(zipOf(variant).toFile())) {
            ZipEntry e = zip.getEntry(path); // nur Namen, die im Pack stehen: kein Zugriff auf andere Dateien
            if (e == null || e.isDirectory()) return WebResponse.error(404, "Datei nicht im Pack");
            if (e.getSize() > MAX_PREVIEW) throw new IllegalArgumentException("Datei zu gross fuer die Vorschau");
            try (InputStream in = zip.getInputStream(e)) {
                String type = path.endsWith(".png") ? "image/png" : path.endsWith(".json") || path.endsWith(".mcmeta") ? "application/json; charset=utf-8"
                        : path.endsWith(".ogg") ? "audio/ogg" : path.endsWith(".txt") || path.endsWith(".lang") ? "text/plain; charset=utf-8" : "application/octet-stream";
                return WebResponse.bytes(type, in.readAllBytes());
            }
        }
    }
}
