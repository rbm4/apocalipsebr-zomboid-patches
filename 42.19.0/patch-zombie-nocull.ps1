<#
.SYNOPSIS
    Removes the server-side zombie cull added in Build 42.19 by NOPing the
    ZombieCountOptimiser.deleteZombies() call in MovingObjectUpdateScheduler.postupdate().

.DESCRIPTION
    Zombie NoCull Patch - MovingObjectUpdateScheduler.postupdate() (Build 42.19)

    Change in 42.19: MovingObjectUpdateScheduler.postupdate() started calling
    ZombieCountOptimiser.deleteZombies() on every frame on the dedicated server,
    reducing zombie populations from ~5000 down to ~400 on servers with many
    connected players.

    In 42.18 this cull did not exist server-side - the code was client-only.

    The patch replaces the 9 bytes of the cull call with NOPs:
      B2 00 2F   getstatic  GameServer.server
      99 00 06   ifeq 9
      B8 00 D7   invokestatic ZombieCountOptimiser.deleteZombies()
    ->
      00 00 00 00 00 00 00 00 00   (9 x nop)

    Result: postupdate() behaves identically to 42.18.
    Binary in-place patch (same class size). Idempotent.

.PARAMETER JarPath
    Path to projectzomboid.jar.
    Default: java\projectzomboid.jar (relative to CWD, i.e. the PZ server root).

.PARAMETER ToolsDir
    Directory for backups. Default: script directory.

.PARAMETER DryRun
    Show what would be done without modifying the jar.

.PARAMETER Revert
    Restore projectzomboid.jar from the most recent backup.

.EXAMPLE
    .\patch-zombie-nocull.ps1
    .\patch-zombie-nocull.ps1 -JarPath "Z:\SteamLibrary\steamapps\common\ProjectZomboid\java\projectzomboid.jar"
    .\patch-zombie-nocull.ps1 -Revert
