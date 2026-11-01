package de.peakverse.core.plugin.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Anmeldung der Web-Oberflaeche: Einmal-Links (kurz gueltig, nur einmal einloesbar) und Sitzungen. Bukkit-frei und mit austauschbarer Uhr testbar.
 * Ein Einmal-Link wird im Spiel von einem Operator erzeugt; wer ihn einloest, bekommt eine Sitzung auf dessen Namen.
 */
public final class WebAuth {
    public static final long TOKEN_TTL_MS = 5 * 60_000L;
    public static final long SESSION_TTL_MS = 8 * 3_600_000L;
    public static final int MAX_FAILURES_PER_MINUTE = 10;

    public record Session(String id, UUID player, String name, long expires, String ip) { }

    private record Token(UUID player, String name, long expires) { }

    private final LongSupplier clock;
    private final long sessionTtlMs;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Token> tokens = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, long[]> failures = new HashMap<>(); // ip -> {Fensterstart, Anzahl}

    public WebAuth(LongSupplier clock) {
        this(clock, SESSION_TTL_MS);
    }

    /** Mit eigener Sitzungsdauer (config web.session-hours). */
    public WebAuth(LongSupplier clock, long sessionTtlMs) {
        this.clock = clock;
        this.sessionTtlMs = Math.max(60_000L, sessionTtlMs);
    }

    public long sessionTtlMs() {
        return sessionTtlMs;
    }

    private String randomId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Erzeugt einen Einmal-Link-Schluessel fuer diesen Operator. */
    public synchronized String issueToken(UUID player, String name) {
        purge();
        String token = randomId();
        tokens.put(token, new Token(player, name, clock.getAsLong() + TOKEN_TTL_MS));
        return token;
    }

    /** Loest einen Einmal-Link-Schluessel ein (danach ungueltig) und legt die Sitzung an. */
    public synchronized Optional<Session> redeem(String token, String ip) {
        purge();
        if (token == null || blocked(ip)) return Optional.empty();
        Token t = tokens.remove(token);
        if (t == null) {
            failure(ip);
            return Optional.empty();
        }
        Session s = new Session(randomId(), t.player(), t.name(), clock.getAsLong() + sessionTtlMs, ip);
        sessions.put(s.id(), s);
        return Optional.of(s);
    }

    public synchronized Optional<Session> session(String id) {
        if (id == null) return Optional.empty();
        Session s = sessions.get(id);
        if (s == null) return Optional.empty();
        if (s.expires() <= clock.getAsLong()) {
            sessions.remove(id);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public synchronized void logout(String id) {
        sessions.remove(id);
    }

    /** Beendet alle Sitzungen eines Spielers (z. B. nach {@code /deop}). */
    public synchronized void revokeAll(UUID player) {
        sessions.values().removeIf(s -> s.player().equals(player));
        tokens.values().removeIf(t -> t.player().equals(player));
    }

    /** Alle Sitzungen beenden (z. B. beim Neuladen). */
    public synchronized void clear() {
        sessions.clear();
        tokens.clear();
    }

    public synchronized int sessionCount() {
        purge();
        return sessions.size();
    }

    private boolean blocked(String ip) {
        long[] f = failures.get(ip);
        return f != null && clock.getAsLong() - f[0] < 60_000L && f[1] >= MAX_FAILURES_PER_MINUTE;
    }

    private void failure(String ip) {
        long now = clock.getAsLong();
        long[] f = failures.get(ip);
        if (f == null || now - f[0] >= 60_000L) failures.put(ip, new long[] {now, 1});
        else f[1]++;
    }

    private void purge() {
        long now = clock.getAsLong();
        tokens.values().removeIf(t -> t.expires() <= now);
        sessions.values().removeIf(s -> s.expires() <= now);
        failures.values().removeIf(f -> now - f[0] >= 60_000L);
    }
}

