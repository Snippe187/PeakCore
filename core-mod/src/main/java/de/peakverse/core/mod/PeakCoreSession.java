package de.peakverse.core.mod;

import de.peakverse.core.client.ClientSession;

/**
 * Zugang anderer Mods zur gemeinsamen Sitzung mit dem Server (Handshake, Admin-Status, Modul-Routing):
 * <pre>{@code PeakCoreSession.get().registerModule("peaksound", VERSION, payload -> ...);}</pre>
 * Die Sitzung wird beim Start der Core-Mod angelegt. Mods, die {@code "peakcore"} in ihrer fabric.mod.json unter {@code depends} nennen,
 * starten danach; sie melden ihr Modul am besten gleich in ihrem Client-Einstieg an, damit es im Hello steht, das beim Beitritt geschickt wird.
 *
 * <p>Diese Klasse liegt bewusst im gemeinsamen Teil der Mod (nicht im Client-Teil), damit andere Mods sie ohne Umwege einbinden koennen.
 */
public final class PeakCoreSession {
    private static volatile ClientSession session;

    private PeakCoreSession() {}

    /** Die Sitzung. @throws IllegalStateException wenn die Core-Mod noch nicht gestartet ist (fehlt {@code depends: peakcore}?) */
    public static ClientSession get() {
        ClientSession s = session;
        if (s == null) throw new IllegalStateException("PeakCore ist noch nicht gestartet: 'peakcore' unter depends in der fabric.mod.json nennen");
        return s;
    }

    /** Nur fuer die Core-Mod selbst. */
    public static void install(ClientSession newSession) {
        session = newSession;
    }
}
