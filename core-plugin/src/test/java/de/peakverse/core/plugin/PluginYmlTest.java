package de.peakverse.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Die plugin.yml muss gueltiges YAML sein und die Pflichtfelder enthalten; sonst laedt Paper das Plugin gar nicht. */
class PluginYmlTest {
    @Test
    void pluginYmlIsValidAndComplete() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(in, "plugin.yml fehlt");
            Map<String, Object> yml = new Yaml().load(in);
            assertEquals("PeakCore", yml.get("name"));
            // die Hauptklasse darf nicht geladen werden (braucht Bukkit); es genuegt, dass ihre Klassendatei existiert
            String main = (String) yml.get("main");
            assertNotNull(getClass().getResource("/" + main.replace('.', '/') + ".class"), "Hauptklasse nicht gefunden: " + main);
            assertNotNull(yml.get("version"));
            assertNotNull(yml.get("api-version"));
            assertTrue(((Map<?, ?>) yml.get("commands")).containsKey("peakcore"));
            assertTrue(((Map<?, ?>) yml.get("permissions")).containsKey("peakcore.admin"));
        }
    }
}
