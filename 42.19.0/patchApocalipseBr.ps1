<#
.SYNOPSIS
    Compiles ALL ApocBR patched Java sources and deploys .class files
    to Project Zomboid via classpath override.

.DESCRIPTION
    Combined deploy script for all ApocBR patches targeting Build 42.19:

    1. Zombie NoCull Fix   â€“ MovingObjectUpdateScheduler.postupdate()
       Removes ZombieCountOptimiser.deleteZombies() call that aggressively
       culls zombie populations on servers with many connected players.

    2. Pathfind Safety     â€“ PathfindNative + ChunkUpdateTask
       Stale-chunk guard that prevents SIGSEGV crashes in libPZPathFind64.so
       when a ChunkUpdateTask executes after its chunk has been removed or
       reloaded in native pathfind state.

    3. NullCraft Fix       â€“ CompressIdenticalItems.save() Null Guard
       Adds a null guard in save(ByteBuffer, InventoryItem) to prevent NPE
       when a drying/curing craft item becomes null, which would corrupt
       chunk saves and cause vehicles to vanish.

    4. Async Save Telemetry â€“ ServerMap, ApocBRServerTelemetry, etc.
       Async background save (ServerMap) + ApocBR server telemetry +
       guarded IsoWorld parallelism + vehicle hit-field optimizations.

    All classes are compiled in a single javac invocation so there are no
    conflicts between patches that touch the same class.

    This script:
      1. Locates or downloads a JDK 25+ compiler (javac)
      2. Backs up original .class files from projectzomboid.jar
      3. Compiles all patched sources against projectzomboid.jar
      4. Deploys the resulting .class files to the PZ game directory
         (classpath override: loose .class files take precedence over JAR)

.PARAMETER PZDir
    Path to the Project Zomboid installation directory.
    Default: Z:\SteamLibrary\steamapps\common\ProjectZomboid

.PARAMETER ToolsDir
    Directory containing this script (and the src\ folder). Default: $PSScriptRoot.

.PARAMETER DryRun
    If set, shows what would be done without actually deploying.

.PARAMETER Revert
    If set, removes ALL deployed .class overrides (restoring original JAR behavior).

.NOTES
    PZ uses Azul Zulu JDK 25. The bundled JRE has no javac, so we need a full JDK.
    The script will auto-download Azul Zulu JDK 25 if no suitable compiler is found.

    Game version targeted: 42.19
