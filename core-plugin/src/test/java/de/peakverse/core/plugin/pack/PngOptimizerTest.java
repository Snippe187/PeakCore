package de.peakverse.core.plugin.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PngOptimizerTest {
    private static int[] pixels(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    @Test
    void shrinksLosslesslyAndStripsTextChunks() throws IOException {
        byte[] in = PngOptimizerTestData.wastefulPng(true);
        byte[] out = PngOptimizer.optimize(in);
        assertTrue(out.length < in.length / 2, in.length + " -> " + out.length);
        assertArrayEquals(pixels(in), pixels(out), "Pixel muessen identisch bleiben");
        assertTrue(new String(out, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("tEXt") < 0);
    }

    @Test
    void secondRunGainsNothingAndReturnsTheSameArray() {
        byte[] once = PngOptimizer.optimize(PngOptimizerTestData.wastefulPng(false));
        assertSame(once, PngOptimizer.optimize(once));
    }

    @Test
    void imageIoWrittenPngStaysValid() throws IOException {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 32; i++) img.setRGB(i, i, 0xFFFF0000 | i);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "png", o);
        byte[] out = PngOptimizer.optimize(o.toByteArray());
        assertArrayEquals(pixels(o.toByteArray()), pixels(out));
    }

    @Test
    void doesNotTouchBrokenOrForeignData() {
        byte[] garbage = {1, 2, 3, 4, 5};
        assertSame(garbage, PngOptimizer.optimize(garbage));
        byte[] png = PngOptimizerTestData.wastefulPng(false);
        byte[] badCrc = png.clone();
        badCrc[badCrc.length - 20] ^= 0x55; // irgendwo in den Daten
        assertSame(badCrc, PngOptimizer.optimize(badCrc));
        byte[] truncated = java.util.Arrays.copyOf(png, png.length - 30);
        assertSame(truncated, PngOptimizer.optimize(truncated));
    }

    @Test
    void animatedPngIsLeftAlone() {
        byte[] png = PngOptimizerTestData.wastefulPng(false);
        ByteArrayOutputStream withActl = new ByteArrayOutputStream();
        withActl.writeBytes(java.util.Arrays.copyOf(png, 8 + 25)); // Signatur + IHDR
        withActl.writeBytes(PngOptimizerTestData.chunk("acTL", new byte[8]));
        withActl.writeBytes(java.util.Arrays.copyOfRange(png, 8 + 25, png.length));
        byte[] in = withActl.toByteArray();
        assertSame(in, PngOptimizer.optimize(in));
    }
}
