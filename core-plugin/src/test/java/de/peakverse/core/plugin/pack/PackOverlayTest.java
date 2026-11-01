package de.peakverse.core.plugin.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Packs mit Overlays (z. B. Vanilla Tweaks): Overlay-Ordner kommen ins fertige Pack, lose Dateien im Hauptordner stoeren nicht. */
class PackOverlayTest {
    private static final String MCMETA = "{\"pack\":{\"pack_format\":46,\"description\":\"x\"},\"overlays\":{\"entries\":["
            + "{\"formats\":[84,88],\"directory\":\"overlay_84_88\"},{\"min_format\":88,\"max_format\":88,\"directory\":\"overlay_88\"},"
            + "{\"formats\":1,\"directory\":\"../evil\"},{\"formats\":2,\"directory\":\"assets\"}]}}";

    private Path zip(Path dir, Map<String, String> files) throws IOException {
        Path p = dir.resolve("p.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(p))) {
            for (var e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return p;
    }

    private Map<String, String> files() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pack.mcmeta", MCMETA);
        m.put("assets/minecraft/textures/item/a.png", "a");
        m.put("overlay_84_88/assets/minecraft/models/block/leaves1.json", "{}");
        m.put("overlay_88/assets/minecraft/shaders/core/text.vsh", "v");
        m.put("overlay_99_unknown/assets/minecraft/x.png", "u");
        m.put("pumpkin_credits.txt", "danke");
        m.put("Selected Packs.txt", "liste mit Leerzeichen im Namen");
        m.put("evil/../assets/x.png", "e");
        return m;
    }

    @Test
    void overlayFoldersListedInTheMcmetaAreKeptAndLooseRootFilesAreSilent(@TempDir Path dir) throws Exception {
        PackBase.Loaded l = PackBase.load(zip(dir, files()));
        assertTrue(l.files().containsKey("assets/minecraft/textures/item/a.png"));
        assertTrue(l.files().containsKey("overlay_84_88/assets/minecraft/models/block/leaves1.json"));
        assertTrue(l.files().containsKey("overlay_88/assets/minecraft/shaders/core/text.vsh"));
        assertFalse(l.files().containsKey("overlay_99_unknown/assets/minecraft/x.png"), "ein Ordner, der nicht in der mcmeta steht, ist kein Overlay");
        assertTrue(l.skipped().stream().anyMatch(s -> s.startsWith("overlay_99_unknown/")));
        assertTrue(l.skipped().stream().noneMatch(s -> s.contains("pumpkin_credits") || s.contains("Selected Packs")), "lose Dateien im Hauptordner sind keine Meldung wert");
        JsonObject first = JsonParser.parseString("{\"e\":" + l.overlays() + "}").getAsJsonObject();
        assertEquals(2, first.getAsJsonArray("e").size(), "unsichere Ordnernamen (../evil, assets) fallen weg");
    }

    @Test
    void builtPackCarriesTheOverlayEntriesInItsMcmeta(@TempDir Path dir) throws Exception {
        PackBase.Loaded l = PackBase.load(zip(dir, files()));
        PackZip.Built built = PackZip.build(l.files(), new PackMeta("Server", 88, 0), l.icon(), l.overlays());
        Path out = dir.resolve("out.zip");
        Files.write(out, built.zip());
        try (ZipFile z = new ZipFile(out.toFile())) {
            assertNotNull(z.getEntry("overlay_84_88/assets/minecraft/models/block/leaves1.json"));
            try (InputStream in = z.getInputStream(z.getEntry("pack.mcmeta"))) {
                JsonObject meta = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals("Server", meta.getAsJsonObject("pack").get("description").getAsString());
                var entries = meta.getAsJsonObject("overlays").getAsJsonArray("entries");
                assertEquals(2, entries.size());
                assertEquals("overlay_84_88", entries.get(0).getAsJsonObject().get("directory").getAsString());
            }
        }
    }

    @Test
    void packWithoutOverlaysKeepsTheOldMcmeta() {
        String json = new String(new PackMeta("x", 88, 0).toJson(), StandardCharsets.UTF_8);
        assertFalse(json.contains("overlays"));
        assertNull(PackBase.overlayEntries("{\"pack\":{}}", new java.util.HashSet<>()));
        assertNull(PackBase.overlayEntries("{\"overlays\":{\"entries\":[]}}", new java.util.HashSet<>()));
        assertTrue(new String(new PackMeta("x", 88, 0).toJson("[{\"directory\":\"o\"}]"), StandardCharsets.UTF_8).contains("\"overlays\""));
    }
}
