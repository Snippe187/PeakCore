package de.peakverse.core.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Kodiert und decodiert {@link CorePacket}s. Aufbau: {@code [Typ: 1 Byte] [Felder]}. Zahlen sind Big-Endian,
 * Strings {@code [Laenge: 2 Bytes] [UTF-8]}, Listen {@code [Anzahl: 1 Byte] [Eintraege]}.
 * Das Decodieren ist streng; jeder Verstoss (auch ueberzaehlige Bytes am Ende) wirft {@link MalformedFrameException}.
 */
public final class CorePacketCodec {
    static final int ID_HELLO = 1;
    static final int ID_HELLO_ACK = 2;
    static final int ID_ADMIN_STATE = 3;
    static final int ID_PACK_OFFER = 4;
    static final int ID_TUNNEL_LOGIN_REQUEST = 5;
    static final int ID_TUNNEL_LOGIN = 6;
    static final int ID_TUNNEL_OPEN = 7;
    static final int ID_TUNNEL_UP = 8;
    static final int ID_TUNNEL_END = 9;
    static final int ID_TUNNEL_DOWN = 10;
    static final int ID_TUNNEL_CLOSED = 11;
    static final int ID_PACK_REPORT = 12;

    private CorePacketCodec() {}

    // ---------- Kodieren ----------

