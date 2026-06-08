<#
.SYNOPSIS
    Compiles patched DebugOptions.java and deploys .class files to Project Zomboid.

.DESCRIPTION
    Threading Optimization Patch - DebugOptions

    Context: Project Zomboid ships with three threading options that are built,
    tested, and wired up in the engine but default to false:

      Threading.Animation  (DebugOptions.threadAnimation)
        Offloads ALL AnimationPlayer.Update() calls for every moving object
        (vehicles, zombies, characters) to the PZForkJoinPool. In IsoWorld,
        MovingObjectUpdateScheduler.postupdate() runs concurrently with the
        rest of updateWorld() and is joined at FinishAnimation().
        With 30-40 parked vehicles this is the dominant CPU cost each frame.

      Threading.World  (DebugOptions.threadWorld)
        Runs updateBuildings(), ObjectRenderEffects.updateStatic(), DB updates,
        and coop player processing concurrently with the game thread's main
        update path (climate, pathfinding etc).

      Threading.Ambient  (DebugOptions.threadAmbient)
        Offloads ObjectAmbientEmitters.update() (FMOD ambient sound emitter
        polling) to the ForkJoinPool concurrently with game logic.

    All three use newOption() (not newDebugOnlyOption()), meaning they are
    production-safe and just happen to default to false. They are tested enough
    that the devs expose them to the debug options file.

    PZForkJoinPool uses Runtime.getRuntime().availableProcessors() - 1 threads,
    so on a 16-core machine this is 15 worker threads already standing by.

    The patched DebugOptions.java:
      - Changes defaults from false → true for all three options
      - Overrides load() to force them back to true after reading debug-options.ini,
        so a pre-existing cached ini cannot re-disable them

    What the K-overlay shows after this patch:
      "GPU wait" (RenderThread.getWaitTime) should drop significantly — it was
      measuring the render thread spinning idle waiting for the game thread to
      finish serial animation computation. With threadAnimation=true, bone
      matrix work happens in parallel on worker threads.

    This script:
      1. Locates or downloads a JDK 25+ compiler (javac)
      2. Backs up original DebugOptions.class from projectzomboid.jar
      3. Compiles the patched source against projectzomboid.jar
      4. Deploys resulting .class files to the PZ game directory
         (loose .class files take classpath precedence over JAR entries)

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
$LocalJdkDir   = Join-Path $ToolsDir "jdk"
$WorkDir       = Join-Path $env:TEMP "pzpatch_debugoptions_threading"
$OutputDir     = Join-Path $WorkDir "classes"
$SourceFile    = Join-Path $ToolsDir "src\zombie\debug\DebugOptions.java"
$RequiredMajor = 25

