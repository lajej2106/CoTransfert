# ============================================================================
#  CoTransfert - lanceur CLIENT.
#  - Auto-detecte un java.exe deja present sur le poste.
#  - Decouvre l'IP du hub (voisinage ARP du lien direct) pour recuperer le jar.
#  - Se met a jour tout seul (lanceur + jar) depuis le hub.
#  - Lance l'agent client (esclave) ; l'agent redecouvre le hub pour la sync.
#  Lancement :
#    powershell -ExecutionPolicy Bypass -File run-client-sync.ps1 -Dir C:\work\MonProjet -Name MonProjet
#  Une fois cotransfert.conf cree, -Dir / -Name ne sont plus necessaires.
# ============================================================================
param(
    [string]$Dir = "",              # dossier a synchroniser (1er lancement seulement)
    [string]$Name = "",             # nom logique (identique cote hub) ; defaut = nom du dossier
    [string]$JarDir = "",           # dossier du jar et de la conf ; defaut = dossier de ce script
    [string]$Java = "",             # chemin de java.exe ; defaut = detection automatique
    [int]$HttpPort = 47780,
    [string]$HubFallback = "",      # IP du hub a essayer si la decouverte echoue
    [string]$OnConflict = "keep-both",
    [string]$Relay = "",            # mode degrade : dossier relais (ou "*:\Dossier" pour une cle USB) ; memorise dans la conf
    [string]$Secret = "",           # secret partage (chiffrement) ; memorise dans la conf
    [switch]$NoSelfUpdate          # garde anti-boucle apres auto-mise a jour du lanceur
)
$ErrorActionPreference = "Continue"
$self = $MyInvocation.MyCommand.Path
if (-not $JarDir) { $JarDir = Split-Path -Parent $self }

# Cherche un Java utilisable SANS rien installer : parametre, JAVA_HOME, PATH,
# puis les emplacements d'installation "utilisateur" courants (scoop).
function Find-Java {
    if ($Java) { return $Java }
    if ($env:JAVA_HOME) {
        $p = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path $p) { return $p }
    }
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $scoopRoots = @($env:SCOOP, (Join-Path $env:USERPROFILE "scoop")) | Where-Object { $_ }
    foreach ($r in $scoopRoots) {
        $g = Get-ChildItem (Join-Path $r "apps\*jdk*\current\bin\java.exe") -ErrorAction SilentlyContinue |
             Sort-Object FullName -Descending | Select-Object -First 1
        if ($g) { return $g.FullName }
    }
    return $null
}

# Cherche le hub : voisin 169.254.x qui sert le jar en HTTP.
function Find-Hub {
    foreach ($ip in (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object IPAddress -like '169.254.*')) {
        $neigh = Get-NetNeighbor -InterfaceIndex $ip.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -like '169.254.*' -and $_.IPAddress -ne $ip.IPAddress -and
                           $_.State -in 'Reachable','Stale','Permanent' -and $_.LinkLayerAddress -and
                           $_.LinkLayerAddress -notlike 'FF-*' -and $_.LinkLayerAddress -notlike '01-00-5E*' }
        foreach ($n in $neigh) {
            try {
                $r = Invoke-WebRequest "http://$($n.IPAddress):$HttpPort/cotransfert.jar" -Method Head -TimeoutSec 2 -UseBasicParsing
                if ($r.StatusCode -eq 200) { return $n.IPAddress }
            } catch { }
        }
    }
    return $null
}

$javaExe = Find-Java
if (-not $javaExe -or -not (Test-Path $javaExe)) { Write-Host "[ERREUR] Aucun java.exe trouve (JAVA_HOME, PATH, scoop). Precise -Java <chemin de java.exe>." -ForegroundColor Red; Read-Host "Entree"; exit 1 }
Write-Host "[OK] Java : $javaExe" -ForegroundColor Green
(& $javaExe -version 2>&1)[0] | ForEach-Object { Write-Host "     $_" }

New-Item -ItemType Directory -Force -Path $JarDir | Out-Null
$jar  = Join-Path $JarDir "cotransfert.jar"
$conf = Join-Path $JarDir "cotransfert.conf"

Write-Host "[..] Recherche du hub sur le lien direct..." -ForegroundColor Cyan
$hub = Find-Hub
if (-not $hub -and $HubFallback) {
    Write-Host "[INFO] Hub non detecte via ARP, essai de secours : $HubFallback" -ForegroundColor Yellow
    $hub = $HubFallback
}
if ($hub) { Write-Host "[OK] Hub : $hub" -ForegroundColor Green }
else      { Write-Host "[INFO] Hub non detecte : pas de verification de mise a jour (l'agent le recherchera lui-meme)." -ForegroundColor Yellow }

