package de.peakverse.core.api;

import org.bukkit.entity.Player;

/**
 * Empfaengt die Nutzdaten, die die Mod eines Spielers an ein Modul schickt. Aufgerufen auf dem Haupt-Thread.
 * Die Daten kommen von einem fremden Client und sind <b>nicht vertrauenswuerdig</b>: Format und Werte selbst streng pruefen.
 * Ausnahmen aus dem Handler werden von PeakCore abgefangen und protokolliert; sie beeintraechtigen andere Module nicht.
 */
@FunctionalInterface
public interface ModuleMessageHandler {
    void onMessage(Player player, byte[] payload);
}
