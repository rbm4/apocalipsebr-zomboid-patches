param(
    [Parameter(Mandatory=$true)][string]$PZDir,
    [switch]$DryRun,
    [switch]$Revert
)
$ErrorActionPreference = 'Stop'
$PZDir = [IO.Path]::GetFullPath($PZDir).TrimEnd('\', '/')
$gameJar = @('projectzomboid.jar', 'java/projectzomboid.jar') | ForEach-Object { Join-Path $PZDir $_ } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (-not $gameJar) { throw 'Original game JAR not found.' }
$gameDir = Split-Path -Parent $gameJar
$runtimeName = 'projectzomboid-apocbr.jar'
$loose = if ($gameDir -eq $PZDir) { '.' } else { 'java/.' }
$updates = @()
foreach ($name in @('ProjectZomboid64.json', 'ProjectZomboid32.json')) {
    $path = Join-Path $PZDir $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $config = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if ($config.classpath -isnot [Array] -or @($config.classpath | Where-Object { $_ -isnot [string] }).Count) {
        throw "Invalid launcher classpath: $path"
    }
    $next = @(); $found = $false
    foreach ($entry in $config.classpath) {
        $normalized = $entry.Replace('\', '/')
        $full = if ([IO.Path]::IsPathRooted($normalized)) { [IO.Path]::GetFullPath($normalized) }
            else { [IO.Path]::GetFullPath((Join-Path $PZDir $normalized)) }
        $base = [IO.Path]::GetFileName($full)
        if ($base -in @('projectzomboid.jar', $runtimeName)) {
            if ((Split-Path -Parent $full) -ne $gameDir) { throw "Launcher references another game JAR directory: $path" }
            if (-not $found) {
                $jarName = if ($Revert) { [IO.Path]::GetFileName($gameJar) } else { $runtimeName }
                $next += $normalized.Substring(0, $normalized.Length - $base.Length) + $jarName
            }
            $found = $true
        } elseif ($full.TrimEnd('\', '/') -ne $gameDir.TrimEnd('\', '/')) { $next += $entry }
    }
    if (-not $found) { throw "No game JAR reference in $path" }
    $config.classpath = @($loose) + $next
    $updates += @{ Path = $path; Text = ($config | ConvertTo-Json -Depth 100) + "`n" }
}
if ($updates.Count -eq 0) { throw 'No native client launcher JSON found.' }
# Validate all launchers before preparing or publishing anything.
if (-not $Revert) { & (Join-Path $PSScriptRoot 'Set-UnsignedGameJar.ps1') -GameJar $gameJar -DryRun:$DryRun }
foreach ($update in $updates) {
    if ($DryRun) { Write-Host "[DRY RUN] Would configure $($update.Path) (revert=$Revert)"; continue }
    if ([IO.File]::ReadAllText($update.Path) -eq $update.Text) { continue }
    $backup = $update.Path + '.apocbr-client.bak'
    if (-not (Test-Path -LiteralPath $backup)) { Copy-Item -LiteralPath $update.Path -Destination $backup }
    $temp = $update.Path + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllText($temp, $update.Text, (New-Object Text.UTF8Encoding($false)))
        [IO.File]::Replace($temp, $update.Path, [NullString]::Value)
    } finally { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force } }
    Write-Host "Configured client launcher: $($update.Path)"
}
