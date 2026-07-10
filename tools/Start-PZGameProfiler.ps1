[CmdletBinding()]
param(
    [string]$CacheDir = "$env:USERPROFILE\Zomboid",
    [int]$WarmupSeconds = 3
)

$messagingDir = Join-Path $CacheDir 'messaging'
$triggerPath = Join-Path $messagingDir 'Trigger_PerformanceProfiler.xml'

New-Item -ItemType Directory -Force -Path $messagingDir | Out-Null

$stopXml = @"
<?xml version="1.0" encoding="UTF-8"?>
<triggerGameProfilerFile>
  <isRecording>false</isRecording>
  <discard>false</discard>
</triggerGameProfilerFile>
"@

$startXml = @"
<?xml version="1.0" encoding="UTF-8"?>
<triggerGameProfilerFile>
  <isRecording>true</isRecording>
  <discard>false</discard>
</triggerGameProfilerFile>
"@

# The game watches file create/modify events and debounces them for roughly two seconds.
# Pulse false first so an already-existing trigger file still causes a fresh change.
Set-Content -LiteralPath $triggerPath -Value $stopXml -Encoding UTF8
Start-Sleep -Seconds ([Math]::Max(3, $WarmupSeconds))
Set-Content -LiteralPath $triggerPath -Value $startXml -Encoding UTF8

Write-Host "Requested Project Zomboid GameProfiler start."
Write-Host "Trigger: $triggerPath"
Write-Host "Wait ~3 seconds, reproduce the stutter, then run Stop-PZGameProfiler.ps1."

