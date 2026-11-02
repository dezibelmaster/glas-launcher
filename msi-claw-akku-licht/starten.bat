@echo off
rem Installiert beim ersten Mal die benoetigten Pakete und startet Claw Akku-Licht.
cd /d "%~dp0"
python -m pip install --user -q -r requirements.txt
start "" pythonw claw_akku_licht.py
