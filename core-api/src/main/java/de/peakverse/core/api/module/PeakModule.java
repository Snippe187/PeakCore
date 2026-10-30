package de.peakverse.core.api.module;

/**
 * Ein eingebautes Modul von PeakCore (z. B. PeakSound, TexturePeak). Module liegen im selben Jar wie der Core und werden per
 * {@link java.util.ServiceLoader} gefunden: In der Datei {@code META-INF/services/de.peakverse.core.api.module.PeakModule} steht der Klassenname.
 *
 * <p>Der Core ruft {@link #enable} nach seinem eigenen Start auf und {@link #disable} beim Abschalten in umgekehrter Reihenfolge.
 * Ob ein Modul laeuft, steht in {@code plugins/PeakCore/config.yml} unter {@code modules.<id>.enabled}.
 */
public interface PeakModule {
    /** Kennung des Moduls, gleichzeitig Modul-ID im Netzwerkprotokoll und Schluessel in der Config (Regeln: {@code ModuleInfo}). */
    String id();

    /** Anzeigename fuer Meldungen und {@code /peakcore modules}. */
    String displayName();

    /** Startet das Modul. Eine Ausnahme schaltet nur dieses Modul ab; der Core und die anderen Module laufen weiter. */
    void enable(ModuleContext context) throws Exception;

    /** Beendet das Modul und speichert seine Daten. Wird auch aufgerufen, wenn {@link #enable} fehlgeschlagen ist. */
    void disable();
}
