#!/usr/bin/env python3
"""Ersetzt die erzeugten Tier- und Fahrzeuggeräusche des Glas Entdeckers durch echte Aufnahmen.

Die Aufnahmen kommen von Wikimedia Commons (gemeinfrei oder Creative Commons). Aus jeder wird der lauteste
Ausschnitt von wenigen Sekunden geschnitten, ein- und ausgeblendet und auf gleiche Lautstärke gebracht.
Urheber und Lizenz jeder Aufnahme landen in entdecker/src/main/assets/geraeusche/QUELLEN.txt.

Aufruf aus dem Projektordner (braucht numpy, scipy, ffmpeg):
    python3 entdecker/tools/aufnahmen.py            alle
    python3 entdecker/tools/aufnahmen.py bellen     nur diese

Ein anderes Geräusch nehmen: in AUFNAHMEN den Dateinamen von Commons eintragen (ohne "File:") und neu aufrufen.
Geräusche, die hier nicht stehen, erzeugt weiter geraeusche.py.
"""
import html
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

import numpy as np
from scipy.io import wavfile

SR = 22050
HIER = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HIER, ".cache", "aufnahmen")
OUT = os.path.join(HIER, "..", "src", "main", "assets", "geraeusche")
UA = {"User-Agent": "GlasEntdeckerSounds/1.0 (sound credits tool)"}

# Geräusch -> (Datei auf Wikimedia Commons, Länge des Ausschnitts in Sekunden)
AUFNAHMEN = {
    # Tiere
    "bellen": ("Barking of a dog 2.ogg", 3.5),
    "heulen": ("Wolf howls.ogg", 5.0),
    "miauen": ("Felis silvestris catus meows.ogg", 3.5),
    "bruellen": ("Lion raring-sound1TamilNadu178.ogg", 4.5),
    "brummen": ("Bear growl.ogg", 3.5),
    "troeten": ("Elephant voice - trumpeting.ogg", 3.0),
    "affe": ("Pant-hoot call made by a male chimpanzee.ogg", 4.5),
    "wiehern": ("Wiehern.ogg", 3.0),
    "grunzen": ("618483 foleyhaven piglet-squeal-01.flac", 3.0),
    "muhen": ("Single Cow Moo.ogg", 4.0),
    "maehen": ("Sheep bleating.ogg", 3.5),
    "kraehen": ("Rooster crowing.ogg", 4.0),
    "gackern": ("Hen announcing shes lain an egg.ogg", 4.0),
    "quaken": ("Anas platyrhynchos - Mallard XC62258.mp3", 3.5),
    "schnattern": ("Geese Honking (loud).ogg", 3.5),
    "zwitschern": ("Erithacus rubecula - European Robin XC509375.mp3", 4.5),
    "papagei": ("Ara militaris - Military Macaw XC250486.mp3", 3.5),
    "eule": ("Strix aluco male.oga", 5.0),
    "adler": ("Buteo jamaicensis - Red-tailed Hawk XC71575.mp3", 2.5),
    "frosch": ("Single Frog Croak.oga", 3.5),
    "wal": ("Humpback-Whale-Song-and-Foraging-Behavior-on-an-Antarctic-Feeding-Ground-pone.0051214.s001.oga", 5.5),
    "summen": ("Bombus buzz.ogg", 3.5),
    "zirpen": ("Field cricket Gryllus pennsylvanicus.ogg", 3.5),
    "fiepen": ("Guinea Pig Feeding Wheek.ogg", 3.5),
    "hufe": ("Six Horses Galloping By.ogg", 4.0),
    "blubbern": ("Water bubbles chortling.ogg", 3.5),
    # Fahrzeuge
    "hupe": ("Car Horn.wav", 3.0),
    "motor": ("Plymouth Duster - engine and exhaust sound (YouTube FWzja7TuZiw).opus", 4.5),
    "rennauto": ("Williams-Renault FW18 (1996).ogg", 5.0),
    "martinshorn": ("WWS Fireenginesiren.ogg", 5.0),
    "bus": ("WWS CityBusMANSG220horn.ogg", 3.5),
    "traktor": ("WWS TractorUrsusC328engine.ogg", 4.0),
    "dampflok": ("WWS SteamWhistle.ogg", 4.5),
    "zug": ("CC203 Horn.wav", 3.5),
    "flugzeug": ("428086 inspectorj airplane-boeing-flyby-right-to-left-a.wav", 6.0),
    "propeller": ("Helicopter flying overhead.wav", 5.0),
    "schiff": ("Cruise ship Albatros ship horn.ogg", 4.5),
    "motorboot": ("WWS MSThti.ogg", 4.0),
    "klingel": ("Bell-ring.flac", 3.0),
    "moped": ("Moped driving by.ogg", 4.5),
    "sonar": ("Sonar pings.ogg", 3.7),
}


def hole(url, ziel=None):
    for versuch in range(6):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                daten = r.read()
            if ziel:
                with open(ziel, "wb") as f:
                    f.write(daten)
            return daten
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise
            # Commons bremst: so lange warten, wie der Server sagt, sonst immer länger
            time.sleep(min(300, int(e.headers.get("Retry-After") or 0) or 30 * (versuch + 1)))
    raise RuntimeError("zu viele Anfragen: " + url)


