package de.peakverse.core.plugin.pack;

import de.peakverse.core.api.pack.PackPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Eigene Pack-Dateien, die ein Administrator ueber die Web-Oberflaeche ablegt ({@code plugins/PeakCore/pack-files/assets/...}).
 * Gleiche Vorsicht wie beim Basis-Pack: nur gueltige Pfade unter {@code assets/}, Grenzen fuer Anzahl und Groesse.
 */
public final class PackCustom {
    public static final int MAX_FILES = 5000;
    public static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 150L * 1024 * 1024;

    private PackCustom() {}

    /** Prueft einen Pack-Pfad fuer eigene Dateien. @throws IllegalArgumentException mit Erklaerung */
    public static void checkPath(String path) {
        if (path == null || !path.startsWith("assets/")) throw new IllegalArgumentException("Der Pfad muss mit assets/ beginnen.");
        if (path.length() > 200) throw new IllegalArgumentException("Der Pfad ist zu lang.");
        PackPaths.validate(path);
    }

    /** Liest alle eigenen Dateien. Unbrauchbare Dateien werden mit Grund in {@code problems} gemeldet und ausgelassen. */
    public static Map<String, byte[]> read(Path root, List<String> problems) {
        Map<String, byte[]> out = new TreeMap<>();
        long total = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                String name = root.relativize(p).toString().replace('\\', '/');
                try {
                    checkPath(name);
                    long size = Files.size(p);
                    if (size > MAX_FILE_BYTES || total + size > MAX_TOTAL_BYTES || out.size() >= MAX_FILES) throw new IllegalArgumentException("Grenze ueberschritten");
                    out.put(name, Files.readAllBytes(p));
                    total += size;
                } catch (IllegalArgumentException | IOException e) {
                    problems.add("Eigene Datei " + name + " nicht uebernommen: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            problems.add("Eigene Dateien nicht lesbar: " + e.getMessage());
        }
        return out;
    }
}
