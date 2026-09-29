<#
.SYNOPSIS
    Compiles and deploys ApocBR client-side Project Zomboid 42.19 patches.

.DESCRIPTION
    Client-only patch script. It compiles every patched Java source under
    .\src\zombie and deploys the generated .class files as loose classpath
    overrides next to projectzomboid.jar. Before compiling/deploying, it shows
    a Portuguese feature summary and waits for the player to press a key.

    Use -ForceJdk to always use the bundled/downloaded Azul Zulu JDK and ignore
    any javac that may be installed on the player's machine.
#>
param(
    [string]$PZDir = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ToolsDir = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert,
    [switch]$ForceJdk
)

$ErrorActionPreference = "Stop"
$ForceJdk = $true

$PatchName = "ApocBR Server optimization Patch (Build 42.20.1)"
$RequiredMajor = 25

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

$GameJar = Join-Path $PZDir "projectzomboid.jar"
if (-not (Test-Path -LiteralPath $GameJar)) { $GameJar = Join-Path $PZDir "java\projectzomboid.jar" }
if (-not (Test-Path -LiteralPath $GameJar)) { $GameJar = Join-Path $PZDir "java\ProjectZomboid.jar" }

$DeployRoot = if (Test-Path -LiteralPath (Join-Path $PZDir "java")) { Join-Path $PZDir "java" } else { $PZDir }
$SrcRoot = Join-Path $ToolsDir "src"
$BackupDir = Join-Path $ToolsDir "backups\ApocalipseBrClient"
$LocalJdkDir = Join-Path $ToolsDir "jdk"
$TempRoot = if (-not [string]::IsNullOrWhiteSpace($env:TEMP)) { $env:TEMP } elseif (-not [string]::IsNullOrWhiteSpace($env:TMP)) { $env:TMP } else { [System.IO.Path]::GetTempPath() }
$WorkDir = Join-Path $TempRoot ("pzpatch_apocbr_client_" + [System.Diagnostics.Process]::GetCurrentProcess().Id + "_" + [DateTime]::UtcNow.Ticks)
$OutputDir = Join-Path $WorkDir "classes"
$ZuluApiUrl = "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$RequiredMajor&os=windows&arch=x64&archive_type=zip&java_package_type=jdk&latest=true"

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
    if (Test-Path -LiteralPath $localJavac) {
        $ver = Get-JavacVersion $localJavac
        if ($ver -ge $RequiredMajor) {
            Write-Host "    Found local JDK: javac $ver at $localJavac" -ForegroundColor Green
            return $localJavac
        }
    }

    if ($ForceJdk) {
        Write-Host "    ForceJdk is set: skipping system javac, will download/use bundled Zulu JDK" -ForegroundColor Yellow
        return $null
    }

    $pathJavac = Get-Command javac -ErrorAction SilentlyContinue
    if ($pathJavac) {
        $ver = Get-JavacVersion $pathJavac.Source
        if ($ver -ge $RequiredMajor) {
            Write-Host "    Found in PATH: javac $ver at $($pathJavac.Source)" -ForegroundColor Green
            return $pathJavac.Source
        }
        Write-Host "    Found javac $ver in PATH (need >= $RequiredMajor, skipping)" -ForegroundColor Yellow
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
        $response = Invoke-RestMethod -Uri $ZuluApiUrl -TimeoutSec 30
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

    $zipPath = Join-Path $ToolsDir "jdk-download.zip"
    $extractDir = Join-Path $ToolsDir "jdk-extract"

    Write-Host "    URL: $downloadUrl" -ForegroundColor Gray
    Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath -UseBasicParsing

    if (Test-Path -LiteralPath $extractDir) { Remove-Item -LiteralPath $extractDir -Recurse -Force }
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

    $innerDir = Get-ChildItem -LiteralPath $extractDir -Directory | Select-Object -First 1
    if (-not $innerDir) {
        Write-Host "ERROR: Extracted archive is empty." -ForegroundColor Red
        exit 1
    }

    if (Test-Path -LiteralPath $LocalJdkDir) { Remove-Item -LiteralPath $LocalJdkDir -Recurse -Force }
    Move-Item -LiteralPath $innerDir.FullName -Destination $LocalJdkDir
    Remove-Item -LiteralPath $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $extractDir -Recurse -Force -ErrorAction SilentlyContinue

    $javacPath = Join-Path $LocalJdkDir "bin\javac.exe"
    if (Test-Path -LiteralPath $javacPath) {
        $ver = Get-JavacVersion $javacPath
        Write-Host "    Installed: javac $ver at $javacPath" -ForegroundColor Green
        return $javacPath
    }

    Write-Host "ERROR: javac not found in downloaded JDK." -ForegroundColor Red
    exit 1
}

