<#
.SYNOPSIS
    Patches CompressIdenticalItems.class to guard against a null item during
    DryingCraftLogic saves, preventing save corruption on the dedicated server.

.DESCRIPTION
    NullCraft Fix - CompressIdenticalItems.save() Null Guard (Build 42.19)

    Bug: when a drying/curing craft (DryingCraftLogic) is in progress and the
    referenced item becomes null (e.g. item despawned, player disconnected),
    the server throws NPE in CompressIdenticalItems.save() during chunk
    serialization. This corrupts the chunk save, causing vehicles to vanish
    from vehicles.db on the next server boot.

    The patch inserts a null guard ("if (item == null) return;") at the top of
    CompressIdenticalItems.save(ByteBuffer, InventoryItem), preventing the NPE
    and allowing the save to proceed normally for all other data.

    Binary in-place patch on the jar (class size increases by 13 bytes).
    Idempotent: detects already-patched state.

    Original error in DebugLog-server.txt:
      NullPointerException: Cannot invoke "InventoryItem.saveWithSize" because
      "item" is null at CompressIdenticalItems.save(CompressIdenticalItems.java:343)

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
    .\patch-nullcraft.ps1
    .\patch-nullcraft.ps1 -JarPath "Z:\SteamLibrary\steamapps\common\ProjectZomboid\java\projectzomboid.jar"
    .\patch-nullcraft.ps1 -Revert
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
$PatchName = "NullCraft Fix - CompressIdenticalItems.save() Null Guard"
$JarEntry  = 'zombie/inventory/CompressIdenticalItems.class'
$BackupDir = Join-Path $ToolsDir "backups\CompressIdenticalItems"

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

# Variante de Update-JarEntry que permite tamanhos diferentes
function Update-JarEntryVariable {
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

        $e.Delete()
        $ne = $zip.CreateEntry($Entry, [System.IO.Compression.CompressionLevel]::Optimal)
        $os = $ne.Open()
        try { $os.Write($patched, 0, $patched.Length) }
        finally { $os.Dispose() }

        Write-Host ("    Size: {0} bytes -> {1} bytes (+{2})" -f $bytes.Length, $patched.Length, ($patched.Length - $bytes.Length)) -ForegroundColor Gray
    } finally {
        $zip.Dispose()
    }
}