#>
[CmdletBinding()]
param(
    [string]$JarPath  = "java\projectzomboid.jar",
    [string]$ToolsDir = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# --- Configuration ---
$PatchName = "Zombie NoCull - MovingObjectUpdateScheduler.postupdate()"
$JarEntry  = 'zombie/MovingObjectUpdateScheduler.class'
$BackupDir = Join-Path $ToolsDir "backups\MovingObjectUpdateScheduler"

Write-Host ""
Write-Host "=== $PatchName ===" -ForegroundColor White
Write-Host ""

# --- Revert ---
if ($Revert) {
    $backups = Get-ChildItem -Path $BackupDir -Filter "*.bak.*" -ErrorAction SilentlyContinue |
               Sort-Object Name -Descending
    if (-not $backups) {
        Write-Host "[!] No backup found in: $BackupDir" -ForegroundColor Yellow
        exit 0
    }
    $latest = $backups[0]
    Write-Host "[*] Restoring from: $($latest.FullName)" -ForegroundColor Cyan
    if (-not $DryRun) {
        Copy-Item -LiteralPath $latest.FullName -Destination $JarPath -Force
        Write-Host "    Restored: $JarPath" -ForegroundColor Green
    } else {
        Write-Host "    [DryRun] Would restore: $JarPath" -ForegroundColor Gray
    }
    Write-Host ""
    Write-Host "=== Patch reverted ===" -ForegroundColor White
    exit 0
}

# --- Validate ---
if (-not (Test-Path -LiteralPath $JarPath)) {
    Write-Host "ERROR: Jar not found: $JarPath" -ForegroundColor Red
    Write-Host "       Run from the PZ server root (where the java\ folder exists), or pass -JarPath." -ForegroundColor Yellow
    exit 1
}
$JarPath = (Resolve-Path -LiteralPath $JarPath).Path
Write-Host "[*] Target: $JarPath" -ForegroundColor Cyan

# --- Backup ---
$stamp  = $null
$backup = $null
if (-not $DryRun) {
    if (-not (Test-Path $BackupDir)) { New-Item -Path $BackupDir -ItemType Directory -Force | Out-Null }
    $stamp  = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backup = Join-Path $BackupDir "projectzomboid.jar.bak.$stamp"
    Copy-Item -LiteralPath $JarPath -Destination $backup -Force
    Write-Host "[*] Backup: $backup" -ForegroundColor Cyan
} else {
    Write-Host "[*] DryRun - jar will not be modified." -ForegroundColor Gray
}

Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

# --- Functions ---
function Find-BytePattern {
    param(
        [Parameter(Mandatory)][byte[]]$Haystack,
        [Parameter(Mandatory)][string]$HexPattern
    )
    $tokens = @($HexPattern -split '\s+' | Where-Object { $_ })
    $len = $tokens.Length
    $bytePat = New-Object 'int[]' $len
    for ($k = 0; $k -lt $len; $k++) {
        if ($tokens[$k] -eq '??') { $bytePat[$k] = -1 }
        else { $bytePat[$k] = [Convert]::ToInt32($tokens[$k], 16) }
    }
    $end = $Haystack.Length - $len
    for ($i = 0; $i -le $end; $i++) {
        $ok = $true
        for ($j = 0; $j -lt $len; $j++) {
            if ($bytePat[$j] -ne -1 -and $Haystack[$i + $j] -ne $bytePat[$j]) { $ok = $false; break }
        }
        if ($ok) { return $i }
    }
    return -1
}

function Update-JarEntry {
    param(
        [Parameter(Mandatory)][string]$Jar,
        [Parameter(Mandatory)][string]$Entry,
        [Parameter(Mandatory)][scriptblock]$Mutator
    )
    $zip = [System.IO.Compression.ZipFile]::Open($Jar, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $e = $zip.GetEntry($Entry)
        if ($null -eq $e) { throw "Entry not found in jar: $Entry" }

        $bytes = $null
        $is = $e.Open()
        try {
            $ms = New-Object System.IO.MemoryStream
            try { $is.CopyTo($ms); $bytes = $ms.ToArray() }
            finally { $ms.Dispose() }
        } finally { $is.Dispose() }

        $result  = & $Mutator $bytes
        $patched = [byte[]]$result
        if ($null -eq $patched) { throw "Mutator returned null for $Entry" }
        if ($patched.Length -ne $bytes.Length) {
            throw "Mutator changed class size ($($bytes.Length) -> $($patched.Length)) - invalid for same-size patch."
        }

        $e.Delete()
        $ne = $zip.CreateEntry($Entry, [System.IO.Compression.CompressionLevel]::Optimal)
        $os = $ne.Open()
        try { $os.Write($patched, 0, $patched.Length) }
        finally { $os.Dispose() }

        Write-Host "    Size: $($bytes.Length) bytes (unchanged)" -ForegroundColor Gray
    } finally {
        $zip.Dispose()
    }
}

# Patch: MovingObjectUpdateScheduler.postupdate()
#
# 42.19 added at the start of the method (server-side only):
#   B2 00 2F   getstatic  GameServer.server : Z
#   99 00 06   ifeq 9  (skip if not server)
#   B8 00 D7   invokestatic ZombieCountOptimiser.deleteZombies()
#
# Search pattern (9 bytes): B2 00 2F 99 00 06 B8 00 D7
# Patch: replace with 9 NOPs (00 x 9)
#
# Not applicable to builds prior to 42.19 (pattern does not exist).
#
$patchNoCull = {
    param([byte[]]$b)

    $searchHex = 'B2 00 2F 99 00 06 B8 00 D7'

    $idx = Find-BytePattern -Haystack $b -HexPattern $searchHex
    if ($idx -lt 0) {
        Write-Warning "    Pattern not found - build may differ from 42.19 or patch is already applied."
        return ,$b
    }

    for ($i = 0; $i -lt 9; $i++) {
        $b[$idx + $i] = 0x00   # nop
    }

    Write-Host ("    Patch applied at offset {0}: deleteZombies() -> 9x nop." -f $idx) -ForegroundColor Green
    return ,$b
}

# --- Main ---
Write-Host "[1/1] Patching: $JarEntry ..." -ForegroundColor Cyan
if (-not $DryRun) {
    Update-JarEntry -Jar $JarPath -Entry $JarEntry -Mutator $patchNoCull
    Write-Host ""
    Write-Host "[+] Done. Restart the server (StartServer64.bat) to apply." -ForegroundColor Green
    Write-Host "    Backup: $backup" -ForegroundColor Gray
} else {
    Write-Host "    [DryRun] Skipping write." -ForegroundColor Gray
    Write-Host ""
    Write-Host "[+] DryRun complete." -ForegroundColor Green
}
Write-Host ""
