[CmdletBinding()]
param(
    [string]$ProcessName = 'ProjectZomboid64',
    [Alias('Pid')]
    [int]$TargetPid = 0,
    [int]$DurationSec = 120,
    [int]$IntervalMs = 250,
    [int]$TopThreads = 12,
    [int]$StackEverySec = 5,
    [string]$OutputDir = '',
    [string]$ToolsDir = $PSScriptRoot,
    [string]$JcmdPath = '',
    [string]$ProfilerHeaderPath = '',
    [switch]$AnalyzeProfiler,
    [switch]$NoStacks,
    [switch]$NoGpuCounters
)

$ErrorActionPreference = 'Stop'

function New-DirectoryIfNeeded {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -Path $Path -ItemType Directory -Force | Out-Null
    }
}

function Resolve-PZProcess {
    param([int]$TargetPid, [string]$TargetName)
    if ($TargetPid -gt 0) {
        $p = Get-Process -Id $TargetPid -ErrorAction Stop
        return $p
    }

    $candidates = @(Get-Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.ProcessName -like "$TargetName*" -or
            $_.ProcessName -like 'ProjectZomboid*' -or
            $_.ProcessName -eq 'java' -or
            $_.ProcessName -eq 'javaw'
        } |
        Sort-Object -Property StartTime -Descending -ErrorAction SilentlyContinue)

    if ($candidates.Count -eq 0) {
        throw "Could not find a running Project Zomboid process. Pass -Pid if the process name differs."
    }
    if ($candidates.Count -gt 1) {
        Write-Host "Found multiple possible PZ/client processes. Using newest: $($candidates[0].ProcessName) PID $($candidates[0].Id)" -ForegroundColor Yellow
        $candidates | Select-Object -First 8 Id,ProcessName,StartTime,CPU | Format-Table | Out-String | Write-Host
    }
    return $candidates[0]
}

function Find-Jcmd {
    param([string]$ExplicitPath, [string]$RepoToolsDir)
    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath) -and (Test-Path -LiteralPath $ExplicitPath)) {
        return (Resolve-Path -LiteralPath $ExplicitPath).Path
    }

    $local = Join-Path $RepoToolsDir '..\42.19.0-client\jdk\bin\jcmd.exe'
    if (Test-Path -LiteralPath $local) {
        return (Resolve-Path -LiteralPath $local).Path
    }

    $cmd = Get-Command jcmd.exe -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    return ''
}

function Get-ThreadSnapshot {
    param([System.Diagnostics.Process]$Process)
    $map = @{}
    try {
        $fresh = Get-Process -Id $Process.Id -ErrorAction Stop
        foreach ($thread in $fresh.Threads) {
            $map[[int]$thread.Id] = [pscustomobject]@{
                Id = [int]$thread.Id
                TotalMs = [double]$thread.TotalProcessorTime.TotalMilliseconds
                UserMs = [double]$thread.UserProcessorTime.TotalMilliseconds
                State = [string]$thread.ThreadState
                WaitReason = [string]$thread.WaitReason
                Priority = [int]$thread.CurrentPriority
            }
        }
    } catch {
        Write-Warning "Could not read thread snapshot: $_"
    }
    return $map
}

function ConvertTo-CsvValue {
    param($Value)
    if ($null -eq $Value) { return '' }
    $s = [string]$Value
    if ($s.IndexOfAny([char[]]@(',', '"', "`r", "`n")) -ge 0) {
        return '"' + $s.Replace('"', '""') + '"'
    }
    return $s
}

function Add-CsvRow {
    param([string]$Path, [string[]]$Columns, [hashtable]$Row)
    $values = foreach ($col in $Columns) {
        ConvertTo-CsvValue $Row[$col]
    }
    Add-Content -LiteralPath $Path -Value ($values -join ',') -Encoding UTF8
}

