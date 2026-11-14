#!/usr/bin/env python3
"""Erzeugt alle Geräusche des Glas Entdeckers per Klangsynthese (keine fremden Aufnahmen, keine Lizenzfragen).

Aufruf aus dem Projektordner:  python3 entdecker/tools/geraeusche.py
Braucht numpy, scipy und ffmpeg. Schreibt entdecker/src/main/assets/geraeusche/<name>.ogg

Eigene, echte Aufnahmen gehen auch ohne neu zu bauen: siehe README (Ordner Geraeusche auf dem Gerät).
"""
import os
import subprocess
import sys
import tempfile

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfilt

SR = 22050
rng = np.random.default_rng(7)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "assets", "geraeusche")


# ---------- Bausteine ----------

def t(d):
    return np.arange(int(d * SR)) / SR


def line(a, b, d):
    return np.linspace(a, b, int(d * SR))


def glide(points, d):
    """Tonhöhenverlauf durch mehrere Punkte, gleichmäßig über d Sekunden."""
    n = int(d * SR)
    xs = np.linspace(0, 1, len(points))
    return np.interp(np.linspace(0, 1, n), xs, points)


def osc(f, d=None, shape="sine", phase=0.0):
    if np.isscalar(f):
        f = np.full(int(d * SR), float(f))
    ph = 2 * np.pi * np.cumsum(f) / SR + phase
    if shape == "sine":
        return np.sin(ph)
    p = (ph / (2 * np.pi)) % 1.0
    if shape == "saw":
        return 2 * p - 1
    if shape == "square":
        return np.where(p < 0.5, 1.0, -1.0)
    if shape == "tri":
        return 4 * np.abs(p - 0.5) - 1
    if shape == "pulse":
        return np.where(p < 0.15, 1.0, -0.18)
    raise ValueError(shape)


def noise(d):
    return rng.uniform(-1, 1, int(d * SR))


def _sos(kind, f, order=2):
    nyq = SR / 2
    if kind == "band":
        lo, hi = max(20, f[0]), min(nyq * 0.98, f[1])
        return butter(order, [lo / nyq, hi / nyq], btype="band", output="sos")
    return butter(order, min(f, nyq * 0.98) / nyq, btype=kind, output="sos")


def lp(x, f, order=2):
    return sosfilt(_sos("low", f, order), x)


def hp(x, f, order=2):
    return sosfilt(_sos("high", f, order), x)


def bp(x, f, q=4.0, order=2):
    bw = f / q
    return sosfilt(_sos("band", (f - bw / 2, f + bw / 2), order), x)


def formant(x, freqs, q=5.0, gains=None):
    gains = gains or [1.0] * len(freqs)
    return sum(g * bp(x, f, q) for f, g in zip(freqs, gains))


def env(n, a=0.01, r=0.1, curve=1.0):
    """Hüllkurve: Anstieg a s, Ausklang r s, dazwischen voll."""
    e = np.ones(n)
    na, nr = min(n, int(a * SR)), min(n, int(r * SR))
    if na:
        e[:na] = np.linspace(0, 1, na)
    if nr:
        e[n - nr:] *= np.linspace(1, 0, nr) ** curve
    return e


def decay(n, tau):
    return np.exp(-np.arange(n) / SR / tau)


def shape(x, a=0.01, r=0.1, curve=1.0):
    return x * env(len(x), a, r, curve)


def sil(d):
    return np.zeros(int(d * SR))


def cat(*parts):
    return np.concatenate([np.asarray(p, dtype=float) for p in parts])


def mix(*parts):
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[:len(p)] += p
    return out


def place(total, items):
    """items: (startzeit, signal) auf eine Spur der Länge total legen."""
    out = np.zeros(int(total * SR))
    for start, sig in items:
        i = int(start * SR)
        j = min(len(out), i + len(sig))
        out[i:j] += sig[:j - i]
    return out


def reverb(x, wet=0.25, room=0.05):
    out = x.copy()
    for k, g in [(1.0, 0.5), (1.37, 0.4), (1.71, 0.33), (2.13, 0.27), (2.9, 0.2), (3.7, 0.14)]:
        dl = int(room * k * SR)
        out = mix(out, cat(sil(dl / SR), x * g * wet))
    return out


def note(name):
    names = {"C": -9, "D": -7, "E": -5, "F": -4, "G": -2, "A": 0, "H": 2, "B": 1}
    acc = 0
    base = name[0]
    rest = name[1:]
    if rest.startswith("#"):
        acc, rest = 1, rest[1:]
    elif rest.startswith("b"):
        acc, rest = -1, rest[1:]
    octave = int(rest)
    return 440.0 * 2 ** ((names[base] + acc + (octave - 4) * 12) / 12)


def vibrato(f, d, rate=5.5, depth=0.02):
    n = int(d * SR)
    base = np.full(n, f) if np.isscalar(f) else f
    return base * (1 + depth * np.sin(2 * np.pi * rate * np.arange(n) / SR))


