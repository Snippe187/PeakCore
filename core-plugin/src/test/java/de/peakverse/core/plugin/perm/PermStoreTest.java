package de.peakverse.core.plugin.perm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermStoreTest {
    private static final List<String> CATALOG = List.of("peakcore.admin", "peaksound.upload", "peaksound.library", "peaksound.limit.unlimited", "texturepeak.edit");

    @TempDir Path dir;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final UUID player = UUID.randomUUID();
    private PermStore store;

    @BeforeEach
    void setUp() {
        store = new PermStore(dir, now::get, Logger.getAnonymousLogger());
        store.load();
    }

    @Test
    void builtInRolesExist() {
        for (String id : List.of("player", "dj", "texture-team", "scoreboard", "admin")) assertNotNull(store.role(id), id);
        assertTrue(store.role("admin").modules.contains("*"));
    }

    @Test
    void playerWithoutEntryIsUntouched() {
        assertTrue(store.effective(player, CATALOG).isEmpty());
        assertNull(store.webGrant(player, false));
    }

    @Test
    void roleGrantsAndWildcardsExpand() {
        store.putRole("musik", "Musik", "Music", List.of("peaksound.*"), false, List.of());
        store.setRole(player, "Neo", "musik");
        Map<String, Boolean> eff = store.effective(player, CATALOG);
        assertEquals(true, eff.get("peaksound.upload"));
        assertEquals(true, eff.get("peaksound.limit.unlimited"));
        assertFalse(eff.containsKey("texturepeak.edit"));
    }

    @Test
    void denyBeatsRoleAndInheritRemoves() {
        store.putRole("musik", "Musik", "Music", List.of("peaksound.*"), false, List.of());
        store.setRole(player, "Neo", "musik");
        store.setPermission(player, "Neo", "peaksound.upload", "deny");
        assertEquals(false, store.effective(player, CATALOG).get("peaksound.upload"));
        store.setPermission(player, "Neo", "peaksound.upload", "inherit");
        assertEquals(true, store.effective(player, CATALOG).get("peaksound.upload"));
        store.setPermission(player, "Neo", "texturepeak.edit", "allow");
        assertEquals(true, store.effective(player, CATALOG).get("texturepeak.edit"));
    }

    @Test
    void moduleSanctionDeniesOnlyThatModuleAndExpires() {
        store.setRole(player, "Neo", "dj");
        store.addSanction(player, "Neo", "Admin", "peaksound", "Spam", 2, false);
        Map<String, Boolean> eff = store.effective(player, CATALOG);
        assertEquals(false, eff.get("peaksound.upload"));
        assertFalse(eff.containsKey("texturepeak.edit"));
        assertTrue(store.sanctioned(player, "peaksound"));
        now.addAndGet(3 * 3_600_000L);
        assertFalse(store.sanctioned(player, "peaksound"));
        assertEquals(true, store.effective(player, CATALOG).get("peaksound.limit.unlimited"));
    }

    @Test
    void warningsDoNotBlockAnything() {
        store.setRole(player, "Neo", "dj");
        store.addSanction(player, "Neo", "Admin", "all", "Bitte ruhiger", 0, true);
        assertFalse(store.sanctioned(player, "all"));
        assertEquals(1, store.entry(player).sanctions.size());
    }

    @Test
    void webAccessFollowsRoleOpAndSanctions() {
        assertTrue(store.webGrant(player, true).all());
        store.setRole(player, "Neo", "texture-team");
        PermStore.Grant g = store.webGrant(player, false);
        assertFalse(g.all());
        assertTrue(g.allows("texturepeak"));
        assertFalse(g.allows("peaksound"));
        store.addSanction(player, "Neo", "Admin", "web", "Test", 0, false);
        assertNull(store.webGrant(player, false), "Web-Sperre");
        store.setRole(player, "Neo", "player");
        assertNull(store.webGrant(player, false), "Rolle ohne Web-Zugang");
    }

    @Test
    void adminRoleSeesEverything() {
        store.setRole(player, "Neo", "admin");
        assertTrue(store.webGrant(player, false).allows("irgendwas"));
    }

    @Test
    void persistsAndReloads() {
        store.putRole("musik", "Musik", "Music", List.of("peaksound.upload"), true, List.of("peaksound"));
        store.setRole(player, "Neo", "musik");
        store.addNote(player, "Neo", "Admin", "Nett");
        PermStore other = new PermStore(dir, now::get, Logger.getAnonymousLogger());
        other.load();
        assertEquals("musik", other.entry(player).role);
        assertEquals("Nett", other.entry(player).notes.get(0).text);
        assertTrue(other.role("musik").web);
    }

    @Test
    void invalidInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.putRole("Gross", "X", "X", List.of(), false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.putRole("ok", "X", "X", List.of("../x"), false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.setRole(player, "Neo", "gibtsnicht"));
        assertThrows(IllegalArgumentException.class, () -> store.addNote(player, "Neo", "A", "   "));
        assertThrows(IllegalArgumentException.class, () -> store.addSanction(player, "Neo", "A", "web", "", 1, false));
        assertThrows(IllegalArgumentException.class, () -> store.addSanction(player, "Neo", "A", "web", "x", -1, false));
        assertThrows(IllegalArgumentException.class, () -> store.deleteRole("admin"));
    }

    @Test
    void deletingACustomRoleFreesItsPlayers() {
        store.putRole("musik", "Musik", "Music", List.of(), false, List.of());
        store.setRole(player, "Neo", "musik");
        store.deleteRole("musik");
        assertEquals("", store.entry(player).role);
        assertNull(store.role("musik"));
    }

    @Test
    void brokenFilesDoNotCrashAndAreKept() throws Exception {
        java.nio.file.Files.writeString(dir.resolve("players.json"), "{ kaputt");
        PermStore other = new PermStore(dir, now::get, Logger.getAnonymousLogger());
        other.load();
        assertNotNull(other.role("admin"));
        assertEquals("{ kaputt", java.nio.file.Files.readString(dir.resolve("players.json")), "die kaputte Datei bleibt zur Rettung liegen");
    }
}