function Get-GpuUtilization {
    param([switch]$Disabled)
    if ($Disabled) {
        return [pscustomobject]@{ Total = ''; Busy = ''; Engines = '' }
    }

    try {
        $samples = @((Get-Counter '\GPU Engine(*)\Utilization Percentage' -ErrorAction Stop).CounterSamples)
        $running = @($samples | Where-Object { $_.CookedValue -gt 0.01 })
        $total = ($running | Measure-Object -Property CookedValue -Sum).Sum
        if ($null -eq $total) { $total = 0.0 }
        $top = @($running | Sort-Object CookedValue -Descending | Select-Object -First 5)
        $engines = ($top | ForEach-Object {
            $name = $_.Path
            $value = [math]::Round($_.CookedValue, 2)
            "$name=$value"
        }) -join ' | '
        return [pscustomobject]@{
            Total = [math]::Round([double]$total, 2)
            Busy = $running.Count
            Engines = $engines
        }
    } catch {
        return [pscustomobject]@{ Total = ''; Busy = ''; Engines = "unavailable: $($_.Exception.Message)" }
    }
}

function Get-ProcessPerfCounters {
    param([int]$TargetPid)
    try {
        $row = Get-CimInstance -ClassName Win32_PerfFormattedData_PerfProc_Process -ErrorAction Stop |
            Where-Object { [int]$_.IDProcess -eq $TargetPid } |
            Select-Object -First 1
        if ($row) {
            return [pscustomobject]@{
                IOReadMBps = [math]::Round(([double]$row.IOReadBytesPersec) / 1MB, 3)
                IOWriteMBps = [math]::Round(([double]$row.IOWriteBytesPersec) / 1MB, 3)
                PageFaultsPerSec = [double]$row.PageFaultsPersec
            }
        }
    } catch {
    }

    return [pscustomobject]@{ IOReadMBps = ''; IOWriteMBps = ''; PageFaultsPerSec = '' }
}

function Invoke-JcmdThreadPrint {
    param([string]$Jcmd, [int]$TargetPid, [string]$OutPath)
    if ([string]::IsNullOrWhiteSpace($Jcmd)) {
        return $false
    }

    try {
        & $Jcmd $TargetPid Thread.print -l | Out-File -LiteralPath $OutPath -Encoding UTF8
        return $true
    } catch {
        "jcmd Thread.print failed: $_" | Out-File -LiteralPath $OutPath -Encoding UTF8
        return $false
    }
}

function Write-Summary {
    param(
        [string]$Path,
        [string]$OutputDir,
        [string]$ProcessLabel,
        [int]$SampleCount,
        [int]$CoreCount,
        [double]$MaxCpuPct,
        [double]$AvgCpuPct,
        [double]$MaxFrameProxyMs,
        [object[]]$TopThreadRows,
        [bool]$JcmdUsed,
        [string]$ProfilerOutput
    )

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('# Project Zomboid Client Stutter Sample')
    $lines.Add('')
    $lines.Add("Process: $ProcessLabel")
    $lines.Add("Samples: $SampleCount")
    $lines.Add("Logical cores: $CoreCount")
    $lines.Add("Average process CPU: $([math]::Round($AvgCpuPct, 2))%")
    $lines.Add("Max process CPU: $([math]::Round($MaxCpuPct, 2))%")
    $lines.Add("Largest sample interval: $([math]::Round($MaxFrameProxyMs, 2)) ms")
    $lines.Add("Stacks captured: $JcmdUsed")
    if (-not [string]::IsNullOrWhiteSpace($ProfilerOutput)) {
        $lines.Add("GameProfiler analysis: $ProfilerOutput")
    }
    $lines.Add('')
    $lines.Add('## How To Read This')
    $lines.Add('')
    $lines.Add('- If `ProcessCpuPct` spikes near one full core while GPU utilization is low, the GPU is probably waiting for the game thread.')
    $lines.Add('- If `SampleDeltaMs` jumps well above the requested interval, the process or OS was stalled during the sampler tick.')
    $lines.Add('- Check `thread_samples.csv` at the same timestamp as a stutter, then open the nearest `stacks_*.txt` and match `ThreadIdHex` to Java `nid=0x...`.')
    $lines.Add('- Constant in-game `GPU WAITING FOR CPU` usually means the next optimization target is main-thread update/render work, not shader throughput.')
    $lines.Add('')
    $lines.Add('## Top CPU Threads')
    $lines.Add('')
    $lines.Add('| ThreadId | Hex | MaxCpuPct | AvgCpuPct | Samples | LastState | LastWaitReason |')
    $lines.Add('|---:|---:|---:|---:|---:|---|---|')
    foreach ($row in $TopThreadRows) {
        $lines.Add("| $($row.ThreadId) | $($row.ThreadIdHex) | $($row.MaxCpuPct) | $($row.AvgCpuPct) | $($row.Samples) | $($row.LastState) | $($row.LastWaitReason) |")
    }
    $lines.Add('')
    $lines.Add('## Files')
    $lines.Add('')
    $lines.Add("- ``$OutputDir\process_samples.csv``")
    $lines.Add("- ``$OutputDir\thread_samples.csv``")
    $lines.Add("- ``$OutputDir\stacks_*.txt`` when stack capture is available")
    $lines | Set-Content -LiteralPath $Path -Encoding UTF8
}

