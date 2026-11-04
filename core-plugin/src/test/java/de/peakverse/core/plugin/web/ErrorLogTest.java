package de.peakverse.core.plugin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorLogTest {
    @TempDir
    Path dir;

    private ErrorLog log(long[] clock) {
        return new ErrorLog(dir.resolve("errors.json"), () -> clock[0], Logger.getLogger("test"));
    }

    @Test
    void sameMessageIsGroupedEvenWithDifferentNumbersAndPlayers() {
        long[] t = {1000};
        ErrorLog l = log(t);
        l.report("PeakCore", "ERROR", "Pack bei Port 8123 kaputt", null, "Anna");
        t[0] = 2000;
        l.report("PeakCore", "ERROR", "Pack bei Port 9999 kaputt", null, "Ben");
        l.report("PeakCore", "ERROR", "Pack bei Port 9999 kaputt", null, "Anna");
        assertEquals(1, l.size());
        var e = l.list().get(0);
        assertEquals(3, e.count);
        assertEquals(1000, e.first);
        assertEquals(2000, e.last);
        assertEquals(2, e.players.size());
    }

    @Test
    void differentSourceOrLevelIsSeparate() {
        ErrorLog l = log(new long[] {1});
        l.report("A", "ERROR", "x", null, null);
        l.report("B", "ERROR", "x", null, null);
        l.report("A", "WARN", "x", null, null);
        assertEquals(3, l.size());
    }

    @Test
    void savesLoadsResolvesAndClears() {
        long[] t = {5};
        ErrorLog l = log(t);
        l.report("A", "ERROR", "eins", "stack", "P");
        l.report("A", "WARN", "zwei", null, null);
        l.saveIfDirty();
        ErrorLog again = log(t);
        again.load();
        assertEquals(2, again.size());
        String id = again.list().get(0).id;
        assertTrue(again.resolve(id));
        assertEquals(1, again.size());
        assertEquals(1, again.clear());
        assertEquals(0, again.size());
    }

    @Test
    void brokenFileIsKeptAside() throws Exception {
        Files.writeString(dir.resolve("errors.json"), "{kaputt");
        ErrorLog l = log(new long[] {1});
        l.load();
        assertEquals(0, l.size());
        assertTrue(Files.exists(dir.resolve("errors.json.kaputt")));
    }

    @Test
    void limitsTheNumberOfGroups() {
        long[] t = {0};
        ErrorLog l = log(t);
        for (int i = 0; i < ErrorLog.MAX_GROUPS + 20; i++) {
            t[0]++;
            l.report("A", "ERROR", "Meldung " + "x".repeat(i % 7) + (char) ('a' + i % 26) + (char) ('a' + i / 26 % 26) + (char) ('a' + i / 676), null, null);
        }
        assertTrue(l.size() <= ErrorLog.MAX_GROUPS);
    }

    @Test
    void captureOnlyTakesPeakVerseWarningsAndErrors() {
        ErrorLog l = log(new long[] {1});
        var h = l.capture();
        h.publish(new LogRecord(Level.INFO, "info"));
        LogRecord other = new LogRecord(Level.SEVERE, "fremd");
        other.setLoggerName("Minecraft");
        h.publish(other);
        LogRecord mine = new LogRecord(Level.WARNING, "meins");
        mine.setLoggerName("PeakSound");
        h.publish(mine);
        LogRecord err = new LogRecord(Level.SEVERE, "kaputt");
        err.setLoggerName("PeakCore");
        err.setThrown(new IllegalStateException("boom"));
        h.publish(err);
        assertEquals(2, l.size());
        assertTrue(l.list().stream().anyMatch(e -> e.detail.contains("boom") && e.level.equals("ERROR")));
    }
}
