@echo off
rem ============================================================================
rem  CoTransfert - point d'entree unique. Double-cliquer sur ce fichier.
rem   - trouve tout seul un Java 21 ou plus recent deja present sur le poste ;
rem   - lance l'agent SANS console (javaw), puis cette fenetre se ferme ;
rem   - l'agent ouvre la page de CoTransfert dans le navigateur : tout le reglage
rem     (role, dossiers, canaux) se fait dans cette page.
rem  Rien n'est installe, aucun droit administrateur n'est demande.
rem  Arreter CoTransfert : bouton "Quitter CoTransfert" de la page.
rem  Journal : cotransfert.log, a cote de cotransfert.conf.
rem
rem  CoTransfert.cmd --console       garde cette fenetre ouverte (diagnostic) :
rem                                  messages a l'ecran, fermer la fenetre arrete
rem                                  CoTransfert.
rem  CoTransfert.cmd --trouver-java  affiche le java.exe retenu et s'arrete.
rem ============================================================================
setlocal EnableExtensions EnableDelayedExpansion
title CoTransfert
cd /d "%~dp0"
set "JAR=%~dp0cotransfert.jar"

rem --- Options du script. Les modes ponctuels (secret, valise, mesure de debit)
rem     ecrivent a l'ecran : ils gardent la console eux aussi.
set "CONSOLE="
set "TROUVER="
call :options %*

rem --- Mise a jour en attente, ou jar a retablir (voir :echange).
call :echange

if not exist "%JAR%" (
    echo [ERREUR] cotransfert.jar est introuvable a cote de ce script :
    echo          %~dp0
    pause
    exit /b 1
)

rem --- Recherche de Java : choix force, JAVA_HOME, PATH, puis emplacements usuels.
set "JAVA="
if defined COTRANSFERT_JAVA call :essai "%COTRANSFERT_JAVA%"
if defined JAVA_HOME call :essai "%JAVA_HOME%\bin\java.exe"
for /f "delims=" %%p in ('where java.exe 2^>nul') do call :essai "%%p"
call :dossier "%USERPROFILE%\.jdks"
call :dossier "%ProgramFiles%\Java"
call :dossier "%ProgramFiles%\Eclipse Adoptium"
call :dossier "%ProgramFiles%\Microsoft"
call :dossier "%ProgramFiles%\Zulu"
call :dossier "%ProgramFiles%\Amazon Corretto"
call :dossier "%ProgramFiles%\BellSoft"
call :dossier "%ProgramFiles%\RedHat"
call :dossier "%ProgramFiles%\OpenJDK"
call :dossier "%ProgramFiles%\SapMachine"
call :dossier "%LOCALAPPDATA%\Programs\Eclipse Adoptium"
call :dossier "%LOCALAPPDATA%\Programs\Microsoft"
call :dossier "%LOCALAPPDATA%\Programs"
call :dossier "%USERPROFILE%\scoop\apps"
call :dossier "%USERPROFILE%\.sdkman\candidates\java"
call :dossier "%ProgramFiles%\JetBrains"
call :dossier "%LOCALAPPDATA%\JetBrains\Toolbox\apps"
call :dossier "%LOCALAPPDATA%\Programs\JetBrains"

if not defined JAVA (
    echo [ERREUR] Aucun Java 21 ou plus recent n'a ete trouve sur ce poste.
    echo.
    echo CoTransfert n'installe rien : il utilise un Java deja present.
    echo Si un Java 21+ existe ailleurs, indique son emplacement puis relance :
    echo     set COTRANSFERT_JAVA=C:\chemin\vers\bin\java.exe
    echo.
    pause
    exit /b 1
)

if defined TROUVER (
    echo !JAVA!
    exit /b 0
)

rem --- Lancement normal : sans console. javaw.exe est le meme Java, sans fenetre ;
rem     il est a cote du java.exe retenu. S'il manque, on garde la console.
set "COTRANSFERT_RELANCE="
set "COTRANSFERT_CONSOLE="
set "COTRANSFERT_ESSAI="
set "COTRANSFERT_RETOUR="
if defined CONSOLE goto console
set "JAVAW="
for %%j in ("!JAVA!") do set "JAVAW=%%~dpjjavaw.exe"
if not exist "!JAVAW!" (
    echo javaw.exe est absent a cote de !JAVA! : CoTransfert garde cette fenetre ouverte.
    goto console
)
start "" "!JAVAW!" -Djava.net.preferIPv4Stack=true -jar "%JAR%" %*
exit /b 0

