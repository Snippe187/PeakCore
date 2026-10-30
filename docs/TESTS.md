# PeakCore - Testliste

## Phase 2 - Grundgeruest

Automatisch (`./gradlew build`):
- [x] `ModuleInfoTest`: gueltige und ungueltige IDs, Namen, Versionen, API-Angaben
- [x] `ModuleRegistryTest`: Anmelden, Sortierung, doppelte ID, zu neue API, Abmelden nur durch den Besitzer, Abmelden aller Module eines Plugins
- [x] `PluginYmlTest`: plugin.yml ist gueltiges YAML mit allen Pflichtfeldern (entstand, nachdem der Testserver das Plugin wegen eines unquotierten Doppelpunkts in der Beschreibung abgelehnt hatte; ob der Test genau diesen Fehler faengt, habe ich nicht gegengeprueft)

Auf dem Testserver (`../tools/build-all.ps1 -Install`, Server in `../run/server` starten):
- [x] Log: `PeakCore 0.1.0 bereit`, `/plugins` zeigt PeakCore
- [x] `/peakcore version`, `/peakcore modules`, falsche Eingabe zeigt die Benutzung
- [x] Anmeldung eines echten Moduls ueber den ServicesManager (mit dem Probe-Modul, siehe Phase 3)
- [ ] Zugriff ohne Recht `peakcore.admin` wird verweigert (als Spieler ohne OP testen)

## Phase 3 - Kanal und Umschlag

Automatisch:
- [x] `FrameCodecTest` (14): Round-Trip, genaues Drahtformat, leere Nutzdaten, exakte Groessengrenzen in beide Richtungen, zu grosse Pakete, unbekannte Typen, kaputte Kopfe, ungueltige Modul-IDs inkl. Nicht-ASCII, Kopie der Nutzdaten, 20 000 zufaellige Eingaben ohne Absturz
- [x] `FrameRouterTest` (7): richtiges Modul bekommt die Daten, unbekanntes Modul, fehlerhafte Pakete werfen nie, fehlerhafter Handler stoert andere nicht, entfernte Handler, Core-Pakete, zu grosse Pakete vom Client

Auf dem Testserver mit `examples/probe-module` (installieren: `PeakCoreProbe-0.1.0.jar` nach `run/server/plugins`):
- [x] Probe meldet sich an: `/peakcore modules` listet `probe`
- [x] `/probe info`: Grenzen stimmen (1 048 568 zum Client, 32 760 vom Client bei der ID `probe`)
- [x] `/probe duplicate`: doppelte ID wird mit lesbarer Meldung abgelehnt
- [x] `/probe toonew`: Modul mit zu neuer API wird abgelehnt
- [x] `/peakcore status` zeigt die Zaehler (alle 0 ohne Client)
- [x] Beim Abschalten meldet PeakCore das Modul ab (Log: "Modul abgemeldet (Plugin abgeschaltet)")
- [ ] Paket von einer echten Mod empfangen und Echo zurueck (braucht die Core-Client-Mod, Phase 5)
- [ ] `/probe send <Spieler>` an einen Spieler mit Mod liefert `true`, ohne Mod `false` (Phase 5)
