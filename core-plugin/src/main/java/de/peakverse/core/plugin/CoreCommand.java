package de.peakverse.core.plugin;

import de.peakverse.core.api.PeakCoreApi;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** {@code /peakcore}: zeigt Version und angemeldete Module. Nur fuer Operatoren (Recht peakcore.admin). */
final class CoreCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUB = List.of("version", "modules", "status");

    private final String coreVersion;
    private final ModuleRegistry registry;
    private final FrameRouter<?> router;

    CoreCommand(String coreVersion, ModuleRegistry registry, FrameRouter<?> router) {
        this.coreVersion = coreVersion;
        this.registry = registry;
        this.router = router;
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
                sender.sendMessage(Component.text("Kanal " + de.peakverse.core.protocol.Frame.CHANNEL + ": " + s.received() + " Pakete empfangen, "
                        + s.delivered() + " zugestellt, " + s.malformed() + " ungueltig, " + s.unknownModule() + " an unbekannte Module, "
                        + s.handlerErrors() + " Handler-Fehler", NamedTextColor.GOLD));
            }
            default -> {
                sender.sendMessage(Component.text("Benutzung: /" + label + " <version|modules|status>", NamedTextColor.RED));
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
