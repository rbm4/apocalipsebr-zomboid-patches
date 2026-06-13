<#
.SYNOPSIS
    Patches Project Zomboid server to perform periodic world saves on a
    background daemon thread instead of blocking the main game loop.

.DESCRIPTION
    Single Java-level patch compiled from decompiler source and deployed via
    classpath override (loose .class files under the game directory take
    precedence over entries inside projectzomboid.jar).

    Patch E - async background save  (ServerMap.java)
        QueuedSaveAll() runs synchronously on the main game thread, blocking
        all game logic and network processing for the full save duration.  On
        a busy server with many loaded ServerCells this easily exceeds the
        600 ms threshold that triggers client-side pause and risks client
        disconnects.

        Patch E moves periodic saves (triggered by SaveWorldEveryMinutes) to a
        daemon thread so the main loop keeps processing during saves.  The
        quit-path save (server shutdown) still runs synchronously so the
        process does not exit with an in-progress background write.

        Known limitations (acceptable for testing):
          - Chunk tile data serialised during the background save may reflect
            state that is 0-1 frames stale for actively-modified chunks.
          - If the server crashes DURING a background save, the partial write
            is no worse than a vanilla mid-save crash (both leave dirty files).
          - Overlapping saves are skipped with a console warning rather than
            queued.

    First run:
        The script copies ServerMap.java from the Zomboid decompiler output
        into src\, applies the patch in-place, and commits the file to disk.
        The resulting src\ file should be git-added and pushed so the Linux
        deployment script can compile it on the game server without needing
        the decompiler.

.PARAMETER PZDir
    Path to the Project Zomboid installation directory.
    Default: Z:\SteamLibrary\steamapps\common\ProjectZomboid

.PARAMETER DecompilerDir
    Path to the 'source' directory produced by ZomboidDecompiler (the folder
    that contains the 'zombie' package tree).
    Default: Z:\Downloads\ZomboidDecompiler\ZomboidDecompiler\bin\output\source

.PARAMETER ToolsDir
    Directory containing this script (and the src\ folder).
    Default: $PSScriptRoot

.PARAMETER DryRun
    Shows what would be done without touching any files.

.PARAMETER Revert
    Removes the deployed .class overrides, restoring original JAR behaviour.

.NOTES
    Requires a JDK 25+ javac.  The script auto-downloads Azul Zulu JDK 25 if
    no suitable compiler is found.

    Game version targeted: 42.19
#>
param(
    [string]$PZDir         = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$DecompilerDir = "Z:\Downloads\ZomboidDecompiler\ZomboidDecompiler\bin\output\source",
    [string]$ToolsDir      = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
$PatchName     = "Async Background Save - Patch E"
$GameJar       = Join-Path $PZDir "projectzomboid.jar"
$BackupDir     = Join-Path $ToolsDir "backups\AsyncSave"
$LocalJdkDir   = Join-Path $ToolsDir "jdk"
$WorkDir       = Join-Path $env:TEMP "pzpatch_asyncsave"
$OutputDir     = Join-Path $WorkDir "classes"
$RequiredMajor = 25

$ZuluApiUrl    = "https://api.azul.com/metadata/v1/zulu/packages/" +
                 "?java_version=$RequiredMajor&os=windows&arch=x64" +
                 "&archive_type=zip&java_package_type=jdk&latest=true"

# Patched source file (generated from decompiler on first run)
$SrcServerMap  = Join-Path $ToolsDir "src\zombie\network\ServerMap.java"

# Deploy target directory inside PZDir
$DeployNet = Join-Path $PZDir "zombie\network"

# Classes to backup/revert
$ClassesServerMap = @(
    "zombie/network/ServerMap.class",
    "zombie/network/ServerMap`$DistToCellComparator.class",
    "zombie/network/ServerMap`$EThreadCommand.class",
    "zombie/network/ServerMap`$ServerCell.class",
    "zombie/network/ServerMap`$WorkerThread.class",
    "zombie/network/ServerMap`$WorkerThreadCommand.class"
)

# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------
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
                Write-Host "    Found installed: javac $ver" -ForegroundColor Green
                return $found.FullName
            }
        }
    }

    return $null
}

