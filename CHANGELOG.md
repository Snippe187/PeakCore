# Changelog

## 0.1.0 (in Arbeit)
- Grundgeruest: `core-api` und `core-plugin`
- API ueber den Bukkit-ServicesManager: `PeakCoreApi`, `ModuleInfo`, `ModuleRegistration`
- Modul-Anmeldung mit Pruefung von ID und API-Version; automatische Abmeldung beim Abschalten des Plugins
- Befehl `/peakcore version|modules`, Recht `peakcore.admin`
- Kanal `peakcore:main`: Umschlag mit Modul-ID, strenger Codec, Groessengrenzen je Richtung (Projekt `core-protocol`)
- Module empfangen Nutzdaten per `ModuleMessageHandler` und senden per `ModuleRegistration.send`; Fehler in einem Modul stoeren andere nicht
- `/peakcore status` mit Kanal-Zaehlern
- Beispielmodul `examples/probe-module`
