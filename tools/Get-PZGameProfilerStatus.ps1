[CmdletBinding()]
param(
    [string]$CacheDir = "$env:USERPROFILE\Zomboid",
    [int]$Count = 30
)

$messagingDir = Join-Path $CacheDir 'messaging'
$triggerPath = Join-Path $messagingDir 'Trigger_PerformanceProfiler.xml'
$recordingDir = Join-Path $CacheDir 'Recording'

Write-Host "CacheDir:     $CacheDir"
Write-Host "Trigger file: $triggerPath"
Write-Host "RecordingDir: $recordingDir"
Write-Host ""

if (Test-Path -LiteralPath $triggerPath) {
    Write-Host "Current trigger XML:"
    Get-Content -LiteralPath $triggerPath
} else {
    Write-Host "Current trigger XML: <missing>"
}

Write-Host ""
Write-Host "Recent GameProfiler files:"
if (Test-Path -LiteralPath $recordingDir) {
    Get-ChildItem -LiteralPath $recordingDir -Filter '*GameProfiler*' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First $Count FullName, Length, LastWriteTime |
        Format-Table -AutoSize
} else {
    Write-Host "Recording directory does not exist yet."
}

