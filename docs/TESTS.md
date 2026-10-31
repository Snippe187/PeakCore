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

## Phase 11 - Merge und Konflikte

Automatisch (`PackMergeTest`, 21 Tests):
- [x] Sprachdateien und `sounds.json` werden je Schluessel vereinigt; gleicher Schluessel mit gleichem Inhalt ist kein Konflikt
- [x] Blockstates: `variants` je Zustand vereinigt, derselbe Zustand mit anderem Modell ist ein Konflikt (Meldung nennt den Zustand); `multipart` angehaengt ohne Duplikate; `variants` gegen `multipart` ist ein Konflikt
- [x] Atlanten: `sources` angehaengt ohne Duplikate
- [x] Item-Definitionen: `select` (Faelle nach `when`) und `range_dispatch` (Eintraege nach `threshold`) mit gleicher Eigenschaft vereinigt, `fallback` von wem auch immer es hat; andere Eigenschaft oder anderer Typ ist ein Konflikt
- [x] Gleiche Datei mit gleichem Inhalt ist kein Konflikt; sonstige Pfad-Kollision ist einer
- [x] Konflikt mit dem Basis-Pack: Basis gewinnt, nur diese Datei des Moduls entfaellt; Konflikt zwischen Modulen: das nach ID spaetere Modul fehlt ganz
- [x] Ergebnis (und Hash) haengt nicht von der Reihenfolge der Beitraege ab; kaputtes JSON ist ein Konflikt, kein Absturz
- [x] Basis-Pack lesen: nur gueltige Pfade unter `assets/`, `pack.mcmeta` ignoriert, `pack.png` als Icon, Nicht-ZIP ist ein Fehler

Auf dem Testserver (`pack.base-file: base-pack.zip` mit 4 Eintraegen):
- [x] Pack wird aus dem Basis-Pack gebaut (2 uebernommen), der Eintrag mit Grossbuchstaben wird gemeldet und ausgelassen; `/peakcore pack status` zeigt Basis-Dateien und Zusammengefuehrte
- [ ] **Offen:** Zusammenfuehren im laufenden Plugin. Der Server hatte keinen Modul-Beitrag, die JSON-Zusammenfuehrung (Gson aus dem Server) lief nur in den Unit-Tests, nicht live
- [ ] Offen: ob Minecraft die zusammengefuehrten Item-Definitionen wie gewuenscht anzeigt (echter Client)
- [ ] Offen: Hochladen des Basis-Packs ueber die Weboberflaeche (kommt mit dem Web-Editor); bis dahin legt man die ZIP-Datei von Hand ab
Grenzen: Item-Definitionen werden nur fuer `select` und `range_dispatch` vereinigt (`condition`, `composite` u. a. gelten als Konflikt); Basis-Pack-Grenzen: 20 000 Dateien, 64 MB je Datei, 200 MB gesamt.

## Phase 12 - Versionen, Testmodus, Varianten, PNG

Automatisch (`PackVersionsTest` 18, `PngOptimizerTest` 5):
- [x] Erster Bau macht beide Varianten live und legt Verlaufseintraege an; gleicher Inhalt bewegt keine Zeiger und legt keinen neuen Eintrag an
- [x] Zurueckrollen setzt das Live-Pack und bleibt nach erneutem Bau mit gleichem Inhalt **und nach einem Neustart** bestehen; neuer Inhalt ersetzt es; unbekannte Nummer, falsche Variante und fehlende Datei werden abgelehnt (Live-Pack bleibt)
- [x] Testmodus: neue Packs bekommen nur Tester, alle anderen das Live-Pack; `publish` macht das Test-Pack live; Ausschalten verwirft es; entfernte Tester bekommen das Live-Pack; Packs, die ein Zeiger braucht, werden nie aufgeraeumt
- [x] Variante "lite" laesst optionale Dateien (`PackSink.optionalFile`) weg; ohne optionale Dateien sind beide Varianten dieselbe Datei; Spieler-Variante, Standard aus der Config, ungueltige Config faellt auf "standard"
- [x] Zustand (`state.json`: Testmodus, Tester, Spieler-Varianten, Verlauf) ueberlebt den Neustart; eine kaputte Datei wird als `state.json.kaputt` aufgehoben
- [x] PNG-Optimierung: gleiche Pixel (per ImageIO geprueft), Text-Bloecke entfallen, zweiter Lauf gewinnt nichts, kaputte/abgeschnittene/APNG-Dateien bleiben unveraendert; im Dienst wird die Ersparnis gemeldet und laesst sich abschalten

Auf dem Testserver (Befehle per RCON):
- [x] `pack rebuild` mit geaendertem Basis-Pack: neue Version #3 live (Nummern zaehlen ueber beide Varianten, daher fehlt #2); `versions`, `test on|off|add|list`, `variant`, `rollback standard 1` (danach [LIVE] bei #1), Fehlermeldungen bei unbekannter Nummer und falscher Eingabe
- [ ] **Offen:** Auslieferung der unterschiedlichen Packs an echte Clients (Tester bekommt Test-Pack, Lite-Spieler Lite-Pack): hing am nicht bestaetigten Pack-Empfang aus Phase 10
- [ ] Offen: Lite mit echten Modul-Beitraegen (PeakSound liefert keine Dateien) und PNG-Optimierung mit echten Texturen aus einem Basis-Pack: nur mit Test-PNGs geprueft
- [ ] Offen: Spieler waehlt seine Variante selbst (kommt mit dem Mod-Menue; bis dahin stellt sie der Admin ein)
Grenzen: PNG-Ergebnis haengt etwas von der zlib-Version der Java-Laufzeit ab (nach einem Java-Update kann der Hash wechseln); Verlauf: 50 Eintraege, Dateien: die von Zeigern benutzten plus die juengsten 5.