def bell(f, d, partials=((1, 1), (2.76, 0.5), (5.4, 0.25), (8.93, 0.12)), tau=0.6):
    n = int(d * SR)
    return sum(a * osc(f * k, d) * decay(n, tau / (1 + k * 0.3)) for k, a in partials)


def pluck(f, d, damp=0.996):
    """Karplus-Strong Saite."""
    n = int(d * SR)
    p = max(2, int(SR / f))
    buf = rng.uniform(-1, 1, p)
    out = np.zeros(n)
    for i in range(n):
        out[i] = buf[i % p]
        buf[i % p] = damp * 0.5 * (buf[i % p] + buf[(i + 1) % p])
    return out


def voice(f0, d, formants, q=5.0, breath=0.05, shape_="saw"):
    src = osc(f0, d, shape_) + breath * noise(d)
    return formant(src, formants, q)


def click(d=0.01, f=3000, q=2.0):
    return bp(noise(d), f, q) * decay(int(d * SR), d / 4)


# ---------- Tiere ----------

def bellen():
    def bark():
        d = 0.2
        f = glide([480, 620, 360], d)
        s = voice(f, d, [650, 1150, 2400], q=3, breath=0.6)
        return shape(s, 0.005, 0.12, 2)
    return cat(bark(), sil(0.15), bark(), sil(0.35), bark(), sil(0.12), bark())


def heulen():
    d = 2.6
    f = vibrato(glide([280, 520, 560, 540, 380], d), d, 5, 0.015)
    return shape(voice(f, d, [380, 820, 2400], q=6, breath=0.15, shape_="tri"), 0.4, 0.8)


def miauen():
    d = 0.85
    f = glide([520, 760, 820, 600], d)
    src = osc(f, d, "saw") + 0.05 * noise(d)
    # i -> a -> u durch wandernde Formanten (in Stücken gefiltert)
    parts = []
    seg = len(src) // 3
    for k, fm in enumerate([[350, 2300, 3000], [850, 1350, 2800], [450, 900, 2600]]):
        parts.append(formant(src[k * seg:(k + 1) * seg + 400], fm, q=5))
    out = np.zeros(len(src) + 400)
    for k, p in enumerate(parts):
        w = np.hanning(len(p))
        out[k * seg:k * seg + len(p)] += p * w
    return shape(out, 0.05, 0.25)


def bruellen():
    d = 2.0
    f = glide([110, 180, 170, 90], d)
    rough = 1 + 0.6 * osc(32, d)
    s = voice(f, d, [420, 900, 2100], q=3, breath=1.2) * rough
    return shape(lp(s, 2500), 0.15, 0.9)


def brummen():
    d = 1.8
    f = glide([85, 95, 75], d)
    s = voice(f, d, [300, 700], q=3, breath=0.8) * (1 + 0.7 * osc(22, d))
    return shape(lp(s, 1200), 0.2, 0.6)


def troeten():
    d = 1.4
    f = vibrato(glide([380, 820, 900, 760], d), d, 9, 0.03)
    s = voice(f, d, [700, 1500, 3000], q=4, breath=0.3)
    return shape(hp(s, 300), 0.08, 0.4)


def affe():
    items, tt = [], 0.0
    for k in range(7):
        d = 0.22 - k * 0.015
        f = glide([300 + k * 40, 600 + k * 70], d)
        items.append((tt, shape(voice(f, d, [500, 1000], q=4, breath=0.2, shape_="tri"), 0.02, 0.08)))
        tt += d + 0.09 - k * 0.008
    return place(tt + 0.3, items)


def wiehern():
    d = 1.6
    f = glide([1100, 950, 700, 520, 420], d)
    f = f * (1 + 0.12 * np.sin(2 * np.pi * glide([14, 9], d).cumsum() / SR))
    s = voice(f, d, [900, 1700, 2800], q=4, breath=0.3)
    return shape(s * (1 + 0.4 * osc(9, d)), 0.03, 0.5)


def grunzen():
    def oink():
        d = 0.24
        f = glide([170, 210, 150], d)
        return shape(voice(f, d, [300, 1100], q=3, breath=0.8, shape_="pulse") * (1 + 0.8 * osc(45, d)), 0.01, 0.1)
    return cat(oink(), sil(0.1), oink(), sil(0.25), oink())


def muhen():
    d = 2.0
    f = glide([115, 145, 140, 105], d)
    src = osc(f, d, "saw") + 0.05 * noise(d)
    cut = glide([250, 1100, 900, 300], d)
    out = np.zeros_like(src)
    blk = 1024
    for i in range(0, len(src), blk):
        out[i:i + blk] = lp(src[max(0, i - 256):i + blk], cut[i])[-len(src[i:i + blk]):]
    return shape(out, 0.25, 0.5)


def maehen():
    def baa():
        d = 0.9
        f = vibrato(glide([420, 460, 380], d), d, 6, 0.02)
        s = voice(f, d, [700, 1750, 2600], q=5, breath=0.15) * (1 + 0.75 * osc(26, d))
        return shape(s, 0.04, 0.35)
    return cat(baa(), sil(0.25), baa())


