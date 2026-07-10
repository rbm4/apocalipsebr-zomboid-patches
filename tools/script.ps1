[CmdletBinding()]
param(
    [string]$CacheDir = "$env:USERPROFILE\Zomboid",
    [string]$HeaderPath,
    [string]$OutputDir,
    [int]$Top = 40,
    [int]$WorstFrameCount = 30,
    [int]$WorstFrameSpanCount = 120
)

$ErrorActionPreference = 'Stop'

class ProfilerSpan {
    [int]$Frame
    [int]$Key
    [string]$Name
    [int]$Depth
    [double]$StartMs
    [double]$InclusiveMs
    [double]$SelfMs
}

function Convert-Ticks100nsToMs([long]$ticks) {
    return [double]$ticks / 10000.0
}

function Escape-Html([object]$value) {
    if ($null -eq $value) { return '' }
    return [System.Net.WebUtility]::HtmlEncode([string]$value)
}

function Write-HtmlTable($title, $rows, $columns) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("<h2>$(Escape-Html $title)</h2>")
    [void]$sb.AppendLine('<table>')
    [void]$sb.AppendLine('<thead><tr>')
    foreach ($col in $columns) { [void]$sb.AppendLine("<th>$(Escape-Html $col)</th>") }
    [void]$sb.AppendLine('</tr></thead><tbody>')
    foreach ($row in $rows) {
        [void]$sb.AppendLine('<tr>')
        foreach ($col in $columns) { [void]$sb.AppendLine("<td>$(Escape-Html $row.$col)</td>") }
        [void]$sb.AppendLine('</tr>')
    }
    [void]$sb.AppendLine('</tbody></table>')
    return $sb.ToString()
}

function Add-WorstFrameCandidate {
    param(
        [System.Collections.Generic.List[object]]$List,
        [object]$Candidate,
        [int]$Limit
    )

    if ($Limit -le 0) { return }

    if ($List.Count -lt $Limit) {
        $List.Add($Candidate) | Out-Null
        return
    }

    $minIndex = 0
    $minTotal = [double]::MaxValue

    for ($j = 0; $j -lt $List.Count; $j++) {
        $value = [double]$List[$j].TotalMs
        if ($value -lt $minTotal) {
            $minTotal = $value
            $minIndex = $j
        }
    }

    if ([double]$Candidate.TotalMs -gt $minTotal) {
        $List[$minIndex] = $Candidate
    }
}

$recordingDir = Join-Path $CacheDir 'Recording'

if (-not $HeaderPath) {
    if (-not (Test-Path -LiteralPath $recordingDir)) { throw "Recording directory does not exist: $recordingDir" }

    $HeaderPath = Get-ChildItem -LiteralPath $recordingDir -Filter '*GameProfiler*MainThread*_header.csv' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName

    if (-not $HeaderPath) {
        $HeaderPath = Get-ChildItem -LiteralPath $recordingDir -Filter '*GameProfiler*_header.csv' |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1 -ExpandProperty FullName
    }
}

if (-not $HeaderPath -or -not (Test-Path -LiteralPath $HeaderPath)) {
    throw "Could not find a GameProfiler header CSV. Pass -HeaderPath or check $recordingDir."
}

$headerFile = Get-Item -LiteralPath $HeaderPath
$prefix = $headerFile.Name -replace '_header\.csv$', ''
$recordingDir = $headerFile.DirectoryName
$segmentFiles = @(Get-ChildItem -LiteralPath $recordingDir -Filter ($prefix + '_times_*.csv') | Sort-Object Name)

if (-not $segmentFiles -or $segmentFiles.Count -eq 0) {
    throw "No detailed segment files found for prefix '$prefix' in $recordingDir. Expected ${prefix}_times_0000.csv etc. The ${prefix}_times.csv file is only a frame summary and cannot produce span analysis."
}

$keyNames = @{}
$inKeyTable = $false

foreach ($line in [System.IO.File]::ReadLines($headerFile.FullName)) {
    $trimmed = $line.Trim()
    if ($trimmed -eq 'Index,Name') { $inKeyTable = $true; continue }
    if (-not $inKeyTable -or [string]::IsNullOrWhiteSpace($trimmed)) { continue }

    $comma = $line.IndexOf(',')
    if ($comma -le 0) { continue }

    $idxText = $line.Substring(0, $comma).Trim()
    $name = $line.Substring($comma + 1).Trim()
    $idx = 0

    if ([int]::TryParse($idxText, [ref]$idx)) { $keyNames[$idx] = $name }
}

if ($keyNames.Count -eq 0) { throw "Could not parse KeyNamesTable from $($headerFile.FullName)." }

if (-not $OutputDir) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDir = Join-Path $recordingDir ("GameProfilerAnalysis_" + $stamp)
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$aggregate = @{}
$worstCandidates = New-Object 'System.Collections.Generic.List[object]'
$totalFrames = 0
$totalSpans = 0
$malformedLines = 0

