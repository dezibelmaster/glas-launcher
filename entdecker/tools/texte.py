#!/usr/bin/env python3
"""Berechnet die Text-Vektoren für die Bilderkennung des Glas Entdeckers.

Die App vergleicht jedes Kamerabild mit einem Vektor pro Ding (CLIP: Bild und Text im selben Raum). Die Vektoren
der Texte werden hier einmal am Rechner berechnet, damit auf dem Handy nur der Bildteil des Modells laufen muss.

Aufruf aus dem Projektordner, nach jeder Änderung an Katalog.kt oder englisch.py:
    pip install onnxruntime tokenizers numpy
    python3 entdecker/tools/texte.py
Lädt beim ersten Mal das Textmodell (OpenAI CLIP ViT-B/32, MIT-Lizenz, 250 MB) nach entdecker/tools/.cache
und schreibt entdecker/src/main/assets/texte.bin und texte.txt.
"""
import os
import re
import sys
import urllib.request

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

HIER = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HIER)
from englisch import EN, HINTERGRUND  # noqa: E402

REPO = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/"
CACHE = os.path.join(HIER, ".cache")
ASSETS = os.path.join(HIER, "..", "src", "main", "assets")
KATALOG = os.path.join(HIER, "..", "src", "main", "java", "de", "glas", "entdecker", "Katalog.kt")

# Mehrere Sätze pro Begriff, gemittelt: so passt es zu Fotos und zu Spielzeug
SAETZE = ["a photo of a {}.", "a photo of a toy {}.", "a close-up photo of a {}.", "a picture of a {}.",
          "a photo of a small {}.", "a children's toy {}."]


def lade(name):
    os.makedirs(CACHE, exist_ok=True)
    ziel = os.path.join(CACHE, os.path.basename(name))
    if not os.path.exists(ziel):
        print("lade", name)
        urllib.request.urlretrieve(REPO + name, ziel)
    return ziel


def main():
    ids = re.findall(r'^\s*(?:tier|fahrzeug|ding|essen)\("([^"]+)"', open(KATALOG, encoding="utf-8").read(), re.M)
    fehlt = [i for i in ids if not EN.get(i)]
    if fehlt:
        sys.exit("Ohne englischen Begriff in englisch.py: " + ", ".join(fehlt))

    tok = Tokenizer.from_file(lade("tokenizer.json"))
    modell = ort.InferenceSession(lade("onnx/text_model.onnx"))

    def vektor(woerter):
        alle = []
        for w in woerter:
            folgen = []
            for s in SAETZE:
                t = tok.encode(s.format(w)).ids[:77]
                folgen.append(t + [49407] * (77 - len(t)))
            v = modell.run(None, {"input_ids": np.array(folgen, dtype=np.int64)})[0]
            v = v / np.linalg.norm(v, axis=1, keepdims=True)
            m = v.mean(0)
            alle.append(m / np.linalg.norm(m))
        m = np.mean(alle, 0)
        return m / np.linalg.norm(m)

    namen = ids + ["#" + h for h in HINTERGRUND]
    vektoren = [vektor(EN[i]) for i in ids] + [vektor([h]) for h in HINTERGRUND]
    np.stack(vektoren).astype("<f4").tofile(os.path.join(ASSETS, "texte.bin"))
    with open(os.path.join(ASSETS, "texte.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(namen) + "\n")
    print(len(ids), "Dinge,", len(HINTERGRUND), "Hintergründe")


if __name__ == "__main__":
    main()