def kraehen():
    parts = []
    for f0, d in [(650, 0.14), (820, 0.12), (760, 0.14), (1000, 0.7)]:
        f = glide([f0, f0 * 1.08, f0 * 0.85], d)
        parts += [shape(voice(f, d, [900, 1900, 3000], q=4, breath=0.4) * (1 + 0.3 * osc(60, d)), 0.01, 0.06 if d < 0.2 else 0.3), sil(0.03)]
    return cat(*parts)


def gackern():
    items, tt = [], 0.0
    for k in range(6):
        d = 0.07
        items.append((tt, shape(voice(glide([380, 330], d), d, [800, 1800], q=4, breath=0.5), 0.004, 0.04)))
        tt += 0.13 if k % 2 else 0.1
    d = 0.4
    items.append((tt + 0.1, shape(voice(glide([600, 700, 480], d), d, [900, 2000], q=4, breath=0.5), 0.01, 0.2)))
    return place(tt + 0.6, items)


def quaken():
    def quack():
        d = 0.2
        f = glide([260, 240, 200], d)
        return shape(formant(osc(f, d, "square") + 0.2 * noise(d), [1100, 2500], q=8), 0.005, 0.1)
    return cat(quack(), sil(0.08), quack(), sil(0.2), quack())


def schnattern():
    def honk():
        d = 0.28
        return shape(formant(osc(glide([470, 520, 430], d), d, "square") + 0.3 * noise(d), [900, 2200], q=6), 0.01, 0.1)
    return cat(honk(), sil(0.12), honk(), sil(0.3), honk())


def zwitschern():
    items = []
    tt = 0.0
    while tt < 2.0:
        d = rng.uniform(0.04, 0.12)
        a, b = rng.uniform(2800, 4500), rng.uniform(3000, 6000)
        items.append((tt, shape(osc(line(a, b, d), d) * 0.8, 0.005, d * 0.6)))
        tt += d + (rng.uniform(0.02, 0.06) if rng.random() < 0.7 else rng.uniform(0.2, 0.35))
    return place(tt + 0.2, items)


def papagei():
    def squawk():
        d = 0.35
        f = glide([900, 1300, 800], d)
        return shape(voice(f, d, [1500, 2800], q=3, breath=1.0) * (1 + 0.5 * osc(70, d)), 0.01, 0.15)
    return cat(squawk(), sil(0.2), squawk())


def eule():
    def hoot(d, f0):
        f = glide([f0, f0 * 1.04, f0 * 0.9], d)
        return shape(osc(f, d) + 0.05 * bp(noise(d), f0, 2), d * 0.3, d * 0.5)
    return reverb(cat(hoot(0.35, 420), sil(0.35), hoot(0.25, 430), sil(0.12), hoot(0.9, 410), sil(0.4)), 0.3, 0.06)


def adler():
    d = 1.3
    f = vibrato(glide([2300, 2100, 1600], d), d, 11, 0.04)
    return shape(voice(f, d, [2200, 3600], q=3, breath=0.6, shape_="tri"), 0.05, 0.5)


def frosch():
    def croak():
        d = 0.35
        pulses = osc(glide([28, 34, 24], d), d, "pulse")
        return shape(formant(pulses * (0.5 + 0.5 * osc(110, d, "tri")), [600, 1300], q=4), 0.02, 0.12)
    return cat(croak(), sil(0.25), croak(), sil(0.45), croak())


def zischen():
    d = 1.8
    return shape(hp(noise(d), 3500, 4), 0.6, 0.9) * 0.9


def blubbern():
    items = []
    for _ in range(26):
        d = rng.uniform(0.03, 0.07)
        f0 = rng.uniform(350, 900)
        items.append((rng.uniform(0, 2.0), osc(line(f0, f0 * 2.2, d), d) * decay(int(d * SR), d / 3)))
    return place(2.2, items)


def wal():
    d = 3.5
    f = vibrato(glide([260, 520, 780, 640, 300, 220], d), d, 3, 0.02)
    return reverb(shape(osc(f, d) + 0.3 * osc(f * 2, d), 0.6, 1.2), 0.5, 0.12)


def robbe():
    def ow():
        d = 0.22
        return shape(voice(glide([320, 380, 260], d), d, [600, 1100], q=4, breath=0.4), 0.01, 0.1)
    return cat(ow(), sil(0.12), ow(), sil(0.12), ow())


def summen():
    d = 2.2
    f = 230 * (1 + 0.04 * np.sin(2 * np.pi * 3 * t(d))) * glide([0.95, 1.08, 0.92], d)
    s = lp(osc(f, d, "saw"), 2500) * (0.6 + 0.4 * np.sin(2 * np.pi * 0.7 * t(d)) ** 2)
    return shape(s, 0.3, 0.5)


def zirpen():
    items = []
    for g in range(7):
        for k in range(3):
            d = 0.025
            items.append((g * 0.32 + k * 0.035, osc(4600, d) * np.hanning(int(d * SR))))
    return place(2.4, items)


