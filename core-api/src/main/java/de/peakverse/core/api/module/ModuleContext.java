package de.peakverse.core.api.module;

import java.io.File;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Was ein {@link PeakModule} vom Core bekommt. Jedes Modul hat einen eigenen Datenordner, eine eigene Config und einen eigenen Logger,
 * als waere es ein eigenes Plugin. Das Bukkit-Plugin selbst ({@link #host()}) ist PeakCore; es wird fuer Scheduler, Events und Befehle gebraucht.
 */
public interface ModuleContext {
    /** Das Bukkit-Plugin PeakCore, fuer Scheduler, Events und Plugin-Kanaele. */
    JavaPlugin host();

    String moduleId();

    /** Version des Jars (gleich fuer Core und alle Module). */
    String version();

    /** Datenordner des Moduls. Standard ist {@code plugins/<Anzeigename>}, damit bestehende Daten (z. B. {@code plugins/PeakSound}) weiter genutzt werden. */
    File dataFolder();

    /** Logger mit dem Anzeigenamen des Moduls als Praefix. */
    Logger logger();

    /** Config des Moduls ({@code config.yml} im Datenordner). Fehlt die Datei, legt {@link #saveDefaultConfig()} sie an. */
    FileConfiguration config();

    /** Kopiert die mitgelieferte Standard-Config ({@code modules/<id>/config.yml} im Jar) in den Datenordner, falls dort noch keine liegt, und laedt sie. */
    void saveDefaultConfig();

    /** Laedt die Config neu von der Platte. */
    void reloadConfig();

    /** Zugang zur Web-Oberflaeche des Cores; ohne eingeschaltete Oberflaeche ein Register, das nichts tut. */
    default de.peakverse.core.api.web.WebRegistry web() {
        return de.peakverse.core.api.web.WebRegistry.NONE;
    }
}
