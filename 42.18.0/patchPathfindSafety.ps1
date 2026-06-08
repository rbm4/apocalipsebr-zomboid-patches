<#
.SYNOPSIS
    Compiles and deploys the Pathfind Safety patch (PathfindNative + ChunkUpdateTask).

.DESCRIPTION
    Pathfind Safety Patch - Stale-Chunk Guard

    Problem:
      After several hours of uptime the dedicated server crashes with a SIGSEGV
      in libPZPathFind64.so at Square::init(int, int, int)+0xa. The 'this' pointer
      (RDI) = 0x30, which is not a valid heap address. This happens on the
      PathfindNativeThread when PathfindNative.updateChunk() is called for a chunk
      whose native state has already been freed or is in an inconsistent condition.

      Root cause: a ChunkUpdateTask can remain in the chunkTaskQueue after its
      chunk has been removed from native pathfind state. When it eventually
      executes, the native Square[] array for that slot is gone, and Square::init()
      receives a garbage pointer, crashing the entire JVM.

    Fix (two patched classes, zero behaviour change for healthy paths):
      PathfindNative:
        Adds a ConcurrentHashMap<Long, Short> activeChunkLoadIds.
        addChunkToWorld()      registers  (wx, wy) -> loadId before queuing task.
        removeChunkFromWorld() unregisters (wx, wy) before queuing remove task.
        stop()                 clears the map on shutdown.

      ChunkUpdateTask:
        execute() checks activeChunkLoadIds before calling updateChunk(). If the
        entry is absent (chunk removed) or has a different loadId (chunk reloaded),
        the call is skipped, preventing the SIGSEGV.

    Limitations:
      A SIGSEGV in JNI native code terminates the entire JVM; it cannot be caught
      with try/catch. This patch eliminates the most common trigger (stale queued
      tasks) but cannot rule out other internal bugs in libPZPathFind64.so.

.PARAMETER PZDir
    Path to the Project Zomboid installation or server directory.
    Default: Z:\SteamLibrary\steamapps\common\ProjectZomboid

.PARAMETER DryRun
    Show what would be done without actually deploying.

.PARAMETER Revert
    Remove the deployed .class overrides and restore original JAR behaviour.
#>
param(
    [string]$PZDir    = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ToolsDir = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

$PatchName  = "Pathfind Safety - Stale-Chunk Guard"
$GameJar    = Join-Path $PZDir "projectzomboid.jar"
$DeployDir  = Join-Path $PZDir "zombie\pathfind\nativeCode"
$BackupDir  = Join-Path $ToolsDir "backups\PathfindSafety"
$WorkDir    = Join-Path $env:TEMP "pzpatch_pathfindsafety"
$OutputDir  = Join-Path $WorkDir "classes"

$SrcPathfindNative    = Join-Path $ToolsDir "src\zombie\pathfind\nativeCode\PathfindNative.java"
$SrcChunkUpdateTask   = Join-Path $ToolsDir "src\zombie\pathfind\nativeCode\ChunkUpdateTask.java"

$RequiredJavaMajor = 25
$Classes = @("PathfindNative", "ChunkUpdateTask")

Write-Host ""
Write-Host "=== PZ Classpath Override Patch ===" -ForegroundColor Cyan
Write-Host "=== $PatchName ===" -ForegroundColor Cyan
Write-Host ""

# --- Revert ---
if ($Revert) {
    Write-Host "[*] Reverting patch..." -ForegroundColor Yellow
    $reverted = $false
    foreach ($cls in $Classes) {
        $target = Join-Path $DeployDir "$cls.class"
        if (Test-Path $target) {
            Remove-Item $target -Force
            Write-Host "    Removed $cls.class"
            $reverted = $true
        }
    }
    if ($reverted) {
        Write-Host ""
        Write-Host "=== Patch reverted. Original classes from JAR will be used on next server start. ===" -ForegroundColor Green
    } else {
        Write-Host "    No patch files found to remove."
    }
    exit 0
}

# --- Locate javac ---
function Find-Javac {
    $candidates = @()
    $localJdk = Join-Path $ToolsDir "jdk\bin\javac.exe"
    if (Test-Path $localJdk) { $candidates += $localJdk }

    $javaHome = $env:JAVA_HOME
    if ($javaHome) { $candidates += Join-Path $javaHome "bin\javac.exe" }

    Get-ChildItem "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft", "C:\Program Files\Zulu", "C:\Program Files\Java" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match "jdk-?2[5-9]|jdk-?3[0-9]" } |
        ForEach-Object { $candidates += Join-Path $_.FullName "bin\javac.exe" }

    foreach ($c in $candidates) {
        if (Test-Path $c) {
            $ver = & $c -version 2>&1 | Select-String '(\d+)' | ForEach-Object { $_.Matches[0].Value }
            if ([int]$ver -ge $RequiredJavaMajor) { return $c }
        }
    }

    $pathJavac = Get-Command javac -ErrorAction SilentlyContinue
    if ($pathJavac) {
        $ver = & javac -version 2>&1 | Select-String '(\d+)' | ForEach-Object { $_.Matches[0].Value }
        if ([int]$ver -ge $RequiredJavaMajor) { return $pathJavac.Source }
    }
    return $null
}

