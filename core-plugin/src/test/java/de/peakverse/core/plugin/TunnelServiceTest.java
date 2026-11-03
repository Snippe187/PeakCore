package de.peakverse.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.peakverse.core.protocol.CorePacket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TunnelServiceTest {
    private ServerSocket web;
    private Thread webThread;
    private final List<CorePacket.Clientbound> sent = new CopyOnWriteArrayList<>();
    private volatile boolean allowed = true;
    private volatile String loginPath = "/login?t=abc";
    private TunnelService<String> tunnel;

    /** Eine kleine "Web-Oberflaeche": liest eine Zeile und antwortet mit "echo:" + Zeile. */
    @BeforeEach
    void setUp() throws Exception {
        web = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        webThread = new Thread(() -> {
            while (!web.isClosed()) {
                try {
                    Socket s = web.accept();
                    Thread t = new Thread(() -> {
                        try (s) {
                            InputStream in = s.getInputStream();
                            ByteArrayOutputStream line = new ByteArrayOutputStream();
                            int b;
                            while ((b = in.read()) >= 0 && b != '\n') line.write(b);
                            OutputStream out = s.getOutputStream();
                            out.write(("echo:" + line).getBytes());
                            out.flush();
                        } catch (IOException ignored) {
                            // Test-Server
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        webThread.setDaemon(true);
        webThread.start();
        tunnel = new TunnelService<>(new TunnelService.Env<String>() {
            @Override
            public UUID id(String player) {
                return UUID.nameUUIDFromBytes(player.getBytes());
            }

            @Override
            public boolean allowed(String player) {
                return allowed;
            }

            @Override
            public void send(String player, CorePacket.Clientbound packet) {
                sent.add(packet);
            }

            @Override
            public String loginPath(String player) {
                return loginPath;
            }

            @Override
            public Socket connect() throws IOException {
                return new Socket(InetAddress.getLoopbackAddress(), web.getLocalPort());
            }
        }, Logger.getAnonymousLogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        tunnel.close();
        web.close();
    }

    private <T extends CorePacket.Clientbound> T await(Class<T> type) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            for (CorePacket.Clientbound p : sent) if (type.isInstance(p)) return type.cast(p);
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("Kein " + type.getSimpleName() + " erhalten, bekommen: " + sent);
    }

    @Test
    void forwardsBytesBothWays() throws Exception {
        tunnel.onPacket("anna", new CorePacket.TunnelOpen(1));
        tunnel.onPacket("anna", new CorePacket.TunnelUp(1, "hallo\n".getBytes()));
        CorePacket.TunnelDown down = await(CorePacket.TunnelDown.class);
        assertEquals(1, down.conn());
        assertEquals("echo:hallo", new String(down.data()));
        // die Web-Oberflaeche schliesst nach der Antwort: der Server meldet das der Mod
        assertEquals(1, await(CorePacket.TunnelClosed.class).conn());
        assertEquals(0, tunnel.connections("anna"));
    }

    @Test
    void refusesPlayersWithoutWebAccess() throws Exception {
        allowed = false;
        tunnel.onPacket("anna", new CorePacket.TunnelOpen(7));
        assertEquals(7, await(CorePacket.TunnelClosed.class).conn());
        assertEquals(0, tunnel.connections("anna"));
        sent.clear();
        tunnel.onPacket("anna", new CorePacket.TunnelLoginRequest());
        assertEquals("", await(CorePacket.TunnelLogin.class).path());
    }

    @Test
    void loginRequestGivesOneTimePath() throws Exception {
        tunnel.onPacket("anna", new CorePacket.TunnelLoginRequest());
        assertEquals("/login?t=abc", await(CorePacket.TunnelLogin.class).path());
        sent.clear();
        loginPath = null; // Web-Oberflaeche aus
        tunnel.onPacket("anna", new CorePacket.TunnelLoginRequest());
        assertEquals("", await(CorePacket.TunnelLogin.class).path());
    }

    @Test
    void duplicateIdAndTooManyConnectionsAreRefused() throws Exception {
        // eine Gegenstelle, die nie antwortet, haelt die Verbindungen offen
        ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        TunnelService<String> t = new TunnelService<>(new TunnelService.Env<String>() {
            @Override
            public UUID id(String player) {
                return UUID.nameUUIDFromBytes(player.getBytes());
            }

            @Override
            public boolean allowed(String player) {
                return true;
            }

            @Override
            public void send(String player, CorePacket.Clientbound packet) {
                sent.add(packet);
            }

            @Override
            public String loginPath(String player) {
                return null;
            }

            @Override
            public Socket connect() throws IOException {
                return new Socket(InetAddress.getLoopbackAddress(), silent.getLocalPort());
            }
        }, Logger.getAnonymousLogger());
        try {
            for (int i = 1; i <= TunnelService.MAX_CONNECTIONS; i++) t.onPacket("anna", new CorePacket.TunnelOpen(i));
            assertEquals(TunnelService.MAX_CONNECTIONS, t.connections("anna"));
            t.onPacket("anna", new CorePacket.TunnelOpen(1)); // doppelt
            t.onPacket("anna", new CorePacket.TunnelOpen(999)); // zu viele
            long closed = sent.stream().filter(p -> p instanceof CorePacket.TunnelClosed).count();
            assertEquals(2, closed);
            assertEquals(TunnelService.MAX_CONNECTIONS, t.connections("anna"));
            // anderer Spieler ist nicht betroffen
            t.onPacket("bert", new CorePacket.TunnelOpen(1));
            assertEquals(1, t.connections("bert"));
            // Austritt raeumt auf
            t.onQuit("anna");
            assertEquals(0, t.connections("anna"));
        } finally {
            t.close();
            silent.close();
        }
    }

    @Test
    void unreachableWebInterfaceClosesConnection() throws Exception {
        web.close();
        tunnel.onPacket("anna", new CorePacket.TunnelOpen(3));
        assertEquals(3, await(CorePacket.TunnelClosed.class).conn());
        assertTrue(tunnel.debugIds("anna").isEmpty());
    }

    @Test
    void dataForUnknownConnectionIsIgnored() {
        tunnel.onPacket("anna", new CorePacket.TunnelUp(42, new byte[] {1}));
        tunnel.onPacket("anna", new CorePacket.TunnelEnd(42));
        assertTrue(sent.isEmpty());
    }
}
