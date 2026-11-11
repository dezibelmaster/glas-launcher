"""Claw Akku-Licht: zeigt den Akkustand des MSI Claw über die Farbe der Stick-LEDs.

Start:  pythonw claw_akku_licht.py            (mit Fenster)
        pythonw claw_akku_licht.py --hintergrund  (nur im Infobereich, für den Autostart)
"""

from __future__ import annotations

import logging
import sys
import threading
import time
import tkinter as tk
from tkinter import colorchooser, ttk

import psutil

from claw_led import ClawLed
from einstellungen import STUFEN, Einstellungen, hex_zu_rgb, ordner, stufe_fuer

APP_NAME = "Claw Akku-Licht"
VORSCHAU_SEKUNDEN = 3
TAKT_SEKUNDEN = 2  # so oft wird auf Aufwachen und fehlenden Controller geachtet
BLINK_SEKUNDEN = 0.7  # Dauer von „an“ bzw. „aus“ beim Blinken
AUTOSTART_SCHLUESSEL = r"Software\Microsoft\Windows\CurrentVersion\Run"

log = logging.getLogger("claw_akku_licht")


# --------------------------------------------------------------------------- Hintergrund


class Steuerung(threading.Thread):
    """Fragt den Akku ab und setzt die LED-Farbe, sobald sie sich ändert."""

    def __init__(self, einstellungen: Einstellungen) -> None:
        super().__init__(daemon=True)
        self.e = einstellungen
        self.led = ClawLed()
        self.wecken = threading.Event()
        self.beenden = threading.Event()
        self.vorschau: tuple[str, float] | None = None  # (Farbe, Ende)
        self.zuletzt: tuple[str, int] | None = None
        self.letzte_erneuerung = 0.0
        self.status = "Starte …"
        self.prozent: float | None = None
        self.laedt = False
        self._neustart = False
        self.blink_farbe: str | None = None  # gesetzt, solange geblinkt wird
        self._blink_an = False

    def neu_anwenden(self) -> None:
        self.zuletzt = None
        self.wecken.set()

    def neu_starten(self) -> None:
        """Licht zurückholen, falls ein anderes Programm (z. B. MSI Center M) es übernommen hat."""
        self._neustart = True
        self.wecken.set()

    def zeige_vorschau(self, farbe: str) -> None:
        self.vorschau = (farbe, time.monotonic() + VORSCHAU_SEKUNDEN)
        self.neu_anwenden()

    def run(self) -> None:
        naechste = 0.0  # sofort beim Start prüfen
        letzter_takt = time.time()
        while not self.beenden.is_set():
            jetzt = time.time()
            # Lücke in der Uhr = das Gerät war im Standby oder ausgeschaltet
            if jetzt - letzter_takt > TAKT_SEKUNDEN + 10:
                log.info("Aufgewacht nach %.0f s, prüfe sofort", jetzt - letzter_takt)
                self.led.trennen()          # alte Verbindung ist nach dem Standby ungültig
                self.zuletzt = None         # Farbe auf jeden Fall neu senden
                naechste = 0.0
            if self._neustart:
                self._neustart = False
                log.info("Neustart der Lichtsteuerung")
                self.status = "Starte Lichtsteuerung neu …"
                self.led.trennen()          # frisch verbinden
                self.zuletzt = None         # Farbe auf jeden Fall neu senden
                self._blink_an = False
                naechste = 0.0
            letzter_takt = jetzt

            if self.wecken.is_set() or jetzt >= naechste:
                self.wecken.clear()
                try:
                    self._schritt()
                except Exception as ex:  # nie abstürzen, nur protokollieren
                    log.exception("Fehler")
                    self.status = f"Fehler: {ex}"
                if self.zuletzt is None and self.e.aktiv:
                    # Controller (noch) nicht bereit, z. B. direkt nach dem Hochfahren: bald wieder versuchen
                    naechste = time.time() + TAKT_SEKUNDEN
                else:
                    naechste = time.time() + self.e.intervall

            warte = TAKT_SEKUNDEN
            if self.blink_farbe and not self.vorschau:
                self._blinken()
                warte = BLINK_SEKUNDEN
            if self.vorschau:
                warte = min(warte, max(0.1, self.vorschau[1] - time.monotonic()))
            self.wecken.wait(warte)

    def _schritt(self) -> None:
        akku = psutil.sensors_battery()
        if akku is None:
            self.status = "Kein Akku gefunden"
            return
        self.prozent = akku.percent
        self.laedt = bool(akku.power_plugged)

        if self.vorschau and time.monotonic() >= self.vorschau[1]:
            self.vorschau = None
            self.zuletzt = None

        if self.vorschau:
            farbe = self.vorschau[0]
        elif not self.e.aktiv:
            self.blink_farbe = None
            self.status = f"Pausiert – Akku {self.prozent:.0f} %"
            return
        else:
            farbe = self.e.farbe_fuer(self.prozent, self.laedt)
            if self.e.soll_blinken(self.prozent, self.laedt):
                if self.blink_farbe is None:
                    self._blink_an = False
                self.blink_farbe = farbe
                self.zuletzt = ("blinkt", farbe)  # das Senden übernimmt _blinken()
                self.status = f"Akku {self.prozent:.0f} % → {farbe}, blinkt"
                return

        if self.blink_farbe is not None and not self.vorschau:
            self.blink_farbe = None
            self.zuletzt = None  # nach dem Blinken die Farbe sicher wieder fest setzen

        ziel = (farbe, self.e.helligkeit)
        # Alle 5 Minuten erneut senden, falls MSI Center M o. Ä. die Farbe überschrieben hat
        faellig = time.monotonic() - self.letzte_erneuerung > 300
        if ziel == self.zuletzt and not faellig:
            return
        if self.led.setze_farbe(hex_zu_rgb(farbe), self.e.helligkeit):
            self.zuletzt = ziel
            self.letzte_erneuerung = time.monotonic()
            lade = " (lädt)" if self.laedt else ""
            self.status = f"Akku {self.prozent:.0f} %{lade} → {farbe}"
        else:
            self.zuletzt = None
            self.status = "MSI-Claw-Controller nicht gefunden"

    def _blinken(self) -> None:
        self._blink_an = not self._blink_an
        hell = self.e.helligkeit if self._blink_an else 0
        if not self.led.setze_farbe(hex_zu_rgb(self.blink_farbe), hell):
            self.status = "MSI-Claw-Controller nicht gefunden"


