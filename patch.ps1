<#
.SYNOPSIS
    Project Zomboid class patch manager - Windows entrypoint.

.DESCRIPTION
    Interactive launcher for PZ class patches.
    - Discovers available version folders in this repository
    - Prompts for the path to projectzomboid.jar
    - Detects 32 vs 64-bit layout from ProjectZomboid64.json / ProjectZomboid32.json
    - Parses the classpath field to determine the deploy base directory
    - Verifies (or installs) Java $RequiredMajor+, shared across all patches
    - Lists available patch scripts in the chosen version folder
    - Runs all patches or a single selected patch

.PARAMETER PZJar
    Path to projectzomboid.jar, or to the folder that contains it.
    Prompted interactively if omitted.

.PARAMETER Version
    Pre-select a version folder (e.g. "42.17.0") to skip the prompt.

.PARAMETER DryRun
    Passed through to every patch script - shows what would happen without
    writing any files.

.PARAMETER Revert
    Passed through to every patch script - removes deployed class overrides.

.EXAMPLE
    .\patch.ps1
    .\patch.ps1 -PZJar "Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar"
    .\patch.ps1 -PZJar "Z:\SteamLibrary\steamapps\common\ProjectZomboid"
    .\patch.ps1 -Version 42.17.0 -DryRun
    .\patch.ps1 -Revert
#>
param(
    [string]$PZJar   = "",
    [string]$Version = "",
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

$RootDir       = $PSScriptRoot
$RequiredMajor = 25
$SharedJdkDir  = Join-Path $RootDir "jdk"
$ZuluApiUrl    = "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$RequiredMajor&os=windows&arch=x64&archive_type=zip&java_package_type=jdk&latest=true"

# -- Display helpers ------------------------------------------------------------

function Write-Header([string]$Text) {
    $sep = "=" * 52
    Write-Host ""
    Write-Host $sep                    -ForegroundColor Cyan
    Write-Host "  $Text"               -ForegroundColor White
    Write-Host $sep                    -ForegroundColor Cyan
    Write-Host ""
}

function Write-Divider {
    Write-Host ("-" * 52) -ForegroundColor DarkGray
}

# -- JDK helpers ----------------------------------------------------------------

function Get-JavacMajor([string]$JavacPath) {
    try {
        $out = & $JavacPath -version 2>&1 | Out-String
        if ($out -match "javac\s+(\d+)") { return [int]$Matches[1] }
    } catch {}
    return 0
}

function Find-Javac {
    Write-Host "[*] Searching for javac >= $RequiredMajor..." -ForegroundColor Cyan

    # 1. Shared local JDK downloaded by this script
    $localJavac = Join-Path $SharedJdkDir "bin\javac.exe"
    if (Test-Path $localJavac) {
        $v = Get-JavacMajor $localJavac
        if ($v -ge $RequiredMajor) {
            Write-Host "    Found (shared JDK): javac $v" -ForegroundColor Green
            return $localJavac
        }
    }

    # 2. PATH
    $pathJavac = Get-Command javac -ErrorAction SilentlyContinue
    if ($pathJavac) {
        $v = Get-JavacMajor $pathJavac.Source
        if ($v -ge $RequiredMajor) {
            Write-Host "    Found (PATH): javac $v at $($pathJavac.Source)" -ForegroundColor Green
            return $pathJavac.Source
        } else {
            Write-Host "    PATH has javac $v (need >= $RequiredMajor, skipping)" -ForegroundColor Yellow
        }
    }

    # 3. Common install locations
    $patterns = @(
        "C:\Program Files\Zulu\zulu-$RequiredMajor*\bin\javac.exe",
        "C:\Program Files\Eclipse Adoptium\jdk-$RequiredMajor*\bin\javac.exe",
        "C:\Program Files\Java\jdk-$RequiredMajor*\bin\javac.exe",
        "C:\Program Files\Microsoft\jdk-$RequiredMajor*\bin\javac.exe"
    )
    foreach ($pattern in $patterns) {
        $hit = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit) {
            $v = Get-JavacMajor $hit.FullName
            if ($v -ge $RequiredMajor) {
                Write-Host "    Found (installed): javac $v at $($hit.FullName)" -ForegroundColor Green
                return $hit.FullName
            }
        }
    }

    return $null
}

