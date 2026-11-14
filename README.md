<img src="docs/logo.png" width="96" align="right" alt="Glas Launcher Logo">

# Glas Launcher

Werbefreie Startseite für den TCL Google TV: Statusleiste mit Wetter, WLAN, Datum und Uhrzeit, wechselnde Hintergründe
und Apps in einem Dock aus dunklem Milchglas.

Aktuelle Versionen: **Glas Launcher 2.46**, **Glas Browser 1.3**, **Glas Natur-Videos 1.2**.

![Startbildschirm mit Video-Hintergrund, Statusleiste und Dock](docs/screenshots/startbildschirm-video.jpg)

## Bilder

| | |
|---|---|
| ![Große Uhr mit Wetterkarte](docs/screenshots/startbildschirm-uhr.png) | ![Ausführliches Wetter](docs/screenshots/wetter.jpg) |
| Große Uhr mit Wetterkarte und gezeichnetem Wetter-Hintergrund | Ausführliches Wetter: 12 Stunden, 7 Tage, Wind, UV, Luftqualität, Pollen |
| ![Hintergrund mit Vorschau wählen](docs/screenshots/hintergrund-auswahl.jpg) | ![Hintergründe](docs/screenshots/einstellungen-hintergrund.png) |
| Hintergrund mit Live-Vorschau und Favoriten (★) wählen | Wetter-Hintergründe, eigene Bilder und Videos, Plugins |
| ![Kindersicherung](docs/screenshots/einstellungen-kindersicherung.png) | ![Leistung](docs/screenshots/einstellungen-leistung.png) |
| Kindersicherung mit PIN und Zeitfenstern | Arbeitsspeicher anzeigen und freigeben |
| ![Sprachen](docs/screenshots/einstellungen-sprache.png) | |
| Sechs Sprachen | |

## Funktionen

- **Einstellen per Handy:** QR-Code am Fernseher scannen und am Handy Hintergrund, eigene Bilder und Videos (Hochladen
  vom Handy), Dock, Wetterorte, Bildschirmschoner, Kindersicherung, Sprache und Sicherung einstellen. Änderungen
  erscheinen sofort auf dem Fernseher.
- **Statusleiste oben mittig:** Datum, Uhrzeit, Wettersymbol mit Temperatur, WLAN-Signalstärke und Zahnrad zu den
  Fernseher-Einstellungen. Auf Wunsch eingeklappt, dann nur die Uhrzeit oben rechts. Alternativ große Uhr mit Wetterkarte.
- **Dock:** Apps hinzufügen, entfernen und verschieben. Alle Symbole als einheitlich abgerundete Quadrate.
- **Kindersicherung:** Apps, „Alle Apps“ und die Einstellungen mit 4-stelligem PIN sperren. Zeitfenster pro App
  (z. B. 06:00 bis 20:00 Uhr), außerhalb nur mit PIN; solche Apps tragen ⏰.
- **Unwetterwarnungen:** amtliche DWD-Warnungen (über Bright Sky) in Deutschland, sonst aus der Open-Meteo-Vorhersage
  abgeleitet (Gewitter, Sturmböen, Glätte, Starkregen, Schneefall). Der gelbe Hinweis oben erscheint auch bei
  eingeklappter Statusleiste; anklicken zeigt die Einzelheiten.
- **Leistung:** freier Arbeitsspeicher und Verbrauch des Launchers, Speicher auf Knopfdruck oder automatisch beim
  Bildschirmschoner freigeben.
- **Sichern:** alle Einstellungen in eine Datei sichern und wiederherstellen, auch auf einem anderen Fernseher.
- **Updates:** sucht täglich in einem GitHub-Projekt nach einer neuen Version und installiert sie.
- **Sprachen:** Deutsch, English, Français, Español, Italiano, Nederlands. Automatisch wie der Fernseher oder fest gewählt.
- **Hintergründe:** Wetter realistisch (Fotos), realistisch animiert (gezeichnete Wolken, Regen, Schnee, Blitze),
  modern (gezeichnet) oder abstrakt (Farbverläufe), eigenes Bild,
  eigene Videos (einzeln oder abwechselnd, mit Schnellauswahl und Favoriten), Erweiterungen wie „Glas Natur-Videos“ und Projectivy-Plugins.
