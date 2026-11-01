package de.peakverse.core.plugin.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Koppeln eines neuen Geraets (Handy): Das Geraet nennt einen Spielernamen, der Spieler bestaetigt im Spiel mit dem Code, den das Geraet anzeigt.
 * Erst danach bekommt das Geraet seinen Schluessel. Bukkit-frei und mit austauschbarer Uhr testbar.
 * <p>
 * Schutz: Je Spieler laeuft nur eine Anfrage, nach einer Ablehnung oder einem Ablauf gilt eine Pause (gegen Chat-Spam), je IP gibt es hoechstens
 * {@link #MAX_STARTS_PER_MINUTE} Anfragen pro Minute, die Anfrage-Kennung ist ein langes Zufallsgeheimnis und nur das anfragende Geraet kennt sie.
 */
public final class PairingService {
    public static final long TTL_MS = 2 * 60_000L;
    public static final long COOLDOWN_MS = 20_000L;
    public static final int MAX_STARTS_PER_MINUTE = 5;
    public static final int MAX_PENDING = 50;

    public enum State { PENDING, APPROVED, DENIED, EXPIRED }

    /** Eine offene Anfrage. */
    public record Request(String id, String code, UUID player, String name, String ip, String label, long expires, State state) { }

    private static final class Entry {
        final Request base;
        State state = State.PENDING;

        Entry(Request base) {
            this.base = base;
        }
    }

    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> byId = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Map<String, long[]> starts = new HashMap<>();

    public PairingService(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Startet eine Anfrage fuer {@code player}. @return die Anfrage, oder {@code null}, wenn jetzt keine neue moeglich ist
     * (Pause, schon eine offene Anfrage, zu viele Anfragen von dieser IP oder insgesamt)
     */
    public synchronized Request start(UUID player, String name, String ip, String label) {
        purge();
        long now = clock.getAsLong();
        long[] s = starts.get(ip);
        if (s == null || now - s[0] >= 60_000L) starts.put(ip, new long[] {now, 1});
        else if (s[1] >= MAX_STARTS_PER_MINUTE) return null;
        else s[1]++;
        if (byId.size() >= MAX_PENDING) return null;
        if (cooldown.getOrDefault(player, 0L) > now) return null;
        for (Entry e : byId.values()) if (e.base.player().equals(player) && e.state == State.PENDING) return null;
        byte[] b = new byte[24];
        random.nextBytes(b);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        String code = String.format("%04d", random.nextInt(10_000));
        Request r = new Request(id, code, player, name, ip, label == null ? "" : label, now + TTL_MS, State.PENDING);
        byId.put(id, new Entry(r));
        return r;
    }

    /** Der Spieler bestaetigt im Spiel mit dem Code. @return {@code true}, wenn es eine passende offene Anfrage gab */
    public synchronized boolean approve(UUID player, String code) {
        purge();
        for (Entry e : byId.values()) {
            if (e.state == State.PENDING && e.base.player().equals(player) && e.base.code().equals(code)) {
                e.state = State.APPROVED;
                return true;
            }
        }
        return false;
    }

    /** Der Spieler lehnt ab (oder meldet "das war ich nicht"). Danach gilt eine Pause. @return {@code true}, wenn es eine offene Anfrage gab */
    public synchronized boolean deny(UUID player) {
        purge();
        boolean any = false;
        for (Entry e : byId.values()) {
            if (e.state == State.PENDING && e.base.player().equals(player)) {
                e.state = State.DENIED;
                any = true;
            }
        }
        if (any) cooldown.put(player, clock.getAsLong() + COOLDOWN_MS);
        return any;
    }

    /** Die offene Anfrage eines Spielers (fuer die Meldung im Spiel), oder {@code null}. */
    public synchronized Request pendingFor(UUID player) {
        purge();
        for (Entry e : byId.values()) if (e.state == State.PENDING && e.base.player().equals(player)) return e.base;
        return null;
    }

    /** Stand einer Anfrage fuer das Geraet. Eine abgelehnte Anfrage wird dabei verbraucht. */
    public synchronized State poll(String id) {
        purge();
        Entry e = byId.get(id);
        if (e == null) return State.EXPIRED;
        if (e.state == State.DENIED) byId.remove(id);
        return e.state;
    }

    /** Holt eine bestaetigte Anfrage ab (nur einmal moeglich). @return die Anfrage oder {@code null}, wenn sie nicht bestaetigt ist */
    public synchronized Request claim(String id) {
        purge();
        Entry e = byId.get(id);
        if (e == null || e.state != State.APPROVED) return null;
        byId.remove(id);
        return e.base;
    }

    private void purge() {
        long now = clock.getAsLong();
        byId.values().removeIf(e -> {
            if (e.base.expires() <= now) {
                if (e.state == State.PENDING) cooldown.put(e.base.player(), now + COOLDOWN_MS);
                return true;
            }
            return false;
        });
        cooldown.values().removeIf(t -> t <= now);
        starts.values().removeIf(s -> now - s[0] >= 60_000L);
    }
}