def fiepen():
    def eep(f0):
        d = 0.12
        return shape(osc(line(f0, f0 * 1.25, d), d), 0.01, 0.06)
    return cat(eep(2400), sil(0.1), eep(2600), sil(0.2), eep(2300), sil(0.08), eep(2800))


def hufe():
    items = []
    for k in range(8):
        base = k * 0.36
        for j, off in enumerate([0, 0.09, 0.18]):
            items.append((base + off, click(0.05, 1400 if j % 2 else 900, 3) * 1.5))
    return place(3.0, items)


def zauber():
    notes = ["C6", "D6", "E6", "G6", "A6", "C7", "E7"]
    items = [(k * 0.09, bell(note(n), 0.9, ((1, 1), (2, 0.3), (3, 0.1)), 0.35) * 0.5) for k, n in enumerate(notes)]
    return place(1.6, items)


def hoppeln():
    def boing():
        d = 0.35
        f = 220 + 380 * np.abs(np.sin(2 * np.pi * 7 * t(d))) * decay(int(d * SR), 0.15)
        return shape(osc(f, d, "tri"), 0.005, 0.15)
    return cat(boing(), sil(0.15), boing(), sil(0.15), boing())


# ---------- Fahrzeuge ----------

def hupe():
    def tut(d):
        s = osc(420, d, "square") + osc(530, d, "square")
        return shape(lp(s, 2500), 0.01, 0.04)
    return cat(tut(0.22), sil(0.12), tut(0.5))


def motor():
    d = 2.6
    f = glide([45, 48, 120, 140, 70, 55], d)
    s = lp(osc(f, d, "saw") + 0.5 * osc(f * 2.01, d, "saw") + 0.4 * noise(d), 900)
    return shape(s * (0.8 + 0.2 * osc(f / 2, d)), 0.1, 0.4)


def rennauto():
    d = 2.4
    f = glide([110, 180, 260, 320, 180, 150], d)
    amp = glide([0.2, 0.5, 1, 1, 0.6, 0.1], d)
    s = lp(osc(f, d, "saw") + 0.6 * osc(f * 1.5, d, "square") * 0.4 + 0.3 * noise(d), 3500)
    return shape(s * amp, 0.05, 0.3)


def martinshorn():
    parts = []
    for k in range(4):
        f = 440 if k % 2 == 0 else 587
        d = 0.62
        s = osc(f, d, "saw") + 0.5 * osc(f * 2, d, "saw")
        parts.append(shape(formant(s, [f, 1200, 2500], q=2), 0.02, 0.04))
    return cat(*parts)


def bus():
    d = 0.8
    horn = shape(lp(osc(220, d, "saw") + osc(277, d, "saw"), 1500), 0.03, 0.1)
    psst = shape(hp(noise(0.7), 2000), 0.01, 0.5) * 0.6
    return cat(horn, sil(0.3), psst)


def lkw():
    d = 1.4
    s = sum(osc(f, d, "saw") for f in (311, 370, 466))
    return shape(lp(s, 3000), 0.06, 0.2)


def traktor():
    d = 2.4
    items = []
    for k in range(int(d * 11)):
        n = int(0.09 * SR)
        items.append((k / 11, lp(noise(0.09) + osc(60, 0.09, "saw"), 350, 4) * decay(n, 0.025) * 3))
    return place(d, items)


def dampflok():
    items, tt, gap = [], 0.0, 0.42
    for _ in range(10):
        n = int(0.2 * SR)
        items.append((tt, bp(noise(0.2), 1200, 1.2) * decay(n, 0.06)))
        tt += gap
        gap *= 0.88
    d = 1.0
    whistle = shape(osc(700, d) + osc(880, d) + 0.6 * osc(1050, d) + 0.25 * bp(noise(d), 900, 2), 0.05, 0.3) * 0.5
    items.append((tt + 0.1, whistle))
    return place(tt + 1.3, items)


def zug():
    items = []
    for k in range(4):
        b = k * 0.55
        items.append((b, click(0.08, 600, 2) * 2))
        items.append((b + 0.13, click(0.08, 500, 2) * 2))
    d = 1.0
    horn = shape(lp(osc(370, d, "saw") + osc(466, d, "saw"), 2200), 0.04, 0.15) * 0.6
    items.append((2.3, horn))
    return place(3.4, items)


def strassenbahn():
    return cat(bell(1250, 0.35, tau=0.5), bell(1250, 1.2, tau=0.5))


def flugzeug():
    d = 3.2
    cut = glide([400, 1800, 2500, 900], d)
    nz = noise(d)
    out = np.zeros_like(nz)
    blk = 1024
    for i in range(0, len(nz), blk):
        out[i:i + blk] = bp(nz[max(0, i - 512):i + blk], cut[i], 1.5)[-len(nz[i:i + blk]):]
    whine = osc(glide([2600, 2800, 2300], d), d) * 0.08
    return shape(out + whine, 0.8, 1.2) * glide([0.3, 1, 1, 0.4], d)


