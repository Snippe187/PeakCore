# PeakCore - Kanal und Paketformat

Alle Pakete laufen ueber den Plugin-Messaging-Kanal **`peakcore:main`** (Client <-> Server). Spieler ohne Mod melden den Kanal nicht an und bekommen nichts gesendet.

## Pakete

```
Modul-Paket:  [0x01] [Laenge der Modul-ID: 1 Byte] [Modul-ID: ASCII] [Nutzdaten bis zum Ende]
Core-Paket:   [0x02] [Nutzdaten bis zum Ende]
```

- **Modul-ID:** 2-24 Zeichen aus `a-z`, `0-9`, `_`, beginnend mit einem Buchstaben (gleiche Regel wie `ModuleInfo`).
- **Nutzdaten:** frei, Sache des Moduls. Der Core reicht sie nur weiter und kennt ihren Inhalt nicht.
- **Core-Pakete:** Handshake und Steuerung des Cores selbst (Phase 4).

## Grenzen

| Richtung | Groesste Paketgroesse | Nutzdaten (Modul-ID `peaksound`, 9 Zeichen) |
|---|---|---|
| Client -> Server | 32 767 Bytes | 32 756 |
| Server -> Client | 1 048 575 Bytes | 1 048 564 |

(Gesamtgroesse = Kopf + Nutzdaten; der Kopf eines Modul-Pakets ist `2 + Laenge der Modul-ID` Bytes.)

## Streng decodieren

Der Server decodiert nach dem Format oben und verwirft alles andere still (gezaehlt unter `/peakcore status`): leeres oder zu grosses Paket, unbekannter Typ, fehlende oder ungueltige Modul-ID, ID ueber das Paketende hinaus. Ein manipulierter Client kann nichts ausloesen ausser verworfen zu werden. Pakete an unbekannte Module werden ebenfalls verworfen und gezaehlt. Wirft der Handler eines Moduls eine Ausnahme, wird sie protokolliert und beeintraechtigt weder andere Module noch den Kanal.

## Core-Pakete (Handshake)

Core-Pakete laufen als Nutzdaten eines Core-Frames (`[0x02] ...`). Aufbau: `[Typ: 1 Byte] [Felder]`; Zahlen Big-Endian, Strings `[Laenge: 2 Bytes] [UTF-8]`, Listen `[Anzahl: 1 Byte] [Eintraege]` (hoechstens 32).

| Typ | Richtung | Inhalt |
|---|---|---|
| 1 `Hello` | Client -> Server | Core-Protokoll (int), Core-Version der Mod (String), Module der Mod: je Modul-ID und Version |
| 2 `HelloAck` | Server -> Client | Core-Protokoll (int), kompatibel (bool), Admin (bool), Core-Version, API-Version (int), Serverzeit in ms (long), Module des Servers: je ID, Anzeigename, Version |
| 3 `AdminState` | Server -> Client | Admin (bool), wenn sich der Status aendert (z. B. nach /op oder /deop) |

**Fremde Protokollversion:** Die Protokollversion steht in `Hello` und `HelloAck` immer als erstes Feld. Weicht sie von der eigenen ab, wird der Rest des Pakets nicht gelesen. So kann der Server auch dann sauber "passt nicht zusammen" melden, wenn sich das Format zwischenzeitlich geaendert hat.

## Ablauf des Handshakes

1. Die Mod meldet beim Beitritt den Kanal an und schickt `Hello`.
2. Der Server prueft die Protokollversion. Passt sie, gilt der Spieler ab sofort als "mit Mod" (`isModded`), und Pakete an Module werden von ihm angenommen. Passt sie nicht, bekommt er einen Hinweis im Chat; er wird **nie gekickt**.
3. Die Antwort `HelloAck` geht erst raus, wenn der Client den Kanal wirklich angemeldet hat (Paper verwirft Sendungen an Clients ohne Anmeldung still). Kommt das Hello vor der Anmeldung, wartet der Server.
4. Nach der Antwort loest der Core das Bukkit-Event `PlayerHandshakeEvent` aus; Module schicken dem Spieler dort ihren Anfangszustand.
5. Der Admin-Status (Recht `peakcore.admin`) wird alle 2 Sekunden abgeglichen und bei Aenderung als `AdminState` gemeldet. Module pruefen Verwaltungsaktionen immer selbst ueber `PeakCoreApi.isAdmin`, nie anhand der Angabe der Mod.

## Schutz

- Pakete an Module von Spielern ohne abgeschlossenen Handshake werden verworfen (gezaehlt als "abgewiesen").
- Ein Spieler darf hoechstens 100 Hello-Pakete je Sitzung schicken, weitere werden ignoriert.
- Module bekommen nur dann Daten gesendet (`ModuleRegistration.send`), wenn der Spieler den Handshake geschafft hat.

## Noch nicht enthalten

- Begrenzung der Paketrate je Spieler fuer Module-Pakete.
- Resource-Pack-Nachrichten (Phase 9 bis 13).