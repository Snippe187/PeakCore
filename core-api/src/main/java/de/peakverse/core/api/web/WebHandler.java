package de.peakverse.core.api.web;

/**
 * Behandelt Web-Anfragen eines Moduls. Laeuft auf einem Thread des Webservers, <b>nicht</b> auf dem Haupt-Thread:
 * Bukkit-Zugriffe gehen ueber {@link WebRegistry#onMain}. {@link IllegalArgumentException} wird als Fehler 400 mit der Meldung beantwortet,
 * alles andere als 500.
 */
@FunctionalInterface
public interface WebHandler {
    WebResponse handle(WebRequest request) throws Exception;
}