def propeller():
    d = 2.4
    s = lp(osc(95, d, "saw") + 0.3 * noise(d), 900) * (0.6 + 0.4 * osc(26, d))
    return shape(s, 0.4, 0.6)


def rakete():
    d = 3.2
    rumble = lp(noise(d), 300, 4) * 3 + hp(noise(d), 2000) * glide([0, 0.6, 0.4], d)
    return shape(rumble * glide([0.05, 0.6, 1, 0.8], d), 0.5, 1.0)


def schiff():
    d = 2.4
    s = osc(110, d, "saw") + osc(165, d, "saw") * 0.7
    return reverb(shape(lp(s, 700), 0.3, 0.6), 0.4, 0.15)


def motorboot():
    d = 2.4
    eng = lp(osc(glide([70, 95], d), d, "square") * (1 + 0.5 * osc(14, d)), 600)
    water = hp(lp(noise(d), 4000), 800) * 0.35
    return shape(eng + water, 0.3, 0.5)


def sonar():
    p = osc(1500, 1.4) * decay(int(1.4 * SR), 0.35)
    return reverb(cat(p, sil(0.3), p * 0.5), 0.4, 0.2)


def klingel():
    def ring():
        items = [(k * 0.045, bell(2600, 0.35, ((1, 1), (1.47, 0.6), (2.3, 0.3)), 0.25)) for k in range(6)]
        return place(0.65, items)
    return cat(ring(), sil(0.2), ring())


def moped():
    d = 2.2
    f = glide([95, 150, 170, 120], d)
    return shape(lp(osc(f, d, "square") + 0.3 * noise(d), 1800), 0.1, 0.4)


def piepen():
    parts = []
    for _ in range(5):
        parts += [shape(osc(1050, 0.3), 0.005, 0.01) * 0.6, sil(0.25)]
    return cat(*parts)


def rollen():
    d = 2.0
    items = [(rng.uniform(0, d), click(0.02, rng.uniform(800, 2500), 2)) for _ in range(90)]
    return mix(place(d + 0.1, items), lp(noise(d + 0.1), 500) * 0.3)


# ---------- Dinge ----------

def ticktack():
    items = []
    for k in range(8):
        items.append((k * 0.5, click(0.03, 3200 if k % 2 == 0 else 2200, 3) * 2))
    return place(4.1, items)


def digitaluhr():
    beep = lambda: shape(osc(4000, 0.07), 0.002, 0.01) * 0.5
    parts = []
    for _ in range(3):
        parts += [beep(), sil(0.06), beep(), sil(0.06), beep(), sil(0.06), beep(), sil(0.5)]
    return cat(*parts)


def handy():
    seq = ["E6", "G6", "C7", "G6", "E6", "G6", "C7", "E7"]
    parts = [shape(osc(note(n), 0.13, "square") * 0.3 + osc(note(n), 0.13) * 0.4, 0.005, 0.03) for n in seq]
    phrase = cat(*parts)
    return cat(phrase, sil(0.4), phrase)


def telefon():
    def ring():
        d = 1.0
        hammer = 0.5 + 0.5 * np.sign(osc(22, d))
        return bell(1000, d, ((1, 1), (1.53, 0.7), (2.4, 0.4)), 0.8) * hammer
    return cat(ring(), sil(0.6), ring())


def klavier():
    def key(n, d):
        f = note(n)
        s = sum(a * osc(f * k, d) for k, a in [(1, 1), (2, 0.5), (3, 0.25), (4, 0.15), (5, 0.08)])
        return s * decay(int(d * SR), 0.6) * env(int(d * SR), 0.003, 0.1)
    items = [(k * 0.25, key(n, 1.2)) for k, n in enumerate(["C4", "E4", "G4", "C5"])]
    items += [(1.1, key(n, 2.0) * 0.6) for n in ["C4", "E4", "G4", "C5"]]
    return place(3.2, items)


def gitarre():
    chord = ["G2", "H2", "D3", "G3", "H3", "G4"]
    items = [(k * 0.03, pluck(note(n), 2.5, 0.997)) for k, n in enumerate(chord)]
    items += [(1.2 + k * 0.02, pluck(note(n), 2.0, 0.996)) for k, n in enumerate(reversed(chord))]
    return lp(place(3.3, items), 4000)


def geige():
    parts = []
    for n, d in [("G4", 0.6), ("D5", 0.6), ("H4", 1.1)]:
        f = vibrato(note(n), d, 5.5, 0.008)
        parts.append(shape(formant(osc(f, d, "saw"), [450, 1100, 2700], q=2), 0.08, 0.15))
    return reverb(cat(*parts), 0.25, 0.04)


def trommel():
    def tom(f0):
        d = 0.35
        return osc(line(f0, f0 * 0.5, d), d) * decay(int(d * SR), 0.1)
    def snare():
        d = 0.2
        return (bp(noise(d), 2500, 1) + 0.5 * osc(200, d)) * decay(int(d * SR), 0.05)
    pat = [(0, tom(130)), (0.25, tom(130)), (0.5, snare()), (0.75, tom(170)), (0.875, tom(150)), (1.0, tom(120)),
           (1.25, snare()), (1.5, tom(130)), (1.75, snare()), (1.875, snare()), (2.0, tom(110))]
    return place(2.5, pat)


