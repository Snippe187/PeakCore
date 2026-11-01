package de.peakverse.core.plugin.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackLibraryTest {
    private Path dir;
    private PackLibrary lib;
    private long now = 1_000;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        dir = tmp;
        lib = new PackLibrary(tmp.resolve("lib"), () -> now, Logger.getAnonymousLogger());
    }

    private static final String MCMETA = "{\"pack\":{\"pack_format\":46,\"description\":{\"text\":\"Mein \",\"extra\":[\"Pack\"]}}}";

    private Path zip(String name, Map<String, String> files) throws IOException {
        Path p = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(p))) {
            for (var e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return p;
    }

    private Map<String, String> good() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pack.mcmeta", MCMETA);
        m.put("pack.png", "\u0089PNG....");
        m.put("assets/minecraft/textures/item/stick.png", "x");
        m.put("assets/minecraft/textures/block/dirt.png", "y");
        m.put("assets/minecraft/sounds/a.ogg", "OggS");
        m.put("assets/minecraft/models/item/stick.json", "{}");
        return m;
    }

    @Test
    void goodPackIsInspectedAndStored() throws Exception {
        PackLibrary.Entry e = lib.addFile(zip("Mein_Pack.zip", good()), "Mein_Pack.zip");
        assertEquals("Mein_Pack", e.name);
        assertEquals(46, e.format);
        assertEquals("Mein Pack", e.description);
        assertEquals(2, e.textures);
        assertEquals(1, e.sounds);
        assertEquals(1, e.models);
        assertTrue(e.icon);
        assertFalse(e.repaired);
        assertTrue(Files.isRegularFile(lib.zipPath(e.id)));
        assertNotNull(lib.icon(e.id));
        assertEquals(1, lib.list().size());

        PackLibrary again = new PackLibrary(dir.resolve("lib"), () -> now, Logger.getAnonymousLogger());
        again.load();
        assertEquals("Mein_Pack", again.get(e.id).name, "bleibt nach einem Neustart erhalten");
    }

    @Test
    void nestedFolderIsRepaired() throws Exception {
        Map<String, String> nested = new LinkedHashMap<>();
        good().forEach((k, v) -> nested.put("MeinPack-v2/" + k, v));
        PackLibrary.Entry e = lib.addFile(zip("n.zip", nested), "n.zip");
        assertTrue(e.repaired);
        assertTrue(e.warnings.get(0).contains("MeinPack-v2/"));
        assertEquals(2, e.textures);
        try (ZipFile z = new ZipFile(lib.zipPath(e.id).toFile())) {
            assertNotNull(z.getEntry("assets/minecraft/textures/item/stick.png"), "der Unterordner ist weg");
            assertNotNull(z.getEntry("pack.mcmeta"));
        }
    }

    @Test
    void notAPackIsRefusedWithAClearMessage() throws Exception {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> lib.addFile(zip("a.zip", Map.of("plugin.yml", "x", "de/x/Y.class", "z")), "a.zip")).getMessage().contains("kein Resource-Pack"));
        Path notZip = dir.resolve("b.zip");
        Files.writeString(notZip, "ich bin kein zip");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> lib.addFile(notZip, "b.zip")).getMessage().contains("kein ZIP"));
        Map<String, String> two = new LinkedHashMap<>();
        two.put("a/assets/x/y.png", "1");
        two.put("b/assets/x/y.png", "2");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> lib.addFile(zip("c.zip", two), "c.zip")).getMessage().contains("mehrere Packs"));
        assertTrue(lib.list().isEmpty());
    }

    @Test
    void missingOrBrokenMcmetaIsOnlyAWarningAndBadPathsAreCounted() throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("assets/minecraft/textures/item/a.png", "x");
        m.put("assets/minecraft/textures/../../evil.png", "x");
        PackLibrary.Entry e = lib.addFile(zip("m.zip", m), "m.zip");
        assertEquals(-1, e.format);
        assertTrue(e.warnings.stream().anyMatch(w -> w.contains("pack.mcmeta fehlt")));
        assertTrue(e.warnings.stream().anyMatch(w -> w.contains("ungueltiger Pfad")));
        assertEquals(1, e.textures);

        Map<String, String> broken = new LinkedHashMap<>();
        broken.put("pack.mcmeta", "{kaputt");
        broken.put("assets/minecraft/textures/item/b.png", "y");
        PackLibrary.Entry e2 = lib.addFile(zip("m2.zip", broken), "m2.zip");
        assertTrue(e2.warnings.stream().anyMatch(w -> w.contains("kein gueltiges JSON")));
    }

    @Test
    void samePackTwiceIsRefused() throws Exception {
        Path z = zip("s.zip", good());
        lib.addFile(z, "s.zip");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> lib.addFile(z, "s2.zip")).getMessage().contains("schon in der Bibliothek"));
        assertEquals(1, lib.list().size());
    }

    @Test
    void chunkedUploadAssemblesChecksAndGuardsTheOrder() throws Exception {
        byte[] all = Files.readAllBytes(zip("u.zip", good()));
        int half = all.length / 2;
        String id = lib.beginUpload("hochgeladen.zip", all.length);
        assertThrows(IllegalArgumentException.class, () -> lib.chunk(id, 1, Arrays.copyOfRange(all, half, all.length)), "falsche Reihenfolge");
        lib.chunk(id, 0, Arrays.copyOfRange(all, 0, half));
        assertThrows(IllegalArgumentException.class, () -> lib.finish(id), "unvollstaendig");
        // nach dem Fehler ist der Upload weg: neu beginnen
        String id2 = lib.beginUpload("hochgeladen.zip", all.length);
        lib.chunk(id2, 0, Arrays.copyOfRange(all, 0, half));
        lib.chunk(id2, 1, Arrays.copyOfRange(all, half, all.length));
        PackLibrary.Entry e = lib.finish(id2);
        assertEquals("hochgeladen", e.name);
        assertEquals(all.length, e.size);
        assertFalse(Files.exists(dir.resolve("lib/uploading/" + id2 + ".part")), "Zwischendatei ist weg");
    }

    @Test
    void uploadLimitsAndTooMuchDataAreRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> lib.beginUpload("x.zip", PackLibrary.MAX_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> lib.beginUpload("x.zip", 0));
        String id = lib.beginUpload("x.zip", 10);
        assertThrows(IllegalArgumentException.class, () -> lib.chunk(id, 0, new byte[11]));
        assertThrows(IllegalArgumentException.class, () -> lib.chunk(id, 0, new byte[1]), "der Upload wurde dabei abgebrochen");
        for (int i = 0; i < 3; i++) lib.beginUpload("y.zip", 10);
        assertThrows(IllegalArgumentException.class, () -> lib.beginUpload("z.zip", 10), "hoechstens drei gleichzeitig");
        now += 31 * 60_000L;
        assertNotNull(lib.beginUpload("z.zip", 10), "alte Uploads laufen ab");
    }

    @Test
    void renameDeleteAndConflicts() throws Exception {
        PackLibrary.Entry e = lib.addFile(zip("r.zip", good()), "r.zip");
        lib.rename(e.id, "Winter-Look", "fuer die Weihnachtszeit");
        assertEquals("Winter-Look", lib.get(e.id).name);
        assertEquals("fuer die Weihnachtszeit", lib.get(e.id).note);
        assertThrows(IllegalArgumentException.class, () -> lib.rename(e.id, "  ", ""));
        assertThrows(IllegalArgumentException.class, () -> lib.rename("00000000", "x", ""));

        List<String[]> c = lib.conflicts(e.id, Map.of("assets/minecraft/textures/item/stick.png", "texturepeak", "assets/other/x.png", "peaksound"), 10);
        assertEquals(1, c.size());
        assertEquals("texturepeak", c.get(0)[1]);

        lib.delete(e.id);
        assertNull(lib.get(e.id));
        assertFalse(Files.exists(dir.resolve("lib/" + e.id + ".zip")));
        assertThrows(IllegalArgumentException.class, () -> lib.zipPath("../etc"));
    }

    @Test
    void windowsBackslashPathsAreRepaired() throws Exception {
        Map<String, String> bs = new LinkedHashMap<>();
        bs.put("MeinPack\\pack.mcmeta", MCMETA);
        bs.put("MeinPack\\assets\\minecraft\\textures\\item\\stick.png", "x");
        PackLibrary.Entry e = lib.addFile(zip("bs.zip", bs), "bs.zip");
        assertTrue(e.repaired);
        assertEquals(46, e.format, "pack.mcmeta wurde trotz Rueckwaerts-Schraegstrichen gelesen");
        assertTrue(e.warnings.stream().anyMatch(w -> w.contains("Rueckwaerts-Schraegstrichen")));
        try (ZipFile z = new ZipFile(lib.zipPath(e.id).toFile())) {
            assertNotNull(z.getEntry("assets/minecraft/textures/item/stick.png"));
            assertNotNull(z.getEntry("pack.mcmeta"));
        }
    }

    @Test
    void fileNamesAreCleaned() throws Exception {
        PackLibrary.Entry e = lib.addFile(zip("f.zip", good()), "C:\\Users\\neo\\<Mein|Pack>.zip");
        assertEquals("MeinPack", e.name);
        assertFalse(e.file.contains("\\") || e.file.contains("<"));
        ByteArrayOutputStream ignore = new ByteArrayOutputStream();
        ignore.close();
    }
}