foreach ($file in $segmentFiles) {
    Write-Verbose "Reading $($file.FullName)"

    foreach ($line in [System.IO.File]::ReadLines($file.FullName)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }

        $cells = $line.Split(',')
        if ($cells.Count -lt 5) { $malformedLines++; continue }

        $frameNo = 0
        if (-not [int]::TryParse($cells[0].Trim(), [ref]$frameNo)) { $malformedLines++; continue }

        $spans = New-Object 'System.Collections.Generic.List[object]'
        $i = 1

        while (($i + 3) -lt $cells.Count) {
            $key = 0
            $depth = 0
            $startTicks = 0L
            $lengthTicks = 0L

            $okKey = [int]::TryParse($cells[$i].Trim(), [ref]$key)
            $okDepth = [int]::TryParse($cells[$i + 1].Trim(), [ref]$depth)
            $okStart = [long]::TryParse($cells[$i + 2].Trim(), [ref]$startTicks)
            $okLength = [long]::TryParse($cells[$i + 3].Trim(), [ref]$lengthTicks)

            if ($okKey -and $okDepth -and $okStart -and $okLength -and $lengthTicks -gt 0) {
                $span = [ProfilerSpan]::new()
                $span.Frame = $frameNo
                $span.Key = $key
                $span.Name = if ($keyNames.ContainsKey($key)) { $keyNames[$key] } else { "<unknown:$key>" }
                $span.Depth = $depth
                $span.StartMs = [math]::Round((Convert-Ticks100nsToMs $startTicks), 4)
                $span.InclusiveMs = Convert-Ticks100nsToMs $lengthTicks
                $span.SelfMs = $span.InclusiveMs
                $spans.Add($span) | Out-Null
            }

            $i += 4
        }

        if ($spans.Count -eq 0) { continue }

        $stack = New-Object 'System.Collections.Generic.List[object]'
        $rootSpan = $null
        $frameTotalMs = 0.0

        foreach ($span in $spans) {
            if ($null -eq $rootSpan -and $span.Depth -eq 0) { $rootSpan = $span }

            while ($stack.Count -gt 0 -and $stack[$stack.Count - 1].Depth -ge $span.Depth) {
                $stack.RemoveAt($stack.Count - 1)
            }

            if ($stack.Count -gt 0) {
                $parent = $stack[$stack.Count - 1]
                $parent.SelfMs = [math]::Max(0.0, $parent.SelfMs - $span.InclusiveMs)
            }

            if ($span.InclusiveMs -gt $frameTotalMs) { $frameTotalMs = $span.InclusiveMs }
            $stack.Add($span) | Out-Null
        }

        if ($null -ne $rootSpan) { $frameTotalMs = $rootSpan.InclusiveMs }

        foreach ($span in $spans) {
            if (-not $aggregate.ContainsKey($span.Key)) {
                $aggregate[$span.Key] = [pscustomobject]@{
                    Key = $span.Key
                    Name = $span.Name
                    Count = 0L
                    FrameCount = 0L
                    LastFrame = [int]::MinValue
                    TotalInclusiveMs = 0.0
                    TotalSelfMs = 0.0
                    MaxInclusiveMs = 0.0
                    MaxSelfMs = 0.0
                }
            }

            $agg = $aggregate[$span.Key]
            $agg.Count++
            $agg.TotalInclusiveMs += $span.InclusiveMs
            $agg.TotalSelfMs += $span.SelfMs

            if ($agg.LastFrame -ne $frameNo) {
                $agg.FrameCount++
                $agg.LastFrame = $frameNo
            }

            if ($span.InclusiveMs -gt $agg.MaxInclusiveMs) { $agg.MaxInclusiveMs = $span.InclusiveMs }
            if ($span.SelfMs -gt $agg.MaxSelfMs) { $agg.MaxSelfMs = $span.SelfMs }
        }

        $candidate = [pscustomobject]@{
            Frame = $frameNo
            TotalMs = [math]::Round($frameTotalMs, 4)
            SpanCount = $spans.Count
            Segment = $file.Name
            Spans = $spans
        }

        Add-WorstFrameCandidate -List $worstCandidates -Candidate $candidate -Limit $WorstFrameCount

        $totalFrames++
        $totalSpans += $spans.Count
    }
}

$summary = foreach ($agg in $aggregate.Values) {
    [pscustomobject]@{
        Key = $agg.Key
        Name = $agg.Name
        Count = $agg.Count
        Frames = $agg.FrameCount
        TotalInclusiveMs = [math]::Round($agg.TotalInclusiveMs, 4)
        TotalSelfMs = [math]::Round($agg.TotalSelfMs, 4)
        AvgInclusiveMs = [math]::Round(($agg.TotalInclusiveMs / [math]::Max(1, $agg.Count)), 4)
        AvgSelfMs = [math]::Round(($agg.TotalSelfMs / [math]::Max(1, $agg.Count)), 4)
        MaxInclusiveMs = [math]::Round($agg.MaxInclusiveMs, 4)
        MaxSelfMs = [math]::Round($agg.MaxSelfMs, 4)
    }
}

