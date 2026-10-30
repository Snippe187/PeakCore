# PeakCore - Testliste

## Phase 2 - Grundgeruest

Automatisch (`./gradlew build`):
- [x] `ModuleInfoTest`: gueltige und ungueltige IDs, Namen, Versionen, API-Angaben
- [x] `ModuleRegistryTest`: Anmelden, Sortierung, doppelte ID, zu neue API, Abmelden nur durch den Besitzer, Abmelden aller Module eines Plugins
- [x] `PluginYmlTest`: plugin.yml ist gueltiges YAML mit allen Pflichtfeldern (entstand, nachdem der Testserver das Plugin wegen eines unquotierten Doppelpunkts in der Beschreibung abgelehnt hatte; ob der Test genau diesen Fehler faengt, habe ich nicht gegengeprueft)

Auf dem Testserver (`../tools/build-all.ps1 -Install`, Server in `../run/server` starten):
- [x] Log: `PeakCore 0.1.0 bereit (API 1).`, `/plugins` zeigt PeakCore
- [x] `/peakcore version` antwortet mit Version und API
- [x] `/peakcore modules` meldet "Keine Module angemeldet."
- [x] `/peakcore foo` zeigt die Benutzung
- [ ] Anmeldung eines echten Moduls ueber den ServicesManager (folgt mit Phase 6, wenn PeakSound sich anmeldet)
- [ ] Zugriff ohne Recht `peakcore.admin` wird verweigert (als Spieler ohne OP testen)
