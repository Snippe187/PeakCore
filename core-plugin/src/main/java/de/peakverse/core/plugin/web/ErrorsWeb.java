package de.peakverse.core.plugin.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.peakverse.core.api.web.WebRegistry;
import de.peakverse.core.api.web.WebResponse;

/** Web-Schnittstelle des Bereichs "Fehler" ({@code /api/errors/...}). */
public final class ErrorsWeb {
    private static final Gson GSON = new Gson();

    private final WebRegistry web;
    private final ErrorLog log;

    public ErrorsWeb(WebRegistry web, ErrorLog log) {
        this.web = web;
        this.log = log;
    }

    public void register(String script) {
        web.panel("Fehler", "Errors", script);
        web.route("list", r -> WebResponse.json(list()));
        web.route("resolve", r -> {
            if (!r.method().equals("POST")) return WebResponse.error(405, "POST");
            String id = r.text().isBlank() ? "" : GSON.fromJson(r.text(), JsonObject.class).get("id").getAsString();
            if (!log.resolve(id)) return WebResponse.error(404, "Diese Meldung gibt es nicht mehr");
            log.saveIfDirty();
            web.log(r.admin(), "Fehler als erledigt markiert");
            web.changed("errors", r.admin());
            return WebResponse.json(list());
        });
        web.route("clear", r -> {
            if (!r.method().equals("POST")) return WebResponse.error(405, "POST");
            int n = log.clear();
            log.saveIfDirty();
            web.log(r.admin(), "Fehlerliste geleert (" + n + " Meldungen)");
            web.changed("errors", r.admin());
            return WebResponse.json(list());
        });
    }

    private String list() {
        JsonArray a = new JsonArray();
        for (ErrorLog.Entry e : log.list()) a.add(GSON.toJsonTree(e));
        return a.toString();
    }
}
