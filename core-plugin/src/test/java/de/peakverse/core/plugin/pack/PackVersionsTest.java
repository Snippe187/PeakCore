package de.peakverse.core.plugin.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.peakverse.core.api.module.ModuleContext;
import de.peakverse.core.api.module.PeakModule;
import de.peakverse.core.api.pack.PackSink;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackVersionsTest {
    private static final PackMeta META = new PackMeta("t", 88, 0);
    private static final UUID TESTER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** Ein Modul, dessen Beitrag sich zwischen den Bauten aendern laesst. */
    private static final class Mod implements PeakModule {
        String content = "v1";
        boolean withOptional;
        byte[] png;

        public String id() { return "mod"; }
        public String displayName() { return "mod"; }
        public void enable(ModuleContext c) {}
        public void disable() {}

        public void contributePack(PackSink sink) {
            sink.text("assets/mod/data.json", content);
            if (withOptional) sink.optionalFile("assets/mod/extra.bin", new byte[2000]);
            if (png != null) sink.file("assets/mod/textures/t.png", png);
        }
    }

    private Mod mod;
    private Path dir;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        mod = new Mod();
        dir = tmp;
    }

    private PackService service() {
        return new PackService(() -> List.of(mod), dir, () -> META, Logger.getAnonymousLogger());
    }

    private static List<String> names(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(zip)))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) names.add(e.getName());
        }
        return names;
    }

    private Path zipOf(String sha) {
        return dir.resolve("pack-" + sha + ".zip");
    }

    // ---- Zeiger und Verlauf ----

    @Test
    void firstBuildMakesBothVariantsLiveAndRecordsVersions() throws IOException {
        PackService s = service();
        PackService.Status st = s.rebuild();
        assertTrue(st.pointersChanged());
        assertEquals(st.sha1(), s.liveSha("standard"));
        assertEquals(st.sha1(), s.liveSha("lite"));
        assertEquals(1, s.versions("standard").size());
        assertEquals(1, s.versions("lite").size());
        assertNotEquals(s.versions("standard").get(0).n, s.versions("lite").get(0).n, "Nummern sind ueber alle Varianten eindeutig");
    }

    @Test
    void unchangedRebuildMovesNothing() throws IOException {
        PackService s = service();
        s.rebuild();
        PackService.Status again = s.rebuild();
        assertFalse(again.pointersChanged());
        assertEquals(1, s.versions("standard").size(), "kein neuer Verlaufseintrag bei gleichem Inhalt");
    }

    @Test
    void changedContentPublishesAndKeepsHistoryNewestFirst() throws IOException {
        PackService s = service();
        String v1 = s.rebuild().sha1();
        mod.content = "v2";
        PackService.Status st = s.rebuild();
        assertTrue(st.pointersChanged());
        assertNotEquals(v1, st.sha1());
        assertEquals(st.sha1(), s.liveSha("standard"));
        assertEquals(List.of(st.sha1(), v1), s.versions("standard").stream().map(e -> e.sha1).toList());
    }

    @Test
    void rollbackSurvivesRebuildWithSameContentAndARestart() throws IOException {
        PackService s = service();
        String v1 = s.rebuild().sha1();
        mod.content = "v2";
        s.rebuild();
        int first = s.versions("standard").get(1).n;
        s.rollback("standard", first);
        assertEquals(v1, s.liveSha("standard"));

        s.rebuild(); // gleicher Inhalt wie Version 2: Zeiger bleibt
        assertEquals(v1, s.liveSha("standard"));

        PackService restarted = service(); // Neustart: Zustand kommt von der Platte
        restarted.rebuild();
        assertEquals(v1, restarted.liveSha("standard"));
        assertEquals(2, restarted.versions("standard").size());
    }

    @Test
    void newContentAfterRollbackReplacesTheRollback() throws IOException {
        PackService s = service();
        s.rebuild();
        mod.content = "v2";
        s.rebuild();
        s.rollback("standard", s.versions("standard").get(1).n);
        mod.content = "v3";
        PackService.Status st = s.rebuild();
        assertEquals(st.sha1(), s.liveSha("standard"));
    }

    @Test
    void rollbackRejectsUnknownNumbersWrongVariantsAndMissingFiles() throws IOException {
        PackService s = service();
        s.rebuild();
        mod.content = "v2";
        s.rebuild();
        int old = s.versions("standard").get(1).n;
        assertThrows(IllegalArgumentException.class, () -> s.rollback("standard", 999));
        assertThrows(IllegalArgumentException.class, () -> s.rollback("lite", old)); // Nummer gehoert zu "standard"
        Files.delete(zipOf(s.versions("standard").get(1).sha1));
        assertThrows(IllegalArgumentException.class, () -> s.rollback("standard", old));
        assertEquals(s.versions("standard").get(0).sha1, s.liveSha("standard"), "nach einem Fehler bleibt das Live-Pack");
    }

    // ---- Testmodus ----

    @Test
    void testModeSendsNewPackOnlyToTestersUntilPublished() throws IOException {
        PackService s = service();
        String v1 = s.rebuild().sha1();
        s.setTestMode(true);
        s.addTester(TESTER);
        mod.content = "v2";
        PackService.Status st = s.rebuild();

        assertEquals(v1, s.liveSha("standard"));
        assertEquals(st.sha1(), s.testSha("standard"));
        assertEquals(st.sha1(), s.shaFor(TESTER));
        assertEquals(v1, s.shaFor(OTHER));

        assertEquals(2, s.publish()); // standard und lite
        assertEquals(st.sha1(), s.liveSha("standard"));
        assertNull(s.testSha("standard"));
        assertEquals(st.sha1(), s.shaFor(OTHER));
    }

    @Test
    void turningTestModeOffDiscardsTheTestPack() throws IOException {
        PackService s = service();
        String v1 = s.rebuild().sha1();
        s.setTestMode(true);
        s.addTester(TESTER);
        mod.content = "v2";
        s.rebuild();
        s.setTestMode(false);
        assertNull(s.testSha("standard"));
        assertEquals(v1, s.shaFor(TESTER));
        assertEquals(0, s.publish());
    }

    @Test
    void removedTesterGetsTheLivePack() throws IOException {
        PackService s = service();
        String v1 = s.rebuild().sha1();
        s.setTestMode(true);
        s.addTester(TESTER);
        mod.content = "v2";
        s.rebuild();
        assertTrue(s.removeTester(TESTER));
        assertEquals(v1, s.shaFor(TESTER));
        assertFalse(s.removeTester(TESTER));
    }

    @Test
    void firstBuildInTestModeStillGivesEveryoneALivePack() throws IOException {
        PackService s = service();
        s.setTestMode(true);
        PackService.Status st = s.rebuild();
        assertEquals(st.sha1(), s.shaFor(OTHER));
    }

    @Test
    void prunedFilesOfPointersAreKept() throws IOException {
        PackService s = service();
        String v1 = s.rebuild().sha1();
        s.setTestMode(true);
        for (int i = 2; i < 12; i++) {
            mod.content = "v" + i;
            s.rebuild();
        }
        assertTrue(Files.exists(zipOf(v1)), "das Live-Pack darf nie aufgeraeumt werden");
        assertTrue(Files.exists(zipOf(s.testSha("standard"))));
    }

    // ---- Varianten ----

    @Test
    void litePackLeavesOutOptionalFiles() throws IOException {
        mod.withOptional = true;
        PackService s = service();
        PackService.Status st = s.rebuild();
        String standard = st.variants().get("standard").sha1();
        String lite = st.variants().get("lite").sha1();
        assertNotEquals(standard, lite);
        assertTrue(names(zipOf(standard)).contains("assets/mod/extra.bin"));
        assertFalse(names(zipOf(lite)).contains("assets/mod/extra.bin"));
        assertTrue(names(zipOf(lite)).contains("assets/mod/data.json"));
        assertTrue(st.variants().get("lite").size() < st.variants().get("standard").size());
    }

    @Test
    void withoutOptionalFilesBothVariantsAreTheSameFile() throws IOException {
        PackService.Status st = service().rebuild();
        assertEquals(st.variants().get("standard").sha1(), st.variants().get("lite").sha1());
    }

    @Test
    void playersGetTheirOwnVariantOrTheConfiguredDefault() throws IOException {
        mod.withOptional = true;
        PackService s = service();
        PackService.Status st = s.rebuild();
        assertEquals(st.variants().get("standard").sha1(), s.shaFor(OTHER));

        s.setPlayerVariant(OTHER, "lite");
        assertEquals(st.variants().get("lite").sha1(), s.shaFor(OTHER));
        assertEquals("lite", s.variantOf(OTHER));

        s.setPlayerVariant(OTHER, null);
        assertEquals("standard", s.variantOf(OTHER));

        s.setDefaultVariant(() -> "lite");
        assertEquals("lite", s.variantOf(OTHER));
        s.setDefaultVariant(() -> "unsinn");
        assertEquals("standard", s.variantOf(OTHER), "ungueltige Config faellt auf standard zurueck");
        assertThrows(IllegalArgumentException.class, () -> s.setPlayerVariant(OTHER, "mega"));
    }

    @Test
    void nothingBuiltMeansNoPackForPlayers() throws IOException {
        assertNull(service().shaFor(OTHER));
    }

    // ---- Zustand auf der Platte ----

    @Test
    void stateSurvivesRestartIncludingTestersAndPlayerVariants() throws IOException {
        PackService s = service();
        s.rebuild();
        s.setTestMode(true);
        s.addTester(TESTER);
        s.setPlayerVariant(OTHER, "lite");

        PackService again = service();
        assertTrue(again.testMode());
        assertEquals(1, again.testers().size());
        assertEquals("lite", again.variantOf(OTHER));
    }

    @Test
    void brokenStateFileStartsFreshAndIsKept() throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("state.json"), "{das ist kein json");
        PackService s = service();
        assertFalse(s.testMode());
        PackService.Status st = s.rebuild();
        assertEquals(st.sha1(), s.liveSha("standard"));
        assertTrue(Files.exists(dir.resolve("state.json.kaputt")));
    }

    // ---- PNG im Dienst ----

    @Test
    void pngFilesAreShrunkAndTheSavingIsReported() throws IOException {
        mod.png = PngOptimizerTestData.wastefulPng();
        PackService s = service();
        PackService.Status on = s.rebuild();
        assertTrue(on.pngSavedBytes() > 1000, "gespart: " + on.pngSavedBytes());

        PackService off = service();
        off.setOptimizePng(() -> false);
        PackService.Status plain = off.rebuild();
        assertEquals(0, plain.pngSavedBytes());
        assertTrue(on.variants().get("standard").size() < plain.variants().get("standard").size());
    }
}
