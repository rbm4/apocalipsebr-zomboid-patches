<#
.SYNOPSIS
    Compiles patched MovingObjectUpdateScheduler.java and deploys to Project Zomboid.

.DESCRIPTION
    Scheduler Performance Optimization - Patch D

    Context: With 30-40 parked vehicles in view, ALL of them run update() and
    postupdate() every single frame because BaseVehicle.getMinimumSimulationLevel()
    returns FULL unconditionally. This hardcodes all vehicles into the fullSimulation
    bucket, bypassing the distance/FPS throttle logic entirely.

    Patch D - QUARTER tier for parked vehicles:
      Intercepts getUpdateSchedulerSimulationLevelForObject() BEFORE the
      minSim==FULL short-circuit. For vehicles with no driver and engine idle,
      returns QUARTER immediately, assigning them to the quarterSimulation bucket
      (frameMod=4). The bucket distributes objects by (objectId % 4), so only
      ~25% of parked vehicles (≈10 of 40) fire update()+postupdate() on any
      given frame instead of all 40 every frame.

      Effect: 75% reduction in per-frame BaseVehicle.update() + postupdate() calls
      for parked vehicles on the client. Server is unaffected (server scheduler
      always returns FULL regardless of this method).

      When a driver enters or the engine starts, the vehicle is no longer caught by
      the parked check and falls through to normal (FULL) scheduling immediately.

.PARAMETER PZDir
    Path to the Project Zomboid installation directory.
    Default: Z:\SteamLibrary\steamapps\common\ProjectZomboid

.PARAMETER DryRun
    If set, shows what would be done without actually deploying.

.PARAMETER Revert
    If set, removes the deployed .class override (restoring original JAR behavior).