$process = Resolve-PZProcess -TargetPid $TargetPid -TargetName $ProcessName
$TargetPid = $process.Id
$coreCount = [Environment]::ProcessorCount

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDir = Join-Path "$env:USERPROFILE\Zomboid\Recording" "PZClientStutterSample_$stamp"
}
New-DirectoryIfNeeded -Path $OutputDir

$jcmd = ''
if (-not $NoStacks) {
    $jcmd = Find-Jcmd -ExplicitPath $JcmdPath -RepoToolsDir $ToolsDir
}

$processCsv = Join-Path $OutputDir 'process_samples.csv'
$threadCsv = Join-Path $OutputDir 'thread_samples.csv'
$summaryPath = Join-Path $OutputDir 'summary.md'
$metadataPath = Join-Path $OutputDir 'metadata.json'

$processColumns = @(
    'Timestamp','ElapsedMs','SampleDeltaMs','Pid','ProcessName','ProcessCpuPct','ProcessCpuOneCorePct',
    'WorkingSetMB','PrivateMB','Threads','Handles','IOReadMBps','IOWriteMBps','PageFaultsPerSec','GpuTotalPct','GpuBusyEngines','GpuTopEngines'
)
$threadColumns = @(
    'Timestamp','ElapsedMs','ThreadId','ThreadIdHex','ThreadCpuPct','ThreadCpuOneCorePct','ThreadDeltaMs',
    'TotalMs','UserMs','State','WaitReason','Priority'
)

($processColumns -join ',') | Set-Content -LiteralPath $processCsv -Encoding UTF8
($threadColumns -join ',') | Set-Content -LiteralPath $threadCsv -Encoding UTF8

$metadata = [ordered]@{
    StartedAt = (Get-Date).ToString('o')
    ProcessId = $TargetPid
    ProcessName = $process.ProcessName
    DurationSec = $DurationSec
    IntervalMs = $IntervalMs
    TopThreads = $TopThreads
    StackEverySec = $StackEverySec
    JcmdPath = $jcmd
    OutputDir = $OutputDir
}
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataPath -Encoding UTF8

Write-Host "Sampling Project Zomboid client PID $TargetPid ($($process.ProcessName)) for $DurationSec sec..." -ForegroundColor Cyan
Write-Host "Output: $OutputDir" -ForegroundColor Cyan
if ([string]::IsNullOrWhiteSpace($jcmd)) {
    Write-Host "jcmd unavailable: stack snapshots will be skipped. CPU/thread samples still work." -ForegroundColor Yellow
} else {
    Write-Host "jcmd: $jcmd" -ForegroundColor Cyan
}

$start = Get-Date
$lastTime = $start
$lastProc = Get-Process -Id $TargetPid -ErrorAction Stop
$lastCpuMs = [double]$lastProc.TotalProcessorTime.TotalMilliseconds
$lastThreads = Get-ThreadSnapshot -Process $lastProc
$nextStackAt = $start
$sampleCount = 0
$cpuValues = New-Object System.Collections.Generic.List[double]
$deltaValues = New-Object System.Collections.Generic.List[double]
$threadAgg = @{}
$jcmdUsed = $false