# --------------------------------------------------------------------------- Autostart


def autostart_befehl() -> str:
    if getattr(sys, "frozen", False):  # als .exe gebaut
        return f'"{sys.executable}" --hintergrund'
    pythonw = sys.executable.replace("python.exe", "pythonw.exe")
    return f'"{pythonw}" "{__file__}" --hintergrund'


def autostart_an() -> bool:
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, AUTOSTART_SCHLUESSEL) as k:
            winreg.QueryValueEx(k, APP_NAME)
            return True
    except (ImportError, OSError):
        return False


def autostart_setzen(an: bool) -> None:
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, AUTOSTART_SCHLUESSEL, 0,
                            winreg.KEY_SET_VALUE) as k:
            if an:
                winreg.SetValueEx(k, APP_NAME, 0, winreg.REG_SZ, autostart_befehl())
            else:
                try:
                    winreg.DeleteValue(k, APP_NAME)
                except FileNotFoundError:
                    pass
    except (ImportError, OSError):
        log.exception("Autostart konnte nicht geändert werden")


# --------------------------------------------------------------------------- Oberfläche


class Fenster:
    def __init__(self, root: tk.Tk, e: Einstellungen, s: Steuerung) -> None:
        self.root, self.e, self.s = root, e, s
        root.title(APP_NAME)
        root.resizable(False, False)
        root.protocol("WM_DELETE_WINDOW", self.verstecken)

        rahmen = ttk.Frame(root, padding=16)
        rahmen.grid()

        self.status = tk.StringVar()
        ttk.Label(rahmen, textvariable=self.status, font=("Segoe UI", 12, "bold")).grid(
            row=0, column=0, columnspan=4, sticky="w", pady=(0, 12))

        ttk.Label(rahmen, text="Akkustand").grid(row=1, column=0, sticky="w")
        ttk.Label(rahmen, text="Farbe").grid(row=1, column=1, sticky="w")
        self.felder: dict[int, tk.Label] = {}
        self.pfeile: dict[int, ttk.Label] = {}
        for i, stufe in enumerate(STUFEN):
            z = i + 2
            text = "100 %" if stufe == 100 else f"{stufe}–{stufe + 9} %"
            ttk.Label(rahmen, text=text, width=10).grid(row=z, column=0, sticky="w", pady=2)
            feld = tk.Label(rahmen, width=10, relief="raised", bd=2, cursor="hand2")
            feld.grid(row=z, column=1, padx=6, pady=2)
            feld.bind("<Button-1>", lambda _ev, st=stufe: self.farbe_waehlen(st))
            self.felder[stufe] = feld
            ttk.Button(rahmen, text="Vorschau", width=9,
                       command=lambda st=stufe: self.s.zeige_vorschau(self.e.farben[st])
                       ).grid(row=z, column=2, padx=2)
            pfeil = ttk.Label(rahmen, text="", width=3)
            pfeil.grid(row=z, column=3)
            self.pfeile[stufe] = pfeil

        z = len(STUFEN) + 2
        ttk.Separator(rahmen).grid(row=z, column=0, columnspan=4, sticky="ew", pady=10)

        self.lade_an = tk.BooleanVar(value=e.lade_farbe_an)
        ttk.Checkbutton(rahmen, text="Eigene Farbe beim Laden", variable=self.lade_an,
                        command=self.uebernehmen).grid(row=z + 1, column=0, columnspan=1, sticky="w")
        self.lade_feld = tk.Label(rahmen, width=10, relief="raised", bd=2, cursor="hand2")
        self.lade_feld.grid(row=z + 1, column=1, padx=6)
        self.lade_feld.bind("<Button-1>", lambda _ev: self.farbe_waehlen("laden"))

        ttk.Label(rahmen, text="Helligkeit").grid(row=z + 2, column=0, sticky="w", pady=(8, 0))
        self.hell = tk.IntVar(value=e.helligkeit)
        ttk.Scale(rahmen, from_=0, to=100, variable=self.hell, length=180,
                  command=lambda _v: self.hell_text.set(f"{self.hell.get()} %")
                  ).grid(row=z + 2, column=1, columnspan=2, sticky="w", pady=(8, 0))
        self.hell_text = tk.StringVar(value=f"{e.helligkeit} %")
        ttk.Label(rahmen, textvariable=self.hell_text, width=5).grid(row=z + 2, column=3, pady=(8, 0))
        rahmen.bind_all("<ButtonRelease-1>", lambda _ev: self.helligkeit_pruefen())

        ttk.Label(rahmen, text="Prüfen alle").grid(row=z + 3, column=0, sticky="w", pady=(8, 0))
        self.intervall = tk.IntVar(value=e.intervall)
        sb = ttk.Spinbox(rahmen, from_=5, to=600, increment=5, width=6,
                         textvariable=self.intervall, command=self.uebernehmen)
        sb.grid(row=z + 3, column=1, sticky="w", padx=6, pady=(8, 0))
        sb.bind("<FocusOut>", lambda _ev: self.uebernehmen())
        ttk.Label(rahmen, text="Sekunden").grid(row=z + 3, column=2, sticky="w", pady=(8, 0))

        self.blinken = tk.BooleanVar(value=e.blinken_an)
        ttk.Checkbutton(rahmen, text="Blinken bei höchstens", variable=self.blinken,
                        command=self.uebernehmen).grid(row=z + 4, column=0, sticky="w", pady=(8, 0))
        self.blinken_ab = tk.IntVar(value=e.blinken_ab)
        sb2 = ttk.Spinbox(rahmen, from_=1, to=50, increment=1, width=6,
                          textvariable=self.blinken_ab, command=self.uebernehmen)
        sb2.grid(row=z + 4, column=1, sticky="w", padx=6, pady=(8, 0))
        sb2.bind("<FocusOut>", lambda _ev: self.uebernehmen())
        ttk.Label(rahmen, text="% (nicht beim Laden)").grid(row=z + 4, column=2, columnspan=2,
                                                           sticky="w", pady=(8, 0))

        self.aktiv = tk.BooleanVar(value=e.aktiv)
        ttk.Checkbutton(rahmen, text="Akku-Licht aktiv", variable=self.aktiv,
                        command=self.uebernehmen).grid(row=z + 5, column=0, columnspan=2,
                                                       sticky="w", pady=(10, 0))
        self.autostart = tk.BooleanVar(value=autostart_an())
        ttk.Checkbutton(rahmen, text="Mit Windows starten", variable=self.autostart,
                        command=lambda: autostart_setzen(self.autostart.get())
                        ).grid(row=z + 6, column=0, columnspan=2, sticky="w")

        knoepfe = ttk.Frame(rahmen)
        knoepfe.grid(row=z + 7, column=0, columnspan=4, sticky="e", pady=(12, 0))
        ttk.Button(knoepfe, text="⟳ Licht neu starten", command=self.s.neu_starten).grid(row=0, column=0, padx=4)
        ttk.Button(knoepfe, text="Standardfarben", command=self.standard).grid(row=0, column=1, padx=4)
        ttk.Button(knoepfe, text="In den Infobereich", command=self.verstecken).grid(row=0, column=2, padx=4)

        self.felder_malen()
        self.aktualisieren()

    # -- Aktionen

    def farbe_waehlen(self, stufe) -> None:
        alt = self.e.lade_farbe if stufe == "laden" else self.e.farben[stufe]
        titel = "Farbe beim Laden" if stufe == "laden" else f"Farbe für {stufe} %"
        _, neu = colorchooser.askcolor(color=alt, title=titel, parent=self.root)
        if not neu:
            return
        neu = neu.upper()
        if stufe == "laden":
            self.e.lade_farbe = neu
        else:
            self.e.farben[stufe] = neu
        self.felder_malen()
        self.s.zeige_vorschau(neu)
        self.uebernehmen()

    def standard(self) -> None:
        from einstellungen import STANDARD_FARBEN
        self.e.farben = dict(STANDARD_FARBEN)
        self.felder_malen()
        self.uebernehmen()

    def helligkeit_pruefen(self) -> None:
        if self.hell.get() != self.e.helligkeit:
            self.uebernehmen()

    def uebernehmen(self) -> None:
        self.e.lade_farbe_an = self.lade_an.get()
        self.e.helligkeit = int(self.hell.get())
        try:
            self.e.intervall = max(5, min(600, int(self.intervall.get())))
        except (tk.TclError, ValueError):
            pass
        self.e.aktiv = self.aktiv.get()
        self.e.blinken_an = self.blinken.get()
        try:
            self.e.blinken_ab = max(1, min(50, int(self.blinken_ab.get())))
        except (tk.TclError, ValueError):
            pass
        self.e.speichern()
        self.s.neu_anwenden()

    # -- Anzeige

    def felder_malen(self) -> None:
        for stufe, feld in self.felder.items():
            farbe = self.e.farben[stufe]
            feld.configure(bg=farbe, activebackground=farbe, text=farbe, fg=textfarbe(farbe))
        self.lade_feld.configure(bg=self.e.lade_farbe, text=self.e.lade_farbe,
                                 fg=textfarbe(self.e.lade_farbe))

    def aktualisieren(self) -> None:
        self.status.set(self.s.status)
        aktuell = stufe_fuer(self.s.prozent) if self.s.prozent is not None else None
        for stufe, pfeil in self.pfeile.items():
            pfeil.configure(text="◀" if stufe == aktuell else "")
        self.root.after(1000, self.aktualisieren)

    def zeigen(self) -> None:
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def verstecken(self) -> None:
        self.root.withdraw()


