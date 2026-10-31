package de.peakverse.core.plugin;

import de.peakverse.core.api.ModuleInfo;
import de.peakverse.core.api.PeakCoreApi;
import de.peakverse.core.api.module.PeakModule;
import de.peakverse.core.plugin.pack.PackMeta;
import de.peakverse.core.plugin.pack.PackService;
import de.peakverse.core.plugin.pack.PackWebServer;
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
    private PackWebServer web;
    private PackSender sender;

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
                () -> getConfig().getString("pack.base-file", "").isBlank() ? null : getDataFolder().toPath().resolve(getConfig().getString("pack.base-file")),
                getLogger());
        packs.setDefaultVariant(() -> getConfig().getString("pack.default-variant", "standard"));
        packs.setOptimizePng(() -> getConfig().getBoolean("pack.optimize-png", true));
        web = new PackWebServer(getDataFolder().toPath().resolve("pack"), getConfig().getString("pack.web.bind", "0.0.0.0"), getConfig().getInt("pack.web.port", 8123), getLogger());
        sender = new PackSender(this, packs, new PackSender.ModLink() {
            @Override
            public boolean isModded(Player player) {
                return handshake.isModded(player);
            }

            @Override
            public void send(Player player, de.peakverse.core.protocol.CorePacket.Clientbound packet) {
                env.send(player, packet);
            }
        }, getLogger());
        getServer().getPluginManager().registerEvents(sender, this);
        api.setPackRefresh(() -> {
            try {
                packs.rebuild();
                if (sender.enabled() && sender.currentUrl() != null) sender.sendAll(Bukkit.getOnlinePlayers());
                return true;
            } catch (java.io.IOException | RuntimeException e) {
                getLogger().log(Level.SEVERE, "Pack konnte nicht gebaut werden", e);
                return false;
            }
        });
        CoreCommand command =new CoreCommand(version, registry, router, handshake, modules::results, packs, sender, web);
        getCommand("peakcore").setExecutor(command);
        getCommand("peakcore").setTabCompleter(command);
        getLogger().info("PeakCore " + version + " bereit (API " + PeakCoreApi.API_VERSION + ", Kanal " + Frame.CHANNEL + ").");
        modules.enableAll(); // erst jetzt: die Module melden sich ueber die API an, die oben bereitgestellt wurde
        startPack();
    }

    /** Webserver starten und das Pack einmal bauen, wenn der Server es ausliefern oder senden soll. Fehler hier legen den Core nicht lahm. */
    private void startPack() {
        boolean webOn = getConfig().getBoolean("pack.web.enabled", false);
        if (webOn) {
            try {
                web.start();
                getLogger().info("Pack-Webserver laeuft auf Port " + web.port() + " (public-url: " + getConfig().getString("pack.public-url", "") + ").");
            } catch (java.io.IOException e) {
                getLogger().severe("Pack-Webserver konnte nicht starten (Port " + web.port() + " belegt oder Adresse falsch?): " + e.getMessage());
            }
        }
        if (webOn || sender.enabled()) {
            try {
                PackService.Status s = packs.rebuild();
                getLogger().info("Pack gebaut: " + s.sha1() + " (" + s.fileCount() + " Dateien)" + (sender.enabled() && sender.currentUrl() == null ? " - ACHTUNG: pack.public-url oder pack.external-url fehlt, es wird nichts gesendet" : ""));
            } catch (java.io.IOException | RuntimeException e) {
                getLogger().log(java.util.logging.Level.SEVERE, "Pack konnte nicht gebaut werden", e);
            }
        }
    }

    @Override
    public void onDisable() {
        if (web != null) web.stop();
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
