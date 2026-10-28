param(
    [Parameter(Mandatory=$true)][string]$PZDir,
    [switch]$DryRun,
    [switch]$Revert
)
$ErrorActionPreference = 'Stop'
$versionRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $PZDir 'apocbr-lib'
$launcher = Join-Path $PZDir 'ProjectZomboidServer.bat'
$names = @('postgresql-42.7.14.jar', 'HikariCP-7.1.0.jar')
$begin = 'REM BEGIN APOCBR POSTGRES CLASSPATH'
$end = 'REM END APOCBR POSTGRES CLASSPATH'
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw "Server launcher not found: $launcher"
}
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
    foreach ($name in $names) {
        if (-not (Test-Path -LiteralPath (Join-Path $versionRoot "lib\$name") -PathType Leaf)) {
            throw "Missing bundled dependency: $name"
        }
    }
    # Insert after vanilla classpath assignments and directly before the server JVM call.
    $launchPattern = '(?mi)^.*-cp\s+"?%PZ_CLASSPATH%"?.*zombie\.network\.GameServer[^\r\n]*'
    $match = [regex]::Match($updated, $launchPattern)
    if (-not $match.Success) { throw 'Unsupported server launcher: expected -cp %PZ_CLASSPATH% and zombie.network.GameServer.' }
    $absoluteRuntime = [IO.Path]::GetFullPath($runtimeDir)
    if ($absoluteRuntime.Contains('%') -or $absoluteRuntime.Contains('"') -or $absoluteRuntime.Contains("`n")) {
        throw 'Installation path cannot be represented safely in a batch classpath.'
    }
    $entries = ($names | ForEach-Object { Join-Path $absoluteRuntime $_ }) -join ';'
    $block = $begin + $newline + 'set "PZ_CLASSPATH=%PZ_CLASSPATH%;' + $entries + '"' + $newline + $end + $newline
    $launchLine = [regex]::Replace($match.Value, '-cp\s+"?%PZ_CLASSPATH%"?', '-cp "%PZ_CLASSPATH%"')
    $updated = $updated.Substring(0, $match.Index) + $block + $launchLine + $updated.Substring($match.Index + $match.Length)
}
if ($DryRun) {
    Write-Host "[DRY RUN] Would update server runtime classpath: $launcher"
    if (-not $Revert) { Write-Host "[DRY RUN] Would copy PostgreSQL and HikariCP JARs into $runtimeDir" }
    exit 0
}
if ($updated -ne $text) {
    $backup = $launcher + '.apocbr-runtime.bak'
    if (-not (Test-Path -LiteralPath $backup)) { Copy-Item -LiteralPath $launcher -Destination $backup }
    [IO.File]::WriteAllText($launcher, $updated, $encoding)
}
if ($Revert) {
    foreach ($name in $names) {
        $target = Join-Path $runtimeDir $name
        if (Test-Path -LiteralPath $target -PathType Leaf) { Remove-Item -LiteralPath $target -Force }
    }
} else {
    New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
    foreach ($name in $names) {
        Copy-Item -LiteralPath (Join-Path $versionRoot "lib\$name") -Destination (Join-Path $runtimeDir $name) -Force
    }
}
Write-Host "Server runtime dependencies configured: $launcher"
