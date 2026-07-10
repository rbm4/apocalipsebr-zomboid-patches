# Project Zomboid Vanilla GameProfiler Tools

These scripts toggle Project Zomboid's built-in `GameProfiler.Enabled` flag by writing the watched XML trigger file:

`%USERPROFILE%\Zomboid\messaging\Trigger_PerformanceProfiler.xml`

Run these while the client is already loaded into the world. The game file watcher reacts to create/modify events and debounces changes for about two seconds, so the start script pulses `false` then `true`.

## Start

```powershell
powershell -ExecutionPolicy Bypass -File .\Start-PZGameProfiler.ps1
```

Wait about 3 seconds, reproduce the stutter for 10-30 seconds, then stop.

## Stop

```powershell
powershell -ExecutionPolicy Bypass -File .\Stop-PZGameProfiler.ps1
```

## List Recordings

```powershell
powershell -ExecutionPolicy Bypass -File .\Get-PZGameProfilerStatus.ps1
```

Recordings are expected in:

`%USERPROFILE%\Zomboid\Recording`

Look for files named like:

- `*_GameProfiler_MainThread_header.csv`
- `*_GameProfiler_MainThread_times.csv`
- `*_GameProfiler_MainThread_times_0000.csv`

The header file contains `KeyNamesTable`, which maps numeric span ids to names such as `GameWindow.frameStep`, `UI`, `Lua - OnTick`, or `Render <UI element>`.

The time segment files store span times as `x * 100ns`; divide by `10000` to get milliseconds.

## Custom Cache Dir

If the game was launched with a different `-cachedir`, pass it explicitly:

```powershell
powershell -ExecutionPolicy Bypass -File .\Start-PZGameProfiler.ps1 -CacheDir C:\Users\ricar\Zomboid
```

## Analyze Recordings

After stopping the profiler, generate readable summaries:

```powershell
powershell -ExecutionPolicy Bypass -File .\Analyze-PZGameProfiler.ps1
```

The analyzer finds the latest `*_GameProfiler_MainThread_header.csv` by default and creates a folder under `Recording`, for example:

`%USERPROFILE%\Zomboid\Recording\GameProfilerAnalysis_YYYYMMDD-HHMMSS`

Outputs:

- `report.html`: browser-friendly overview
- `top_inclusive.csv`: broad sections consuming the most total time, including children
- `top_self.csv`: estimated time inside a span after subtracting child spans
- `top_max_spikes.csv`: labels with the largest single-frame spikes
- `worst_frames.csv`: slowest frames in the recording
- `worst_frame_spans.csv`: span detail from the slowest frames

You can analyze a specific recording by passing the header file:

```powershell
powershell -ExecutionPolicy Bypass -File .\Analyze-PZGameProfiler.ps1 -HeaderPath "C:\Users\ricar\Zomboid\Recording\..._GameProfiler_MainThread_header.csv"
```