function ConvertTo-JavacArgFileLine {
    param([string]$Argument)

    if ($Argument -match '[\s"]') {
        return '"' + $Argument.Replace('\', '\\').Replace('"', '\"') + '"'
    }

    return $Argument
}

function Get-RelativeClassPathForSource {
    param([string]$SourcePath)
    $fullSrcRoot = (Resolve-Path -LiteralPath $SrcRoot).Path
    $fullSource = (Resolve-Path -LiteralPath $SourcePath).Path
    $rel = $fullSource.Substring($fullSrcRoot.Length).TrimStart('\', '/')
    return [System.IO.Path]::ChangeExtension($rel, ".class")
}

function Remove-DeployedClassFamily {
    param([string]$RelativeClassPath)

    $dest = Join-Path $DeployRoot $RelativeClassPath
    $dir = Split-Path -Parent $dest
    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($dest)
    $removed = 0

    if (Test-Path -LiteralPath $dest) {
        Remove-Item -LiteralPath $dest -Force
        Write-Host "    Removed: $RelativeClassPath" -ForegroundColor Green
        $removed++
    }

    if (Test-Path -LiteralPath $dir) {
        Get-ChildItem -LiteralPath $dir -Filter ($baseName + '`$*.class') -File -ErrorAction SilentlyContinue | ForEach-Object {
            $rel = $_.FullName.Substring($DeployRoot.Length).TrimStart('\', '/')
            Remove-Item -LiteralPath $_.FullName -Force
            Write-Host "    Removed: $rel" -ForegroundColor Green
            $script:removed++
        }
    }

    return $removed
}

Write-Host ""
Write-Host "=== $PatchName ===" -ForegroundColor White
Write-Host ""

if (-not (Test-Path -LiteralPath $SrcRoot)) {
    Write-Host "ERROR: Source root not found: $SrcRoot" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path -LiteralPath $GameJar)) {
    Write-Host "ERROR: Game JAR not found under PZDir: $PZDir" -ForegroundColor Red
    exit 1
}

$Sources = @(Get-ChildItem -LiteralPath (Join-Path $SrcRoot "zombie") -Recurse -Filter "*.java" -File | Sort-Object FullName | ForEach-Object { $_.FullName })
if ($Sources.Count -eq 0) {
    Write-Host "ERROR: No patched Java sources found under $SrcRoot\zombie" -ForegroundColor Red
    exit 1
}
$ClassRoots = @($Sources | ForEach-Object { Get-RelativeClassPathForSource $_ })

if ($Revert) {
    Write-Host "[*] Reverting client patch class overrides from $DeployRoot..." -ForegroundColor Yellow
    $totalRemoved = 0
    foreach ($rel in $ClassRoots) {
        $totalRemoved += Remove-DeployedClassFamily -RelativeClassPath $rel
    }
    if ($totalRemoved -eq 0) {
        Write-Host "    No client patch class overrides found." -ForegroundColor Yellow
    }
    Write-Host ""
    Write-Host "=== Revert complete ===" -ForegroundColor White
    exit 0
}

$javac = Find-Javac
if (-not $javac) { $javac = Install-Jdk }

Write-Host "[*] PZ dir:  $PZDir" -ForegroundColor Cyan
Write-Host "[*] JAR:     $GameJar" -ForegroundColor Cyan
Write-Host "[*] Deploy:  $DeployRoot" -ForegroundColor Cyan
Write-Host "[*] Sources: $($Sources.Count) client Java files" -ForegroundColor Cyan
Write-Host "[*] javac:   $javac ($(& $javac -version 2>&1))" -ForegroundColor Cyan
Write-Host ""

Write-Host "[*] Compiling client patched sources..." -ForegroundColor Cyan
New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null

$JavacOut = Join-Path $WorkDir "javac.out.log"
$JavacErr = Join-Path $WorkDir "javac.err.log"
$JavacArgsFile = Join-Path $WorkDir "javac.args"
$JavacArgs = @(
    "--release", "25",
    "-Xlint:none",
    "-implicit:none",
    "-cp", $GameJar,
    "-sourcepath", $SrcRoot,
    "-d", $OutputDir,
    "-encoding", "UTF-8"
) + $Sources

Write-Host "    javac invocation with $($Sources.Count) source files..." -ForegroundColor Gray
$JavacArgFileLines = @($JavacArgs | ForEach-Object { ConvertTo-JavacArgFileLine $_ })
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($JavacArgsFile, [string[]]$JavacArgFileLines, $utf8NoBom)
$JavacArgFileRef = "@`"$JavacArgsFile`""
$JavacProcess = Start-Process -FilePath $javac -ArgumentList $JavacArgFileRef -Wait -NoNewWindow -PassThru -RedirectStandardOutput $JavacOut -RedirectStandardError $JavacErr
$JavacExitCode = $JavacProcess.ExitCode
$javacOutput = @()
if (Test-Path -LiteralPath $JavacOut) { $javacOutput += Get-Content -LiteralPath $JavacOut }
if (Test-Path -LiteralPath $JavacErr) { $javacOutput += Get-Content -LiteralPath $JavacErr }
if ($JavacExitCode -ne 0) {
    $javacOutput | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    Write-Host ""
    Write-Host "ERROR: Compilation failed (exit code $JavacExitCode)." -ForegroundColor Red
    Remove-Item -LiteralPath $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 1
}
$javacOutput | Where-Object { $_ -notmatch "^Note:" -and $_ -notmatch "^Recompile with" } | ForEach-Object { Write-Host $_ }
Write-Host "    Compiled successfully." -ForegroundColor Green

$CompiledClasses = @(Get-ChildItem -LiteralPath $OutputDir -Recurse -Filter "*.class" -File | Sort-Object FullName)
if ($CompiledClasses.Count -eq 0) {
    Write-Host "ERROR: Compilation produced no class files." -ForegroundColor Red
    Remove-Item -LiteralPath $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 1
}

Write-Host ""
if ($DryRun) {
    Write-Host "[*] DRY RUN: Would deploy $($CompiledClasses.Count) client class files to $DeployRoot" -ForegroundColor Yellow
    foreach ($class in $CompiledClasses) {
        $rel = $class.FullName.Substring($OutputDir.Length).TrimStart('\', '/')
        Write-Host "    $rel" -ForegroundColor Yellow
    }
    Write-Host ""
    Write-Host "=== Dry run complete. No files changed. ===" -ForegroundColor Green
} else {
    Write-Host "[*] Deploying client class overrides..." -ForegroundColor Cyan
    New-Item -Path $BackupDir -ItemType Directory -Force | Out-Null
    $ts = Get-Date -Format "yyyyMMdd_HHmmss"
    $deployed = 0

    foreach ($class in $CompiledClasses) {
        $rel = $class.FullName.Substring($OutputDir.Length).TrimStart('\', '/')
        $dest = Join-Path $DeployRoot $rel
        $destDirectory = Split-Path -Parent $dest
        if (-not (Test-Path -LiteralPath $destDirectory)) {
            New-Item -Path $destDirectory -ItemType Directory -Force | Out-Null
        }

        if (Test-Path -LiteralPath $dest) {
            $safe = $rel.Replace('\', '_').Replace("`$", "D")
            Copy-Item -LiteralPath $dest -Destination (Join-Path $BackupDir "$safe.prev_$ts") -Force -ErrorAction SilentlyContinue
        }

        Copy-Item -LiteralPath $class.FullName -Destination $dest -Force
        Write-Host "    Deployed: $rel" -ForegroundColor Green
        $deployed++
    }

    Write-Host "    Deployed $deployed client class files." -ForegroundColor Green
}

Remove-Item -LiteralPath $WorkDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "=== Done ===" -ForegroundColor White
if ($DryRun) { Write-Host "Patch verified: $PatchName" -ForegroundColor Green } else { Write-Host "Patch deployed: $PatchName" -ForegroundColor Green }
Write-Host ""
Write-Host "Included client source roots:" -ForegroundColor Gray
foreach ($src in $Sources) {
    $rel = $src.Substring($SrcRoot.Length).TrimStart('\', '/')
    Write-Host "  - $rel" -ForegroundColor Gray
}
Write-Host ""
Write-Host "To revert:" -ForegroundColor Yellow
Write-Host "  .\patchApocalipseBr.ps1 -Revert" -ForegroundColor Yellow
Write-Host ""


