package de.peakverse.core.plugin;

import de.peakverse.core.api.ModuleInfo;
import de.peakverse.core.api.PeakCoreApi;
import de.peakverse.core.api.module.PeakModule;
import de.peakverse.core.plugin.pack.PackMeta;
import de.peakverse.core.plugin.pack.PackService;
import de.peakverse.core.protocol.Frame;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Einstiegspunkt von PeakCore: stellt die API als Bukkit-Dienst bereit, betreibt den Kanal und den Handshake mit der Mod
 * und meldet Module ab, wenn deren Plugin abgeschaltet wird.
 */
public final class PeakCorePlugin extends JavaPlugin implements Listener {
    private ModuleRegistry registry;
    private ChannelService channel;
    private ModuleLoader modules;
    private PackService packs;

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

        modules = new ModuleLoader(findModules(), this::skipReason, m -> new ModuleHost(this, m, version), getLogger());
        packs = new PackService(
                () -> modules.results().stream().filter(r -> r.state() == ModuleLoader.State.ENABLED).map(ModuleLoader.Result::module).toList(),
                getDataFolder().toPath().resolve("pack"),
                () -> new PackMeta(getConfig().getString("pack.description", "PeakVerse"), getConfig().getInt("pack.format-major", 88),
                        getConfig().getInt("pack.format-minor", 0)),
                getLogger());
        CoreCommand command = new CoreCommand(version, registry, router, handshake, modules::results, packs);
        getCommand("peakcore").setExecutor(command);
        getCommand("peakcore").setTabCompleter(command);
        getLogger().info("PeakCore " + version + " bereit (API " + PeakCoreApi.API_VERSION + ", Kanal " + Frame.CHANNEL + ").");
        modules.enableAll(); // erst jetzt: die Module melden sich ueber die API an, die oben bereitgestellt wurde
    }

    @Override
    public void onDisable() {
        if (modules != null) modules.disableAll();
        getServer().getServicesManager().unregisterAll(this);
        if (channel != null) channel.unregister();
    }

    /** Die eingebauten Module aus dem Jar (ServiceLoader). Ein kaputter Eintrag wird gemeldet und uebersprungen. */
    private List<PeakModule> findModules() {
        List<PeakModule> found = new ArrayList<>();
        Iterator<PeakModule> it = ServiceLoader.load(PeakModule.class, getClass().getClassLoader()).iterator();
        while (true) {
            try {
                if (!it.hasNext()) break;
                found.add(it.next());
            } catch (ServiceConfigurationError e) {
                getLogger().log(Level.SEVERE, "Ein eingebautes Modul konnte nicht geladen werden", e);
            }
        }
        return found;
    }

    /** Grund, ein Modul nicht zu starten, oder {@code null}. */
    private String skipReason(PeakModule module) {
        if (!getConfig().getBoolean("modules." + module.id() + ".enabled", true)) {
            return "in plugins/PeakCore/config.yml ausgeschaltet (modules." + module.id() + ".enabled)";
        }
        Plugin standalone = getServer().getPluginManager().getPlugin(module.displayName());
        if (standalone != null && standalone != this) {
            getLogger().severe("Das alte eigenstaendige Plugin " + module.displayName() + " ist noch installiert. Bitte " + module.displayName()
                    + ".jar aus plugins/ loeschen: PeakCore bringt " + module.displayName() + " jetzt selbst mit. Die Daten in plugins/"
                    + module.displayName() + " bleiben erhalten.");
            return "das alte Plugin " + module.displayName() + " ist noch installiert (Jar entfernen)";
        }
        return null;
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
