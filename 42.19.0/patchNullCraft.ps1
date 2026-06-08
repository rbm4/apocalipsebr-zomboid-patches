<#
.SYNOPSIS
    Compiles patched CompressIdenticalItems.java and deploys the .class files
    to Project Zomboid via classpath override.

.DESCRIPTION
    NullCraft Fix - CompressIdenticalItems.save() Null Guard (Build 42.19)

    Bug: when a drying/curing craft (DryingCraftLogic) is in progress and the
    referenced item becomes null (e.g. item despawned, player disconnected),
    the server throws NPE in CompressIdenticalItems.save(ByteBuffer, InventoryItem)
    during chunk serialization:

      NullPointerException: Cannot invoke "InventoryItem.saveWithSize" because
      "item" is null at CompressIdenticalItems.save(CompressIdenticalItems.java:343)

    This corrupts the chunk save, causing vehicles to vanish from vehicles.db
    on the next server boot.

    The patch adds a null guard at the top of save(ByteBuffer, InventoryItem):
      if (item == null) return;

    This script:
      1. Locates or downloads a JDK 25+ compiler (javac)
      2. Backs up the original CompressIdenticalItems.class from projectzomboid.jar
      3. Compiles the patched source against projectzomboid.jar
      4. Deploys the resulting .class files (including inner classes) to the PZ
         game directory (classpath override: loose .class files take precedence
         over JAR entries)

.PARAMETER PZDir
    Path to the Project Zomboid installation directory.
    Default: Z:\SteamLibrary\steamapps\common\ProjectZomboid

.PARAMETER ToolsDir
    Directory containing this script (and the src\ folder). Default: $PSScriptRoot.

.PARAMETER DryRun
    If set, shows what would be done without actually deploying.

.PARAMETER Revert
    If set, removes the deployed .class overrides (restoring original JAR behavior).

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
$PatchName       = "NullCraft Fix - CompressIdenticalItems.save() Null Guard"
$GameJar         = Join-Path $PZDir "projectzomboid.jar"
$DeployDir       = Join-Path $PZDir "zombie\inventory"
$DeployClass     = Join-Path $DeployDir "CompressIdenticalItems.class"
$BackupDir       = Join-Path $ToolsDir "backups"
$BackupClass     = Join-Path $BackupDir "CompressIdenticalItems.class.original"
$LocalJdkDir     = Join-Path $ToolsDir "jdk"
$WorkDir         = Join-Path $env:TEMP "pzpatch_nullcraft"
$OutputDir       = Join-Path $WorkDir "classes"
$SourceFile      = Join-Path $ToolsDir "src\zombie\inventory\CompressIdenticalItems.java"
$RequiredMajor   = 25

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
    if (Test-Path $BackupClass) {
        Write-Host "[*] Backup already exists: $BackupClass" -ForegroundColor Gray
        return
    }

    Write-Host "[*] Extracting original CompressIdenticalItems.class from JAR..." -ForegroundColor Cyan
    if (-not (Test-Path $BackupDir)) { New-Item -Path $BackupDir -ItemType Directory -Force | Out-Null }

    $tempDir = Join-Path $ToolsDir "tmp-extract-nc"
    if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
    New-Item -Path $tempDir -ItemType Directory -Force | Out-Null

    Push-Location $tempDir
    try {
        $jarExe = Get-Command jar -ErrorAction SilentlyContinue
        if (-not $jarExe) { Write-Host "    WARNING: 'jar' not on PATH - skipping backup" -ForegroundColor Yellow; return }
        & $jarExe.Source xf $GameJar "zombie/inventory/CompressIdenticalItems.class" 2>$null
        $extracted = Join-Path $tempDir "zombie\inventory\CompressIdenticalItems.class"
        if (Test-Path $extracted) {
            Copy-Item $extracted $BackupClass
            Write-Host "    Backed up: $BackupClass" -ForegroundColor Green
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
    $reverted = $false
    # Remove main class and all inner classes
    Get-ChildItem -Path $DeployDir -Filter "CompressIdenticalItems*.class" -ErrorAction SilentlyContinue | ForEach-Object {
        Remove-Item $_.FullName -Force
        Write-Host "    Removed: $($_.Name)" -ForegroundColor Green
        $reverted = $true
    }
    if ($reverted) {
        Write-Host ""
        Write-Host "=== Patch reverted ===" -ForegroundColor White
        Write-Host "Original CompressIdenticalItems from JAR will be used on next server start." -ForegroundColor Gray
    } else {
        Write-Host "    No patch files found to remove." -ForegroundColor Yellow
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
Write-Host "[*] Compiling patched CompressIdenticalItems.java..." -ForegroundColor Cyan
if (-not (Test-Path $OutputDir)) { New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null }

$javacArgs = @(
    "--release", "25",
    "-cp",       $GameJar,
    "-d",        $OutputDir,
    "-encoding", "UTF-8",
    $SourceFile
)
Write-Host "    javac $($javacArgs -join ' ')" -ForegroundColor Gray
& $javac @javacArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ERROR: Compilation failed (exit code $LASTEXITCODE)." -ForegroundColor Red
    Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 1
}

$compiledDir = Join-Path $OutputDir "zombie\inventory"
$mainClass   = Join-Path $compiledDir "CompressIdenticalItems.class"
if (-not (Test-Path $mainClass)) {
    Write-Host "ERROR: Expected output not found: $mainClass" -ForegroundColor Red
    exit 1
}
Write-Host "    Compiled successfully." -ForegroundColor Green

# Step 4: Deploy
Write-Host ""
if ($DryRun) {
    Write-Host "[*] DRY RUN: Would deploy to: $DeployDir\" -ForegroundColor Yellow
    Get-ChildItem -Path $compiledDir -Filter "CompressIdenticalItems*.class" | ForEach-Object {
        Write-Host "    Would copy: $($_.Name)" -ForegroundColor Yellow
    }
} else {
    Write-Host "[*] Deploying..." -ForegroundColor Cyan
    if (-not (Test-Path $DeployDir)) { New-Item -Path $DeployDir -ItemType Directory -Force | Out-Null }

    # Back up any existing deployed override before overwriting
    if (Test-Path $DeployClass) {
        $ts   = Get-Date -Format "yyyyMMdd_HHmmss"
        $prev = Join-Path $BackupDir "CompressIdenticalItems.class.prev_$ts"
        Copy-Item $DeployClass $prev
        Write-Host "    Previous override backed up: $prev" -ForegroundColor Gray
    }

    Get-ChildItem -Path $compiledDir -Filter "CompressIdenticalItems*.class" | ForEach-Object {
        Copy-Item $_.FullName (Join-Path $DeployDir $_.Name) -Force
        Write-Host "    Deployed: $($_.Name)" -ForegroundColor Green
    }
}

# Done
Write-Host ""
Write-Host "=== Done ===" -ForegroundColor White
Write-Host ""
Write-Host "Patch deployed: $PatchName" -ForegroundColor Green
Write-Host ""
Write-Host "How it works:" -ForegroundColor Gray
Write-Host "  PZ classpath has '.' before 'projectzomboid.jar', so the loose .class" -ForegroundColor Gray
Write-Host "  files at '$DeployDir' take precedence over the ones inside the JAR." -ForegroundColor Gray
Write-Host ""
Write-Host "To revert:" -ForegroundColor Yellow
Write-Host "  .\patchNullCraft.ps1 -Revert" -ForegroundColor Yellow
Write-Host "  (or delete CompressIdenticalItems*.class from: $DeployDir)" -ForegroundColor Yellow
Write-Host ""

Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
