<#
.SYNOPSIS
    Compiles patched BaseVehicle.java and deploys all .class files to Project Zomboid.

.DESCRIPTION
    Vehicle Performance Optimizations - BaseVehicle

    Context: With 30-40 parked vehicles loaded in a safehouse area, the game
    suffers a CPU-bound FPS drop (~100 → ~30 FPS). Two verified hot paths cause this:

    1. updateSignalDevice() called every tick for EVERY part of EVERY vehicle.
       With 40 vehicles × 2-4 device parts = 80-160 redundant calls/tick on the
       client. Radio device state changes slowly (once per message at most).

    2. AnimationPlayer.Update() called every frame for every sub-model of every
       vehicle (body + doors + windows × 40 cars). For stationary parked vehicles
       with no active animations, this is pure CPU waste: full bone matrix
       interpolation + FloatBuffer upload to GPU via glUniformMatrix4fv per mesh.

    Patch A - Round-robin device updates (BaseVehicle.drainBatteryUpdateHack):
      Parked vehicles (engine off, no driver) call drainBatteryUpdateHack() instead
      of updateParts(). Patch A spreads updateSignalDevice() calls across
      DEVICE_UPDATE_SPREAD frames for parked vehicles on the client, using
      (vehicleId & 0xFFFF) % spread as the slot. Default spread=4.
      NOTE: updateParts() still updates devices every frame for active vehicles
      (alarm/mechanic UI open/needsUpdate), which is correct vanilla behavior.

    Patch B - Dirty-flag animation freeze (BaseVehicle.updateAnimationPlayer):
      When a vehicle is parked (no driver + engine idle), the animation update
      frequency is reduced to once every ANIM_FREEZE_SPREAD frames after
      ANIM_FREEZE_DELAY warmup frames. Full-rate animation resumes immediately when:
        - A driver enters the vehicle
        - Engine is no longer idle
        - Any animation track becomes active (door/window opening)
      FIX vs prior version: replaced isAtRest() condition (which requires
      ourSquare.hasFloor() — always false for outdoor/grass/dirt tiles) with a
      direct check: getDriver()==null && engineState==Idle. This ensures the
      freeze actually fires for outdoor parked vehicles.
      Constants: ANIM_FREEZE_DELAY=20, ANIM_FREEZE_SPREAD=8

    This script:
    1. Locates or downloads a JDK 25+ compiler (javac)
    2. Backs up the original BaseVehicle.class files from projectzomboid.jar
    3. Compiles the patched source against projectzomboid.jar
    4. Deploys all resulting .class files (main + 20 inner classes) to the PZ
       game directory (classpath override: loose .class files take precedence
       over JAR entries).

.PARAMETER PZDir
    Path to the Project Zomboid installation directory.
    Default: Z:\SteamLibrary\steamapps\common\ProjectZomboid

.PARAMETER DryRun
    If set, shows what would be done without actually deploying.

.PARAMETER Revert
    If set, removes all deployed .class overrides (restoring original JAR behavior).

.NOTES
    PZ uses Azul Zulu JDK 25.0.1. The bundled JRE has no javac.
    The script will auto-download Azul Zulu JDK 25 if no suitable compiler is found.

    Tuning knobs (add to JVM args in ProjectZomboid64.json or server start script):
      -Dapocbr.vehicle.deviceUpdateSpread=4   (Patch A: 1=off, 2/4/8 = spread)
      With spread=4: each vehicle's signal devices update once every 4 frames.
      With spread=1: disabled (same as vanilla behavior).
#>
param(
    [string]$PZDir     = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ToolsDir  = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

# --- Configuration ---
$PatchName      = "Vehicle Performance Optimizations - BaseVehicle"
$GameJar        = Join-Path $PZDir "projectzomboid.jar"
$DeployDir      = Join-Path $PZDir "zombie\vehicles"
$DeployClass    = Join-Path $DeployDir "BaseVehicle.class"
$BackupDir      = Join-Path $ToolsDir "backups\BaseVehicle"
$LocalJdkDir    = Join-Path $ToolsDir "jdk"
$WorkDir        = Join-Path $env:TEMP "pzpatch_basevehicle_opt"
$OutputDir      = Join-Path $WorkDir "classes"
$SourceFile     = Join-Path $ToolsDir "src\zombie\vehicles\BaseVehicle.java"
$RequiredMajor  = 25

# All inner classes produced by compiling BaseVehicle.java
$InnerClassNames = @(
    'BaseVehicle$1',
    'BaseVehicle$Authorization',
    'BaseVehicle$engineStateTypes',
    'BaseVehicle$HitVars',
    'BaseVehicle$L_testCollisionWithVehicle',
    'BaseVehicle$Matrix4fObjectPool',
    'BaseVehicle$MinMaxPosition',
    'BaseVehicle$ModelInfo',
    'BaseVehicle$Passenger',
    'BaseVehicle$QuaternionfObjectPool',
    'BaseVehicle$ServerVehicleState',
    'BaseVehicle$TransformPool',
    'BaseVehicle$UpdateFlags',
    'BaseVehicle$Vector2fObjectPool',
    'BaseVehicle$Vector3fObjectPool',
    'BaseVehicle$Vector3ObjectPool',
    'BaseVehicle$Vector4fObjectPool',
    'BaseVehicle$VehicleImpulse',
    'BaseVehicle$WeightedVehiclePart',
    'BaseVehicle$WheelInfo'
)

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
        } else {
            Write-Host "    Found javac $ver in PATH (need >= $RequiredMajor, skipping)" -ForegroundColor Yellow
        }
    }
    $searchPaths = @(
        "C:\Program Files\Zulu\zulu-$RequiredMajor*\bin\javac.exe",
        "C:\Program Files\Eclipse Adoptium\jdk-$RequiredMajor*\bin\javac.exe",
        "C:\Program Files\Java\jdk-$RequiredMajor*\bin\javac.exe",
        "C:\Program Files\Microsoft\jdk-$RequiredMajor*\bin\javac.exe"
    )
    foreach ($pattern in $searchPaths) {
        $found = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) {
            $ver = Get-JavacVersion $found.FullName
            if ($ver -ge $RequiredMajor) {
                Write-Host "    Found: javac $ver at $($found.FullName)" -ForegroundColor Green
                return $found.FullName
            }
        }
    }
    return $null
}

