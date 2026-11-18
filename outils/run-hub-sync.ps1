# ============================================================================
#  CoTransfert - lanceur HUB.
#  - Detecte l'IP du lien direct (APIPA 169.254.x.x) de ce poste.
#  - Sert le jar et le lanceur client en HTTP pour le bootstrap / la mise a jour
#    du client.
#  - Lance l'agent hub (maitre) sur le ou les dossiers a synchroniser.
#  Lancement :
#    powershell -ExecutionPolicy Bypass -File run-hub-sync.ps1 -Dir C:\work\MonProjet
#  Une fois cotransfert.conf cree, -Dir n'est plus necessaire.
# ============================================================================
param(
    [string]$Dir  = "",           # dossier a synchroniser (1er lancement seulement)
    [string]$Name = "",           # nom logique du dossier (identique des deux cotes) ; defaut = nom du dossier
    [string]$Java = "",           # chemin de java.exe ; defaut = JAVA_HOME puis PATH
    [int]$HttpPort = 47780,
    [string]$OnConflict = "keep-both",
    [string]$Relay = "",          # mode degrade : dossier relais (ou "*:\Dossier" pour une cle USB) ; memorise dans la conf
    [string]$Secret = ""          # secret partage (chiffrement) ; memorise dans la conf
)
$ErrorActionPreference = "Continue"
$proj = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar  = Join-Path $proj "cotransfert.jar"
$conf = Join-Path $proj "cotransfert.conf"

function Find-Java {
    if ($Java) { return $Java }
    if ($env:JAVA_HOME) {
        $p = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path $p) { return $p }
    }
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$javaExe = Find-Java
if (-not $javaExe -or -not (Test-Path $javaExe)) { Write-Host "[ERREUR] Java introuvable (JAVA_HOME, PATH). Precise -Java <chemin de java.exe>." -ForegroundColor Red; Read-Host "Entree"; exit 1 }
if (-not (Test-Path $jar))  { Write-Host "[ERREUR] Jar introuvable : $jar (compile-le d'abord)" -ForegroundColor Red; Read-Host "Entree"; exit 1 }
if (-not (Test-Path $conf) -and -not $Dir) { Write-Host "[ERREUR] Premier lancement : precise le dossier a synchroniser avec -Dir <chemin>." -ForegroundColor Red; Read-Host "Entree"; exit 1 }
if ($Dir -and -not $Name) { $Name = Split-Path -Leaf $Dir }
if ($Dir -and -not (Test-Path $Dir)) { Write-Host "[ATTENTION] Dossier a synchroniser absent : $Dir" -ForegroundColor Yellow; Write-Host "            Il sera cree au besoin par l'agent." }

# IP du lien direct du hub (pour le serveur HTTP de bootstrap) : premiere adresse
# APIPA d'une carte active, en preferant une carte Thunderbolt/USB4 si presente.
$hubIp = $null
$apipa = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object IPAddress -like '169.254.*')
$up = @(Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object Status -eq 'Up')
$cands = @($apipa | Where-Object { $up.ifIndex -contains $_.InterfaceIndex })
$pref = @($cands | Where-Object { $idx = $_.InterfaceIndex; ($up | Where-Object ifIndex -eq $idx).InterfaceDescription -match 'Thunderbolt|USB4' })
if ($pref.Count -gt 0) { $hubIp = $pref[0].IPAddress } elseif ($cands.Count -gt 0) { $hubIp = $cands[0].IPAddress }

# Serveur HTTP pour distribuer le jar au client. Lie a l'IP du lien direct
# uniquement : sans lien detecte, on ne sert rien (pas d'exposition sur les
# autres reseaux).
$py = Get-Command python -ErrorAction SilentlyContinue
if ($hubIp -and $py) {
    Write-Host "[OK] IP du lien direct : $hubIp" -ForegroundColor Green
    Start-Process python -ArgumentList "-m","http.server","$HttpPort","--bind",$hubIp -WorkingDirectory $proj -WindowStyle Hidden
    Write-Host "[OK] Jar servi sur http://${hubIp}:$HttpPort/cotransfert.jar" -ForegroundColor Green
} elseif (-not $hubIp) {
    Write-Host "[INFO] Aucun lien direct (169.254.x.x) detecte : jar non servi. Branche le cable puis relance, ou copie le jar a la main sur le client." -ForegroundColor Yellow
} else {
    Write-Host "[INFO] python absent : copie le jar manuellement sur le client." -ForegroundColor Yellow
}

# Si cotransfert.conf existe deja, il FAIT AUTORITE : on ne force rien en ligne
# de commande (sinon --dir ecraserait tes modifications de la config).
# Sinon, on bootstrappe une config par defaut, que l'agent enregistrera.
if (Test-Path $conf) {
    Write-Host "[OK] Config existante ($conf) : elle fait foi." -ForegroundColor Green
    $jargs = @("-Djava.net.preferIPv4Stack=true","-jar",$jar)
} else {
    Write-Host "[..] Creation de la config par defaut (role hub, dossier $Name)." -ForegroundColor Cyan
    $jargs = @("-Djava.net.preferIPv4Stack=true","-jar",$jar,"--role","hub","--dir","$Name=$Dir","--master","--on-conflict",$OnConflict)
}
if ($Relay)  { $jargs += @("--relay", $Relay) }
if ($Secret) { $jargs += @("--secret", $Secret) }
Write-Host ""
Write-Host "[..] Agent HUB (maitre) - 1er lancement : accepter l'alerte pare-feu (Reseaux publics)." -ForegroundColor Yellow
Write-Host "     IHM web : http://localhost:47790 (s'ouvre automatiquement)." -ForegroundColor Green
Write-Host ("-" * 76)
# Ouvre l'IHM dans le navigateur quelques secondes apres le demarrage du serveur.
Start-Job { Start-Sleep 4; Start-Process "http://localhost:47790" } | Out-Null
& $javaExe @jargs
Write-Host ("-" * 76)
Read-Host "Agent arrete. Entree pour quitter"
