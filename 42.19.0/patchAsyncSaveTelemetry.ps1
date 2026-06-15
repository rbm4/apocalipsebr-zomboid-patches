<#
.SYNOPSIS
    Combined deploy for Async Background Save (ServerMap) + ApocBR Server Telemetry.
#>
param(
    [string]$PZDir = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ToolsDir = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"
$PatchName = "Async Save + Server Telemetry + Guarded IsoWorld Parallelism"

if ([string]::IsNullOrWhiteSpace($ToolsDir)) {
    if (-not [string]::IsNullOrWhiteSpace($PSScriptRoot)) {
        $ToolsDir = $PSScriptRoot
    } elseif ($MyInvocation.MyCommand.Path) {
        $ToolsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    } else {
        $ToolsDir = (Get-Location).Path
    }
}
if ([string]::IsNullOrWhiteSpace($PZDir)) { throw "PZDir is empty." }

$GameJar = Join-Path $PZDir "projectzomboid.jar"
if (-not (Test-Path $GameJar)) { $GameJar = Join-Path $PZDir "java\projectzomboid.jar" }
$DeployRoot = if (Test-Path (Join-Path $PZDir "java")) { Join-Path $PZDir "java" } else { $PZDir }
$SrcRoot = Join-Path $ToolsDir "src"
$BackupDir = Join-Path $ToolsDir "backups\AsyncSaveTelemetry"
$TempRoot = if (-not [string]::IsNullOrWhiteSpace($env:TEMP)) { $env:TEMP } elseif (-not [string]::IsNullOrWhiteSpace($env:TMP)) { $env:TMP } else { [System.IO.Path]::GetTempPath() }
$WorkDir = Join-Path $TempRoot ("pzpatch_asyncsave_telemetry_" + [System.Diagnostics.Process]::GetCurrentProcess().Id + "_" + [DateTime]::UtcNow.Ticks)
$OutputDir = Join-Path $WorkDir "classes"
$RequiredMajor = 25

$Sources = @(
    (Join-Path $SrcRoot "zombie\ApocBRServerTelemetry.java"),
    (Join-Path $SrcRoot "zombie\MovingObjectUpdateScheduler.java"),
    (Join-Path $SrcRoot "zombie\MovingObjectUpdateSchedulerUpdateBucket.java"),
    (Join-Path $SrcRoot "zombie\vehicles\BaseVehicle.java"),
    (Join-Path $SrcRoot "zombie\network\GameServer.java"),
    (Join-Path $SrcRoot "zombie\gameStates\IngameState.java"),
    (Join-Path $SrcRoot "zombie\iso\IsoWorld.java"),
    (Join-Path $SrcRoot "zombie\iso\IsoCell.java"),
    (Join-Path $SrcRoot "zombie\network\PlayerDownloadServer.java"),
    (Join-Path $SrcRoot "zombie\network\ServerMap.java")
)
$ClassFiles = @(
    "zombie\ApocBRServerTelemetry.class",
    "zombie\MovingObjectUpdateScheduler.class",
    "zombie\MovingObjectUpdateSchedulerUpdateBucket.class",
    "zombie\vehicles\BaseVehicle.class",
    "zombie\vehicles\BaseVehicle`$1.class",
    "zombie\vehicles\BaseVehicle`$Authorization.class",
    "zombie\vehicles\BaseVehicle`$engineStateTypes.class",
    "zombie\vehicles\BaseVehicle`$HitVars.class",
    "zombie\vehicles\BaseVehicle`$L_testCollisionWithVehicle.class",
    "zombie\vehicles\BaseVehicle`$Matrix4fObjectPool.class",
    "zombie\vehicles\BaseVehicle`$MinMaxPosition.class",
    "zombie\vehicles\BaseVehicle`$ModelInfo.class",
    "zombie\vehicles\BaseVehicle`$Passenger.class",
    "zombie\vehicles\BaseVehicle`$QuaternionfObjectPool.class",
    "zombie\vehicles\BaseVehicle`$ServerVehicleState.class",
    "zombie\vehicles\BaseVehicle`$TransformPool.class",
    "zombie\vehicles\BaseVehicle`$UpdateFlags.class",
    "zombie\vehicles\BaseVehicle`$Vector2fObjectPool.class",
    "zombie\vehicles\BaseVehicle`$Vector3fObjectPool.class",
    "zombie\vehicles\BaseVehicle`$Vector3ObjectPool.class",
    "zombie\vehicles\BaseVehicle`$Vector4fObjectPool.class",
    "zombie\vehicles\BaseVehicle`$VehicleImpulse.class",
    "zombie\vehicles\BaseVehicle`$WeightedVehiclePart.class",
    "zombie\vehicles\BaseVehicle`$ApocBRBreakingResult.class",
    "zombie\vehicles\BaseVehicle`$WheelInfo.class",
    "zombie\network\GameServer.class",
    "zombie\network\GameServer`$1.class",
    "zombie\network\GameServer`$2.class",
    "zombie\network\GameServer`$CCFilter.class",
    "zombie\network\GameServer`$DelayedConnection.class",
    "zombie\network\GameServer`$MapRemotePlayerVisibility.class",
    "zombie\network\GameServer`$s_performance.class",
    "zombie\gameStates\IngameState.class",
    "zombie\gameStates\IngameState`$CountFileVisitor.class",
    "zombie\gameStates\IngameState`$s_performance.class",
    "zombie\iso\IsoWorld.class",
    "zombie\iso\IsoWorld`$CompDistToPlayer.class",
    "zombie\iso\IsoWorld`$CompScoreToPlayer.class",
    "zombie\iso\IsoWorld`$Frame.class",
    "zombie\iso\IsoWorld`$MetaCell.class",
    "zombie\iso\IsoWorld`$s_performance.class",
    "zombie\iso\IsoCell.class",
    "zombie\iso\IsoCell`$BuildingSearchCriteria.class",
    "zombie\iso\IsoCell`$PerPlayerRender.class",
    "zombie\iso\IsoCell`$SnowGrid.class",
    "zombie\iso\IsoCell`$SnowGridTiles.class",
    "zombie\iso\IsoCell`$s_performance.class",
    "zombie\iso\IsoCell`$s_performance`$renderTiles.class",
    "zombie\iso\IsoCell`$s_performance`$renderTiles`$PerformRenderTilesLayer.class",
    "zombie\network\PlayerDownloadServer.class",
    "zombie\network\PlayerDownloadServer`$EThreadCommand.class",
    "zombie\network\PlayerDownloadServer`$WorkerThread.class",
    "zombie\network\PlayerDownloadServer`$WorkerThreadCommand.class",
    "zombie\network\ServerMap.class",
    "zombie\network\ServerMap`$DistToCellComparator.class",
    "zombie\network\ServerMap`$EThreadCommand.class",
    "zombie\network\ServerMap`$ServerCell.class",
    "zombie\network\ServerMap`$WorkerThread.class",
    "zombie\network\ServerMap`$WorkerThreadCommand.class"
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
Write-Host "=== PZ Classpath Override Patch ===" -ForegroundColor White
Write-Host "=== $PatchName ===" -ForegroundColor White
Write-Host ""

if ($Revert) {
    $reverted = $false
    foreach ($rel in $ClassFiles) {
        $path = Join-Path $DeployRoot $rel
        if (Test-Path $path) { Remove-Item $path -Force; Write-Host "    Removed: $rel" -ForegroundColor Green; $reverted = $true }
    }
    if ($reverted) { Write-Host "=== Patch reverted. JAR originals restored on next server start. ===" -ForegroundColor White } else { Write-Host "    No patch files found to remove." }
    exit 0
}

if (-not (Test-Path $GameJar)) { throw "JAR not found: $GameJar" }
foreach ($src in $Sources) { if (-not (Test-Path $src)) { throw "Required patched source not found: $src" } }
$Javac = Find-Javac
if (-not $Javac) { throw "javac $RequiredMajor+ not found." }

Write-Host "[*] PZ dir:  $PZDir" -ForegroundColor Cyan
Write-Host "[*] JAR:     $GameJar" -ForegroundColor Cyan
Write-Host "[*] Deploy:  $DeployRoot" -ForegroundColor Cyan
Write-Host "[*] javac:   $Javac ($(& $Javac -version 2>&1))" -ForegroundColor Cyan

New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null
Write-Host "[*] Compiling combined patched sources..." -ForegroundColor Cyan
$JavacOut = Join-Path $WorkDir "javac.out.log"
$JavacErr = Join-Path $WorkDir "javac.err.log"
$JavacArgs = @("--release", "25", "-Xlint:none", "-implicit:none", "-cp", $GameJar, "-sourcepath", $SrcRoot, "-d", $OutputDir, "-encoding", "UTF-8") + $Sources
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
    Write-Host "[*] DRY RUN: Would deploy combined classes to $DeployRoot" -ForegroundColor Yellow
    foreach ($rel in $ClassFiles) { if (Test-Path (Join-Path $OutputDir $rel)) { Write-Host "    $rel" -ForegroundColor Yellow } }
} else {
    Write-Host "[*] Deploying..." -ForegroundColor Cyan
    New-Item -Path (Join-Path $DeployRoot "zombie") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie\network") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie\gameStates") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie\iso") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie\vehicles") -ItemType Directory -Force | Out-Null
    New-Item -Path $BackupDir -ItemType Directory -Force | Out-Null
    $ts = Get-Date -Format "yyyyMMdd_HHmmss"
    foreach ($rel in $ClassFiles) {
        $compiled = Join-Path $OutputDir $rel
        if (-not (Test-Path $compiled)) { continue }
        $dest = Join-Path $DeployRoot $rel
        if (Test-Path $dest) {
            $safe = $rel.Replace("\", "_")
            Copy-Item $dest (Join-Path $BackupDir "$safe.prev_$ts") -Force
        }
        Copy-Item $compiled $dest -Force
        Write-Host "    Deployed: $rel" -ForegroundColor Green
    }
}

Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
Write-Host ""
Write-Host "=== Done ===" -ForegroundColor White
Write-Host "Patch deployed: $PatchName" -ForegroundColor Green
Write-Host "Config: -Dapocbr.telemetry.enabled=true -Dapocbr.telemetry.intervalMs=30000 -Dapocbr.parallel.isoWorldSafe=true -Dapocbr.parallel.skipIfBacklogged=true" -ForegroundColor Gray

