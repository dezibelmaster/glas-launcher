"""Ansteuerung der RGB-LEDs (Stick-Ringe) des MSI Claw über HID.

Das Protokoll stammt aus dem quelloffenen Handheld Daemon (hhd-dev/hhd,
hhd/device/claw/base.py). Der Controller des Claw meldet sich als USB-HID-Gerät
mit der Hersteller-ID 0x0DB0. Die Farbe wird als „Profil 1“ in den Speicher
des Controllers geschrieben; die Adresse hängt von der Firmware-Version ab.
"""

from __future__ import annotations

import logging

log = logging.getLogger(__name__)

MSI_VID = 0x0DB0
# 0x1901 = XInput-Modus, 0x1902 = DInput-Modus, 0x1903 = Testmodus
MSI_PIDS = (0x1901, 0x1902, 0x1903)

# Hersteller-Schnittstellen (usage_page << 16 | usage), über die Befehle gehen
VENDOR_APPLICATIONS = (0xFFF00040, 0x00010005, 0xFF000000, 0xFFA00001)

# Speicheradresse der LED-Einstellung je nach Firmware
ADDR_ALT = (0x01, 0xFA)  # Firmware bis 0x0163 / 0x0211
ADDR_NEU = (0x02, 0x4A)  # Firmware ab 0x0166 / 0x0217 / 3.x (Claw 8 AI+)

LED_ANZAHL = 9
REPORT_LAENGE = 64


def adresse_fuer_firmware(version: int) -> tuple[int, int]:
    major = version >> 8
    if (major == 1 and version >= 0x0166) or (major == 2 and version >= 0x0217) or major >= 3:
        return ADDR_NEU
    if version == 0:
        return ADDR_NEU
    return ADDR_ALT


def farb_befehl(rot: int, gruen: int, blau: int, helligkeit: int,
                adresse: tuple[int, int] = ADDR_NEU) -> bytes:
    """Baut den 64-Byte-Report für eine einfarbige Beleuchtung.

    helligkeit: 0..100 (Prozent)
    """
    helligkeit = max(0, min(100, int(helligkeit)))
    kopf = bytes([
        0x0F, 0x00, 0x00, 0x3C,   # Präambel (Report-ID 0x0F)
        0x21, 0x01,               # Profil 1 schreiben
        *adresse,                 # Startadresse
        0x20,                     # 32 Byte Daten
        0x00, 0x01, 0x09, 0x03,   # Index, Frame-Anzahl, Effekt (fest), Tempo
        helligkeit,
    ])
    farbe = bytes([max(0, min(255, int(c))) for c in (rot, gruen, blau)])
    daten = kopf + farbe * LED_ANZAHL
    return daten + bytes(REPORT_LAENGE - len(daten))


class ClawLed:
    """Verbindung zum Controller. Öffnet sich bei Bedarf neu (z. B. nach Standby)."""

    def __init__(self) -> None:
        self._dev = None
        self._adresse = ADDR_NEU
        self.geraet_name = ""

    @staticmethod
    def _hid():
        import hid  # Paket „hidapi“
        return hid

    def _finden(self):
        hid = self._hid()
        kandidaten = []
        for info in hid.enumerate(MSI_VID, 0):
            if info.get("product_id") not in MSI_PIDS:
                continue
            app = (info.get("usage_page", 0) << 16) | info.get("usage", 0)
            kandidaten.append((app in VENDOR_APPLICATIONS, info))
        # Hersteller-Schnittstellen zuerst probieren
        kandidaten.sort(key=lambda k: not k[0])
        return [info for _, info in kandidaten]

    def verbinden(self) -> bool:
        if self._dev is not None:
            return True
        hid = self._hid()
        for info in self._finden():
            dev = hid.device()
            try:
                dev.open_path(info["path"])
            except OSError:
                continue
            self._dev = dev
            version = info.get("release_number", 0)
            self._adresse = adresse_fuer_firmware(version)
            self.geraet_name = f"{info.get('product_string') or 'MSI Claw'} (Firmware {version:#06x})"
            log.info("Verbunden mit %s über %s", self.geraet_name, info["path"])
            return True
        return False

    def trennen(self) -> None:
        if self._dev is not None:
            try:
                self._dev.close()
            except Exception:
                pass
        self._dev = None

    def setze_farbe(self, rgb: tuple[int, int, int], helligkeit: int) -> bool:
        """Setzt die Farbe. Gibt False zurück, wenn kein Claw gefunden wurde."""
        for _ in range(2):
            if not self.verbinden():
                return False
            try:
                self._dev.write(farb_befehl(*rgb, helligkeit, self._adresse))
                return True
            except (OSError, ValueError) as e:
                log.warning("Schreiben fehlgeschlagen, verbinde neu: %s", e)
                self.trennen()
        return False
