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

## Noch nicht enthalten

- Handshake und Mod-Erkennung (Phase 4): bis dahin gibt es keine Pruefung, ob die Mod ein Spieler wirklich hat. Module muessen eingehende Daten wie jede fremde Eingabe behandeln.
- Begrenzung der Paketrate je Spieler.
