package de.peakverse.core.plugin.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackCustomTest {
    @Test
    void onlyValidAssetPathsAreAccepted() {
        PackCustom.checkPath("assets/minecraft/lang/de_de.json");
        for (String bad : new String[] {null, "", "pack.mcmeta", "assets/../x.png", "/assets/x", "assets/Gross/x.png", "assets/a b/x.png", "other/x.png", "assets/" + "a/".repeat(120) + "x.png"}) {
            assertThrows(IllegalArgumentException.class, () -> PackCustom.checkPath(bad), String.valueOf(bad));
        }
    }

    @Test
    void readsFilesWithPackPathsAndSkipsBadOnes(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("assets/minecraft/lang"));
        Files.writeString(dir.resolve("assets/minecraft/lang/de_de.json"), "{}");
        Files.createDirectories(dir.resolve("assets/Bad"));
        Files.writeString(dir.resolve("assets/Bad/x.txt"), "x");
        Files.writeString(dir.resolve("loose.txt"), "x");
        List<String> problems = new ArrayList<>();
        Map<String, byte[]> files = PackCustom.read(dir, problems);
        assertEquals(List.of("assets/minecraft/lang/de_de.json"), List.copyOf(files.keySet()));
        assertEquals(2, problems.size());
    }

    @Test
    void customFilesWinOverTheBasePackInTheBuild(@TempDir Path dir) throws IOException {
        Path custom = dir.resolve("custom");
        Files.createDirectories(custom.resolve("assets/minecraft/lang"));
        Files.writeString(custom.resolve("assets/minecraft/lang/de_de.json"), "{\"a\":\"b\"}");
        PackService service = new PackService(List::of, dir.resolve("pack"), () -> new PackMeta("t", 88, 0), Logger.getLogger("t"));
        service.setCustomFolder(() -> custom);
        service.setOptimizePng(() -> false);
        PackService.Status s = service.rebuild();
        assertTrue(s.fileCount() >= 2, "pack.mcmeta + eigene Datei");
        try (var zip = new java.util.zip.ZipFile(dir.resolve("pack/pack-" + s.sha1() + ".zip").toFile())) {
            assertArrayEquals("{\"a\":\"b\"}".getBytes(), zip.getInputStream(zip.getEntry("assets/minecraft/lang/de_de.json")).readAllBytes());
        }
    }
}
