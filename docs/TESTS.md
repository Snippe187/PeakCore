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
- [x] Paket von einer echten Mod empfangen und Echo zurueck (Dev-Client: "ping" gesendet, Plugin-Log "Paket von PeakDev: 4 Bytes", Client-Log "4 Bytes vom Server empfangen: ping")
- [x] `/probe send PeakDev` an einen Spieler mit Mod liefert `true` (der Fall "ohne Mod" liefert `false`: noch nicht mit einem echten Spieler ohne Mod geprueft)

## Phase 4 - Handshake und Admin-Pruefung

Automatisch:
- [x] `CorePacketCodecTest` (15): Round-Trips, genaues Format, fremde Protokollversion ohne Lesen des Rests, falsche Richtung, leer/unbekannt/abgeschnitten (jede Schnittstelle), ueberzaehlige Bytes, ungueltige Wahrheitswerte, zu viele Module, ungueltige IDs und Laengen, 20 000 zufaellige Eingaben
- [x] `HandshakeServiceTest` (14) mit Testumgebung: Antwort erst nach Kanal-Anmeldung, sofortige Antwort, Admin-Status im Ack, inkompatible Mod (Hinweis, kein Event, kein Admin), Wechsel auf passende Mod, erneutes Hello, Hello-Flut gedeckelt, Admin-Aenderungen nur einmal und nur an Spieler mit Mod, kein AdminState vor dem Ack, kaputte Pakete, ClientInfo, Austritt, abgeschnittene Modulliste, Diagnosetext
- [x] `FrameRouterTest`: Pakete an Module nur nach Handshake, Core-Frames immer

Auf dem Testserver:
- [x] PeakCore startet mit der neuen Konfiguration (`plugins/PeakCore/config.yml` wird angelegt), `/peakcore players` und `/peakcore status` antworten
- [x] **Mit echter Mod (Phase 5):** Hello kommt an, Ack kommt zurueck, `PlayerHandshakeEvent` im Log (Probe: "Handshake von PeakDev: Mod 0.1.0, Module [probe], Admin: true")
- [ ] **Mit echter Mod:** Hello vor der Kanal-Anmeldung wird erst nach der Anmeldung beantwortet
- [x] **Mit echter Mod:** `/op` und `/deop` aendern den Admin-Status der Mod innerhalb von ca. 2 Sekunden (Client-Log: "Admin-Status geaendert: false" / "true")
- [ ] **Ohne Mod:** Spieler bekommt nach 5 Sekunden den Hinweis im Chat und wird nicht gekickt
- [ ] **Mit Mod anderer Protokollversion:** Spieler bekommt den Hinweis "passt nicht zusammen" und wird nicht gekickt
- [ ] `/peakcore` als Spieler ohne `peakcore.admin` wird verweigert

## Phase 5 - Core-Client-Mod

Automatisch (`./gradlew build`):
- [x] `ClientSessionTest` (19): Hello mit den angemeldeten Modulen, Warten auf den Kanal und Aufgeben nach 10 s (kein PeakCore auf dem Server), Hello sofort nach Kanal-Meldung, Wiederholung alle 2 s bis 10 Versuche, keine Wiederholung nach der Antwort, inkompatibler Server (Mod bleibt inaktiv), Pakete an Module erst nach dem Handshake, fehlerhafter Handler stoert nicht, Senden nur im aktiven Zustand und mit Groessengrenze, Admin-Status (nur aktiv, Meldung nur bei Aenderung), Trennung setzt alles zurueck, spaete Modul-Anmeldung sendet neues Hello, Abmelden, ungueltige und doppelte Module, unerwartete Antworten, 20 000 zufaellige Pakete

