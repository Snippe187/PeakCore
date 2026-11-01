package de.peakverse.core.plugin.pack;

import de.peakverse.core.api.pack.PackPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Liest das Basis-Pack (das ZIP, das ein Admin hochgeladen oder abgelegt hat). Das Pack ist Fremdmaterial und wird entsprechend vorsichtig gelesen:
 * Grenzen fuer Anzahl und Groesse (Schutz vor ZIP-Bomben), nur gueltige Pack-Pfade (Schutz vor Pfad-Tricks), nichts wird auf die Platte entpackt.
 * {@code pack.mcmeta} setzt der Core; ein {@code pack.png} wird als Icon uebernommen.
 */
public final class PackBase {
    static final int MAX_ENTRIES = 20_000;
    static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 250L * 1024 * 1024;

    /**
     * @param skipped Dateien, die nicht uebernommen wurden (mit Grund, die ersten 20); lose Dateien im Hauptordner (Lizenz, Liesmich) zaehlen nicht dazu
     * @param overlays die Eintraege "overlays.entries" aus der pack.mcmeta des Packs als JSON-Text (leer, wenn es keine gibt); die Ordner dazu sind in iles, die mcmeta setzt der Core
     */
    public record Loaded(Map<String, byte[]> files, byte[] icon, List<String> skipped, String overlays) {
        public Loaded(Map<String, byte[]> files, byte[] icon, List<String> skipped) {
            this(files, icon, skipped, "");
        }
    }

    private PackBase() {}

    /** @throws IOException wenn die Datei kein lesbares ZIP ist oder die Grenzen ueberschritten werden */
    public static Loaded load(Path zipFile) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        List<String> skipped = new ArrayList<>();
        byte[] icon = null;
        long total = 0;
        int seen = 0;
        String overlayJson = "";
        Set<String> overlayDirs = new HashSet<>();
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry meta = zip.getEntry("pack.mcmeta");
            if (meta != null && meta.getSize() <= 1024 * 1024) {
                try (InputStream in = zip.getInputStream(meta)) {
                    JsonArray entries = overlayEntries(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), overlayDirs);
                    if (entries != null) overlayJson = entries.toString();
                } catch (RuntimeException e) {
                    // kaputte mcmeta: dann eben ohne Overlays, der Core schreibt eine eigene
                }
            }
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                if (entry.isDirectory()) continue;
                if (++seen > MAX_ENTRIES) throw new IOException("Das Basis-Pack hat mehr als " + MAX_ENTRIES + " Dateien");
                String name = entry.getName();
                if (name.equals("pack.mcmeta")) continue;
                if (name.indexOf('/') < 0 && !name.equals("pack.png")) continue; // lose Datei im Hauptordner (Lizenz, Liesmich, Auswahl-Liste): gehoert nicht ins Pack, ist aber kein Fehler
                boolean isIcon = name.equals("pack.png");
                if (!isIcon) {
                    try {
                        PackPaths.validate(name);
                    } catch (IllegalArgumentException e) {
                        skip(skipped, name + " (ungueltiger Pfad)");
                        continue;
                    }
                    if (!name.startsWith("assets/") && !isOverlayFile(name, overlayDirs)) {
                        skip(skipped, name + " (nicht unter assets/)");
                        continue;
                    }
                }
                byte[] data = readBounded(zip, entry, Math.min(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - total), name);
                total += data.length;
                if (isIcon) icon = data;
                else files.put(name, data);
            }
        }
        return new Loaded(files, icon, List.copyOf(skipped), overlayJson);
    }

    /** Ordner eines Overlays ({@code overlay_84/assets/...}): nur die, die in der pack.mcmeta des Packs stehen. */
    private static boolean isOverlayFile(String name, Set<String> dirs) {
        int slash = name.indexOf('/');
        return slash > 0 && dirs.contains(name.substring(0, slash)) && name.startsWith("/assets/", slash);
    }

    /** Liest "overlays.entries" aus einer pack.mcmeta; sammelt die Ordnernamen. Eintraege mit unsicherem Ordnernamen fallen weg. @return die Eintraege oder {@code null} */
    static JsonArray overlayEntries(String mcmeta, Set<String> dirsOut) {
        JsonObject root = com.google.gson.JsonParser.parseString(mcmeta).getAsJsonObject();
        if (!root.has("overlays") || !root.get("overlays").isJsonObject()) return null;
        JsonObject overlays = root.getAsJsonObject("overlays");
        if (!overlays.has("entries") || !overlays.get("entries").isJsonArray()) return null;
        JsonArray kept = new JsonArray();
        for (JsonElement e : overlays.getAsJsonArray("entries")) {
            if (!e.isJsonObject() || !e.getAsJsonObject().has("directory")) continue;
            String dir = e.getAsJsonObject().get("directory").getAsString();
            if (!dir.matches("[a-z0-9_.-]{1,64}") || dir.equals("assets") || dir.startsWith(".")) continue;
            if (kept.size() >= 64) break;
            dirsOut.add(dir);
            kept.add(e);
        }
        return kept.isEmpty() ? null : kept;
    }

    private static void skip(List<String> skipped, String what) {
        if (skipped.size() < 20) skipped.add(what);
    }

    private static byte[] readBounded(ZipFile zip, ZipEntry entry, long limit, String name) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            long n = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                n += r;
                if (n > limit) throw new IOException("Das Basis-Pack ist zu gross (Datei " + name + " ueberschreitet die Grenze)");
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }
}
