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
    private static final List<String> SUB = List.of("version", "modules", "status", "players", "pack");

    private final String coreVersion;
    private final ModuleRegistry registry;
    private final FrameRouter<?> router;
    private final HandshakeService<Player> handshake;
    private final Supplier<List<ModuleLoader.Result>> builtIn;

    private final PackService packs;
    private final PackSender sender;
    private final PackWebServer web;

    CoreCommand(String coreVersion, ModuleRegistry registry, FrameRouter<?> router, HandshakeService<Player> handshake,
            Supplier<List<ModuleLoader.Result>> builtIn, PackService packs, PackSender sender, PackWebServer web) {
        this.packs = packs;
        this.sender = sender;
        this.web = web;
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
            case "pack" -> pack(sender, args.length > 1 ? args[1].toLowerCase() : "status");
            default -> {
                sender.sendMessage(Component.text("Benutzung: /" + label + " <version|modules|status|players|pack>", NamedTextColor.RED));
                return false;
            }
        }
        return true;
    }

    private void pack(CommandSender sender, String action) {
        switch (action) {
            case "status" -> {
                PackService.Status s = packs.status();
                if (s == null) sender.sendMessage(Component.text("Das Pack wurde noch nicht gebaut: /peakcore pack rebuild", NamedTextColor.GRAY));
                else describe(sender, s);
                delivery(sender);
            }
            case "rebuild" -> {
                try {
                    PackService.Status before = packs.status();
                    PackService.Status s = packs.rebuild();
                    sender.sendMessage(Component.text(s.written() ? "Pack neu gebaut." : "Pack unveraendert (gleicher Inhalt, nichts neu geschrieben).", NamedTextColor.GREEN));
                    describe(sender, s);
                    if (this.sender.enabled() && (before == null || !before.sha1().equals(s.sha1()))) {
                        sender.sendMessage(Component.text("Neues Pack an " + this.sender.sendAll(Bukkit.getOnlinePlayers()) + " Spieler geschickt.", NamedTextColor.GREEN));
                    }
                } catch (Exception e) {
                    sender.sendMessage(Component.text("Pack konnte nicht gebaut werden: " + e, NamedTextColor.RED));
                }
            }
            case "resend" -> {
                if (packs.status() == null) {
                    sender.sendMessage(Component.text("Das Pack wurde noch nicht gebaut: /peakcore pack rebuild", NamedTextColor.RED));
                } else if (this.sender.currentUrl() == null) {
                    sender.sendMessage(Component.text("Keine Adresse: pack.public-url oder pack.external-url in der config.yml eintragen.", NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text("Pack an " + this.sender.sendAll(Bukkit.getOnlinePlayers()) + " Spieler geschickt.", NamedTextColor.GREEN));
                }
            }
            default -> sender.sendMessage(Component.text("Benutzung: /peakcore pack <status|rebuild|resend>", NamedTextColor.RED));
        }
    }

    private void delivery(CommandSender sender) {
        String url;
        try {
            url = this.sender.currentUrl();
        } catch (IllegalArgumentException e) {
            url = "FEHLER: " + e.getMessage();
        }
        sender.sendMessage(Component.text("Webserver: " + (web.running() ? "laeuft auf Port " + web.port() : "aus") + ", Senden: " + (this.sender.enabled() ? "an" : "aus")
                + ", Adresse: " + (url == null ? "nicht eingestellt" : url), NamedTextColor.GRAY));
    }

    private static void describe(CommandSender sender, PackService.Status s) {
        sender.sendMessage(Component.text("Pack " + s.sha1() + ": " + s.fileCount() + " Dateien (mit pack.mcmeta), " + s.size() + " Bytes, davon " + s.baseFiles() + " aus dem Basis-Pack, " + s.mergedFiles() + " zusammengefuehrt, Beitraege "
                + (s.filesByModule().isEmpty() ? "keine" : s.filesByModule().toString()), NamedTextColor.GOLD));
        for (String problem : s.problems()) sender.sendMessage(Component.text("  Ausgelassen: " + problem, NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String prefix = args[args.length - 1].toLowerCase();
        if (args.length == 1) return SUB.stream().filter(s -> s.startsWith(prefix)).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("pack")) return List.of("status", "rebuild", "resend").stream().filter(s -> s.startsWith(prefix)).toList();
        return List.of();
    }
}
