package de.peakverse.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.peakverse.core.api.ModuleInfo;
import de.peakverse.core.api.ModuleRegistrationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModuleRegistryTest {
    private static ModuleInfo info(String id, int requiredApi) {
        return new ModuleInfo(id, id.toUpperCase(), "1.0.0", requiredApi);
    }

    @Test
    void registersAndFindsModules() {
        ModuleRegistry registry = new ModuleRegistry(1);
        registry.register("PeakSound", info("peaksound", 1));
        assertEquals("PeakSound", registry.find("peaksound").orElseThrow().owner());
        assertTrue(registry.find("unknown").isEmpty());
    }

    @Test
    void listsModulesSortedById() {
        ModuleRegistry registry = new ModuleRegistry(1);
        registry.register("T", info("texturepeak", 1));
        registry.register("S", info("peaksound", 1));
        registry.register("X", info("alpha", 1));
        assertEquals(List.of("alpha", "peaksound", "texturepeak"), registry.entries().stream().map(e -> e.info().id()).toList());
    }

    @Test
    void rejectsDuplicateIdsFromAnotherPlugin() {
        ModuleRegistry registry = new ModuleRegistry(1);
        registry.register("PeakSound", info("peaksound", 1));
        ModuleRegistrationException e = assertThrows(ModuleRegistrationException.class, () -> registry.register("Fake", info("peaksound", 1)));
        assertTrue(e.getMessage().contains("PeakSound"), e.getMessage());
        assertEquals("PeakSound", registry.find("peaksound").orElseThrow().owner(), "das Original bleibt erhalten");
    }

    @Test
    void rejectsModulesThatNeedANewerApi() {
        ModuleRegistry registry = new ModuleRegistry(1);
        assertThrows(ModuleRegistrationException.class, () -> registry.register("P", info("peaksound", 2)));
        assertTrue(registry.entries().isEmpty());
        ModuleRegistry newer = new ModuleRegistry(3);
        newer.register("P", info("peaksound", 3));
        newer.register("Q", info("old", 1));
        assertEquals(2, newer.entries().size());
    }

    @Test
    void onlyTheOwnerMayUnregister() {
        ModuleRegistry registry = new ModuleRegistry(1);
        registry.register("PeakSound", info("peaksound", 1));
        assertFalse(registry.unregister("peaksound", "Fake"));
        assertTrue(registry.find("peaksound").isPresent());
        assertTrue(registry.unregister("peaksound", "PeakSound"));
        assertFalse(registry.unregister("peaksound", "PeakSound"), "zweites Abmelden ist harmlos");
        registry.register("Other", info("peaksound", 1)); // die ID ist wieder frei
    }

    @Test
    void disablingAPluginRemovesAllItsModules() {
        ModuleRegistry registry = new ModuleRegistry(1);
        registry.register("Multi", info("one", 1));
        registry.register("Multi", info("two", 1));
        registry.register("Keep", info("three", 1));
        List<ModuleInfo> removed = registry.unregisterOwner("Multi");
        assertEquals(List.of("one", "two"), removed.stream().map(ModuleInfo::id).sorted().toList());
        assertEquals(List.of("three"), registry.entries().stream().map(e -> e.info().id()).toList());
        assertTrue(registry.unregisterOwner("Multi").isEmpty());
    }
}
