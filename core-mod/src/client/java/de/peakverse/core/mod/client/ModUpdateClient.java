package de.peakverse.core.mod.client;

import de.peakverse.core.client.update.ModUpdater;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric-Seite des Mod-Updates: sucht beim Start im Release-Repo nach neueren Versionen der installierten PeakVerse-Mods, meldet das beim Beitritt im Chat
 * und laedt auf {@code /peakcoreupdate} die neuen Jars (mit Pruefsumme) in einen Zwischenordner. Getauscht wird erst nach dem Beenden des Spiels
 * durch ein kleines Skript, weil eine laufende Mod ihr Jar sperrt. Einstellungen: {@code config/peakcore/mod-update.properties}.
 */
final class ModUpdateClient {
    private static final Logger LOG = LoggerFactory.getLogger("PeakCore/ModUpdate");

    private final Path configFile = FabricLoader.getInstance().getConfigDir().resolve("peakcore").resolve("mod-update.properties");
    private final Path stageDir = FabricLoader.getInstance().getConfigDir().resolve("peakcore").resolve("update-stage");
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PeakCore-ModUpdate");
        t.setDaemon(true);
        return t;
    });
    private final List<ModUpdater.Swap> pending = new ArrayList<>();
    private volatile List<ModUpdater.Available> available = List.of();
    private volatile boolean announced;
    private volatile String lastError = "";
    private boolean enabled = true;
    private ModUpdater updater;
    private boolean hookInstalled;

    /** Liest die Einstellungen und startet die Pruefung im Hintergrund. */
    void start() {
        Properties p = new Properties();
        p.setProperty("enabled", "true");
        p.setProperty("repo", "Snippe187/PeakVerse-Releases");
        p.setProperty("api-url", "https://api.github.com");
        try {
            if (Files.isRegularFile(configFile)) {
                try (InputStream in = Files.newInputStream(configFile)) {
                    p.load(in);
                }
            } else {
                Files.createDirectories(configFile.getParent());
                Files.writeString(configFile, "# Mod-Updates aus dem Release-Repo. enabled=false schaltet die Pruefung ab.\n"
                        + "enabled=true\nrepo=Snippe187/PeakVerse-Releases\napi-url=https://api.github.com\n");
            }
            enabled = Boolean.parseBoolean(p.getProperty("enabled").trim());
            if (enabled) updater = new ModUpdater(p.getProperty("api-url").trim(), p.getProperty("repo").trim());
        } catch (IOException | RuntimeException e) {
            enabled = false;
            LOG.warn("mod-update.properties nicht brauchbar, Mod-Updates aus: {}", e.getMessage());
        }
        if (enabled) io.execute(this::check);
    }

    /** Installierte PeakVerse-Mods: ID -> Version, nur solche, die als Jar im mods-Ordner liegen (nicht im Entwicklungsordner). */
    private Map<String, String> installed() {
        Map<String, String> m = new TreeMap<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            if (c.getOrigin().getKind() == ModOrigin.Kind.PATH && jarOf(c) != null) m.put(c.getMetadata().getId(), c.getMetadata().getVersion().getFriendlyString());
        }
        return m;
    }

    private static Path jarOf(ModContainer c) {
        for (Path p : c.getOrigin().getPaths()) if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) return p;
        return null;
    }

    private void check() {
        try {
            available = updater.check(installed());
            lastError = "";
            LOG.info("Mod-Update-Pruefung: {}", available.isEmpty() ? "alles aktuell" : available.size() + " Update(s) verfuegbar");
        } catch (IOException e) {
            lastError = e.getMessage();
            LOG.info("Mod-Update-Pruefung nicht moeglich: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Beim Beitritt: einmal im Chat auf ein Update hinweisen. */
    void onJoin(Minecraft client) {
        if (announced || available.isEmpty() || client.player == null) return;
        announced = true;
        StringBuilder s = new StringBuilder("[PeakVerse] Update fuer die Mods verfuegbar:");
        for (ModUpdater.Available a : available) s.append(' ').append(a.modId()).append(' ').append(a.installed()).append(" -> ").append(a.asset().version()).append(';');
        client.player.sendSystemMessage(Component.literal(s + " Mit /peakcoreupdate herunterladen, getauscht wird beim Beenden des Spiels."));
    }

    /** {@code /peakcoreupdate}: laedt die verfuegbaren Updates in den Zwischenordner. @return die Meldung fuer den Chat */
    String command(Minecraft client) {
        if (!enabled) return "Mod-Updates sind aus (config/peakcore/mod-update.properties).";
        io.execute(() -> {
            check();
            String msg;
            if (!lastError.isEmpty()) msg = "Update-Pruefung fehlgeschlagen: " + lastError;
            else if (available.isEmpty()) msg = "Alle PeakVerse-Mods sind aktuell.";
            else msg = stageAll();
            client.execute(() -> {
                if (client.player != null) client.player.sendSystemMessage(Component.literal("[PeakVerse] " + msg));
            });
        });
        return "Suche nach Updates ...";
    }

    private String stageAll() {
        Map<String, Path> jars = new TreeMap<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            Path j = c.getOrigin().getKind() == ModOrigin.Kind.PATH ? jarOf(c) : null;
            if (j != null) jars.put(c.getMetadata().getId(), j);
        }
        List<String> done = new ArrayList<>();
        for (ModUpdater.Available a : available) {
            try {
                Path staged = updater.stage(a.asset(), stageDir);
                synchronized (pending) {
                    pending.removeIf(w -> w.oldJar().equals(jars.get(a.modId())));
                    pending.add(new ModUpdater.Swap(jars.get(a.modId()), staged));
                }
                done.add(a.modId() + " " + a.asset().version());
            } catch (IOException e) {
                return "Download von " + a.modId() + " fehlgeschlagen: " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Abgebrochen.";
            }
        }
        installHook();
        return "Bereit: " + String.join(", ", done) + ". Spiel beenden und neu starten, dann sind die neuen Versionen aktiv.";
    }

    private synchronized void installHook() {
        if (hookInstalled) return;
        hookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            List<ModUpdater.Swap> swaps;
            synchronized (pending) {
                swaps = List.copyOf(pending);
            }
            if (swaps.isEmpty()) return;
            try {
                boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
                Path script = ModUpdater.writeSwapScript(stageDir, ProcessHandle.current().pid(), swaps, windows);
                ProcessBuilder pb = windows
                        ? new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString())
                        : new ProcessBuilder("sh", script.toString());
                pb.start();
            } catch (IOException e) {
                LOG.warn("Update-Tausch konnte nicht gestartet werden: {}", e.getMessage());
            }
        }, "PeakCore-ModUpdate-Swap"));
    }

    String debug() {
        return "Mod-Update: " + (enabled ? "an" : "aus") + ", verfuegbar: " + available.size() + (lastError.isEmpty() ? "" : ", Fehler: " + lastError);
    }
}
