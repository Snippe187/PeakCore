package de.peakverse.core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CorePacketCodecTest {
    private static CorePacket.Hello hello() {
        return new CorePacket.Hello(CoreProtocol.VERSION, "0.1.0",
                List.of(new CorePacket.ModuleVersion("peaksound", "0.1.0"), new CorePacket.ModuleVersion("texturepeak", "1.2.3-beta")));
    }

    private static CorePacket.HelloAck ack() {
        return new CorePacket.HelloAck(CoreProtocol.VERSION, true, true, "0.1.0", 1, 1_700_000_000_123L,
                List.of(new CorePacket.ModuleEntry("peaksound", "PeakSound", "0.1.0"), new CorePacket.ModuleEntry("texturepeak", "TexturePeak", "1.0")));
    }

    @Test
    void helloRoundTrips() {
        CorePacket.Hello in = hello();
        CorePacket.Serverbound out = CorePacketCodec.decodeServerbound(CorePacketCodec.encode(in));
        assertEquals(in, out);
        assertTrue(((CorePacket.Hello) out).compatible());
    }

    @Test
    void helloWithoutModulesRoundTrips() {
        CorePacket.Hello in = new CorePacket.Hello(CoreProtocol.VERSION, "0.1.0", List.of());
        assertEquals(in, CorePacketCodec.decodeServerbound(CorePacketCodec.encode(in)));
    }

    @Test
    void helloAckRoundTrips() {
        CorePacket.HelloAck in = ack();
        assertEquals(in, CorePacketCodec.decodeClientbound(CorePacketCodec.encode(in)));
    }

    @Test
    void adminStateRoundTrips() {
        for (boolean admin : new boolean[] {true, false}) {
            assertEquals(new CorePacket.AdminState(admin), CorePacketCodec.decodeClientbound(CorePacketCodec.encode(new CorePacket.AdminState(admin))));
        }
    }

    @Test
    void packOfferRoundTripsAndRejectsBadValues() {
        CorePacket.PackOffer offer = new CorePacket.PackOffer("http://localhost:8123/pack/x.zip", "ab".repeat(20), 12345);
        assertEquals(offer, CorePacketCodec.decodeClientbound(CorePacketCodec.encode(offer)));
        assertThrows(IllegalArgumentException.class, () -> new CorePacket.PackOffer("http://x", "XY", 1));
        assertThrows(IllegalArgumentException.class, () -> new CorePacket.PackOffer("http://x", "ab".repeat(20), 0));
        assertThrows(IllegalArgumentException.class, () -> new CorePacket.PackOffer("", "ab".repeat(20), 1));
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeServerbound(CorePacketCodec.encode(offer)), "nur vom Server");
        byte[] badSha = CorePacketCodec.encode(offer);
        badSha[badSha.length - 8 - 40] = 'Z';
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeClientbound(badSha));
    }

    @Test
    void wireFormatOfSmallPacketsIsExact() {
        assertArrayEquals(new byte[] {3, 1}, CorePacketCodec.encode(new CorePacket.AdminState(true)));
        assertArrayEquals(new byte[] {3, 0}, CorePacketCodec.encode(new CorePacket.AdminState(false)));
        // Hello ohne Module: Typ 1, Protokoll 1 (4 Bytes), Version "1" (Laenge 2 Bytes + Zeichen), 0 Module
        assertArrayEquals(new byte[] {1, 0, 0, 0, 1, 0, 1, '1', 0},
                CorePacketCodec.encode(new CorePacket.Hello(CoreProtocol.VERSION, "1", List.of())));
    }

    @Test
    void foreignProtocolVersionIsReportedWithoutReadingTheRest() {
        // beliebiger Rest nach der Protokollversion darf nicht zum Fehler werden: das Format der fremden Version ist unbekannt
        byte[] foreign = {1, 0, 0, 0, 9, (byte) 0xFF, (byte) 0xFF, 1, 2, 3};
        CorePacket.Hello hello = (CorePacket.Hello) CorePacketCodec.decodeServerbound(foreign);
        assertEquals(9, hello.protocol());
        assertFalse(hello.compatible());
        assertTrue(hello.modules().isEmpty());

        byte[] foreignAck = {2, 0, 0, 0, 7, 5, 5, 5};
        CorePacket.HelloAck ack = (CorePacket.HelloAck) CorePacketCodec.decodeClientbound(foreignAck);
        assertEquals(7, ack.protocol());
        assertFalse(ack.compatible());
        assertFalse(ack.admin());
    }

    @Test
    void rejectsWrongDirection() {
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeServerbound(CorePacketCodec.encode(ack())), "HelloAck darf nicht vom Client kommen");
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeServerbound(CorePacketCodec.encode(new CorePacket.AdminState(true))));
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeClientbound(CorePacketCodec.encode(hello())), "Hello darf nicht vom Server kommen");
    }

    @Test
    void rejectsEmptyUnknownAndTruncatedPackets() {
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeServerbound(new byte[0]));
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeServerbound(null));
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeServerbound(new byte[] {99}));
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeClientbound(new byte[] {99}));
        byte[] full = CorePacketCodec.encode(hello());
        for (int cut = 1; cut < full.length; cut++) {
            byte[] truncated = Arrays.copyOf(full, cut);
            // Abschnitte, die nach der Protokollversion enden, sind ein gueltiges Hello nur dann nicht, wenn Felder fehlen
            int len = cut;
            assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeServerbound(truncated), "abgeschnitten bei " + len);
        }
        byte[] fullAck = CorePacketCodec.encode(ack());
        for (int cut = 1; cut < fullAck.length; cut++) {
            byte[] truncated = Arrays.copyOf(fullAck, cut);
            int len = cut;
            assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeClientbound(truncated), "Ack abgeschnitten bei " + len);
        }
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] hello = CorePacketCodec.encode(hello());
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeServerbound(Arrays.copyOf(hello, hello.length + 1)));
        byte[] ack = CorePacketCodec.encode(ack());
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeClientbound(Arrays.copyOf(ack, ack.length + 1)));
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeClientbound(new byte[] {3, 1, 0}));
    }

    @Test
    void rejectsInvalidBooleans() {
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeClientbound(new byte[] {3, 2}));
        byte[] ack = CorePacketCodec.encode(ack());
        ack[5] = 7; // "compatible"
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeClientbound(ack));
    }

    @Test
    void rejectsTooManyModulesOnTheWire() {
        List<CorePacket.ModuleVersion> many = new ArrayList<>();
        for (int i = 0; i < CoreProtocol.MAX_MODULES; i++) many.add(new CorePacket.ModuleVersion("module" + i, "1"));
        byte[] ok = CorePacketCodec.encode(new CorePacket.Hello(CoreProtocol.VERSION, "1", many));
        assertEquals(CoreProtocol.MAX_MODULES, ((CorePacket.Hello) CorePacketCodec.decodeServerbound(ok)).modules().size());
        many.add(new CorePacket.ModuleVersion("zuviel", "1"));
        assertThrows(IllegalArgumentException.class, () -> CorePacketCodec.encode(new CorePacket.Hello(CoreProtocol.VERSION, "1", many)));
        // manipuliertes Paket mit Anzahl 33: Zaehler steht nach Typ(1) + Protokoll(4) + Version "1"(3)
        byte[] forged = Arrays.copyOf(ok, ok.length);
        forged[1 + 4 + 3] = (byte) (CoreProtocol.MAX_MODULES + 1);
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeServerbound(forged));
    }

    @Test
    void rejectsInvalidModuleIdsAndStringsOnTheWire() {
        byte[] good = CorePacketCodec.encode(new CorePacket.Hello(CoreProtocol.VERSION, "1", List.of(new CorePacket.ModuleVersion("abc", "1"))));
        // Modul-ID "abc" steht nach Typ(1)+Protokoll(4)+"1"(3)+Anzahl(1)+Laenge(2) ab Index 11
        byte[] upper = good.clone();
        upper[11] = 'A';
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeServerbound(upper));
        byte[] huge = good.clone();
        huge[6] = (byte) 0xFF; // Laenge der Core-Version ueber alle Grenzen
        huge[7] = (byte) 0xFF;
        assertThrows(MalformedFrameException.class, () -> CorePacketCodec.decodeServerbound(huge));
    }

    @Test
    void recordsRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new CorePacket.ModuleVersion("Bad", "1"));
        assertThrows(IllegalArgumentException.class, () -> new CorePacket.ModuleVersion("good", ""));
        assertThrows(IllegalArgumentException.class, () -> new CorePacket.ModuleVersion("good", "v".repeat(33)));
        assertThrows(IllegalArgumentException.class, () -> new CorePacket.ModuleEntry("good", "", "1"));
        assertThrows(IllegalArgumentException.class, () -> new CorePacket.ModuleEntry("good", "N".repeat(41), "1"));
    }

    @Test
    void packetsFitInsideAFrameInBothDirections() {
        List<CorePacket.ModuleVersion> many = new ArrayList<>();
        for (int i = 0; i < CoreProtocol.MAX_MODULES; i++) many.add(new CorePacket.ModuleVersion("a".repeat(24), "v".repeat(32)));
        byte[] wire = FrameCodec.encode(new Frame.CoreFrame(CorePacketCodec.encode(new CorePacket.Hello(CoreProtocol.VERSION, "v".repeat(32), many))), Direction.SERVERBOUND);
        assertTrue(wire.length < Direction.SERVERBOUND.maxFrameSize(), "das groesstmoegliche Hello passt in ein Client-Paket: " + wire.length);
    }

    @Test
    void randomGarbageNeverCrashesTheDecoders() {
        Random random = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            byte[] data = new byte[1 + random.nextInt(50)];
            random.nextBytes(data);
            if (random.nextInt(3) == 0) data[0] = (byte) (1 + random.nextInt(3));
            if (data.length > 4 && random.nextInt(2) == 0) { data[1] = 0; data[2] = 0; data[3] = 0; data[4] = (byte) CoreProtocol.VERSION; }
            try { CorePacketCodec.decodeServerbound(data); } catch (MalformedFrameException ignored) { }
            try { CorePacketCodec.decodeClientbound(data); } catch (MalformedFrameException ignored) { }
        }
    }
}
