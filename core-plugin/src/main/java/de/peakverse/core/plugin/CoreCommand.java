package de.peakverse.core.plugin;

import de.peakverse.core.api.PeakCoreApi;
import de.peakverse.core.protocol.Frame;
import java.util.List;
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
    private static final List<String> SUB = List.of("version", "modules", "status", "players");

    private final String coreVersion;
    private final ModuleRegistry registry;
    private final FrameRouter<?> router;
    private final HandshakeService<Player> handshake;

    CoreCommand(String coreVersion, ModuleRegistry registry, FrameRouter<?> router, HandshakeService<Player> handshake) {
        this.coreVersion = coreVersion;
        this.registry = registry;
        this.router = router;
        this.handshake = handshake;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "version" : args[0].toLowerCase();
        switch (sub) {
            case "version" -> sender.sendMessage(Component.text("PeakCore " + coreVersion + " (API " + PeakCoreApi.API_VERSION + ")", NamedTextColor.GOLD));
            case "modules" -> {
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
            default -> {
                sender.sendMessage(Component.text("Benutzung: /" + label + " <version|modules|status|players>", NamedTextColor.RED));
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase();
        return SUB.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