def textfarbe(hexfarbe: str) -> str:
    r, g, b = hex_zu_rgb(hexfarbe)
    return "#000000" if (0.299 * r + 0.587 * g + 0.114 * b) > 140 else "#FFFFFF"


# --------------------------------------------------------------------------- Infobereich


def infobereich_starten(root: tk.Tk, fenster: Fenster, s: Steuerung):
    try:
        import pystray
        from PIL import Image, ImageDraw
    except ImportError:
        return None

    def bild(farbe: str):
        img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        d.ellipse((4, 4, 60, 60), fill=farbe, outline="#202020", width=4)
        return img

    def beenden(icon, _item):
        icon.stop()
        s.beenden.set()
        s.wecken.set()
        root.after(0, root.destroy)

    icon = pystray.Icon(
        "claw_akku_licht", bild("#00FF00"), APP_NAME,
        menu=pystray.Menu(
            pystray.MenuItem("Einstellungen", lambda: root.after(0, fenster.zeigen), default=True),
            pystray.MenuItem("Licht neu starten", lambda: s.neu_starten()),
            pystray.MenuItem("Beenden", beenden),
        ),
    )

    def symbol_aktualisieren():
        if s.prozent is not None:
            farbe = s.e.farbe_fuer(s.prozent, s.laedt)
            icon.icon = bild(farbe)
            icon.title = f"{APP_NAME} – {s.status}"
        root.after(5000, symbol_aktualisieren)

    icon.run_detached()
    symbol_aktualisieren()
    return icon


