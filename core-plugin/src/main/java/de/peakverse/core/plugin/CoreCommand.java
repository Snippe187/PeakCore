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

    private final PackCommand packCommand;

    CoreCommand(String coreVersion, ModuleRegistry registry, FrameRouter<?> router, HandshakeService<Player> handshake,
            Supplier<List<ModuleLoader.Result>> builtIn, PackService packs, PackSender sender, PackWebServer web) {
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
            case "pack" -> packCommand.run(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
            default -> {
                sender.sendMessage(Component.text("Benutzung: /" + label + " <version|modules|status|players|pack>", NamedTextColor.RED));
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
        return List.of();
    }
}