def brass(n, d):
    f = vibrato(note(n), d, 5, 0.006)
    s = osc(f, d, "saw")
    return shape(formant(s, [note(n), 1200, 2400], q=2) + lp(s, 1800) * 0.4, 0.04, 0.08)


def trompete():
    return reverb(cat(brass("G4", 0.18), sil(0.04), brass("C5", 0.18), sil(0.04), brass("E5", 0.18),
                      sil(0.04), brass("G5", 0.7)), 0.25, 0.05)


def floete():
    parts = []
    for n, d in [("A5", 0.35), ("C6", 0.35), ("E6", 0.35), ("D6", 0.9)]:
        f = vibrato(note(n), d, 5, 0.01)
        parts.append(shape(osc(f, d) + 0.15 * osc(f * 2, d) + 0.08 * bp(noise(d), note(n), 3), 0.05, 0.1))
    return reverb(cat(*parts), 0.3, 0.05)


def harfe():
    ns = ["C4", "E4", "G4", "C5", "E5", "G5", "C6", "E6"]
    return place(2.6, [(k * 0.1, pluck(note(n), 1.8, 0.998) * 0.8) for k, n in enumerate(ns)])


def xylophon():
    ns = ["C5", "D5", "E5", "F5", "G5", "A5", "H5", "C6"]
    def bar(n):
        d = 0.5
        f = note(n)
        return (osc(f, d) + 0.3 * osc(f * 4, d) * decay(int(d * SR), 0.03)) * decay(int(d * SR), 0.12)
    return place(1.8, [(k * 0.14, bar(n)) for k, n in enumerate(ns)])


def rassel():
    items = []
    for k, tt in enumerate([0, 0.25, 0.5, 0.62, 0.75, 1.0, 1.25, 1.5, 1.62, 1.75]):
        d = 0.12
        items.append((tt, bp(noise(d), 5500, 1.5) * env(int(d * SR), 0.02, 0.08)))
    return place(2.0, items)


def gong():
    return bell(110, 4.0, ((1, 1), (1.47, 0.7), (2.09, 0.5), (2.56, 0.4), (3.2, 0.3), (4.1, 0.2)), 2.5) * 0.8


def windspiel():
    ns = ["C6", "E6", "G6", "A6", "C7", "D7"]
    items = [(rng.uniform(0, 2.0), bell(note(rng.choice(ns)), 1.6, tau=0.8) * 0.4) for _ in range(9)]
    return place(3.5, items)


def akkordeon():
    def chord(ns, d):
        s = sum(osc(note(n), d, "saw") + osc(note(n) * 1.004, d, "saw") for n in ns)
        return shape(lp(s, 2500), 0.06, 0.1)
    return cat(chord(["C4", "E4", "G4"], 0.6), chord(["F4", "A4", "C5"], 0.6), chord(["G4", "H4", "D5"], 0.6),
               chord(["C4", "E4", "G4", "C5"], 1.0))


def mundharmonika():
    def chord(ns, d):
        s = sum(osc(note(n), d, "square") for n in ns) * (1 + 0.15 * osc(6, d))
        return shape(formant(s, [700, 1500], q=2), 0.05, 0.1)
    return cat(chord(["C5", "E5", "G5"], 0.5), chord(["D5", "F5", "A5"], 0.5), chord(["C5", "E5", "G5"], 0.9))


def orgel():
    d = 2.4
    ns = ["C3", "C4", "E4", "G4"]
    s = sum(sum(a * osc(note(n) * k, d) for k, a in [(1, 1), (2, 0.6), (3, 0.4), (4, 0.3), (8, 0.2)]) for n in ns)
    return reverb(shape(s, 0.08, 0.5), 0.4, 0.08)


def saxophon():
    parts = []
    for n, d in [("D4", 0.3), ("F4", 0.3), ("G4", 0.3), ("Ab4", 0.2), ("G4", 0.8)]:
        f = vibrato(note(n), d, 5, 0.01)
        parts.append(shape(formant(osc(f, d, "saw") + 0.1 * noise(d), [500, 1300, 2600], q=3), 0.03, 0.08))
    return reverb(cat(*parts), 0.25, 0.04)


def pfeife():
    def blast(d):
        f = 2900 * (1 + 0.04 * osc(32, d, "square"))
        return shape(osc(f, d) + 0.1 * bp(noise(d), 2900, 4), 0.01, 0.05)
    return cat(blast(0.25), sil(0.12), blast(0.8))


def ballon():
    d = 0.8
    squeak = shape(formant(osc(glide([500, 900, 600, 1000], d), d, "saw"), [1200, 2500], q=5), 0.05, 0.1) * 0.6
    pop = bp(noise(0.15), 1500, 0.8) * decay(int(0.15 * SR), 0.02) * 3
    return cat(squeak, sil(0.25), pop, sil(0.3))


