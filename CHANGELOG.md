# Changelog

## 0.1.3 (in Arbeit)
- Pack-Seite: die Dateiliste des Packs zeigt bis zu 30000 Dateien (vorher 5000), damit grosse Packs im Texturen-Inventar vollstaendig erscheinen
- Basis-Pack/Bibliothek: **Overlays** (z. B. bei Vanilla-Tweaks-Packs: overlay_84_88/...) werden jetzt uebernommen: ihre Ordner kommen ins fertige Pack und die overlays-Eintraege in die pack.mcmeta; Ordner, die nicht in der mcmeta des Packs stehen, bleiben draussen. Lose Dateien im Hauptordner (Lizenz, Liesmich, Auswahl-Liste) erzeugen keine rote Meldung mehr

## 0.1.2 (in Arbeit)
- **Handy verbinden**: Auf der Anmeldeseite den Minecraft-Namen eingeben, im Spiel kommt eine Nachricht mit Code und [Erlauben]/[Ablehnen]; danach bleibt das Geraet dauerhaft angemeldet (Schluessel nur als Pruefsumme gespeichert, `devices.json`). Karte "Verbundene Geraete" auf der Server-Seite zum Sperren, im Spiel `/peakweb devices` und `/peakweb revoke <id>`. Wer kein Operator mehr ist und keine Rolle mit Web-Zugang hat, verliert alle Geraete. Config `web.remote-login`, `web.device-expire-days`
- **Pack-Bibliothek**: mehrere fertige Resource-Packs (ZIP bis 250 MB, in Stuecken hochgeladen) in `plugins/PeakCore/pack-library/`, Karten mit Vorschaubild, Format, Groesse und Notiz, eines davon ist das Server-Pack; Wechsel schickt es sofort an alle Spieler online. Pruefung beim Hochladen (Aufbau, pack.mcmeta, Grenzen), automatische Reparatur bei Unterordner oder Windows-Pfaden mit `\`, Anzeige von Ueberschneidungen mit den Modul-Texturen, Umbenennen, Herunterladen (bis 100 MB), Loeschen. Ein bisheriges `base-pack.zip` wird automatisch uebernommen
- **Aenderung**: Bei gleichen Dateien gewinnt jetzt die Textur des Moduls (TexturePeak, PeakSound) gegen das Basis-Pack, damit die Gegenstaende funktionieren (vorher gewann das Basis-Pack)

## 0.1.1
- Mod-Update: die Mods suchen beim Start im Release-Repo nach neueren `*-Mod-<Version>.jar` (mit `.sha256`), melden es beim Beitritt, `/peakcoreupdate` laedt sie, getauscht wird beim Beenden des Spiels (`ModUpdater`, `config/peakcore/mod-update.properties`)
- Web-Oberflaeche: Klickt man weiter, waehrend eine Seite noch laedt, springt die alte nicht mehr ueber die neue zurueck
- Lizenzfeld der Mods auf GPL-3.0-only

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
- API: `PackSink` und `PackPaths` (Pack-Beitrag eines Moduls, strenge Pfadpruefung), `PeakModule.contributePack` (optional; der Core ruft sie ab Phase 9 auf)
- Pack-Bau: deterministisches ZIP (sortiert, fester Zeitstempel, unkomprimiert) mit stabilem SHA1, `pack.mcmeta` aus der Config, Beitraege je Modul mit Konflikt-Erkennung, Ablage in `plugins/PeakCore/pack/`, `/peakcore pack status|rebuild`
- Pack-Versand: eingebauter Webserver (`pack.web.*`, Standard aus), `pack.public-url` oder `pack.external-url`, Senden beim Beitritt und nach Aenderung (`pack.send.*`, Standard aus), `/peakcore pack resend`, Log der Annahme-Status
- Pack-Merge: Basis-Pack (`pack.base-file`) plus Modul-Beitraege; zusammenfuehrbar sind Sprachdateien, sounds.json, Blockstates, Atlanten und Item-Definitionen (select, range_dispatch); Konflikte werden gemeldet (Basis gewinnt, Modul-gegen-Modul: spaeteres Modul fehlt ganz)
- Pack-Versionen (Verlauf, `/peakcore pack versions|rollback`), Testmodus (`pack test on|off|add|remove|list`, `pack publish`), Varianten standard/lite (`PackSink.optionalFile`, `pack variant`, `pack.default-variant`), verlustfreie PNG-Optimierung (`pack.optimize-png`); Zustand in `plugins/PeakCore/pack/state.json`

- Phase 13: Mod laedt das Server-Pack selbst (neues Core-Paket PackOffer), Chat-Erinnerung alle 2 Minuten, Aktivierung beim naechsten Beitritt; Config pack.send.via-mod.

- API: PeakCoreApi.refreshPack() baut das Pack neu und sendet es (fuer Module).

- Web-Oberflaeche (`web.enabled`, Standard aus): Einmal-Link aus `/peakcore web` (auch Konsole: `peakcore web <operator>`), Sitzungen 8 h, Live-Aktualisierung, Konflikt-Warnung, Aenderungsprotokoll, DE/EN, Tag/Nacht, Effekte/Sounds abschaltbar; Modul-API `WebRegistry` (`ModuleContext.web()`)
- Web-Seite Pack: Status, Testmodus mit Testern, Freigabe, Zurueckrollen, Basis-Pack hochladen, Dateiliste mit Vorschau
- Web-Seite Bereiche: eigene Pack-Dateien (`plugins/PeakCore/pack-files/`) fuer Sounds, Texte, Schriften, Partikel, HUD, animierte Texturen, Modelle, Himmel, Titelbild; JSON-Pruefung, Vorlagen, Texteditor, 3D-Vorschau fuer Modelle
- Core-Mod: Taste "PeakVerse Web-Oberflaeche oeffnen" (im Steuerungs-Menue zuweisbar, nur Operatoren)
- Gesamt-Jar: baut die Teil-Jars jetzt immer mit (vorher konnte ein veraltetes Modul-Jar im Gesamt-Jar stecken)
- TexturePeak (eingebaut): Texturen-Eintraege mit Archiv, Faehigkeiten (Effekte, Werte, Partikel), Web-Seite mit Pixel-Editor; `/texturepeak list|archive` mit klickbaren Eintraegen

- Mod: ein geaendertes Server-Pack wird sofort aktiv, kein erneutes Beitreten noetig (F3+T als Reserve).
- Web: neue Oberflaeche mit Kopfleiste, Pixel-Landschaft mit Parallaxe und Tag/Nacht.

- Web: Willkommensseite mit Beschreibung und Sprachwahl (DE/EN/FR/ES fuer den Rahmen), Reiter Server und Module, animiertes TexturePeak-Logo, lebendigere Optik.

- Spieler-Reiter: Rollen, Rechte je Modul, Notizen, Verwarnungen/Sperren, Web-Zugang ohne OP per /peakweb, Sitzung an die IP gebunden.

- Updates: Pruefung eines GitHub-Release-Repos, Download mit SHA-256-Pruefung nach plugins/update/, Neustart mit Countdown (Web und /peakcore update). Sitzungs-IP: localhost (127.0.0.1 und ::1) zaehlt als derselbe Rechner.
