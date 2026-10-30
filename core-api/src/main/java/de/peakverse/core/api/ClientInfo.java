package de.peakverse.core.api;

import java.util.List;
import java.util.Optional;

/**
 * Was die Mod eines Spielers beim Handshake gemeldet hat.
 *
 * @param coreProtocol Version des Core-Protokolls der Mod
 * @param coreVersion  Version der Core-Mod
 * @param modules      Module, die die Mod mitbringt (z. B. peaksound), mit ihrer Version
 * @param helloAtMillis Zeitpunkt des Handshakes (Serverzeit in Millisekunden)
 */
public record ClientInfo(int coreProtocol, String coreVersion, List<ClientModule> modules, long helloAtMillis) {
    public ClientInfo {
        modules = List.copyOf(modules);
    }

    /** Ein Modul auf Seiten der Mod. */
    public record ClientModule(String id, String version) {}

    /** Bringt die Mod dieses Modul mit? */
    public boolean hasModule(String moduleId) {
        return module(moduleId).isPresent();
    }

    public Optional<ClientModule> module(String moduleId) {
        return modules.stream().filter(m -> m.id().equals(moduleId)).findFirst();
    }
}
