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
- Handshake: Core-Pakete Hello, HelloAck, AdminState; Antwort erst nach Kanal-Anmeldung des Clients; Hinweis bei fremder Protokollversion (kein Kick)
- API: `isModded`, `client`, `isAdmin`, `PlayerHandshakeEvent`, `ClientInfo`; Pakete an Module nur von Spielern mit abgeschlossenem Handshake
- Admin-Status folgt /op und /deop (Abgleich alle 2 Sekunden)
- Chat-Hinweis fuer Spieler ohne Mod (`config.yml`), `/peakcore players`
- Core-Mod fuer den Client (Fabric): Handshake, Modul-Routing (`PeakCoreClient.session()`), Admin-Status, `/peakcorestatus`
- `core-client`: testbare Zustandsmaschine der Mod
- All-in-One-Jar: eingebaute Module (`PeakModule` per ServiceLoader, `ModuleContext` mit eigenem Datenordner, Config und Logger), Schalter `modules.<id>.enabled`, Warnung bei altem Einzel-Plugin, `/peakcore modules` zeigt Zustand der eingebauten Module
- Core-Mod: `PeakCoreSession.whenReady` (unabhaengig von der Startreihenfolge der Mods)