function Install-SharedJdk {
    Write-Host "[*] No JDK $RequiredMajor+ found. Downloading Azul Zulu JDK $RequiredMajor..." -ForegroundColor Cyan

    try {
        $resp = Invoke-RestMethod -Uri $ZuluApiUrl -TimeoutSec 30
        $url  = ($resp | Select-Object -First 1).download_url
    } catch {
        Write-Host "ERROR: Azul API query failed: $_" -ForegroundColor Red
        Write-Host "       Download manually: https://www.azul.com/downloads/?version=java-$RequiredMajor" -ForegroundColor Yellow
        exit 1
    }

    if (-not $url) {
        Write-Host "ERROR: No download URL returned from Azul API." -ForegroundColor Red
        exit 1
    }

    Write-Host "    URL: $url" -ForegroundColor Gray

    $zipPath    = Join-Path $RootDir "jdk-zulu-download.zip"
    $extractDir = Join-Path $RootDir "jdk-zulu-extract"

    Write-Host "    Downloading..." -ForegroundColor Gray
    Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
    Write-Host "    Downloaded ($([math]::Round((Get-Item $zipPath).Length / 1MB, 1)) MB). Extracting..." -ForegroundColor Gray

    if (Test-Path $extractDir) { Remove-Item $extractDir -Recurse -Force }
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

    $innerDir = Get-ChildItem -Path $extractDir -Directory | Select-Object -First 1
    if (-not $innerDir) {
        Write-Host "ERROR: Extracted archive is empty." -ForegroundColor Red
        exit 1
    }

    if (Test-Path $SharedJdkDir) { Remove-Item $SharedJdkDir -Recurse -Force }
    Move-Item $innerDir.FullName $SharedJdkDir

    Remove-Item $zipPath     -Force          -ErrorAction SilentlyContinue
    Remove-Item $extractDir  -Recurse -Force -ErrorAction SilentlyContinue

    $javacPath = Join-Path $SharedJdkDir "bin\javac.exe"
    if (-not (Test-Path $javacPath)) {
        Write-Host "ERROR: javac.exe not found after JDK install." -ForegroundColor Red
        exit 1
    }

    $v = Get-JavacMajor $javacPath
    Write-Host "    Installed: javac $v at $javacPath" -ForegroundColor Green
    return $javacPath
}

# -- PZ config helpers ----------------------------------------------------------

function Get-PZJsonConfig([string]$Dir) {
    foreach ($name in @("ProjectZomboid64.json", "ProjectZomboid32.json")) {
        $path = Join-Path $Dir $name
        if (Test-Path $path) {
            $cfg  = Get-Content $path -Raw | ConvertFrom-Json
            $bits = if ($name -like "*64*") { 64 } else { 32 }
            return @{ Bits = $bits; File = $path; Config = $cfg }
        }
    }
    return $null
}

function Resolve-DeployBase([object]$Classpath, [string]$PZDir) {
    # classpath[0] == "java/." or "java/projectzomboid.jar" → Linux layout
    # classpath[0] == "."     or "projectzomboid.jar"       → Windows layout
    $first = if ($Classpath -is [array]) { [string]$Classpath[0] } else { [string]$Classpath }
    if ($first -match "^java[/\\]") {
        return (Join-Path $PZDir "java")
    }
    return $PZDir
}

# -- Main -----------------------------------------------------------------------

Write-Header "Project Zomboid Patch Manager"

# -- 1. Discover version folders -----------------------------------------------

