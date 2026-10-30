package de.peakverse.core.plugin.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.peakverse.core.api.module.ModuleContext;
import de.peakverse.core.api.module.PeakModule;
import de.peakverse.core.api.pack.PackSink;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackTest {
    private static final PackMeta META = new PackMeta("Test \"Pack\"", 88, 0);

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static PeakModule module(String id, Consumer<PackSink> contribution) {
        return new PeakModule() {
            public String id() { return id; }
            public String displayName() { return id; }
            public void enable(ModuleContext c) {}
            public void disable() {}
            public void contributePack(PackSink sink) { contribution.accept(sink); }
        };
    }

    private static List<String> names(byte[] zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) names.add(e.getName());
        }
        return names;
    }

    @Test
    void sameFilesGiveSameHashRegardlessOfOrder() {
        Map<String, byte[]> a = new LinkedHashMap<>();
        a.put("assets/x/b.json", b("{}"));
        a.put("assets/x/a.json", b("[1]"));
        Map<String, byte[]> c = new LinkedHashMap<>();
        c.put("assets/x/a.json", b("[1]"));
        c.put("assets/x/b.json", b("{}"));
        PackZip.Built one = PackZip.build(a, META, null);
        PackZip.Built two = PackZip.build(c, META, null);
        assertEquals(one.sha1(), two.sha1());
        assertArrayEquals(one.zip(), two.zip());
        assertEquals(40, one.sha1().length());
    }

    @Test
    void sha1BelongsToTheZipBytes() {
        PackZip.Built built = PackZip.build(Map.of("assets/x/a.json", b("{}")), META, null);
        assertEquals(PackZip.sha1(built.zip()), built.sha1());
    }

    @Test
    void anyChangeChangesTheHash() {
        String base = PackZip.build(Map.of("assets/x/a.json", b("{}")), META, null).sha1();
        assertNotEquals(base, PackZip.build(Map.of("assets/x/a.json", b("{ }")), META, null).sha1());
        assertNotEquals(base, PackZip.build(Map.of("assets/x/b.json", b("{}")), META, null).sha1());
        assertNotEquals(base, PackZip.build(Map.of("assets/x/a.json", b("{}")), new PackMeta("anders", 88, 0), null).sha1());
    }

    @Test
    void zipIsSortedAndHasMcmetaAndOptionalIcon() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("assets/z/z.png", new byte[] {1, 2, 3});
        files.put("assets/a/a.json", b("{}"));
        assertEquals(List.of("assets/a/a.json", "assets/z/z.png", "pack.mcmeta"), names(PackZip.build(files, META, null).zip()));
        assertEquals(List.of("assets/a/a.json", "assets/z/z.png", "pack.mcmeta", "pack.png"), names(PackZip.build(files, META, new byte[] {9}).zip()));
    }

    @Test
    void mcmetaIsValidJsonWithEscapedDescription() {
        String json = new String(META.toJson(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"description\": \"Test \\\"Pack\\\"\""), json);
        assertTrue(json.contains("\"min_format\": [88, 0]") && json.contains("\"max_format\": [88, 0]"), json);
    }

    @Test
    void coreOwnedFilesCannotBeSuppliedByModules() {
        PackAssembler assembler = new PackAssembler();
        assertThrows(IllegalArgumentException.class, () -> assembler.sinkFor("m").file("pack.mcmeta", b("{}")));
        assertThrows(IllegalArgumentException.class, () -> PackZip.build(Map.of("pack.mcmeta", b("{}")), META, null));
    }

    @Test
    void secondContributionToTheSamePathIsRejectedAndNamesBothOwners() {
        PackAssembler assembler = new PackAssembler();
        assembler.sinkFor("alpha").file("assets/x/a.json", b("1"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> assembler.sinkFor("beta").file("assets/x/a.json", b("2")));
        assertTrue(e.getMessage().contains("alpha") && e.getMessage().contains("beta"), e.getMessage());
        assertEquals(Map.of("alpha", 1), assembler.countsByOwner());
    }

    @Test
    void invalidPathsAreRejected() {
        PackAssembler assembler = new PackAssembler();
        assertThrows(IllegalArgumentException.class, () -> assembler.sinkFor("m").file("../evil", b("x")));
        assertThrows(IllegalArgumentException.class, () -> assembler.sinkFor("m").file("assets/X/a.json", b("x")));
    }

    @Test
    void assemblerCopiesTheData() {
        PackAssembler assembler = new PackAssembler();
        byte[] data = {1, 2};
        assembler.sinkFor("m").file("assets/x/a.bin", data);
        data[0] = 99;
        assertEquals(1, assembler.files().get("assets/x/a.bin")[0]);
    }

    @Test
    void serviceBuildsWritesAndReusesTheFile(@TempDir Path dir) throws IOException {
        PackService service = new PackService(() -> List.of(module("one", s -> s.text("assets/one/a.json", "{}"))), dir, () -> META, Logger.getAnonymousLogger());
        PackService.Status first = service.rebuild();
        assertTrue(first.written());
        Path file = dir.resolve("pack-" + first.sha1() + ".zip");
        assertTrue(Files.exists(file));
        assertEquals(PackZip.sha1(Files.readAllBytes(file)), first.sha1());
        assertEquals(Map.of("one", 1), first.filesByModule());

        PackService.Status second = service.rebuild();
        assertEquals(first.sha1(), second.sha1());
        assertFalse(second.written(), "gleicher Inhalt: nichts neu schreiben");
    }

    @Test
    void failingModuleIsLeftOutWholeAndOthersStay(@TempDir Path dir) throws IOException {
        PeakModule broken = module("broken", s -> {
            s.text("assets/broken/a.json", "{}");
            throw new IllegalStateException("kaputt");
        });
        PeakModule good = module("good", s -> s.text("assets/good/a.json", "{}"));
        PackService service = new PackService(() -> List.of(broken, good), dir, () -> META, Logger.getAnonymousLogger());
        PackService.Status status = service.rebuild();
        assertEquals(Map.of("good", 1), status.filesByModule());
        assertEquals(1, status.problems().size());
        assertTrue(status.problems().get(0).startsWith("broken: "));
        assertEquals(List.of("assets/good/a.json", "pack.mcmeta"), names(service.built().zip()));
    }

    @Test
    void clashingModuleIsLeftOutWhole(@TempDir Path dir) throws IOException {
        PeakModule first = module("first", s -> s.text("assets/shared/a.json", "1"));
        PeakModule second = module("second", s -> {
            s.text("assets/second/own.json", "x");
            s.text("assets/shared/a.json", "2");
        });
        PackService service = new PackService(() -> List.of(first, second), dir, () -> META, Logger.getAnonymousLogger());
        PackService.Status status = service.rebuild();
        assertEquals(Map.of("first", 1), status.filesByModule());
        assertTrue(status.problems().get(0).contains("first"), status.problems().toString());
        assertEquals(List.of("assets/shared/a.json", "pack.mcmeta"), names(service.built().zip()));
    }

    @Test
    void oldPacksArePrunedButTheCurrentOneStays(@TempDir Path dir) throws IOException {
        int[] n = {0};
        PackService service = new PackService(() -> List.of(module("m", s -> s.text("assets/m/a.json", "v" + n[0]))), dir, () -> META, Logger.getAnonymousLogger());
        String last = null;
        for (n[0] = 0; n[0] < PackService.KEEP + 3; n[0]++) {
            last = service.rebuild().sha1();
            Files.setAttribute(dir.resolve("pack-" + last + ".zip"), "lastModifiedTime", java.nio.file.attribute.FileTime.fromMillis(1_000_000L * (n[0] + 1)));
        }
        try (var files = Files.list(dir)) {
            assertEquals(PackService.KEEP, files.count());
        }
        assertTrue(Files.exists(dir.resolve("pack-" + last + ".zip")));
    }

    @Test
    void emptyPackStillHasMcmeta(@TempDir Path dir) throws IOException {
        PackService service = new PackService(List::of, dir, () -> META, Logger.getAnonymousLogger());
        PackService.Status status = service.rebuild();
        assertEquals(1, status.fileCount());
        assertEquals(List.of("pack.mcmeta"), names(service.built().zip()));
    }
}