- **Hintergrund-Leiste:** weiterschalten und mit Live-Vorschau auswählen.
- **Milchglas:** hell, mittel oder dunkel; über Videos einstellbar, wie oft es das Bild übernimmt (Standbild bis 15× pro Sekunde).
- **Bildschirmschoner:** große Uhr mit Datum und Wetter, wandert gegen Einbrennen.
- **Wetter:** beliebige Orte; beim ersten Start fragt der Launcher nach der eigenen Stadt. Hohe Schleierwolken werden herausgerechnet.
- **Startbildschirm:** ersetzt die Google-TV-Oberfläche, auch nach einem Neustart.
- **Glas Browser:** eigene App mit der Firefox-Engine für Videoseiten, mit Firefox-Erweiterungen wie uBlock Origin; Mauszeiger auf der Fernbedienung, Adressen und Text tippst du auf dem Handy ein.

## Bedienung auf dem Fernseher

| Was | Wie |
|---|---|
| App verschieben, entfernen, sperren | Im Dock auf der App **OK gedrückt halten** oder **Menü-Taste** |
| App zum Dock hinzufügen | *Alle Apps* → App OK gedrückt halten → „Zum Dock hinzufügen“ |
| „Alle Apps“ sperren | Auf „Alle Apps“ OK gedrückt halten → „Mit PIN sperren“ |
| Hintergrund weiterschalten | Im Dock **nach oben** → ◀ / ▶ |
| Hintergrund mit Vorschau wählen | Im Dock nach oben → auf den Titel klicken → Liste durchblättern, **OK** übernimmt, **Zurück** bricht ab |
| Favorit markieren | In der Hintergrund-Liste auf einem Eintrag **OK gedrückt halten** oder **Menü-Taste** → ★ |
| Nur Favoriten zeigen | Hintergrund-Liste → „Nur Favoriten wechseln“ |
| Statusleiste | Im Dock nach oben (bei aktiver Hintergrund-Leiste zweimal) |
| Ausführliches Wetter | Statusleiste → Wettersymbol, oder die Wetterkarte bei der großen Uhr |
| WLAN-Einstellungen | Statusleiste → WLAN-Symbol |
| Fernseher-Einstellungen | Statusleiste → ⚙ (mit PIN, wenn die Einstellungen geschützt sind) |
| Statusleiste ein- und ausklappen | Statusleiste → Uhrzeit anklicken; erneut auf die Uhrzeit oben rechts klicken |
| Einstellungen des Launchers | ⚙ **Einstellungen** im Dock |
| Browser öffnen | **Glas Browser** im Dock (auch unter *Alle Apps*) |

Die Hintergrund-Leiste erscheint nur bei Hintergründen mit mehreren Videos oder Bildern. Sie blendet sich nach
5 Sekunden ohne Tastendruck oder beim Wechsel ins Dock wieder aus.

## Einstellungen

- **Sprache:** Automatisch (Sprache des Fernsehers, sonst Englisch) oder eine feste Sprache
- **Handy:** Einstellen per Handy ein- und ausschalten, QR-Code anzeigen
- **Aussehen:** Hintergrund, Milchglas, *Oben anzeigen* (Statusleiste oder große Uhr mit Wetterkarte),
  eigene Bilder und Videos löschen, heruntergeladene Videos löschen
- **Wetter:** Orte suchen und hinzufügen, Hauptort festlegen (bestimmt den Hintergrund), Orte entfernen
- **Dock:** zurücksetzen auf YouTube, Netflix, MagentaTV und Jellyfin
- **Kindersicherung:** PIN festlegen, ändern oder entfernen, gesperrte Apps, „Alle Apps“ und Einstellungen schützen, Zeitfenster
- **Leistung:** Arbeitsspeicher frei/gesamt, Verbrauch des Launchers, jetzt freigeben, automatisch beim Bildschirmschoner freigeben.
  Freigeben beendet Apps, die im Hintergrund ruhen; Android räumt auch selbst auf.
- **Sichern:** Einstellungen sichern nach `Download/GlasLauncher-Einstellungen.json` und wiederherstellen. Für einen anderen
  Fernseher die Datei nach `/sdcard/Android/data/de.glas.launcher/files/` kopieren (z. B. mit Bugjaeger).
