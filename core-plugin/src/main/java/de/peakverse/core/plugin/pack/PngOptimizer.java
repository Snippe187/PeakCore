package de.peakverse.core.plugin.pack;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Verlustfreies Verkleinern von PNG-Dateien: Die Bilddaten (IDAT) werden mit der staerksten Deflate-Stufe neu gepackt, Text-, Zeit- und
 * EXIF-Bloecke entfallen. Die Pixel und die Filterung bleiben <b>bit-genau</b> gleich (die entpackten Bilddaten werden vor der Rueckgabe verglichen).
 * Im Zweifel kommt die Eingabe unveraendert zurueck: kaputte Pruefsummen, animierte PNGs (APNG), unbekannte Pflicht-Bloecke, zu grosse Dateien
 * oder kein Gewinn.
 *
 * <p>Die Ausgabe von Deflate haengt etwas von der zlib-Version der Java-Laufzeit ab. Nach einem Java-Update kann derselbe Inhalt daher einen anderen
 * Pack-Hash ergeben (die Spieler laden das Pack dann einmal neu).
 */
public final class PngOptimizer {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final long MAX_RAW = 128L * 1024 * 1024;
    /** Bloecke, die weg duerfen (rein beschreibend, Minecraft liest sie nicht). */
    private static final Set<String> STRIP = Set.of("tEXt", "zTXt", "iTXt", "tIME", "eXIf", "pHYs");

    private PngOptimizer() {}

    private record Chunk(String type, byte[] data) {}

    /** @return die verkleinerte Datei oder genau die Eingabe ({@code ==}), wenn nichts gewonnen wurde */
    public static byte[] optimize(byte[] png) {
        try {
            byte[] out = tryOptimize(png);
            return out != null && out.length < png.length ? out : png;
        } catch (RuntimeException | DataFormatException e) {
            return png;
        }
    }

    private static byte[] tryOptimize(byte[] png) throws DataFormatException {
        if (png.length > MAX_BYTES || png.length < 33 || !Arrays.equals(Arrays.copyOf(png, 8), SIGNATURE)) return null;
        List<Chunk> chunks = new ArrayList<>();
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        int pos = 8;
        int firstIdat = -1;
        boolean sawEnd = false;
        while (pos + 12 <= png.length && !sawEnd) {
            int len = ByteBuffer.wrap(png, pos, 4).getInt();
            if (len < 0 || pos + 12L + len > png.length) return null;
            String type = new String(png, pos + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            byte[] data = Arrays.copyOfRange(png, pos + 8, pos + 8 + len);
            CRC32 crc = new CRC32();
            crc.update(png, pos + 4, 4 + len);
            if (ByteBuffer.wrap(png, pos + 8 + len, 4).getInt() != (int) crc.getValue()) return null;
            pos += 12 + len;
            if (type.equals("acTL") || type.equals("fcTL") || type.equals("fdAT")) return null; // animiert: nicht anfassen
            if (type.equals("IDAT")) {
                if (firstIdat < 0) {
                    firstIdat = chunks.size();
                    chunks.add(new Chunk("IDAT", null)); // Platzhalter
                }
                idat.write(data, 0, data.length);
                continue;
            }
            if (STRIP.contains(type)) continue;
            if (Character.isUpperCase(type.charAt(0)) && !Set.of("IHDR", "PLTE", "IEND").contains(type)) return null; // unbekannter Pflicht-Block
            chunks.add(new Chunk(type, data));
            if (type.equals("IEND")) sawEnd = true;
        }
        if (!sawEnd || firstIdat < 0 || chunks.isEmpty() || !chunks.get(0).type().equals("IHDR")) return null;

        byte[] raw = inflate(idat.toByteArray());
        byte[] packed = deflate(raw);
        // Probe: genau dieselben Bilddaten wie vorher
        if (!Arrays.equals(inflate(packed), raw)) return null;

        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length);
        out.write(SIGNATURE, 0, 8);
        for (Chunk c : chunks) write(out, c.type(), c.type().equals("IDAT") ? packed : c.data());
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static byte[] inflate(byte[] zlib) throws DataFormatException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(zlib);
            ByteArrayOutputStream out = new ByteArrayOutputStream(zlib.length * 4);
            byte[] buf = new byte[64 * 1024];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw new DataFormatException("unvollstaendiger Datenstrom");
                out.write(buf, 0, n);
                if (out.size() > MAX_RAW) throw new DataFormatException("zu gross");
            }
            return out.toByteArray();
        } finally {
            inf.end();
        }
    }

    private static byte[] deflate(byte[] raw) {
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            def.setInput(raw);
            def.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + 64);
            byte[] buf = new byte[64 * 1024];
            while (!def.finished()) out.write(buf, 0, def.deflate(buf));
            return out.toByteArray();
        } finally {
            def.end();
        }
    }
}
