package de.peakverse.core.plugin;

import de.peakverse.core.api.ClientInfo;
import de.peakverse.core.api.PeakCoreApi;
import de.peakverse.core.api.event.PlayerHandshakeEvent;
import de.peakverse.core.protocol.CorePacket;
import de.peakverse.core.protocol.CorePacketCodec;
import de.peakverse.core.protocol.Direction;
import de.peakverse.core.protocol.Frame;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Verbindet den {@link HandshakeService} mit Bukkit: Spieler, Kanal, Rechte, Events. Alle Aufrufe laufen auf dem Haupt-Thread. */
final class BukkitHandshakeEnv implements HandshakeService.Env<Player> {
    /** Recht fuer Administratoren; standardmaessig Operatoren (siehe plugin.yml). */
    static final String ADMIN_PERMISSION = "peakcore.admin";

    private final Plugin plugin;
    private final ModuleRegistry registry;
    private final String coreVersion;

    BukkitHandshakeEnv(Plugin plugin, ModuleRegistry registry, String coreVersion) {
        this.plugin = plugin;
        this.registry = registry;
        this.coreVersion = coreVersion;
    }

    @Override
    public UUID id(Player player) {
        return player.getUniqueId();
    }

    @Override
    public String name(Player player) {
        return player.getName();
    }

    @Override
    public boolean isAdmin(Player player) {
        return player.isOnline() && player.hasPermission(ADMIN_PERMISSION);
    }

    @Override
    public boolean channelRegistered(Player player) {
        return player.getListeningPluginChannels().contains(Frame.CHANNEL);
    }

    @Override
    public void send(Player player, CorePacket.Clientbound packet) {
        byte[] wire = de.peakverse.core.protocol.FrameCodec.encode(new Frame.CoreFrame(CorePacketCodec.encode(packet)), Direction.CLIENTBOUND);
        player.sendPluginMessage(plugin, Frame.CHANNEL, wire);
    }

    @Override
    public void notifyIncompatible(Player player, int clientProtocol) {
        player.sendMessage(CoreMessages.versionMismatch(player, de.peakverse.core.protocol.CoreProtocol.VERSION, clientProtocol));
    }

    @Override
    public void handshakeDone(Player player, ClientInfo client) {
        Bukkit.getPluginManager().callEvent(new PlayerHandshakeEvent(player, client));
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    @Override
    public String coreVersion() {
        return coreVersion;
    }

    @Override
    public int apiVersion() {
        return PeakCoreApi.API_VERSION;
    }

    @Override
    public List<CorePacket.ModuleEntry> serverModules() {
        return registry.entries().stream().map(e -> new CorePacket.ModuleEntry(e.info().id(), e.info().displayName(), e.info().version())).toList();
    }
}