def staubsauger():
    d = 2.8
    whine = osc(glide([150, 600, 640, 620], d), d, "saw") * 0.15
    air = bp(noise(d), 1800, 0.7)
    return shape(lp(whine + air, 5000) * glide([0.1, 1, 1, 1, 0.9], d), 0.1, 0.5)


def ventilator():
    d = 2.6
    return shape(lp(noise(d), 1200) * (0.7 + 0.3 * osc(18, d)) * 1.5, 0.5, 0.6)


def mikrowelle():
    d = 1.6
    hum = shape(lp(osc(100, d, "square"), 400) * 0.25 + lp(noise(d), 800) * 0.2, 0.1, 0.1)
    beep = lambda: shape(osc(2000, 0.35), 0.005, 0.02) * 0.6
    return cat(hum, sil(0.2), beep(), sil(0.25), beep(), sil(0.25), beep())


def toaster():
    d = 0.3
    boing = osc(220 + 300 * np.abs(np.sin(2 * np.pi * 9 * t(d))), d, "tri") * decay(int(d * SR), 0.12)
    return cat(sil(0.1), click(0.04, 1500, 2) * 3, boing, sil(0.15), bell(1760, 1.3, tau=0.6) * 0.6)


def wasserkessel():
    d = 2.6
    f = glide([1400, 1700, 2100, 2200], d)
    s = osc(f, d) * glide([0.05, 0.6, 1, 1], d) + bp(noise(d), 2000, 2) * 0.4
    return shape(s, 0.3, 0.3)


def bohrer():
    def run(d):
        f = glide([200, 900, 950], d)
        return shape(lp(osc(f, d, "saw") + 0.2 * noise(d), 3000), 0.05, 0.1)
    return cat(run(0.9), sil(0.25), run(1.3))


def hammer():
    hit = lambda: mix(bell(900, 0.4, ((1, 0.5), (2.3, 0.3), (3.9, 0.2)), 0.08), click(0.05, 2000, 1) * 2)
    return cat(hit(), sil(0.3), hit(), sil(0.3), hit())


def rasenmaeher():
    d = 2.6
    s = lp(osc(glide([30, 42, 42], d), d, "pulse") * 2 + 0.5 * noise(d), 1500) * (1 + 0.3 * osc(7, d))
    return shape(s, 0.2, 0.5)


def ball():
    items, tt, gap, amp = [], 0.0, 0.6, 1.0
    for _ in range(7):
        d = 0.15
        thud = osc(line(150, 70, d), d) * decay(int(d * SR), 0.04)
        thud = mix(thud, click(0.03, 900, 1) * 0.6)
        items.append((tt, thud * amp))
        tt += gap
        gap *= 0.72
        amp *= 0.8
    return place(tt + 0.3, items)


def tischtennis():
    items = []
    for k, tt in enumerate([0, 0.35, 0.7, 1.05, 1.4, 1.75]):
        items.append((tt, bp(noise(0.03), 3500 if k % 2 else 2500, 3) * decay(int(0.03 * SR), 0.006) * 4))
    return place(2.1, items)


def spieluhr():
    melody = ["C6", "C6", "G6", "G6", "A6", "A6", "G6", None, "F6", "F6", "E6", "E6", "D6", "D6", "C6"]
    items = []
    for k, n in enumerate(melody):
        if n:
            items.append((k * 0.32, bell(note(n), 1.2, ((1, 1), (3, 0.2), (5.4, 0.08)), 0.4) * 0.5))
    return place(len(melody) * 0.32 + 1.0, items)


def regen():
    d = 3.0
    base = lp(noise(d), 3000) * 0.4
    drops = place(d, [(rng.uniform(0, d), click(0.01, rng.uniform(2000, 6000), 3) * rng.uniform(0.3, 1.2)) for _ in range(160)])
    return shape(base + drops, 0.4, 0.6)


def schritte():
    items = []
    for k in range(6):
        d = 0.12
        items.append((k * 0.45, mix(lp(noise(d), 700, 2) * decay(int(d * SR), 0.03) * 3, click(0.03, 2500, 2) * 0.3)))
    return place(2.8, items)


def mampf():
    items = []
    for k in range(5):
        d = 0.18
        items.append((k * 0.33, bp(noise(d), rng.uniform(1500, 3000), 1.2) * env(int(d * SR), 0.01, 0.12) * (1 + osc(40, d))))
    return place(1.8, items)


def blaettern():
    def flip():
        d = 0.3
        return shape(hp(bp(noise(d), 3000, 0.8), 1000), 0.08, 0.18)
    return cat(flip(), sil(0.35), flip(), sil(0.35), flip())


def tippen():
    items = [(k * 0.12 + rng.uniform(0, 0.05), click(0.025, rng.uniform(2000, 3500), 3) * 2) for k in range(16)]
    return place(2.2, items)


