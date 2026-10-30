# PeakCore

Basis-Plugin fuer die PeakVerse-Module (PeakSound, TexturePeak, ...). Module haengen von PeakCore ab (`depend: [PeakCore]`) und laden ohne PeakCore nicht.

**Stand: Phase 2** - Grundgeruest: oeffentliche API, Modul-Anmeldung mit Versionspruefung, `/peakcore`. Kanal zur Mod, Handshake und Resource-Pack-Dienst folgen in den naechsten Phasen (siehe `../docs/PLAN.md`).

## Aufbau

| Projekt | Inhalt |
|---|---|
| `core-api` | Oeffentliche Schnittstelle fuer Module (`PeakCoreApi`, `ModuleInfo`, `ModuleRegistration`) |
| `core-plugin` | Das Plugin (`PeakCore-<Version>.jar`); enthaelt die API-Klassen gleich mit |

## Benutzung durch ein Modul

```java
PeakCoreApi core = PeakCoreApi.get().orElseThrow();   // ueber den Bukkit-ServicesManager
ModuleRegistration reg = core.registerModule(this, new ModuleInfo("peaksound", "PeakSound", "0.1.0", 1));
```

Abgelehnt wird eine doppelte Modul-ID oder ein Modul, das eine neuere API verlangt (`ModuleInfo.requiredApi`) als das installierte PeakCore anbietet. Wird das Plugin eines Moduls abgeschaltet, meldet PeakCore das Modul automatisch ab.

## Befehle und Rechte

| Befehl | Zweck |
|---|---|
| `/peakcore version` | Version von PeakCore und der API |
| `/peakcore modules` | angemeldete Module |

Recht: `peakcore.admin` (Standard: Operatoren).

## Bauen

```bash
./gradlew build
```

Oder alle Module gemeinsam: `../tools/build-all.ps1 -Install`.