Mit echtem Dev-Client (`gradlew :core-mod:runClient`, Server `../run/server` mit PeakCore und PeakCoreProbe):
- [x] Client verbindet, Hello kommt an, Server-Log "Mod meldet sich (Core-Protokoll 1, Server 1, Module: probe 0.1.0)"
- [x] Client-Log: Zustand OFFLINE -> WAITING -> ACTIVE mit Server-Core, Admin-Status und Modulliste
- [x] Echo: Client sendet "ping", das Plugin echot es, der Client protokolliert die Antwort; das Plugin schickt nach dem Handshake zusaetzlich "hallo"
- [x] `/peakcore players`: "PeakDev: Core-Protokoll 1 (kompatibel), Mod 0.1.0, Module: probe 0.1.0"; `/peakcore status`: 2 Pakete empfangen, 2 zugestellt, 0 ungueltig
- [x] `/deop` und `/op` erreichen die Mod innerhalb von etwa 2 Sekunden
- [x] Beim Beenden des Clients meldet sich der Spieler sauber ab
- [ ] Server ohne PeakCore: Mod bleibt still und geht nach 10 s in "kein Server-Support" (nur durch Unit-Test abgedeckt)
- [ ] Mod mit fremder Protokollversion gegen den Server (nur durch Unit-Tests abgedeckt)
- [ ] Spieler ohne Mod bekommt den Chat-Hinweis (braucht einen Client ohne Mod)
- [ ] `/peakcorestatus` im Spiel eingeben (Client-Befehl; ich habe die Ausgabe nur ueber das Log geprueft, nicht im Chat)
- [ ] Spieler ohne `peakcore.admin` darf `/peakcore` nicht benutzen

Hinweis zum Dev-Client: Beim ersten Start in einem neuen Ordner (`core-mod/run`) zeigt Minecraft den Barrierefreiheits-Bildschirm und ignoriert dadurch den automatischen Beitritt. In `core-mod/run/options.txt` `onboardAccessibility:false` setzen.
## Phase 6b - All-in-One-Jar (Server)

Automatisch (`./gradlew build`):
- [x] `ModuleLoaderTest`: Start in Reihenfolge, Beenden umgekehrt, uebersprungenes Modul wird nie gestartet, scheiterndes Modul stoppt die anderen nicht und wird sofort abgebaut, Fehler beim Beenden stoppen die uebrigen nicht, doppelte ID wird abgelehnt
- [x] `../Bundle` `PluginYmlTest`: Name PeakCore, Hauptklasse im Jar, Befehle `peakcore`/`peaksound`/`peaksoundvote`, alle Rechte der Alpha-Version, Audio-Bibliotheken als `libraries`, ServiceLoader-Datei und Modul-Config im Jar

Auf dem Testserver (nur `PeakCore-0.1.0.jar`, Alpha-Testdaten):
- [x] Log: `Modul PeakSound (peaksound) gestartet`; Daten aus `plugins/PeakSound` (73 Bloecke, Songs) geladen, kein neuer Ordner
- [x] `/peakcore modules` zeigt `Eingebaut: PeakSound [peaksound] laeuft`; `/peaksound` und `/peaksoundvote` antworten
- [x] Neue PeakSound-Mod: Weg `PeakCore`, Handshake ACTIVE, Wiedergabe laeuft (Auto-Debug)
- [x] `modules.peaksound.enabled: false`: Modul startet nicht, Core laeuft, `/peakcore modules` nennt den Grund
- [x] Altes `PeakSound`-Jar zusaetzlich in `plugins/`: Fehlermeldung im Log, eingebautes Modul startet nicht, nichts stuerzt ab
- [ ] Alte (Alpha-)Mod gegen das All-in-One-Jar: nicht erneut durchgespielt (Kanal und Code unveraendert, in Phase 6 geprueft)
- [x] Beobachtung geklaert: Die 3 ungueltigen `PlaybackStart`-Pakete aus dem ersten Lauf traten beim zweiten Lauf nicht mehr auf (0 ungueltig, Auto-Debug mit vollem Hex-Dump). Die Settings der Testdaten sind gueltig (Reichweite 16, Lautstaerke <= 100); die genaue Ursache blieb ungeklaert, vermutlich ein Zeitzustand direkt nach dem Neustart. Der Debug-Bericht zeigt Ungueltige Pakete jetzt komplett (128 Bytes).

