<#
.SYNOPSIS
    Compiles and deploys ApocBR server responsiveness telemetry classes.

.PARAMETER PZDir
    Project Zomboid installation directory. Default: Z:\SteamLibrary\steamapps\common\ProjectZomboid
.PARAMETER ToolsDir
    Patch folder containing src\.
.PARAMETER DryRun
    Compile but do not deploy.
.PARAMETER Revert
    Remove loose class overrides for this telemetry patch.
#>
param(
    [string]$PZDir = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ToolsDir = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"
$PatchName = "ApocBR Server Telemetry"

# Resolve ToolsDir when patch launcher passes an empty value or dot-sources the script.
if ([string]::IsNullOrWhiteSpace($ToolsDir)) {
    if (-not [string]::IsNullOrWhiteSpace($PSScriptRoot)) {
        $ToolsDir = $PSScriptRoot
    } elseif ($MyInvocation.MyCommand.Path) {
        $ToolsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    } else {
        $ToolsDir = (Get-Location).Path
    }
}

if ([string]::IsNullOrWhiteSpace($PZDir)) {
    throw "PZDir is empty. Pass -PZDir with the Project Zomboid install directory."
}
$GameJar = Join-Path $PZDir "projectzomboid.jar"
if (-not (Test-Path $GameJar)) { $GameJar = Join-Path $PZDir "java\projectzomboid.jar" }
$DeployRoot = if (Test-Path (Join-Path $PZDir "java")) { Join-Path $PZDir "java" } else { $PZDir }
$SrcRoot = Join-Path $ToolsDir "src"
$BackupDir = Join-Path $ToolsDir "backups\ServerTelemetry"
$TempRoot = if (-not [string]::IsNullOrWhiteSpace($env:TEMP)) { $env:TEMP } elseif (-not [string]::IsNullOrWhiteSpace($env:TMP)) { $env:TMP } else { [System.IO.Path]::GetTempPath() }
$WorkDir = Join-Path $TempRoot ("pzpatch_servertelemetry_" + [System.Diagnostics.Process]::GetCurrentProcess().Id + "_" + [DateTime]::UtcNow.Ticks)
$OutputDir = Join-Path $WorkDir "classes"
$RequiredMajor = 25
$ClassFiles = @(
    "zombie\ApocBRServerTelemetry.class",
    "zombie\network\GameServer.class",
    "zombie\network\PlayerDownloadServer.class",
    "zombie\network\PlayerDownloadServer`$EThreadCommand.class",
    "zombie\network\PlayerDownloadServer`$WorkerThread.class",
    "zombie\network\PlayerDownloadServer`$WorkerThreadCommand.class"
)

function Get-JavacVersion { param([string]$JavacPath) try { $o = & $JavacPath -version 2>&1 | Out-String; if ($o -match "javac\s+(\d+)") { return [int]$Matches[1] } } catch {}; return 0 }
function Find-Javac {
    $cmd = Get-Command javac -ErrorAction SilentlyContinue
    if ($cmd) { $ver = Get-JavacVersion $cmd.Source; if ($ver -ge $RequiredMajor) { return $cmd.Source } }
    $local = Join-Path $ToolsDir "jdk\bin\javac.exe"
    if (Test-Path $local) { $ver = Get-JavacVersion $local; if ($ver -ge $RequiredMajor) { return $local } }
    $found = Get-ChildItem -Path "C:\Program Files\Zulu\zulu-$RequiredMajor*\bin\javac.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { return $found.FullName }
    return $null
}

Write-Host ""
Write-Host "=== $PatchName ===" -ForegroundColor White
Write-Host ""

if ($Revert) {
    foreach ($rel in $ClassFiles) {
        $path = Join-Path $DeployRoot $rel
        if (Test-Path $path) { Remove-Item $path -Force; Write-Host "    Removed: $rel" -ForegroundColor Green }
    }
    Get-ChildItem (Join-Path $DeployRoot "zombie\network") -Filter "GameServer`$*.class" -ErrorAction SilentlyContinue | Remove-Item -Force
    Write-Host "=== Patch reverted ===" -ForegroundColor White
    exit 0
}

$Javac = Find-Javac
if (-not $Javac) { throw "javac $RequiredMajor+ not found. Install JDK 25 or run an existing patch script that downloads it." }
if (-not (Test-Path $GameJar)) { throw "JAR not found: $GameJar" }

Write-Host "[*] PZ dir:  $PZDir" -ForegroundColor Cyan
Write-Host "[*] JAR:     $GameJar" -ForegroundColor Cyan
Write-Host "[*] Deploy:  $DeployRoot" -ForegroundColor Cyan
Write-Host "[*] javac:   $Javac ($(& $Javac -version 2>&1))" -ForegroundColor Cyan

New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null

Write-Host "[*] Compiling telemetry patched sources..." -ForegroundColor Cyan
$JavacOut = Join-Path $WorkDir "javac.out.log"
$JavacErr = Join-Path $WorkDir "javac.err.log"
$JavacArgs = @(
    "--release", "25",
    "-Xlint:none",
    "-implicit:none",
    "-cp", $GameJar,
    "-sourcepath", $SrcRoot,
    "-d", $OutputDir,
    "-encoding", "UTF-8",
    (Join-Path $SrcRoot "zombie\ApocBRServerTelemetry.java"),
    (Join-Path $SrcRoot "zombie\network\GameServer.java"),
    (Join-Path $SrcRoot "zombie\network\PlayerDownloadServer.java")
)
$JavacProcess = Start-Process -FilePath $Javac -ArgumentList $JavacArgs -Wait -NoNewWindow -PassThru -RedirectStandardOutput $JavacOut -RedirectStandardError $JavacErr
$javacOutput = @()
if (Test-Path $JavacOut) { $javacOutput += Get-Content $JavacOut }
if (Test-Path $JavacErr) { $javacOutput += Get-Content $JavacErr }
if ($JavacProcess.ExitCode -ne 0) {
    $javacOutput | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    throw "javac failed with exit code $($JavacProcess.ExitCode)"
}
$javacOutput | Where-Object { $_ -notmatch "^Note:" -and $_ -notmatch "^Recompile with" } | ForEach-Object { Write-Host $_ }
Write-Host "    Compiled successfully." -ForegroundColor Green
if ($DryRun) {
    Write-Host "[*] DRY RUN: Would deploy telemetry classes to $DeployRoot" -ForegroundColor Yellow
} else {
    Write-Host "[*] Deploying..." -ForegroundColor Cyan
    New-Item -Path (Join-Path $DeployRoot "zombie\network") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie") -ItemType Directory -Force | Out-Null
    New-Item -Path $BackupDir -ItemType Directory -Force | Out-Null
    $ts = Get-Date -Format "yyyyMMdd_HHmmss"
    foreach ($rel in $ClassFiles) {
        $existing = Join-Path $DeployRoot $rel
        if (Test-Path $existing) {
            $safe = $rel.Replace("\", "_")
            Copy-Item $existing (Join-Path $BackupDir "$safe.prev_$ts") -Force
        }
    }
    Write-Host "    Existing override backed up when present: $BackupDir" -ForegroundColor Gray
    Copy-Item (Join-Path $OutputDir "zombie\ApocBRServerTelemetry.class") (Join-Path $DeployRoot "zombie") -Force
    Copy-Item (Join-Path $OutputDir "zombie\network\GameServer*.class") (Join-Path $DeployRoot "zombie\network") -Force
    Copy-Item (Join-Path $OutputDir "zombie\network\PlayerDownloadServer*.class") (Join-Path $DeployRoot "zombie\network") -Force
    Write-Host "    Deployed telemetry classes." -ForegroundColor Green
}

Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
Write-Host ""
Write-Host "=== Done ===" -ForegroundColor White
Write-Host "Config: -Dapocbr.telemetry.enabled=true -Dapocbr.telemetry.intervalMs=30000" -ForegroundColor Gray