#>
param(
    [string]$PZDir = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ToolsDir = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

$PatchName = "ApocBR All-in-One (NoCull + PathfindSafety + NullCraft + AsyncSaveTelemetry)"

# --- Resolve ToolsDir ---
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

# --- Paths ---
$GameJar = Join-Path $PZDir "projectzomboid.jar"
if (-not (Test-Path $GameJar)) { $GameJar = Join-Path $PZDir "java\projectzomboid.jar" }
$DeployRoot = if (Test-Path (Join-Path $PZDir "java")) { Join-Path $PZDir "java" } else { $PZDir }
$SrcRoot = Join-Path $ToolsDir "src"
$BackupDir = Join-Path $ToolsDir "backups\ApocalipseBr"
$LocalJdkDir = Join-Path $ToolsDir "jdk"
$TempRoot = if (-not [string]::IsNullOrWhiteSpace($env:TEMP)) { $env:TEMP } elseif (-not [string]::IsNullOrWhiteSpace($env:TMP)) { $env:TMP } else { [System.IO.Path]::GetTempPath() }
$WorkDir = Join-Path $TempRoot ("pzpatch_apocalipsebr_" + [System.Diagnostics.Process]::GetCurrentProcess().Id + "_" + [DateTime]::UtcNow.Ticks)
$OutputDir = Join-Path $WorkDir "classes"
$RequiredMajor = 25

# Azul Zulu JDK 25 download (Windows x64 zip)
$ZuluApiUrl = "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$RequiredMajor&os=windows&arch=x64&archive_type=zip&java_package_type=jdk&latest=true"

# --- All patched source files ---
$Sources = @(
    # AsyncSaveTelemetry + NoCull (MovingObjectUpdateScheduler is shared)
    (Join-Path $SrcRoot "zombie\ApocBRServerTelemetry.java"),
    (Join-Path $SrcRoot "zombie\GameTime.java"),
    (Join-Path $SrcRoot "zombie\MovingObjectUpdateScheduler.java"),
    (Join-Path $SrcRoot "zombie\MovingObjectUpdateSchedulerUpdateBucket.java"),
    (Join-Path $SrcRoot "zombie\Lua\AsyncLuaManager.java"),
    (Join-Path $SrcRoot "zombie\Lua\LuaManager.java"),
    (Join-Path $SrcRoot "zombie\WorldSoundManager.java"),
    (Join-Path $SrcRoot "zombie\iso\FishSchoolManager.java"),
    (Join-Path $SrcRoot "zombie\iso\IsoPuddlesCompute.java"),
    (Join-Path $SrcRoot "zombie\iso\IsoGridSquare.java"),
    (Join-Path $SrcRoot "zombie\iso\objects\IsoZombieGiblets.java"),
    (Join-Path $SrcRoot "zombie\iso\objects\IsoDoor.java"),
    (Join-Path $SrcRoot "zombie\vehicles\BaseVehicle.java"),
    (Join-Path $SrcRoot "zombie\network\GameServer.java"),
    (Join-Path $SrcRoot "zombie\gameStates\IngameState.java"),
    (Join-Path $SrcRoot "zombie\iso\IsoWorld.java"),
    (Join-Path $SrcRoot "zombie\iso\IsoCell.java"),
    (Join-Path $SrcRoot "zombie\network\PlayerDownloadServer.java"),
    (Join-Path $SrcRoot "zombie\network\ServerMap.java"),
    # PathfindSafety
    (Join-Path $SrcRoot "zombie\pathfind\PathFindBehavior2.java"),
    (Join-Path $SrcRoot "zombie\pathfind\nativeCode\PathfindNative.java"),
    (Join-Path $SrcRoot "zombie\pathfind\nativeCode\ChunkUpdateTask.java"),
    (Join-Path $SrcRoot "zombie\pathfind\LineClearCollideMain.java"),
    # NullCraft
    (Join-Path $SrcRoot "zombie\inventory\CompressIdenticalItems.java"),
    # ServerChunkLoader CRC fix (async save thread safety)
    (Join-Path $SrcRoot "zombie\network\ServerChunkLoader.java"),
    # Parallel Animal Simulation (null safety + async-ready)
    (Join-Path $SrcRoot "zombie\characters\animals\IsoAnimal.java"),
    (Join-Path $SrcRoot "zombie\characters\animals\AnimalChunk.java"),
    (Join-Path $SrcRoot "zombie\characters\animals\AnimalZones.java"),
    (Join-Path $SrcRoot "zombie\characters\animals\VirtualAnimal.java"),
    (Join-Path $SrcRoot "zombie\characters\animals\VirtualAnimalState.java"),
    # IsoGameCharacter null-safety patch
    (Join-Path $SrcRoot "zombie\characters\IsoGameCharacter.java"),
    # Parallel Player LOS (split-phase compute, chunk-parallel)
    (Join-Path $SrcRoot "zombie\characters\IsoPlayer.java"),
    (Join-Path $SrcRoot "zombie\network\ServerLOS.java")
)

# --- All expected class files (relative to deploy root) ---
$ClassFiles = @(
    # AsyncSaveTelemetry + NoCull
    "zombie\ApocBRServerTelemetry.class",
    "zombie\GameTime.class",
    "zombie\MovingObjectUpdateScheduler.class",
    "zombie\MovingObjectUpdateSchedulerUpdateBucket.class",
    "zombie\Lua\AsyncLuaManager.class",
    "zombie\Lua\LuaManager`$GlobalObject.class",
    "zombie\WorldSoundManager.class",
    "zombie\WorldSoundManager`$ResultBiggestSound.class",
    "zombie\WorldSoundManager`$WorldSound.class",
    "zombie\iso\FishSchoolManager.class",
    "zombie\iso\FishSchoolManager`$ChumData.class",
    "zombie\iso\FishSchoolManager`$ZoneData.class",
    "zombie\iso\IsoPuddlesCompute.class",
    "zombie\iso\IsoGridSquare.class",
    "zombie\iso\objects\IsoZombieGiblets.class",
    "zombie\iso\objects\IsoDoor.class",
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
    "zombie\network\ServerMap`$PhaseAResult.class",
    "zombie\network\ServerMap`$ServerCell.class",
    "zombie\network\ServerMap`$WorkerThread.class",
    "zombie\network\ServerMap`$WorkerThreadCommand.class",
    # PathfindSafety
    "zombie\pathfind\PathFindBehavior2.class",
    "zombie\pathfind\nativeCode\PathfindNative.class",
    "zombie\pathfind\nativeCode\ChunkUpdateTask.class",
    "zombie\pathfind\LineClearCollideMain.class",
    # NullCraft
    "zombie\inventory\CompressIdenticalItems.class",
    "zombie\inventory\CompressIdenticalItems`$1.class",
    "zombie\inventory\CompressIdenticalItems`$PerCallData.class",
    "zombie\inventory\CompressIdenticalItems`$PerThreadData.class",
    # ServerChunkLoader CRC fix
    "zombie\network\ServerChunkLoader.class",
    # Parallel Animal Simulation
    "zombie\characters\animals\IsoAnimal.class",
    "zombie\characters\animals\AnimalChunk.class",
    "zombie\characters\animals\AnimalZones.class",
    "zombie\characters\animals\VirtualAnimal.class",
    "zombie\characters\animals\VirtualAnimalState.class",
    "zombie\characters\animals\VirtualAnimalState`$StateEat.class",
    "zombie\characters\animals\VirtualAnimalState`$StateFollow.class",
    "zombie\characters\animals\VirtualAnimalState`$StateMoveFromEat.class",
    "zombie\characters\animals\VirtualAnimalState`$StateMoveFromSleep.class",
    "zombie\characters\animals\VirtualAnimalState`$StateMoveToEat.class",
    "zombie\characters\animals\VirtualAnimalState`$StateMoveToSleep.class",
    "zombie\characters\animals\VirtualAnimalState`$StateSleep.class",
    # IsoGameCharacter null-safety patch
    "zombie\characters\IsoGameCharacter.class",
    # Parallel Player LOS
    "zombie\characters\IsoPlayer.class",
    "zombie\characters\IsoPlayer`$LOSRecord.class",
    "zombie\network\ServerLOS.class",
    "zombie\network\ServerLOS`$LOSThread.class",
    "zombie\network\ServerLOS`$PlayerData.class",
    "zombie\network\ServerLOS`$ServerLighting.class",
    "zombie\network\ServerLOS`$UpdateStatus.class",
    "zombie\network\ServerChunkLoader`$GetSquare.class",
    "zombie\network\ServerChunkLoader`$LoaderThread.class",
    "zombie\network\ServerChunkLoader`$QuitThreadTask.class",
    "zombie\network\ServerChunkLoader`$RecalcAllThread.class",
    "zombie\network\ServerChunkLoader`$SaveChunkThread.class",
    "zombie\network\ServerChunkLoader`$SaveGameTimeTask.class",
    "zombie\network\ServerChunkLoader`$SaveLoadedTask.class",
    "zombie\network\ServerChunkLoader`$SaveTask.class",
    "zombie\network\ServerChunkLoader`$SaveUnloadedTask.class"
)

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

    # 1. Check local JDK folder (from previous download)
    $localJavac = Join-Path $LocalJdkDir "bin\javac.exe"
    if (Test-Path $localJavac) {
        $ver = Get-JavacVersion $localJavac
        if ($ver -ge $RequiredMajor) {
            Write-Host "    Found local JDK: javac $ver at $localJavac" -ForegroundColor Green
            return $localJavac
        }
    }

    # 2. Check PATH
    $pathJavac = Get-Command javac -ErrorAction SilentlyContinue
    if ($pathJavac) {
        $ver = Get-JavacVersion $pathJavac.Source
        if ($ver -ge $RequiredMajor) {
            Write-Host "    Found in PATH: javac $ver at $($pathJavac.Source)" -ForegroundColor Green
            return $pathJavac.Source
        } else {
            Write-Host "    Found javac $ver in PATH (need >= $RequiredMajor, skipping)" -ForegroundColor Yellow
        }
    }

    # 3. Check common install locations
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
                Write-Host "    Found installed: javac $ver at $($found.FullName)" -ForegroundColor Green
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
        Write-Host "       Download JDK $RequiredMajor manually from https://www.azul.com/downloads/" -ForegroundColor Yellow
        exit 1
    }

    if (-not $downloadUrl) {
        Write-Host "ERROR: No JDK $RequiredMajor package found from Azul API." -ForegroundColor Red
        exit 1
    }

    Write-Host "    URL: $downloadUrl" -ForegroundColor Gray
    $zipPath    = Join-Path $ToolsDir "jdk-download.zip"
    $extractDir = Join-Path $ToolsDir "jdk-extract"

    Write-Host "    Downloading..." -ForegroundColor Gray
    Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath -UseBasicParsing
    Write-Host "    Downloaded ($([math]::Round((Get-Item $zipPath).Length / 1MB, 1)) MB). Extracting..." -ForegroundColor Gray

    if (Test-Path $extractDir) { Remove-Item $extractDir -Recurse -Force }
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

    $innerDir = Get-ChildItem -Path $extractDir -Directory | Select-Object -First 1
    if (-not $innerDir) {
        Write-Host "ERROR: Extracted archive is empty." -ForegroundColor Red
        exit 1
    }

    if (Test-Path $LocalJdkDir) { Remove-Item $LocalJdkDir -Recurse -Force }
    Move-Item $innerDir.FullName $LocalJdkDir
    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item $extractDir -Recurse -Force -ErrorAction SilentlyContinue

    $javacPath = Join-Path $LocalJdkDir "bin\javac.exe"
    if (Test-Path $javacPath) {
        $ver = Get-JavacVersion $javacPath
        Write-Host "    Installed: javac $ver at $javacPath" -ForegroundColor Green
        return $javacPath
    } else {
        Write-Host "ERROR: javac not found in downloaded JDK." -ForegroundColor Red
        exit 1
    }
}