while (((Get-Date) - $start).TotalSeconds -lt $DurationSec) {
    Start-Sleep -Milliseconds $IntervalMs
    $now = Get-Date
    $elapsedMs = ($now - $start).TotalMilliseconds
    $deltaMs = [math]::Max(1.0, ($now - $lastTime).TotalMilliseconds)
    $proc = Get-Process -Id $TargetPid -ErrorAction Stop
    $cpuMs = [double]$proc.TotalProcessorTime.TotalMilliseconds
    $cpuDeltaMs = [math]::Max(0.0, $cpuMs - $lastCpuMs)
    $cpuPct = ($cpuDeltaMs / $deltaMs) * 100.0 / [math]::Max(1, $coreCount)
    $cpuOneCorePct = ($cpuDeltaMs / $deltaMs) * 100.0
    $gpu = Get-GpuUtilization -Disabled:$NoGpuCounters
    $perf = Get-ProcessPerfCounters -TargetPid $TargetPid

    Add-CsvRow -Path $processCsv -Columns $processColumns -Row @{
        Timestamp = $now.ToString('o')
        ElapsedMs = [math]::Round($elapsedMs, 2)
        SampleDeltaMs = [math]::Round($deltaMs, 2)
        Pid = $TargetPid
        ProcessName = $proc.ProcessName
        ProcessCpuPct = [math]::Round($cpuPct, 3)
        ProcessCpuOneCorePct = [math]::Round($cpuOneCorePct, 3)
        WorkingSetMB = [math]::Round($proc.WorkingSet64 / 1MB, 2)
        PrivateMB = [math]::Round($proc.PrivateMemorySize64 / 1MB, 2)
        Threads = $proc.Threads.Count
        Handles = $proc.HandleCount
        IOReadMBps = $perf.IOReadMBps
        IOWriteMBps = $perf.IOWriteMBps
        PageFaultsPerSec = $perf.PageFaultsPerSec
        GpuTotalPct = $gpu.Total
        GpuBusyEngines = $gpu.Busy
        GpuTopEngines = $gpu.Engines
    }

    $threads = Get-ThreadSnapshot -Process $proc
    $threadRows = New-Object System.Collections.Generic.List[object]
    foreach ($threadId in $threads.Keys) {
        if (-not $lastThreads.ContainsKey($threadId)) {
            continue
        }
        $cur = $threads[$threadId]
        $old = $lastThreads[$threadId]
        $threadDelta = [math]::Max(0.0, [double]$cur.TotalMs - [double]$old.TotalMs)
        $threadCpuPct = ($threadDelta / $deltaMs) * 100.0 / [math]::Max(1, $coreCount)
        $threadOneCorePct = ($threadDelta / $deltaMs) * 100.0
        $threadRows.Add([pscustomobject]@{
            ThreadId = $threadId
            ThreadIdHex = ('0x{0:x}' -f $threadId)
            ThreadCpuPct = [math]::Round($threadCpuPct, 3)
            ThreadCpuOneCorePct = [math]::Round($threadOneCorePct, 3)
            ThreadDeltaMs = [math]::Round($threadDelta, 3)
            TotalMs = [math]::Round($cur.TotalMs, 3)
            UserMs = [math]::Round($cur.UserMs, 3)
            State = $cur.State
            WaitReason = $cur.WaitReason
            Priority = $cur.Priority
        })
    }

    foreach ($row in @($threadRows | Sort-Object ThreadDeltaMs -Descending | Select-Object -First $TopThreads)) {
        Add-CsvRow -Path $threadCsv -Columns $threadColumns -Row @{
            Timestamp = $now.ToString('o')
            ElapsedMs = [math]::Round($elapsedMs, 2)
            ThreadId = $row.ThreadId
            ThreadIdHex = $row.ThreadIdHex
            ThreadCpuPct = $row.ThreadCpuPct
            ThreadCpuOneCorePct = $row.ThreadCpuOneCorePct
            ThreadDeltaMs = $row.ThreadDeltaMs
            TotalMs = $row.TotalMs
            UserMs = $row.UserMs
            State = $row.State
            WaitReason = $row.WaitReason
            Priority = $row.Priority
        }

        if (-not $threadAgg.ContainsKey($row.ThreadId)) {
            $threadAgg[$row.ThreadId] = [pscustomobject]@{
                ThreadId = $row.ThreadId
                ThreadIdHex = $row.ThreadIdHex
                Samples = 0
                SumCpuPct = 0.0
                MaxCpuPct = 0.0
                LastState = ''
                LastWaitReason = ''
            }
        }
        $agg = $threadAgg[$row.ThreadId]
        $agg.Samples++
        $agg.SumCpuPct += [double]$row.ThreadCpuOneCorePct
        if ([double]$row.ThreadCpuOneCorePct -gt [double]$agg.MaxCpuPct) {
            $agg.MaxCpuPct = [double]$row.ThreadCpuOneCorePct
        }
        $agg.LastState = $row.State
        $agg.LastWaitReason = $row.WaitReason
    }

    if (-not $NoStacks -and -not [string]::IsNullOrWhiteSpace($jcmd) -and $now -ge $nextStackAt) {
        $stackPath = Join-Path $OutputDir ("stacks_{0:000000}ms.txt" -f [int]$elapsedMs)
        $jcmdUsed = (Invoke-JcmdThreadPrint -Jcmd $jcmd -TargetPid $TargetPid -OutPath $stackPath) -or $jcmdUsed
        $nextStackAt = $now.AddSeconds([math]::Max(1, $StackEverySec))
    }

    $cpuValues.Add([double]$cpuPct)
    $deltaValues.Add([double]$deltaMs)
    $sampleCount++
    $lastTime = $now
    $lastCpuMs = $cpuMs
    $lastThreads = $threads

    if ($sampleCount % [math]::Max(1, [int](1000 / $IntervalMs)) -eq 0) {
        Write-Host ("{0,6:n1}s CPU {1,5:n1}% one-core {2,6:n1}% GPU {3}" -f ($elapsedMs / 1000.0), $cpuPct, $cpuOneCorePct, $gpu.Total)
    }
}