#>
param(
    [string]$PZDir    = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ToolsDir = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

# --- Configuration ---
$PatchName     = "Scheduler Optimization - Patch D"
$GameJar       = Join-Path $PZDir "projectzomboid.jar"
$DeployDir     = Join-Path $PZDir "zombie"
$DeployClass   = Join-Path $DeployDir "MovingObjectUpdateScheduler.class"
$BackupDir     = Join-Path $ToolsDir "backups\MovingObjectUpdateScheduler"
$LocalJdkDir   = Join-Path $ToolsDir "jdk"
$WorkDir       = Join-Path $env:TEMP "pzpatch_scheduler"
$OutputDir     = Join-Path $WorkDir "classes"
$SourceFile    = Join-Path $ToolsDir "src\zombie\MovingObjectUpdateScheduler.java"
$RequiredMajor = 25

$ZuluApiUrl = "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$RequiredMajor&os=windows&arch=x64&archive_type=zip&java_package_type=jdk&latest=true"

# --- Functions ---
function Get-JavacVersion {
    param([string]$JavacPath)
    try {
        $output = & $JavacPath -version 2>&1 | Out-String
        if ($output -match "javac\s+(\d+)") { return [int]$Matches[1] }
    } catch {}
    return 0
}

function Find-Javac {
    Write-Host "[*] Searching for javac >= $RequiredMajor..." -ForegroundColor Cyan
    $localJavac = Join-Path $LocalJdkDir "bin\javac.exe"
    if (Test-Path $localJavac) {
        $ver = Get-JavacVersion $localJavac
        if ($ver -ge $RequiredMajor) {
            Write-Host "    Found local JDK: javac $ver" -ForegroundColor Green
            return $localJavac
        }
    }
    $pathJavac = Get-Command javac -ErrorAction SilentlyContinue
    if ($pathJavac) {
        $ver = Get-JavacVersion $pathJavac.Source
        if ($ver -ge $RequiredMajor) {
            Write-Host "    Found in PATH: javac $ver" -ForegroundColor Green
            return $pathJavac.Source
        }
    }
    return $null
}

function Backup-OriginalClasses {
    if ((Test-Path $BackupDir) -and (Get-ChildItem $BackupDir -Filter "*.original" -ErrorAction SilentlyContinue).Count -gt 0) {
        Write-Host "[*] Backup already exists: $BackupDir" -ForegroundColor Gray
        return
    }
    Write-Host "[*] Extracting original MovingObjectUpdateScheduler.class from JAR..." -ForegroundColor Cyan
    if (-not (Test-Path $BackupDir)) { New-Item -Path $BackupDir -ItemType Directory -Force | Out-Null }
    $tempDir = Join-Path $ToolsDir "tmp-extract-sched"
    if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
    New-Item -Path $tempDir -ItemType Directory -Force | Out-Null
    Push-Location $tempDir
    try {
        $jarExe = Get-Command jar -ErrorAction SilentlyContinue
        if (-not $jarExe) { Write-Host "WARNING: 'jar' not on PATH - skipping backup" -ForegroundColor Yellow; return }
        & $jarExe.Source xf $GameJar "zombie/MovingObjectUpdateScheduler.class" 2>$null
        $extracted = Join-Path $tempDir "zombie"
        if (Test-Path (Join-Path $extracted "MovingObjectUpdateScheduler.class")) {
            Copy-Item (Join-Path $extracted "MovingObjectUpdateScheduler.class") (Join-Path $BackupDir "MovingObjectUpdateScheduler.class.original")
            Write-Host "    Backed up MovingObjectUpdateScheduler.class" -ForegroundColor Green
        } else {
            Write-Host "    WARNING: Could not extract original class." -ForegroundColor Yellow
        }
    } finally {
        Pop-Location
        Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# --- Main ---
Write-Host ""
Write-Host "=== $PatchName ===" -ForegroundColor White
Write-Host ""

if ($Revert) {
    if (Test-Path $DeployClass) {
        Remove-Item $DeployClass -Force
        Write-Host "    Removed: MovingObjectUpdateScheduler.class" -ForegroundColor Green
        Write-Host "`n=== Patch reverted - original JAR behavior restored ===" -ForegroundColor White
    } else {
        Write-Host "    No patch file found to remove." -ForegroundColor Yellow
    }
    exit 0
}

if (-not (Test-Path $GameJar)) {
    Write-Host "ERROR: Game JAR not found: $GameJar" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $SourceFile)) {
    Write-Host "ERROR: Patched source not found: $SourceFile" -ForegroundColor Red
    exit 1
}

Backup-OriginalClasses

$javac = Find-Javac
if (-not $javac) {
    Write-Host "ERROR: javac >= $RequiredMajor not found. Run patchVehicleOptimizations.ps1 first to auto-install JDK, or install manually." -ForegroundColor Red
    exit 1
}

# Compile
Write-Host ""
Write-Host "[*] Compiling patched MovingObjectUpdateScheduler.java..." -ForegroundColor Cyan
if (Test-Path $OutputDir) { Remove-Item $OutputDir -Recurse -Force }
New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null

$TempSrcDir = Join-Path $WorkDir "src\zombie"
if (-not (Test-Path $TempSrcDir)) { New-Item -Path $TempSrcDir -ItemType Directory -Force | Out-Null }
Copy-Item $SourceFile (Join-Path $TempSrcDir "MovingObjectUpdateScheduler.java")

$compileArgs = @(
    "--release", "25",
    "-cp", $GameJar,
    "-d", $OutputDir,
    (Join-Path $TempSrcDir "MovingObjectUpdateScheduler.java")
)

if ($DryRun) {
    Write-Host "    [DryRun] Would run: $javac $($compileArgs -join ' ')" -ForegroundColor Yellow
} else {
    $javacStdout = Join-Path $env:TEMP "pzpatch_sched_javac_stdout.txt"
    $javacStderr = Join-Path $env:TEMP "pzpatch_sched_javac_stderr.txt"
    $proc = Start-Process -FilePath $javac -ArgumentList $compileArgs `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $javacStdout `
        -RedirectStandardError  $javacStderr
    $compileExitCode = $proc.ExitCode
    $stderrText = if (Test-Path $javacStderr) { Get-Content $javacStderr -Raw } else { "" }
    $stdoutText = if (Test-Path $javacStdout) { Get-Content $javacStdout -Raw } else { "" }
    Remove-Item $javacStdout, $javacStderr -Force -ErrorAction SilentlyContinue
    if ($compileExitCode -ne 0) {
        Write-Host "ERROR: Compilation failed!" -ForegroundColor Red
        if ($stdoutText) { Write-Host $stdoutText -ForegroundColor Red }
        if ($stderrText) { Write-Host $stderrText -ForegroundColor Red }
        exit 1
    }
    Write-Host "    Compiled successfully." -ForegroundColor Green
}

# Deploy
Write-Host ""
Write-Host "[*] Deploying to: $DeployDir" -ForegroundColor Cyan
if (-not (Test-Path $DeployDir)) { New-Item -Path $DeployDir -ItemType Directory -Force | Out-Null }

$CompiledFile = Join-Path $OutputDir "zombie\MovingObjectUpdateScheduler.class"

if ($DryRun) {
    Write-Host "    [DryRun] Would deploy: MovingObjectUpdateScheduler.class" -ForegroundColor Yellow
} else {
    if (-not (Test-Path $CompiledFile)) {
        Write-Host "ERROR: Compiled class not found at $CompiledFile" -ForegroundColor Red
        exit 1
    }
    Copy-Item $CompiledFile $DeployClass -Force
    Write-Host "    Deployed: MovingObjectUpdateScheduler.class" -ForegroundColor Green
}

Write-Host ""
if ($DryRun) {
    Write-Host "=== Dry run complete - no files were changed ===" -ForegroundColor Yellow
} else {
    Write-Host "=== Patch D applied successfully ===" -ForegroundColor White
    Write-Host ""
    Write-Host "Effect: parked vehicles (no driver + engine idle) run update() every 4 frames" -ForegroundColor Gray
    Write-Host "        instead of every frame. With 40 vehicles: max 10 per frame instead of 40." -ForegroundColor Gray
    Write-Host ""
    Write-Host "Restart the game for changes to take effect." -ForegroundColor Gray
}
Write-Host ""