# --- Main ---
Write-Host ""
Write-Host "=== ApocBR All-in-One Patch Suite (Build 42.19) ===" -ForegroundColor White
Write-Host "=== $PatchName ===" -ForegroundColor White
Write-Host ""

# Handle --Revert
if ($Revert) {
    Write-Host "[*] Reverting ALL ApocBR patches..." -ForegroundColor Yellow
    $reverted = $false
    foreach ($rel in $ClassFiles) {
        $path = Join-Path $DeployRoot $rel
        if (Test-Path $path) {
            Remove-Item $path -Force
            Write-Host "    Removed: $rel" -ForegroundColor Green
            $reverted = $true
        }
    }
    if ($reverted) {
        Write-Host ""
        Write-Host "=== All patches reverted. JAR originals restored on next server start. ===" -ForegroundColor White
    } else {
        Write-Host "    No patch files found to remove." -ForegroundColor Yellow
    }
    Write-Host ""
    Write-Host "Included patches:" -ForegroundColor Gray
    Write-Host "  - Zombie NoCull Fix" -ForegroundColor Gray
    Write-Host "  - Pathfind Safety (Stale-Chunk Guard)" -ForegroundColor Gray
    Write-Host "  - NullCraft Fix (CompressIdenticalItems null guard)" -ForegroundColor Gray
    Write-Host "  - Async Save + Server Telemetry" -ForegroundColor Gray
    Write-Host ""
    exit 0
}

