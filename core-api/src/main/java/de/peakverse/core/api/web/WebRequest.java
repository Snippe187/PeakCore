package de.peakverse.core.api.web;

import java.util.Map;

/**
 * Eine Anfrage der Web-Oberflaeche an ein Modul. Kommt nur von angemeldeten Administratoren.
 *
 * @param method {@code GET}, {@code POST}, {@code PUT} oder {@code DELETE}
 * @param path   Pfad hinter {@code /api/<modul>/}, ohne fuehrenden Schraegstrich (leer fuer die Wurzel)
 * @param query  Query-Parameter (decodiert)
 * @param body   Rohdaten des Bodys (leer bei GET); JSON oder Datei-Inhalt
 * @param admin  Spielername des Administrators, der die Anfrage stellt
 */
public record WebRequest(String method, String path, Map<String, String> query, byte[] body, String admin) {
    public String text() {
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }
}