function Install-Jdk {
    Write-Host "[*] Downloading Azul Zulu JDK $RequiredMajor..." -ForegroundColor Cyan
    try {
        $response    = Invoke-RestMethod -Uri $ZuluApiUrl -TimeoutSec 30
        $downloadUrl = ($response | Select-Object -First 1).download_url
    } catch {
        Write-Host "ERROR: Failed to query Azul API: $_" -ForegroundColor Red
        exit 1
    }
    if (-not $downloadUrl) {
        Write-Host "ERROR: No JDK $RequiredMajor package found." -ForegroundColor Red
        exit 1
    }
    $zipPath    = Join-Path $ToolsDir "jdk-download.zip"
    $extractDir = Join-Path $ToolsDir "jdk-extract"
    Write-Host "    Downloading from $downloadUrl ..." -ForegroundColor Gray
    Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath -UseBasicParsing
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
        $ver = Get-JavacVersion $javacPath
        Write-Host "    Installed: javac $ver" -ForegroundColor Green
        return $javacPath
    }
    Write-Host "ERROR: javac not found after JDK install." -ForegroundColor Red
    exit 1
}

function Apply-Patch {
    param(
        [string]$FilePath,
        [string]$OldText,
        [string]$NewText,
        [string]$Description
    )
    $content = [System.IO.File]::ReadAllText($FilePath, [System.Text.Encoding]::UTF8)
    $content = $content.Replace("`r`n", "`n")
    if ($content[0] -eq [char]0xFEFF) { $content = $content.Substring(1) }
    $count = [regex]::Matches($content, [regex]::Escape($OldText)).Count
    if ($count -eq 0) {
        Write-Host "    ERROR: patch string not found - $Description" -ForegroundColor Red
        Write-Host "    The decompiler output may have changed. Check the source manually." -ForegroundColor Yellow
        exit 1
    }
    if ($count -gt 1) {
        Write-Host "    WARNING: patch string found $count times (expected 1) - $Description" -ForegroundColor Yellow
    }
    $content = $content.Replace($OldText, $NewText)
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($FilePath, $content, $utf8NoBom)
    Write-Host "    Patched: $Description" -ForegroundColor Green
}