$profilerOutput = ''
if ($AnalyzeProfiler) {
    $analyzer = Join-Path $ToolsDir 'Analyze-PZGameProfiler.ps1'
    if (Test-Path -LiteralPath $analyzer) {
        $profilerOutput = Join-Path $OutputDir 'GameProfilerAnalysis'
        New-DirectoryIfNeeded -Path $profilerOutput
        $args = @('-ExecutionPolicy','Bypass','-File',$analyzer,'-OutputDir',$profilerOutput)
        if (-not [string]::IsNullOrWhiteSpace($ProfilerHeaderPath)) {
            $args += @('-HeaderPath', $ProfilerHeaderPath)
        }
        Write-Host "Running GameProfiler analyzer..." -ForegroundColor Cyan
        & powershell @args
    } else {
        Write-Warning "Analyze-PZGameProfiler.ps1 not found at $analyzer"
    }
}

$topThreadRows = @(
    $threadAgg.Values |
        ForEach-Object {
            [pscustomobject]@{
                ThreadId = $_.ThreadId
                ThreadIdHex = $_.ThreadIdHex
                Samples = $_.Samples
                AvgCpuPct = [math]::Round(($_.SumCpuPct / [math]::Max(1, $_.Samples)), 2)
                MaxCpuPct = [math]::Round($_.MaxCpuPct, 2)
                LastState = $_.LastState
                LastWaitReason = $_.LastWaitReason
            }
        } |
        Sort-Object MaxCpuPct -Descending |
        Select-Object -First 20
)

$maxCpu = if ($cpuValues.Count -gt 0) { ($cpuValues | Measure-Object -Maximum).Maximum } else { 0.0 }
$avgCpu = if ($cpuValues.Count -gt 0) { ($cpuValues | Measure-Object -Average).Average } else { 0.0 }
$maxDelta = if ($deltaValues.Count -gt 0) { ($deltaValues | Measure-Object -Maximum).Maximum } else { 0.0 }

Write-Summary `
    -Path $summaryPath `
    -OutputDir $OutputDir `
    -ProcessLabel "$($process.ProcessName) PID $TargetPid" `
    -SampleCount $sampleCount `
    -CoreCount $coreCount `
    -MaxCpuPct $maxCpu `
    -AvgCpuPct $avgCpu `
    -MaxFrameProxyMs $maxDelta `
    -TopThreadRows $topThreadRows `
    -JcmdUsed $jcmdUsed `
    -ProfilerOutput $profilerOutput

Write-Host ""
Write-Host "Sampling complete." -ForegroundColor Green
Write-Host "Summary: $summaryPath" -ForegroundColor Green
Write-Host "Process samples: $processCsv"
Write-Host "Thread samples:  $threadCsv"
