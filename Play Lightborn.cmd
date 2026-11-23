@echo off
rem Starts Minecraft 26.3 + Fabric with the Lightborn mod.
rem Own game folder (Game\): its own worlds, settings and mods. TLauncher and other versions are untouched.
set "HERE=%~dp0"
set "PMC=%HERE%..\..\..\Pipelines\vendor\portablemc\portablemc.exe"
set "DATA=%HERE%..\..\..\Pipelines\vendor\portablemc\data"
rem Run on the vendored JDK 25 the builds and tests use. portablemc 5.0.6 refreshes its own Mojang Java by file
rem size only, which left a mix of two Java versions on 2026-11-18 and crashed every start. Falls back to that
rem Java only when the vendored JDK is missing.
set "JVMOPT="
for /d %%J in ("%HERE%..\..\..\Pipelines\vendor\jdk25\jdk-25*") do if exist "%%J\bin\javaw.exe" set JVMOPT="--jvm=%%J\bin\javaw.exe"
title Lightborn - Minecraft is starting (keep this window open)
py -3.14 -I "%HERE%tools\prepare_game.py"
if errorlevel 1 (
  echo Lightborn could not prepare its music and resource packs.
  pause
  exit /b 1
)
"%PMC%" --main-dir "%DATA%" start fabric:26.3:0.20.1 --mc-dir "%HERE%Game" -u Light %JVMOPT% --jvm-arg=-Xmx6G
