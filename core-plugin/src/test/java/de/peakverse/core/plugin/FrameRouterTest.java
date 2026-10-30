package de.peakverse.core.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import de.peakverse.core.protocol.Direction;
import de.peakverse.core.protocol.Frame;
import de.peakverse.core.protocol.FrameCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FrameRouterTest {
    private record Delivery(String player, byte[] payload) {}

    private FrameRouter<String> router;

    @BeforeEach
    void setUp() {
        Logger log = Logger.getLogger("FrameRouterTest");
        log.setLevel(Level.OFF);
        router = new FrameRouter<>(log);
    }

    private static byte[] up(Frame frame) {
        return FrameCodec.encode(frame, Direction.SERVERBOUND);
    }

    @Test
    void deliversPayloadToTheRightModule() {
        List<Delivery> sound = new ArrayList<>(), texture = new ArrayList<>();
        router.setHandler("peaksound", (p, d) -> sound.add(new Delivery(p, d)));
        router.setHandler("texturepeak", (p, d) -> texture.add(new Delivery(p, d)));
        router.receive("Alice", up(new Frame.ModuleFrame("peaksound", new byte[] {1, 2, 3})));
        router.receive("Bob", up(new Frame.ModuleFrame("texturepeak", new byte[] {9})));
        assertEquals(1, sound.size());
        assertEquals("Alice", sound.get(0).player());
        assertArrayEquals(new byte[] {1, 2, 3}, sound.get(0).payload());
        assertEquals(1, texture.size());
        assertEquals("Bob", texture.get(0).player());
        assertEquals(new FrameRouter.Stats(2, 0, 0, 0, 2), router.stats());
    }

    @Test
    void countsUnknownModulesAndDropsTheirPackets() {
        router.receive("Alice", up(new Frame.ModuleFrame("nobody", new byte[] {1})));
        assertEquals(new FrameRouter.Stats(1, 0, 1, 0, 0), router.stats());
    }

    @Test
    void countsMalformedPacketsAndNeverThrows() {
        router.setHandler("peaksound", (p, d) -> {});
        byte[][] garbage = {new byte[0], {99}, {1}, {1, 9, 'a'}, {1, 2, 'A', 'B'}, new byte[Direction.SERVERBOUND.maxFrameSize() + 1]};
        for (byte[] data : garbage) assertDoesNotThrow(() -> router.receive("Mallory", data));
        assertDoesNotThrow(() -> router.receive("Mallory", null));
        assertEquals(garbage.length + 1, router.stats().received());
        assertEquals(garbage.length + 1, router.stats().malformed());
        assertEquals(0, router.stats().delivered());
    }

    @Test
    void aFailingHandlerDoesNotAffectOthers() {
        List<String> ok = new ArrayList<>();
        router.setHandler("badmodule", (p, d) -> { throw new IllegalStateException("kaputt"); });
        router.setHandler("goodmodule", (p, d) -> ok.add(p));
        assertDoesNotThrow(() -> router.receive("Alice", up(new Frame.ModuleFrame("badmodule", new byte[0]))));
        router.receive("Bob", up(new Frame.ModuleFrame("goodmodule", new byte[0])));
        assertEquals(List.of("Bob"), ok);
        assertEquals(1, router.stats().handlerErrors());
        assertEquals(1, router.stats().delivered());
    }

    @Test
    void removedHandlersStopReceiving() {
        List<String> got = new ArrayList<>();
        router.setHandler("peaksound", (p, d) -> got.add(p));
        router.receive("A", up(new Frame.ModuleFrame("peaksound", new byte[0])));
        router.removeHandler("peaksound");
        router.receive("B", up(new Frame.ModuleFrame("peaksound", new byte[0])));
        router.setHandler("peaksound", (p, d) -> got.add(p));
        router.setHandler("peaksound", null); // null entfernt den Handler
        router.receive("C", up(new Frame.ModuleFrame("peaksound", new byte[0])));
        assertEquals(List.of("A"), got);
        assertEquals(2, router.stats().unknownModule());
    }

    @Test
    void routesCoreFramesToTheCoreHandler() {
        List<String> got = new ArrayList<>();
        router.receive("A", up(new Frame.CoreFrame(new byte[] {1}))); // noch kein Handler: verworfen
        router.setCoreHandler((p, d) -> got.add(p + ":" + d.length));
        router.receive("B", up(new Frame.CoreFrame(new byte[] {1, 2})));
        assertEquals(List.of("B:2"), got);
        assertEquals(1, router.stats().unknownModule());
        assertEquals(1, router.stats().delivered());
    }

    @Test
    void clientboundPacketsAreNotAcceptedFromClients() {
        router.setHandler("peaksound", (p, d) -> {});
        byte[] tooBigForAClient = FrameCodec.encode(new Frame.ModuleFrame("peaksound", new byte[100_000]), Direction.CLIENTBOUND);
        router.receive("Mallory", tooBigForAClient);
        assertEquals(1, router.stats().malformed());
        assertEquals(0, router.stats().delivered());
    }
}
