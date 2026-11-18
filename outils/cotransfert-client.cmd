@echo off
setlocal enabledelayedexpansion
rem ============================================================================
rem  CoTransfert - lanceur client de MESURE DE DEBIT (bench), version .cmd
rem  Telecharge le jar depuis le hub et le lance via un Java deja present.
rem  Usage : cotransfert-client.cmd <ip-hub> [Gio]
rem ============================================================================

rem --- Parametres ------------------------------------------------------------
set "HUB=%~1"
set "HTTP_PORT=47780"
set "DIR=%~dp0"
if "%DIR:~-1%"=="\" set "DIR=%DIR:~0,-1%"
set "GIB=%~2"
if "%GIB%"=="" set "GIB=8"

if "%HUB%"=="" (
  echo [ERREUR] Usage : cotransfert-client.cmd ^<ip-hub^> [Gio]
  goto :fin
)

rem --- 1) Trouver un java.exe deja present (JAVA_HOME puis PATH) --------------
set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /f "delims=" %%J in ('where java.exe 2^>nul') do if not defined JAVA set "JAVA=%%J"

if not defined JAVA (
  echo [ERREUR] Aucun java.exe trouve. Definis JAVA_HOME ou ajoute java au PATH.
  goto :fin
)
echo [OK] Java : !JAVA!

rem --- 2) Telecharger le jar depuis le hub -----------------------------------
echo [..] Telechargement du jar depuis http://%HUB%:%HTTP_PORT% ...
curl -s -f -o "%DIR%\cotransfert.jar" "http://%HUB%:%HTTP_PORT%/cotransfert.jar"
if errorlevel 1 (
  echo [ERREUR] Telechargement echoue. Le hub est-il lance et le cable branche ?
  echo          Test : ping %HUB%
  goto :fin
)
echo [OK] Jar telecharge dans %DIR%

rem --- 3) Lancer le client ----------------------------------------------------
echo [..] Lancement du bench (%GIB% Gio)...
echo ----------------------------------------------------------------------------
"!JAVA!" -Djava.net.preferIPv4Stack=true -jar "%DIR%\cotransfert.jar" --role client --bench --gib %GIB%
echo ----------------------------------------------------------------------------
echo [FIN] Code de sortie : %errorlevel%

:fin
echo.
pause
endlocal
