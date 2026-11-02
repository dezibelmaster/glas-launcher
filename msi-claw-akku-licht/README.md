# Claw Akku-Licht

Kleines Windows-Programm für den **MSI Claw 8 AI+** (läuft auch auf Claw A1M / 7 AI+): Die RGB-Ringe um die Sticks
zeigen den Akkustand als Farbe an. Standardmäßig von **Grün (100 %)** über Gelb und Orange zu **Rot (20 % und weniger)**.

Jede Stufe in 10-%-Schritten lässt sich mit einer eigenen Farbe belegen:

| Stufe | gilt für | Standardfarbe |
|---|---|---|
| 100 % | 100 % | `#00FF00` grün |
| 90 % | 90–99 % | `#40FF00` |
| 80 % | 80–89 % | `#80FF00` |
| 70 % | 70–79 % | `#BFFF00` |
| 60 % | 60–69 % | `#FFFF00` gelb |
| 50 % | 50–59 % | `#FFC000` |
| 40 % | 40–49 % | `#FF8000` orange |
| 30 % | 30–39 % | `#FF4000` |
| 20 % | 20–29 % | `#FF0000` rot |
| 10 % | 10–19 % | `#FF0000` rot |
| 0 % | 0–9 % | `#FF0000` rot |

## Funktionen

- Farbe pro Stufe per Klick auf das Farbfeld wählen; die Sticks zeigen die neue Farbe 3 Sekunden lang als Vorschau.
- „Vorschau“-Knopf je Stufe, ◀ markiert die aktuelle Stufe.
- Helligkeit 0–100 %, Abfrage-Intervall (5–600 s, Standard 30 s).
- Optional eigene Farbe, solange das Netzteil steckt.
- Blinkt bei niedrigem Akku (Standard: 10 % oder weniger, einstellbar 1–50 %, abschaltbar), nicht während des Ladens.
- Läuft im Infobereich (Symbol in der aktuellen Akkufarbe), Schließen des Fensters minimiert dorthin.
- „Mit Windows starten“ trägt das Programm in den Autostart ein (startet dann unsichtbar im Infobereich).
- Prüft sofort nach dem Start und nach dem Aufwachen aus dem Standby; ist der Controller noch nicht bereit, alle 2 Sekunden erneut.
- Sendet nur bei einem Stufenwechsel und zusätzlich alle 5 Minuten, falls MSI Center M die Farbe überschrieben hat.

Einstellungen und Protokoll liegen in `%APPDATA%\ClawAkkuLicht\`.

## Installation

**Variante A – mit Python** (Python 3.10+ von python.org, beim Installieren „Add python.exe to PATH“ anhaken):
Ordner auf den Claw kopieren und `starten.bat` doppelklicken.

**Variante B – als .exe:** auf einem Windows-PC mit Python `exe_bauen.bat` ausführen und `dist\ClawAkkuLicht.exe` auf
den Claw kopieren.

## Hinweise

- **MSI Center M:** Wenn dort eine Beleuchtung eingestellt ist, kann es die Farbe zurücksetzen. Am besten in MSI Center M
  das Licht auf statisch lassen bzw. dessen Lichtsteuerung nicht verwenden; Claw Akku-Licht setzt die Farbe ohnehin
  regelmäßig neu.
- Die Farbe wird als Profil 1 in den Controller geschrieben. Das Protokoll stammt aus dem quelloffenen
  [Handheld Daemon](https://github.com/hhd-dev/hhd) (Linux). Es ist nicht offiziell von MSI dokumentiert.
- Zeigt das Fenster „MSI-Claw-Controller nicht gefunden“, steht in `protokoll.txt` mehr. Auf älterer Controller-Firmware
  (vor 0x0166) wird automatisch die alte Speicheradresse benutzt.

## Dateien

- `claw_akku_licht.py` – Programm mit Fenster, Infobereich und Akku-Abfrage
- `claw_led.py` – HID-Befehle an den Claw-Controller (VID `0x0DB0`, PID `0x1901`/`0x1902`)
- `einstellungen.py` – Stufen, Standardfarben, Speichern/Laden
