package de.peakverse.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.peakverse.core.api.pack.PackPaths;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackPathsTest {
    @Test
    void acceptsNormalPackPaths() {
        for (String p : List.of("pack.mcmeta", "assets/texturepeak/textures/item/ruby.png", "assets/minecraft/items/stone.json", "a-b_c.d/e")) {
            assertEquals(p, PackPaths.validate(p));
        }
    }

    @Test
    void rejectsDangerousOrInvalidPaths() {
        for (String p : List.of("", "/etc/x", "../x", "a/../b", "a/./b", "a//b", "a/", "A/b", "a b", "a\\b", "ä/b", "C:/x")) {
            assertThrows(IllegalArgumentException.class, () -> PackPaths.validate(p), "sollte abgelehnt werden: '" + p + "'");
        }
        assertThrows(IllegalArgumentException.class, () -> PackPaths.validate(null));
        assertThrows(IllegalArgumentException.class, () -> PackPaths.validate("a".repeat(251)));
    }

    @Test
    void findsTheNamespace() {
        assertEquals("texturepeak", PackPaths.namespaceOf("assets/texturepeak/lang/de_de.json"));
        assertNull(PackPaths.namespaceOf("pack.mcmeta"));
        assertNull(PackPaths.namespaceOf("assets/only"));
    }
}
