@echo off
setlocal
rem Double-click to rebuild Lingo Lock from this folder and update it on your phone. Your progress is kept.
rem The phone must be connected by USB (or wireless debugging) with USB debugging on - see README.md.
rem   update-phone.cmd            build + install
rem   update-phone.cmd -NoBuild   install the APK that was built last
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\install.ps1" %*
set "rc=%errorlevel%"
echo.
if "%rc%"=="0" (
  echo Done - Lingo Lock on the phone is up to date.
) else (
  echo Update failed - see the message above.
)
pause
exit /b %rc%