$versions = @(
    Get-ChildItem -Path $RootDir -Directory |
    Where-Object  { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object   { [Version]$_.Name } |
    Select-Object -ExpandProperty Name
)

if ($versions.Count -eq 0) {
    Write-Host "ERROR: No version folders (e.g. 42.17.0) found in $RootDir" -ForegroundColor Red
    exit 1
}

# -- 2. Version selection ------------------------------------------------------

$selVersion = $null

if ($Version -and ($versions -contains $Version)) {
    $selVersion = $Version
    Write-Host "Version: $selVersion (pre-selected)" -ForegroundColor Green
} else {
    if ($Version) {
        Write-Host "Version '$Version' not found. Please select from the list." -ForegroundColor Yellow
        Write-Host ""
    }
    Write-Host "Available versions:" -ForegroundColor Cyan
    for ($i = 0; $i -lt $versions.Count; $i++) {
        Write-Host "  [$($i + 1)] $($versions[$i])"
    }
    Write-Host ""

    while (-not $selVersion) {
        $raw = Read-Host "Select version (1-$($versions.Count))"
        $n   = [int]($raw -replace '\D', '')
        if ($n -ge 1 -and $n -le $versions.Count) {
            $selVersion = $versions[$n - 1]
        } else {
            Write-Host "    Invalid selection. Try again." -ForegroundColor Yellow
        }
    }
}

$VersionDir = Join-Path $RootDir $selVersion
Write-Host ""
Write-Host "Version directory: $VersionDir" -ForegroundColor Gray

# -- 3. projectzomboid.jar path ------------------------------------------------

Write-Host ""
$jarValid = $false

while (-not $jarValid) {
    if (-not $PZJar) {
        $PZJar = Read-Host "Enter path to projectzomboid.jar"
    }

    # Strip surrounding quotes the user may have included
    $PZJar = $PZJar.Trim('"').Trim("'").Trim()

    # Accept a folder path — look for projectzomboid.jar inside it
    if (Test-Path $PZJar -PathType Container) {
        $candidate = Join-Path $PZJar "projectzomboid.jar"
        if (Test-Path $candidate -PathType Leaf) {
            $PZJar = $candidate
        } else {
            Write-Host "    projectzomboid.jar not found in folder: $PZJar" -ForegroundColor Yellow
            $PZJar = ""
            continue
        }
    }

    if (Test-Path $PZJar -PathType Leaf) {
        $jarValid = $true
    } else {
        Write-Host "    Path not found: $PZJar" -ForegroundColor Yellow
        $PZJar = ""
    }
}

$PZDir = Split-Path (Resolve-Path $PZJar).Path -Parent
Write-Host "PZ directory: $PZDir" -ForegroundColor Green

# -- 4. Read JSON config (arch + classpath) ------------------------------------

$pzJson = Get-PZJsonConfig $PZDir

if ($pzJson) {
    $bits       = $pzJson.Bits
    $classpath  = @($pzJson.Config.classpath)
    $deployBase = Resolve-DeployBase $classpath $PZDir

    Write-Host "Architecture: $bits-bit  ($($pzJson.File | Split-Path -Leaf))" -ForegroundColor Green
    Write-Host "Classpath[0]: $($classpath[0])"                                  -ForegroundColor Gray
    Write-Host "Deploy base:  $deployBase"                                        -ForegroundColor Green
} else {
    Write-Host "WARNING: ProjectZomboid64.json / ProjectZomboid32.json not found in $PZDir" -ForegroundColor Yellow
    Write-Host "         Defaulting to 64-bit, deploy base = PZ directory."                 -ForegroundColor Yellow
    $bits       = 64
    $deployBase = $PZDir
}

# -- 5. Java compiler check ----------------------------------------------------

Write-Host ""
$javac = Find-Javac

if (-not $javac) {
    $javac = Install-SharedJdk
}

# Expose javac to child scripts via PATH so they find it without re-downloading
$javacBin = Split-Path $javac -Parent
if ($env:PATH -notlike "*$javacBin*") {
    $env:PATH = "$javacBin;$env:PATH"
    Write-Host "    Added to PATH: $javacBin" -ForegroundColor Gray
}

# -- 6. Discover patch scripts -------------------------------------------------

$patches = @(
    Get-ChildItem -Path $VersionDir -Filter "patch*.ps1" |
    Sort-Object Name
)

if ($patches.Count -eq 0) {
    Write-Host ""
    Write-Host "ERROR: No patch scripts (patch*.ps1) found in $VersionDir" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Patches available for $selVersion :" -ForegroundColor Cyan
Write-Host "  [0] Run ALL ($($patches.Count) patches)"
for ($i = 0; $i -lt $patches.Count; $i++) {
    Write-Host "  [$($i + 1)] $($patches[$i].BaseName)"
}
Write-Host ""

# -- 7. Patch selection --------------------------------------------------------

$selPatches = $null
while (-not $selPatches) {
    $raw = Read-Host "Select (0 = all, 1-$($patches.Count) = individual)"
    $n   = [int]($raw -replace '\D', '')

    if ($raw.Trim() -eq "0") {
        $selPatches = $patches
    } elseif ($n -ge 1 -and $n -le $patches.Count) {
        $selPatches = @($patches[$n - 1])
    } else {
        Write-Host "    Invalid selection. Try again." -ForegroundColor Yellow
    }
}

# -- 8. Run patches ------------------------------------------------------------

$resultFile = Join-Path $env:TEMP "pz-patch-results-$(Get-Date -Format 'yyyyMMddHHmmss').txt"
$null | Out-File $resultFile -Encoding UTF8

Write-Host ""
$passed = 0
$failed = 0

foreach ($patch in $selPatches) {
    Write-Host "  Running $($patch.BaseName)..." -ForegroundColor Cyan -NoNewline

    $callArgs = @{ PZDir = $PZDir }
    if ($DryRun) { $callArgs['DryRun'] = $true }
    if ($Revert) { $callArgs['Revert'] = $true }

    Add-Content -Path $resultFile -Value ""
    Add-Content -Path $resultFile -Value ("-" * 52)
    Add-Content -Path $resultFile -Value "Patch: $($patch.BaseName)"
    Add-Content -Path $resultFile -Value ""

    try {
        $patchOutput = & $patch.FullName @callArgs *>&1 | Out-String
        $patchExitCode = $LASTEXITCODE
        Add-Content -Path $resultFile -Value $patchOutput

        if ($patchExitCode -and $patchExitCode -ne 0) {
            Write-Host " FAILED" -ForegroundColor Red
            Add-Content -Path $resultFile -Value "    FAILED (exit code $patchExitCode)"
            $failed++
        } else {
            Write-Host " OK" -ForegroundColor Green
            $passed++
        }
    } catch {
        Write-Host " ERROR" -ForegroundColor Red
        Add-Content -Path $resultFile -Value "    ERROR: $_"
        $failed++
    }
}

# -- Display results -----------------------------------------------------------

$sep = "=" * 52
Write-Host ""
Write-Host $sep -ForegroundColor Cyan
Write-Host "  Patch Output" -ForegroundColor White
Write-Host $sep -ForegroundColor Cyan
Write-Host ""
Get-Content $resultFile | ForEach-Object { Write-Host $_ }
Remove-Item $resultFile -Force -ErrorAction SilentlyContinue

# -- Summary -------------------------------------------------------------------

Write-Host $sep -ForegroundColor Cyan
$summaryColor = if ($failed -gt 0) { "Yellow" } else { "Green" }
Write-Host "  Session complete - $passed passed, $failed failed" -ForegroundColor $summaryColor
Write-Host $sep -ForegroundColor Cyan
Write-Host ""
