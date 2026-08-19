param(
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

function Get-SteamPath {
    $keys = @(
        "HKCU:\Software\Valve\Steam",
        "HKLM:\SOFTWARE\WOW6432Node\Valve\Steam",
        "HKLM:\SOFTWARE\Valve\Steam"
    )

    foreach ($key in $keys) {
        try {
            $props = Get-ItemProperty -Path $key -ErrorAction Stop
            $candidates = @($props.SteamPath, $props.InstallPath)

            foreach ($candidate in $candidates) {
                if (!$candidate) {
                    continue
                }

                $path = $candidate.Replace("/", "\")
                
                if (Test-Path -LiteralPath $path) {
                    return (Resolve-Path -LiteralPath $path).Path
                }
            }
        } catch {}
    }

    return $null
}

function Read-AcfValues {
    param([string]$Path)

    $values = @{}

    Get-Content -LiteralPath $Path | ForEach-Object {
        if ($_ -match '^\s*"([^"]+)"\s+"([^"]*)"\s*$') {
            $values[$matches[1]] = $matches[2]
        }
    }

    return $values
}

function Get-SteamLibraries {
    param([string]$SteamPath)

    $libraries = @()

    if ($SteamPath -and (Test-Path -LiteralPath $SteamPath)) {
        $libraries += (Resolve-Path -LiteralPath $SteamPath).Path
    }

    $vdfCandidates = @(
        (Join-Path -Path $SteamPath -ChildPath "steamapps\libraryfolders.vdf")
        (Join-Path -Path $SteamPath -ChildPath "config\libraryfolders.vdf")
    )

    foreach ($vdf in $vdfCandidates) {
        if (!(Test-Path -LiteralPath $vdf)) {
            continue
        }

        Get-Content -LiteralPath $vdf | ForEach-Object {
            if ($_ -match '^\s*"path"\s+"([^"]+)"\s*$') {
                $libraryPath = $matches[1].Replace("\\", "\").Replace("/", "\")

                if (Test-Path -LiteralPath $libraryPath) {
                    $libraries += (Resolve-Path -LiteralPath $libraryPath).Path
                }
            }
        }
    }

    return $libraries | Select-Object -Unique
}

function Get-ProjectZomboidPath {
    $appId = "108600"
    $steamPath = Get-SteamPath

    if (!$steamPath) {
        throw "Steam installation folder was not found."
    }

    $libraries = Get-SteamLibraries -SteamPath $steamPath
    $checkedManifests = @()

    foreach ($library in $libraries) {
        $manifest = Join-Path -Path $library -ChildPath "steamapps\appmanifest_$appId.acf"
        $checkedManifests += $manifest

        if (!(Test-Path -LiteralPath $manifest)) {
            continue
        }

        $acf = Read-AcfValues -Path $manifest
        $installDir = $acf["installdir"]

        if (!$installDir) {
            continue
        }

        $gamePath = Join-Path -Path $library -ChildPath "steamapps\common\$installDir"

        $exeCandidates = @(
            "ProjectZomboid64.exe",
            "ProjectZomboid32.exe",
            "ProjectZomboid.exe"
        )

        foreach ($exe in $exeCandidates) {
            $exePath = Join-Path -Path $gamePath -ChildPath $exe

            if (Test-Path -LiteralPath $exePath) {
                return (Resolve-Path -LiteralPath $gamePath).Path
            }
        }
    }

    $checkedText = $checkedManifests -join "`n"
    throw "Project Zomboid was not found. Checked manifests:`n$checkedText"
}


$projectZomboidPath = Get-ProjectZomboidPath
$clientToolsDir = Join-Path -Path $PSScriptRoot -ChildPath "42.20.3"
$clientPatch = Join-Path -Path $clientToolsDir -ChildPath "patchApocalipseBr.ps1"

if (-not (Test-Path -LiteralPath $clientPatch)) {
    throw "Client patch script was not found: $clientPatch"
}

Write-Host "[*] Project Zomboid: $projectZomboidPath" -ForegroundColor Cyan
Write-Host "[*] Client tools:    $clientToolsDir" -ForegroundColor Cyan
Write-Host "[*] Client patch:    $clientPatch" -ForegroundColor Cyan

if ($DryRun -and $Revert) {
    & $clientPatch -PZDir $projectZomboidPath -ToolsDir $clientToolsDir -DryRun -Revert -ForceJdk
} elseif ($DryRun) {
    & $clientPatch -PZDir $projectZomboidPath -ToolsDir $clientToolsDir -DryRun -ForceJdk
} elseif ($Revert) {
    & $clientPatch -PZDir $projectZomboidPath -ToolsDir $clientToolsDir -Revert -ForceJdk
} else {
    & $clientPatch -PZDir $projectZomboidPath -ToolsDir $clientToolsDir -ForceJdk
}
exit $LASTEXITCODE



