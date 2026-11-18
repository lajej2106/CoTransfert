# ============================================================================
#  CoTransfert - lanceur client de MESURE DE DEBIT (bench), version PowerShell.
#  Auto-detecte Java, telecharge le jar depuis le hub, lance le bench.
#  Cote hub : java -jar cotransfert.jar --role hub --bench
#  Lancement :
#    powershell -ExecutionPolicy Bypass -File cotransfert-client.ps1 -Hub <ip-hub> [-Gib 8]
# ============================================================================
param(
    [Parameter(Mandatory = $true)][string]$Hub,   # IP du hub sur le lien direct
    [int]$HttpPort = 47780,
    [string]$Dir = "",                            # dossier du jar ; defaut = dossier de ce script
    [string]$Java = "",                           # chemin de java.exe ; defaut = detection automatique
    [int]$Gib = 8
)
# Continue : la sortie stderr des commandes natives (java -version) ne doit pas
# faire planter le script. Les erreurs cmdlet critiques sont gerees via try/catch.
$ErrorActionPreference = "Continue"
if (-not $Dir) { $Dir = Split-Path -Parent $MyInvocation.MyCommand.Path }

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

$javaExe = Find-Java
if (-not $javaExe -or -not (Test-Path $javaExe)) {
    Write-Host "[ERREUR] Aucun java.exe trouve (JAVA_HOME, PATH, scoop). Precise -Java <chemin de java.exe>." -ForegroundColor Red
    Read-Host "Entree pour quitter"; exit 1
}
Write-Host "[OK] Java : $javaExe" -ForegroundColor Green
(& $javaExe -version 2>&1)[0] | ForEach-Object { Write-Host "     $_" }

New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$jar = Join-Path $Dir "cotransfert.jar"

Write-Host "[..] Telechargement du jar depuis http://${Hub}:$HttpPort ..." -ForegroundColor Cyan
try {
    Invoke-WebRequest "http://${Hub}:$HttpPort/cotransfert.jar" -OutFile $jar -UseBasicParsing -TimeoutSec 15 -ErrorAction Stop
    Write-Host "[OK] Jar : $jar ($((Get-Item $jar).Length) octets)" -ForegroundColor Green
} catch {
    Write-Host "[ERREUR] Telechargement echoue : $($_.Exception.Message.Split([char]10)[0])" -ForegroundColor Red
    Write-Host "         Le hub est-il lance ? Teste : ping $Hub"
    Read-Host "Entree pour quitter"; exit 1
}

$jargs = @("-Djava.net.preferIPv4Stack=true","-jar",$jar,"--role","client","--bench","--gib","$Gib")
Write-Host "[..] Lancement : $javaExe $($jargs -join ' ')" -ForegroundColor Cyan
Write-Host ("-" * 76)
& $javaExe @jargs
Write-Host ("-" * 76)
Write-Host "[FIN] code $LASTEXITCODE"
Read-Host "Entree pour quitter"