- **Updates:** installierte Version, „Neu in Version …“ mit früheren Versionen, Update-Quelle (voreingestellt: `dezibelmaster/glas-launcher`; leer lassen stellt sie zurück), jetzt nach Updates suchen
- **Browser:** Glas Browser installieren, öffnen und aktualisieren, Handy als Fernbedienung (QR-Code von *Einstellen per Handy*)
- **Startbildschirm:** Status, als Standard festlegen, Home-Taste umleiten
- **Bildschirmschoner:** Uhrzeit, Datum und Wetter einzeln ein- und ausschalten (alles aus zeigt nur den Hintergrund), Zeit bis zum Start im Launcher, jetzt anzeigen, System-Bildschirmschoner
- **Fernseher:** Fernseher-Einstellungen öffnen
- **Info:** installierte Versionen von Glas Launcher und Glas Natur-Videos

## Einstellen per Handy

*Einstellungen → Handy → Einstellen per Handy* startet einen kleinen Webserver im Launcher und zeigt einen QR-Code.
Mit der Handy-Kamera scannen (Handy und Fernseher im selben WLAN), die Seite öffnet sich im Browser. Ohne QR-Code
geht es auch mit der angezeigten Adresse, z. B. `http://192.168.178.173:8765/#k7m2p9qa`.

- **Was geht:** Hintergrund (Wetter-Stile, eigene Bilder und Videos), Milchglas, „Oben anzeigen“, eigene Bilder und
  Videos hochladen, als Hintergrund verwenden und löschen, Dock sortieren, Apps hinzufügen und entfernen, Wetterorte
  suchen, Hauptort, Wetterquelle, Bildschirmschoner, Sprache, Sicherung aufs Handy und zurück, Update-Quelle.
- **Kindersicherung:** PIN festlegen oder ändern, Apps sperren, Zeitfenster (auch eigene Uhrzeiten), „Alle Apps“ und
  Einstellungen schützen. Ist ein PIN festgelegt, fragt die Seite ihn zuerst ab, genau wie der Fernseher. Nach fünf
  falschen Versuchen ist eine Minute Pause.
- **Sicherheit:** Die Adresse enthält nach dem `#` einen Zugangsschlüssel, der bei jedem Einschalten neu entsteht.
  Ohne ihn lässt sich nichts lesen oder ändern. Der Fernseher meldet, wenn sich ein Handy verbindet. Der Server läuft
  nur, solange die Funktion an ist, und schaltet sich nach 30 Minuten ohne Nutzung von selbst aus. Die Verbindung ist
  unverschlüsselt (HTTP) und nur für das eigene Heimnetz gedacht.
- **Hochgeladene Dateien** landen in `Movies/GlasLauncher` bzw. `Pictures/GlasLauncher`. Ein Bild mit dem Namen
  eines Wetterfotos (z. B. `clear_day.jpg`) ersetzt dort das eingebaute Foto, solange es noch keine Datei mit diesem Namen gibt.
- **Löschen vom Handy:** Eigene Uploads löscht der Launcher direkt. Dateien, die anders auf den Fernseher kamen
  (z. B. mit Bugjaeger), darf eine App ab Android 10 nicht ohne Weiteres löschen. Ist das USB-Debugging an, löscht der
  Launcher sie über den eigenen Debugging-Zugang (wie bei der Google-TV-Oberfläche). Sonst erscheint am Fernseher
  Androids Frage, ob gelöscht werden darf, und wird mit der Fernbedienung bestätigt.
- Erweiterungen, Projectivy-Plugins und die Google-TV-Oberfläche werden weiter am Fernseher eingestellt.

## Glas Vorleser (Handy-App)

Eigene App fürs Handy (Modul `vorlesen`, Android 7 und neuer): Buchseite vor die Kamera halten, die App erkennt den
Text und liest die Geschichte mit der deutschen Stimme des Handys vor.

- **Lesen:** gelben Knopf drücken, die Seite im Bild wird erkannt und vorgelesen. Der Satz, der gerade gesprochen
  wird, ist im Text unten markiert.
- **Automatik:** Buch einfach ins Bild halten. Sobald eine neue Seite ruhig im Bild ist, wird sie vorgelesen; nach
  dem Umblättern geht es mit der nächsten Seite weiter. Dieselbe Seite wird nicht zweimal gelesen.
- **Doppelseiten:** liegen beide Buchseiten im Bild, wird erst die linke, dann die rechte gelesen. Getrennte Wörter
  am Zeilenende („Geschich-te“) werden zusammengefügt, Seitenzahlen übersprungen.
- **Pause / Weiter, Nochmal, Stopp** und **Tempo** (0,5× bis 1,5×, voreingestellt 0,9×). **Licht** schaltet die
  Taschenlampe ein, Tippen ins Bild stellt scharf.
