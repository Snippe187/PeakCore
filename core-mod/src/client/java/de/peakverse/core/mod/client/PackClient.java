package de.peakverse.core.mod.client;

import de.peakverse.core.client.PackReceiver;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric-Seite des Pack-Empfangs: laedt das Pack in {@code config/peakcore/packs}, prueft SHA1 und Groesse und schaltet es beim Beitritt ein,
 * indem es als {@code resourcepacks/peakverse-<sha>.zip} bereitgestellt und in die Auswahl aufgenommen wird.
 */
final class PackClient implements PackReceiver.Env {
    private static final Logger LOG = LoggerFactory.getLogger("PeakCore/Pack");
    private static final String PREFIX = "peakverse-";

    private final Path cache = FabricLoader.getInstance().getConfigDir().resolve("peakcore").resolve("packs");
    private final Path activeFile = FabricLoader.getInstance().getConfigDir().resolve("peakcore").resolve("active-pack.txt");
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PeakCore-PackDownload");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    private Path cached(String sha1) {
        return cache.resolve(sha1 + ".zip");
    }

    @Override
    public boolean isCached(String sha1) {
        return Files.isRegularFile(cached(sha1));
    }

    @Override
    public void download(String url, String sha1, long size, PackReceiver.DownloadCallback callback) {
        Minecraft mc = Minecraft.getInstance();
        io.execute(() -> {
            String error = null;
            try {
                fetch(url, sha1, size);
            } catch (IOException | InterruptedException | RuntimeException e) {
                LOG.warn("Pack-Download fehlgeschlagen: {}", e.toString());
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            String result = error;
            mc.execute(() -> callback.done(result));
        });
    }

    private void fetch(String url, String sha1, long size) throws IOException, InterruptedException {
        Files.createDirectories(cache);
        Path tmp = cache.resolve(sha1 + ".tmp");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Server antwortet mit " + response.statusCode());
            MessageDigest digest = sha1Digest();
            long total = 0;
            try (var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                for (int n = body.read(buf); n >= 0; n = body.read(buf)) {
                    total += n;
                    if (total > size) throw new IOException("Datei ist groesser als angekuendigt");
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (total != size) throw new IOException("Groesse stimmt nicht (" + total + " statt " + size + " Bytes)");
            if (!HexFormat.of().formatHex(digest.digest()).equals(sha1)) throw new IOException("SHA1 stimmt nicht");
            Files.move(tmp, cached(sha1), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void activate(String sha1) {
        Minecraft mc = Minecraft.getInstance();
        try {
            Path dir = mc.getResourcePackDirectory();
            Files.createDirectories(dir);
            String name = PREFIX + sha1.substring(0, 12) + ".zip";
            Files.copy(cached(sha1), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);

            PackRepository repo = mc.getResourcePackRepository();
            repo.reload();
            String id = "file/" + name;
            if (!repo.isAvailable(id)) throw new IOException("Minecraft erkennt das Pack nicht: " + id);
            List<String> selected = new java.util.ArrayList<>(repo.getSelectedIds());
            selected.removeIf(s -> s.startsWith("file/" + PREFIX));
            selected.add(id);
            repo.setSelected(selected);
            mc.options.updateResourcePacks(repo);

            Files.createDirectories(activeFile.getParent());
            Files.writeString(activeFile, sha1);
            deleteOldCopies(dir, name);
            LOG.info("Server-Pack {} aktiviert", sha1);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Server-Pack konnte nicht aktiviert werden", e);
            chat("[PeakVerse] Das Server-Resource-Pack konnte nicht aktiviert werden: " + e.getMessage());
        }
    }

    private static void deleteOldCopies(Path dir, String keep) {
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith(PREFIX) && n.endsWith(".zip") && !n.equals(keep)) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        LOG.debug("Altes Pack {} noch in Benutzung, bleibt liegen", n);
                    }
                }
            }
        } catch (IOException e) {
            LOG.debug("Aufraeumen nicht moeglich: {}", e.toString());
        }
    }

    @Override
    public String activeSha1() {
        try {
            String s = Files.isRegularFile(activeFile) ? Files.readString(activeFile).trim() : "";
            return s.matches("[0-9a-f]{40}") ? s : null;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void chat(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(message));
        else LOG.info(message);
    }
}
