param(
    [Parameter(Mandatory=$true)][string]$GameJar,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$GameJar = [IO.Path]::GetFullPath($GameJar)
$target = Join-Path (Split-Path -Parent $GameJar) 'projectzomboid-apocbr.jar'
$receipt = $target + '.source.json'
foreach ($path in @($target, $receipt)) {
    if ((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Managed runtime JAR/receipt must not be symbolic links.'
    }
}
# Only read the original. Every mutation below uses a distinct temporary file.
$source = [IO.Compression.ZipFile]::OpenRead($GameJar)
try {
    if ($null -eq $source.GetEntry('META-INF/MANIFEST.MF')) { throw 'Original JAR manifest is missing.' }
} finally { $source.Dispose() }
if ($DryRun) {
    Write-Host "[DRY RUN] Would prepare unsigned $target; original remains untouched"
    return
}
$sourceHash = (Get-FileHash -LiteralPath $GameJar -Algorithm SHA256).Hash.ToLowerInvariant()
if ((Test-Path -LiteralPath $target -PathType Leaf) -and (Test-Path -LiteralPath $receipt -PathType Leaf)) {
    try {
        $cached = Get-Content -LiteralPath $receipt -Raw | ConvertFrom-Json
        $runtimeHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($cached.source_sha256 -eq $sourceHash -and $cached.runtime_sha256 -eq $runtimeHash) {
            Write-Host "Unsigned runtime JAR already current: $target"
            return
        }
    } catch { Write-Verbose 'Invalid runtime receipt; rebuilding from the original.' }
}
$temp = $target + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
try {
    Copy-Item -LiteralPath $GameJar -Destination $temp
    # Copy-Item can inherit a read-only attribute from the source.
    [IO.File]::SetAttributes($temp, [IO.FileAttributes]::Normal)
    $zip = [IO.Compression.ZipFile]::Open($temp, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $signatures = @($zip.Entries | Where-Object { $_.FullName -match '(?i)^META-INF/(?:[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)$' })
        foreach ($entry in $signatures) { $entry.Delete() }
    } finally { $zip.Dispose() }
    # Read every retained entry before publishing the finished archive.
    $zip = [IO.Compression.ZipFile]::OpenRead($temp)
    try {
        foreach ($entry in $zip.Entries) {
            $stream = $entry.Open()
            try { $stream.CopyTo([IO.Stream]::Null) } finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
    if ((Get-FileHash -LiteralPath $GameJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sourceHash) {
        throw 'Original JAR changed during preparation; retry after the game update finishes.'
    }
    $runtimeHash = (Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash.ToLowerInvariant()
    if (Test-Path -LiteralPath $target) {
        # Replace the directory entry, rather than modifying a possible hard link.
        [IO.File]::Replace($temp, $target, [NullString]::Value)
    } else { [IO.File]::Move($temp, $target) }
    $data = [ordered]@{ source_sha256 = $sourceHash; runtime_sha256 = $runtimeHash }
    $receiptTemp = $receipt + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllText($receiptTemp, ($data | ConvertTo-Json) + "`n", (New-Object Text.UTF8Encoding($false)))
        if (Test-Path -LiteralPath $receipt) {
            [IO.File]::Replace($receiptTemp, $receipt, [NullString]::Value)
        } else { [IO.File]::Move($receiptTemp, $receipt) }
    } finally {
        if (Test-Path -LiteralPath $receiptTemp) { Remove-Item -LiteralPath $receiptTemp -Force }
    }
} finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
}
Write-Host "Prepared unsigned runtime JAR: $target"
