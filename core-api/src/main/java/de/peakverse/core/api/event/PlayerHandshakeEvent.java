package de.peakverse.core.api.event;

import de.peakverse.core.api.ClientInfo;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Wird auf dem Haupt-Thread ausgeloest, wenn die Mod eines Spielers den Handshake erfolgreich abgeschlossen hat
 * (auch erneut, wenn die Mod sich nach einem Neustart des Clients noch einmal meldet). Ab hier ist {@code PeakCoreApi.isModded} wahr.
 * Module nutzen das, um dem Spieler ihren Anfangszustand zu schicken.
 */
public final class PlayerHandshakeEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final ClientInfo client;

    public PlayerHandshakeEvent(Player player, ClientInfo client) {
        super(player);
        this.client = client;
    }

    /** Was die Mod gemeldet hat. */
    public ClientInfo client() {
        return client;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
