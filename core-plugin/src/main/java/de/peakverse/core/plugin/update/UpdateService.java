package de.peakverse.core.plugin.update;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Update-Dienst: prueft regelmaessig das Release-Repo, laedt ein neues Jar in {@code plugins/update/} (Paper tauscht es beim naechsten Start aus)
 * und kann den Server mit Countdown neu starten. Ein Reload im laufenden Betrieb gibt es bewusst nicht (Klassen und Songs im Speicher).
 */
public final class UpdateService implements Listener {
    private static final Gson GSON = new Gson();

    private final JavaPlugin plugin;
    private final String currentVersion;
    private final String jarName;
    private final Path stagedMarker;
    private volatile UpdateChecker checker;
    private volatile UpdateChecker.Release latest;
    private volatile String error = "";
    private volatile long checkedAt;
    private volatile boolean busy;
    private volatile String stagedVersion = "";
    private BukkitTask countdown;
    private volatile int secondsLeft = -1;

    public UpdateService(JavaPlugin plugin, String currentVersion, String jarName) {
        this.plugin = plugin;
        this.currentVersion = currentVersion;
        this.jarName = jarName;
        this.stagedMarker = plugin.getDataFolder().toPath().resolve("staged-version.txt");
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("update.enabled", false);
    }

    private Path stagedJar() {
        return Bukkit.getUpdateFolderFile().toPath().resolve(jarName);
    }

    public void start() {
        try {
            checker = new UpdateChecker(plugin.getConfig().getString("update.api-url", "https://api.github.com"), plugin.getConfig().getString("update.repo", ""));
        } catch (IllegalArgumentException e) {
            error = "Update-Quelle ungueltig: " + e.getMessage();
        }
        if (Files.isRegularFile(stagedJar()) && Files.isRegularFile(stagedMarker)) {
            try {
                stagedVersion = Files.readString(stagedMarker).strip();
                // Ein bereitgelegtes Update, das nicht neuer ist als das laufende Plugin, wuerde sonst beim naechsten Start zurueckstufen
                if (UpdateChecker.compare(stagedVersion, currentVersion) <= 0) {
                    Files.deleteIfExists(stagedJar());
                    Files.deleteIfExists(stagedMarker);
                    plugin.getLogger().info("Veraltetes bereitgelegtes Update " + stagedVersion + " entfernt.");
                    stagedVersion = "";
                }
            } catch (IOException ignored) {
                // nur Anzeige
            }
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (enabled() && checker != null) {
            long ticks = Math.max(1, plugin.getConfig().getLong("update.check-hours", 12)) * 3600L * 20L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::check, 600L, ticks);
        }
    }

    public void stop() {
        cancelRestart();
    }

    // ---- Pruefen und Herunterladen ----

    /** Fragt das Repo (blockiert; aus einem asynchronen Task oder einer Web-Anfrage aufrufen). */
    public synchronized void check() {
        if (checker == null) return;
        try {
            latest = checker.latest();
            error = "";
        } catch (IOException e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Abgebrochen";
        } catch (RuntimeException e) {
            error = e.toString();
        }
        checkedAt = System.currentTimeMillis();
        if (isNewer() && enabled()) notifyAdmins();
    }

    public boolean isNewer() {
        UpdateChecker.Release r = latest;
        return r != null && UpdateChecker.compare(r.version(), currentVersion) > 0;
    }

    /** Laedt die neueste Version nach {@code plugins/update/} (blockiert). */
    public synchronized void download() throws IOException {
        if (checker == null) throw new IOException(error.isEmpty() ? "Keine Update-Quelle eingestellt (update.repo)." : error);
        if (latest == null) check();
        if (!isNewer()) throw new IOException("Es gibt keine neuere Version.");
        busy = true;
        try {
            checker.download(latest, stagedJar());
            stagedVersion = latest.version();
            Files.writeString(stagedMarker, stagedVersion);
            error = "";
            plugin.getLogger().info("Update " + stagedVersion + " geladen und geprueft. Es wird beim naechsten Start installiert.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Abgebrochen");
        } finally {
            busy = false;
        }
    }

