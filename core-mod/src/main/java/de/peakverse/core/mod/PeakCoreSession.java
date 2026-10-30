package de.peakverse.core.mod;

import de.peakverse.core.client.ClientSession;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Zugang anderer Mods zur gemeinsamen Sitzung mit dem Server (Handshake, Admin-Status, Modul-Routing):
 * <pre>{@code PeakCoreSession.whenReady(session -> session.registerModule("peaksound", VERSION, payload -> ...));}</pre>
 * {@link #whenReady} ist der sichere Weg: Fabric garantiert nicht, dass die Core-Mod vor einer anderen Mod startet. Ist die Sitzung noch nicht angelegt,
 * wird die Aktion nachgeholt, sobald die Core-Mod gestartet ist (noch waehrend des Starts, also vor dem ersten Beitritt, sodass die Modul-ID im Hello steht).
 *
 * <p>Diese Klasse liegt bewusst im gemeinsamen Teil der Mod (nicht im Client-Teil), damit andere Mods sie ohne Umwege einbinden koennen.
 */
public final class PeakCoreSession {
    private static final Object LOCK = new Object();
    private static volatile ClientSession session;
    private static List<Consumer<ClientSession>> waiting = new ArrayList<>();

    private PeakCoreSession() {}

    /** Die Sitzung. @throws IllegalStateException wenn die Core-Mod noch nicht gestartet ist; im Start besser {@link #whenReady} nehmen */
    public static ClientSession get() {
        ClientSession s = session;
        if (s == null) throw new IllegalStateException("PeakCore ist noch nicht gestartet: 'peakcore' unter depends in der fabric.mod.json nennen, im Start whenReady nutzen");
        return s;
    }

    /** Fuehrt die Aktion aus, sobald die Sitzung existiert: sofort, wenn sie schon da ist, sonst beim Start der Core-Mod. */
    public static void whenReady(Consumer<ClientSession> action) {
        ClientSession ready;
        synchronized (LOCK) {
            ready = session;
            if (ready == null) {
                waiting.add(action);
                return;
            }
        }
        action.accept(ready);
    }

    /** Nur fuer die Core-Mod selbst. */
    public static void install(ClientSession newSession) {
        List<Consumer<ClientSession>> pending;
        synchronized (LOCK) {
            session = newSession;
            pending = waiting;
            waiting = new ArrayList<>();
        }
        for (Consumer<ClientSession> action : pending) action.accept(newSession);
    }
}