$topInclusive = $summary | Sort-Object TotalInclusiveMs -Descending | Select-Object -First $Top
$topSelf = $summary | Sort-Object TotalSelfMs -Descending | Select-Object -First $Top
$topMax = $summary | Sort-Object MaxInclusiveMs -Descending | Select-Object -First $Top
$worstFramesFull = @($worstCandidates | Sort-Object TotalMs -Descending | Select-Object -First $WorstFrameCount)
$worstFrames = $worstFramesFull | Select-Object Frame, TotalMs, SpanCount, Segment

$worstFrameSpanRows = New-Object 'System.Collections.Generic.List[object]'

foreach ($frame in $worstFramesFull) {
    foreach ($span in $frame.Spans) {
        $worstFrameSpanRows.Add([pscustomobject]@{
            Frame = $span.Frame
            Segment = $frame.Segment
            Depth = $span.Depth
            StartMs = $span.StartMs
            InclusiveMs = [math]::Round($span.InclusiveMs, 4)
            SelfMs = [math]::Round($span.SelfMs, 4)
            Key = $span.Key
            Name = $span.Name
        }) | Out-Null
    }
}

$worstFrameSpans = $worstFrameSpanRows | Sort-Object Frame, Depth, StartMs | Select-Object -First $WorstFrameSpanCount

$topInclusive | Export-Csv -NoTypeInformation -Encoding UTF8 -Path (Join-Path $OutputDir 'top_inclusive.csv')
$topSelf | Export-Csv -NoTypeInformation -Encoding UTF8 -Path (Join-Path $OutputDir 'top_self.csv')
$topMax | Export-Csv -NoTypeInformation -Encoding UTF8 -Path (Join-Path $OutputDir 'top_max_spikes.csv')
$worstFrames | Export-Csv -NoTypeInformation -Encoding UTF8 -Path (Join-Path $OutputDir 'worst_frames.csv')
$worstFrameSpans | Export-Csv -NoTypeInformation -Encoding UTF8 -Path (Join-Path $OutputDir 'worst_frame_spans.csv')

[pscustomobject]@{
    HeaderPath = $headerFile.FullName
    SegmentFileCount = $segmentFiles.Count
    ParsedFrames = $totalFrames
    ParsedSpans = $totalSpans
    MalformedLines = $malformedLines
    OutputDir = $OutputDir
} | ConvertTo-Json | Set-Content -Encoding UTF8 -Path (Join-Path $OutputDir 'metadata.json')

$style = @(
    '<style>',
    'body { font-family: Segoe UI, Arial, sans-serif; margin: 24px; color: #202020; }',
    'h1 { margin-bottom: 0; }',
    '.meta { color: #555; margin-top: 6px; }',
    'table { border-collapse: collapse; margin: 12px 0 28px 0; width: 100%; font-size: 13px; }',
    'th, td { border: 1px solid #ddd; padding: 6px 8px; text-align: left; vertical-align: top; }',
    'th { background: #f2f2f2; position: sticky; top: 0; }',
    'tr:nth-child(even) { background: #fafafa; }',
    'code { background: #f6f6f6; padding: 2px 4px; }',
    '</style>'
) -join [Environment]::NewLine

$html = New-Object System.Text.StringBuilder
[void]$html.AppendLine('<!doctype html><html><head><meta charset="utf-8">')
[void]$html.AppendLine($style)
[void]$html.AppendLine('</head><body>')
[void]$html.AppendLine('<h1>Project Zomboid GameProfiler Analysis</h1>')
[void]$html.AppendLine("<p class='meta'>Header: <code>$(Escape-Html $headerFile.FullName)</code><br>Frames: $totalFrames | Spans: $totalSpans | Segments: $($segmentFiles.Count) | Malformed lines: $malformedLines</p>")
[void]$html.AppendLine((Write-HtmlTable 'Worst Frames' $worstFrames @('Frame','TotalMs','SpanCount','Segment')))
[void]$html.AppendLine((Write-HtmlTable 'Top Inclusive Time' $topInclusive @('Key','Name','Count','Frames','TotalInclusiveMs','AvgInclusiveMs','MaxInclusiveMs')))
[void]$html.AppendLine((Write-HtmlTable 'Top Self Time' $topSelf @('Key','Name','Count','Frames','TotalSelfMs','AvgSelfMs','MaxSelfMs')))
[void]$html.AppendLine((Write-HtmlTable 'Largest Single Spikes' $topMax @('Key','Name','Count','Frames','MaxInclusiveMs','TotalInclusiveMs')))
[void]$html.AppendLine((Write-HtmlTable 'Spans From Worst Frames' $worstFrameSpans @('Frame','Segment','Depth','StartMs','InclusiveMs','SelfMs','Key','Name')))
[void]$html.AppendLine('</body></html>')
$html.ToString() | Set-Content -Encoding UTF8 -Path (Join-Path $OutputDir 'report.html')

Write-Host "Analyzed Project Zomboid GameProfiler recording."
Write-Host "Header: $($headerFile.FullName)"
Write-Host "Frames: $totalFrames"
Write-Host "Spans:  $totalSpans"
Write-Host "Malformed lines: $malformedLines"
Write-Host "Output: $OutputDir"
Write-Host "Open report.html in a browser, or inspect top_inclusive.csv/top_self.csv/worst_frames.csv."