def text(wert):
    return re.sub(r"\s+", " ", html.unescape(re.sub("<[^>]+>", "", wert or ""))).strip()


def angaben(titel):
    """Adresse, Urheber und Lizenz einer Datei von Commons."""
    q = urllib.parse.urlencode({"action": "query", "format": "json", "titles": "File:" + titel, "prop": "imageinfo",
                                "iiprop": "url|extmetadata", "iiextmetadatafilter": "LicenseShortName|Artist|LicenseUrl"})
    seite = next(iter(json.loads(hole("https://commons.wikimedia.org/w/api.php?" + q))["query"]["pages"].values()))
    info = seite["imageinfo"][0]
    meta = info.get("extmetadata", {})
    return {
        "url": info["url"],
        "seite": info["descriptionurl"],
        "urheber": text(meta.get("Artist", {}).get("value")) or "unbekannt",
        "lizenz": text(meta.get("LicenseShortName", {}).get("value")) or "siehe Seite",
    }


def ausschnitt(x, laenge):
    """Der lauteste zusammenhängende Teil, höchstens `laenge` Sekunden."""
    n = int(laenge * SR)
    if len(x) > n:
        energie = np.concatenate([[0.0], np.cumsum(x.astype(np.float64) ** 2)])
        schritt = SR // 20
        starts = np.arange(0, len(x) - n, schritt)
        beste = starts[np.argmax(energie[starts + n] - energie[starts])]
        # Etwas früher anfangen, damit der Einsatz des Geräuschs nicht abgeschnitten wird
        beste = max(0, beste - SR // 5)
        x = x[beste:beste + n]
    # Stille am Anfang und Ende wegnehmen
    huelle = np.convolve(np.abs(x), np.ones(SR // 50) / (SR // 50), mode="same")
    laut = np.where(huelle > 0.04 * huelle.max())[0]
    if len(laut):
        x = x[max(0, laut[0] - SR // 20):min(len(x), laut[-1] + SR // 5)]
    ein, aus = min(len(x) // 4, SR // 30), min(len(x) // 3, SR // 4)
    x = x.copy()
    x[:ein] *= np.linspace(0, 1, ein)
    x[-aus:] *= np.linspace(1, 0, aus) ** 2
    return x


def main():
    nur = set(sys.argv[1:])
    os.makedirs(CACHE, exist_ok=True)
    quellen_datei = os.path.join(CACHE, "quellen.json")
    quellen = json.load(open(quellen_datei)) if os.path.exists(quellen_datei) else {}
    for name, (titel, laenge) in AUFNAHMEN.items():
        if nur and name not in nur:
            continue
        roh = os.path.join(CACHE, name + os.path.splitext(titel)[1])
        if os.path.exists(roh) and quellen.get(name, {}).get("titel") == titel:
            a = quellen[name]  # schon geladen: Commons nicht noch einmal fragen
        else:
            try:
                a = angaben(titel)
                time.sleep(3)
                hole(a["url"], roh)
                time.sleep(3)
            except Exception as e:
                print(f"{name:12} FEHLER: {e}")
                continue
        wav = os.path.join(CACHE, name + ".wav")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", roh, "-ac", "1", "-ar", str(SR), wav], check=True)
        _, x = wavfile.read(wav)
        x = x.astype(np.float32)
        x -= x.mean()
        x = ausschnitt(x, laenge)
        x = x / (np.abs(x).max() or 1.0) * 0.89
        wavfile.write(wav, SR, np.int16(x * 32767))
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "4",
                        os.path.join(OUT, name + ".ogg")], check=True)
        quellen[name] = dict(a, titel=titel)
        json.dump(quellen, open(quellen_datei, "w"), ensure_ascii=False, indent=1)
        print(f"{name:12} {len(x) / SR:4.1f} s  {a['lizenz']:14} {a['urheber'][:40]}")

    with open(os.path.join(OUT, "QUELLEN.txt"), "w", encoding="utf-8") as f:
        f.write("Echte Aufnahmen im Glas Entdecker\n"
                "=================================\n\n"
                "Quelle: Wikimedia Commons. Jede Aufnahme wurde gekürzt (lautester Ausschnitt), ein- und ausgeblendet,\n"
                "in Mono umgewandelt und in der Lautstärke angeglichen. Die bearbeiteten Ausschnitte stehen unter\n"
                "derselben Lizenz wie das jeweilige Original.\n"
                "Alle anderen Geräusche in diesem Ordner sind selbst erzeugt (entdecker/tools/geraeusche.py).\n\n")
        for name in sorted(quellen):
            if name not in AUFNAHMEN:
                continue
            q = quellen[name]
            f.write(f"{name}.ogg\n  Original: {q['titel']}\n  Urheber:  {q['urheber']}\n  Lizenz:   {q['lizenz']}\n  {q['seite']}\n\n")


if __name__ == "__main__":
    main()
