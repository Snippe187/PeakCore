package de.peakverse.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.peakverse.core.api.module.ModuleContext;
import de.peakverse.core.api.module.PeakModule;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class ModuleLoaderTest {
    private final List<String> events = new ArrayList<>();
    private final Logger log = quietLogger();

    private static Logger quietLogger() {
        Logger l = Logger.getLogger("ModuleLoaderTest");
        l.setLevel(Level.OFF);
        return l;
    }

    private final class Fake implements PeakModule {
        private final String id;
        private final boolean failOnEnable;
        private final boolean failOnDisable;

        Fake(String id, boolean failOnEnable, boolean failOnDisable) {
            this.id = id;
            this.failOnEnable = failOnEnable;
            this.failOnDisable = failOnDisable;
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return id.toUpperCase(); }

        @Override
        public void enable(ModuleContext context) {
            events.add("enable " + id);
            if (failOnEnable) throw new IllegalStateException("kaputt");
        }

        @Override
        public void disable() {
            events.add("disable " + id);
            if (failOnDisable) throw new IllegalStateException("kaputt beim Beenden");
        }
    }

    private ModuleLoader loader(List<PeakModule> modules, java.util.function.Function<PeakModule, String> skip) {
        return new ModuleLoader(modules, skip, m -> null, log);
    }

    @Test
    void startsModulesInOrderAndStopsThemInReverse() {
        ModuleLoader loader = loader(List.of(new Fake("a", false, false), new Fake("b", false, false)), m -> null);
        List<ModuleLoader.Result> results = loader.enableAll();
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> r.state() == ModuleLoader.State.ENABLED));
        loader.disableAll();
        assertEquals(List.of("enable a", "enable b", "disable b", "disable a"), events);
        assertTrue(loader.results().isEmpty());
    }

    @Test
    void skippedModuleIsNeverStartedNorStopped() {
        ModuleLoader loader = loader(List.of(new Fake("a", false, false), new Fake("b", false, false)), m -> m.id().equals("a") ? "ausgeschaltet" : null);
        List<ModuleLoader.Result> results = loader.enableAll();
        assertEquals(ModuleLoader.State.SKIPPED, results.get(0).state());
        assertEquals("ausgeschaltet", results.get(0).detail());
        assertEquals(ModuleLoader.State.ENABLED, results.get(1).state());
        loader.disableAll();
        assertEquals(List.of("enable b", "disable b"), events);
    }

    @Test
    void aFailingModuleDoesNotStopTheOthersAndIsCleanedUp() {
        ModuleLoader loader = loader(List.of(new Fake("a", true, false), new Fake("b", false, false)), m -> null);
        List<ModuleLoader.Result> results = loader.enableAll();
        assertEquals(ModuleLoader.State.FAILED, results.get(0).state());
        assertEquals("kaputt", results.get(0).detail());
        assertEquals(ModuleLoader.State.ENABLED, results.get(1).state());
        assertEquals(List.of("enable a", "disable a", "enable b"), events, "das halb gestartete Modul wird sofort abgebaut");
        events.clear();
        loader.disableAll();
        assertEquals(List.of("disable b"), events, "ein gescheitertes Modul wird nicht noch einmal beendet");
    }

    @Test
    void aFailingDisableDoesNotPreventTheOthersFromStopping() {
        ModuleLoader loader = loader(List.of(new Fake("a", false, false), new Fake("b", false, true)), m -> null);
        loader.enableAll();
        events.clear();
        loader.disableAll();
        assertEquals(List.of("disable b", "disable a"), events);
    }

    @Test
    void duplicateIdIsRejected() {
        ModuleLoader loader = loader(List.of(new Fake("a", false, false), new Fake("a", false, false)), m -> null);
        List<ModuleLoader.Result> results = loader.enableAll();
        assertEquals(ModuleLoader.State.ENABLED, results.get(0).state());
        assertEquals(ModuleLoader.State.FAILED, results.get(1).state());
        assertEquals(List.of("enable a"), events);
        assertNull(results.get(0).detail());
    }
}
