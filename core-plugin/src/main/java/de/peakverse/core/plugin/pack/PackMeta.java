package de.peakverse.core.plugin.pack;

import java.nio.charset.StandardCharsets;

/**
 * Inhalt der {@code pack.mcmeta}. Die Format-Nummer kommt aus der {@code version.json} des Servers (Minecraft 26.2: Resource-Pack {@code 88.0});
 * sie steht in der Config, weil sie sich mit jeder Minecraft-Version aendert.
 *
 * <p>Geschrieben wird {@code min_format}/{@code max_format} (ohne das alte {@code pack_format}). Dass Minecraft 26.2 genau diese Form
 * annimmt, ist noch nicht mit einem echten Client geprueft (siehe docs/TESTS.md).
 */
public record PackMeta(String description, int major, int minor) {
    public PackMeta {
        if (description == null) description = "";
        if (major < 1 || minor < 0) throw new IllegalArgumentException("Ungueltige Pack-Format-Nummer: " + major + "." + minor);
    }

    public byte[] toJson() {
        return toJson("");
    }

    /** Mit den Overlays des Basis-Packs ({@code overlayEntries}: JSON-Array der Eintraege, leer = keine). */
    public byte[] toJson(String overlayEntries) {
        String json = "{\n  \"pack\": {\n    \"description\": " + quote(description) + ",\n    \"min_format\": [" + major + ", " + minor
                + "],\n    \"max_format\": [" + major + ", " + minor + "]\n  }" + (overlayEntries == null || overlayEntries.isBlank() ? "" : ",\n  \"overlays\": {\n    \"entries\": " + overlayEntries + "\n  }") + "\n}\n";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
