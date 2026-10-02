@echo off
setlocal
rem Drag a word list (.csv, .tsv or .txt) onto this file to add it as a new word set.
rem Just the words are enough - readings, meanings and example sentences are filled in automatically.
rem   add-words.cmd my-words.csv            language detected from the writing (or asked)
rem   add-words.cmd food.csv es "Food"      language code and set name
rem Importing a file with the same name again updates that set.
cd /d "%~dp0"
if "%~1"=="" (
  echo Drag a .csv / .txt word list onto add-words.cmd, or run:  add-words.cmd words.csv [language] [set name]
  pause
  exit /b 1
)
set "PY=python"
where python >nul 2>nul || set "PY=py -3"
set "ARGS="
if not "%~2"=="" set ARGS=--lang %~2
if not "%~3"=="" set ARGS=%ARGS% --name "%~3"
%PY% tools\sets.py import "%~1" %ARGS%
if errorlevel 1 (
  echo.
  echo Import failed - see the message above.
  pause
  exit /b 1
)
echo.
choice /c YN /m "Update the phone now"
if errorlevel 2 (
  echo OK - the new set goes onto the phone the next time you run update-phone.cmd.
  pause
  exit /b 0
)
call "%~dp0update-phone.cmd"