function Install-Jdk {
    Write-Host "[*] Downloading Azul Zulu JDK $RequiredMajor..." -ForegroundColor Cyan
    try {
        $response = Invoke-RestMethod -Uri $ZuluApiUrl -TimeoutSec 30
        $pkg = $response | Select-Object -First 1
        $downloadUrl = $pkg.download_url
    } catch {
        Write-Host "ERROR: Failed to query Azul API: $_" -ForegroundColor Red
        exit 1
    }
    if (-not $downloadUrl) {
        Write-Host "ERROR: No JDK $RequiredMajor package found." -ForegroundColor Red
        exit 1
    }
    Write-Host "    URL: $downloadUrl" -ForegroundColor Gray
    $zipPath = Join-Path $ToolsDir "jdk-download.zip"
    Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath -UseBasicParsing
    $extractDir = Join-Path $ToolsDir "jdk-extract"
    if (Test-Path $extractDir) { Remove-Item $extractDir -Recurse -Force }
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force
    $innerDir = Get-ChildItem -Path $extractDir -Directory | Select-Object -First 1
    if (-not $innerDir) { Write-Host "ERROR: Extracted archive is empty." -ForegroundColor Red; exit 1 }
    if (Test-Path $LocalJdkDir) { Remove-Item $LocalJdkDir -Recurse -Force }
    Move-Item $innerDir.FullName $LocalJdkDir
    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item $extractDir -Recurse -Force -ErrorAction SilentlyContinue
    $javacPath = Join-Path $LocalJdkDir "bin\javac.exe"
    if (Test-Path $javacPath) {
        Write-Host "    Installed: javac $(Get-JavacVersion $javacPath)" -ForegroundColor Green
        return $javacPath
    }
    Write-Host "ERROR: javac not found in downloaded JDK." -ForegroundColor Red
    exit 1
}

