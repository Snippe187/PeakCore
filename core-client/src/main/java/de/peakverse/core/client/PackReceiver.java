package de.peakverse.core.client;

import de.peakverse.core.protocol.CorePacket;

/**
 * Empfang des Server-Packs in der Mod: laedt das Pack selbst (statt Annahme-Dialog), prueft SHA1 und Groesse, erinnert alle
 * {@value #REMINDER_MS} ms im Chat und aktiviert es beim naechsten Beitritt. Reine Logik ohne Minecraft; die Fabric-Mod stellt die {@link Env} bereit.
 * Alle Aufrufe gehoeren auf den Client-Haupt-Thread (auch {@link DownloadCallback}).
 */
public final class PackReceiver {
    public enum State {
        /** Nichts zu tun: kein Angebot oder das Angebot ist schon aktiv. */
        IDLE,
        /** Das Pack wird geladen. */
        DOWNLOADING,
        /** Geladen und geprueft, wartet auf den naechsten Beitritt. */
        PENDING,
        /** Laden oder Pruefen ist fehlgeschlagen; beim naechsten Angebot wird es erneut versucht. */
        FAILED
    }

    /** Ergebnis eines Downloads. */
    @FunctionalInterface
    public interface DownloadCallback {
        /** @param error {@code null} bei Erfolg (Datei liegt im Cache und ist geprueft), sonst die Fehlermeldung */
        void done(String error);
    }

    /** Umgebung (Dateien, Netzwerk, Chat); in der Mod mit Fabric/Minecraft umgesetzt, im Test nachgebaut. */
    public interface Env {
        long now();

        /** Liegt ein Pack mit diesem SHA1 geprueft im Cache? */
        boolean isCached(String sha1);

        /** Laedt asynchron, prueft SHA1 und Groesse, legt es im Cache ab und ruft dann {@code callback} auf dem Haupt-Thread. */
        void download(String url, String sha1, long size, DownloadCallback callback);

        /** Schaltet das gecachte Pack ein (ersetzt das vorher aktive PeakVerse-Pack). */
        void activate(String sha1);

        /** Das aktuell aktive Pack oder {@code null}. */
        String activeSha1();

        void chat(String message);

        /** Das geladene, noch nicht aktivierte Pack aus einer frueheren Sitzung oder {@code null}. Ueberlebt einen Neustart des Spiels. */
        String pendingSha1();

        /** Merkt sich das wartende Pack dauerhaft ({@code null} = keins). */
        void setPendingSha1(String sha1);

        /**
         * Schaltet ein frisch geladenes Pack sofort ein, ohne dass man neu beitreten muss. {@code true}, wenn es geklappt hat und der Spieler im
         * Chat Bescheid bekommen hat; {@code false} (Standard) heisst: wie bisher erst beim naechsten Beitritt.
         */
        default boolean applyNow(String sha1) {
            return false;
        }

        /** Das Laden ist fehlgeschlagen: dem Server Bescheid geben (er zeigt es im Fehler-Bereich der Web-Oberflaeche). */
        default void reportFailure(String error) {
        }
    }

    /** Abstand der Chat-Erinnerungen, solange ein geladenes Pack auf den Beitritt wartet. */
    public static final long REMINDER_MS = 2 * 60 * 1000;

    private final Env env;
    private State state = State.IDLE;
    private String pendingSha;
    private String lastError = "";
    private long lastReminderAt;
    private int generation;

    public PackReceiver(Env env) {
        this.env = env;
    }

    private void remember(String sha1) {
        env.setPendingSha1(sha1);
    }

    /** Das Angebot des Servers (kommt nach dem Handshake). */
    public void onOffer(CorePacket.PackOffer offer) {
        if (offer.sha1().equals(env.activeSha1()) && state != State.DOWNLOADING) {
            state = State.IDLE;
            pendingSha = null;
            remember(null);
            return;
        }
        if (state == State.DOWNLOADING && offer.sha1().equals(pendingSha)) return; // gleiches Angebot noch einmal
        pendingSha = offer.sha1();
        int mine = ++generation; // ein neueres Angebot macht die Antwort eines aelteren Downloads unwirksam
        if (env.isCached(offer.sha1())) {
            becomePending();
            return;
        }
        state = State.DOWNLOADING;
        env.download(offer.url(), offer.sha1(), offer.size(), error -> {
            if (mine != generation) return;
            if (error == null) {
                becomePending();
            } else {
                state = State.FAILED;
                lastError = error;
                pendingSha = null;
                env.chat("[PeakVerse] Das Server-Resource-Pack konnte nicht geladen werden: " + error);
                env.reportFailure(error);
            }
        });
    }

    private void becomePending() {
        String sha = pendingSha;
        if (sha != null && env.applyNow(sha)) {
            state = State.IDLE;
            pendingSha = null;
            remember(null);
            return;
        }
        state = State.PENDING;
        remember(pendingSha);
        lastReminderAt = env.now();
        env.chat("[PeakVerse] Das Server-Resource-Pack ist geladen. Es wird aktiv, wenn du dem Server das naechste Mal beitrittst.");
    }

    /** Beitritt zu einem Server: ein wartendes Pack wird jetzt aktiviert. */
    public void onJoin() {
        // Ein Pack aus einer frueheren Sitzung wartet nur auf der Platte: auch das wird jetzt aktiviert
        String sha = state == State.PENDING && pendingSha != null ? pendingSha : state == State.DOWNLOADING ? null : env.pendingSha1();
        if (sha == null || sha.equals(env.activeSha1()) || !env.isCached(sha)) {
            if (sha != null && sha.equals(env.activeSha1())) remember(null);
            return;
        }
        pendingSha = null;
        state = State.IDLE;
        remember(null);
        env.activate(sha);
    }

    /** Einmal je Client-Tick: erinnert im Chat, solange ein Pack wartet. */
    public void tick() {
        if (state != State.PENDING) return;
        long now = env.now();
        if (now - lastReminderAt >= REMINDER_MS) {
            lastReminderAt = now;
            env.chat("[PeakVerse] Erinnerung: Das neue Server-Resource-Pack wird erst nach erneutem Beitritt zum Server aktiv. Verbinde dich neu, um es zu nutzen.");
        }
    }

    public State state() {
        return state;
    }

    public String lastError() {
        return lastError;
    }

    /** Eine Zeile fuer die Diagnose. */
    public String debug() {
        return "Pack: " + state + (pendingSha == null ? "" : " " + pendingSha.substring(0, 8)) + ", aktiv " + (env.activeSha1() == null ? "-" : env.activeSha1().substring(0, 8))
                + (lastError.isEmpty() ? "" : ", letzter Fehler: " + lastError);
    }
}
