package de.peakverse.core.api.stats;

import java.util.UUID;

/**
 * Lesezugriff auf freigegebene Statistik-Werte (zum Beispiel fuer Platzhalter in PeakBoard). PeakStats registriert eine Umsetzung als
 * Bukkit-Dienst; andere Module holen sie mit {@code Bukkit.getServicesManager().load(StatsLookup.class)} und muessen mit {@code null} rechnen
 * (PeakStats ist aus).
 */
public interface StatsLookup {
    /** Eigener Wert eines Spielers, lesbar formatiert ({@code "1 Std 5 Min"}); {@code "-"}, wenn der Wert nicht freigegeben oder unbekannt ist. */
    String value(UUID player, String key);

    /** Name auf Platz {@code rank} (ab 1) der Gesamt-Rangliste; {@code "-"}, wenn es ihn nicht gibt oder der Wert nicht freigegeben ist. */
    String topName(String key, int rank);

    /** Wert auf Platz {@code rank}, lesbar formatiert; {@code "-"} wie oben. */
    String topValue(String key, int rank);
}
