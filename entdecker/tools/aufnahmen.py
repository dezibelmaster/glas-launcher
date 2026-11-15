#!/usr/bin/env python3
"""Ersetzt die erzeugten Tier- und Fahrzeuggeräusche des Glas Entdeckers durch echte Aufnahmen.

Die Aufnahmen kommen von BigSoundBank.com (Joseph Sardin, CC0) und von Wikimedia Commons (gemeinfrei oder
Creative Commons). Aus jeder wird der lauteste
Ausschnitt von wenigen Sekunden geschnitten, ein- und ausgeblendet und auf gleiche Lautstärke gebracht.
Urheber und Lizenz jeder Aufnahme landen in entdecker/src/main/assets/geraeusche/QUELLEN.txt.

Aufruf aus dem Projektordner (braucht numpy, scipy, ffmpeg):
    python3 entdecker/tools/aufnahmen.py            alle
    python3 entdecker/tools/aufnahmen.py bellen     nur diese

Ein anderes Geräusch nehmen: in AUFNAHMEN die Nummer von BigSoundBank oder den Dateinamen von Commons eintragen
und neu aufrufen.
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
# BigSoundBank liefert nur an Browser-Kennungen, Wikimedia weist genau die ab und will eine beschreibende Kennung
UA_BROWSER = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36", "Accept": "*/*"}
UA_WIKI = {"User-Agent": "GlasEntdeckerSounds/1.0 (https://github.com; kids app sound credits)"}

# Geräusch -> (Quelle, Länge des Ausschnitts in Sekunden)
# Quelle: eine Datei aus dem Ordner entdecker/tools/aufnahmen (dann in LOKAL eintragen),
#         oder eine Nummer von BigSoundBank.com (vierstellig, steht in der Adresse der Seite: ...-s0283.html)
#         oder der Dateiname auf Wikimedia Commons (ohne "File:")
AUFNAHMEN = {
    # Tiere
    "bellen": ("2955", 3.5),
    "miauen": ("1890", 3.0),
    "muhen": ("2383", 3.5),
    "wiehern": ("0863", 4.0),
    "maehen": ("2343", 2.0),
    "meckern": ("0279", 2.5),
    "iah": ("1550", 4.5),
    "kraehen": ("0283", 3.5),
    "gackern": ("0978", 4.0),
    "quaken": ("0276", 4.0),
    "eule": ("1764", 5.0),
    "frosch": ("0819", 4.0),
    "summen": ("1000", 4.0),
    "zirpen": ("1020", 3.5),
    "kraechzen": ("3463", 3.0),
    "moewe": ("2573", 4.0),
    "hufe": ("0611", 4.0),
    "zwitschern": ("3486", 5.0),
    "heulen": ("Wolf howls.ogg", 5.0),
    "bruellen": ("Lion raring-sound1TamilNadu178.ogg", 4.5),
    "brummen": ("Bear growl.ogg", 3.5),
    "troeten": ("Elephant voice - trumpeting.ogg", 3.0),
    "affe": ("Pant-hoot call made by a male chimpanzee.ogg", 4.5),
    "grunzen": ("pig-sound-effect.mp3", 3.5),
    "schnattern": ("Geese Honking (loud).ogg", 3.5),
    "papagei": ("Ara militaris - Military Macaw XC250486.mp3", 3.5),
    "adler": ("Buteo jamaicensis - Red-tailed Hawk XC71575.mp3", 2.5),
    "wal": ("Humpback-Whale-Song-and-Foraging-Behavior-on-an-Antarctic-Feeding-Ground-pone.0051214.s001.oga", 5.5),
    "fiepen": ("Guinea Pig Feeding Wheek.ogg", 3.5),
    # Fahrzeuge
    "hupe": ("0258", 2.5),
    "motor": ("0189", 5.0),
    "bus": ("3593", 3.5),
    "lkw": ("2721", 3.5),
    "traktor": ("0499", 4.5),
    "bagger": ("2147", 4.0),
    "dampflok": ("3015", 5.5),
    "zug": ("2847", 4.5),
    "strassenbahn": ("3374", 5.0),
    "propeller": ("0264", 5.0),
    "schiff": ("0261", 5.0),
    "klingel": ("0275", 2.5),
    "moped": ("0603", 5.0),
    "piepen": ("1298", 3.5),
    "martinshorn": ("WWS Fireenginesiren.ogg", 5.0),
    "rennauto": ("Williams-Renault FW18 (1996).ogg", 5.0),
    "flugzeug": ("2478", 6.0),
    "motorboot": ("WWS MSThti.ogg", 4.0),
}


# Aufnahmen, die als Datei im Ordner entdecker/tools/aufnahmen liegen (nicht automatisch ladbar)
LOKAL = {
    "pig-sound-effect.mp3": {"urheber": "Free Sounds Library (freesoundslibrary.com)", "lizenz": "CC BY 4.0",
                             "seite": "https://www.freesoundslibrary.com/"},
}


def hole(url, ziel=None):
    for versuch in range(6):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA_WIKI if "wikimedia.org" in url else UA_BROWSER), timeout=60) as r:
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
    """Adresse, Urheber und Lizenz einer Aufnahme."""
    if re.fullmatch(r"\d{4}", titel):
        return {"url": f"https://bigsoundbank.com/UPLOAD/mp3/{titel}.mp3", "seite": f"https://bigsoundbank.com/s{titel}.html",
                "urheber": "Joseph SARDIN - BigSoundBank.com", "lizenz": "CC0"}
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
        roh = os.path.join(CACHE, name + (os.path.splitext(titel)[1] or ".mp3"))
        if titel in LOKAL:
            roh = os.path.join(HIER, "aufnahmen", titel)
            a = dict(LOKAL[titel], url="")
        elif os.path.exists(roh) and quellen.get(name, {}).get("titel") == titel:
            a = quellen[name]  # schon geladen: Commons nicht noch einmal fragen
        else:
            try:
                wiki = not titel.isdigit()
                a = angaben(titel)
                time.sleep(3 if wiki else 0)
                hole(a["url"], roh)
                time.sleep(3 if wiki else 0.5)
            except Exception as e:
                print(f"{name:12} FEHLER: {e}")
                continue
        wav = os.path.join(CACHE, name + ".wav")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", roh, "-ac", "1", "-ar", str(SR), wav], check=True)
        _, x = wavfile.read(wav)
        x = x.astype(np.float32)
        x -= x.mean()
        x = ausschnitt(x, laenge)
        if len(x) < 1.3 * SR:
            # Sehr kurze Rufe (ein "Mäh", ein Hupen) zweimal hintereinander
            x = np.concatenate([x, np.zeros(SR // 4, dtype=np.float32), x])
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
                "Quellen: BigSoundBank.com (Joseph Sardin) und Wikimedia Commons. Jede Aufnahme wurde gekürzt (lautester Ausschnitt), ein- und ausgeblendet,\n"
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
