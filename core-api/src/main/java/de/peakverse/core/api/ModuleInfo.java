package de.peakverse.core.api;

import de.peakverse.core.protocol.ModuleIds;

/**
 * Beschreibung eines Moduls, das sich bei PeakCore anmeldet.
 *
 * @param id          kurze, eindeutige Kennung in Kleinbuchstaben, z. B. {@code peaksound}; sie dient auch als Namensraum fuer Kanaele und Pack-Dateien
 * @param displayName Anzeigename, z. B. {@code PeakSound}
 * @param version     Version des Moduls, z. B. {@code 0.1.0}
 * @param requiredApi kleinste Version der Core-API, die das Modul braucht (siehe {@link PeakCoreApi#API_VERSION})
 */
public record ModuleInfo(String id, String displayName, String version, int requiredApi) {
    public ModuleInfo {
        if (!ModuleIds.isValid(id)) {
            throw new IllegalArgumentException("Modul-ID muss aus 2-24 Zeichen bestehen (a-z, 0-9, _) und mit einem Buchstaben beginnen: " + id);
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 40) {
            throw new IllegalArgumentException("Anzeigename fehlt oder ist laenger als 40 Zeichen");
        }
        if (version == null || version.isBlank() || version.length() > 32) {
            throw new IllegalArgumentException("Version fehlt oder ist laenger als 32 Zeichen");
        }
        if (requiredApi < 1) {
            throw new IllegalArgumentException("requiredApi muss mindestens 1 sein");
        }
    }
}