rem --- Mode console (diagnostic) : l'agent reste au premier plan dans cette
rem     fenetre. Code de sortie 75 : il demande a etre relance (reglages modifies,
rem     changement de canal, mise a jour) ; COTRANSFERT_RELANCE=1 lui dit de ne
rem     pas rouvrir la page, deja ouverte, qui se reconnecte toute seule.
:console
set "COTRANSFERT_CONSOLE=1"
echo CoTransfert demarre avec !JAVA!
echo La page s'ouvre dans le navigateur. Fermer cette fenetre arrete CoTransfert.
echo.
:lancer
"!JAVA!" -Djava.net.preferIPv4Stack=true -jar "%JAR%" %*
set "CODE=!ERRORLEVEL!"
set "COTRANSFERT_RELANCE=1"
if "!CODE!"=="75" (
    call :echange
    goto lancer
)
if not "!CODE!"=="0" (
    echo.
    echo CoTransfert s'est arrete avec le code !CODE!.
    pause
)
exit /b !CODE!

rem --- Lit les options du script parmi les arguments (tous sont aussi passes a l'agent).
:options
if "%~1"=="" exit /b 0
if /i "%~1"=="--console" set "CONSOLE=1"
if /i "%~1"=="--trouver-java" set "TROUVER=1"
if /i "%~1"=="--new-secret" set "CONSOLE=1"
if /i "%~1"=="--export" set "CONSOLE=1"
if /i "%~1"=="--import" set "CONSOLE=1"
if /i "%~1"=="--bench" set "CONSOLE=1"
if /i "%~1"=="--no-web" set "CONSOLE=1"
shift
goto options

rem --- Mise a jour recue par le canal de transfert (voir README, "Mise a jour de
rem     l'autre poste"). Fichiers a cote du jar :
rem       cotransfert.jar.nouveau    version telechargee et verifiee par l'agent
rem       cotransfert.jar.precedent  version remplacee, gardee pour revenir en arriere
rem       cotransfert.jar.essai      temoin : la version en place n'a pas encore
rem                                  prouve qu'elle demarre (l'agent le retire)
rem     Chaque etape copie puis renomme : il reste toujours un cotransfert.jar
rem     complet. Si un agent tourne deja, son jar est verrouille : rien ne change.
:echange
rem     Jar absent : on prend la version en attente, sinon la precedente.
if not exist "%JAR%" if exist "%JAR%.nouveau" move /y "%JAR%.nouveau" "%JAR%" >nul 2>&1
if not exist "%JAR%" if exist "%JAR%.precedent" copy /y /b "%JAR%.precedent" "%JAR%" >nul 2>&1
rem     Temoin encore la : la derniere version installee n'a jamais tenu 20 s.
rem     On remet la precedente.
if not exist "%JAR%.essai" goto attente
if not exist "%JAR%.precedent" goto attente
copy /y /b "%JAR%.precedent" "%JAR%.retour" >nul 2>&1
if errorlevel 1 goto attente
move /y "%JAR%.retour" "%JAR%" >nul 2>&1
if errorlevel 1 goto attente
del "%JAR%.essai" >nul 2>&1
:attente
if exist "%JAR%.retour" del "%JAR%.retour" >nul 2>&1
rem     Version en attente : l'ancienne est gardee, la nouvelle prend sa place.
if not exist "%JAR%.nouveau" exit /b 0
if exist "%JAR%.essai" exit /b 0
copy /y /b "%JAR%" "%JAR%.precedent" >nul 2>&1
if errorlevel 1 exit /b 0
>"%JAR%.essai" echo essai
move /y "%JAR%.nouveau" "%JAR%" >nul 2>&1
if errorlevel 1 del "%JAR%.essai" >nul 2>&1
exit /b 0

rem --- Essaie chaque sous-dossier d'un emplacement d'installation.
:dossier
if defined JAVA exit /b 0
if not exist "%~1\" exit /b 0
for /d %%d in ("%~1\*") do (
    call :essai "%%~d\bin\java.exe"
    call :essai "%%~d\jbr\bin\java.exe"
    call :essai "%%~d\current\bin\java.exe"
)
exit /b 0

rem --- Retient ce java.exe s'il existe et s'il est en version 21 ou plus.
:essai
if defined JAVA exit /b 0
if "%~1"=="" exit /b 0
if not exist "%~1" exit /b 0
set "VER="
for /f "tokens=3" %%v in ('^""%~1" -version 2^>^&1 ^| findstr /i /c:" version "^"') do if not defined VER set "VER=%%~v"
if not defined VER exit /b 0
set "MAJ="
for /f "delims=.-+_" %%m in ("!VER!") do set "MAJ=%%m"
if not defined MAJ exit /b 0
set /a NUM=MAJ 2>nul
if !NUM! GEQ 21 set "JAVA=%~1"
exit /b 0
