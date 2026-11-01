package de.peakverse.core.plugin.perm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Gibt Spielern die Rechte, die in PeakCore (Rolle, Einzelrechte, Sperren) eingestellt sind: je Spieler ein {@link PermissionAttachment}.
 * Wer keinen Eintrag hat, wird nicht angefasst. Sperren laufen von selbst ab (regelmaessiger Abgleich).
 */
public final class PermissionService implements Listener {
    /** Rechte-Praefixe, die PeakCore verwaltet (je Modul). */
    public static final List<String> PREFIXES = List.of("peakcore.", "peaksound.", "texturepeak.", "peakboard.");

    private final JavaPlugin plugin;
    private final PermStore store;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public PermissionService(JavaPlugin plugin, PermStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /** Alle bekannten Rechte der PeakVerse-Module (aus der plugin.yml und den Modulen). */
    public List<Permission> catalog() {
        List<Permission> out = new ArrayList<>();
        for (Permission p : Bukkit.getPluginManager().getPermissions()) {
            if (PREFIXES.stream().anyMatch(p.getName()::startsWith)) out.add(p);
        }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    public List<String> catalogNames() {
        return catalog().stream().map(Permission::getName).toList();
    }

    /** Das Modul eines Rechts: der Teil vor dem ersten Punkt. */
    public static String moduleOf(String node) {
        int dot = node.indexOf('.');
        return dot < 0 ? node : node.substring(0, dot);
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        applyAll();
        // Sperren laufen ab: einmal pro Minute abgleichen
        Bukkit.getScheduler().runTaskTimer(plugin, this::applyAll, 1200L, 1200L);
    }

    public void stop() {
        for (UUID id : List.copyOf(attachments.keySet())) remove(id);
    }

    public void applyAll() {
        for (Player p : Bukkit.getOnlinePlayers()) apply(p);
    }

    /** Rechte dieses Spielers neu setzen (auf dem Haupt-Thread aufrufen). */
    public void apply(Player player) {
        remove(player.getUniqueId());
        store.rememberName(player.getUniqueId(), player.getName());
        Map<String, Boolean> eff = store.effective(player.getUniqueId(), catalogNames());
        if (eff.isEmpty()) return;
        PermissionAttachment a = player.addAttachment(plugin);
        eff.forEach(a::setPermission);
        attachments.put(player.getUniqueId(), a);
        player.recalculatePermissions();
    }

    private void remove(UUID id) {
        PermissionAttachment a = attachments.remove(id);
        if (a != null) {
            try {
                a.remove();
            } catch (IllegalArgumentException ignored) {
                // schon entfernt
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer().getUniqueId());
    }
}