$Javac = Find-Javac
if (-not $Javac) {
    Write-Host "ERROR: javac >= $RequiredJavaMajor not found." -ForegroundColor Red
    Write-Host "       The main patch.ps1 script can download Azul Zulu JDK 25 automatically."
    Write-Host "       Or install JDK $RequiredJavaMajor manually and ensure it is on PATH."
    exit 1
}
Write-Host "[*] javac : $Javac"

# --- Validate inputs ---
if (-not (Test-Path $GameJar)) {
    Write-Host "ERROR: Game JAR not found: $GameJar" -ForegroundColor Red
    Write-Host "       Pass -PZDir to your Project Zomboid installation."
    exit 1
}
if (-not (Test-Path $SrcPathfindNative)) {
    Write-Host "ERROR: Source not found: $SrcPathfindNative" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $SrcChunkUpdateTask)) {
    Write-Host "ERROR: Source not found: $SrcChunkUpdateTask" -ForegroundColor Red
    exit 1
}

Write-Host "[*] PZ dir: $PZDir"
Write-Host ""

# --- Backup originals from JAR ---
Write-Host "[1/4] Backing up originals from JAR..."
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($GameJar)
try {
    foreach ($cls in $Classes) {
        $entryPath = "zombie/pathfind/nativeCode/$cls.class"
        $backupTarget = Join-Path $BackupDir "$cls.class.bak"
        if (-not (Test-Path $backupTarget)) {
            $entry = $zip.Entries | Where-Object { $_.FullName -eq $entryPath } | Select-Object -First 1
            if ($entry) {
                $stream = $entry.Open()
                $outStream = [System.IO.File]::Create($backupTarget)
                $stream.CopyTo($outStream)
                $outStream.Close()
                $stream.Close()
                Write-Host "    Backed up: $cls.class"
            } else {
                Write-Host "    Warning: $cls.class not found in JAR (may be fine)"
            }
        } else {
            Write-Host "    Already backed up: $cls.class"
        }
    }
} finally {
    $zip.Dispose()
}

# --- Compile ---
Write-Host ""
Write-Host "[2/4] Compiling patched sources..."

if ($DryRun) {
    Write-Host "    [DRY RUN] Would compile:"
    Write-Host "      $SrcPathfindNative"
    Write-Host "      $SrcChunkUpdateTask"
    Write-Host "    [DRY RUN] classpath: $GameJar"
    Write-Host ""
    Write-Host "=== Dry run complete. No files changed. ===" -ForegroundColor Green
    exit 0
}

if (Test-Path $WorkDir) { Remove-Item $WorkDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

& $Javac `
    -cp $GameJar `
    -d $OutputDir `
    --release 17 `
    $SrcPathfindNative `
    $SrcChunkUpdateTask

if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Compilation failed." -ForegroundColor Red
    exit 1
}
Write-Host "    Compilation successful."

# --- Deploy ---
Write-Host ""
Write-Host "[3/4] Deploying .class files..."
New-Item -ItemType Directory -Force -Path $DeployDir | Out-Null

foreach ($cls in $Classes) {
    $srcClass = Join-Path $OutputDir "zombie\pathfind\nativeCode\$cls.class"
    $dstClass = Join-Path $DeployDir "$cls.class"
    if (Test-Path $srcClass) {
        Copy-Item $srcClass $dstClass -Force
        Write-Host "    Deployed: zombie\pathfind\nativeCode\$cls.class"
    } else {
        Write-Host "ERROR: Compiled class not found: $srcClass" -ForegroundColor Red
        exit 1
    }
}

# --- Summary ---
Write-Host ""
Write-Host "[4/4] Verifying deployment..."
foreach ($cls in $Classes) {
    $deployed = Join-Path $DeployDir "$cls.class"
    if (Test-Path $deployed) {
        Write-Host "    OK: $deployed" -ForegroundColor Green
    } else {
        Write-Host "    MISSING: $deployed" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "=== $PatchName applied successfully ===" -ForegroundColor Green
Write-Host ""
Write-Host "The patched classes are loaded by the JVM ahead of projectzomboid.jar."
Write-Host "To revert:  .\patchPathfindSafety.ps1 -PZDir '$PZDir' -Revert"
Write-Host ""
