package de.peakverse.core.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Kodiert und decodiert {@link Frame}s. Format:
 * <pre>
 * Modul-Paket:  [0x01] [Laenge der Modul-ID: 1 Byte] [Modul-ID: ASCII] [Nutzdaten bis zum Ende]
 * Core-Paket:   [0x02] [Nutzdaten bis zum Ende]
 * </pre>
 * Das Decodieren ist streng: alles, was nicht genau diesem Format entspricht, wirft {@link MalformedFrameException}
 * (manipulierte Pakete duerfen nie etwas anderes ausloesen als verworfen zu werden).
 */
public final class FrameCodec {
    public static final int TYPE_MODULE = 1;
    public static final int TYPE_CORE = 2;

    private FrameCodec() {}

    /** Groesste Nutzdatenmenge, die in ein Modul-Paket dieser Modul-ID passt. */
    public static int maxModulePayload(String moduleId, Direction direction) {
        return direction.maxFrameSize() - 2 - moduleId.length();
    }

    /** Groesste Nutzdatenmenge eines Core-Pakets. */
    public static int maxCorePayload(Direction direction) {
        return direction.maxFrameSize() - 1;
    }

    /** @throws IllegalArgumentException wenn die Nutzdaten fuer die Richtung zu gross sind */
    public static byte[] encode(Frame frame, Direction direction) {
        return switch (frame) {
            case Frame.ModuleFrame m -> {
                if (m.payload().length > maxModulePayload(m.moduleId(), direction)) {
                    throw new IllegalArgumentException("Nutzdaten zu gross: " + m.payload().length + " Bytes (Modul " + m.moduleId() + ")");
                }
                byte[] id = m.moduleId().getBytes(StandardCharsets.US_ASCII);
                byte[] out = new byte[2 + id.length + m.payload().length];
                out[0] = TYPE_MODULE;
                out[1] = (byte) id.length;
                System.arraycopy(id, 0, out, 2, id.length);
                System.arraycopy(m.payload(), 0, out, 2 + id.length, m.payload().length);
                yield out;
            }
            case Frame.CoreFrame c -> {
                if (c.payload().length > maxCorePayload(direction)) {
                    throw new IllegalArgumentException("Nutzdaten zu gross: " + c.payload().length + " Bytes (Core)");
                }
                byte[] out = new byte[1 + c.payload().length];
                out[0] = TYPE_CORE;
                System.arraycopy(c.payload(), 0, out, 1, c.payload().length);
                yield out;
            }
        };
    }

    /** @throws MalformedFrameException bei jedem Verstoss gegen das Format */
    public static Frame decode(byte[] data, Direction direction) {
        if (data == null || data.length == 0 || data.length > direction.maxFrameSize()) {
            throw new MalformedFrameException("Ungueltige Paketgroesse");
        }
        int type = data[0] & 0xFF;
        switch (type) {
            case TYPE_MODULE -> {
                if (data.length < 2) throw new MalformedFrameException("Modul-Paket ohne Modul-ID");
                int idLength = data[1] & 0xFF;
                if (idLength < ModuleIds.MIN_LENGTH || idLength > ModuleIds.MAX_LENGTH || 2 + idLength > data.length) {
                    throw new MalformedFrameException("Ungueltige Laenge der Modul-ID");
                }
                String id = new String(data, 2, idLength, StandardCharsets.US_ASCII);
                if (!ModuleIds.isValid(id)) throw new MalformedFrameException("Ungueltige Modul-ID");
                return new Frame.ModuleFrame(id, Arrays.copyOfRange(data, 2 + idLength, data.length));
            }
            case TYPE_CORE -> {
                return new Frame.CoreFrame(Arrays.copyOfRange(data, 1, data.length));
            }
            default -> throw new MalformedFrameException("Unbekannter Pakettyp " + type);
        }
    }
}
