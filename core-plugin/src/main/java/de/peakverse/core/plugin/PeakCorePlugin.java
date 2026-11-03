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
import de.peakverse.core.plugin.web.DeviceStore;
import de.peakverse.core.plugin.web.PairingService;
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
    private TunnelService<Player> tunnel;
    private WebHost webHost;
    private DeviceStore deviceStore;
    private PairingService pairing;
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
        getServer().getPluginManager().registerEvents(new PlayerListener(this, handshake, p -> {
            if (tunnel != null) tunnel.onQuit(p);
        }), this);
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
        if (webOn && getConfig().getBoolean("web.tunnel", true)) {
            tunnel = new TunnelService<>(new TunnelService.Env<Player>() {
                @Override
                public java.util.UUID id(Player player) {
                    return player.getUniqueId();
                }

                @Override
                public boolean allowed(Player player) {
                    return permStore.webGrant(player.getUniqueId(), player.isOp()) != null;
                }

                @Override
                public void send(Player player, de.peakverse.core.protocol.CorePacket.Clientbound packet) {
                    // Tunnel-Threads sind nicht der Haupt-Thread: das Senden laeuft dort
                    if (Bukkit.isPrimaryThread()) env.send(player, packet);
                    else if (isEnabled()) Bukkit.getScheduler().runTask(PeakCorePlugin.this, () -> {
                        if (player.isOnline()) env.send(player, packet);
                    });
                }

                @Override
                public String loginPath(Player player) {
                    return "/login?t=" + webAuth.issueToken(player.getUniqueId(), player.getName());
                }

                @Override
                public java.net.Socket connect() throws java.io.IOException {
                    String bind = getConfig().getString("pack.web.bind", "0.0.0.0");
                    boolean any = bind == null || bind.isBlank() || bind.equals("0.0.0.0") || bind.equals("::");
                    java.net.InetAddress host = any ? java.net.InetAddress.getLoopbackAddress() : java.net.InetAddress.getByName(bind);
                    java.net.Socket s = new java.net.Socket();
                    try {
                        s.connect(new java.net.InetSocketAddress(host, web != null && web.port() > 0 ? web.port() : getConfig().getInt("pack.web.port", 8123)), 3000);
                    } catch (java.io.IOException e) {
                        s.close();
                        throw e;
                    }
                    s.setTcpNoDelay(true);
                    return s;
                }
            }, getLogger());
            handshake.setTunnelHandler(tunnel::onPacket);
        }
        if (webOn && getConfig().getBoolean("web.remote-login", true)) {
            deviceStore = new DeviceStore(getDataFolder().toPath().resolve("devices.json"), System::currentTimeMillis, getLogger());
            deviceStore.load();
            pairing = new PairingService(System::currentTimeMillis);
            webHost.enablePairing(deviceStore, pairing, new WebHost.PairHooks() {
                @Override
                public Candidate lookup(String name) {
                    Player p = Bukkit.getPlayerExact(name);
                    return p == null || permStore.webGrant(p.getUniqueId(), p.isOp()) == null ? null : new Candidate(p.getUniqueId(), p.getName());
                }

                @Override
                public void ask(Candidate player, PairingService.Request request) {
                    Player p = Bukkit.getPlayer(player.id());
                    if (p == null) return;
                    var red = net.kyori.adventure.text.format.NamedTextColor.RED;
                    var gray = net.kyori.adventure.text.format.NamedTextColor.GRAY;
                    p.sendMessage(net.kyori.adventure.text.Component.text("[PeakVerse] Ein neues Geraet will sich mit der Web-Oberflaeche verbinden.", net.kyori.adventure.text.format.NamedTextColor.GOLD));
                    p.sendMessage(net.kyori.adventure.text.Component.text("Geraet: " + request.label() + ", Adresse " + request.ip() + ". Code: ", gray)
                            .append(net.kyori.adventure.text.Component.text(request.code(), net.kyori.adventure.text.format.NamedTextColor.YELLOW, net.kyori.adventure.text.format.TextDecoration.BOLD)));
                    p.sendMessage(net.kyori.adventure.text.Component.text("[Erlauben]", net.kyori.adventure.text.format.NamedTextColor.GREEN)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/peakweb allow " + request.code()))
                            .append(net.kyori.adventure.text.Component.text("  "))
                            .append(net.kyori.adventure.text.Component.text("[Ablehnen]", red).clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/peakweb deny"))));
                    p.sendMessage(net.kyori.adventure.text.Component.text("Nur erlauben, wenn genau dieser Code auf deinem Handy steht. Wer es nicht war, klickt Ablehnen.", gray));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.4f);
                }
            }, getConfig().getInt("web.device-expire-days", 0));
        }
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
                }, packLibrary(), () -> getConfig().getString("pack.base-file", ""), () -> getConfig().getInt("pack.format-major", 88), getDataFolder().toPath()).register(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), webHost.registry("areas"), new String(in2.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
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
            if (a.length > 0 && (a[0].equalsIgnoreCase("devices") || a[0].equalsIgnoreCase("revoke"))) return deviceCommand(s, a);
            if (!(s instanceof Player p)) {
                s.sendMessage("Nur im Spiel. In der Konsole: peakcore web <operator>  |  peakweb devices  |  peakweb revoke <id>");
                return true;
            }
            if (a.length > 0 && (a[0].equalsIgnoreCase("allow") || a[0].equalsIgnoreCase("deny"))) {
                var green = net.kyori.adventure.text.format.NamedTextColor.GREEN;
                if (pairing == null) {
                    p.sendMessage(net.kyori.adventure.text.Component.text("Das Koppeln von Geraeten ist aus.", net.kyori.adventure.text.format.NamedTextColor.RED));
                } else if (a[0].equalsIgnoreCase("deny")) {
                    p.sendMessage(net.kyori.adventure.text.Component.text(pairing.deny(p.getUniqueId()) ? "Abgelehnt." : "Es wartet keine Anfrage.", green));
                } else {
                    boolean ok = a.length > 1 && pairing.approve(p.getUniqueId(), a[1]);
                    p.sendMessage(net.kyori.adventure.text.Component.text(ok ? "Erlaubt. Das Geraet ist gleich angemeldet." : "Keine passende Anfrage (Code falsch oder abgelaufen).",
                            ok ? green : net.kyori.adventure.text.format.NamedTextColor.RED));
                    if (ok) getLogger().info("[Web] " + p.getName() + " hat ein Geraet gekoppelt.");
                }
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

    private de.peakverse.core.plugin.pack.PackLibrary library;

    private de.peakverse.core.plugin.pack.PackLibrary packLibrary() {
        if (library == null) {
            library = new de.peakverse.core.plugin.pack.PackLibrary(getDataFolder().toPath().resolve("pack-library"), System::currentTimeMillis, getLogger());
            library.load();
        }
        return library;
    }

    /** {@code /peakweb devices} zeigt die gekoppelten Geraete, {@code /peakweb revoke <id>} sperrt eines. Spieler sehen nur ihre eigenen, Operatoren und die Konsole alle. */
    private boolean deviceCommand(org.bukkit.command.CommandSender s, String[] a) {
        var red = net.kyori.adventure.text.format.NamedTextColor.RED;
        if (deviceStore == null) {
            s.sendMessage(net.kyori.adventure.text.Component.text("Das Koppeln von Geraeten ist aus (web.remote-login in der config.yml).", red));
            return true;
        }
        boolean all = !(s instanceof Player) || s.isOp();
        java.util.List<DeviceStore.Device> mine = s instanceof Player p && !all ? deviceStore.listFor(p.getUniqueId()) : deviceStore.list();
        if (a[0].equalsIgnoreCase("devices")) {
            if (mine.isEmpty()) s.sendMessage("Keine gekoppelten Geraete.");
            for (DeviceStore.Device d : mine) {
                s.sendMessage(d.id + "  " + d.name + "  " + d.label + "  zuletzt " + java.time.Instant.ofEpochMilli(d.lastUsed) + "  " + d.lastIp);
            }
            return true;
        }
        if (a.length < 2) {
            s.sendMessage("Benutzung: /peakweb revoke <id>");
            return true;
        }
        boolean allowed = mine.stream().anyMatch(d -> d.id.equals(a[1]));
        s.sendMessage(allowed && deviceStore.revoke(a[1]) ? "Geraet gesperrt." : "Kein solches Geraet (oder es gehoert dir nicht).");
        return true;
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
        if (tunnel != null) tunnel.close();
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

