<#
.SYNOPSIS
    Compiles and deploys the Server Cell Tick Parallelization patch (ServerMap).

.DESCRIPTION
    Server Cell Tick Parallelization (PATCH-F)

    Problem:
      The dedicated server runs its main game loop entirely on one thread. Each
      tick, ServerMap.postupdate() iterates every loaded cell sequentially and
      calls ServerCell.update(), which in turn calls IsoChunk.update() for each
      of the 64 chunks in the cell. With many loaded cells (large player count
      or spread-out players), this loop becomes a CPU bottleneck while other
      cores sit idle.

    Fix:
      Split postupdate() into two phases:

      Phase 1 (serial, main thread):
        - Handle cancel-loading and cell unloads (unchanged, requires
          ServerLOS.instance.suspend/resume and loadedCells list mutation).
        - Collect loaded+relevant cells into a local list.

      Phase 2 (parallel, PZForkJoinPool):
        - Submit each cell's update() as a CompletableFuture to the existing
          PZForkJoinPool (availableProcessors - 1 threads).
        - Join all futures before returning, so the main-thread sequencing of
          NetworkZombiePacker.postupdate() and chunkLoader.updateSaved() is
          preserved.
        - When only one cell needs updating the parallel path is skipped.

    Thread-safety analysis:
      - ServerCell.update() iterates its own 8x8 chunk array exclusively; no
        two cells share chunk references.
      - IsoChunk.doAttachments is a read-only static during ticking.
      - IsoChunk.ragdollControllersForAddToWorld is always null on a dedicated
        server (no code populates it server-side), so Bullet addToWorld() never
        executes.
      - updateVehicleStory() reads getMetaChunk(wx, wy) keyed on the chunk's
        own coordinates; different cells access different meta chunks. The
        zone.hourLastSeen++ write is benign even with a race.
      - ServerLOS remains suspended for the full duration of postupdate(),
        preventing any LOS-thread interference with chunk state.

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

$PatchName  = "Server Cell Tick Parallelization (PATCH-F)"

# Auto-detect JAR location (Linux server: java/ subdir; Windows: PZ root)
$JavaSubJar = Join-Path $PZDir "java\projectzomboid.jar"
if (Test-Path $JavaSubJar) {
    $GameJar    = $JavaSubJar
    $DeployBase = Join-Path $PZDir "java"
} else {
    $GameJar    = Join-Path $PZDir "projectzomboid.jar"
    $DeployBase = $PZDir
}

$DeployDir  = Join-Path $DeployBase "zombie\network"
$BackupDir  = Join-Path $ToolsDir "backups\ServerCellTick"
$WorkDir    = Join-Path $env:TEMP "pzpatch_servercellick"
$OutputDir  = Join-Path $WorkDir "classes"

$SrcServerMap = Join-Path $ToolsDir "src\zombie\network\ServerMap.java"

$RequiredJavaMajor = 25

Write-Host ""
Write-Host "=== PZ Classpath Override Patch ===" -ForegroundColor Cyan
Write-Host "=== $PatchName ===" -ForegroundColor Cyan
Write-Host ""

