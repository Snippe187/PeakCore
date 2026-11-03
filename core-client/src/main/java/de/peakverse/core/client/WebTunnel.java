package de.peakverse.core.client;

import de.peakverse.core.protocol.CorePacket;
import de.peakverse.core.protocol.CoreProtocol;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Web-Tunnel, Seite der Mod: ein kleiner Proxy auf {@code 127.0.0.1}. Der Browser spricht mit diesem Port, die Mod reicht die Bytes ueber die
 * Minecraft-Verbindung an den Server weiter (der verbindet sich mit seiner Web-Oberflaeche) und die Antworten zurueck. So braucht die Web-Oberflaeche
 * keinen von aussen erreichbaren Port. Der Proxy lauscht nur auf Loopback, nur solange eine Verbindung zum Server besteht, und startet erst auf Wunsch.
 * Reine Logik ohne Minecraft: {@code mainThread} bringt Sendungen auf den Client-Haupt-Thread (Aufrufe von {@link ClientSession} gehoeren dorthin).
 */
public final class WebTunnel implements ClientSession.Listener, AutoCloseable {
    /** So lange wartet die Mod auf die Antwort des Servers auf die Bitte um einen Link. */
    static final long LOGIN_TIMEOUT_MS = 6000;
    private static final byte[] CLOSE = new byte[0];
    private static final int MAX_QUEUED_DOWN = 1024;

    private final ClientSession session;
    private final Executor mainThread;
    private final LongSupplier clock;
    private final Consumer<String> onLink;
    private final Consumer<String> onFailure;

    private final Map<Integer, Conn> conns = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile ServerSocket server;
    private volatile boolean pending;
    private volatile long requestedAt;

    private final class Conn {
        final int id;
        final Socket socket;
        final LinkedBlockingQueue<byte[]> down = new LinkedBlockingQueue<>(MAX_QUEUED_DOWN);
        volatile boolean done;

        Conn(int id, Socket socket) {
            this.id = id;
            this.socket = socket;
        }
    }

    /**
     * @param onLink    bekommt die vollstaendige Adresse (mit Einmal-Schluessel), wenn der Server zugestimmt hat
     * @param onFailure bekommt eine Meldung fuer den Spieler, wenn es nicht klappt
     */
    public WebTunnel(ClientSession session, Executor mainThread, LongSupplier clock, Consumer<String> onLink, Consumer<String> onFailure) {
        this.session = session;
        this.mainThread = mainThread;
        this.clock = clock;
        this.onLink = onLink;
        this.onFailure = onFailure;
    }

    // ---------- Starten ----------

    /** Bittet den Server um einen Link und startet bei Bedarf den lokalen Proxy. Das Ergebnis kommt ueber {@code onLink} oder {@code onFailure}. */
    public void open() {
        if (!session.isActive()) {
            onFailure.accept("Keine Verbindung zu einem Server mit PeakCore.");
            return;
        }
        try {
            ensureStarted();
        } catch (IOException e) {
            onFailure.accept("Der lokale Tunnel konnte nicht starten: " + e.getMessage());
            return;
        }
        pending = true;
        requestedAt = clock.getAsLong();
        session.sendCore(new CorePacket.TunnelLoginRequest());
    }

    /** Einmal je Client-Tick: meldet, wenn der Server nicht antwortet (aelterer PeakCore ohne Tunnel). */
    public void tick() {
        if (pending && clock.getAsLong() - requestedAt > LOGIN_TIMEOUT_MS) {
            pending = false;
            onFailure.accept("Der Server antwortet nicht. Er braucht PeakCore ab Version 0.1.4 mit eingeschalteter Web-Oberflaeche (web.enabled und web.tunnel).");
        }
    }

    /** Lokaler Port des Proxys oder 0, solange er nicht laeuft. */
    public int port() {
        ServerSocket s = server;
        return s == null ? 0 : s.getLocalPort();
    }

    public int connections() {
        return conns.size();
    }

    private synchronized void ensureStarted() throws IOException {
        if (server != null && !server.isClosed()) return;
        ServerSocket s = new ServerSocket(0, 32, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        server = s;
        Thread t = new Thread(() -> acceptLoop(s), "PeakCore-WebTunnel-Accept");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                Socket browser = s.accept();
                browser.setTcpNoDelay(true);
                int id = nextId.getAndIncrement();
                Conn c = new Conn(id, browser);
                conns.put(id, c);
                mainThread.execute(() -> session.sendCore(new CorePacket.TunnelOpen(id)));
                daemon("PeakCore-WebTunnel-Up-" + id, () -> readBrowser(c));
                daemon("PeakCore-WebTunnel-Down-" + id, () -> writeBrowser(c));
            } catch (IOException e) {
                if (s.isClosed()) return;
            }
        }
    }

    /** Browser -> Server. */
    private void readBrowser(Conn c) {
        byte[] buf = new byte[CoreProtocol.TUNNEL_MAX_UP];
        try {
            InputStream in = c.socket.getInputStream();
            int n;
            while (!c.done && (n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                byte[] chunk = Arrays.copyOf(buf, n);
                mainThread.execute(() -> session.sendCore(new CorePacket.TunnelUp(c.id, chunk)));
            }
        } catch (IOException ignored) {
            // Browser hat die Verbindung beendet
        }
        if (!c.done) mainThread.execute(() -> session.sendCore(new CorePacket.TunnelEnd(c.id)));
    }

    /** Server -> Browser (eigener Thread, damit ein langsamer Browser das Spiel nicht aufhaelt). */
    private void writeBrowser(Conn c) {
        try {
            OutputStream out = c.socket.getOutputStream();
            while (true) {
                byte[] chunk = c.down.take();
                if (chunk == CLOSE) break;
                out.write(chunk);
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Browser weg
        }
        finish(c);
    }

    private void finish(Conn c) {
        if (c.done) return;
        c.done = true;
        conns.remove(c.id, c);
        try {
            c.socket.close();
        } catch (IOException ignored) {
            // egal
        }
    }

    // ---------- Antworten des Servers (Haupt-Thread) ----------

    @Override
    public void onTunnelLogin(String path) {
        if (!pending) return;
        pending = false;
        int port = port();
        if (path.isEmpty() || !path.startsWith("/") || port == 0) {
            onFailure.accept("Du hast keinen Zugang zur Web-Oberflaeche, oder sie ist auf dem Server aus (web.enabled).");
            return;
        }
        onLink.accept("http://127.0.0.1:" + port + path);
    }

    @Override
    public void onTunnelData(int conn, byte[] data) {
        Conn c = conns.get(conn);
        if (c == null) return;
        if (!c.down.offer(data)) {
            // der Browser liest zu langsam: lieber beenden als Speicher fuellen
            c.down.clear();
            c.down.offer(CLOSE);
            mainThread.execute(() -> session.sendCore(new CorePacket.TunnelEnd(conn)));
        }
    }

    @Override
    public void onTunnelClosed(int conn) {
        Conn c = conns.get(conn);
        if (c != null) c.down.offer(CLOSE);
    }

    @Override
    public void onStateChanged(ClientSession.State previous, ClientSession.State now) {
        if (now != ClientSession.State.ACTIVE) close();
    }

    /** Beendet den Proxy und alle Verbindungen. */
    @Override
    public synchronized void close() {
        pending = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // egal
            }
        }
        for (Conn c : conns.values()) {
            c.down.clear();
            c.down.offer(CLOSE);
            finish(c);
        }
    }

    public String debug() {
        return server == null ? "Web-Tunnel: aus" : "Web-Tunnel: Port " + port() + ", " + conns.size() + " Verbindungen";
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
