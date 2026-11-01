package de.peakverse.core.plugin.update;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import de.peakverse.core.api.web.WebRegistry;
import de.peakverse.core.api.web.WebRequest;
import de.peakverse.core.api.web.WebResponse;
import java.io.IOException;
import org.bukkit.Bukkit;

/** Web-Schnittstelle der Updates ({@code /api/update/...}): nur fuer Operatoren und Voll-Zugang. */
public final class UpdateWeb {
    private static final Gson GSON = new Gson();

    private final WebRegistry web;
    private final UpdateService service;

    public UpdateWeb(WebRegistry web, UpdateService service) {
        this.web = web;
        this.service = service;
    }

    public void register() {
        web.route("status", r -> WebResponse.json(service.statusJson()));
        web.route("check", r -> post(r, "Update-Pruefung gestartet", () -> service.check()));
        web.route("download", r -> post(r, "Update wird geladen", () -> {
            try {
                service.download();
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }));
        web.route("restart", r -> {
            int minutes = r.text().isBlank() ? 5 : GSON.fromJson(r.text(), JsonObject.class).get("minutes").getAsInt();
            return post(r, "Neustart in " + minutes + " Minuten geplant", () -> {
                try {
                    web.onMain(() -> {
                        service.scheduleRestart(minutes);
                        return null;
                    });
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalArgumentException(e.getMessage());
                }
            });
        });
        web.route("cancel", r -> post(r, "Geplanten Neustart abgebrochen", () -> {
            try {
                web.onMain(() -> service.cancelRestart());
            } catch (Exception e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }));
    }

    private WebResponse post(WebRequest r, String what, Runnable action) {
        if (!r.method().equals("POST")) return WebResponse.error(405, "POST");
        action.run();
        web.log(r.admin(), what);
        Bukkit.getLogger().info("[PeakCore/Web] " + r.admin() + ": " + what);
        web.changed("update", r.admin());
        return WebResponse.json(service.statusJson());
    }
}