# --- Revert ---
if ($Revert) {
    Write-Host "[*] Reverting patch..." -ForegroundColor Yellow
    $reverted = $false
    $target = Join-Path $DeployDir "ServerMap.class"
    if (Test-Path $target) {
        Remove-Item $target -Force
        Write-Host "    Removed ServerMap.class"
        $reverted = $true
    }
    # Remove inner classes (ServerMap$ServerCell.class etc.)
    Get-ChildItem $DeployDir -Filter "ServerMap`$*.class" -ErrorAction SilentlyContinue | ForEach-Object {
        Remove-Item $_.FullName -Force
        Write-Host "    Removed $($_.Name)"
        $reverted = $true
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

    $searchRoots = @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Zulu",
        "C:\Program Files\Java"
    )
    foreach ($root in $searchRoots) {
        if (Test-Path $root) {
            Get-ChildItem $root -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match "jdk-?2[5-9]|jdk-?3[0-9]" } |
                ForEach-Object { $candidates += Join-Path $_.FullName "bin\javac.exe" }
        }
    }

    foreach ($c in $candidates) {
        if (Test-Path $c) {
            $verStr = & $c -version 2>&1
            $ver = ($verStr | Select-String '(\d+)').Matches[0].Value
            if ([int]$ver -ge $RequiredJavaMajor) { return $c }
        }
    }

    $gcJavac = Get-Command javac -ErrorAction SilentlyContinue
    if ($gcJavac) {
        $verStr = & javac -version 2>&1
        $ver = ($verStr | Select-String '(\d+)').Matches[0].Value
        if ([int]$ver -ge $RequiredJavaMajor) { return $gcJavac.Source }
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
if (-not (Test-Path $SrcServerMap)) {
    Write-Host "ERROR: Source not found: $SrcServerMap" -ForegroundColor Red
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
    $entryPath  = "zombie/network/ServerMap.class"
    $backupTarget = Join-Path $BackupDir "ServerMap.class.bak"
    if (-not (Test-Path $backupTarget)) {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq $entryPath } | Select-Object -First 1
        if ($entry) {
            $stream = $entry.Open()
            $outStream = [System.IO.File]::Create($backupTarget)
            $stream.CopyTo($outStream)
            $outStream.Close()
            $stream.Close()
            Write-Host "    Backed up: ServerMap.class"
        } else {
            Write-Host "    Warning: ServerMap.class not found in JAR (may be fine if already patched)"
        }
    } else {
        Write-Host "    Already backed up: ServerMap.class"
    }
} finally {
    $zip.Dispose()
}

# --- Compile ---
Write-Host ""
Write-Host "[2/4] Compiling patched sources..."

if ($DryRun) {
    Write-Host "    [DRY RUN] Would compile:"
    Write-Host "      $SrcServerMap"
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
    $SrcServerMap

if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Compilation failed." -ForegroundColor Red
    exit 1
}
Write-Host "    Compilation successful."

# --- Deploy ---
Write-Host ""
Write-Host "[3/4] Deploying .class files..."
New-Item -ItemType Directory -Force -Path $DeployDir | Out-Null

$deployed = 0

# Deploy ServerMap.class
$mainClass = Join-Path $OutputDir "zombie\network\ServerMap.class"
if (Test-Path $mainClass) {
    Copy-Item $mainClass (Join-Path $DeployDir "ServerMap.class") -Force
    Write-Host "    Deployed: zombie\network\ServerMap.class"
    $deployed++
} else {
    Write-Host "ERROR: Compiled class not found: $mainClass" -ForegroundColor Red
    exit 1
}

# Deploy inner classes (ServerMap$ServerCell.class, ServerMap$WorkerThread.class, etc.)
$innerSrc = Join-Path $OutputDir "zombie\network"
Get-ChildItem $innerSrc -Filter "ServerMap`$*.class" -ErrorAction SilentlyContinue | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $DeployDir $_.Name) -Force
    Write-Host "    Deployed: zombie\network\$($_.Name)"
    $deployed++
}

# --- Summary ---
Write-Host ""
Write-Host "[4/4] Verifying deployment..."
$ok = $true

$mainDeployed = Join-Path $DeployDir "ServerMap.class"
if (Test-Path $mainDeployed) {
    Write-Host "    OK: $mainDeployed" -ForegroundColor Green
} else {
    Write-Host "    MISSING: $mainDeployed" -ForegroundColor Red
    $ok = $false
}

Get-ChildItem $DeployDir -Filter "ServerMap`$*.class" -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host "    OK: $($_.FullName)" -ForegroundColor Green
}

if ($ok) {
    $cores = [System.Environment]::ProcessorCount
    Write-Host ""
    Write-Host "=== $PatchName applied successfully ===" -ForegroundColor Green
    Write-Host ""
    Write-Host "ServerMap.postupdate() now dispatches cell.update() calls in parallel"
    Write-Host "across PZForkJoinPool ($cores CPUs → $($cores - 1) worker threads)."
    Write-Host "To revert:  .\patchServerCellTick.ps1 -PZDir '$PZDir' -Revert"
} else {
    Write-Host ""
    Write-Host "=== Deployment verification FAILED ===" -ForegroundColor Red
    exit 1
}
Write-Host ""
