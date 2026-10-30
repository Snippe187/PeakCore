package de.peakverse.probe;

import de.peakverse.core.api.ModuleInfo;
import de.peakverse.core.api.ModuleRegistration;
import de.peakverse.core.api.ModuleRegistrationException;
import de.peakverse.core.api.PeakCoreApi;
import java.nio.charset.StandardCharsets;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Minimales Modul als Vorlage und Test: meldet sich als "probe" an, schickt jedes empfangene Paket als Echo zurueck
 * und bietet {@code /probe} zum Ausprobieren der Ablehnungsfaelle.
 */
public final class ProbePlugin extends JavaPlugin implements CommandExecutor {
    private PeakCoreApi core;
    private ModuleRegistration registration;

    @Override
    public void onEnable() {
        core = PeakCoreApi.get().orElseThrow(() -> new IllegalStateException("PeakCore nicht gefunden"));
        registration = core.registerModule(this, new ModuleInfo("probe", "PeakCore Probe", getPluginMeta().getVersion(), 1));
        registration.setMessageHandler((player, payload) -> {
            getLogger().info("Paket von " + player.getName() + ": " + payload.length + " Bytes, Echo wird gesendet");
            registration.send(player, payload);
        });
        getCommand("probe").setExecutor(this);
    }

    @Override
    public void onDisable() {
        if (registration != null) registration.unregister();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();
        switch (sub) {
            case "info" -> sender.sendMessage("probe: Modul " + registration.info().id() + ", API " + core.apiVersion() + ", Core " + core.coreVersion()
                    + ", max. Nutzdaten zum Client " + registration.maxPayloadToClient() + ", vom Client " + registration.maxPayloadFromClient());
            case "send" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    sender.sendMessage("probe: Spieler nicht gefunden");
                    break;
                }
                boolean sent = registration.send(target, "hallo".getBytes(StandardCharsets.UTF_8));
                sender.sendMessage("probe: an " + target.getName() + " gesendet: " + sent + " (false = Mod nicht verbunden)");
            }
            case "duplicate" -> sender.sendMessage("probe: " + tryRegister(new ModuleInfo("probe", "Doppelt", "1", 1)));
            case "toonew" -> sender.sendMessage("probe: " + tryRegister(new ModuleInfo("neuermodul", "Zu neu", "1", PeakCoreApi.API_VERSION + 5)));
            default -> {
                return false;
            }
        }
        return true;
    }

    private String tryRegister(ModuleInfo info) {
        try {
            core.registerModule(this, info);
            return "ANGEMELDET (unerwartet!)";
        } catch (ModuleRegistrationException e) {
            return "abgelehnt: " + e.getMessage();
        }
    }
}
