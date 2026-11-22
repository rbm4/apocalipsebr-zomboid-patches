param(
    [Parameter(Mandatory=$true)][string]$PZDir,
    [switch]$DryRun,
    [switch]$Revert
)
$ErrorActionPreference = 'Stop'
$versionRoot = Split-Path -Parent $PSScriptRoot
$PZDir = [IO.Path]::GetFullPath($PZDir)
$runtimeDir = Join-Path $PZDir 'apocbr-lib'
$gameJar = @('projectzomboid.jar', 'java\projectzomboid.jar', 'java\ProjectZomboid.jar') |
    ForEach-Object { Join-Path $PZDir $_ } | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (-not $gameJar) { throw "Original projectzomboid.jar not found under $PZDir" }
$gameDir = Split-Path -Parent $gameJar
$runtimeName = 'projectzomboid-apocbr.jar'
$loose = if ($gameDir -eq $PZDir.TrimEnd('\', '/')) { '.' } else { 'java/.' }

function Write-RuntimeFile {
    param([string]$Path, [string]$Text, [Text.Encoding]$Encoding)
    $temp = $Path + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllText($temp, $Text, $Encoding)
        [IO.File]::Replace($temp, $Path, [NullString]::Value)
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
    }
}

# Plan every JSON edit before any installation writes. Preserve unrelated settings.
$jsonUpdates = @()
foreach ($jsonName in @('ProjectZomboid64.json', 'ProjectZomboid32.json')) {
    $jsonPath = Join-Path $PZDir $jsonName
    if (-not (Test-Path -LiteralPath $jsonPath -PathType Leaf)) { continue }
    $original = [IO.File]::ReadAllBytes($jsonPath)
    $config = [Text.Encoding]::UTF8.GetString($original).TrimStart([char]0xFEFF) | ConvertFrom-Json
    $cp = $config.classpath
    if ($cp -isnot [Array] -or @($cp | Where-Object { $_ -isnot [string] }).Count -gt 0) {
        throw "Invalid launcher classpath: $jsonPath"
    }
    $next = @()
    $found = $false
    foreach ($entry in $cp) {
        $normalized = $entry.Replace('\', '/')
        $full = if ([IO.Path]::IsPathRooted($normalized)) {
            [IO.Path]::GetFullPath($normalized)
        } else { [IO.Path]::GetFullPath((Join-Path $PZDir $normalized)) }
        $base = [IO.Path]::GetFileName($full)
        if ($base -in @('projectzomboid.jar', $runtimeName)) {
            if ((Split-Path -Parent $full) -ne $gameDir) { throw "Launcher references another game JAR directory: $jsonPath" }
            if (-not $found) {
                $name = if ($Revert) { [IO.Path]::GetFileName($gameJar) } else { $runtimeName }
                $prefix = $normalized.Substring(0, $normalized.Length - $base.Length)
                $next += $prefix + $name
            }
            $found = $true
        } elseif ($normalized -notin @('apocbr-lib/postgresql-42.7.14.jar', 'apocbr-lib/HikariCP-7.1.0.jar')) {
            if ($Revert -or $full.TrimEnd('\', '/') -ne $gameDir.TrimEnd('\', '/')) { $next += $entry }
        }
    }
    if (-not $found) { throw "No original or managed game JAR in launcher classpath: $jsonPath" }
    if (-not $Revert) { $next = @($loose) + $next + @('apocbr-lib/postgresql-42.7.14.jar', 'apocbr-lib/HikariCP-7.1.0.jar') }
    if (($next | ConvertTo-Json -Compress) -cne ($cp | ConvertTo-Json -Compress)) {
        $config.classpath = @($next)
        $jsonUpdates += [pscustomobject]@{ Path = $jsonPath; Bytes = $original; Text = ($config | ConvertTo-Json -Depth 100) + "`n" }
    }
}
$launcher = Join-Path $PZDir 'ProjectZomboidServer.bat'
$names = @('postgresql-42.7.14.jar', 'HikariCP-7.1.0.jar')
$begin = 'REM BEGIN APOCBR POSTGRES CLASSPATH'
$end = 'REM END APOCBR POSTGRES CLASSPATH'
$hasBatch = Test-Path -LiteralPath $launcher -PathType Leaf
$hasJson = (Test-Path -LiteralPath (Join-Path $PZDir 'ProjectZomboid64.json')) -or (Test-Path -LiteralPath (Join-Path $PZDir 'ProjectZomboid32.json'))
if (-not $hasBatch -and -not $hasJson) { throw 'No supported batch or native JSON launcher found.' }
if ($hasBatch) {
    # Preserve the launcher's encoding and line endings, including legacy ANSI files.
    $bytes = [IO.File]::ReadAllBytes($launcher)
    $encoding = [Text.Encoding]::Default
    $offset = 0
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191) {
        $encoding = New-Object Text.UTF8Encoding($true)
        $offset = 3
    }
    $text = $encoding.GetString($bytes, $offset, $bytes.Length - $offset)
    $newline = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $pattern = '(?ms)^' + [regex]::Escape($begin) + '\r?\n.*?^' + [regex]::Escape($end) + '(?:\r?\n|$)'
    $updated = [regex]::Replace($text, $pattern, '')
    if (-not $Revert) {
        # Insert after vanilla classpath assignments and directly before the server JVM call.
        $launchPattern = '(?mi)^.*-cp\s+"?%PZ_CLASSPATH%"?.*zombie\.network\.GameServer[^\r\n]*'
        $match = [regex]::Match($updated, $launchPattern)
        if (-not $match.Success) { throw 'Unsupported server launcher: expected -cp %PZ_CLASSPATH% and zombie.network.GameServer.' }
        $absoluteRuntime = [IO.Path]::GetFullPath($runtimeDir)
        if ($absoluteRuntime.Contains('%') -or $absoluteRuntime.Contains('"') -or $absoluteRuntime.Contains("`n")) {
            throw 'Installation path cannot be represented safely in a batch classpath.'
        }
        $entries = ($names | ForEach-Object { Join-Path $absoluteRuntime $_ }) -join ';'
        # Change only the managed block; vanilla classpath assignments stay intact.
        # Runtime substitution is evaluated by cmd after the vanilla assignments.
        $jarPattern = '(?i)(?<![\w.-])projectzomboid\.jar(?![\w.-])'
        if (-not [regex]::IsMatch($updated, $jarPattern)) { throw 'Batch launcher has no original projectzomboid.jar reference.' }
        $block = $begin + $newline +
            'set "PZ_CLASSPATH=%PZ_CLASSPATH:projectzomboid.jar=projectzomboid-apocbr.jar%"' + $newline +
            'set "PZ_CLASSPATH=' + $loose + ';%PZ_CLASSPATH%;' + $entries + '"' + $newline + $end + $newline
        $launchLine = [regex]::Replace($match.Value, '-cp\s+"?%PZ_CLASSPATH%"?', '-cp "%PZ_CLASSPATH%"')
        $updated = $updated.Substring(0, $match.Index) + $block + $launchLine + $updated.Substring($match.Index + $match.Length)
    }
}
if (-not $Revert) {
    foreach ($name in $names) {
        if (-not (Test-Path -LiteralPath (Join-Path $versionRoot "lib\$name") -PathType Leaf)) { throw "Missing bundled dependency: $name" }
    }
    # Also validates the original archive in dry-run mode.
    & (Join-Path $PSScriptRoot 'Set-UnsignedGameJar.ps1') -GameJar $gameJar -DryRun:$DryRun
}
if ($DryRun) {
    if ($hasBatch) { Write-Host "[DRY RUN] Would update server runtime classpath: $launcher" }
    foreach ($update in $jsonUpdates) { Write-Host "[DRY RUN] Would update native runtime classpath: $($update.Path)" }
    if (-not $Revert) { Write-Host "[DRY RUN] Would copy PostgreSQL and HikariCP JARs into $runtimeDir" }
    exit 0
}
# Install dependencies before redirecting any launcher to the prepared runtime.
if (-not $Revert) {
    New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
    foreach ($name in $names) {
        $source = Join-Path $versionRoot "lib\$name"
        $target = Join-Path $runtimeDir $name
        if (-not (Test-Path -LiteralPath $target -PathType Leaf) -or
            (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash) {
            Copy-Item -LiteralPath $source -Destination $target -Force
        }
    }
}
foreach ($update in $jsonUpdates) {
    $backup = $update.Path + '.apocbr-runtime.bak'
    if (-not (Test-Path -LiteralPath $backup)) { [IO.File]::WriteAllBytes($backup, $update.Bytes) }
    Write-RuntimeFile -Path $update.Path -Text $update.Text -Encoding (New-Object Text.UTF8Encoding($false))
}
if ($hasBatch -and $updated -ne $text) {
    $backup = $launcher + '.apocbr-runtime.bak'
    if (-not (Test-Path -LiteralPath $backup)) { Copy-Item -LiteralPath $launcher -Destination $backup }
    Write-RuntimeFile -Path $launcher -Text $updated -Encoding $encoding
}
if ($Revert) {
    foreach ($name in $names) {
        $target = Join-Path $runtimeDir $name
        if (Test-Path -LiteralPath $target -PathType Leaf) { Remove-Item -LiteralPath $target -Force }
    }
}
Write-Host "Server runtime configured; original game JAR untouched: $PZDir"
