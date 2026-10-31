package de.peakverse.core.plugin.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Live-Aktualisierung per Server-Sent-Events: offene Seiten bekommen eine Zeile, sobald ein Modul {@code changed} meldet.
 * Tote Verbindungen werden beim naechsten Senden entfernt.
 */
public final class LiveHub {
    public static final int MAX_CLIENTS = 50;

    private record Client(HttpExchange ex, OutputStream out, String admin) { }

    private final List<Client> clients = new ArrayList<>();

    /** Haengt die Verbindung an; sie bleibt offen, bis ein Sendeversuch scheitert. @return {@code false}, wenn zu viele Seiten offen sind */
    public synchronized boolean attach(HttpExchange ex, String admin) throws IOException {
        if (clients.size() >= MAX_CLIENTS) return false;
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        out.write(": verbunden\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        clients.add(new Client(ex, out, admin));
        return true;
    }

    /** Schickt ein Ereignis an alle. {@code json} muss einzeilig sein. */
    public synchronized void send(String event, String json) {
        byte[] data = ("event: " + event + "\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        clients.removeIf(c -> {
            try {
                c.out().write(data);
                c.out().flush();
                return false;
            } catch (IOException e) {
                close(c);
                return true;
            }
        });
    }

    /** Alle zwei Minuten aufrufen: haelt Verbindungen offen und raeumt tote weg. */
    public void ping() {
        send("ping", "{}");
    }

    public synchronized List<String> admins() {
        return clients.stream().map(Client::admin).distinct().toList();
    }

    public synchronized int count() {
        return clients.size();
    }

    public synchronized void closeAll() {
        clients.forEach(LiveHub::close);
        clients.clear();
    }

    private static void close(Client c) {
        try {
            c.ex().close();
        } catch (RuntimeException ignored) {
            // schon zu
        }
    }
}
