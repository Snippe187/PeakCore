package de.peakverse.core.plugin.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import de.peakverse.core.api.module.ModuleContext;
import de.peakverse.core.api.module.PeakModule;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackMergeTest {
    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] d) {
        return new String(d, StandardCharsets.UTF_8);
    }

    private static PackMerger.Contribution c(String owner, String... pathAndContent) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int i = 0; i < pathAndContent.length; i += 2) files.put(pathAndContent[i], b(pathAndContent[i + 1]));
        return new PackMerger.Contribution(owner, files);
    }

    private static boolean sameJson(String expected, byte[] actual) {
        return JsonParser.parseString(expected).equals(JsonParser.parseString(s(actual)));
    }

    // ---- Sprachdateien und sounds.json ----

    @Test
    void langFilesAreUnitedByKey() {
        PackMerger.Result r = PackMerger.merge(Map.of("assets/minecraft/lang/de_de.json", b("{\"a\":\"1\"}")),
                List.of(c("mod", "assets/minecraft/lang/de_de.json", "{\"b\":\"2\"}")));
        assertTrue(sameJson("{\"a\":\"1\",\"b\":\"2\"}", r.files().get("assets/minecraft/lang/de_de.json")));
        assertTrue(r.problems().isEmpty());
        assertEquals(1, r.mergedFiles());
        assertEquals(Map.of("mod", 1), r.filesByOwner());
    }

    @Test
    void sameLangKeyWithSameValueIsNoConflict() {
        PackMerger.Result r = PackMerger.merge(Map.of("assets/x/lang/en_us.json", b("{\"a\":\"1\"}")), List.of(c("mod", "assets/x/lang/en_us.json", "{\"a\":\"1\"}")));
        assertTrue(r.problems().isEmpty());
    }

    @Test
    void soundsAreUnitedByEvent() {
        PackMerger.Result r = PackMerger.merge(Map.of(), List.of(c("a", "assets/minecraft/sounds.json", "{\"x\":{\"sounds\":[\"a\"]}}"),
                c("b", "assets/minecraft/sounds.json", "{\"y\":{\"sounds\":[\"b\"]}}")));
        assertTrue(sameJson("{\"x\":{\"sounds\":[\"a\"]},\"y\":{\"sounds\":[\"b\"]}}", r.files().get("assets/minecraft/sounds.json")));
    }

    // ---- Blockstates ----

    @Test
    void blockstateVariantsAreUnitedAndSameStateWithOtherModelConflicts() {
        String path = "assets/minecraft/blockstates/note_block.json";
        PackMerger.Result ok = PackMerger.merge(Map.of(path, b("{\"variants\":{\"note=0\":{\"model\":\"a\"}}}")),
                List.of(c("mod", path, "{\"variants\":{\"note=1\":{\"model\":\"b\"}}}")));
        assertTrue(sameJson("{\"variants\":{\"note=0\":{\"model\":\"a\"},\"note=1\":{\"model\":\"b\"}}}", ok.files().get(path)));

        PackMerger.Result clash = PackMerger.merge(Map.of(path, b("{\"variants\":{\"note=0\":{\"model\":\"a\"}}}")),
                List.of(c("mod", path, "{\"variants\":{\"note=0\":{\"model\":\"other\"}}}")));
        assertEquals(1, clash.problems().size());
        assertTrue(clash.problems().get(0).detail().contains("ersetzt 1 Datei"), clash.problems().get(0).detail());
        assertTrue(sameJson("{\"variants\":{\"note=0\":{\"model\":\"other\"}}}", clash.files().get(path)), "die Textur des Moduls gewinnt");
        assertFalse(clash.problems().get(0).whole());
    }

    @Test
    void multipartListsAreAppendedWithoutDuplicates() {
        String path = "assets/minecraft/blockstates/fence.json";
        PackMerger.Result r = PackMerger.merge(Map.of(path, b("{\"multipart\":[{\"apply\":{\"model\":\"a\"}}]}")),
                List.of(c("mod", path, "{\"multipart\":[{\"apply\":{\"model\":\"a\"}},{\"apply\":{\"model\":\"b\"}}]}")));
        assertTrue(sameJson("{\"multipart\":[{\"apply\":{\"model\":\"a\"}},{\"apply\":{\"model\":\"b\"}}]}", r.files().get(path)));
    }

    @Test
    void variantsAndMultipartCannotBeMixed() {
        String path = "assets/minecraft/blockstates/x.json";
        PackMerger.Result r = PackMerger.merge(Map.of(path, b("{\"variants\":{\"\":{\"model\":\"a\"}}}")), List.of(c("mod", path, "{\"multipart\":[]}")));
        assertEquals(1, r.problems().size());
    }

    // ---- Atlanten ----

    @Test
    void atlasSourcesAreAppended() {
        String path = "assets/minecraft/atlases/blocks.json";
        PackMerger.Result r = PackMerger.merge(Map.of(path, b("{\"sources\":[{\"type\":\"directory\",\"source\":\"a\"}]}")),
                List.of(c("mod", path, "{\"sources\":[{\"type\":\"directory\",\"source\":\"a\"},{\"type\":\"directory\",\"source\":\"b\"}]}")));
        assertTrue(sameJson("{\"sources\":[{\"type\":\"directory\",\"source\":\"a\"},{\"type\":\"directory\",\"source\":\"b\"}]}", r.files().get(path)));
    }

    // ---- Item-Definitionen ----

    private static final String ITEM = "assets/minecraft/items/stick.json";

    private static String select(String... cases) {
        return "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:custom_model_data\",\"cases\":[" + String.join(",", cases) + "]}}";
    }

    private static String sel(String when, String model) {
        return "{\"when\":\"" + when + "\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + model + "\"}}";
    }

    @Test
    void itemSelectCasesAreUnited() {
        PackMerger.Result r = PackMerger.merge(Map.of(ITEM, b(select(sel("a", "m:a")))), List.of(c("mod", ITEM, select(sel("b", "m:b")))));
        assertTrue(r.problems().isEmpty(), r.problems().toString());
        assertTrue(sameJson(select(sel("a", "m:a"), sel("b", "m:b")), r.files().get(ITEM)));
    }

    @Test
    void itemSameCaseWithOtherModelTakesTheModulesModel() {
        PackMerger.Result r = PackMerger.merge(Map.of(ITEM, b(select(sel("a", "m:a")))), List.of(c("mod", ITEM, select(sel("a", "m:other")))));
        assertEquals(1, r.problems().size());
        assertTrue(sameJson(select(sel("a", "m:other")), r.files().get(ITEM)));
    }

    @Test
    void itemFallbackTakenFromWhoeverHasOne() {
        String withFallback = "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:custom_model_data\",\"cases\":[],\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"m:f\"}}}";
        PackMerger.Result r = PackMerger.merge(Map.of(ITEM, b(select(sel("a", "m:a")))), List.of(c("mod", ITEM, withFallback)));
        assertTrue(r.problems().isEmpty());
        assertTrue(s(r.files().get(ITEM)).contains("m:f"));
    }

    @Test
    void itemRangeDispatchEntriesAreUnitedByThreshold() {
        String a = "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:damage\",\"entries\":[{\"threshold\":0.5,\"model\":{\"type\":\"minecraft:model\",\"model\":\"m:a\"}}]}}";
        String bb = "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:damage\",\"entries\":[{\"threshold\":0.9,\"model\":{\"type\":\"minecraft:model\",\"model\":\"m:b\"}}]}}";
        PackMerger.Result r = PackMerger.merge(Map.of(ITEM, b(a)), List.of(c("mod", ITEM, bb)));
        assertTrue(r.problems().isEmpty(), r.problems().toString());
        assertTrue(s(r.files().get(ITEM)).contains("m:a") && s(r.files().get(ITEM)).contains("m:b"));
    }

    @Test
    void itemWithDifferentPropertyOrTypeConflicts() {
        String other = "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\",\"cases\":[]}}";
        assertEquals(1, PackMerger.merge(Map.of(ITEM, b(select())), List.of(c("mod", ITEM, other))).problems().size());
        String plain = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"m:x\"}}";
        assertEquals(1, PackMerger.merge(Map.of(ITEM, b(select())), List.of(c("mod", ITEM, plain))).problems().size());
        String plain2 = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"m:y\"}}";
        assertEquals(1, PackMerger.merge(Map.of(ITEM, b(plain)), List.of(c("mod", ITEM, plain2))).problems().size());
    }

    // ---- Allgemeine Regeln ----

    @Test
    void identicalFileFromTwoSourcesIsFine() {
        PackMerger.Result r = PackMerger.merge(Map.of("assets/x/textures/a.png", new byte[] {1, 2}), List.of(new PackMerger.Contribution("mod", Map.of("assets/x/textures/a.png", new byte[] {1, 2}))));
        assertTrue(r.problems().isEmpty());
    }

    @Test
    void nonMergeableCollisionWithBaseLetsTheModuleWin() {
        PackMerger.Result r = PackMerger.merge(Map.of("assets/x/textures/a.png", new byte[] {1}),
                List.of(new PackMerger.Contribution("mod", Map.of("assets/x/textures/a.png", new byte[] {9}, "assets/x/textures/own.png", new byte[] {5}))));
        assertArrayEquals(new byte[] {9}, r.files().get("assets/x/textures/a.png"));
        assertArrayEquals(new byte[] {5}, r.files().get("assets/x/textures/own.png"));
        assertEquals(1, r.problems().size());
        assertTrue(r.problems().get(0).detail().contains("ersetzt 1 Datei"));
        assertFalse(r.problems().get(0).whole());
        assertEquals(Map.of("mod", 2), r.filesByOwner());
    }

    @Test
    void collisionBetweenModulesExcludesTheLaterModuleCompletely() {
        PackMerger.Result r = PackMerger.merge(Map.of(), List.of(
                new PackMerger.Contribution("beta", Map.of("assets/x/textures/a.png", new byte[] {2}, "assets/beta/own.png", new byte[] {7})),
                new PackMerger.Contribution("alpha", Map.of("assets/x/textures/a.png", new byte[] {1}))));
        // sortiert nach ID: alpha zuerst, beta scheitert und fehlt ganz
        assertArrayEquals(new byte[] {1}, r.files().get("assets/x/textures/a.png"));
        assertNull(r.files().get("assets/beta/own.png"));
        assertEquals(1, r.problems().size());
        assertTrue(r.problems().get(0).whole());
        assertEquals("beta", r.problems().get(0).owner());
        assertEquals(Map.of("alpha", 1), r.filesByOwner());
    }

    @Test
    void resultDoesNotDependOnTheOrderOfTheContributions() {
        PackMerger.Contribution x = c("a", "assets/minecraft/lang/de_de.json", "{\"k1\":\"1\"}");
        PackMerger.Contribution y = c("b", "assets/minecraft/lang/de_de.json", "{\"k2\":\"2\"}");
        PackMerger.Result one = PackMerger.merge(Map.of(), List.of(x, y));
        PackMerger.Result two = PackMerger.merge(Map.of(), List.of(y, x));
        assertEquals(PackZip.build(one.files(), new PackMeta("t", 88, 0), null).sha1(), PackZip.build(two.files(), new PackMeta("t", 88, 0), null).sha1());
    }

    @Test
    void brokenJsonIsAConflictNotACrash() {
        PackMerger.Result r = PackMerger.merge(Map.of("assets/x/lang/de_de.json", b("{kaputt")), List.of(c("mod", "assets/x/lang/de_de.json", "{\"a\":\"1\"}")));
        assertEquals(1, r.problems().size());
        r = PackMerger.merge(Map.of("assets/x/lang/de_de.json", b("[1]")), List.of(c("mod", "assets/x/lang/de_de.json", "{\"a\":\"1\"}")));
        assertEquals(1, r.problems().size());
    }

    // ---- Basis-Pack lesen ----

    private static Path zip(Path dir, String name, Map<String, byte[]> entries) throws IOException {
        Path p = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(p); ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return p;
    }

    @Test
    void basePackKeepsOnlyValidAssetsAndTakesTheIcon(@TempDir Path dir) throws IOException {
        Map<String, byte[]> in = new LinkedHashMap<>();
        in.put("pack.mcmeta", b("{}"));
        in.put("pack.png", new byte[] {9});
        in.put("assets/x/textures/ok.png", new byte[] {1});
        in.put("assets/X/Gross.png", new byte[] {2});
        in.put("../evil.txt", new byte[] {3});
        in.put("__MACOSX/x", new byte[] {4});
        in.put("data/x/loot.json", new byte[] {5});
        in.put("assets/x/dir/", new byte[0]);
        PackBase.Loaded l = PackBase.load(zip(dir, "base.zip", in));
        assertEquals(List.of("assets/x/textures/ok.png"), List.copyOf(l.files().keySet()));
        assertArrayEquals(new byte[] {9}, l.icon());
        assertEquals(4, l.skipped().size(), l.skipped().toString());
    }

    @Test
    void notAZipIsAnError(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("base.zip");
        Files.writeString(p, "kein zip");
        assertThrows(IOException.class, () -> PackBase.load(p));
    }

    // ---- Zusammenspiel im Dienst ----

    private static PeakModule module(String id, Map<String, String> files) {
        return new PeakModule() {
            public String id() { return id; }
            public String displayName() { return id; }
            public void enable(ModuleContext c) {}
            public void disable() {}
            public void contributePack(de.peakverse.core.api.pack.PackSink sink) { files.forEach(sink::text); }
        };
    }

    @Test
    void serviceBuildsFromBaseAndModulesAndReportsEverything(@TempDir Path dir) throws IOException {
        Path base = zip(dir, "base.zip", Map.of("assets/minecraft/lang/de_de.json", b("{\"a\":\"1\"}"), "pack.png", new byte[] {7}, "assets/x/Bad.png", new byte[] {1}));
        Map<String, String> files = Map.of("assets/minecraft/lang/de_de.json", "{\"b\":\"2\"}", "assets/mod/textures/t.json", "{}");
        PackService service = new PackService(() -> List.of(module("mod", files)), dir.resolve("out"), () -> new PackMeta("t", 88, 0), () -> base, Logger.getAnonymousLogger());
        PackService.Status st = service.rebuild();
        assertEquals(1, st.baseFiles());
        assertEquals(1, st.mergedFiles());
        assertEquals(Map.of("mod", 2), st.filesByModule());
        assertTrue(st.problems().stream().anyMatch(p -> p.contains("Bad.png")), st.problems().toString());
        assertEquals(4, st.fileCount()); // lang, t.json, pack.mcmeta, pack.png
        assertNotNull(service.built());
        assertEquals(st.sha1(), service.rebuild().sha1(), "gleiche Quellen, gleicher Hash");
    }

    @Test
    void missingBaseFileMeansNoBase(@TempDir Path dir) throws IOException {
        PackService service = new PackService(List::of, dir, () -> new PackMeta("t", 88, 0), () -> dir.resolve("gibt-es-nicht.zip"), Logger.getAnonymousLogger());
        assertEquals(0, service.rebuild().baseFiles());
    }
}