$InnerClassNames = @('DebugOptions$Checks')

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
    if ((Test-Path $BackupDir) -and (Get-ChildItem $BackupDir -Filter "*.original" -ErrorAction SilentlyContinue).Count -gt 0) {
        Write-Host "[*] Backup already exists: $BackupDir" -ForegroundColor Gray
        return
    }
    Write-Host "[*] Extracting original DebugOptions classes from JAR..." -ForegroundColor Cyan
    if (-not (Test-Path $BackupDir)) { New-Item -Path $BackupDir -ItemType Directory -Force | Out-Null }
    $tempDir = Join-Path $ToolsDir "tmp-extract-do"
    if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
    New-Item -Path $tempDir -ItemType Directory -Force | Out-Null
    Push-Location $tempDir
    try {
        $jarExe = Get-Command jar -ErrorAction SilentlyContinue
        if (-not $jarExe) { Write-Host "WARNING: 'jar' not on PATH - skipping backup" -ForegroundColor Yellow; return }
        $classArgs = @("zombie/debug/DebugOptions.class") + ($InnerClassNames | ForEach-Object { "zombie/debug/$_.class" })
        & $jarExe.Source xf $GameJar @classArgs 2>$null
        $extracted = Join-Path $tempDir "zombie\debug"
        if (Test-Path $extracted) {
            Get-ChildItem $extracted -Filter "DebugOptions*.class" | ForEach-Object {
                Copy-Item $_.FullName (Join-Path $BackupDir "$($_.Name).original")
            }
            Write-Host "    Backed up $((Get-ChildItem $BackupDir).Count) class file(s)" -ForegroundColor Green
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
    if (Test-Path $DeployClass) { Remove-Item $DeployClass -Force; Write-Host "    Removed: DebugOptions.class" -ForegroundColor Green; $reverted = $true }
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
    Write-Host "       Expected: $($ToolsDir)\src\zombie\debug\DebugOptions.java" -ForegroundColor Yellow
    exit 1
}

Backup-OriginalClasses

$javac = Find-Javac
if (-not $javac) { $javac = Install-Jdk }

# Compile
Write-Host ""
Write-Host "[*] Compiling patched DebugOptions.java..." -ForegroundColor Cyan
if (Test-Path $OutputDir) { Remove-Item $OutputDir -Recurse -Force }
New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null

$TempSrcDir = Join-Path $WorkDir "src\zombie\debug"
if (-not (Test-Path $TempSrcDir)) { New-Item -Path $TempSrcDir -ItemType Directory -Force | Out-Null }
Copy-Item $SourceFile (Join-Path $TempSrcDir "DebugOptions.java")

$compileArgs = @(
    "--release", "25",
    "-cp", $GameJar,
    "-d", $OutputDir,
    (Join-Path $TempSrcDir "DebugOptions.java")
)

if ($DryRun) {
    Write-Host "    [DryRun] Would run: $javac $($compileArgs -join ' ')" -ForegroundColor Yellow
} else {
    $result = & $javac @compileArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Compilation failed!" -ForegroundColor Red
        $result | ForEach-Object { Write-Host $_ -ForegroundColor Red }
        exit 1
    }
    Write-Host "    Compiled successfully." -ForegroundColor Green
}

# Deploy
Write-Host ""
Write-Host "[*] Deploying class files to: $DeployDir" -ForegroundColor Cyan
if (-not (Test-Path $DeployDir)) { New-Item -Path $DeployDir -ItemType Directory -Force | Out-Null }

if ($DryRun) {
    Write-Host "    [DryRun] Would deploy: DebugOptions.class + $($InnerClassNames.Count) inner class(es)" -ForegroundColor Yellow
} else {
    $CompiledDir = Join-Path $OutputDir "zombie\debug"
    $classFiles = Get-ChildItem $CompiledDir -Filter "DebugOptions*.class" -ErrorAction SilentlyContinue
    if (-not $classFiles) {
        Write-Host "ERROR: No compiled class files found in $CompiledDir" -ForegroundColor Red
        exit 1
    }
    foreach ($cf in $classFiles) {
        $dest = Join-Path $DeployDir $cf.Name
        Copy-Item $cf.FullName $dest -Force
        Write-Host "    Deployed: $($cf.Name)" -ForegroundColor Green
    }
    Write-Host ""
    Write-Host "=== Patch applied successfully ===" -ForegroundColor White
    Write-Host ""
    Write-Host "Deployed $($classFiles.Count) class file(s) to $DeployDir" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Enabled threading options:" -ForegroundColor Gray
    Write-Host "  Threading.Animation = true  (AnimationPlayer.Update on ForkJoinPool)" -ForegroundColor Gray
    Write-Host "  Threading.World     = true  (buildings/static/DB updates concurrent)" -ForegroundColor Gray
    Write-Host "  Threading.Ambient   = true  (FMOD ambient emitters concurrent)" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Expected: K-overlay 'GPU wait' drops as render thread no longer waits" -ForegroundColor Gray
    Write-Host "          on serial animation. Monitor for crash on vehicle collisions." -ForegroundColor Gray
    Write-Host ""
    Write-Host "Restart the game/server for changes to take effect." -ForegroundColor Gray
}
Write-Host ""
