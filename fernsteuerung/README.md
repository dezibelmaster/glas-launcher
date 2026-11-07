# RC-Fernsteuerung per Handy (2,4 GHz)

Das Handy wird zur Fernsteuerung, ein **ESP32** im Modell ersetzt den Empfänger. Die Verbindung läuft über WLAN
auf 2,4 GHz. Eine App musst du nicht installieren, die Steuerung läuft im Browser (Android und iPhone).

```
 Handy (Browser)  ── WLAN 2,4 GHz ──▶  ESP32 im Modell ──▶ Servo (Lenkung)
 2 Sticks, Scharf-Taste                                 ──▶ Fahrtregler (Gas)
                                                        ──▶ K3, K4 (frei)
```

> Ein Handy kann nicht direkt mit einem fertigen RC-Empfänger (FlySky, FrSky, Spektrum …) sprechen, weil diese
> eigene Funkprotokolle nutzen. Darum ersetzt der ESP32 den Empfänger.

## Einkaufsliste

| Teil | ca. Preis |
|---|---|
| ESP32-Entwicklungsboard (z. B. „ESP32 DevKit V1“, „ESP32-WROOM-32“) | 5–8 € |
| 3–4 Dupont-Kabel (Buchse–Buchse) | 1 € |
| optional 2 Widerstände für die Akkuanzeige (10 kΩ + 4,7 kΩ) | – |

Servos, Fahrtregler (ESC) und Akku hast du schon im Modell.

## Verkabelung

| ESP32 | Anschluss |
|---|---|
| GPIO 18 | K1: Lenkservo (Signal, meist gelb/weiß) |
| GPIO 19 | K2: Fahrtregler (Signal) |
| GPIO 21 | K3: frei (z. B. Höhenruder) |
| GPIO 22 | K4: frei (z. B. Seitenruder) |
| GND | Minus (braun/schwarz) **aller** Servos und des Reglers |
| VIN / 5V | Plus (rot) vom BEC des Fahrtreglers (5–6 V) |

- Die Servos bekommen Plus und Minus wie bisher vom BEC des Reglers, vom ESP32 kommt nur das Signal.
- Alle Massen (GND) müssen verbunden sein.
- 3,3-V-Signale funktionieren mit fast allen Servos und Reglern.
- Pins lassen sich oben im Sketch unter `KANAL_PINS` ändern. Beim ESP32-C3/S3 andere Pins wählen
  (beim C3 sind 18/19 der USB-Anschluss).

## Aufspielen

1. **Arduino IDE** installieren.
2. *Boardverwalter*: „**esp32** by Espressif Systems“ installieren (Version 2.x oder 3.x).
3. *Bibliotheksverwaltung*: „**WebSockets**“ von *Markus Sattler* installieren.
4. Ordner `RC-Fernsteuerung` öffnen, oben im Sketch **WLAN-Passwort ändern**.
5. Board „ESP32 Dev Module“ wählen, Port wählen, hochladen.

## Benutzen

1. Modell einschalten. Die LED am ESP32 blinkt, das heißt: wartet, Failsafe.
2. Am Handy mit dem WLAN **„RC-Modell“** verbinden (Passwort aus dem Sketch).
   Android fragt eventuell „Kein Internet – trotzdem verbunden bleiben?“, dann **Ja** wählen.
3. Im Browser **http://192.168.4.1** öffnen und das Handy quer halten.
4. **GESICHERT** antippen. Daraus wird **SCHARF**, jetzt gibt der Gaskanal Gas.

| Stick | Kanal |
|---|---|
| links hoch/runter | K2 Gas |
| links seitlich | K4 |
| rechts seitlich | K1 Lenkung |
| rechts hoch/runter | K3 |

Die Sticks sind „schwebend“: Wo der Daumen aufsetzt, ist die Mitte. Beide Sticks gehen gleichzeitig (Mehrfinger).

**Tipps**
- **Vollbild** (⛶) auf Android. Auf dem iPhone: *Teilen → Zum Home-Bildschirm*, dann startet die Seite ohne
  Browserleiste.
- Die automatische Displaysperre des Handys hochstellen. Geht das Display aus, wird gesichert und das Modell stoppt.

### Einstellungen (⚙)

- **Modell-Typ:** *Auto/Boot*: Der Gas-Stick federt zur Mitte, Mitte heißt Stopp, Regler mit Rückwärtsgang.
  *Flugzeug*: Der Gas-Stick bleibt stehen, unten heißt Motor aus. Dann im Sketch `FAILSAFE_US` für den Gaskanal auf
  `1000` setzen!
- Pro Kanal: **Richtung umkehren**, **Trimm**, **Weg** (Ausschlag begrenzen, z. B. Gas auf 50 % für Anfänger),
  **Expo**.
- Die Einstellungen werden im Handy-Browser gespeichert.

## Sicherheit

- **Failsafe:** Kommen 0,5 s keine Daten an (Reichweite, App gewechselt, Display aus, Akku leer), setzt der ESP32
  alle Kanäle auf `FAILSAFE_US`.
- **Scharf-Taste:** Ohne „SCHARF“ bleibt das Gas immer auf Failsafe. Scharf schalten geht nur mit Gas in
  Neutralstellung. Nach einem Verbindungsabbruch wird automatisch gesichert.
- Nur ein Handy steuert. Ein zweites kann erst übernehmen, wenn das erste schweigt.
- Vor jeder Fahrt das Modell aufbocken und den Failsafe testen: WLAN am Handy ausschalten, dann muss der Motor stoppen.

## Grenzen

- **Reichweite** ca. 50–100 m mit freier Sicht (Handy-WLAN). Das ist ideal für Autos, Boote und Crawler im
  Garten oder Park. Ein ESP32 mit externer Antenne (z. B. „ESP32-WROOM-32U“) schafft mehr.
- **Latenz** typisch 10–30 ms (wird oben angezeigt).
- Für Flugmodelle, die weit weg fliegen, ist WLAN nicht zuverlässig genug. Dafür ist eine echte RC-Anlage die
  sicherere Wahl.

## Telemetrie

Oben in der App siehst du die Latenz, die Signalstärke beim Modell und, wenn eingerichtet, die **Akkuspannung**.
Dafür einen Spannungsteiler an GPIO 34 anschließen (Akku+ → 10 kΩ → GPIO 34 → 4,7 kΩ → GND) und im Sketch
`AKKU_PIN = 34` setzen. Der Teiler passt für einen 2S-LiPo; am Pin dürfen höchstens 3,3 V ankommen.
