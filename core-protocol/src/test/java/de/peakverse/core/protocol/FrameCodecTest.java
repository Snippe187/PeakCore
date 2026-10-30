package de.peakverse.core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FrameCodecTest {
    private static final Direction UP = Direction.SERVERBOUND;
    private static final Direction DOWN = Direction.CLIENTBOUND;

    @Test
    void moduleFrameRoundTrips() {
        byte[] payload = {1, 2, 3, (byte) 0xFF, 0};
        Frame.ModuleFrame in = new Frame.ModuleFrame("peaksound", payload);
        byte[] wire = FrameCodec.encode(in, DOWN);
        assertEquals(Frame.ModuleFrame.class, FrameCodec.decode(wire, DOWN).getClass());
        assertEquals(in, FrameCodec.decode(wire, DOWN));
    }

    @Test
    void wireFormatIsExactlyAsDocumented() {
        byte[] wire = FrameCodec.encode(new Frame.ModuleFrame("ab", new byte[] {9, 8}), UP);
        assertArrayEquals(new byte[] {1, 2, 'a', 'b', 9, 8}, wire);
        assertArrayEquals(new byte[] {2, 7}, FrameCodec.encode(new Frame.CoreFrame(new byte[] {7}), UP));
    }

    @Test
    void coreFrameRoundTrips() {
        Frame.CoreFrame in = new Frame.CoreFrame(new byte[] {5, 6, 7});
        assertEquals(in, FrameCodec.decode(FrameCodec.encode(in, UP), UP));
    }

    @Test
    void emptyPayloadsAreAllowed() {
        Frame.ModuleFrame m = new Frame.ModuleFrame("peaksound", new byte[0]);
        assertEquals(m, FrameCodec.decode(FrameCodec.encode(m, UP), UP));
        Frame.CoreFrame c = new Frame.CoreFrame(new byte[0]);
        assertEquals(c, FrameCodec.decode(FrameCodec.encode(c, UP), UP));
    }

    @Test
    void payloadLimitsAreExactForBothDirections() {
        for (Direction d : Direction.values()) {
            for (String id : new String[] {"ab", "peaksound", "a".repeat(24)}) {
                int max = FrameCodec.maxModulePayload(id, d);
                byte[] ok = FrameCodec.encode(new Frame.ModuleFrame(id, new byte[max]), d);
                assertEquals(d.maxFrameSize(), ok.length, "ein Paket mit maximaler Nutzlast fuellt die Grenze genau");
                assertEquals(max, ((Frame.ModuleFrame) FrameCodec.decode(ok, d)).payload().length);
                assertThrows(IllegalArgumentException.class, () -> FrameCodec.encode(new Frame.ModuleFrame(id, new byte[max + 1]), d));
            }
            int coreMax = FrameCodec.maxCorePayload(d);
            assertEquals(d.maxFrameSize(), FrameCodec.encode(new Frame.CoreFrame(new byte[coreMax]), d).length);
            assertThrows(IllegalArgumentException.class, () -> FrameCodec.encode(new Frame.CoreFrame(new byte[coreMax + 1]), d));
        }
    }

    @Test
    void serverboundIsMuchSmallerThanClientbound() {
        assertTrue(UP.maxFrameSize() < DOWN.maxFrameSize());
        byte[] big = FrameCodec.encode(new Frame.ModuleFrame("peaksound", new byte[100_000]), DOWN);
        assertThrows(MalformedFrameException.class, () -> FrameCodec.decode(big, UP), "ein zu grosses Paket vom Client wird verworfen");
    }

    @Test
    void rejectsEmptyOversizedAndNullInput() {
        assertThrows(MalformedFrameException.class, () -> FrameCodec.decode(new byte[0], UP));
        assertThrows(MalformedFrameException.class, () -> FrameCodec.decode(null, UP));
        byte[] tooBig = new byte[UP.maxFrameSize() + 1];
        tooBig[0] = FrameCodec.TYPE_CORE;
        assertThrows(MalformedFrameException.class, () -> FrameCodec.decode(tooBig, UP));
    }

    @Test
    void rejectsUnknownTypes() {
        for (int type : new int[] {0, 3, 4, 127, 128, 255}) {
            assertThrows(MalformedFrameException.class, () -> FrameCodec.decode(new byte[] {(byte) type, 1, 2, 3}, UP), "Typ " + type);
        }
    }

    @Test
    void rejectsBrokenModuleHeaders() {
        assertThrows(MalformedFrameException.class, () -> FrameCodec.decode(new byte[] {1}, UP), "ohne Laengenbyte");
        assertThrows(MalformedFrameException.class, () -> FrameCodec.decode(new byte[] {1, 0}, UP), "Laenge 0");
        assertThrows(MalformedFrameException.class, () -> FrameCodec.decode(new byte[] {1, 1, 'a'}, UP), "Laenge 1 ist zu kurz");
        assertThrows(MalformedFrameException.class, () -> FrameCodec.decode(new byte[] {1, 25}, UP), "Laenge ueber 24");
        assertThrows(MalformedFrameException.class, () -> FrameCodec.decode(new byte[] {1, 5, 'a', 'b'}, UP), "ID ragt ueber das Paketende");
        assertThrows(MalformedFrameException.class, () -> FrameCodec.decode(new byte[] {1, (byte) 200, 'a', 'b'}, UP), "Laenge als vorzeichenloses Byte");
    }

    @Test
    void rejectsInvalidModuleIdsOnTheWire() {
        for (String id : new String[] {"Ab", "1ab", "a-b", "a b", "ab\u0000", "ab/..", "AB"}) {
            byte[] idBytes = id.getBytes(StandardCharsets.ISO_8859_1);
            byte[] wire = new byte[2 + idBytes.length];
            wire[0] = 1;
            wire[1] = (byte) idBytes.length;
            System.arraycopy(idBytes, 0, wire, 2, idBytes.length);
            assertThrows(MalformedFrameException.class, () -> FrameCodec.decode(wire, UP), "ID: " + id);
        }
        // Nicht-ASCII-Bytes duerfen nicht als gueltige ID durchgehen
        assertThrows(MalformedFrameException.class, () -> FrameCodec.decode(new byte[] {1, 2, (byte) 0xC3, (byte) 0xA4}, UP));
    }

    @Test
    void framesCannotBeBuiltWithInvalidData() {
        assertThrows(IllegalArgumentException.class, () -> new Frame.ModuleFrame("Bad", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Frame.ModuleFrame(null, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Frame.ModuleFrame("peaksound", null));
        assertThrows(IllegalArgumentException.class, () -> new Frame.CoreFrame(null));
    }

    @Test
    void moduleIdRules() {
        assertTrue(ModuleIds.isValid("ab"));
        assertTrue(ModuleIds.isValid("peak_sound9"));
        assertTrue(ModuleIds.isValid("a".repeat(24)));
        assertFalse(ModuleIds.isValid("a"));
        assertFalse(ModuleIds.isValid("a".repeat(25)));
        assertFalse(ModuleIds.isValid("9ab"));
        assertFalse(ModuleIds.isValid("_ab"));
        assertFalse(ModuleIds.isValid("Ab"));
        assertFalse(ModuleIds.isValid(null));
    }

    @Test
    void decodedPayloadIsACopy() {
        byte[] wire = {1, 2, 'a', 'b', 1, 2, 3};
        Frame.ModuleFrame frame = (Frame.ModuleFrame) FrameCodec.decode(wire, UP);
        wire[4] = 99;
        assertEquals(1, frame.payload()[0], "spaetere Aenderungen am Eingabe-Array duerfen den Frame nicht veraendern");
    }

    /** Zufaellige Bytes duerfen nie etwas anderes als einen Frame oder MalformedFrameException ergeben. */
    @Test
    void randomGarbageNeverCrashesTheDecoder() {
        Random random = new Random(42);
        int decoded = 0, rejected = 0;
        for (int i = 0; i < 20_000; i++) {
            byte[] data = new byte[random.nextInt(60)];
            random.nextBytes(data);
            if (data.length > 0 && random.nextInt(3) == 0) data[0] = (byte) (1 + random.nextInt(2)); // oefter gueltige Typen treffen
            try {
                FrameCodec.decode(data, random.nextBoolean() ? UP : DOWN);
                decoded++;
            } catch (MalformedFrameException e) {
                rejected++;
            }
        }
        assertTrue(decoded > 0 && rejected > 0, "decoded=" + decoded + " rejected=" + rejected);
    }
}
