package de.peakverse.core.plugin;

import de.peakverse.core.api.module.ModuleContext;
import de.peakverse.core.api.module.PeakModule;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Der {@link ModuleContext} eines Moduls: eigener Datenordner unter {@code plugins/}, eigene Config, eigener Logger. */
final class ModuleHost implements ModuleContext {
    private final JavaPlugin host;
    private final String id;
    private final String displayName;
    private final String version;
    private final File dataFolder;
    private final Logger logger;
    private FileConfiguration config;

    ModuleHost(JavaPlugin host, PeakModule module, String version) {
        this.host = host;
        this.id = module.id();
        this.displayName = module.displayName();
        this.version = version;
        // Der Ordner heisst wie das Modul frueher als eigenes Plugin hiess (plugins/PeakSound), damit vorhandene Daten weiterlaufen
        this.dataFolder = new File(host.getDataFolder().getParentFile(), displayName);
        this.logger = Logger.getLogger(displayName);
    }

    @Override
    public JavaPlugin host() {
        return host;
    }

    @Override
    public String moduleId() {
        return id;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public File dataFolder() {
        return dataFolder;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    private String resourceName() {
        return "modules/" + id + "/config.yml";
    }

    @Override
    public synchronized FileConfiguration config() {
        if (config == null) reloadConfig();
        return config;
    }

    @Override
    public synchronized void saveDefaultConfig() {
        File file = new File(dataFolder, "config.yml");
        if (!file.exists()) {
            try (InputStream in = host.getResource(resourceName())) {
                if (in != null) {
                    Files.createDirectories(dataFolder.toPath());
                    Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Standard-config.yml konnte nicht angelegt werden", e);
            }
        }
        reloadConfig();
    }

    @Override
    public synchronized void reloadConfig() {
        File file = new File(dataFolder, "config.yml");
        config = YamlConfiguration.loadConfiguration(file);
        // Fehlende Schluessel (neue Einstellungen nach einem Update) fallen auf die Werte der mitgelieferten Config zurueck
        try (InputStream in = host.getResource(resourceName())) {
            if (in != null) config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            logger.log(Level.WARNING, "Standardwerte der config.yml konnten nicht gelesen werden", e);
        }
    }
}