# --- Auto-mise a jour du LANCEUR lui-meme (si une nouvelle version est publiee) ---
if ($hub -and -not $NoSelfUpdate) {
    try {
        $remote = (Invoke-WebRequest "http://${hub}:$HttpPort/run-client-sync.ps1" -UseBasicParsing -TimeoutSec 8 -ErrorAction Stop).Content
        $local = Get-Content $self -Raw
        if ($remote -and ($remote.Trim() -ne $local.Trim())) {
            Write-Host "[MAJ] Nouvelle version du lanceur, mise a jour et redemarrage..." -ForegroundColor Magenta
            [System.IO.File]::WriteAllText($self, $remote)  # UTF-8 sans BOM (evite une re-MAJ a chaque lancement)
            # On relance avec les memes parametres (+ la garde anti-boucle).
            $fwd = @()
            foreach ($k in $PSBoundParameters.Keys) {
                $v = $PSBoundParameters[$k]
                if ($v -is [switch]) { if ($v) { $fwd += "-$k" } } else { $fwd += "-$k"; $fwd += "$v" }
            }
            & powershell -ExecutionPolicy Bypass -File $self @fwd -NoSelfUpdate
            exit $LASTEXITCODE
        }
    } catch { Write-Host "[INFO] Verif lanceur ignoree ($($_.Exception.Message.Split([char]10)[0]))" -ForegroundColor DarkGray }
}

# --- Mise a jour du JAR : on telecharge seulement si la taille differe ---
if ($hub) {
    try {
        $remoteLen = [int64](Invoke-WebRequest "http://${hub}:$HttpPort/cotransfert.jar" -Method Head -UseBasicParsing -TimeoutSec 8 -ErrorAction Stop).Headers['Content-Length']
        $localLen = if (Test-Path $jar) { (Get-Item $jar).Length } else { -1 }
        if ($remoteLen -ne $localLen) {
            Write-Host "[MAJ] Nouvelle version du jar ($remoteLen o), telechargement..." -ForegroundColor Magenta
            Invoke-WebRequest "http://${hub}:$HttpPort/cotransfert.jar" -OutFile $jar -UseBasicParsing -TimeoutSec 20 -ErrorAction Stop
        } else {
            Write-Host "[OK] Jar deja a jour ($localLen o)." -ForegroundColor Green
        }
    } catch {
        if (Test-Path $jar) { Write-Host "[INFO] Verif jar KO, on garde la version locale." -ForegroundColor Yellow }
    }
}
if (-not (Test-Path $jar)) {
    Write-Host "[ERREUR] Pas de jar dans $JarDir et telechargement impossible. Le hub est-il lance et le cable branche ?" -ForegroundColor Red
    Write-Host "         Sinon, copie cotransfert.jar a la main dans ce dossier."
    Read-Host "Entree"; exit 1
}

# cotransfert.conf fait autorite s'il existe : on ne force pas --dir.
if (Test-Path $conf) {
    Write-Host "[OK] Config existante ($conf) : elle fait foi." -ForegroundColor Green
    $jargs = @("-Djava.net.preferIPv4Stack=true","-jar",$jar)
} else {
    if (-not $Dir) { Write-Host "[ERREUR] Premier lancement : precise le dossier a synchroniser avec -Dir <chemin> (et -Name <nom logique>, identique cote hub)." -ForegroundColor Red; Read-Host "Entree"; exit 1 }
    if (-not $Name) { $Name = Split-Path -Leaf $Dir }
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    Write-Host "[..] Creation de la config par defaut (role client, dossier $Name)." -ForegroundColor Cyan
    $jargs = @("-Djava.net.preferIPv4Stack=true","-jar",$jar,"--role","client","--slave","--dir","$Name=$Dir","--on-conflict",$OnConflict)
}
if ($Relay)  { $jargs += @("--relay", $Relay) }
if ($Secret) { $jargs += @("--secret", $Secret) }
Write-Host ""
Write-Host "[..] Agent CLIENT (esclave) - lien direct si possible, sinon relais s'il est configure." -ForegroundColor Cyan
Write-Host ("-" * 76)
& $javaExe @jargs
Write-Host ("-" * 76)
Read-Host "Agent arrete. Entree pour quitter"
