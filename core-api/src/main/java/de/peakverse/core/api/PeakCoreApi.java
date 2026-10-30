package de.peakverse.core.api;

import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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

    /** Hat die Mod dieses Spielers den Handshake abgeschlossen (und ist mit diesem PeakCore kompatibel)? Nur dann duerfen Module ihm Daten schicken. */
    boolean isModded(Player player);

    /** Was die Mod des Spielers beim Handshake gemeldet hat; leer, wenn sie sich nicht (kompatibel) gemeldet hat. */
    Optional<ClientInfo> client(Player player);

    /**
     * Ist der Spieler Administrator? (Recht {@code peakcore.admin}, standardmaessig fuer Operatoren.) Wird bei jedem Aufruf neu geprueft;
     * Module muessen Verwaltungsaktionen immer hier pruefen und sich nie auf Angaben der Mod verlassen.
     */
    boolean isAdmin(Player player);

    /** Die laufende PeakCore-API, falls PeakCore geladen ist. */
    static Optional<PeakCoreApi> get() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(PeakCoreApi.class));
    }
}