# Validate inputs
if (-not (Test-Path $GameJar)) {
    Write-Host "ERROR: Game JAR not found: $GameJar" -ForegroundColor Red
    Write-Host "       Set -PZDir to your ProjectZomboid installation" -ForegroundColor Yellow
    exit 1
}
foreach ($src in $Sources) {
    if (-not (Test-Path $src)) {
        Write-Host "ERROR: Patched source not found: $src" -ForegroundColor Red
        exit 1
    }
}

# Step 1: Find or install JDK
$javac = Find-Javac
if (-not $javac) { $javac = Install-Jdk }

Write-Host "[*] PZ dir:  $PZDir" -ForegroundColor Cyan
Write-Host "[*] JAR:     $GameJar" -ForegroundColor Cyan
Write-Host "[*] Deploy:  $DeployRoot" -ForegroundColor Cyan
Write-Host "[*] javac:   $javac ($(& $javac -version 2>&1))" -ForegroundColor Cyan
Write-Host ""

# Step 2: Compile all patched sources together
Write-Host "[*] Compiling all patched sources together..." -ForegroundColor Cyan
if (-not (Test-Path $OutputDir)) { New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null }

$JavacOut = Join-Path $WorkDir "javac.out.log"
$JavacErr = Join-Path $WorkDir "javac.err.log"
$JavacArgs = @(
    "--release", "25",
    "-Xlint:none",
    "-implicit:none",
    "-cp",       $GameJar,
    "-sourcepath", $SrcRoot,
    "-d",        $OutputDir,
    "-encoding", "UTF-8"
) + $Sources
Write-Host "    javac invocation with $($Sources.Count) source files..." -ForegroundColor Gray
$JavacProcess = Start-Process -FilePath $javac -ArgumentList $JavacArgs -Wait -NoNewWindow -PassThru -RedirectStandardOutput $JavacOut -RedirectStandardError $JavacErr
$javacOutput = @()
if (Test-Path $JavacOut) { $javacOutput += Get-Content $JavacOut }
if (Test-Path $JavacErr) { $javacOutput += Get-Content $JavacErr }
if ($JavacProcess.ExitCode -ne 0) {
    $javacOutput | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    Write-Host ""
    Write-Host "ERROR: Compilation failed (exit code $($JavacProcess.ExitCode))." -ForegroundColor Red
    Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 1
}
$javacOutput | Where-Object { $_ -notmatch "^Note:" -and $_ -notmatch "^Recompile with" } | ForEach-Object { Write-Host $_ }
Write-Host "    Compiled successfully." -ForegroundColor Green

