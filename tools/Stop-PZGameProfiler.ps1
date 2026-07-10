[CmdletBinding()]
param(
    [string]$CacheDir = "$env:USERPROFILE\Zomboid",
    [switch]$Discard
)

$messagingDir = Join-Path $CacheDir 'messaging'
$triggerPath = Join-Path $messagingDir 'Trigger_PerformanceProfiler.xml'
$discardText = if ($Discard) { 'true' } else { 'false' }

New-Item -ItemType Directory -Force -Path $messagingDir | Out-Null

$xml = @"
<?xml version="1.0" encoding="UTF-8"?>
<triggerGameProfilerFile>
  <isRecording>false</isRecording>
  <discard>$discardText</discard>
</triggerGameProfilerFile>
"@

Set-Content -LiteralPath $triggerPath -Value $xml -Encoding UTF8

Write-Host "Requested Project Zomboid GameProfiler stop."
Write-Host "Trigger: $triggerPath"
if ($Discard) {
    Write-Host "Discard was requested. Existing profiler files may still remain if already flushed."
} else {
    Write-Host "Recordings should be under: $(Join-Path $CacheDir 'Recording')"
}

