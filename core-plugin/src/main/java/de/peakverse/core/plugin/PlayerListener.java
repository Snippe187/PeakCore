package de.peakverse.core.plugin;

import de.peakverse.core.protocol.Frame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.Plugin;

/** Ereignisse der Spieler fuer den Handshake: Kanal angemeldet, Beitritt (Hinweis fuer Spieler ohne Mod), Austritt. */
final class PlayerListener implements Listener {
    private final Plugin plugin;
    private final HandshakeService<Player> handshake;

    PlayerListener(Plugin plugin, HandshakeService<Player> handshake) {
        this.plugin = plugin;
        this.handshake = handshake;
    }

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (event.getChannel().equals(Frame.CHANNEL)) handshake.onChannelRegistered(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("non-mod-hint.enabled", true)) return;
        Player player = event.getPlayer();
        long delay = Math.max(1, plugin.getConfig().getLong("non-mod-hint.delay-ticks", 100));
        // Die Mod meldet sich kurz nach dem Beitritt; wer bis dahin keinen Handshake geschafft hat, bekommt den Hinweis (nie ein Kick)
        player.getScheduler().runDelayed(plugin, task -> {
            if (handshake.needsNonModHint(player)) player.sendMessage(CoreMessages.nonModHint(player));
        }, null, delay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        handshake.onQuit(event.getPlayer());
    }
}
