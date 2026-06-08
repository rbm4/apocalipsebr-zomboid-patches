<#
.SYNOPSIS
    Compiles patched MovingObjectUpdateScheduler.java and deploys the .class
    to Project Zomboid via classpath override.

.DESCRIPTION
    Zombie NoCull Fix - MovingObjectUpdateScheduler.postupdate() (Build 42.19)

    Build 42.19 added a call to ZombieCountOptimiser.deleteZombies() at the
    start of MovingObjectUpdateScheduler.postupdate(), which runs every frame
    on the dedicated server. This aggressively culls zombie populations from
    ~5000 down to ~400 on servers with many connected players.

    In Build 42.18 this cull did not exist server-side. This patch restores
    the 42.18 behavior by compiling a version of the class with the
    deleteZombies() call removed from postupdate().

    Note: ZombieCountOptimiser.startCount() and incrementZombie() in
    startFrame() are NOT removed - only the actual deletion step in postupdate()
    is suppressed.

    This script:
      1. Locates or downloads a JDK 25+ compiler (javac)
      2. Backs up the original MovingObjectUpdateScheduler.class from the JAR
      3. Compiles the patched source against projectzomboid.jar
      4. Deploys the resulting .class to the PZ game directory
         (classpath override: loose .class files take precedence over JAR entries)

.PARAMETER PZDir
    Path to the Project Zomboid installation directory.
    Default: Z:\SteamLibrary\steamapps\common\ProjectZomboid

.PARAMETER ToolsDir
    Directory containing this script (and the src\ folder). Default: $PSScriptRoot.

.PARAMETER DryRun
    If set, shows what would be done without actually deploying.

.PARAMETER Revert
    If set, removes the deployed .class override (restoring original JAR behavior).

.NOTES
    PZ uses Azul Zulu JDK 25. The bundled JRE has no javac, so we need a full JDK.
    The script will auto-download Azul Zulu JDK 25 if no suitable compiler is found.

    Game version targeted: 42.19