# Step 3: Deploy
Write-Host ""
if ($DryRun) {
    Write-Host "[*] DRY RUN: Would deploy combined classes to $DeployRoot" -ForegroundColor Yellow
    foreach ($rel in $ClassFiles) {
        $compiled = Join-Path $OutputDir $rel
        if (Test-Path $compiled) {
            Write-Host "    $rel" -ForegroundColor Yellow
        }
    }
    Write-Host ""
    Write-Host "=== Dry run complete. No files changed. ===" -ForegroundColor Green
} else {
    Write-Host "[*] Deploying..." -ForegroundColor Cyan

    # Create all necessary deploy directories
    New-Item -Path (Join-Path $DeployRoot "zombie") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie\network") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie\gameStates") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie\iso") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie\iso\objects") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie\vehicles") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie\pathfind\nativeCode") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie\inventory") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie\Lua") -ItemType Directory -Force | Out-Null
    New-Item -Path (Join-Path $DeployRoot "zombie\characters\animals") -ItemType Directory -Force | Out-Null
    New-Item -Path $BackupDir -ItemType Directory -Force | Out-Null

    $ts = Get-Date -Format "yyyyMMdd_HHmmss"
    $deployed = 0
    foreach ($rel in $ClassFiles) {
        $compiled = Join-Path $OutputDir $rel
        if (-not (Test-Path $compiled)) { continue }
        $dest = Join-Path $DeployRoot $rel

        # Backup existing override
        if (Test-Path $dest) {
            $safe = $rel.Replace('\', '_').Replace("`$", "D")
            Copy-Item $dest (Join-Path $BackupDir "$safe.prev_$ts") -Force -ErrorAction SilentlyContinue
        }

        Copy-Item $compiled $dest -Force
        Write-Host "    Deployed: $rel" -ForegroundColor Green
        $deployed++
    }
    Write-Host "    Deployed $deployed class files." -ForegroundColor Green
}

# Cleanup temp work dir
Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue

# Done
Write-Host ""
Write-Host "=== Done ===" -ForegroundColor White
Write-Host ""
Write-Host "Patch deployed: $PatchName" -ForegroundColor Green
Write-Host ""
Write-Host "Included patches:" -ForegroundColor Gray
Write-Host "  1. Zombie NoCull Fix" -ForegroundColor Gray
Write-Host "     - Removes ZombieCountOptimiser.deleteZombies() from postupdate()" -ForegroundColor Gray
Write-Host "  2. Pathfind Safety (Stale-Chunk Guard)" -ForegroundColor Gray
Write-Host "     - Prevents SIGSEGV in libPZPathFind64.so from stale ChunkUpdateTasks" -ForegroundColor Gray
Write-Host "  3. NullCraft Fix" -ForegroundColor Gray
Write-Host "     - Null guard in CompressIdenticalItems.save() prevents chunk corruption" -ForegroundColor Gray
Write-Host "  4. Async Save + Server Telemetry" -ForegroundColor Gray
Write-Host "     - Async background save, guarded IsoWorld parallelism, vehicle optimizations" -ForegroundColor Gray
Write-Host ""
Write-Host "How it works:" -ForegroundColor Gray
Write-Host "  PZ classpath has '.' before 'projectzomboid.jar', so loose .class" -ForegroundColor Gray
Write-Host "  files take precedence over the ones inside the JAR." -ForegroundColor Gray
Write-Host ""
Write-Host "Config (AsyncSaveTelemetry):" -ForegroundColor Gray
Write-Host "  -Dapocbr.telemetry.enabled=true -Dapocbr.telemetry.intervalMs=30000" -ForegroundColor Gray
Write-Host "  -Dapocbr.parallel.isoWorldSafe=true -Dapocbr.parallel.skipIfBacklogged=true" -ForegroundColor Gray
Write-Host ""
Write-Host "To revert:" -ForegroundColor Yellow
Write-Host "  .\patchApocalipseBr.ps1 -Revert" -ForegroundColor Yellow
Write-Host ""



