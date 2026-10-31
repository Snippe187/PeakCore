package de.peakverse.core.api.web;

import java.util.concurrent.Callable;

/**
 * Der Zugang eines Moduls zur Web-Oberflaeche (Seite im Menue, Schnittstelle unter {@code /api/<modul>/...}, Live-Meldungen, Protokoll).
 * Anmeldung, Sitzungen, Schutz vor Fremdzugriff und Konflikt-Warnung erledigt der Core; ein Modul sieht nur angemeldete Operatoren.
 */
public interface WebRegistry {
    /** Ist die Web-Oberflaeche an? Sonst tun alle anderen Aufrufe nichts. */
    boolean enabled();

    /**
     * Legt die Seite des Moduls ins Menue. {@code script} ist ein JavaScript-Modul (Text), das {@code export default function mount(root, ctx)} liefert;
     * {@code ctx} bietet {@code api(path, options)}, {@code t(key)}, {@code toast(text)}, {@code on(event, fn)} und die Sprache.
     *
     * @param title Menue-Titel (de/en)
     */
    void panel(String titleDe, String titleEn, String script);

    /** Nimmt Anfragen unter {@code /api/<modul>/<pfad>} an. Ein Pfad endet mit {@code /*}, wenn er auch Unterpfade bekommen soll. */
    void route(String pattern, WebHandler handler);

    /**
     * Meldet allen offenen Seiten, dass sich etwas geaendert hat (Live-Aktualisierung), und erhoeht die Revision des Moduls fuer die Konflikt-Warnung.
     * Ereignisname frei waehlbar, z. B. {@code entries}.
     */
    void changed(String event, String admin);

    /** Schreibt eine Zeile ins Aenderungsprotokoll ({@code plugins/PeakCore/web-log.jsonl}). */
    void log(String admin, String action);

    /** Fuehrt Code auf dem Haupt-Thread aus (fuer Bukkit-Zugriffe) und wartet auf das Ergebnis. */
    <T> T onMain(Callable<T> task) throws Exception;

    /** Ein Register, das nichts tut: fuer Module ohne Web-Oberflaeche und fuer Tests. */
    WebRegistry NONE = new WebRegistry() {
        public boolean enabled() { return false; }
        public void panel(String titleDe, String titleEn, String script) { }
        public void route(String pattern, WebHandler handler) { }
        public void changed(String event, String admin) { }
        public void log(String admin, String action) { }
        public <T> T onMain(Callable<T> task) throws Exception { return task.call(); }
    };
}
