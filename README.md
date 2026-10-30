# PeakCore

Basis-Plugin fuer die PeakVerse-Module (PeakSound, TexturePeak, ...). Module haengen von PeakCore ab (`depend: [PeakCore]`) und laden ohne PeakCore nicht.

**Stand: Phase 5** - oeffentliche API, Modul-Anmeldung mit Versionspruefung, Kanal `peakcore:main` mit Umschlag (Modul-ID + Nutzdaten), Handshake mit der Mod (Version, Admin-Status, Modulliste), Admin-Pruefung, `/peakcore`, Core-Mod fuer den Client. Der Resource-Pack-Dienst folgt in den naechsten Phasen (siehe `../docs/PLAN.md`). Paketformat: `docs/PROTOCOL.md`.

## Aufbau

| Projekt | Inhalt |
|---|---|
| `core-protocol` | Paketformat (Umschlag, Codec, Modul-ID-Regeln), reine Java-Bibliothek ohne Bukkit, auch fuer die Mod |
| `core-api` | Oeffentliche Schnittstelle fuer Module (`PeakCoreApi`, `ModuleInfo`, `ModuleRegistration`, `ModuleMessageHandler`) |
| `core-client` | Zustandsmaschine der Mod (Handshake, Wiederholung, Modul-Routing), reine Java-Bibliothek ohne Minecraft |
| `core-mod` | Fabric-Mod `PeakCore-<Version>.jar` fuer den Client; haengt die Sitzung an das Fabric-Netzwerk |
| `core-plugin` | Das Plugin (`PeakCore-<Version>.jar`); enthaelt API und Protokoll gleich mit |
| `examples/probe-module` | Beispiel- und Testmodul (nicht Teil des Cores) |

## Benutzung durch ein Modul

```java
PeakCoreApi core = PeakCoreApi.get().orElseThrow();   // ueber den Bukkit-ServicesManager
ModuleRegistration reg = core.registerModule(this, new ModuleInfo("peaksound", "PeakSound", "0.1.0", 1));
reg.setMessageHandler((player, payload) -> { /* Daten der Mod, nicht vertrauenswuerdig */ });
reg.send(player, bytes);   // false, wenn der Spieler keine (kompatible) Mod hat
core.isModded(player);     // Handshake geschafft?
core.isAdmin(player);      // Recht peakcore.admin (Standard: Operatoren), wird bei jedem Aufruf neu geprueft
core.client(player);       // was die Mod gemeldet hat (Version, Module)
```

Abgelehnt wird eine doppelte Modul-ID oder ein Modul, das eine neuere API verlangt (`ModuleInfo.requiredApi`) als das installierte PeakCore anbietet. Wird das Plugin eines Moduls abgeschaltet, meldet PeakCore das Modul automatisch ab.

## Befehle und Rechte

| Befehl | Zweck |
|---|---|
| `/peakcore version` | Version von PeakCore und der API |
| `/peakcore modules` | angemeldete Module |
| `/peakcore status` | Zaehler des Kanals (empfangen, zugestellt, ungueltig, abgewiesen, ...) |
| `/peakcore players` | Spieler online, wer eine kompatible Mod hat, Versionen und Module |

Recht: `peakcore.admin` (Standard: Operatoren).

## Konfiguration (`plugins/PeakCore/config.yml`)

| Schluessel | Standard | Bedeutung |
|---|---|---|
| `non-mod-hint.enabled` | `true` | Chat-Hinweis fuer Spieler ohne Mod (sie werden nie gekickt) |
| `non-mod-hint.delay-ticks` | `100` | so lange wartet der Server auf die Mod, bevor er den Hinweis zeigt |

Ereignis fuer Module: `PlayerHandshakeEvent` (Haupt-Thread) nach erfolgreichem Handshake.

## Bauen

```bash
./gradlew build
```

Oder alle Module gemeinsam: `../tools/build-all.ps1 -Install`.

## Die Mod (Client)

Andere Mods melden ihr Modul in der gemeinsamen Sitzung an, am besten in ihrem Client-Einstieg:

```java
ClientSession session = PeakCoreClient.session();
ClientSession.ModuleChannel channel = session.registerModule("peaksound", "0.1.0", payload -> { /* Daten vom Server */ });
if (channel.send(bytes)) { ... }   // false, solange der Handshake nicht geschafft ist
session.isActive(); session.isAdmin(); session.serverHasModule("peaksound"); session.serverNow();
```

Beim Beitritt schickt die Mod `Hello` (mit allen angemeldeten Modulen), sobald der Server den Kanal meldet, und wiederholt es alle 2 Sekunden bis zu 10-mal. Kennt der Server den Kanal nach 10 Sekunden nicht (kein PeakCore), bleibt die Mod still. Client-Befehl `/peakcorestatus` zeigt den Zustand.

Dev-Client: `./gradlew :core-mod:runClient` verbindet als `PeakDev` mit `localhost:25565` und meldet das Test-Modul `probe` an (Gegenstueck zu `examples/probe-module`).