## Phase 9 - Pack bauen

Automatisch (`PackTest`, 13 Tests):
- [x] Gleiche Dateien in beliebiger Reihenfolge ergeben dieselben Bytes und denselben SHA1; jede Aenderung (Inhalt, Pfad, Beschreibung) aendert den Hash
- [x] SHA1 gehoert zu den ZIP-Bytes; Eintraege sortiert, `pack.mcmeta` immer dabei, `pack.png` optional
- [x] `pack.mcmeta` ist dem Core vorbehalten; ungueltige Pfade (`../`, Grossbuchstaben) werden abgelehnt; zweiter Beitrag zum selben Pfad nennt beide Besitzer
- [x] Ein Modul mit Fehler oder Pfad-Konflikt fehlt **ganz** im Pack, die anderen bleiben; Meldung in `/peakcore pack status`
- [x] Gleicher Inhalt: Datei wird nicht neu geschrieben; hoechstens 5 Packs im Ordner, das aktuelle bleibt

Auf dem Testserver (All-in-One-Jar, `/peakcore pack status|rebuild`):
- [x] Vor dem ersten Bau: Hinweis; Bau: `plugins/PeakCore/pack/pack-<sha1>.zip`; zweiter Bau: "unveraendert"; Datei-SHA1 stimmt mit der Anzeige ueberein
- [ ] **Offen:** ob Minecraft 26.2 die `pack.mcmeta` mit `min_format`/`max_format` `[88, 0]` (ohne `pack_format`) annimmt: erst mit einem echten Client pruefbar (Phase 10/13)
- [ ] Offen: ein echtes Modul mit Dateien (PeakSound liefert noch keine); nur per Unit-Test mit Test-Modulen geprueft

Bekannte Grenzen: Das ZIP ist unkomprimiert (STORED), damit der Hash nicht von der zlib-Version abhaengt; gebaut wird auf dem Haupt-Thread (bei grossen Packs spaeter asynchron).

## Phase 10 - Webserver und Versand

Automatisch (`PackWebTest`, 8 Tests): [x] liefert das Pack (Inhalt, Content-Type), HEAD mit Groesse, 404 fuer alles ausser `/pack/<40 Hex>.zip` (auch `..`, `%2e%2e`, Grossbuchstaben, fremde Dateien im Ordner), 405 fuer POST/DELETE, spaeter gebaute Packs ohne Neustart, belegter Port scheitert sauber, Stopp schliesst den Port, URL-Regeln (`public-url`, `external-url` mit `{sha1}`, Fehler bei falschem Schema)

Auf dem Testserver (Webserver an, Port 8123, `public-url` localhost):
- [x] Start meldet Port und baut das Pack; `GET /pack/<sha1>.zip` liefert 200 `application/zip`, SHA1 der heruntergeladenen Datei stimmt; `/`, `/pack/../x` sind 404
- [x] `/peakcore pack status` zeigt Webserver, Senden und Adresse; `resend` ohne Spieler: "an 0 Spieler"; `rebuild` ohne Aenderung sendet nichts neu
- [ ] **Offen: Annahme beim echten Client.** Der Dev-Client bekam das Pack beim Beitritt angeboten, das Annahme-Fenster wurde aber nicht bestaetigt; es gibt kein "Pack bei PeakDev"-Log. Download, Anwenden und die `pack.mcmeta`-Form (`min_format`/`max_format`) sind damit weiter ungeprueft.
- [ ] Offen: Neusenden nach Aenderung an einen Spieler, der das alte Pack hat (gleiche Pack-ID, soll ersetzen); Verhalten bei `required: true`
- [ ] Offen: von aussen erreichbarer Port (Firewall/Router) und HTTPS: haengt vom echten Server ab

Standard ist alles aus (`pack.web.enabled`, `pack.send.enabled` = false): keine neuen offenen Ports ohne Zutun.
