package de.peakverse.core.api;

import org.bukkit.entity.Player;

/** Nachweis einer erfolgreichen Modul-Anmeldung und Zugang des Moduls zum Kanal zur Mod. Wird auch automatisch beendet, wenn das Plugin des Moduls abgeschaltet wird. */
public interface ModuleRegistration {
    /** Das angemeldete Modul. */
    ModuleInfo info();

    /** Legt fest, wer die Nutzdaten empfaengt, die die Mod an dieses Modul schickt. {@code null} entfernt den Handler (Pakete werden dann verworfen). */
    void setMessageHandler(ModuleMessageHandler handler);

    /** Kann an diesen Spieler gesendet werden? (Seine Mod hat den Kanal angemeldet.) Nur auf dem Haupt-Thread aufrufen. */
    boolean canSend(Player player);

    /**
     * Schickt Nutzdaten an die Mod des Spielers. Gibt {@code false} zurueck, wenn der Spieler den Kanal nicht angemeldet hat (dann ist nichts gesendet worden).
     * Nur auf dem Haupt-Thread aufrufen.
     *
     * @throws IllegalArgumentException wenn die Nutzdaten laenger sind als {@link #maxPayloadToClient()}
     */
    boolean send(Player player, byte[] payload);

    /** Groesste Nutzdatenmenge je Paket vom Server zur Mod. */
    int maxPayloadToClient();

    /** Groesste Nutzdatenmenge je Paket von der Mod zum Server (laengere Pakete werden verworfen). */
    int maxPayloadFromClient();

    /** Meldet das Modul wieder ab. Mehrfaches Aufrufen schadet nicht. */
    void unregister();
}
