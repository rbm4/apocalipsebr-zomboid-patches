<# Compile and deploy client loose classes and a separate unsigned runtime JAR; never modify the original JAR. #>
param(
    [string]$PZDir = 'Z:\SteamLibrary\steamapps\common\ProjectZomboid',
    [switch]$DryRun,
    [switch]$Revert,
    [switch]$BuildOnly,
    [string]$ArtifactDir
)
$ErrorActionPreference = 'Stop'
if ($BuildOnly -and ($DryRun -or $Revert)) { throw 'BuildOnly cannot be combined with DryRun or Revert.' }
if ($ArtifactDir -and -not $BuildOnly) { throw 'ArtifactDir requires BuildOnly.' }
$runtime = Join-Path $PSScriptRoot 'tools/Set-ClientRuntime.ps1'
$original = @('projectzomboid.jar', 'java/projectzomboid.jar') | ForEach-Object { Join-Path $PZDir $_ } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (-not $original) { throw 'Original game JAR not found.' }
$deployRoot = [IO.Path]::GetFullPath((Split-Path -Parent $original))
$statePath = Join-Path $deployRoot 'apocbr-client-telemetry.install.json'
$backupRoot = Join-Path $deployRoot 'apocbr-client-telemetry-backup'
function Resolve-ClassPath([string]$Root, [string]$Relative) {
    if ($Relative -notmatch '^zombie[\\/].+\.class$') { throw "Invalid class path: $Relative" }
    $full = [IO.Path]::GetFullPath((Join-Path $Root $Relative))
    $prefix = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Class path escapes root.' }
    return $full
}
if ($Revert) {
    & $runtime -PZDir $PZDir -Revert -DryRun:$DryRun
    if (Test-Path -LiteralPath $statePath) {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        foreach ($entry in $state.classes) {
            $target = Resolve-ClassPath $deployRoot $entry.path
            $backup = Resolve-ClassPath $backupRoot $entry.path
            if ($DryRun) { Write-Host "[DRY RUN] Would restore/remove $target"; continue }
            if ($entry.existed) { Copy-Item -LiteralPath $backup -Destination $target -Force }
            elseif (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force }
        }
        if (-not $DryRun) { Remove-Item -LiteralPath $statePath -Force }
    }
    Write-Host 'Client revert complete. Derived runtime JAR retained, original JAR untouched.'
    return
}
if ($BuildOnly) {
    $arguments = @{ PZDir = $PZDir }
    if ($ArtifactDir) { $arguments.ArtifactDir = $ArtifactDir }
    & (Join-Path $PSScriptRoot 'Build-ClientTelemetry.ps1') @arguments
    return
}
# Validate descriptors before compilation and again when publishing.
& $runtime -PZDir $PZDir -DryRun
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$work = Join-Path $tempRoot ('apocbr-client-' + [Guid]::NewGuid().ToString('N'))
try {
    & (Join-Path $PSScriptRoot 'Build-ClientTelemetry.ps1') -PZDir $PZDir -ArtifactDir $work
    $classesRoot = Join-Path $work 'classes'
    $compiled = @(Get-ChildItem -LiteralPath $classesRoot -Recurse -Filter '*.class' -File)
    if (-not $compiled.Count) { throw 'No compiled classes.' }
    if ($DryRun) {
        Write-Host "[DRY RUN] Would deploy $($compiled.Count) loose classes into $deployRoot\zombie. No game files changed."
        return
    }
    $entries = @()
    if (Test-Path -LiteralPath $statePath) { $entries = @((Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json).classes) }
    # Back up original loose overrides once, before overwriting them on any deployment.
    foreach ($class in $compiled) {
        $relative = $class.FullName.Substring($classesRoot.Length).TrimStart('\', '/')
        $target = Resolve-ClassPath $deployRoot $relative
        if (-not @($entries | Where-Object { $_.path -eq $relative }).Count) {
            $existed = Test-Path -LiteralPath $target
            if ($existed) {
                $backup = Resolve-ClassPath $backupRoot $relative
                New-Item -ItemType Directory -Path (Split-Path -Parent $backup) -Force | Out-Null
                Copy-Item -LiteralPath $target -Destination $backup -Force
            }
            $entries += @{ path = $relative; existed = [bool]$existed }
        }
    }
    # Save recovery metadata before publishing classes; prior launcher backups are retained.
    @{ target = '42.22.2-client'; classes = $entries } | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath $statePath -Encoding UTF8
    & $runtime -PZDir $PZDir
    foreach ($class in $compiled) {
        $relative = $class.FullName.Substring($classesRoot.Length).TrimStart('\', '/')
        $target = Resolve-ClassPath $deployRoot $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        Copy-Item -LiteralPath $class.FullName -Destination $target -Force
    }
    Write-Host "Deployed $($compiled.Count) client classes into $deployRoot\zombie"
    Write-Host 'Client JSON now uses projectzomboid-apocbr.jar with loose classes first. Original JAR untouched.'
    Write-Host 'Enable capture with JVM argument -Dapocbr.clientTelemetry.enabled=true and restart the client.'
} finally {
    $resolved = [IO.Path]::GetFullPath($work)
    if (-not $resolved.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing cleanup outside temporary directory.'
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
