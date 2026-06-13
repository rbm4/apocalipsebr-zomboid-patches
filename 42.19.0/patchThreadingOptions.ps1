<#
.SYNOPSIS
    Compiles patched DebugOptions.java and deploys .class files to Project Zomboid.

.DESCRIPTION
    Threading Optimization Patch - DebugOptions (Build 42.19)

    Context: Project Zomboid ships with three threading options that are built,
    tested, and wired up in the engine but default to false:

      Threading.Animation  (DebugOptions.threadAnimation)
        Offloads ALL AnimationPlayer.Update() calls for every moving object
        (vehicles, zombies, characters) to the PZForkJoinPool. In IsoWorld,
        MovingObjectUpdateScheduler.postupdate() runs concurrently via
        CompletableFuture.runAsync() and is joined at FinishAnimation().
        With many active zombies or parked vehicles this is the dominant CPU cost.

      Threading.World  (DebugOptions.threadWorld)
        Runs updateBuildings(), ObjectRenderEffects.updateStatic(), DB updates,
        addCoopPlayers processing, and virtual animals concurrently with the
        game thread's main update path (climate, pathfinding etc).

      Threading.Ambient  (DebugOptions.threadAmbient)
        Offloads ObjectAmbientEmitters.update() (FMOD ambient sound emitter
        polling) to the ForkJoinPool concurrently with game logic.
        Less impactful on dedicated servers (no audio device).

    All three use newOption() (not newDebugOnlyOption()), meaning they are
    production-safe and just happen to default to false.

    PZForkJoinPool uses Runtime.getRuntime().availableProcessors() - 1 threads,
    so on a 16-core machine this is 15 worker threads already standing by.

    Verified against Build 42.19 decompiled source:
      - threadAnimation and threadWorld are NOT gated by !GameServer.server
        in IsoWorld, so both run on the dedicated server as well as on clients.
      - All three options exist as of 42.19.0 (lines 185, 192, 194 of DebugOptions).

    The patched DebugOptions.java:
      - Changes defaults from false -> true for all three options
      - Overrides load() to force them back to true after reading debug-options.ini,
        so a pre-existing cached ini cannot re-disable them

.PARAMETER PZDir
    Path to the Project Zomboid installation directory.
    Default: Z:\SteamLibrary\steamapps\common\ProjectZomboid

.PARAMETER DryRun
    If set, shows what would be done without actually deploying.

.PARAMETER Revert
    If set, removes all deployed .class overrides (restoring original JAR behavior).

.NOTES
    PZ uses Azul Zulu JDK 25.0.1. The bundled JRE has no javac.
    The script will search for a suitable JDK 25+ compiler automatically.