def klick():
    c = lambda: click(0.015, 3000, 3) * 3
    return cat(c(), sil(0.08), c(), sil(0.5), c(), sil(0.08), c(), sil(0.2))


def radio():
    static = bp(noise(0.8), 2500, 0.7) * 0.4
    tune = cat(*[shape(osc(note(n), 0.18, "tri"), 0.01, 0.05) for n in ["C5", "E5", "G5", "E5", "C5", "G4", "C5"]])
    return cat(shape(static, 0.05, 0.3), tune, sil(0.2))


def fernseher():
    return cat(*[shape(sum(osc(note(n) * k, 0.3) / k for k in (1, 2, 3)), 0.01, 0.15) for n in ["G5", "C6", "E6"]], sil(0.05),
               shape(sum(osc(note(n), 0.9) for n in ["C6", "E6", "G6"]) / 3, 0.01, 0.6))


def kamera():
    return cat(click(0.03, 3000, 2) * 3, sil(0.05), click(0.04, 1500, 2) * 2,
               shape(lp(osc(glide([400, 900], 0.5), 0.5, "saw"), 2000) * 0.2, 0.02, 0.1))


def pusten():
    d = 1.4
    return shape(lp(hp(noise(d), 300), 2500) * glide([0.1, 1, 0.8, 0], d), 0.1, 0.3)


def waschmaschine():
    d = 3.0
    slosh = lp(noise(d), 600) * (0.5 + 0.5 * np.sin(2 * np.pi * 0.9 * t(d)) ** 2) * 1.5
    hum = lp(osc(60, d, "saw"), 300) * 0.3
    return shape(slosh + hum, 0.4, 0.6)


def ampel():
    items = [(k * 0.9, click(0.04, 1000, 3) * 3) for k in range(3)]
    items += [(2.7 + k * 0.15, click(0.04, 1000, 3) * 3) for k in range(10)]
    return place(4.4, items)


def klirren():
    c = lambda: bell(3200, 0.7, ((1, 1), (1.7, 0.6), (2.65, 0.3)), 0.25) * 0.6
    return cat(c(), sil(0.15), c())


def quietschen():
    def creak():
        d = 0.6
        return shape(formant(osc(glide([500, 900, 700], d), d, "pulse"), [1300, 2600], q=8), 0.05, 0.15)
    return cat(creak(), sil(0.4), creak())


def wind():
    d = 3.0
    nz = noise(d)
    cut = glide([500, 1300, 700, 1500, 600], d)
    out = np.zeros_like(nz)
    blk = 1024
    for i in range(0, len(nz), blk):
        out[i:i + blk] = bp(nz[max(0, i - 512):i + blk], cut[i], 3)[-len(nz[i:i + blk]):]
    return shape(out * 1.5, 0.6, 0.8)


def platschen():
    d = 0.6
    splash = bp(noise(d), 1500, 0.6) * decay(int(d * SR), 0.12) * 1.5
    return cat(splash, sil(0.1), blubbern()[:int(0.8 * SR)])


SOUNDS = [
    # Tiere
    bellen, heulen, miauen, bruellen, brummen, troeten, affe, wiehern, grunzen, muhen, maehen, kraehen, gackern,
    quaken, schnattern, zwitschern, papagei, eule, adler, frosch, zischen, blubbern, wal, robbe, summen, zirpen,
    fiepen, hufe, zauber, hoppeln,
    # Fahrzeuge
    hupe, motor, rennauto, martinshorn, bus, lkw, traktor, dampflok, zug, strassenbahn, flugzeug, propeller, rakete,
    schiff, motorboot, sonar, klingel, moped, piepen, rollen,
    # Dinge
    ticktack, digitaluhr, handy, telefon, klavier, gitarre, geige, trommel, trompete, floete, harfe, xylophon,
    rassel, gong, windspiel, akkordeon, mundharmonika, orgel, saxophon, pfeife, ballon, staubsauger, ventilator,
    mikrowelle, toaster, wasserkessel, bohrer, hammer, rasenmaeher, ball, tischtennis, spieluhr, regen, schritte,
    mampf, blaettern, tippen, klick, radio, fernseher, kamera, pusten, waschmaschine, ampel, klirren, quietschen,
    wind, platschen,
]


def finish(x):
    x = np.asarray(x, dtype=float)
    x = x - np.mean(x)
    peak = np.max(np.abs(x)) or 1.0
    x = x / peak * 0.89
    fade = min(len(x) // 2, int(0.01 * SR))
    x[:fade] *= np.linspace(0, 1, fade)
    x[-fade:] *= np.linspace(1, 0, fade)
    return np.int16(x * 32767)


def main():
    only = set(sys.argv[1:])
    os.makedirs(OUT, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for fn in SOUNDS:
            if only and fn.__name__ not in only:
                continue
            wav = os.path.join(tmp, fn.__name__ + ".wav")
            wavfile.write(wav, SR, finish(fn()))
            ogg = os.path.join(OUT, fn.__name__ + ".ogg")
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "3", ogg],
                           check=True)
            print(fn.__name__)


if __name__ == "__main__":
    main()
