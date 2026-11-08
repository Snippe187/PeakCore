package de.peakverse.core.plugin;

import de.peakverse.core.api.PeakCoreApi;
import de.peakverse.core.plugin.pack.PackService;
import de.peakverse.core.plugin.pack.PackWebServer;
import de.peakverse.core.protocol.Frame;
import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /peakcore}: Version, Module, Kanal-Status und Spieler mit Mod. Nur fuer Administratoren (Recht peakcore.admin). */
final class CoreCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUB = List.of("version", "modules", "status", "players", "pack", "web", "update");

    private final String coreVersion;
    private final ModuleRegistry registry;
    private final FrameRouter<?> router;
    private final HandshakeService<Player> handshake;
    private final Supplier<List<ModuleLoader.Result>> builtIn;

    private final PackCommand packCommand;
    private de.peakverse.core.plugin.update.UpdateService updates;

    void setUpdates(de.peakverse.core.plugin.update.UpdateService updates) {
        this.updates = updates;
    }

    private final java.util.function.Function<org.bukkit.OfflinePlayer, String> webLink;
    private final Supplier<String> webStatus;

    CoreCommand(String coreVersion, ModuleRegistry registry, FrameRouter<?> router, HandshakeService<Player> handshake,
            Supplier<List<ModuleLoader.Result>> builtIn, PackService packs, PackSender sender, PackWebServer web,
            java.util.function.Function<org.bukkit.OfflinePlayer, String> webLink, Supplier<String> webStatus) {
        this.webLink = webLink;
        this.webStatus = webStatus;
        this.packCommand = new PackCommand(packs, sender, web);
        this.coreVersion = coreVersion;
        this.registry = registry;
        this.router = router;
        this.handshake = handshake;
        this.builtIn = builtIn;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "version" : args[0].toLowerCase();
        switch (sub) {
            case "version" -> sender.sendMessage(Component.text("PeakCore " + coreVersion + " (API " + PeakCoreApi.API_VERSION + ")", NamedTextColor.GOLD));
            case "modules" -> {
                for (ModuleLoader.Result r : builtIn.get()) {
                    String state = switch (r.state()) {
                        case ENABLED -> "laeuft";
                        case SKIPPED -> "aus: " + r.detail();
                        case FAILED -> "FEHLER: " + r.detail();
                    };
                    sender.sendMessage(Component.text("Eingebaut: " + r.module().displayName() + " [" + r.module().id() + "] " + state,
                            r.state() == ModuleLoader.State.ENABLED ? NamedTextColor.GREEN : NamedTextColor.RED));
                }
                List<ModuleRegistry.Entry> entries = registry.entries();
                if (entries.isEmpty()) {
                    sender.sendMessage(Component.text("Keine Module angemeldet.", NamedTextColor.GRAY));
                    break;
                }
                sender.sendMessage(Component.text("Angemeldete Module (" + entries.size() + "):", NamedTextColor.GOLD));
                for (ModuleRegistry.Entry e : entries) {
                    sender.sendMessage(Component.text(" - " + e.info().displayName() + " " + e.info().version() + " [" + e.info().id()
                            + ", Plugin " + e.owner() + ", API ab " + e.info().requiredApi() + "]", NamedTextColor.YELLOW));
                }
            }
            case "status" -> {
                FrameRouter.Stats s = router.stats();
                sender.sendMessage(Component.text("Kanal " + Frame.CHANNEL + ": " + s.received() + " Pakete empfangen, " + s.delivered() + " zugestellt, "
                        + s.malformed() + " ungueltig, " + s.rejected() + " ohne Handshake abgewiesen, " + s.unknownModule() + " an unbekannte Module oder ohne Handler, "
                        + s.handlerErrors() + " Handler-Fehler", NamedTextColor.GOLD));
            }
            case "players" -> {
                List<? extends Player> online = List.copyOf(Bukkit.getOnlinePlayers());
                long modded = online.stream().filter(handshake::isModded).count();
                sender.sendMessage(Component.text("Spieler online: " + online.size() + ", davon mit kompatibler Mod: " + modded, NamedTextColor.GOLD));
                for (Player p : online) {
                    boolean ok = handshake.isModded(p);
                    sender.sendMessage(Component.text(" - " + p.getName() + ": " + handshake.describe(p), ok ? NamedTextColor.GREEN : NamedTextColor.GRAY));
                }
            }
            case "update" -> {
                String act = args.length > 1 ? args[1].toLowerCase() : "status";
                try {
                    switch (act) {
                        case "check" -> {
                            updates.check();
                            if (updates.isNewer()) {
                                sender.sendMessage(Component.text("Neue Version " + updates.latestVersion() + " verfuegbar.", NamedTextColor.GOLD));
                                sender.sendMessage(updates.downloadOffer());
                            } else {
                                sender.sendMessage(Component.text("Du bist auf dem neuesten Stand (oder die Quelle ist nicht erreichbar, siehe status).", NamedTextColor.GOLD));
                            }
                        }
                        case "download" -> {
                            updates.download();
                            sender.sendMessage(Component.text("Update geladen und geprueft. Es wird beim naechsten Start installiert.", NamedTextColor.GREEN));
                            sender.sendMessage(updates.restartOffer());
                        }
                        case "beta" -> {
                            if (args.length > 2 && args[2].equalsIgnoreCase("download")) {
                                updates.downloadBeta();
                                sender.sendMessage(Component.text("Beta " + updates.betaVersion() + " geladen und geprueft. Sie wird beim naechsten Start installiert.", NamedTextColor.GREEN));
                                sender.sendMessage(updates.restartOffer());
                            } else {
                                updates.checkBeta();
                                if (updates.isBetaNewer()) {
                                    sender.sendMessage(Component.text("Beta " + updates.betaVersion() + " verfuegbar. Betas sind zum Testen: es kann Fehler geben, am besten zuerst nicht auf dem Haupt-Server.", NamedTextColor.GOLD));
                                    sender.sendMessage(updates.betaOffer());
                                } else {
                                    sender.sendMessage(Component.text("Es gibt keine neuere Beta oder Version (neueste im Repo: " + updates.betaVersion() + ").", NamedTextColor.GOLD));
                                }
                            }
                        }
                        case "restart" -> updates.scheduleRestart(args.length > 2 ? Integer.parseInt(args[2]) : 5);
                        case "cancel" -> sender.sendMessage(Component.text(updates.cancelRestart() ? "Neustart abgebrochen." : "Es war kein Neustart geplant.", NamedTextColor.GOLD));
                        default -> sender.sendMessage(Component.text(updates.statusJson(), NamedTextColor.GRAY));
                    }
                } catch (java.io.IOException | IllegalArgumentException e) {
                    sender.sendMessage(Component.text("Update: " + e.getMessage(), NamedTextColor.RED));
                }
            }
            case "pack" -> packCommand.run(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
            case "web" -> {
                org.bukkit.OfflinePlayer p;
                if (sender instanceof Player self) {
                    p = self;
                } else {
                    // Die Konsole darf einen Link fuer einen Operator erzeugen (z. B. auf Servern ohne Spieler im Spiel)
                    org.bukkit.OfflinePlayer other = args.length > 1 ? Bukkit.getOfflinePlayerIfCached(args[1]) : null;
                    if (other == null || !other.isOp()) {
                        sender.sendMessage(Component.text("Benutzung in der Konsole: /peakcore web <operator>  (" + webStatus.get() + ")", NamedTextColor.GRAY));
                        break;
                    }
                    p = other;
                }
                String link = webLink.apply(p);
                if (link == null) {
                    sender.sendMessage(Component.text("Die Web-Oberflaeche ist aus. In plugins/PeakCore/config.yml web.enabled auf true setzen und neu starten.", NamedTextColor.RED));
                    break;
                }
                if (!(sender instanceof Player)) sender.sendMessage(Component.text(link, NamedTextColor.AQUA));
                sender.sendMessage(Component.text("[Web-Oberflaeche oeffnen]", NamedTextColor.GREEN)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(link))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Einmal-Link, 5 Minuten gueltig")))
                        .append(Component.text("  (nur fuer dich, nicht weitergeben; 5 Minuten, einmal nutzbar)", NamedTextColor.GRAY)));
                sender.sendMessage(Component.text(webStatus.get(), NamedTextColor.DARK_GRAY));
            }
            default -> {
                sender.sendMessage(Component.text("Benutzung: /" + label + " <version|modules|status|players|pack|web|update>", NamedTextColor.RED));
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String prefix = args[args.length - 1].toLowerCase();
        if (args.length == 1) return SUB.stream().filter(s -> s.startsWith(prefix)).toList();
        if (args[0].equalsIgnoreCase("pack")) return packCommand.complete(java.util.Arrays.copyOfRange(args, 1, args.length));
        if (args[0].equalsIgnoreCase("update")) {
            if (args.length == 2) return List.of("status", "check", "download", "beta", "restart", "cancel").stream().filter(s -> s.startsWith(prefix)).toList();
            if (args.length == 3 && args[1].equalsIgnoreCase("beta")) return List.of("download").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