    private void notifyAdmins() {
        UpdateChecker.Release r = latest;
        for (Player p : Bukkit.getOnlinePlayers()) if (p.isOp()) tell(p, r);
    }

    private void tell(Player p, UpdateChecker.Release r) {
        p.sendMessage(Component.text("[PeakVerse] Neue Version " + r.version() + " verfuegbar (du hast " + currentVersion + "). ", NamedTextColor.GOLD)
                .append(Component.text("[Release ansehen]", NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(r.page()))));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (event.getPlayer().isOp() && enabled() && isNewer()) tell(event.getPlayer(), latest);
    }

    // ---- Neustart mit Countdown ----

    /** Startet den Countdown; nach Ablauf wird gespeichert und neu gestartet (oder beendet, je nach update.restart-mode). */
    public synchronized void scheduleRestart(int minutes) {
        if (minutes < 0 || minutes > 120) throw new IllegalArgumentException("Die Wartezeit muss zwischen 0 und 120 Minuten liegen.");
        cancelRestart();
        secondsLeft = minutes * 60;
        broadcast("Der Server startet in " + describe(secondsLeft) + " neu (Update). Bitte rechtzeitig fertig bauen.");
        countdown = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            secondsLeft--;
            if (secondsLeft == 120 || secondsLeft == 60 || secondsLeft == 30 || secondsLeft == 10 || (secondsLeft <= 5 && secondsLeft > 0)) {
                broadcast("Neustart in " + describe(secondsLeft) + ".");
            }
            if (secondsLeft <= 0) {
                cancelRestart();
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
                plugin.getLogger().info("Neustart fuer das Update.");
                if ("stop".equalsIgnoreCase(plugin.getConfig().getString("update.restart-mode", "restart"))) Bukkit.shutdown();
                else restartServer();
            }
        }, 20L, 20L);
    }

    public synchronized boolean cancelRestart() {
        boolean was = countdown != null;
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
        }
        if (was && secondsLeft > 0) broadcast("Der geplante Neustart wurde abgebrochen.");
        secondsLeft = -1;
        return was;
    }

    /** Neustart ueber Spigots Schnittstelle (per Reflexion, weil sie als veraltet markiert ist); klappt sie nicht, wird der Server beendet. */
    private void restartServer() {
        try {
            Object spigot = Bukkit.class.getMethod("spigot").invoke(null);
            spigot.getClass().getMethod("restart").invoke(spigot);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Neustart ueber Spigot nicht moeglich (" + e + "), der Server wird beendet.");
            Bukkit.shutdown();
        }
    }
    private static String describe(int s) {
        return s >= 120 ? (s / 60) + " Minuten" : s >= 60 ? "1 Minute" : s + " Sekunden";
    }

    private static void broadcast(String text) {
        Bukkit.broadcast(Component.text("[PeakVerse] " + text, NamedTextColor.GOLD));
    }

    // ---- Anzeige ----

    public String statusJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled());
        o.addProperty("repo", plugin.getConfig().getString("update.repo", ""));
        o.addProperty("current", currentVersion);
        o.addProperty("checkedAt", checkedAt);
        o.addProperty("error", error);
        o.addProperty("busy", busy);
        o.addProperty("newer", isNewer());
        UpdateChecker.Release r = latest;
        if (r != null) {
            JsonObject l = new JsonObject();
            l.addProperty("version", r.version());
            l.addProperty("name", r.name());
            l.addProperty("notes", r.notes().length() > 4000 ? r.notes().substring(0, 4000) : r.notes());
            l.addProperty("page", r.page());
            l.addProperty("size", r.size());
            l.addProperty("hasChecksum", r.shaUrl() != null);
            o.add("latest", l);
        }
        o.addProperty("staged", Files.isRegularFile(stagedJar()));
        o.addProperty("stagedVersion", stagedVersion);
        o.addProperty("restartIn", secondsLeft);
        o.addProperty("restartMinutes", plugin.getConfig().getInt("update.restart-minutes", 5));
        o.addProperty("restartMode", plugin.getConfig().getString("update.restart-mode", "restart"));
        return GSON.toJson(o);
    }

    public static void warn(JavaPlugin plugin, String what, Exception e) {
        plugin.getLogger().log(Level.WARNING, what, e);
    }
}
