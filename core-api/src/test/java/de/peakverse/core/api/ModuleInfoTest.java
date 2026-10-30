package de.peakverse.core.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModuleInfoTest {
    @Test
    void acceptsValidModules() {
        assertDoesNotThrow(() -> new ModuleInfo("peaksound", "PeakSound", "0.1.0", 1));
        assertDoesNotThrow(() -> new ModuleInfo("tp", "TexturePeak", "1.0", 1));
        assertDoesNotThrow(() -> new ModuleInfo("a_b_9", "Name", "x", 3));
        assertDoesNotThrow(() -> new ModuleInfo("a".repeat(24), "N", "v", 1));
    }

    @Test
    void rejectsBadIds() {
        for (String id : new String[] {null, "", "a", "A", "Peaksound", "1abc", "_abc", "peak-sound", "peak sound", "a".repeat(25), "peäk"}) {
            assertThrows(IllegalArgumentException.class, () -> new ModuleInfo(id, "Name", "1", 1), "ID: " + id);
        }
    }

    @Test
    void rejectsBadNamesVersionsAndApi() {
        assertThrows(IllegalArgumentException.class, () -> new ModuleInfo("abc", null, "1", 1));
        assertThrows(IllegalArgumentException.class, () -> new ModuleInfo("abc", " ", "1", 1));
        assertThrows(IllegalArgumentException.class, () -> new ModuleInfo("abc", "N".repeat(41), "1", 1));
        assertThrows(IllegalArgumentException.class, () -> new ModuleInfo("abc", "Name", null, 1));
        assertThrows(IllegalArgumentException.class, () -> new ModuleInfo("abc", "Name", "", 1));
        assertThrows(IllegalArgumentException.class, () -> new ModuleInfo("abc", "Name", "v".repeat(33), 1));
        assertThrows(IllegalArgumentException.class, () -> new ModuleInfo("abc", "Name", "1", 0));
    }
}
