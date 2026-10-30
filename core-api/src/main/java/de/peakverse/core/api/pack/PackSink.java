package de.peakverse.core.api.pack;

/**
 * Hierhin legt ein Modul seine Dateien fuer das Server-Resource-Pack. Der Core sammelt die Beitraege aller Module und baut daraus das Pack
 * (Pack-Bau ab Phase 9; bis dahin ruft der Core {@code contributePack} noch nicht auf, die Schnittstelle steht aber fest).
 *
 * <p>Pfade sind Pack-Pfade wie {@code assets/texturepeak/textures/item/ruby.png}; die Regeln stehen in {@link PackPaths}.
 */
public interface PackSink {
    /**
     * Fuegt eine Datei hinzu.
     *
     * @throws IllegalArgumentException wenn der Pfad ungueltig ist (siehe {@link PackPaths#validate}) oder schon von einem anderen Beitrag belegt wurde
     */
    void file(String path, byte[] data);

    /** Wie {@link #file}, aber fuer Text (UTF-8, z. B. JSON). */
    default void text(String path, String content) {
        file(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
