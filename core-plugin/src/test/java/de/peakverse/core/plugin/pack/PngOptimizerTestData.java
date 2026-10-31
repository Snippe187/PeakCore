package de.peakverse.core.plugin.pack;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Testdaten: ein absichtlich schlecht gepacktes PNG. */
final class PngOptimizerTestData {
    private PngOptimizerTestData() {}

    static byte[] wastefulPng() {
        return wastefulPng(true);
    }

    static byte[] chunk(String type, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        return out.toByteArray();
    }

    /** Ein 64x64-PNG mit absichtlich schlecht gepackten Bilddaten (Stufe 0 = nur gespeichert) und Text-Block. */
    static byte[] wastefulPng(boolean withText) {
        int w = 64;
        int h = 64;
        byte[] raw = new byte[(1 + w * 4) * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = y * (1 + w * 4) + 1 + x * 4;
                raw[o] = (byte) (x * 4);
                raw[o + 1] = (byte) (y * 4);
                raw[o + 2] = (byte) 128;
                raw[o + 3] = (byte) 255;
            }
        }
        Deflater d = new Deflater(Deflater.NO_COMPRESSION);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) z.write(buf, 0, d.deflate(buf));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        png.writeBytes(chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).array()));
        if (withText) png.writeBytes(chunk("tEXt", "Comment\0hallo hallo hallo hallo hallo hallo".getBytes()));
        png.writeBytes(chunk("IDAT", z.toByteArray()));
        png.writeBytes(chunk("IEND", new byte[0]));
        return png.toByteArray();
    }

}