function Backup-OriginalClasses {
    $marker = Join-Path $BackupDir "BaseVehicle.class.original"
    if ((Test-Path $BackupDir) -and (Get-ChildItem $BackupDir -Filter "*.original" -ErrorAction SilentlyContinue).Count -gt 0) {
        Write-Host "[*] Backup already exists: $BackupDir" -ForegroundColor Gray
        return
    }
    Write-Host "[*] Extracting original BaseVehicle classes from JAR..." -ForegroundColor Cyan
    if (-not (Test-Path $BackupDir)) { New-Item -Path $BackupDir -ItemType Directory -Force | Out-Null }
    $tempDir = Join-Path $ToolsDir "tmp-extract-bv"
    if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
    New-Item -Path $tempDir -ItemType Directory -Force | Out-Null
    Push-Location $tempDir
    try {
        $jarExe = Get-Command jar -ErrorAction SilentlyContinue
        if (-not $jarExe) { Write-Host "WARNING: 'jar' not on PATH - skipping backup" -ForegroundColor Yellow; return }
        # Extract main class and all inner classes
        $classArgs = @("zombie/vehicles/BaseVehicle.class") + ($InnerClassNames | ForEach-Object { "zombie/vehicles/$_.class" })
        & $jarExe.Source xf $GameJar @classArgs 2>$null
        $extracted = Join-Path $tempDir "zombie\vehicles"
        if (Test-Path $extracted) {
            Get-ChildItem $extracted -Filter "BaseVehicle*.class" | ForEach-Object {
                Copy-Item $_.FullName (Join-Path $BackupDir "$($_.Name).original")
            }
            Write-Host "    Backed up $((Get-ChildItem $BackupDir).Count) class files" -ForegroundColor Green
        } else {
            Write-Host "    WARNING: Could not extract original classes." -ForegroundColor Yellow
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
    $reverted = $false
    if (Test-Path $DeployClass) { Remove-Item $DeployClass -Force; Write-Host "    Removed: BaseVehicle.class" -ForegroundColor Green; $reverted = $true }
    foreach ($inner in $InnerClassNames) {
        $path = Join-Path $DeployDir "$inner.class"
        if (Test-Path $path) { Remove-Item $path -Force; Write-Host "    Removed: $inner.class" -ForegroundColor Green; $reverted = $true }
    }
    if ($reverted) { Write-Host "`n=== Patch reverted - original JAR behavior restored ===" -ForegroundColor White }
    else { Write-Host "    No patch files found to remove." -ForegroundColor Yellow }
    exit 0
}

if (-not (Test-Path $GameJar)) {
    Write-Host "ERROR: Game JAR not found: $GameJar" -ForegroundColor Red
    Write-Host "       Set -PZDir to your ProjectZomboid installation" -ForegroundColor Yellow
    exit 1
}
if (-not (Test-Path $SourceFile)) {
    Write-Host "ERROR: Patched source not found: $SourceFile" -ForegroundColor Red
    Write-Host "       Expected: $($ToolsDir)\src\zombie\vehicles\BaseVehicle.java" -ForegroundColor Yellow
    exit 1
}

Backup-OriginalClasses

$javac = Find-Javac
if (-not $javac) { $javac = Install-Jdk }

# Compile
Write-Host ""
Write-Host "[*] Compiling patched BaseVehicle.java..." -ForegroundColor Cyan
if (Test-Path $OutputDir) { Remove-Item $OutputDir -Recurse -Force }
New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null

$TempSrcDir = Join-Path $WorkDir "src\zombie\vehicles"
if (-not (Test-Path $TempSrcDir)) { New-Item -Path $TempSrcDir -ItemType Directory -Force | Out-Null }
Copy-Item $SourceFile (Join-Path $TempSrcDir "BaseVehicle.java")

$compileArgs = @(
    "--release", "25",
    "-cp", $GameJar,
    "-d", $OutputDir,
    (Join-Path $TempSrcDir "BaseVehicle.java")
)

if ($DryRun) {
    Write-Host "    [DryRun] Would run: $javac $($compileArgs -join ' ')" -ForegroundColor Yellow
} else {
    # Use Start-Process to bypass PowerShell's error stream entirely.
    # With $ErrorActionPreference = "Stop", any native-command stderr triggers
    # a terminating error even with 2>file — Start-Process avoids this by
    # redirecting at the OS process level, never touching PS stream 2.
    $javacStdout = Join-Path $env:TEMP "pzpatch_basevehicle_javac_stdout.txt"
    $javacStderr = Join-Path $env:TEMP "pzpatch_basevehicle_javac_stderr.txt"
    $proc = Start-Process -FilePath $javac -ArgumentList $compileArgs `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $javacStdout `
        -RedirectStandardError  $javacStderr
    $compileExitCode = $proc.ExitCode
    $stdoutText = if (Test-Path $javacStdout) { Get-Content $javacStdout -Raw } else { "" }
    $stderrText = if (Test-Path $javacStderr) { Get-Content $javacStderr -Raw } else { "" }
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
Write-Host "[*] Deploying class files to: $DeployDir" -ForegroundColor Cyan
if (-not (Test-Path $DeployDir)) { New-Item -Path $DeployDir -ItemType Directory -Force | Out-Null }

if ($DryRun) {
    Write-Host "    [DryRun] Would deploy: BaseVehicle.class + $($InnerClassNames.Count) inner classes" -ForegroundColor Yellow
} else {
    $CompiledDir = Join-Path $OutputDir "zombie\vehicles"
    $classFiles = Get-ChildItem $CompiledDir -Filter "BaseVehicle*.class" -ErrorAction SilentlyContinue
    if (-not $classFiles) {
        Write-Host "ERROR: No compiled class files found in $CompiledDir" -ForegroundColor Red
        exit 1
    }
    foreach ($cf in $classFiles) {
        $dest = Join-Path $DeployDir $cf.Name
        Copy-Item $cf.FullName $dest -Force
        Write-Host "    Deployed: $($cf.Name)" -ForegroundColor Green
    }
}

Write-Host ""
if ($DryRun) {
    Write-Host "=== Dry run complete - no files were changed ===" -ForegroundColor Yellow
} else {
    Write-Host "=== Patch applied successfully ===" -ForegroundColor White
    Write-Host ""
    Write-Host "Deployed $($classFiles.Count) class file(s)." -ForegroundColor Gray
    Write-Host ""
    Write-Host "Tuning (add to JVM args in ProjectZomboid64.json or server start script):" -ForegroundColor Gray
    Write-Host "  -Dapocbr.vehicle.deviceUpdateSpread=4   (default 4; 1=disable Patch A)" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Restart the game/server for changes to take effect." -ForegroundColor Gray
}
Write-Host ""