#>
param(
    [string]$PZDir    = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ToolsDir = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

# --- Configuration ---
$PatchName     = "Zombie NoCull Fix - MovingObjectUpdateScheduler.postupdate()"
$GameJar       = Join-Path $PZDir "projectzomboid.jar"
$DeployDir     = Join-Path $PZDir "zombie"
$DeployClass   = Join-Path $DeployDir "MovingObjectUpdateScheduler.class"
$BackupDir     = Join-Path $ToolsDir "backups\MovingObjectUpdateScheduler"
$LocalJdkDir   = Join-Path $ToolsDir "jdk"
$WorkDir       = Join-Path $env:TEMP "pzpatch_zombienocull"
$OutputDir     = Join-Path $WorkDir "classes"
$SourceFile    = Join-Path $ToolsDir "src\zombie\MovingObjectUpdateScheduler.java"
$RequiredMajor = 25

# Azul Zulu JDK 25 download (Windows x64 zip)
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

function Backup-OriginalClass {
    if ((Test-Path $BackupDir) -and (Get-ChildItem $BackupDir -Filter "*.original" -ErrorAction SilentlyContinue).Count -gt 0) {
        Write-Host "[*] Backup already exists: $BackupDir" -ForegroundColor Gray
        return
    }

    Write-Host "[*] Extracting original MovingObjectUpdateScheduler.class from JAR..." -ForegroundColor Cyan
    if (-not (Test-Path $BackupDir)) { New-Item -Path $BackupDir -ItemType Directory -Force | Out-Null }

    $tempDir = Join-Path $ToolsDir "tmp-extract-mous"
    if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
    New-Item -Path $tempDir -ItemType Directory -Force | Out-Null

    Push-Location $tempDir
    try {
        $jarExe = Get-Command jar -ErrorAction SilentlyContinue
        if (-not $jarExe) { Write-Host "    WARNING: 'jar' not on PATH - skipping backup" -ForegroundColor Yellow; return }
        & $jarExe.Source xf $GameJar "zombie/MovingObjectUpdateScheduler.class" 2>$null
        $extracted = Join-Path $tempDir "zombie\MovingObjectUpdateScheduler.class"
        if (Test-Path $extracted) {
            Copy-Item $extracted (Join-Path $BackupDir "MovingObjectUpdateScheduler.class.original")
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

# Handle --Revert
if ($Revert) {
    if (Test-Path $DeployClass) {
        Remove-Item $DeployClass -Force
        Write-Host "    Removed: MovingObjectUpdateScheduler.class" -ForegroundColor Green
        Write-Host ""
        Write-Host "=== Patch reverted - original JAR behavior restored ===" -ForegroundColor White
    } else {
        Write-Host "    No patch file found to remove." -ForegroundColor Yellow
    }
    Write-Host ""
    exit 0
}

# Validate inputs
if (-not (Test-Path $GameJar)) {
    Write-Host "ERROR: Game JAR not found: $GameJar" -ForegroundColor Red
    Write-Host "       Set -PZDir to your ProjectZomboid installation" -ForegroundColor Yellow
    exit 1
}
if (-not (Test-Path $SourceFile)) {
    Write-Host "ERROR: Patched source not found: $SourceFile" -ForegroundColor Red
    exit 1
}

# Step 1: Backup
Backup-OriginalClass

# Step 2: Find or install JDK
$javac = Find-Javac
if (-not $javac) { $javac = Install-Jdk }

# Step 3: Compile
Write-Host ""
Write-Host "[*] Compiling patched MovingObjectUpdateScheduler.java..." -ForegroundColor Cyan
if (-not (Test-Path $OutputDir)) { New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null }

# Stage source in a temp layout matching the package path
$TempSrcDir = Join-Path $WorkDir "src\zombie"
if (-not (Test-Path $TempSrcDir)) { New-Item -Path $TempSrcDir -ItemType Directory -Force | Out-Null }
$TempSource = Join-Path $TempSrcDir "MovingObjectUpdateScheduler.java"
Copy-Item $SourceFile $TempSource

$javacArgs = @(
    "--release", "25",
    "-cp",       $GameJar,
    "-d",        $OutputDir,
    "-encoding", "UTF-8",
    $TempSource
)
Write-Host "    javac $($javacArgs -join ' ')" -ForegroundColor Gray
& $javac @javacArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ERROR: Compilation failed (exit code $LASTEXITCODE)." -ForegroundColor Red
    Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 1
}

$compiledClass = Join-Path $OutputDir "zombie\MovingObjectUpdateScheduler.class"
if (-not (Test-Path $compiledClass)) {
    Write-Host "ERROR: Expected output not found: $compiledClass" -ForegroundColor Red
    exit 1
}
Write-Host "    Compiled successfully." -ForegroundColor Green

# Step 4: Deploy
Write-Host ""
if ($DryRun) {
    Write-Host "[*] DRY RUN: Would deploy to $DeployDir\" -ForegroundColor Yellow
    Write-Host "    Would copy: MovingObjectUpdateScheduler.class" -ForegroundColor Yellow
} else {
    Write-Host "[*] Deploying..." -ForegroundColor Cyan
    if (-not (Test-Path $DeployDir)) { New-Item -Path $DeployDir -ItemType Directory -Force | Out-Null }

    if (Test-Path $DeployClass) {
        $ts   = Get-Date -Format "yyyyMMdd_HHmmss"
        $prev = Join-Path $BackupDir "MovingObjectUpdateScheduler.class.prev_$ts"
        Copy-Item $DeployClass $prev
        Write-Host "    Previous override backed up: $prev" -ForegroundColor Gray
    }

    Copy-Item $compiledClass $DeployClass -Force
    Write-Host "    Deployed: MovingObjectUpdateScheduler.class" -ForegroundColor Green
}

# Done
Write-Host ""
Write-Host "=== Done ===" -ForegroundColor White
Write-Host ""
Write-Host "Patch deployed: $PatchName" -ForegroundColor Green
Write-Host ""
Write-Host "How it works:" -ForegroundColor Gray
Write-Host "  PZ classpath has '.' before 'projectzomboid.jar', so the loose .class" -ForegroundColor Gray
Write-Host "  file at '$DeployDir' takes precedence over the one inside the JAR." -ForegroundColor Gray
Write-Host ""
Write-Host "To revert:" -ForegroundColor Yellow
Write-Host "  .\patchZombieNoCull.ps1 -Revert" -ForegroundColor Yellow
Write-Host "  (or delete MovingObjectUpdateScheduler.class from: $DeployDir)" -ForegroundColor Yellow
Write-Host ""

Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
