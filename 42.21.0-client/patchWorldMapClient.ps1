param(
    [string]$PZDir = 'Z:\SteamLibrary\steamapps\common\ProjectZomboid',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$javac = Join-Path $repoRoot 'jdk/bin/javac.exe'
$gameJar = Join-Path $PZDir 'projectzomboid.jar'
if (-not (Test-Path -LiteralPath $gameJar)) { $gameJar = Join-Path $PZDir 'java/projectzomboid.jar' }
if (-not (Test-Path -LiteralPath $gameJar)) { throw 'projectzomboid.jar not found.' }
if (-not (Test-Path -LiteralPath $javac)) { throw 'Repository JDK 25 is required at jdk/bin/javac.exe.' }
# Match the actual vanilla class used for this target, rather than silently
# installing a whole-class override against a different game implementation.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($gameJar)
try {
    $entry = $archive.GetEntry('zombie/worldMap/WorldMapGeometry.class')
    if ($null -eq $entry) { throw 'Vanilla WorldMapGeometry class missing from JAR.' }
    $stream = $entry.Open()
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $baselineHash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose(); $stream.Dispose() }
} finally { $archive.Dispose() }
if ($baselineHash -ne '03439dd20bd157202738a8dcb77bd51621c465156c34fe45588376fb38ff6a4d') {
    throw 'Game JAR geometry differs from the verified 42.21.0 baseline. Refusing installation.'
}
$deployRoot = Split-Path -Parent $gameJar
$source = Join-Path $PSScriptRoot 'src/zombie/worldMap/WorldMapGeometry.java'
$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/')
$work = Join-Path $tempBase ('apocbr-worldmap-client-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $work 'classes'

try {
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    & $javac --release 25 -encoding UTF-8 -implicit:none -cp $gameJar -d $classes $source
    if ($LASTEXITCODE -ne 0) { throw '42.21.0 client geometry compilation failed.' }
    $outputs = @(Get-ChildItem -LiteralPath $classes -Filter '*.class' -Recurse -File)
    if ($outputs.Count -ne 4) { throw "Unexpected class family: $($outputs.Count) files (expected 4)." }
    Write-Output 'Compiled 42.21.0 WorldMapGeometry client patch successfully.'
    if ($DryRun) {
        foreach ($output in $outputs) { Write-Output ('DRY RUN: would deploy zombie/worldMap/' + $output.Name) }
        Write-Output 'No game files changed.'
    } else {
        $destination = Join-Path $deployRoot 'zombie/worldMap'
        $backup = Join-Path $PSScriptRoot ('backups/' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff'))
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        foreach ($output in $outputs) {
            $target = Join-Path $destination $output.Name
            if (Test-Path -LiteralPath $target) {
                New-Item -ItemType Directory -Path $backup -Force | Out-Null
                Copy-Item -LiteralPath $target -Destination (Join-Path $backup $output.Name)
            }
            Copy-Item -LiteralPath $output.FullName -Destination $target
            Write-Output ('Deployed: ' + $target)
        }
        Write-Output 'Client override installed. Fully restart Project Zomboid. The launcher must put loose classes before the game JAR.'
    }
} finally {
    $resolvedWork = [System.IO.Path]::GetFullPath($work)
    $allowedPrefix = $tempBase + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolvedWork.StartsWith($allowedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing temporary cleanup outside $tempBase"
    }
    if (Test-Path -LiteralPath $resolvedWork) { Remove-Item -LiteralPath $resolvedWork -Recurse -Force }
}