# --------------------------------------------------------------------------- Start


def nur_eine_instanz() -> bool:
    if sys.platform != "win32":
        return True
    import ctypes
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    global _MUTEX  # Handle muss bis zum Programmende leben
    _MUTEX = kernel32.CreateMutexW(None, False, "ClawAkkuLichtMutex")
    return ctypes.get_last_error() != 183  # ERROR_ALREADY_EXISTS


def main() -> None:
    ordner().mkdir(parents=True, exist_ok=True)
    logging.basicConfig(filename=ordner() / "protokoll.txt", level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    if not nur_eine_instanz():
        return

    e = Einstellungen.laden()
    s = Steuerung(e)
    s.start()

    root = tk.Tk()
    fenster = Fenster(root, e, s)
    icon = infobereich_starten(root, fenster, s)
    if "--hintergrund" in sys.argv and icon is not None:
        fenster.verstecken()
    if icon is None:
        # Ohne pystray gibt es keinen Infobereich: Schließen beendet dann das Programm
        root.protocol("WM_DELETE_WINDOW", root.destroy)
        if "--hintergrund" in sys.argv:
            root.iconify()
    root.mainloop()
    s.beenden.set()
    s.wecken.set()
    if icon is not None:
        icon.stop()


if __name__ == "__main__":
    main()
