package de.peakverse.core.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.peakverse.core.protocol.CorePacket;
import de.peakverse.core.protocol.CorePacketCodec;
import de.peakverse.core.protocol.CoreProtocol;
import de.peakverse.core.protocol.Direction;
import de.peakverse.core.protocol.Frame;
import de.peakverse.core.protocol.FrameCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientSessionTest {
    /** Testverbindung: merkt sich alle gesendeten Pakete. */
    private static final class FakeTransport implements ClientSession.Transport {
        boolean canSend = true;
        final List<byte[]> sent = new ArrayList<>();

        @Override public boolean canSend() { return canSend; }
        @Override public void send(byte[] frame) { sent.add(frame); }

        List<CorePacket.Hello> hellos() {
            List<CorePacket.Hello> out = new ArrayList<>();
            for (byte[] data : sent) {
                Frame f = FrameCodec.decode(data, Direction.SERVERBOUND);
                if (f instanceof Frame.CoreFrame c) out.add((CorePacket.Hello) CorePacketCodec.decodeServerbound(c.payload()));
            }
            return out;
        }

        List<Frame.ModuleFrame> moduleFrames() {
            List<Frame.ModuleFrame> out = new ArrayList<>();
            for (byte[] data : sent) if (FrameCodec.decode(data, Direction.SERVERBOUND) instanceof Frame.ModuleFrame m) out.add(m);
            return out;
        }
    }

    private FakeTransport transport;
    private long now;
    private ClientSession session;
    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        now = 10_000L;
        session = new ClientSession("0.1.0", transport, () -> now);
        session.addListener(new ClientSession.Listener() {
            @Override public void onStateChanged(ClientSession.State previous, ClientSession.State next) { events.add(previous + ">" + next); }
            @Override public void onAdminChanged(boolean admin) { events.add("admin=" + admin); }
        });
    }

    private static byte[] coreFrame(CorePacket.Clientbound packet) {
        return FrameCodec.encode(new Frame.CoreFrame(CorePacketCodec.encode(packet)), Direction.CLIENTBOUND);
    }

    private static byte[] ackFrame(boolean admin, long serverTime, CorePacket.ModuleEntry... modules) {
        return coreFrame(new CorePacket.HelloAck(CoreProtocol.VERSION, true, admin, "0.1.0", 1, serverTime, List.of(modules)));
    }

    private static byte[] moduleFrame(String id, byte... payload) {
        return FrameCodec.encode(new Frame.ModuleFrame(id, payload), Direction.CLIENTBOUND);
    }

    @Test
    void joinSendsAHelloWithTheRegisteredModules() {
        session.registerModule("peaksound", "0.1.0", p -> {});
        session.registerModule("texturepeak", "2.0", p -> {});
        session.onJoin();
        assertEquals(ClientSession.State.WAITING, session.state());
        CorePacket.Hello hello = transport.hellos().get(0);
        assertEquals(CoreProtocol.VERSION, hello.protocol());
        assertEquals("0.1.0", hello.coreVersion());
        assertEquals(List.of(new CorePacket.ModuleVersion("peaksound", "0.1.0"), new CorePacket.ModuleVersion("texturepeak", "2.0")), hello.modules());
    }

    @Test
    void aServerWithoutTheChannelKeepsTheModQuiet() {
        transport.canSend = false;
        session.onJoin();
        assertEquals(ClientSession.State.WAITING, session.state(), "der Server meldet den Kanal erst kurz nach dem Beitritt");
        assertTrue(transport.sent.isEmpty());
        now += ClientSession.CHANNEL_WAIT_MS - 1;
        session.tick();
        assertEquals(ClientSession.State.WAITING, session.state());
        now += 1;
        session.tick();
        assertEquals(ClientSession.State.NO_SERVER_SUPPORT, session.state());
        now += 60_000;
        session.tick();
        assertTrue(transport.sent.isEmpty(), "ohne Kanal wird nie etwas gesendet");
        assertFalse(session.isActive());
    }

    @Test
    void theHelloGoesOutAsSoonAsTheServerReportsTheChannel() {
        transport.canSend = false;
        session.registerModule("peaksound", "0.1.0", p -> {});
        session.onJoin();
        now += 50;
        session.tick();
        assertTrue(transport.sent.isEmpty());
        transport.canSend = true; // der Server hat seine Kanaele gemeldet
        now += 50;
        session.tick();
        assertEquals(1, transport.hellos().size());
        session.onFrame(ackFrame(false, 1L));
        assertTrue(session.isActive());
    }

    @Test
    void aRetryWaitsIfTheChannelDisappears() {
        session.onJoin();
        transport.canSend = false;
        now += 2_000;
        session.tick();
        assertEquals(1, transport.hellos().size(), "ohne Kanal kein weiterer Versuch");
        transport.canSend = true;
        session.tick();
        assertEquals(2, transport.hellos().size());
    }

    @Test
    void theAckActivatesTheSession() {
        session.onJoin();
        now += 100;
        session.onFrame(ackFrame(true, 50_000L, new CorePacket.ModuleEntry("peaksound", "PeakSound", "0.1.0")));
        assertEquals(ClientSession.State.ACTIVE, session.state());
        assertTrue(session.isActive());
        assertTrue(session.isAdmin());
        assertEquals("0.1.0", session.serverCoreVersion());
        assertEquals(1, session.serverApiVersion());
        assertTrue(session.serverHasModule("peaksound"));
        assertFalse(session.serverHasModule("texturepeak"));
        assertEquals(List.of("OFFLINE>WAITING", "WAITING>ACTIVE"), events);
        // Uhr: Serverzeit 50 000 entspricht der Mitte zwischen Senden (10 000) und Empfang (10 100)
        assertEquals(50_000L - 10_050L, session.serverNow() - now);
    }

    @Test
    void retriesTheHelloEveryTwoSecondsUpToTenTimesThenStops() {
        session.onJoin();
        assertEquals(1, transport.hellos().size());
        now += 1_999;
        session.tick();
        assertEquals(1, transport.hellos().size(), "noch nicht Zeit");
        now += 1;
        session.tick();
        assertEquals(2, transport.hellos().size());
        for (int i = 0; i < 20; i++) {
            now += 2_000;
            session.tick();
        }
        assertEquals(ClientSession.MAX_ATTEMPTS, transport.hellos().size());
        assertEquals(ClientSession.State.WAITING, session.state());
    }

    @Test
    void noRetriesAfterTheAck() {
        session.onJoin();
        session.onFrame(ackFrame(false, 1L));
        now += 10_000;
        session.tick();
        assertEquals(1, transport.hellos().size());
    }

    @Test
    void incompatibleServerLeavesTheModInactive() {
        List<byte[]> received = new ArrayList<>();
        session.registerModule("peaksound", "0.1.0", received::add);
        session.onJoin();
        session.onFrame(coreFrame(new CorePacket.HelloAck(CoreProtocol.VERSION + 1, false, false, "", 0, 0, List.of())));
        assertEquals(ClientSession.State.INCOMPATIBLE, session.state());
        assertFalse(session.isActive());
        assertFalse(session.isAdmin());
        session.onFrame(moduleFrame("peaksound", (byte) 1));
        assertTrue(received.isEmpty());
        assertFalse(session.registerModule("other", "1", p -> {}).send(new byte[0]));
        now += 20_000;
        session.tick();
        assertEquals(1, transport.hellos().size(), "keine weiteren Hello-Versuche");
    }

    @Test
    void modulePacketsAreOnlyDeliveredAfterTheHandshake() {
        List<byte[]> received = new ArrayList<>();
        session.registerModule("peaksound", "0.1.0", received::add);
        session.onJoin();
        session.onFrame(moduleFrame("peaksound", (byte) 1)); // zu frueh
        assertTrue(received.isEmpty());
        session.onFrame(ackFrame(false, 1L));
        session.onFrame(moduleFrame("peaksound", (byte) 7, (byte) 8));
        assertEquals(1, received.size());
        assertArrayEquals(new byte[] {7, 8}, received.get(0));
        session.onFrame(moduleFrame("unknown")); // unbekanntes Modul: verworfen
        assertEquals(1, received.size());
    }

    @Test
    void aFailingHandlerDoesNotBreakTheSession() {
        List<String> ok = new ArrayList<>();
        session.registerModule("badmodule", "1", p -> { throw new IllegalStateException("kaputt"); });
        session.registerModule("goodmodule", "1", p -> ok.add("ok"));
        session.onJoin();
        session.onFrame(ackFrame(false, 1L));
        assertDoesNotThrow(() -> session.onFrame(moduleFrame("badmodule")));
        session.onFrame(moduleFrame("goodmodule"));
        assertEquals(List.of("ok"), ok);
        assertTrue(session.debug().contains("1 Handler-Fehler"), session.debug());
    }

    @Test
    void sendingNeedsAnActiveHandshakeAndRespectsTheSizeLimit() {
        ClientSession.ModuleChannel channel = session.registerModule("peaksound", "0.1.0", p -> {});
        assertFalse(channel.send(new byte[] {1}), "vor dem Beitritt");
        session.onJoin();
        assertFalse(channel.send(new byte[] {1}), "vor der Antwort");
        session.onFrame(ackFrame(false, 1L));
        assertTrue(channel.send(new byte[] {1, 2, 3}));
        assertEquals(1, transport.moduleFrames().size());
        assertEquals("peaksound", transport.moduleFrames().get(0).moduleId());
        assertArrayEquals(new byte[] {1, 2, 3}, transport.moduleFrames().get(0).payload());
        assertTrue(channel.send(new byte[channel.maxPayload()]));
        assertThrows(IllegalArgumentException.class, () -> channel.send(new byte[channel.maxPayload() + 1]));
    }

    @Test
    void adminStateUpdatesOnlyWhileActiveAndNotifiesOnChange() {
        session.onJoin();
        session.onFrame(coreFrame(new CorePacket.AdminState(true))); // zu frueh
        assertFalse(session.isAdmin());
        session.onFrame(ackFrame(false, 1L));
        events.clear();
        session.onFrame(coreFrame(new CorePacket.AdminState(true)));
        session.onFrame(coreFrame(new CorePacket.AdminState(true))); // keine Aenderung, keine Meldung
        assertTrue(session.isAdmin());
        session.onFrame(coreFrame(new CorePacket.AdminState(false)));
        assertFalse(session.isAdmin());
        assertEquals(List.of("admin=true", "admin=false"), events);
    }

    @Test
    void disconnectResetsEverything() {
        ClientSession.ModuleChannel channel = session.registerModule("peaksound", "0.1.0", p -> {});
        session.onJoin();
        session.onFrame(ackFrame(true, 1L, new CorePacket.ModuleEntry("peaksound", "PeakSound", "0.1.0")));
        session.onDisconnect();
        assertEquals(ClientSession.State.OFFLINE, session.state());
        assertFalse(session.isAdmin());
        assertTrue(session.serverModules().isEmpty());
        assertEquals("", session.serverCoreVersion());
        assertFalse(channel.send(new byte[0]));
        // neue Verbindung beginnt wieder bei null Versuchen
        session.onJoin();
        assertEquals(ClientSession.State.WAITING, session.state());
        assertEquals(2, transport.hellos().size());
    }

    @Test
    void lateRegistrationDuringAConnectionSendsAFreshHello() {
        session.registerModule("peaksound", "0.1.0", p -> {});
        session.onJoin();
        session.onFrame(ackFrame(false, 1L));
        session.registerModule("texturepeak", "1", p -> {});
        assertEquals(ClientSession.State.WAITING, session.state(), "das neue Hello wird erneut beantwortet");
        List<CorePacket.Hello> hellos = transport.hellos();
        assertEquals(2, hellos.size());
        assertEquals(2, hellos.get(1).modules().size());
        session.onFrame(ackFrame(false, 2L));
        assertTrue(session.isActive());
    }

    @Test
    void unregisteringAModuleRemovesItFromTheNextHello() {
        ClientSession.ModuleChannel a = session.registerModule("alpha", "1", p -> {});
        session.registerModule("beta", "1", p -> {});
        session.onJoin();
        a.unregister();
        assertEquals(List.of("beta"), session.moduleIds());
        assertEquals(List.of(new CorePacket.ModuleVersion("beta", "1")), transport.hellos().get(1).modules());
        a.unregister(); // zweites Abmelden ist harmlos
    }

    @Test
    void registrationRejectsInvalidAndDuplicateModules() {
        session.registerModule("peaksound", "0.1.0", p -> {});
        assertThrows(IllegalArgumentException.class, () -> session.registerModule("peaksound", "0.1.0", p -> {}));
        assertThrows(IllegalArgumentException.class, () -> session.registerModule("Bad", "1", p -> {}));
        assertThrows(IllegalArgumentException.class, () -> session.registerModule("fine", "", p -> {}));
        assertThrows(IllegalArgumentException.class, () -> session.registerModule("fine", "1", null));
        for (int i = 1; i < CoreProtocol.MAX_MODULES; i++) session.registerModule("module" + i, "1", p -> {});
        assertThrows(IllegalArgumentException.class, () -> session.registerModule("onemore", "1", p -> {}));
    }

    @Test
    void unexpectedAcksAreIgnored() {
        session.onFrame(ackFrame(true, 1L)); // nicht verbunden
        assertEquals(ClientSession.State.OFFLINE, session.state());
        assertFalse(session.isAdmin());
    }

    @Test
    void garbageNeverBreaksTheSession() {
        session.registerModule("peaksound", "0.1.0", p -> {});
        session.onJoin();
        session.onFrame(ackFrame(false, 1L));
        Random random = new Random(3);
        for (int i = 0; i < 20_000; i++) {
            byte[] data = new byte[random.nextInt(60)];
            random.nextBytes(data);
            if (data.length > 0 && random.nextBoolean()) data[0] = (byte) (1 + random.nextInt(2));
            final byte[] frame = data;
            assertDoesNotThrow(() -> session.onFrame(frame));
        }
        assertDoesNotThrow(() -> session.onFrame(null));
        assertDoesNotThrow(() -> session.onFrame(new byte[0]));
    }

    @Test
    void debugDescribesTheState() {
        session.registerModule("peaksound", "0.1.0", p -> {});
        session.onJoin();
        session.onFrame(ackFrame(true, 1L, new CorePacket.ModuleEntry("peaksound", "PeakSound", "0.1.0")));
        String debug = session.debug();
        assertTrue(debug.contains("ACTIVE") && debug.contains("Admin true") && debug.contains("peaksound 0.1.0") && debug.contains("Hello 1x gesendet"), debug);
    }
}
