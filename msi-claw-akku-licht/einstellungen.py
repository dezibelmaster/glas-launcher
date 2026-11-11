"""Einstellungen und Zuordnung Akkustand → Farbe."""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path

# Stufen in 10-%-Schritten. Eine Stufe gilt ab ihrem Wert bis unter die nächste:
# 100 = 100 %, 90 = 90–99 %, …, 10 = 10–19 %, 0 = 0–9 %.
STUFEN = [100, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0]

STANDARD_FARBEN = {
    100: "#00FF00",  # grün
    90: "#40FF00",
    80: "#80FF00",
    70: "#BFFF00",
    60: "#FFFF00",   # gelb
    50: "#FFC000",
    40: "#FF8000",   # orange
    30: "#FF4000",
    20: "#FF0000",   # rot
    10: "#FF0000",
    0: "#FF0000",
}


def ordner() -> Path:
    basis = os.environ.get("APPDATA") or str(Path.home() / ".config")
    return Path(basis) / "ClawAkkuLicht"


@dataclass
class Einstellungen:
    farben: dict[int, str] = field(default_factory=lambda: dict(STANDARD_FARBEN))
    helligkeit: int = 80            # Prozent
    intervall: int = 30             # Sekunden zwischen Akku-Abfragen
    lade_farbe_an: bool = False
    lade_farbe: str = "#0080FF"
    aktiv: bool = True
    blinken_an: bool = True         # bei niedrigem Akku blinken (nicht beim Laden)
    blinken_ab: int = 10            # Prozent, ab denen (und darunter) geblinkt wird

    @classmethod
    def laden(cls) -> "Einstellungen":
        pfad = ordner() / "einstellungen.json"
        e = cls()
        try:
            daten = json.loads(pfad.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return e
        for stufe, farbe in (daten.get("farben") or {}).items():
            if int(stufe) in STANDARD_FARBEN and ist_farbe(farbe):
                e.farben[int(stufe)] = farbe.upper()
        e.helligkeit = max(0, min(100, int(daten.get("helligkeit", e.helligkeit))))
        e.intervall = max(5, min(600, int(daten.get("intervall", e.intervall))))
        e.lade_farbe_an = bool(daten.get("lade_farbe_an", e.lade_farbe_an))
        if ist_farbe(daten.get("lade_farbe", "")):
            e.lade_farbe = daten["lade_farbe"].upper()
        e.aktiv = bool(daten.get("aktiv", e.aktiv))
        e.blinken_an = bool(daten.get("blinken_an", e.blinken_an))
        e.blinken_ab = max(1, min(50, int(daten.get("blinken_ab", e.blinken_ab))))
        return e

    def speichern(self) -> None:
        pfad = ordner() / "einstellungen.json"
        pfad.parent.mkdir(parents=True, exist_ok=True)
        daten = asdict(self)
        daten["farben"] = {str(k): v for k, v in self.farben.items()}
        pfad.write_text(json.dumps(daten, indent=2), encoding="utf-8")

    def farbe_fuer(self, prozent: float, laedt: bool = False) -> str:
        if laedt and self.lade_farbe_an:
            return self.lade_farbe
        return self.farben[stufe_fuer(prozent)]


    def soll_blinken(self, prozent: float, laedt: bool) -> bool:
        return self.blinken_an and not laedt and prozent <= self.blinken_ab


def stufe_fuer(prozent: float) -> int:
    p = max(0, min(100, int(prozent)))
    return (p // 10) * 10


def ist_farbe(wert) -> bool:
    if not isinstance(wert, str) or len(wert) != 7 or not wert.startswith("#"):
        return False
    try:
        int(wert[1:], 16)
        return True
    except ValueError:
        return False


def hex_zu_rgb(wert: str) -> tuple[int, int, int]:
    return int(wert[1:3], 16), int(wert[3:5], 16), int(wert[5:7], 16)
