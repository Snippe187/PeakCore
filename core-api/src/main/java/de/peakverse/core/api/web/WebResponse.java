package de.peakverse.core.api.web;

import java.nio.charset.StandardCharsets;

/** Antwort eines Moduls an die Web-Oberflaeche. */
public record WebResponse(int status, String contentType, byte[] body) {
    public static WebResponse json(String json) {
        return new WebResponse(200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    public static WebResponse text(String text) {
        return new WebResponse(200, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    public static WebResponse bytes(String contentType, byte[] data) {
        return new WebResponse(200, contentType, data);
    }

    /** Fehler als {@code {"error": "..."}}, damit die Oberflaeche die Meldung anzeigen kann. */
    public static WebResponse error(int status, String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        return new WebResponse(status, "application/json; charset=utf-8", ("{\"error\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    public static WebResponse ok() {
        return json("{\"ok\":true}");
    }
}