- Die Texterkennung (Google ML Kit) läuft komplett auf dem Handy, ohne Internet und ohne Konto; nichts wird
  hochgeladen. Fehlt die deutsche Stimme, führt ein Tippen auf den Hinweis zur Installation.

Installieren: `GlasVorleser-1.0.apk` aufs Handy laden und öffnen (Installation aus unbekannten Quellen erlauben),
oder `adb install -r GlasVorleser-1.0.apk`.

## Bauen

```
./gradlew assembleDebug
```

Das Projekt bringt Gradle selbst mit (`gradlew`, Gradle 9). Gebraucht werden Java 17 und das Android-SDK mit
Plattform 35 und 37.2 (der Glas Browser baut gegen das neueste SDK, weil GeckoView es verlangt).

- Launcher: `app/build/outputs/apk/debug/GlasLauncher-<Version>.apk`, z. B. `GlasLauncher-2.46.apk`
- Browser: `browser/build/outputs/apk/debug/GlasBrowser-<Version>-<Prozessor>.apk`, je eine Datei für
  `arm64-v8a` (64 Bit) und `armeabi-v7a` (32 Bit), etwa 100 MB
- Erweiterung: `videos/build/outputs/apk/debug/GlasNaturVideos-<Version>.apk`
- Vorleser (Handy-App): `vorlesen/build/outputs/apk/debug/GlasVorleser-<Version>.apk`

Die Versionsnummer steckt immer im Dateinamen. Die fertigen APKs liegen auch im Projektordner.

**Signaturschlüssel:** `keystore/glas-launcher.keystore` (Alias `androiddebugkey`, Passwort `android`). Alle Versionen
werden damit signiert, so lassen sich Updates von jedem Rechner aus über die alte Version installieren. Den Ordner
`keystore` nicht löschen. Da der Schlüssel öffentlich im Projekt liegt, ist er nur für den privaten Gebrauch gedacht.

**Updates über GitHub:** Ein Release mit dem Tag `v2.46` anlegen und `GlasLauncher-2.46.apk` anhängen. Der Launcher
sucht von sich aus in `dezibelmaster/glas-launcher` (änderbar unter *Einstellungen → Updates*) und meldet die neue Version.
Die beiden `GlasBrowser-…apk` nur anhängen, wenn sich der Browser geändert hat: Der Launcher sucht sie in den letzten
15 Releases und nimmt die neueste, die zum Prozessor des Fernsehers passt.

**Was neu ist:** Steht in `app/src/main/assets/changelog.json` (alle sechs Sprachen) und erscheint unter
*Einstellungen → Updates → Neu in Version …*. Der Text eines GitHub-Releases wird vor dem Installieren angezeigt.

**TCL-Fernseher:** Dort zeigt *Installieren unbekannter Apps* den Glas Launcher manchmal als „Zugelassen“, obwohl
Android die Erlaubnis nicht erteilt hat. Abhilfe einmalig per Bugjaeger-Shell bzw. `adb shell`:

```
appops set de.glas.launcher REQUEST_INSTALL_PACKAGES allow
```

## Installieren oder aktualisieren

Einmalig am Fernseher:

1. *Einstellungen → System → Info* → 7× auf *Android TV-Betriebssystem-Build* drücken.
2. *Einstellungen → System → Entwickleroptionen* → **USB-Debugging** einschalten.

Bei Updates bleiben Dock, PIN und Einstellungen erhalten.

**Vom Android-Handy mit Bugjaeger** (ohne Computer)

1. App **„Bugjaeger Mobile ADB“** aus dem Play Store installieren. Handy und Fernseher im selben WLAN.
2. Auf das **Stecker-Symbol** tippen, Adresse `192.168.178.173`, Port `5555`, verbinden.
   Am Fernseher „USB-Debugging zulassen?“ mit **Zulassen** bestätigen.
3. APK installieren: Reiter mit dem **Paket-Symbol** → *Installieren* → APK auswählen.
4. Befehle: Reiter **Shell**. Dort **ohne** das `adb shell` davor eingeben.

**Vom Computer**

```
adb connect 192.168.178.173
adb install -r GlasLauncher-2.46.apk
adb install -r GlasNaturVideos-1.2.apk
```

## Startbildschirm festlegen

Google TV bietet keine Auswahl für den Startbildschirm. Es gibt zwei Wege.

**A) Google-Oberfläche abschalten (empfohlen)**

Danach startet der Fernseher direkt im Glas Launcher, auch nach einem Neustart.

