package de.peakverse.core.plugin;

import de.peakverse.core.api.ModuleInfo;
import de.peakverse.core.api.PeakCoreApi;
import de.peakverse.core.protocol.Frame;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Einstiegspunkt von PeakCore: stellt die API als Bukkit-Dienst bereit, betreibt den Kanal und den Handshake mit der Mod
 * und meldet Module ab, wenn deren Plugin abgeschaltet wird.
 */
public final class PeakCorePlugin extends JavaPlugin implements Listener {
    private ModuleRegistry registry;
    private ChannelService channel;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String version = getPluginMeta().getVersion();
        registry = new ModuleRegistry(PeakCoreApi.API_VERSION);

        BukkitHandshakeEnv env = new BukkitHandshakeEnv(this, registry, version);
        HandshakeService<Player> handshake = new HandshakeService<>(env, getLogger());
        FrameRouter<Player> router = new FrameRouter<>(getLogger());
        router.setCoreHandler(handshake::onCorePayload);
        router.setModuleAccess(handshake::isModded); // Pakete an Module nur von Spielern mit abgeschlossenem Handshake
        channel = new ChannelService(this, router);
        channel.register();

        CoreApiImpl api = new CoreApiImpl(version, registry, channel, handshake, env, getLogger());
        getServer().getServicesManager().register(PeakCoreApi.class, api, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this, handshake), this);
        // Der Admin-Status folgt /op und /deop: alle 2 Sekunden abgleichen, gemeldet wird nur bei Aenderungen
        Bukkit.getScheduler().runTaskTimer(this, () -> handshake.checkAdmins(Bukkit.getOnlinePlayers()), 40L, 40L);

        CoreCommand command = new CoreCommand(version, registry, router, handshake);
        getCommand("peakcore").setExecutor(command);
        getCommand("peakcore").setTabCompleter(command);
        getLogger().info("PeakCore " + version + " bereit (API " + PeakCoreApi.API_VERSION + ", Kanal " + Frame.CHANNEL + ").");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (channel != null) channel.unregister();
    }

    /** Ein abgeschaltetes Plugin verliert seine Modul-Anmeldungen, damit sich das Modul nach einem Neuladen wieder anmelden kann. */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == this || registry == null) return;
        List<ModuleInfo> removed = registry.unregisterOwner(event.getPlugin().getName());
        for (ModuleInfo info : removed) {
            channel.router().removeHandler(info.id());
            getLogger().info("Modul abgemeldet (Plugin abgeschaltet): " + info.displayName() + " (" + info.id() + ")");
        }
    }
}
