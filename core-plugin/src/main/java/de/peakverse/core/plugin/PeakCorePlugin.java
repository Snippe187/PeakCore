package de.peakverse.core.plugin;

import de.peakverse.core.api.ModuleInfo;
import de.peakverse.core.api.PeakCoreApi;
import java.util.List;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Einstiegspunkt von PeakCore: stellt die API als Bukkit-Dienst bereit und meldet Module ab, wenn deren Plugin abgeschaltet wird. */
public final class PeakCorePlugin extends JavaPlugin implements Listener {
    private ModuleRegistry registry;

    @Override
    public void onEnable() {
        String version = getPluginMeta().getVersion();
        registry = new ModuleRegistry(PeakCoreApi.API_VERSION);
        CoreApiImpl api = new CoreApiImpl(version, registry, getLogger());
        getServer().getServicesManager().register(PeakCoreApi.class, api, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);

        CoreCommand command = new CoreCommand(version, registry);
        getCommand("peakcore").setExecutor(command);
        getCommand("peakcore").setTabCompleter(command);
        getLogger().info("PeakCore " + version + " bereit (API " + PeakCoreApi.API_VERSION + ").");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }

    /** Ein abgeschaltetes Plugin verliert seine Modul-Anmeldungen, damit sich das Modul nach einem Neuladen wieder anmelden kann. */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == this || registry == null) return;
        List<ModuleInfo> removed = registry.unregisterOwner(event.getPlugin().getName());
        for (ModuleInfo info : removed) getLogger().info("Modul abgemeldet (Plugin abgeschaltet): " + info.displayName() + " (" + info.id() + ")");
    }
}