**Direkt im Launcher (ab 2.35):** *Einstellungen → Startbildschirm → Google-TV-Oberfläche* → Ausschalten bzw.
Einschalten. Der Launcher verbindet sich dafür mit dem Debugging-Zugang des Fernsehers selbst (127.0.0.1:5555, wie
Bugjaeger), mit der Bibliothek [dadb](https://github.com/mobile-dev-inc/dadb) (Apache-2.0). Voraussetzung: USB-Debugging
in den Entwickleroptionen ist an. Beim ersten Mal fragt der Fernseher „USB-Debugging zulassen?“ → „Immer zulassen“ und
„Zulassen“ wählen.

**Von Hand:** In der Bugjaeger-Shell (ohne `adb shell`) oder am Computer:

```
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

Erfolgreich, wenn jeweils `new state: disabled-user` erscheint. Nach großen System-Updates gegebenenfalls wiederholen.

Rückgängig:

```
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
```

**B) Home-Taste umleiten (ohne adb)**

*Einstellungen → System → Bedienungshilfen* → „Glas Launcher: Home-Taste umleiten“ einschalten.
Erscheint „Eingeschränkte Einstellung“: *Einstellungen → Apps → Glas Launcher* → ⋮ → „Eingeschränkte Einstellungen zulassen“,
danach erneut einschalten. Nach einem Neustart öffnet sich der Glas Launcher von selbst, vorher ist kurz die
Google-Oberfläche zu sehen.

Per adb geht es auch:

```
adb shell settings put secure enabled_accessibility_services de.glas.launcher/.HomeRedirectService
adb shell settings put secure accessibility_enabled 1
```

## Fire TV Stick

Läuft ab Fire OS 6 (Android 7.1), also auch auf dem ersten Fire TV Stick 4K. Einschränkungen:

- **Startbildschirm:** Amazon erlaubt keinen anderen Startbildschirm, und das Abschalten der Amazon-Oberfläche per adb
  ist auf neueren Fire-OS-Versionen gesperrt. Die Home-Taste-Umleitung (*Einstellungen → Bedienungshilfen*) erkennt
  die Amazon-Oberfläche (`com.amazon.tv.launcher`); ob Amazon das zulässt, hängt von der Fire-OS-Version ab.
- Manche Amazon-eigenen Apps erscheinen nicht unter „Alle Apps“.
- Installation: *Einstellungen → Mein Fire TV → Info* → 7× auf den Gerätenamen → *Entwickleroptionen* → **ADB-Debugging**
  einschalten, dann mit Bugjaeger verbinden (IP unter *Mein Fire TV → Info → Netzwerk*).

## Bildschirmschoner systemweit

Damit der Glas-Bildschirmschoner auch in anderen Apps erscheint:

```
adb shell settings put secure screensaver_components de.glas.launcher/.ClockDream
```

## Eigene Bilder und Videos

Dateien auf den Fernseher kopieren, dann in den Einstellungen unter *Hintergrund* auswählen:

```
adb push urlaub.jpg /sdcard/Pictures/
adb push aquarium.mp4 /sdcard/Movies/
```

**Mehrere eigene Videos:** Unter *Hintergrund → Eigenes Video …* gibt es „Alle eigenen Videos abwechselnd“. Wie bei
den Plugins schaltest du dann im Dock **nach oben** mit ◀ / ▶ weiter oder wählst mit Live-Vorschau. Videos lassen sich
dort mit **OK gedrückt halten** oder der **Menü-Taste** als Favorit (★) markieren, und „Nur Favoriten wechseln“ spielt
nur diese ab. Auch bei einem einzelnen gewählten Video stehen die anderen in der Schnellauswahl bereit.

**Videoformat:** Am flüssigsten laufen Videos in 1080p mit 25 oder 30 Bildern pro Sekunde, ohne HDR, als MP4 (H.264
oder HEVC). Bei 4K, 60 Bildern pro Sekunde oder HDR zeigt der Launcher beim Auswählen einen Hinweis.
Beim Öffnen einer App gibt der Launcher den Video-Decoder sofort frei (viele Fernseher haben nur einen für 4K)
und setzt das Video bei der Rückkehr an derselben Stelle fort.

**Löschen:** *Einstellungen → Aussehen → Eigene Bilder und Videos verwalten* → Datei wählen → „Löschen“.
Ab Android 10 fragt das System bei Dateien, die nicht vom Launcher stammen (z. B. per `adb push` kopiert), zusätzlich
selbst nach. Ist die gelöschte Datei der Hintergrund, zeigt der Launcher wieder das Wetter.
*Heruntergeladene Videos löschen* leert den Zwischenspeicher der Erweiterungen; eigene Dateien bleiben erhalten.

Eigene **Wetterfotos** ersetzen die eingebauten, wenn sie in `/sdcard/Pictures/GlasLauncher/` liegen und so heißen:
`clear_day`, `partly_day`, `overcast_day`, `fog_day`, `rain_day`, `snow_day`, `storm_day`, Nacht z. B. `clear_night`,
`partly_night`, `rain_night` (jeweils `.jpg`). Fehlt ein Nachtfoto, wird das Tagfoto abgedunkelt.

## Wetter

**Wetter-Ansicht:** jetzt, nächste 12 Stunden mit Temperaturverlauf, 7 Tage, Wind, Luftfeuchtigkeit und Luftdruck,
UV-Index, Luftqualität (europäischer Index), Pollen (Birke, Gräser, Beifuß) sowie Sonnenauf- und -untergang.
Oben lässt sich zwischen den Orten umschalten. Luftqualität und Pollen kommen von der Open-Meteo-Air-Quality-API.

- Quelle: [Open-Meteo](https://open-meteo.com), kostenlos und ohne Konto, Ortssuche über die Open-Meteo-Geocoding-API.
- Aktualisierung höchstens alle 15 Minuten; die Wetterkarte zeigt „Stand“ mit Uhrzeit.
- Bei trockenem Wetter rechnet der Launcher den Himmelszustand selbst aus den Wolkenschichten: tiefe Wolken zählen voll,
  mittlere zu 80 %, hohe Schleierwolken nur zu 30 %. So erscheint ein blauer Himmel mit Federwolken nicht als „Bedeckt“.

**Wetterquellen:** *Einstellungen → Wetter → Wetterquelle*: Automatisch (Open-Meteo, empfohlen), Deutscher Wetterdienst
(über [Bright Sky](https://brightsky.dev), nur Deutschland) oder [Met.no](https://api.met.no) (weltweit). Antwortet die
gewählte Quelle nicht, springt automatisch eine andere ein; die Wetteransicht zeigt oben rechts die Quelle. Bei DWD
und Met.no berechnet der Launcher Sonnenauf- und -untergang selbst; Luftqualität und Pollen kommen immer von Open-Meteo,
der UV-Index fehlt beim DWD.

**Unwetterwarnungen:** in Deutschland amtliche Warnungen des Deutschen Wetterdienstes über
[Bright Sky](https://brightsky.dev), sonst aus der stündlichen Open-Meteo-Vorhersage der nächsten 12 Stunden
(Gewitter, Böen ab 60 km/h, Glätte, Starkregen, starker Schneefall). Abruf alle 30 Minuten.

## Glas Browser

Ein Browser für Videoseiten wie YouTube, ARD- und ZDF-Mediathek, arte, 3sat, Vimeo oder Dailymotion – als eigene
App (`de.glas.browser`, Modul `browser`) mit **GeckoView**, der Firefox-Engine von Mozilla. Dadurch laufen
**Firefox-Erweiterungen** von [addons.mozilla.org](https://addons.mozilla.org), allen voran uBlock Origin gegen Werbung.

**Installieren:** *Einstellungen → Browser → Glas Browser installieren*. Der Launcher holt die passende Datei für den
Prozessor des Fernsehers aus den GitHub-Releases (etwa 100 MB) und öffnet den Installer; *Glas Browser aktualisieren*
holt später neuere Versionen. Danach liegt der Browser im Dock (an Stelle des früheren eingebauten Browsers) und lässt
sich wie jede App verschieben, entfernen oder mit PIN und Zeitfenster sperren. Er braucht mindestens Android 8;
der alte Fire TV Stick mit Fire OS 6 bleibt ohne ihn.

**Erweiterungen:** im Browser 🧩 (oder Menü → *Erweiterungen …*). Beim ersten Start bietet der Browser uBlock Origin an.
Empfohlen sind uBlock Origin, SponsorBlock, Consent-O-Matic (Cookie-Banner), Dark Reader, Return YouTube Dislike und
Privacy Badger; *Andere von addons.mozilla.org* nimmt den Namen aus der Adresse einer Erweiterung, z. B. `ublock-origin`.
Vor dem Installieren zeigt der Browser, was die Erweiterung darf. Ausschalten, entfernen und ihre Einstellungen öffnen
geht über 🧩 → Erweiterung. Bequemer tippen: am Handy unter *Einstellen per Handy* → *Browser* → *Erweiterungen*.

**Mit der Fernbedienung des Fernsehers**

| Was | Wie |
|---|---|
| Zeiger bewegen | Pfeiltasten (gedrückt halten wird schneller) |
| Klicken | **OK** (gedrückt halten = lange drücken) |
| Blättern | Zeiger an den Bildschirmrand schieben und weiter drücken |
| Eine Seite zurück, Vollbild verlassen | **Zurück** |
| Adresse, YouTube-Suche, Favoriten, Erweiterungen, Zeiger an/aus, Ansicht, Handy als Fernbedienung | **Menü-Taste** oder ☰ oben rechts |
| Erweiterungen | 🧩 oben rechts |
| Video im Vollbild | ◀ / ▶ spulen 10 Sekunden, **OK** Pause/Weiter, ▲ / ▼ holen den Zeiger zurück |
| Wiedergabe | Play/Pause-, Vor- und Zurückspul-Tasten der Fernbedienung |

**Videos:** Startet ein großes Video mit Ton, wechselt der Browser von selbst ins Vollbild (*Menü → Videos automatisch im
Vollbild*). Stumme Vorschauen, z. B. auf der YouTube-Startseite, bleiben klein. Lässt eine Seite kein echtes Vollbild
zu, wird das Video auf den ganzen Bildschirm vergrößert. **Zurück** verlässt das Vollbild; dieses Video bleibt dann klein.
*Menü → Videoqualität höchstens* (720p, 1080p, 1440p oder 4K, Standard 1080p) sagt den Seiten, welche Auflösung der
Browser höchstens abspielt; bis 1440p sind auch AV1 und HDR aus. Viele Fernseher zeigen bei 4K-Videos im Browser nach
kurzer Zeit nur noch ein grünes Bild, mit 1080p schicken YouTube und andere ein Format, das sicher läuft.

Die Startseite zeigt die Favoriten als Kacheln. ☆ oben merkt die aktuelle Seite.
Läuft ein Video, bleibt der Fernseher wach; auf einer stehenden Seite schaltet er wie gewohnt ab. Geschützte
Videos (DRM) spielt der Browser ab, soweit Firefox sie auf Android unterstützt; Netflix und ähnliche Dienste nur in
ihrer eigenen App. Seitenabstürze fängt der Browser ab und lädt die Seite neu. *Mehr → Browserdaten löschen* entfernt
Anmeldungen, Cookies und Verlauf; Erweiterungen und Favoriten bleiben.

**Handy als Fernbedienung**

Die Steuerung steckt in *Einstellen per Handy*: Die Seite hat oben die Reiter **Browser** und **Einstellungen**.

1. Handy und Fernseher ins selbe WLAN.
2. Im Browser 📱 oben rechts (oder ☰ → *Handy als Fernbedienung …*, *Einstellungen → Browser* bzw. *→ Handy*) zeigt
   den QR-Code von *Einstellen per Handy*. Mit der Handy-Kamera scannen. Solange es läuft, steht der Code auch auf der
   Startseite des Browsers.
3. Oben **Browser** wählen. Die Seite merkt sich den Reiter.

Auf dem Handy: Adresse oder Suchbegriff eintippen und *Öffnen* (öffnet den Browser am Fernseher, auch vom
Startbildschirm aus), *Auf YouTube suchen*, Touchpad für den Zeiger (wischen, tippen = klicken, zwei Finger oder der
rechte Rand blättern), Zurück/Vor/Neu laden/Start, Video ±10 s und Pause, Lautstärke, Text in ein Eingabefeld der
Seite schreiben (z. B. Suchbegriff, Anmeldung), Favoriten öffnen, merken und löschen, Pfeiltasten.

- Es gelten die Regeln von *Einstellen per Handy*: Zugang nur mit dem Schlüssel aus dem QR-Code, Ende nach 30 Minuten
  ohne Nutzung.
- Ist der Browser gesperrt oder außerhalb seines Zeitfensters, fragt das Handy nach dem PIN, bevor es ihn öffnet.
- Läuft gerade eine andere App, lässt Android das Öffnen des Browsers vom Handy aus manchmal nicht zu. Dann den
  Browser am Fernseher über das Dock öffnen.

## Erweiterungen

`GlasNaturVideos.apk` (Modul `videos`) bringt 12 ruhige Naturvideos von Wikimedia Commons (CC0) mit, in Echtzeit und
mit fester Kamera, ohne Zeitraffer oder Kamerafahrten: Nordseestrand, Seemündung, Möwen auf der Sandbank, Bach,
drei Wasserfälle und fünf Winterszenen.

Danach unter *Hintergrund* „Glas Natur-Videos …“ wählen: ein Video in Schleife oder „Alle Videos abwechselnd“.
Jedes Video wird beim ersten Abspielen heruntergeladen (zusammen etwa 320 MB) und danach vom Fernseher abgespielt.
Der Speicher ist auf 800 MB begrenzt; ältere Videos werden automatisch gelöscht.

**Eigene Erweiterung bauen:** eine App mit einem exportierten Content Provider, der auf die Aktion
`de.glas.launcher.WALLPAPER_PROVIDER` hört. Der Launcher fragt `content://<authority>/wallpapers` ab und liest die Spalten
`title`, `subtitle`, `type` (`video` oder `image`) und `uri` (https, content oder file). Beispiel: `videos/`.
Damit der Launcher eine neue Erweiterung sieht, muss ihre Authority außerdem in `app/src/main/AndroidManifest.xml`
unter `<queries>` stehen.

## Projectivy-Plugins

Der Launcher spricht die öffentliche Plugin-Schnittstelle von Projectivy
([spocky/projectivy-plugin-wallpaper-provider](https://github.com/spocky/projectivy-plugin-wallpaper-provider), Apache 2.0).
Installierte Plugins erscheinen unter *Hintergrund* als „Plugin: …“ mit drei Punkten: als Hintergrund verwenden,
Wechsel-Intervall (1 bis 60 Minuten) und Einstellungen des Plugins.

*Hintergrund → Plugins installieren …* lädt die neueste APK von der GitHub-Seite des Entwicklers und öffnet den Installer:

| Plugin | Quelle |
|---|---|
| Overflight | spocky/projectivy-plugin-wallpaper-overflight |
| Bing-Foto des Tages | spocky/projectivy-plugin-wallpaper-bingwallpaper |
| Aerial Views | theothernt/AerialViews |
| Jellyfin Wallpapers | Mikescher/projectivity-jellyfin-wallpaper |

- Beim ersten Mal fragt der Fernseher, ob der Glas Launcher Apps installieren darf.
- Plugin-Videos werden gestreamt, auch über http (z. B. Overflight), Bilder zwischengespeichert.
- Lottie- und animierte Hintergründe werden übersprungen.
- Ein von Hand gewählter Hintergrund bleibt stehen (📌), auch nach einem Neustart. „Zufällig wechseln“ schaltet den automatischen
  Wechsel wieder ein.
- Favoriten (★) stehen in der Liste ganz oben; „Nur Favoriten wechseln“ wechselt nur zwischen ihnen. Gilt auch für Glas Natur-Videos.

## Verhalten nach dem Einschalten

Ist das WLAN beim Einschalten noch nicht verbunden, wartet der Launcher darauf und startet das Hintergrundvideo dann
von selbst. Bei anderen Fehlern versucht er es bis zu viermal (nach 3, 6, 9 und 12 Sekunden). Plugins, die noch keine
Liste liefern, werden nach 20 Sekunden erneut gefragt.

## Übersetzungen

Die Texte stehen auf Deutsch im Code und laufen durch `tr("…")`. Die Übersetzungen liegen in
`app/src/main/assets/i18n/<sprache>.json` (Glas Browser: `browser/src/main/assets/i18n/`) und ordnen jedem deutschen Text den übersetzten zu; Platzhalter
heißen `{0}`, `{1}`. Fehlt ein Eintrag, erscheint der deutsche Text. Eine neue Sprache braucht eine solche Datei
und einen Eintrag in `I18n.languages`. Amtliche Unwetterwarnungen gibt der Deutsche Wetterdienst nur auf Deutsch
und Englisch heraus; in den anderen Sprachen erscheinen sie auf Englisch.

## Quellen

- Wetterfotos: Wikimedia Commons, CC0. Quellen: `app/src/main/assets/weather/CREDITS.txt`
- Natur-Videos: Wikimedia Commons, CC0. Adressen: `videos/src/main/java/de/glas/launcher/videos/VideoProvider.kt`
- Plugin-Schnittstelle: Projectivy Wallpaper Provider API, Apache 2.0
- Glas Browser: [GeckoView](https://mozilla.github.io/geckoview/) von Mozilla, Mozilla Public License 2.0
- Wetterdaten: Open-Meteo