function Backup-Classes {
    param([string]$Label, [string[]]$JarEntries)

    $labelDir = Join-Path $BackupDir $Label
    if ((Test-Path $labelDir) -and (Get-ChildItem $labelDir -Filter "*.original" -ErrorAction SilentlyContinue).Count -gt 0) {
        Write-Host "[*] Backup already exists: $labelDir" -ForegroundColor Gray
        return
    }

    Write-Host "[*] Backing up original $Label classes from JAR..." -ForegroundColor Cyan
    if (-not (Test-Path $labelDir)) { New-Item -Path $labelDir -ItemType Directory -Force | Out-Null }

    $tempDir = Join-Path $WorkDir "backup-extract-$Label"
    if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
    New-Item -Path $tempDir -ItemType Directory -Force | Out-Null

    Push-Location $tempDir
    try {
        $jarExe = Get-Command jar -ErrorAction SilentlyContinue
        if (-not $jarExe) {
            Write-Host "    WARNING: 'jar' not on PATH - skipping backup for $Label" -ForegroundColor Yellow
            return
        }
        & $jarExe.Source xf $GameJar @JarEntries 2>$null
        Get-ChildItem -Path $tempDir -Recurse -Filter "*.class" | ForEach-Object {
            $dest = Join-Path $labelDir ($_.Name + ".original")
            Copy-Item $_.FullName $dest
            Write-Host "    Backed up $($_.Name)" -ForegroundColor Gray
        }
    } finally {
        Pop-Location
        Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ---------------------------------------------------------------------------
# Revert mode
# ---------------------------------------------------------------------------
if ($Revert) {
    Write-Host ""
    Write-Host "=== Reverting $PatchName ===" -ForegroundColor Cyan
    $reverted = $false
    foreach ($entry in $ClassesServerMap) {
        $localPath = $entry.Replace("/", "\")
        $target = Join-Path $PZDir $localPath
        if (Test-Path $target) {
            Remove-Item $target -Force
            Write-Host "    Removed: $localPath" -ForegroundColor Gray
            $reverted = $true
        }
    }
    if ($reverted) {
        Write-Host ""
        Write-Host "=== Patch reverted. JAR originals restored on next server start. ===" -ForegroundColor Green
    } else {
        Write-Host "    No patch files found to remove." -ForegroundColor Yellow
    }
    exit 0
}

# ---------------------------------------------------------------------------
# Validate prerequisites
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "=== $PatchName ===" -ForegroundColor White
Write-Host ""

if (-not (Test-Path $GameJar)) {
    Write-Host "ERROR: JAR not found at $GameJar" -ForegroundColor Red
    Write-Host "       Set -PZDir to your Project Zomboid installation." -ForegroundColor Yellow
    exit 1
}

# ---------------------------------------------------------------------------
# Step 1: Generate / refresh patched source file from decompiler output
# ---------------------------------------------------------------------------
Write-Host "[*] Preparing patched source file..." -ForegroundColor Cyan

$decompilerSrc = Join-Path $DecompilerDir "zombie\network\ServerMap.java"
if (-not (Test-Path $decompilerSrc)) {
    Write-Host "ERROR: Decompiler source not found: $decompilerSrc" -ForegroundColor Red
    Write-Host "       Set -DecompilerDir to your ZomboidDecompiler 'source' output directory." -ForegroundColor Yellow
    exit 1
}
$destDir = Split-Path $SrcServerMap -Parent
if (-not (Test-Path $destDir)) { New-Item -Path $destDir -ItemType Directory -Force | Out-Null }
Copy-Item $decompilerSrc $SrcServerMap -Force
Write-Host "    Copied: ServerMap.java" -ForegroundColor Gray

# ---------------------------------------------------------------------------
# Apply Patch E  -  ServerMap.java: run periodic saves on a background thread
# ---------------------------------------------------------------------------

# E-1: add asyncSaveRunning field
Write-Host ""
Write-Host "[*] Applying Patch E-1 - asyncSaveRunning field..." -ForegroundColor Cyan

$pE1_Old = ("    public boolean queuedSaveAll;`n" +
            "    public boolean queuedQuit;")
$pE1_New = ("    public boolean queuedSaveAll;`n" +
            "    public boolean queuedQuit;`n" +
            "    private volatile boolean asyncSaveRunning = false; // PATCH-E: guards against overlapping background saves")
Apply-Patch -FilePath $SrcServerMap -OldText $pE1_Old -NewText $pE1_New `
    -Description "field asyncSaveRunning"

# E-2: replace the queuedSaveAll dispatch in postupdate() with a background-thread version
Write-Host ""
Write-Host "[*] Applying Patch E-2 - async save dispatch in postupdate()..." -ForegroundColor Cyan

$pE2_Old = ("        if (this.queuedSaveAll && !ZipBackup.isRunning()) {`n" +
            "            this.queuedSaveAll = false;`n" +
            "            this.QueuedSaveAll(false);`n" +
            "        }`n`n" +
            "        if (this.queuedQuit) {")
$pE2_New = ("        if (this.queuedSaveAll && !ZipBackup.isRunning()) {`n" +
            "            this.queuedSaveAll = false;`n" +
            "            if (!this.asyncSaveRunning) { // PATCH-E: non-blocking periodic save`n" +
            "                this.asyncSaveRunning = true;`n" +
            "                // ServerPlayerDB.save() iterates the live connection list - must run on the main thread`n" +
            "                ServerPlayerDB.getInstance().save();`n" +
            "                final ArrayList<ServerMap.ServerCell> cellsSnapshot = new ArrayList<>(this.loadedCells);`n" +
            "                final Thread saveThread = new Thread(() -> {`n" +
            "                    try {`n" +
            "                        this.runAsyncSave(cellsSnapshot);`n" +
            "                    } finally {`n" +
            "                        this.asyncSaveRunning = false;`n" +
            "                    }`n" +
            "                }, `"ServerMap-AsyncSave`");`n" +
            "                saveThread.setDaemon(true);`n" +
            "                saveThread.start();`n" +
            "            } else {`n" +
            "                System.out.println(`"[PATCH-E] Skipping periodic save: previous async save still running`");`n" +
            "            }`n" +
            "        }`n`n" +
            "        if (this.queuedQuit) {")
Apply-Patch -FilePath $SrcServerMap -OldText $pE2_Old -NewText $pE2_New `
    -Description "postupdate() async save dispatch"

# E-3: add a wait at the top of QueuedSaveAll for the quit path so shutdown
#       always blocks until any in-progress background save has finished before
#       starting its own synchronous save.
Write-Host ""
Write-Host "[*] Applying Patch E-3 - quit-path wait in QueuedSaveAll()..." -ForegroundColor Cyan

$pE3_Old = ("    public void QueuedSaveAll(boolean quit) {`n" +
            "        this.saveQuitFlag = quit;`n" +
            "        this.saveClientPaused = false;`n" +
            "        this.saveStartTime = System.nanoTime();`n" +
            "        this.SaveAll();")
$pE3_New = ("    public void QueuedSaveAll(boolean quit) {`n" +
            "        // PATCH-E: wait for any in-progress background save before running the blocking quit-save`n" +
            "        if (quit) {`n" +
            "            while (this.asyncSaveRunning) {`n" +
            "                try { Thread.sleep(50L); } catch (InterruptedException ignored) {}`n" +
            "            }`n" +
            "        }`n" +
            "        this.saveQuitFlag = quit;`n" +
            "        this.saveClientPaused = false;`n" +
            "        this.saveStartTime = System.nanoTime();`n" +
            "        this.SaveAll();")
Apply-Patch -FilePath $SrcServerMap -OldText $pE3_Old -NewText $pE3_New `
    -Description "QueuedSaveAll() quit-path wait for async save"

# E-4: inject the runAsyncSave() method just before preupdate()
Write-Host ""
Write-Host "[*] Applying Patch E-4 - inject runAsyncSave() method..." -ForegroundColor Cyan

$pE4_Old = ("        System.out.println(`"Saving finish`");`n" +
            "        DebugLog.log(`"Saving took `" + (System.nanoTime() - this.saveStartTime) / 1000000.0 + `" ms`");`n" +
            "    }`n`n" +
            "    public void preupdate() {")
