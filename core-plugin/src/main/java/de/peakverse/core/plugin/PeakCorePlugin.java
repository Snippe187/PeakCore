package de.peakverse.core.plugin;

import de.peakverse.core.api.ModuleInfo;
import de.peakverse.core.api.PeakCoreApi;
import de.peakverse.core.api.module.PeakModule;
import de.peakverse.core.plugin.pack.PackMeta;
import de.peakverse.core.plugin.perm.PermStore;
import de.peakverse.core.plugin.perm.PermissionService;
import de.peakverse.core.plugin.perm.PlayersWeb;
import de.peakverse.core.plugin.update.UpdateService;
import de.peakverse.core.plugin.update.UpdateWeb;
import de.peakverse.core.plugin.pack.PackService;
import de.peakverse.core.plugin.pack.PackWebServer;
import de.peakverse.core.plugin.web.AdminLog;
import de.peakverse.core.plugin.web.LiveHub;
import de.peakverse.core.plugin.web.WebAuth;
import de.peakverse.core.plugin.web.WebHost;
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
    private WebAuth webAuth;
    private LiveHub webHub;
    private WebHost webHost;
    private PermStore permStore;
    private PermissionService permService;
    private UpdateService updates;

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

        permStore = new PermStore(getDataFolder().toPath(), System::currentTimeMillis, getLogger());
        permStore.load();
        permService = new PermissionService(this, permStore);
        webAuth = new WebAuth(System::currentTimeMillis, (long) (getConfig().getDouble("web.session-hours", 8) * 3_600_000L));
        webHub = new LiveHub();
        AdminLog adminLog = new AdminLog(getDataFolder().toPath().resolve("web-log.jsonl"), System::currentTimeMillis);
        webHost = new WebHost(webAuth, adminLog, webHub,
                (java.util.function.Function<java.util.UUID, PermStore.Grant>) (uuid -> permStore.webGrant(uuid, Bukkit.getOfflinePlayer(uuid).isOp())), new WebHost.MainThread() {
                    @Override
                    public <T> T call(java.util.concurrent.Callable<T> task) throws Exception {
                        try {
                            return Bukkit.getScheduler().callSyncMethod(PeakCorePlugin.this, task).get(30, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (java.util.concurrent.ExecutionException e) {
                            // Die urspruengliche Ausnahme weitergeben, damit z. B. eine Eingabe-Pruefung als 400 ankommt und nicht als 500
                            if (e.getCause() instanceof Exception cause) throw cause;
                            throw e;
                        }
                    }
                }, webBase().startsWith("https://"), version, getLogger());
        boolean webOn = getConfig().getBoolean("web.enabled", false);
        webHost.setBindIp(getConfig().getBoolean("web.bind-session-ip", true));
        if (webOn) {
            try (java.io.InputStream in = getResource("web/players-panel.js")) {
                new PlayersWeb(webHost.registry("players"), permStore, permService, adminLog, webHost::moduleIds).register(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException | RuntimeException e) {
                getLogger().log(Level.SEVERE, "Die Spieler-Seite der Web-Oberflaeche konnte nicht angelegt werden", e);
            }
        }
        modules = new ModuleLoader(findModules(), this::skipReason,
                m -> new ModuleHost(this, m, version, webOn ? webHost.registry(m.id()) : de.peakverse.core.api.web.WebRegistry.NONE), getLogger());
        packs = new PackService(
                () -> modules.results().stream().filter(r -> r.state() == ModuleLoader.State.ENABLED).map(ModuleLoader.Result::module).toList(),
                getDataFolder().toPath().resolve("pack"),
                () -> new PackMeta(getConfig().getString("pack.description", "PeakVerse"), getConfig().getInt("pack.format-major", 88),
                        getConfig().getInt("pack.format-minor", 0)),
                () -> getConfig().getString("pack.base-file", "").isBlank() ? null : getDataFolder().toPath().resolve(getConfig().getString("pack.base-file")),
                getLogger());
        packs.setCustomFolder(() -> getDataFolder().toPath().resolve("pack-files"));
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
        if (webOn) {
            try (java.io.InputStream in = getResource("web/pack-panel.js"); java.io.InputStream in2 = getResource("web/areas-panel.js")) {
                new PackWeb(webHost.registry("pack"), packs, sender, web, getDataFolder().toPath().resolve("pack"), getDataFolder().toPath().resolve("base-pack.zip"), getDataFolder().toPath().resolve("pack-files"), file -> {
                    getConfig().set("pack.base-file", file);
                    saveConfig();
                }).register(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), webHost.registry("areas"), new String(in2.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException | RuntimeException e) {
                getLogger().log(Level.SEVERE, "Die Pack-Seite der Web-Oberflaeche konnte nicht angelegt werden", e);
            }
        }
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
        CoreCommand command = new CoreCommand(version, registry, router, handshake, modules::results, packs, sender, web,
                player -> {
                    if (!getConfig().getBoolean("web.enabled", false)) return null;
                    return webBase() + "/login?t=" + webAuth.issueToken(player.getUniqueId(), String.valueOf(player.getName()));
                }, () -> webAuth.sessionCount() + " Sitzungen, " + webHub.count() + " offene Seiten");
        if (webOn) Bukkit.getScheduler().runTaskTimerAsynchronously(this, webHub::ping, 2400L, 2400L);
        getCommand("peakweb").setExecutor((s, c, l, a) -> {
            if (!(s instanceof Player p)) {
                s.sendMessage("Nur im Spiel. In der Konsole: peakcore web <operator>");
                return true;
            }
            if (permStore.webGrant(p.getUniqueId(), p.isOp()) == null) {
                p.sendMessage(net.kyori.adventure.text.Component.text("Du hast keinen Zugang zur Web-Oberflaeche. Frag einen Admin nach einer Rolle mit Web-Zugang.", net.kyori.adventure.text.format.NamedTextColor.RED));
                return true;
            }
            String link = getConfig().getBoolean("web.enabled", false) ? webBase() + "/login?t=" + webAuth.issueToken(p.getUniqueId(), p.getName()) : null;
            if (link == null) {
                p.sendMessage(net.kyori.adventure.text.Component.text("Die Web-Oberflaeche ist aus.", net.kyori.adventure.text.format.NamedTextColor.RED));
                return true;
            }
            p.sendMessage(net.kyori.adventure.text.Component.text("[Web-Oberflaeche oeffnen]", net.kyori.adventure.text.format.NamedTextColor.GREEN)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(link))
                    .append(net.kyori.adventure.text.Component.text("  (nur fuer dich, 5 Minuten, einmal nutzbar)", net.kyori.adventure.text.format.NamedTextColor.GRAY)));
            return true;
        });
        permService.start();
        updates = new UpdateService(this, version, getFile().getName());
        updates.start();
        command.setUpdates(updates);
        if (webOn) new UpdateWeb(webHost.registry("update"), updates).register();
        getCommand("peakcore").setExecutor(command);
        getCommand("peakcore").setTabCompleter(command);
        getLogger().info("PeakCore " + version + " bereit (API " + PeakCoreApi.API_VERSION + ", Kanal " + Frame.CHANNEL + ").");
        modules.enableAll(); // erst jetzt: die Module melden sich ueber die API an, die oben bereitgestellt wurde
        startPack();
    }

    /** Webserver starten und das Pack einmal bauen, wenn der Server es ausliefern oder senden soll. Fehler hier legen den Core nicht lahm. */
    private void startPack() {
        boolean adminWeb = getConfig().getBoolean("web.enabled", false);
        boolean webOn = getConfig().getBoolean("pack.web.enabled", false) || adminWeb;
        if (adminWeb) web.setFallback(webHost);
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

    /** Adresse der Web-Oberflaeche ohne abschliessenden Schraegstrich. */
    private String webBase() {
        String url = getConfig().getString("web.public-url", "").trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url.isEmpty() ? "http://localhost:" + getConfig().getInt("pack.web.port", 8123) : url;
    }

    @Override
    public void onDisable() {
        if (updates != null) updates.stop();
        if (permService != null) permService.stop();
        if (webHub != null) webHub.closeAll();
        if (webAuth != null) webAuth.clear();
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

