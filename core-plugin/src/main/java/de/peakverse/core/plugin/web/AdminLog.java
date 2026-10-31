package de.peakverse.core.plugin.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Aenderungsprotokoll der Web-Oberflaeche: eine JSON-Zeile je Aktion in {@code web-log.jsonl}. */
public final class AdminLog {
    private static final Gson GSON = new Gson();

    private final Path file;
    private final LongSupplier clock;

    public AdminLog(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
    }

    public synchronized void append(String admin, String module, String action) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("time", clock.getAsLong());
        o.addProperty("admin", admin);
        o.addProperty("module", module);
        o.addProperty("action", action);
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(o) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Die letzten {@code max} Zeilen, neueste zuerst. Kaputte Zeilen werden uebersprungen. */
    public synchronized List<JsonObject> recent(int max) throws IOException {
        List<JsonObject> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = lines.size() - 1; i >= 0 && out.size() < max; i--) {
            try {
                out.add(JsonParser.parseString(lines.get(i)).getAsJsonObject());
            } catch (RuntimeException e) {
                // halb geschriebene oder fremde Zeile: ignorieren
            }
        }
        return out;
    }
}