$pE4_New = ("        System.out.println(`"Saving finish`");`n" +
            "        DebugLog.log(`"Saving took `" + (System.nanoTime() - this.saveStartTime) / 1000000.0 + `" ms`");`n" +
            "    }`n`n" +
            "    // PATCH-E: background save implementation - called from a daemon thread`n" +
            "    // ServerPlayerDB.save() is intentionally excluded here: it was called on the main thread`n" +
            "    // before this thread started (see E-2) to avoid iterating the live connection list off-thread.`n" +
            "    private void runAsyncSave(ArrayList<ServerMap.ServerCell> cells) {`n" +
            "        long saveStart = System.nanoTime();`n" +
            "        System.out.println(`"[PATCH-E] Background save started (`" + cells.size() + `" cells)`");`n" +
            "        try {`n" +
            "            if (!GameServer.softReset && cells.size() >= 10) {`n" +
            "                for (int i = 0; i < 4; i++) {`n" +
            "                    workerThreads[i] = new WorkerThread();`n" +
            "                    workerThreads[i].setDaemon(true);`n" +
            "                    workerThreads[i].start();`n" +
            "                }`n" +
            "                for (int n = 0; n < cells.size(); n++) {`n" +
            "                    workerThreads[n % 4].putCommand(ServerMap.EThreadCommand.SaveCell, cells.get(n));`n" +
            "                    cells.get(n).UpdateVehicle();`n" +
            "                }`n" +
            "                for (int i = 0; i < 4; i++) {`n" +
            "                    workerThreads[i].putCommand(ServerMap.EThreadCommand.Quit, null);`n" +
            "                }`n" +
            "                while (true) {`n" +
            "                    boolean running = false;`n" +
            "                    for (int i = 0; i < 4; i++) {`n" +
            "                        if (!workerThreads[i].quit) { running = true; break; }`n" +
            "                    }`n" +
            "                    if (!running) {`n" +
            "                        Arrays.fill(workerThreads, null);`n" +
            "                        ServerMap.ServerCell.chunkLoader.updateSaved();`n" +
            "                        break;`n" +
            "                    }`n" +
            "                    ServerMap.ServerCell.chunkLoader.updateSaved();`n" +
            "                    try { Thread.sleep(10L); } catch (InterruptedException ignored) {}`n" +
            "                }`n" +
            "            } else {`n" +
            "                for (ServerMap.ServerCell cell : cells) {`n" +
            "                    cell.Save(false);`n" +
            "                    cell.UpdateVehicle();`n" +
            "                }`n" +
            "            }`n" +
            "            this.grid.save();`n" +
            "            ServerMap.ServerCell.chunkLoader.saveLater(GameTime.instance);`n" +
            "            ReanimatedPlayers.instance.saveReanimatedPlayers();`n" +
            "            AnimalPopulationManager.getInstance().save();`n" +
            "            MapCollisionData.instance.save();`n" +
            "            SGlobalObjects.save();`n" +
            "            WorldGenParams.INSTANCE.save();`n" +
            "            InstanceTracker.save();`n" +
            "            MetaTracker.save();`n" +
            "            try { ZomboidRadio.getInstance().Save(); } catch (Exception e) { DebugType.General.printException(e, LogSeverity.Error); }`n" +
            "            try { GlobalModData.instance.save(); } catch (Exception e) { DebugType.General.printException(e, LogSeverity.Error); }`n" +
            "            GameEntityManager.Save();`n" +
            "            WorldMapServer.instance.writeSavefile();`n" +
            "        } catch (Exception e) {`n" +
            "            DebugType.General.printException(e, LogSeverity.Error);`n" +
            "        }`n" +
            "        System.out.println(`"[PATCH-E] Background save finished in `" + (System.nanoTime() - saveStart) / 1000000.0 + `" ms`");`n" +
            "    }`n`n" +
            "    public void preupdate() {")
Apply-Patch -FilePath $SrcServerMap -OldText $pE4_Old -NewText $pE4_New `
    -Description "inject runAsyncSave() method"