## Phase 13 - Pack-Empfang in der Mod

Automatisch geprueft (Tests): Paket PackOffer (Kodierung, ungueltige Werte), Zustandslogik der Mod (laden, Cache, Erinnerung alle 2 Minuten, Aktivierung beim Beitritt, Fehler, neueres Angebot ersetzt altes).

Von Hand (echter Client noetig, bisher NICHT geprueft):
1. Server: `pack.web.enabled`, `pack.public-url`, `pack.send.enabled` an, `pack.send.via-mod: true`, Server neu starten.
2. Mit der Mod beitreten: im Chat steht "Das Server-Resource-Pack ist geladen ...", kein Annahme-Dialog.
3. Warten: nach 2 Minuten kommt die Erinnerung, danach alle 2 Minuten.
4. Neu beitreten: Pack ist in den Resource-Pack-Einstellungen als `peakverse-<sha>.zip` aktiv. `/peakcorestatus` zeigt "Pack: IDLE, aktiv <sha>".
5. Ohne Mod beitreten: normaler Annahme-Dialog.
6. `pack.send.via-mod: false`: auch mit Mod kommt der Dialog.

### Phase 13 - Ergebnis im echten Client (31.10.2026, Dev-Client, Minecraft 26.2)

Geprueft und in Ordnung: Die Mod laedt das Pack vom Webserver (SHA1 und Groesse geprueft, Datei in `config/peakcore/packs`), die Chat-Meldung und die 2-Minuten-Erinnerung erscheinen, und ein beim Start wartendes Pack wird beim naechsten Beitritt aktiviert (`resourcepacks/peakverse-<sha>.zip`, `options.txt` enthaelt `file/peakverse-...zip`, `incompatibleResourcePacks` leer, `pack.mcmeta` mit Format 88.0 wurde akzeptiert).

Gefundener und behobener Fehler: Das wartende Pack wurde nur im Speicher gehalten und nach einem Neustart des Spiels nie aktiviert. Jetzt steht es in `config/peakcore/pending-pack.txt`.

Nicht geprueft: ob die Texturen im Spiel sichtbar sind (der Testeintrag `rubin` wurde nicht angesehen), der Weg ueber den normalen Annahme-Dialog fuer Spieler ohne Mod.

## Phase 16/17: Web-Oberflaeche und Pack-Manager

Automatisch (WebAuthTest, WebHostTest): Einmal-Link nur einmal und 5 Minuten, Sperre nach 10 Fehlversuchen/Minute, Sitzung 8 Stunden und sofort weg nach /deop,
API nur mit Sitzung, Aenderungen nur mit Header X-Peak, Konflikt 409 mit Revisionen (X-Force ueberschreibt), Body-Grenze 8 MB (413), Live-Meldung per SSE, Protokoll.

Von Hand (im echten Browser gegen den Testserver geprueft am 31.10.2026):
1. `web.enabled: true` in plugins/PeakCore/config.yml, Server neu starten.
2. Im Spiel `/peakcore web` (oder in der Konsole `peakcore web <operator>`), Link oeffnen: Start-Seite, Menue Start/Pack/Texturen/Protokoll. [geprueft]
3. Texturen: Eintrag im Pixel-Editor zeichnen (Fuellen), als neuen Eintrag anlegen: erscheint in der Liste, Datei in plugins/TexturePeak/textures, Protokoll-Zeile. [geprueft]
4. API: Faehigkeit hinzufuegen, Duplizieren, Archivieren, Zurueckholen, Testmodus an/aus, Pack neu bauen, Dateiliste. [geprueft, ueber die Schnittstelle]
5. Noch NICHT von Hand geprueft: Stift/Radierer/Pipette mit der Maus, PNG-Import, Basis-Pack-Upload, Rollback-Knopf, zwei Admins gleichzeitig mit Konflikt-Dialog, Tag/Nacht und Sprachwechsel.
Grenzen: Ohne HTTPS laufen Einmal-Link und Cookie im Klartext (siehe config.yml). Die OP-Pruefung der Sitzung nutzt /op (nicht das Recht peakcore.admin).

## Phase 18/19: Bereiche und 3D-Vorschau
Automatisch: PackCustomTest (Pfadpruefung, Lesen, eigene Dateien gewinnen im Bau).
Von Hand geprueft (Browser, 31.10.2026): Upload von PNG und Modell-JSON, kaputtes JSON und Grossbuchstaben im Pfad werden abgelehnt, Dateien erscheinen im Live-Pack, Loeschen,
3D-Vorschau eines Modells mit Texturen. Nicht geprueft: dass die Dateien im Spiel wirken (Sounds, Partikel, HUD, Himmel, Titelbild, Animation), Upload ueber die Maske mit grossen OGG-Dateien.
