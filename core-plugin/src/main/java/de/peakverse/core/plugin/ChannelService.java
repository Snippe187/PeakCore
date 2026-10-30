package de.peakverse.core.plugin;

import de.peakverse.core.protocol.Direction;
import de.peakverse.core.protocol.Frame;
import de.peakverse.core.protocol.FrameCodec;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Der Plugin-Messaging-Kanal {@value Frame#CHANNEL}: Empfang ueber den {@link FrameRouter}, Senden mit Groessenpruefung. */
final class ChannelService implements PluginMessageListener {
    private final Plugin plugin;
    private final FrameRouter<Player> router;

    ChannelService(Plugin plugin, FrameRouter<Player> router) {
        this.plugin = plugin;
        this.router = router;
    }

    FrameRouter<Player> router() {
        return router;
    }

    void register() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, Frame.CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, Frame.CHANNEL);
    }

    void unregister() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, Frame.CHANNEL);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, Frame.CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Frame.CHANNEL.equals(channel)) return;
        router.receive(player, message);
    }

    /** Hat die Mod des Spielers den Kanal angemeldet? Paper verwirft Sendungen an Clients ohne Anmeldung still. */
    boolean canSend(Player player) {
        return player.isOnline() && player.getListeningPluginChannels().contains(Frame.CHANNEL);
    }

    /** Sendet Nutzdaten an das Modul-Gegenstueck in der Mod. Nur auf dem Haupt-Thread. */
    boolean sendToModule(String moduleId, Player player, byte[] payload) {
        byte[] wire = FrameCodec.encode(new Frame.ModuleFrame(moduleId, payload), Direction.CLIENTBOUND);
        if (!canSend(player)) return false;
        player.sendPluginMessage(plugin, Frame.CHANNEL, wire);
        return true;
    }
}
