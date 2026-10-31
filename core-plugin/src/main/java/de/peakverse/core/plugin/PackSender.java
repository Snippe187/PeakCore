package de.peakverse.core.plugin;

import de.peakverse.core.plugin.pack.PackService;
import de.peakverse.core.plugin.pack.PackState;
import de.peakverse.core.plugin.pack.PackUrls;
import java.util.Collection;
import java.util.HexFormat;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Schickt Spielern das gebaute Pack (Paper-API {@code setResourcePack}). Das Pack hat eine feste ID: ein neues Pack ersetzt das alte beim Spieler,
 * statt zusaetzlich geladen zu werden. Wer ablehnt, wird nicht gekickt, ausser {@code pack.send.required} ist an.
 * Muss auf dem Haupt-Thread laufen.
 */
final class PackSender implements Listener {
    /** Feste Pack-ID von PeakVerse: gleiche ID = ersetzt das vorherige Pack beim Client. */
    static final UUID PACK_ID = UUID.nameUUIDFromBytes("peakverse:server-pack".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private final JavaPlugin plugin;
    private final PackService packs;
    private final Logger log;

    PackSender(JavaPlugin plugin, PackService packs, Logger log) {
        this.plugin = plugin;
        this.packs = packs;
        this.log = log;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    boolean enabled() {
        return config().getBoolean("pack.send.enabled", false);
    }

    /** Die Adresse eines Packs oder {@code null}, wenn keine Adresse eingestellt ist. @throws IllegalArgumentException bei ungueltiger Einstellung */
    String urlFor(String sha1) {
        return PackUrls.urlFor(sha1, config().getString("pack.public-url", ""), config().getString("pack.external-url", ""));
    }

    /** Die Adresse des Live-Packs der Standard-Variante (fuer die Anzeige) oder {@code null}, wenn keins gebaut oder keine Adresse eingestellt ist. */
    String currentUrl() {
        String sha = packs.liveSha(PackState.STANDARD);
        return sha == null ? null : urlFor(sha);
    }

    /** @return {@code true}, wenn das Pack an den Spieler geschickt wurde */
    boolean send(Player player) {
        String sha = packs.shaFor(player.getUniqueId());
        String url;
        try {
            url = sha == null ? null : urlFor(sha);
        } catch (IllegalArgumentException e) {
            log.warning("Pack nicht gesendet: " + e.getMessage());
            return false;
        }
        if (url == null) return false;
        String prompt = config().getString("pack.send.prompt", "");
        player.setResourcePack(PACK_ID, url, HexFormat.of().parseHex(sha), prompt.isBlank() ? null : Component.text(prompt),
                config().getBoolean("pack.send.required", false));
        return true;
    }
    /** @return die Zahl der Spieler, denen das Pack geschickt wurde */
    int sendAll(Collection<? extends Player> players) {
        int n = 0;
        for (Player p : players) if (send(p)) n++;
        return n;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled()) return;
        Player p = event.getPlayer();
        // Einen Tick warten: direkt im Beitritt ist die Verbindung noch im Aufbau
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) send(p);
        }, 20L);
    }

    /** Meldet, was beim Spieler mit unserem Pack passiert (angenommen, heruntergeladen, abgelehnt, fehlgeschlagen). */
    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!PACK_ID.equals(event.getID())) return;
        log.info("Pack bei " + event.getPlayer().getName() + ": " + event.getStatus());
    }
}
