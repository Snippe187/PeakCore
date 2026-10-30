package de.peakverse.core.api;

import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Oeffentliche Schnittstelle von PeakCore fuer Module. Sie wird ueber den Bukkit-ServicesManager bereitgestellt:
 * <pre>{@code PeakCoreApi core = PeakCoreApi.get().orElseThrow();}</pre>
 * Module tragen {@code depend: [PeakCore]} in ihrer plugin.yml ein; ohne PeakCore laden sie nicht.
 */
public interface PeakCoreApi {
    /** Version dieser Schnittstelle. Sie steigt nur, wenn sich die API aendert; Module nennen die kleinste, die sie brauchen. */
    int API_VERSION = 1;

    /** Version des PeakCore-Plugins (z. B. {@code 0.1.0}). */
    String coreVersion();

    /** Version der Schnittstelle, die dieses PeakCore anbietet. */
    default int apiVersion() {
        return API_VERSION;
    }

    /**
     * Meldet ein Modul an. Wird abgelehnt, wenn die ID schon vergeben ist oder das Modul eine neuere API verlangt als dieses PeakCore anbietet.
     *
     * @throws ModuleRegistrationException bei Ablehnung
     */
    ModuleRegistration registerModule(Plugin owner, ModuleInfo info);

    /** Alle angemeldeten Module, nach ID sortiert. */
    List<ModuleInfo> modules();

    /** Ein angemeldetes Modul anhand seiner ID. */
    Optional<ModuleInfo> module(String id);

    /** Die laufende PeakCore-API, falls PeakCore geladen ist. */
    static Optional<PeakCoreApi> get() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(PeakCoreApi.class));
    }
}
