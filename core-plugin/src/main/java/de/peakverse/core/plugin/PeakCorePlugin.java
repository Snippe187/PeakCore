package de.peakverse.core.plugin;

import de.peakverse.core.api.ModuleInfo;
import de.peakverse.core.api.PeakCoreApi;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Einstiegspunkt von PeakCore: stellt die API als Bukkit-Dienst bereit, betreibt den Kanal zur Mod und meldet Module ab, wenn deren Plugin abgeschaltet wird. */
public final class PeakCorePlugin extends JavaPlugin implements Listener {
    private ModuleRegistry registry;
    private ChannelService channel;

    @Override
    public void onEnable() {
        String version = getPluginMeta().getVersion();
        registry = new ModuleRegistry(PeakCoreApi.API_VERSION);
        channel = new ChannelService(this, new FrameRouter<Player>(getLogger()));
        channel.register();
        CoreApiImpl api = new CoreApiImpl(version, registry, channel, getLogger());
        getServer().getServicesManager().register(PeakCoreApi.class, api, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);

        CoreCommand command = new CoreCommand(version, registry, channel.router());
        getCommand("peakcore").setExecutor(command);
        getCommand("peakcore").setTabCompleter(command);
        getLogger().info("PeakCore " + version + " bereit (API " + PeakCoreApi.API_VERSION + ", Kanal " + de.peakverse.core.protocol.Frame.CHANNEL + ").");
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
