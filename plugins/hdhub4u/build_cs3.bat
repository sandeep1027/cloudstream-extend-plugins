@echo off
REM Build this CloudStream plugin module as a .cs3 file (Windows).
REM
REM .cs3 format (per recloudstream docs + this fork's PluginManager):
REM   zip archive containing classes*.dex and manifest.json
REM
REM The module compiles against :library (compileOnly), so the jar holds only
REM the plugin's own classes — exactly what the app expects in a .cs3.
REM
REM Same script works in every plugins\<module> directory: it derives the module
REM name from its own location and the .cs3 name from manifest.json.

setlocal enabledelayedexpansion

REM --- repo root = two levels up from plugins\<module> -------------------
pushd "%~dp0"
for %%I in (".") do set "MODULE=%%~nxI"
popd
for %%I in ("%~dp0..") do set "PLUGINSROOT=%%~fI"
for %%I in ("%~dp0..\..") do set "ROOT=%%~fI"

if not defined ANDROID_HOME (
  set "SDKTMP=%TEMP%\cs3_sdkdir.txt"
  del /q "!SDKTMP!" >nul 2>nul
  powershell -NoProfile -Command "$l = Get-Content '%ROOT%\local.properties' | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1; if ($l) { $v = ($l -split '=',2)[1].Trim(); $v = $v -replace '\\:',':'; $v = $v -replace '\\\\','\'; [IO.File]::WriteAllText($env:TEMP + '\cs3_sdkdir.txt', $v) }"
  if exist "!SDKTMP!" set /p ANDROID_HOME=<"!SDKTMP!"
)
if not exist "%ANDROID_HOME%\build-tools" (
  echo ERROR: set ANDROID_HOME or add sdk.dir to local.properties
  exit /b 1
)

echo ==^> module: plugins\%MODULE%

echo ==^> Compiling plugin module
REM drop stale jars (e.g. from a previous module name) so we never dex the wrong one
if exist "%ROOT%\plugins\%MODULE%\build\libs" rmdir /s /q "%ROOT%\plugins\%MODULE%\build\libs"
call "%ROOT%\gradlew.bat" ":plugins:%MODULE%:jar" -q || exit /b 1

REM --- newest build-tools + platform ---------------------------------------
set "D8="
for /d %%D in ("%ANDROID_HOME%\build-tools\*") do set "D8=%%D\d8.bat"
if not exist "!D8!" (
  echo ERROR: no build-tools with d8.bat under %ANDROID_HOME%
  exit /b 1
)

set "ANDROID_JAR="
for /d %%P in ("%ANDROID_HOME%\platforms\android-*") do set "ANDROID_JAR=%%P\android.jar"

set "JAR="
for %%J in ("%ROOT%\plugins\%MODULE%\build\libs\*.jar") do set "JAR=%%~fJ"
if not defined JAR (
  echo ERROR: no jar built for plugins\%MODULE%
  exit /b 1
)
echo ==^> jar: !JAR!

set "OUT=%ROOT%\plugins\%MODULE%\build\cs3"
if exist "!OUT!" rmdir /s /q "!OUT!"
mkdir "!OUT!\dex" || exit /b 1

echo ==^> dex (d8, min-api 24)
call "!D8!" --release --lib "!ANDROID_JAR!" --min-api 24 --output "!OUT!\dex" "!JAR!" || exit /b 1

copy /y "%ROOT%\plugins\%MODULE%\manifest.json" "!OUT!\manifest.json" >nul

echo ==^> packaging .cs3
pushd "!OUT!"
REM A script file, not a -Command one-liner: this statement list is long
REM enough that cmd refuses to launch powershell for it.
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\tools\package_cs3.ps1" -Module "%MODULE%"
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" exit /b %RC%

echo ==^> done. copy the .cs3 into plugins\%MODULE%\repo\ and update repo/plugins.json
endlocal