package de.peakverse.core.plugin.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Was der Pack-Dienst ueber Neustarts hinweg weiss (Datei {@code state.json} im Pack-Ordner): welches Pack je Variante gerade "live" ist,
 * welches im Testmodus nur die Tester bekommen, der Verlauf der gebauten Versionen (fuers Zurueckrollen), die Tester und die Variante je Spieler.
 */
public final class PackState {
    public static final String STANDARD = "standard";
    public static final String LITE = "lite";
    public static final List<String> VARIANTS = List.of(STANDARD, LITE);
    static final int MAX_HISTORY = 50;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Eine gebaute Version. {@code n} zaehlt ueber alle Varianten hoch und ist die Nummer fuer {@code /peakcore pack rollback}. */
    public static final class Entry {
        public int n;
        public String variant;
        public String sha1;
        public String builtAt;
        public int size;

        public Entry() {}

        public Entry(int n, String variant, String sha1, String builtAt, int size) {
            this.n = n;
            this.variant = variant;
            this.sha1 = sha1;
            this.builtAt = builtAt;
            this.size = size;
        }
    }

    public boolean testMode;
    public int nextNumber = 1;
    public Map<String, String> live = new TreeMap<>();
    public Map<String, String> test = new TreeMap<>();
    public List<Entry> history = new ArrayList<>();
    public Set<String> testers = new TreeSet<>();
    public Map<String, String> playerVariant = new TreeMap<>();

    /** Laedt den Zustand; fehlt die Datei oder ist sie kaputt, beginnt ein leerer Zustand (die kaputte Datei wird als {@code .kaputt} aufgehoben). */
    public static PackState load(Path file) {
        if (!Files.isRegularFile(file)) return new PackState();
        try {
            PackState s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), PackState.class);
            if (s == null) return new PackState();
            s.fix();
            return s;
        } catch (IOException | RuntimeException e) {
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".kaputt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // dann bleibt die Datei liegen und wird beim naechsten Speichern ueberschrieben
            }
            return new PackState();
        }
    }

    /** Gson laesst fehlende Felder {@code null}: auffuellen. */
    private void fix() {
        if (live == null) live = new TreeMap<>();
        if (test == null) test = new TreeMap<>();
        if (history == null) history = new ArrayList<>();
        if (testers == null) testers = new TreeSet<>();
        if (playerVariant == null) playerVariant = new TreeMap<>();
        if (nextNumber < 1) nextNumber = 1;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Die neueste Version einer Variante oder {@code null}. */
    public Entry latest(String variant) {
        for (int i = history.size() - 1; i >= 0; i--) if (history.get(i).variant.equals(variant)) return history.get(i);
        return null;
    }

    public Entry byNumber(int n) {
        for (Entry e : history) if (e.n == n) return e;
        return null;
    }

    public Entry add(String variant, String sha1, String builtAt, int size) {
        Entry e = new Entry(nextNumber++, variant, sha1, builtAt, size);
        history.add(e);
        while (history.size() > MAX_HISTORY) history.remove(0);
        return e;
    }
}