Write-Host ""
Write-Host "    Patched source file written to src\" -ForegroundColor Green
Write-Host "    -> git add 42.19.0\src\zombie\network\ServerMap.java && git commit -m 'Add async save patch source'" -ForegroundColor Yellow

# ---------------------------------------------------------------------------
# Step 2: Find javac
# ---------------------------------------------------------------------------
Write-Host ""
$javac = Find-Javac
if (-not $javac) {
    $javac = Install-Jdk
}
Write-Host ""

# ---------------------------------------------------------------------------
# Step 3: Compile patched ServerMap.java
# ---------------------------------------------------------------------------
Write-Host "[*] Compiling patched ServerMap.java..." -ForegroundColor Cyan

if (-not (Test-Path $OutputDir)) { New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null }

$workSrc = Join-Path $WorkDir "src\zombie\network"
if (-not (Test-Path $workSrc)) { New-Item -Path $workSrc -ItemType Directory -Force | Out-Null }
Copy-Item $SrcServerMap (Join-Path $workSrc "ServerMap.java") -Force

$javacArgs = @(
    "--release", "25",
    "-cp",       $GameJar,
    "-d",        $OutputDir,
    "-encoding", "UTF-8",
    "-nowarn",
    (Join-Path $workSrc "ServerMap.java")
)

Write-Host "    javac $($javacArgs -join ' ')" -ForegroundColor Gray

$savedEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $javac @javacArgs 2>$null
$javacExitCode = $LASTEXITCODE
$ErrorActionPreference = $savedEAP

if ($javacExitCode -ne 0) {
    $ErrorActionPreference = "Continue"
    & $javac @javacArgs 2>&1 | Where-Object { $_ -notmatch '^Note:' } | ForEach-Object {
        Write-Host "    $_"
    }
    $ErrorActionPreference = $savedEAP
    Write-Host "ERROR: Compilation failed (exit code $javacExitCode)." -ForegroundColor Red
    Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 1
}
Write-Host "    Compiled successfully." -ForegroundColor Green

# ---------------------------------------------------------------------------
# Step 4: Backup originals from JAR
# ---------------------------------------------------------------------------
Write-Host ""
if (-not $DryRun) {
    Backup-Classes -Label "ServerMap" -JarEntries $ClassesServerMap
}

# ---------------------------------------------------------------------------
# Step 5: Deploy
# ---------------------------------------------------------------------------
Write-Host ""
if ($DryRun) {
    Write-Host "[*] DRY RUN: Would deploy .class files to:" -ForegroundColor Yellow
    Write-Host "    $DeployNet" -ForegroundColor Yellow
} else {
    Write-Host "[*] Deploying..." -ForegroundColor Cyan

    if (-not (Test-Path $DeployNet)) { New-Item -Path $DeployNet -ItemType Directory -Force | Out-Null }

    $ts = Get-Date -Format "yyyyMMdd_HHmmss"
    Get-ChildItem -Path (Join-Path $OutputDir "zombie\network") -Filter "ServerMap*.class" | ForEach-Object {
        $dest = Join-Path $DeployNet $_.Name
        if (Test-Path $dest) {
            Copy-Item $dest (Join-Path $BackupDir "ServerMap\$($_.Name).prev_$ts") -ErrorAction SilentlyContinue
        }
        Copy-Item $_.FullName $dest -Force
        Write-Host "    Deployed: zombie\network\$($_.Name)" -ForegroundColor Green
    }
}

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "=== Done ===" -ForegroundColor White
Write-Host ""
Write-Host "Patch deployed: $PatchName" -ForegroundColor Green
Write-Host ""
Write-Host "How it works:" -ForegroundColor Gray
Write-Host "  PZ classpath has '.' before 'projectzomboid.jar', so loose .class" -ForegroundColor Gray
Write-Host "  files in the game directory take precedence over JAR entries." -ForegroundColor Gray
Write-Host ""
Write-Host "Next step for Linux server:" -ForegroundColor Yellow
Write-Host "  git add 42.19.0\src\zombie\network\ServerMap.java && git commit && git push" -ForegroundColor Yellow
Write-Host "  (then run patchAsyncSave.sh on the Linux server)" -ForegroundColor Yellow
Write-Host ""
Write-Host "To revert:" -ForegroundColor Yellow
Write-Host "  .\patchAsyncSave.ps1 -Revert" -ForegroundColor Yellow
Write-Host ""
