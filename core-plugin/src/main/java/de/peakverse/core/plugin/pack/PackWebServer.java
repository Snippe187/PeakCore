package de.peakverse.core.plugin.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kleiner HTTP-Server, der die gebauten Packs ausliefert: {@code GET /pack/<sha1>.zip} liefert {@code pack-<sha1>.zip} aus dem Pack-Ordner.
 * Alles andere ist 404 (kein Verzeichnis-Listing, kein Dateizugriff ausserhalb des Pack-Ordners: der Hash wird vor dem Zugriff streng geprueft).
 * Nur GET und HEAD. Laeuft auf eigenen Threads und beruehrt keine Bukkit-Objekte.
 */
public final class PackWebServer {
    private static final Pattern PATH = Pattern.compile("/pack/([0-9a-f]{40})\\.zip");

    private final Path folder;
    private final String bind;
    private final int port;
    private final Logger log;
    private HttpServer server;
    private ExecutorService executor;

    public PackWebServer(Path folder, String bind, int port, Logger log) {
        this.folder = folder;
        this.bind = bind;
        this.port = port;
        this.log = log;
    }

    /** Startet den Server. @throws IOException wenn der Port nicht frei ist oder die Adresse nicht passt */
    public synchronized void start() throws IOException {
        if (server != null) return;
        HttpServer s = HttpServer.create(new InetSocketAddress(bind, port), 16);
        s.createContext("/", this::handle);
        executor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "PeakCore-PackWeb");
            t.setDaemon(true);
            return t;
        });
        s.setExecutor(executor);
        s.start();
        server = s;
    }

    public synchronized void stop() {
        if (server == null) return;
        server.stop(0);
        executor.shutdownNow();
        server = null;
        executor = null;
    }

    public synchronized boolean running() {
        return server != null;
    }

    /** Der tatsaechliche Port (bei Port 0 der vom System gewaehlte). */
    public synchronized int port() {
        return server == null ? port : server.getAddress().getPort();
    }

    /** Alles, was nicht {@code /pack/...} ist, geht an diesen Handler (die Web-Oberflaeche); er schliesst die Verbindung selbst. */
    public void setFallback(com.sun.net.httpserver.HttpHandler fallback) {
        this.fallback = fallback;
    }

    private volatile com.sun.net.httpserver.HttpHandler fallback;

    private void handle(HttpExchange ex) throws IOException {
        com.sun.net.httpserver.HttpHandler other = fallback;
        if (other != null && !ex.getRequestURI().getRawPath().startsWith("/pack/")) {
            other.handle(ex);
            return;
        }
        try (ex) {
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                ex.getResponseHeaders().set("Allow", "GET, HEAD");
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Matcher m = PATH.matcher(ex.getRequestURI().getRawPath());
            Path file = m.matches() ? folder.resolve("pack-" + m.group(1) + ".zip") : null;
            if (file == null || !Files.isRegularFile(file)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            long size = Files.size(file);
            ex.getResponseHeaders().set("Content-Type", "application/zip");
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable"); // der Hash steht im Namen: Inhalt aendert sich nie
            if (method.equals("HEAD")) {
                ex.getResponseHeaders().set("Content-Length", Long.toString(size));
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, size);
            try (OutputStream out = ex.getResponseBody()) {
                Files.copy(file, out);
            }
        } catch (IOException e) {
            // Abbruch durch den Client (Spieler bricht den Download ab) ist normal
            log.log(Level.FINE, "Pack-Download abgebrochen", e);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Fehler im Pack-Webserver", e);
        }
    }
}
