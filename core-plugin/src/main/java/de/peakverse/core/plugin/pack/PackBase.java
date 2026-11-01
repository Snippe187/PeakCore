package de.peakverse.core.plugin.pack;

import de.peakverse.core.api.pack.PackPaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
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

    /** @param skipped Dateien, die nicht uebernommen wurden (mit Grund, die ersten 20) */
    public record Loaded(Map<String, byte[]> files, byte[] icon, List<String> skipped) {}

    private PackBase() {}

    /** @throws IOException wenn die Datei kein lesbares ZIP ist oder die Grenzen ueberschritten werden */
    public static Loaded load(Path zipFile) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        List<String> skipped = new ArrayList<>();
        byte[] icon = null;
        long total = 0;
        int seen = 0;
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                if (entry.isDirectory()) continue;
                if (++seen > MAX_ENTRIES) throw new IOException("Das Basis-Pack hat mehr als " + MAX_ENTRIES + " Dateien");
                String name = entry.getName();
                if (name.equals("pack.mcmeta")) continue;
                boolean isIcon = name.equals("pack.png");
                if (!isIcon) {
                    try {
                        PackPaths.validate(name);
                    } catch (IllegalArgumentException e) {
                        skip(skipped, name + " (ungueltiger Pfad)");
                        continue;
                    }
                    if (!name.startsWith("assets/")) {
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
        return new Loaded(files, icon, List.copyOf(skipped));
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
