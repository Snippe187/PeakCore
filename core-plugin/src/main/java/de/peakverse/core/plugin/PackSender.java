package de.peakverse.core.plugin;

import de.peakverse.core.api.event.PlayerHandshakeEvent;
import de.peakverse.core.plugin.pack.PackService;
import de.peakverse.core.protocol.CorePacket;
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

    /** Zugang zu Handshake und Kanal fuer den Weg ueber die Mod. */
    interface ModLink {
        boolean isModded(Player player);

        void send(Player player, CorePacket.Clientbound packet);
    }

    private final ModLink mod;

    PackSender(JavaPlugin plugin, PackService packs, ModLink mod, Logger log) {
        this.plugin = plugin;
        this.packs = packs;
        this.mod = mod;
        this.log = log;
    }

    /** Laedt die Mod das Pack selbst (statt Annahme-Dialog), wenn der Spieler sie hat? */
    boolean viaMod() {
        return config().getBoolean("pack.send.via-mod", true);
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
        if (viaMod() && mod.isModded(player)) {
            long size = packs.sizeOf(sha);
            if (size <= 0) {
                log.warning("Pack-Angebot an " + player.getName() + " nicht gesendet: Groesse von " + sha + " unbekannt");
                return false;
            }
            try {
                mod.send(player, new CorePacket.PackOffer(url, sha, size));
            } catch (IllegalArgumentException e) {
                log.warning("Pack-Angebot an " + player.getName() + " nicht gesendet: " + e.getMessage());
                return false;
            }
            return true;
        }
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
        // Warten: direkt im Beitritt ist die Verbindung noch im Aufbau. Mit Mod-Weg etwas laenger, damit der Handshake fertig ist und
        // Spieler mit Mod (die das Angebot schon beim Handshake bekommen) keinen Annahme-Dialog sehen
        long wait = viaMod() ? Math.max(20L, config().getLong("non-mod-hint.delay-ticks", 100L)) : 20L;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !(viaMod() && mod.isModded(p))) send(p);
        }, wait);
    }

    /** Der Handshake mit der Mod ist fertig: das Pack als Angebot schicken. */
    @EventHandler
    public void onHandshake(PlayerHandshakeEvent event) {
        if (enabled() && viaMod()) send(event.getPlayer());
    }

    /** Meldet, was beim Spieler mit unserem Pack passiert (angenommen, heruntergeladen, abgelehnt, fehlgeschlagen). */
    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!PACK_ID.equals(event.getID())) return;
        log.info("Pack bei " + event.getPlayer().getName() + ": " + event.getStatus());
    }
}
