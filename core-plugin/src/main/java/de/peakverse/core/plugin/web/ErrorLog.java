package de.peakverse.core.plugin.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Sammelt Fehler und Warnungen von PeakVerse fuer den Bereich "Fehler" der Web-Oberflaeche.
 * Gleiche Meldungen werden zu einer Gruppe zusammengefasst (Anzahl, erstes und letztes Mal, betroffene Spieler).
 * Gespeichert in {@code errors.json}; eine kaputte Datei wird als {@code .kaputt} beiseitegelegt.
 */
public final class ErrorLog {
    public static final int MAX_GROUPS = 300;
    private static final int MAX_PLAYERS = 20;
    private static final int MAX_DETAIL = 3000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Eine Gruppe gleicher Meldungen. */
    public static final class Entry {
        public String id;
        public String source = "";
        public String level = "ERROR";
        public String message = "";
        public String detail = "";
        public int count;
        public long first;
        public long last;
        public List<String> players = new ArrayList<>();
    }

    private final Path file;
    private final LongSupplier clock;
    private final Logger logger;
    private final List<Entry> entries = new ArrayList<>();
    private boolean dirty;
    private Runnable onChange = () -> { };

    public ErrorLog(Path file, LongSupplier clock, Logger logger) {
        this.file = file;
        this.clock = clock;
        this.logger = logger;
    }

    /** Wird nach jeder neuen Meldung aufgerufen (fuer die Live-Aktualisierung der Seite). */
    public void onChange(Runnable r) {
        this.onChange = r == null ? () -> { } : r;
    }

    public synchronized void load() {
        entries.clear();
        if (!Files.exists(file)) return;
        try {
            List<Entry> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Entry>>() { }.getType());
            if (read != null) for (Entry e : read) if (e != null && e.id != null && e.message != null) entries.add(e);
        } catch (IOException | RuntimeException e) {
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".kaputt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // nicht schlimm: die Datei wird beim naechsten Speichern ersetzt
            }
            logger.warning("errors.json war kaputt und wurde als .kaputt beiseitegelegt: " + e);
        }
    }

    /** Merkt eine Meldung. {@code player} darf leer sein. */
    public void report(String source, String level, String message, String detail, String player) {
        if (message == null || message.isBlank()) return;
        synchronized (this) {
            String key = key(source, level, message);
            long now = clock.getAsLong();
            Entry e = null;
            for (Entry x : entries) if (x.id.equals(key)) { e = x; break; }
            if (e == null) {
                e = new Entry();
                e.id = key;
                e.source = source == null ? "" : source;
                e.level = level == null ? "ERROR" : level;
                e.first = now;
                entries.add(e);
                trim();
            }
            e.count++;
            e.last = now;
            e.message = cut(message, 600);
            if (detail != null && !detail.isBlank()) e.detail = cut(detail, MAX_DETAIL);
            if (player != null && !player.isBlank() && !e.players.contains(player) && e.players.size() < MAX_PLAYERS) e.players.add(player);
            dirty = true;
        }
        onChange.run();
    }

    private void trim() {
        while (entries.size() > MAX_GROUPS) {
            Entry oldest = entries.stream().min(Comparator.comparingLong(x -> x.last)).orElse(null);
            if (oldest == null) return;
            entries.remove(oldest);
        }
    }

    /** Neueste zuerst. */
    public synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>(entries);
        out.sort(Comparator.comparingLong((Entry x) -> x.last).reversed());
        return out;
    }

    /** @return ob es die Gruppe gab */
    public synchronized boolean resolve(String id) {
        boolean removed = entries.removeIf(x -> x.id.equals(id));
        if (removed) dirty = true;
        return removed;
    }

    public synchronized int clear() {
        int n = entries.size();
        entries.clear();
        if (n > 0) dirty = true;
        return n;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Schreibt die Datei, wenn sich etwas geaendert hat. */
    public synchronized void saveIfDirty() {
        if (!dirty) return;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(entries), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            logger.warning("errors.json konnte nicht gespeichert werden: " + e);
        }
    }

    /** Gleiche Meldung = gleicher Schluessel, auch wenn sich Zahlen, Hashes oder Spielernamen im Text unterscheiden. */
    static String key(String source, String level, String message) {
        String norm = message.replaceAll("[0-9a-fA-F]{16,}", "#").replaceAll("[0-9]+", "#").trim();
        return Integer.toHexString((source + "|" + level + "|" + norm).hashCode());
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Ein Log-Handler, der Warnungen und Fehler der PeakVerse-Logger hierher leitet. */
    public Handler capture() {
        return new Handler() {
            private final ThreadLocal<Boolean> busy = ThreadLocal.withInitial(() -> false);

            @Override
            public void publish(LogRecord r) {
                if (r == null || r.getLevel().intValue() < Level.WARNING.intValue()) return;
                String name = r.getLoggerName();
                if (name == null || !(name.startsWith("Peak") || name.equals("TexturePeak"))) return;
                if (busy.get()) return;
                busy.set(true);
                try {
                    String msg = r.getMessage() == null ? "" : r.getMessage();
                    Throwable t = r.getThrown();
                    if (msg.isBlank() && t != null) msg = t.toString();
                    String detail = null;
                    if (t != null) {
                        StringWriter sw = new StringWriter();
                        t.printStackTrace(new PrintWriter(sw));
                        detail = sw.toString();
                    }
                    report(name, r.getLevel().intValue() >= Level.SEVERE.intValue() ? "ERROR" : "WARN", msg, detail, null);
                } catch (RuntimeException ignored) {
                    // der Fehler-Sammler darf nie selbst Fehler verursachen
                } finally {
                    busy.set(false);
                }
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
    }
}
