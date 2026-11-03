package de.peakverse.core.plugin;

import de.peakverse.core.protocol.CorePacket;
import de.peakverse.core.protocol.CoreProtocol;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

/**
 * Web-Tunnel, Seite des Servers: Die Mod des Spielers leitet den Browser ueber die Minecraft-Verbindung zur Web-Oberflaeche. Der Server oeffnet je
 * Verbindung einen Socket zur eigenen Web-Oberflaeche (Loopback) und reicht die Bytes in beide Richtungen weiter. Es gibt keine eigene Anmeldung:
 * die Web-Oberflaeche verlangt wie immer ihre Sitzung. Der Tunnel prueft nur, ob der Spieler Web-Zugang hat. Bukkit-frei ({@code P} ist der Spieler-Typ).
 */
final class TunnelService<P> implements AutoCloseable {
    interface Env<P> {
        UUID id(P player);

        /** Hat der Spieler Zugang zur Web-Oberflaeche (Operator oder Rolle)? */
        boolean allowed(P player);

        void send(P player, CorePacket.Clientbound packet);

        /** Pfad mit Einmal-Schluessel (z. B. {@code /login?t=...}) oder {@code null}, wenn die Web-Oberflaeche aus ist. */
        String loginPath(P player);

        /** Verbindung zur Web-Oberflaeche dieses Servers. */
        Socket connect() throws IOException;
    }

    /** Gleichzeitige Tunnel-Verbindungen je Spieler (ein Browser oeffnet mehrere, dazu Live-Meldungen). */
    static final int MAX_CONNECTIONS = 24;
    /** Wartende Stuecke je Verbindung (je hoechstens 16 KB); ist die Schlange voll, ist die Gegenseite zu langsam und die Verbindung wird beendet. */
    static final int MAX_QUEUED = 512;
    private static final byte[] END = new byte[0];

    private final class Conn {
        final UUID player;
        final int id;
        final LinkedBlockingQueue<byte[]> up = new LinkedBlockingQueue<>(MAX_QUEUED);
        volatile Socket socket;
        volatile boolean closed;

        Conn(UUID player, int id) {
            this.player = player;
            this.id = id;
        }
    }

    private final Env<P> env;
    private final Logger log;
    private final Map<UUID, Map<Integer, Conn>> conns = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "PeakCore-Tunnel");
        t.setDaemon(true);
        return t;
    });

    TunnelService(Env<P> env, Logger log) {
        this.env = env;
        this.log = log;
    }

    /** Ein Tunnel-Paket vom Spieler (Haupt-Thread). Wirft nie eine Ausnahme. */
    void onPacket(P player, CorePacket.Serverbound packet) {
        try {
            switch (packet) {
                case CorePacket.TunnelLoginRequest r -> login(player);
                case CorePacket.TunnelOpen o -> open(player, o.conn());
                case CorePacket.TunnelUp u -> up(player, u.conn(), u.data());
                case CorePacket.TunnelEnd e -> end(player, e.conn());
                default -> { }
            }
        } catch (RuntimeException e) {
            log.warning("Tunnel-Paket konnte nicht verarbeitet werden: " + e);
        }
    }

    private void login(P player) {
        String path = env.allowed(player) ? env.loginPath(player) : null;
        env.send(player, new CorePacket.TunnelLogin(path == null ? "" : path));
    }

    private void open(P player, int connId) {
        UUID pid = env.id(player);
        Map<Integer, Conn> mine = conns.computeIfAbsent(pid, k -> new ConcurrentHashMap<>());
        if (!env.allowed(player) || mine.size() >= MAX_CONNECTIONS) {
            env.send(player, new CorePacket.TunnelClosed(connId));
            return;
        }
        Conn c = new Conn(pid, connId);
        if (mine.putIfAbsent(connId, c) != null) {
            env.send(player, new CorePacket.TunnelClosed(connId));
            return;
        }
        pool.execute(() -> run(player, c));
    }

    private void up(P player, int connId, byte[] data) {
        Conn c = find(player, connId);
        if (c == null) return;
        if (!c.up.offer(data)) close(player, c, true);
    }

    private void end(P player, int connId) {
        Conn c = find(player, connId);
        if (c != null) c.up.offer(END);
    }

    private Conn find(P player, int connId) {
        Map<Integer, Conn> mine = conns.get(env.id(player));
        return mine == null ? null : mine.get(connId);
    }

    /** Laeuft in einem eigenen Thread: Verbindung herstellen, dann Bytes vom Spieler zur Web-Oberflaeche schreiben (Lesen macht ein zweiter Thread). */
    private void run(P player, Conn c) {
        Socket s;
        try {
            s = env.connect();
        } catch (IOException e) {
            log.fine("Tunnel: keine Verbindung zur Web-Oberflaeche: " + e.getMessage());
            close(player, c, true);
            return;
        }
        c.socket = s;
        if (c.closed) { // waehrenddessen beendet
            closeQuietly(s);
            return;
        }
        pool.execute(() -> pump(player, c, s));
        try {
            OutputStream out = s.getOutputStream();
            while (true) {
                byte[] chunk = c.up.take();
                if (chunk == END) break;
                out.write(chunk);
                out.flush();
            }
            // Der Browser hat fertig gesendet: nur die Schreibrichtung schliessen, die Antwort darf noch kommen
            try {
                s.shutdownOutput();
            } catch (IOException ignored) {
                // schon zu
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close(player, c, true);
        } catch (IOException e) {
            close(player, c, true);
        }
    }

    /** Liest die Antwort der Web-Oberflaeche und schickt sie in Stuecken an den Spieler. */
    private void pump(P player, Conn c, Socket s) {
        byte[] buf = new byte[CoreProtocol.TUNNEL_MAX_DOWN];
        try {
            InputStream in = s.getInputStream();
            int n;
            while (!c.closed && (n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                env.send(player, new CorePacket.TunnelDown(c.id, java.util.Arrays.copyOf(buf, n)));
            }
        } catch (IOException ignored) {
            // Verbindung beendet
        }
        close(player, c, true);
    }

    private void close(P player, Conn c, boolean notify) {
        if (c.closed) return;
        c.closed = true;
        Map<Integer, Conn> mine = conns.get(c.player);
        if (mine != null) mine.remove(c.id, c);
        Socket s = c.socket;
        if (s != null) closeQuietly(s);
        c.up.offer(END);
        if (notify) env.send(player, new CorePacket.TunnelClosed(c.id));
    }

    /** Der Spieler hat den Server verlassen: alle seine Verbindungen schliessen. */
    void onQuit(P player) {
        Map<Integer, Conn> mine = conns.remove(env.id(player));
        if (mine == null) return;
        for (Conn c : new ArrayList<>(mine.values())) close(player, c, false);
    }

    int connections(P player) {
        Map<Integer, Conn> mine = conns.get(env.id(player));
        return mine == null ? 0 : mine.size();
    }

    List<Integer> debugIds(P player) {
        Map<Integer, Conn> mine = conns.get(env.id(player));
        return mine == null ? List.of() : new ArrayList<>(mine.keySet());
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // egal
        }
    }
}