    public static byte[] encode(CorePacket packet) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        switch (packet) {
            case CorePacket.Hello h -> {
                out.write(ID_HELLO);
                writeInt(out, h.protocol());
                writeString(out, h.coreVersion(), CoreProtocol.MAX_VERSION_LENGTH);
                writeCount(out, h.modules().size());
                for (CorePacket.ModuleVersion m : h.modules()) {
                    writeString(out, m.id(), ModuleIds.MAX_LENGTH);
                    writeString(out, m.version(), CoreProtocol.MAX_VERSION_LENGTH);
                }
            }
            case CorePacket.HelloAck a -> {
                out.write(ID_HELLO_ACK);
                writeInt(out, a.protocol());
                out.write(a.compatible() ? 1 : 0);
                out.write(a.admin() ? 1 : 0);
                writeString(out, a.coreVersion(), CoreProtocol.MAX_VERSION_LENGTH);
                writeInt(out, a.apiVersion());
                writeLong(out, a.serverTimeMillis());
                writeCount(out, a.modules().size());
                for (CorePacket.ModuleEntry m : a.modules()) {
                    writeString(out, m.id(), ModuleIds.MAX_LENGTH);
                    writeString(out, m.displayName(), CoreProtocol.MAX_NAME_LENGTH);
                    writeString(out, m.version(), CoreProtocol.MAX_VERSION_LENGTH);
                }
            }
            case CorePacket.AdminState s -> {
                out.write(ID_ADMIN_STATE);
                out.write(s.admin() ? 1 : 0);
            }
            case CorePacket.PackOffer o -> {
                out.write(ID_PACK_OFFER);
                writeString(out, o.url(), CoreProtocol.MAX_URL_LENGTH);
                writeString(out, o.sha1(), 40);
                writeLong(out, o.size());
            }
            case CorePacket.PackReport r -> {
                out.write(ID_PACK_REPORT);
                writeString(out, r.message(), CoreProtocol.MAX_URL_LENGTH);
            }
            case CorePacket.TunnelLoginRequest r -> out.write(ID_TUNNEL_LOGIN_REQUEST);
            case CorePacket.TunnelLogin l -> {
                out.write(ID_TUNNEL_LOGIN);
                writeString(out, l.path(), CoreProtocol.MAX_URL_LENGTH);
            }
            case CorePacket.TunnelOpen o -> {
                out.write(ID_TUNNEL_OPEN);
                writeInt(out, o.conn());
            }
            case CorePacket.TunnelUp u -> {
                out.write(ID_TUNNEL_UP);
                writeInt(out, u.conn());
                writeBytes(out, u.data());
            }
            case CorePacket.TunnelEnd e -> {
                out.write(ID_TUNNEL_END);
                writeInt(out, e.conn());
            }
            case CorePacket.TunnelDown d -> {
                out.write(ID_TUNNEL_DOWN);
                writeInt(out, d.conn());
                writeBytes(out, d.data());
            }
            case CorePacket.TunnelClosed c -> {
                out.write(ID_TUNNEL_CLOSED);
                writeInt(out, c.conn());
            }
        }
        return out.toByteArray();
    }

    // ---------- Decodieren ----------

    public static CorePacket.Serverbound decodeServerbound(byte[] payload) {
        ByteBuffer in = open(payload);
        int id = in.get() & 0xFF;
        try {
            CorePacket.Serverbound packet = switch (id) {
                case ID_HELLO -> decodeHello(in);
                case ID_TUNNEL_LOGIN_REQUEST -> new CorePacket.TunnelLoginRequest();
                case ID_TUNNEL_OPEN -> new CorePacket.TunnelOpen(in.getInt());
                case ID_TUNNEL_UP -> new CorePacket.TunnelUp(in.getInt(), readBytes(in, CoreProtocol.TUNNEL_MAX_UP));
                case ID_TUNNEL_END -> new CorePacket.TunnelEnd(in.getInt());
                case ID_PACK_REPORT -> new CorePacket.PackReport(readString(in, CoreProtocol.MAX_URL_LENGTH));
                default -> throw new MalformedFrameException("Unerwartetes Core-Paket vom Client: " + id);
            };
            if (id != ID_HELLO) requireFullyRead(in);
            return packet;
        } catch (IllegalArgumentException e) {
            throw new MalformedFrameException("Ungueltiges Paket: " + e.getMessage());
        } catch (BufferUnderflowException e) {
            throw new MalformedFrameException("Paket zu kurz");
        }
    }

    public static CorePacket.Clientbound decodeClientbound(byte[] payload) {
        ByteBuffer in = open(payload);
        int id = in.get() & 0xFF;
        try {
            CorePacket.Clientbound packet = switch (id) {
                case ID_HELLO_ACK -> decodeHelloAck(in);
                case ID_ADMIN_STATE -> new CorePacket.AdminState(readBoolean(in));
                case ID_PACK_OFFER -> decodePackOffer(in);
                case ID_TUNNEL_LOGIN -> new CorePacket.TunnelLogin(readString(in, CoreProtocol.MAX_URL_LENGTH));
                case ID_TUNNEL_DOWN -> new CorePacket.TunnelDown(in.getInt(), readBytes(in, CoreProtocol.TUNNEL_MAX_DOWN));
                case ID_TUNNEL_CLOSED -> new CorePacket.TunnelClosed(in.getInt());
                default -> throw new MalformedFrameException("Unbekanntes Core-Paket vom Server: " + id);
            };
            requireFullyRead(in);
            return packet;
        } catch (IllegalArgumentException e) {
            throw new MalformedFrameException("Ungueltiges Paket: " + e.getMessage());
        } catch (BufferUnderflowException e) {
            throw new MalformedFrameException("Paket zu kurz");
        }
    }

    private static ByteBuffer open(byte[] payload) {
        if (payload == null || payload.length == 0) throw new MalformedFrameException("Leeres Core-Paket");
        return ByteBuffer.wrap(payload);
    }

    private static CorePacket.Hello decodeHello(ByteBuffer in) {
        int protocol = in.getInt();
        if (protocol != CoreProtocol.VERSION) {
            // fremde Version: Format unbekannt, der Rest wird bewusst nicht gelesen
            return new CorePacket.Hello(protocol, "", List.of());
        }
        String coreVersion = readString(in, CoreProtocol.MAX_VERSION_LENGTH);
        int count = readCount(in);
        List<CorePacket.ModuleVersion> modules = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = readString(in, ModuleIds.MAX_LENGTH);
            String version = readString(in, CoreProtocol.MAX_VERSION_LENGTH);
            modules.add(validModuleVersion(id, version));
        }
        requireFullyRead(in);
        return new CorePacket.Hello(protocol, coreVersion, modules);
    }

    private static CorePacket.HelloAck decodeHelloAck(ByteBuffer in) {
        int protocol = in.getInt();
        if (protocol != CoreProtocol.VERSION) {
            // fremde Version: nur die Versionsangabe ist verlaesslich, der Rest wird nicht gelesen
            in.position(in.limit());
            return new CorePacket.HelloAck(protocol, false, false, "", 0, 0, List.of());
        }
        boolean compatible = readBoolean(in);
        boolean admin = readBoolean(in);
        String coreVersion = readString(in, CoreProtocol.MAX_VERSION_LENGTH);
        int apiVersion = in.getInt();
        long serverTime = in.getLong();
        int count = readCount(in);
        List<CorePacket.ModuleEntry> modules = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = readString(in, ModuleIds.MAX_LENGTH);
            String name = readString(in, CoreProtocol.MAX_NAME_LENGTH);
            String version = readString(in, CoreProtocol.MAX_VERSION_LENGTH);
            try {
                modules.add(new CorePacket.ModuleEntry(id, name, version));
            } catch (IllegalArgumentException e) {
                throw new MalformedFrameException("Ungueltiger Moduleintrag: " + e.getMessage());
            }
        }
        return new CorePacket.HelloAck(protocol, compatible, admin, coreVersion, apiVersion, serverTime, modules);
    }

    private static CorePacket.PackOffer decodePackOffer(ByteBuffer in) {
        String url = readString(in, CoreProtocol.MAX_URL_LENGTH);
        String sha1 = readString(in, 40);
        long size = in.getLong();
        try {
            return new CorePacket.PackOffer(url, sha1, size);
        } catch (IllegalArgumentException e) {
            throw new MalformedFrameException("Ungueltiges Pack-Angebot: " + e.getMessage());
        }
    }

    private static CorePacket.ModuleVersion validModuleVersion(String id, String version) {
        try {
            return new CorePacket.ModuleVersion(id, version);
        } catch (IllegalArgumentException e) {
            throw new MalformedFrameException("Ungueltiger Moduleintrag: " + e.getMessage());
        }
    }

    // ---------- Lesen und Schreiben ----------

    private static void requireFullyRead(ByteBuffer in) {
        if (in.hasRemaining()) throw new MalformedFrameException("Ueberzaehlige Bytes am Paketende");
    }

    private static boolean readBoolean(ByteBuffer in) {
        byte b = in.get();
        if (b != 0 && b != 1) throw new MalformedFrameException("Ungueltiger Wahrheitswert");
        return b == 1;
    }

    private static int readCount(ByteBuffer in) {
        int count = in.get() & 0xFF;
        if (count > CoreProtocol.MAX_MODULES) throw new MalformedFrameException("Zu viele Eintraege: " + count);
        return count;
    }

    private static String readString(ByteBuffer in, int maxLength) {
        int length = in.getShort() & 0xFFFF;
        if (length > maxLength * 4 || length > in.remaining()) throw new MalformedFrameException("Ungueltige Stringlaenge");
        byte[] bytes = new byte[length];
        in.get(bytes);
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (s.length() > maxLength) throw new MalformedFrameException("String zu lang");
        return s;
    }

    private static void writeString(ByteArrayOutputStream out, String s, int maxLength) {
        if (s.length() > maxLength) throw new IllegalArgumentException("String zu lang");
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length >>> 8);
        out.write(bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] readBytes(ByteBuffer in, int max) {
        int length = in.getInt();
        if (length <= 0 || length > max || length > in.remaining()) throw new MalformedFrameException("Ungueltige Datenlaenge");
        byte[] bytes = new byte[length];
        in.get(bytes);
        return bytes;
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] data) {
        writeInt(out, data.length);
        out.write(data, 0, data.length);
    }

    private static void writeCount(ByteArrayOutputStream out, int count) {
        if (count > CoreProtocol.MAX_MODULES) throw new IllegalArgumentException("Zu viele Eintraege: " + count);
        out.write(count);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        writeInt(out, (int) (v >>> 32));
        writeInt(out, (int) v);
    }
}