# Patch: CompressIdenticalItems.save(ByteBuffer, InventoryItem)
#
# Search pattern (31 bytes) - Code attribute of the method:
#   00 00 00 53   attr_length = 83
#   00 03 00 02   max_stack=3 max_locals=2
#   00 00 00 13   code_length = 19
#   2A 04 B6 00 AB 57   aload_0; iconst_1; putShort; pop
#   2A 04 B6 00 36 57   aload_0; iconst_1; putInt; pop
#   2B 2A 03 B6 00 BC   aload_1; aload_0; iconst_0; invokevirtual saveWithSize
#   B1                  return
#
# Replacement (44 bytes):
#   00 00 00 60   attr_length = 96 (+13)
#   00 03 00 02   max_stack=3 max_locals=2 (unchanged)
#   00 00 00 17   code_length = 23 (+4)
#   2B C6 00 15   aload_1; ifnull 22  <- NEW NULL CHECK
#   2A 04 B6 00 AB 57   aload_0; iconst_1; putShort; pop
#   2A 04 B6 00 36 57   aload_0; iconst_1; putInt; pop
#   2B 2A 03 B6 00 BC   aload_1; aload_0; iconst_0; invokevirtual saveWithSize
#   B1                  return
#   00 00 00 03   exception_table_length = 0 + attrs_count = 3 (+1 new sub-attr)
#   00 E3 00 00 00 03 00 01 16   StackMapTable: cp=227, len=3, count=1, same_frame(22)
#
$patchNullCraft = {
    param([byte[]]$b)

    # Original pattern
    $searchHex  = '00 00 00 53 00 03 00 02 00 00 00 13 2A 04 B6 00 AB 57 2A 04 B6 00 36 57 2B 2A 03 B6 00 BC B1'
    # Already-patched pattern
    $patchedHex = '00 00 00 60 00 03 00 02 00 00 00 17 2B C6 00 15 2A 04 B6 00 AB 57 2A 04 B6 00 36 57 2B 2A 03 B6 00 BC B1'

    $idxAlready = Find-BytePattern -Haystack $b -HexPattern $patchedHex
    if ($idxAlready -ge 0) {
        Write-Warning "    Patch already applied at offset $idxAlready."
        return ,$b
    }

    $idx = Find-BytePattern -Haystack $b -HexPattern $searchHex
    if ($idx -lt 0) {
        throw "    Pattern not found - class may have changed in this PZ version."
    }
    Write-Host ("    Pattern found at offset {0}" -f $idx) -ForegroundColor Gray

    # Patched Code attribute header (12 bytes)
    $newHeader = [byte[]]@(
        0x00, 0x00, 0x00, 0x60,   # attr_length = 96
        0x00, 0x03, 0x00, 0x02,   # max_stack=3 max_locals=2
        0x00, 0x00, 0x00, 0x17    # code_length = 23
    )
    # New bytecode (23 bytes): null check + original code
    $newCode = [byte[]]@(
        0x2B,                         # aload_1 (item)
        0xC6, 0x00, 0x15,             # ifnull -> offset 22 (return)
        0x2A, 0x04, 0xB6, 0x00, 0xAB, # aload_0; iconst_1; invokevirtual putShort
        0x57,                         # pop
        0x2A, 0x04, 0xB6, 0x00, 0x36, # aload_0; iconst_1; invokevirtual putInt
        0x57,                         # pop
        0x2B, 0x2A, 0x03,             # aload_1; aload_0; iconst_0
        0xB6, 0x00, 0xBC,             # invokevirtual saveWithSize
        0xB1                          # return
    )
    # The 31-byte search block covers attr_len+header+code but NOT
    # exception_table and sub_attrs - those sit immediately after in $b at $idx+31.
    # First 12 bytes = header, bytes 12-30 = code (19 bytes).
    # After offset $idx+31: exc_table_len(2)=00 00, sub_attrs_count(2)=00 02,
    # then LineNumberTable and LocalVariableTable.
    # We substitute the 31 original bytes and rewrite exc+sub_attrs.

    $origEnd          = $idx + 31
    $subAttrsCountOff = $origEnd + 2   # skip exc_table_len(2) + exc_table(0*8)

    $origSubAttrsCount = [System.BitConverter]::ToUInt16([byte[]]@($b[$subAttrsCountOff+1], $b[$subAttrsCountOff]), 0)
    Write-Host ("    Original sub_attrs_count: {0}" -f $origSubAttrsCount) -ForegroundColor Gray

    # Walk and collect existing sub_attrs (LineNumberTable + LocalVariableTable)
    $sOff = $subAttrsCountOff + 2
    $preservedSubAttrs = New-Object System.Collections.Generic.List[byte]
    for ($sa = 0; $sa -lt $origSubAttrsCount; $sa++) {
        $saLen   = [uint32](([uint32]$b[$sOff+2] -shl 24) -bor ([uint32]$b[$sOff+3] -shl 16) -bor ([uint32]$b[$sOff+4] -shl 8) -bor [uint32]$b[$sOff+5])
        $saTotal = 6 + [int]$saLen
        $preservedSubAttrs.AddRange([byte[]]($b[$sOff..($sOff+$saTotal-1)]))
        $sOff += $saTotal
    }

    # StackMapTable sub-attr (9 bytes):
    # name_idx=0x00E3(227), attr_len=3, count=1, same_frame(offset_delta=22)
    $smtAttr = [byte[]]@(0x00, 0xE3, 0x00, 0x00, 0x00, 0x03, 0x00, 0x01, 0x16)

    # Build replacement block:
    # [newHeader(12)] [newCode(23)] [exc_table_len=0(2)] [sub_attrs_count=3(2)] [StackMapTable(9)] [preserved sub_attrs]
    $replacement = New-Object System.Collections.Generic.List[byte]
    $replacement.AddRange($newHeader)
    $replacement.AddRange($newCode)
    $replacement.AddRange([byte[]]@(0x00, 0x00))  # exc_table_len = 0
    $replacement.AddRange([byte[]]@(0x00, 0x03))  # sub_attrs_count = 3
    $replacement.AddRange($smtAttr)               # StackMapTable
    $replacement.AddRange($preservedSubAttrs)

    # Reassemble class file
    $before = [byte[]]($b[0..($idx-1)])
    $after  = [byte[]]($b[$sOff..($b.Length-1)])

    $result = New-Object System.Collections.Generic.List[byte]
    $result.AddRange($before)
    $result.AddRange($replacement)
    $result.AddRange($after)

    Write-Host ("    Patch applied at offset {0}: code 19 -> 23 bytes, class +13 bytes." -f $idx) -ForegroundColor Green
    return ,$result.ToArray()
}

# --- Main ---
Write-Host "[1/1] Patching: $JarEntry ..." -ForegroundColor Cyan
if (-not $DryRun) {
    Update-JarEntryVariable -Jar $JarPath -Entry $JarEntry -Mutator $patchNullCraft
    Write-Host ""
    Write-Host "[+] Done. Restart the server (StartServer64.bat) to apply." -ForegroundColor Green
    Write-Host "    Backup: $backup" -ForegroundColor Gray
} else {
    Write-Host "    [DryRun] Skipping write." -ForegroundColor Gray
    Write-Host ""
    Write-Host "[+] DryRun complete." -ForegroundColor Green
}
Write-Host ""
