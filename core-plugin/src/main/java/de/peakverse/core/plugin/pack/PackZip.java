package de.peakverse.core.plugin.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Baut das Pack-ZIP so, dass dieselben Dateien immer dieselben Bytes (und damit denselben SHA1) ergeben: Eintraege nach Pfad sortiert,
 * fester Zeitstempel, keine Zusatzfelder und <b>ohne Kompression</b> (STORED). Die Deflate-Ausgabe waere von der zlib-Version der jeweiligen
 * Java-Laufzeit abhaengig und der Hash damit nicht ueber Maschinen hinweg stabil. Texturen und Klaenge sind ohnehin schon komprimiert.
 */
public final class PackZip {
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2000, 1, 1, 0, 0, 0);

    private PackZip() {}

    /** @param zip die ZIP-Bytes; @param sha1 kleingeschriebener Hex-SHA1 der ZIP-Bytes (so erwartet ihn Minecraft beim Pack-Versand) */
    public record Built(byte[] zip, String sha1, int fileCount) {}

    /** Baut das Pack. {@code files} darf {@code pack.mcmeta} nicht enthalten, sie wird aus {@code meta} erzeugt; {@code icon} ist optional ({@code pack.png}). */
    public static Built build(Map<String, byte[]> files, PackMeta meta, byte[] icon) {
        Map<String, byte[]> all = new TreeMap<>(files);
        if (all.containsKey("pack.mcmeta") || all.containsKey("pack.png")) throw new IllegalArgumentException("pack.mcmeta und pack.png setzt der Core");
        all.put("pack.mcmeta", meta.toJson());
        if (icon != null) all.put("pack.png", icon);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.setMethod(ZipOutputStream.STORED);
                for (Map.Entry<String, byte[]> e : all.entrySet()) {
                    byte[] data = e.getValue();
                    ZipEntry entry = new ZipEntry(e.getKey());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(data.length);
                    entry.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    entry.setCrc(crc.getValue());
                    entry.setTimeLocal(FIXED_TIME);
                    zip.putNextEntry(entry);
                    zip.write(data);
                    zip.closeEntry();
                }
            }
            byte[] bytes = out.toByteArray();
            return new Built(bytes, sha1(bytes), all.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