#>
param(
    [string]$PZDir    = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ToolsDir = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

# --- Configuration ---
$PatchName     = "Threading Optimization - DebugOptions"
$GameJar       = Join-Path $PZDir "projectzomboid.jar"
$DeployDir     = Join-Path $PZDir "zombie\debug"
$DeployClass   = Join-Path $DeployDir "DebugOptions.class"
$BackupDir     = Join-Path $ToolsDir "backups\DebugOptions"
$SourceFile    = Join-Path $ToolsDir "src\zombie\debug\DebugOptions.java"
$WorkDir       = Join-Path $env:TEMP "pzpatch_debugoptions_threading"
$OutputDir     = Join-Path $WorkDir "classes"
$RequiredMajor = 25
$InnerClasses  = @('DebugOptions$Checks')

Write-Host ""
Write-Host "=== PZ Classpath Override Patch ===" -ForegroundColor Cyan
Write-Host "=== $PatchName ===" -ForegroundColor Cyan
Write-Host ""

# --- Revert ---
if ($Revert) {
    Write-Host "[*] Reverting patch..." -ForegroundColor Yellow
    $reverted = $false
    if (Test-Path $DeployClass) {
        if (-not $DryRun) { Remove-Item $DeployClass -Force }
        Write-Host "    Removed: DebugOptions.class"
        $reverted = $true
    }
    foreach ($inner in $InnerClasses) {
        $path = Join-Path $DeployDir "$inner.class"
        if (Test-Path $path) {
            if (-not $DryRun) { Remove-Item $path -Force }
            Write-Host "    Removed: $inner.class"
            $reverted = $true
        }
    }
    if ($reverted) {
        Write-Host ""
        Write-Host "=== Patch reverted. Original classes from JAR will be used on next restart. ===" -ForegroundColor Green
    } else {
        Write-Host "    Nothing to revert."
    }
    exit 0
}

# --- Validation ---
if (-not (Test-Path $GameJar)) {
    throw "Game JAR not found: $GameJar"
}
if (-not (Test-Path $SourceFile)) {
    throw "Patched source not found: $SourceFile"
}

# --- Backup ---
if (-not (Test-Path $BackupDir) -or @(Get-ChildItem $BackupDir).Count -eq 0) {
    Write-Host "[*] Backing up original DebugOptions classes..."
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    $tmpDir = Join-Path $env:TEMP "pzpatch_backup_debug_$(Get-Random)"
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
    Push-Location $tmpDir
    try {
        & jar xf "$GameJar" zombie/debug/DebugOptions.class 2>$null
        foreach ($inner in $InnerClasses) {
            & jar xf "$GameJar" "zombie/debug/$inner.class" 2>$null
        }
        $extracted = Get-ChildItem "$tmpDir\zombie\debug" -Filter "DebugOptions*.class" -ErrorAction SilentlyContinue
        if ($extracted) {
            foreach ($f in $extracted) {
                Copy-Item $f.FullName (Join-Path $BackupDir "$($f.BaseName).class.original")
            }
            Write-Host "    Backed up $($extracted.Count) file(s)"
        } else {
            Write-Host "    WARNING: Could not extract original classes from JAR."
        }
    } finally {
        Pop-Location
        Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
    }
} else {
    Write-Host "[*] Backup already exists: $BackupDir"
}

# --- Find javac ---
function Find-Javac {
    $candidates = @()
    $localJdk = Join-Path $ToolsDir "jdk\bin\javac.exe"
    if (Test-Path $localJdk) { $candidates += $localJdk }
    $gcJavac = Get-Command javac -ErrorAction SilentlyContinue
    if ($gcJavac) { $candidates += $gcJavac.Source }
    foreach ($jvmPath in (Get-ChildItem "C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft" -ErrorAction SilentlyContinue | Where-Object { $_.PSIsContainer })) {
        $jc = Join-Path $jvmPath.FullName "bin\javac.exe"
        if (Test-Path $jc) { $candidates += $jc }
    }
    foreach ($c in $candidates) {
        try {
            $ver = (& "$c" -version 2>&1) -replace 'javac ', '' -replace '\..*', ''
            if ([int]$ver -ge $RequiredMajor) { return $c }
        } catch {}
    }
    return $null
}

Write-Host "[*] Searching for javac >= $RequiredMajor..."
$javac = Find-Javac
if (-not $javac) {
    throw "No javac >= $RequiredMajor found. Install JDK $RequiredMajor and ensure javac.exe is on PATH."
}
Write-Host "    Found: $javac"

# --- Compile ---
Write-Host ""
Write-Host "[*] Compiling patched DebugOptions.java..."
if (Test-Path $WorkDir) { Remove-Item $WorkDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$tempSrc = Join-Path $WorkDir "src\zombie\debug"
New-Item -ItemType Directory -Force -Path $tempSrc | Out-Null
Copy-Item $SourceFile (Join-Path $tempSrc "DebugOptions.java")

if ($DryRun) {
    Write-Host "    [DryRun] Would run: $javac --release 25 -cp `"$GameJar`" -d `"$OutputDir`" ..."
} else {
    & "$javac" --release 25 -cp "$GameJar" -d "$OutputDir" (Join-Path $tempSrc "DebugOptions.java")
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed (exit $LASTEXITCODE)" }
    Write-Host "    Compiled successfully."
}

# --- Deploy ---
Write-Host ""
Write-Host "[*] Deploying class files to: $DeployDir"
if (-not $DryRun) { New-Item -ItemType Directory -Force -Path $DeployDir | Out-Null }

$compiledDir = Join-Path $OutputDir "zombie\debug"

if ($DryRun) {
    Write-Host "    [DryRun] Would deploy DebugOptions.class + $($InnerClasses.Count) inner class(es)"
} else {
    $count = 0
    Get-ChildItem $compiledDir -Filter "DebugOptions*.class" | ForEach-Object {
        Copy-Item $_.FullName (Join-Path $DeployDir $_.Name)
        Write-Host "    Deployed: $($_.Name)"
        $count++
    }

    Write-Host ""
    Write-Host "=== Patch applied successfully ===" -ForegroundColor Green
    Write-Host ""
    Write-Host "Deployed $count class file(s) to $DeployDir"
    Write-Host ""
    Write-Host "Enabled threading options (Build 42.19):"
    Write-Host "  Threading.Animation = true  (AnimationPlayer.Update on ForkJoinPool)"
    Write-Host "  Threading.World     = true  (buildings/static/DB/animals concurrent)"
    Write-Host "  Threading.Ambient   = true  (FMOD ambient emitters concurrent)"
    Write-Host ""
    Write-Host "Restart the game/server for changes to take effect."
}
Write-Host ""
