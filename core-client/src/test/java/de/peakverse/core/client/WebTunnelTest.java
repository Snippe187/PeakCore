package de.peakverse.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.peakverse.core.protocol.CorePacket;
import de.peakverse.core.protocol.CorePacketCodec;
import de.peakverse.core.protocol.CoreProtocol;
import de.peakverse.core.protocol.Direction;
import de.peakverse.core.protocol.Frame;
import de.peakverse.core.protocol.FrameCodec;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebTunnelTest {
    private final List<CorePacket.Serverbound> toServer = new CopyOnWriteArrayList<>();
    private final List<String> links = new CopyOnWriteArrayList<>();
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private final AtomicLong now = new AtomicLong(1000);
    private ClientSession session;
    private WebTunnel tunnel;

    @BeforeEach
    void setUp() {
        session = new ClientSession("0.1.4", new ClientSession.Transport() {
            @Override
            public boolean canSend() {
                return true;
            }

            @Override
            public void send(byte[] frame) {
                Frame f = FrameCodec.decode(frame, Direction.SERVERBOUND);
                if (f instanceof Frame.CoreFrame c) {
                    CorePacket.Serverbound p = CorePacketCodec.decodeServerbound(c.payload());
                    if (!(p instanceof CorePacket.Hello)) toServer.add(p);
                }
            }
        }, now::get);
        tunnel = new WebTunnel(session, Runnable::run, now::get, links::add, failures::add);
        session.addListener(tunnel);
        session.onJoin();
        session.tick();
        fromServer(new CorePacket.HelloAck(CoreProtocol.VERSION, true, true, "0.1.4", 1, now.get(), List.of()));
    }

    @AfterEach
    void tearDown() {
        tunnel.close();
    }

    private void fromServer(CorePacket.Clientbound packet) {
        session.onFrame(FrameCodec.encode(new Frame.CoreFrame(CorePacketCodec.encode(packet)), Direction.CLIENTBOUND));
    }

    private <T extends CorePacket.Serverbound> T await(Class<T> type) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            for (CorePacket.Serverbound p : toServer) if (type.isInstance(p)) return type.cast(p);
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("Kein " + type.getSimpleName() + ", bekommen: " + toServer);
    }

    @Test
    void openAsksServerAndBuildsLocalLink() throws Exception {
        tunnel.open();
        assertTrue(tunnel.port() > 0);
        await(CorePacket.TunnelLoginRequest.class);
        fromServer(new CorePacket.TunnelLogin("/login?t=xyz"));
        assertEquals(List.of("http://127.0.0.1:" + tunnel.port() + "/login?t=xyz"), links);
        assertTrue(failures.isEmpty());
    }

    @Test
    void emptyLoginAnswerIsAFailure() {
        tunnel.open();
        fromServer(new CorePacket.TunnelLogin(""));
        assertTrue(links.isEmpty());
        assertEquals(1, failures.size());
    }

    @Test
    void silentServerTimesOut() {
        tunnel.open();
        tunnel.tick();
        assertTrue(failures.isEmpty());
        now.addAndGet(WebTunnel.LOGIN_TIMEOUT_MS + 1);
        tunnel.tick();
        assertEquals(1, failures.size());
        tunnel.tick();
        assertEquals(1, failures.size(), "nur einmal melden");
        // eine spaete Antwort ohne Anfrage wird ignoriert
        fromServer(new CorePacket.TunnelLogin("/login?t=late"));
        assertTrue(links.isEmpty());
    }

    @Test
    void browserBytesGoUpAndAnswersComeBack() throws Exception {
        tunnel.open();
        try (Socket browser = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), tunnel.port())) {
            CorePacket.TunnelOpen open = await(CorePacket.TunnelOpen.class);
            OutputStream out = browser.getOutputStream();
            out.write("GET / HTTP/1.1\r\n\r\n".getBytes());
            out.flush();
            CorePacket.TunnelUp up = await(CorePacket.TunnelUp.class);
            assertEquals(open.conn(), up.conn());
            assertEquals("GET / HTTP/1.1\r\n\r\n", new String(up.data()));

            fromServer(new CorePacket.TunnelDown(open.conn(), "HTTP/1.1 200 OK\r\n\r\n".getBytes()));
            InputStream in = browser.getInputStream();
            byte[] buf = new byte[19];
            int read = 0;
            browser.setSoTimeout(5000);
            while (read < buf.length) read += in.read(buf, read, buf.length - read);
            assertEquals("HTTP/1.1 200 OK\r\n\r\n", new String(buf));

            // Der Server schliesst: der Browser sieht das Ende
            fromServer(new CorePacket.TunnelClosed(open.conn()));
            assertEquals(-1, in.read());
        }
    }

    @Test
    void browserClosingTellsServer() throws Exception {
        tunnel.open();
        CorePacket.TunnelOpen open;
        try (Socket browser = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), tunnel.port())) {
            open = await(CorePacket.TunnelOpen.class);
        }
        assertEquals(open.conn(), await(CorePacket.TunnelEnd.class).conn());
    }

    @Test
    void connectionsGetDistinctIds() throws Exception {
        tunnel.open();
        try (Socket a = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), tunnel.port());
             Socket b = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), tunnel.port())) {
            long end = System.currentTimeMillis() + 5000;
            while (toServer.stream().filter(p -> p instanceof CorePacket.TunnelOpen).count() < 2 && System.currentTimeMillis() < end) TimeUnit.MILLISECONDS.sleep(10);
            List<Integer> ids = toServer.stream().filter(p -> p instanceof CorePacket.TunnelOpen).map(p -> ((CorePacket.TunnelOpen) p).conn()).toList();
            assertEquals(2, ids.size());
            assertNotEquals(ids.get(0), ids.get(1));
        }
    }

    @Test
    void disconnectStopsProxy() {
        tunnel.open();
        assertTrue(tunnel.port() > 0);
        session.onDisconnect();
        assertEquals(0, tunnel.port());
    }

    @Test
    void openWithoutServerReportsFailure() {
        session.onDisconnect();
        tunnel.open();
        assertEquals(1, failures.size());
        assertEquals(0, tunnel.port());
    }
}
