@echo off
rem Baut dist\ClawAkkuLicht.exe (keine Python-Installation auf dem Claw noetig).
cd /d "%~dp0"
python -m pip install -q -r requirements.txt pyinstaller
python -m PyInstaller --noconfirm --onefile --windowed --name ClawAkkuLicht claw_akku_licht.py
echo.
echo Fertig: dist\ClawAkkuLicht.exe
pause
