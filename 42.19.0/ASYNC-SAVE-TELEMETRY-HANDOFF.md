# patchAsyncSaveTelemetry Handoff Documentation

## Summary

`patchAsyncSaveTelemetry.sh` is the combined Build 42.19.0 classpath-override patch for async save, server telemetry, guarded IsoWorld parallelism, moving-object diagnostics, and vehicle diagnostics.

Its purpose is to diagnose Project Zomboid dedicated-server main-thread starvation without flooding logs, then choose narrower optimization targets safely. The patch intentionally avoids moving authoritative world mutation or Lua execution off the main thread after repeated `Lua code called from the wrong thread` failures.

## What The Script Does

- Compiles patched Java sources from `42.19.0/src` against `projectzomboid.jar` using `javac --release 25`.
- Deploys compiled `.class` files as loose classpath overrides into the PZ install directory.
- Supports `--pz-dir PATH`, `--dry-run`, and `--revert`.
- Owns shared class outputs that otherwise conflict between async save, telemetry, no-cull, IsoWorld, IsoCell, scheduler, and vehicle patches.
- Deploys patched classes for `ApocBRServerTelemetry`, `GameServer`, `ServerMap`, `PlayerDownloadServer`, `IngameState`, `IsoWorld`, `IsoCell`, `MovingObjectUpdateScheduler`, `MovingObjectUpdateSchedulerUpdateBucket`, and `BaseVehicle` including all generated `BaseVehicle$...` inner classes.

## Intent

The original hypothesis was that server lag came from too much zombie simulation on one main thread. Telemetry refined that view:

- Some high-zombie snapshots show `IsoWorld -> IsoCell -> movingObjects` pressure.
- Other long-running snapshots lag with few zombies, proving live zombie count alone is not the full explanation.
- Packet queues and chunk worker compression are usually not the sustained bottleneck.
- Main-thread world update starvation is the recurring issue.

The script exists to identify exactly which subsystem owns the main-thread time before attempting risky parallelism.

## Telemetry Blocks

The periodic `[ApocBRTelemetry]` log records one aggregate line per interval.

Important blocks:

- `world{...}`: whole server tick timing and high-level sections.
- `packets{...}`: high/player/normal packet drain volume and time.
- `chunks{...}`: chunk request/prep/worker throughput.
- `stateUpdate{...}`: `IngameState` internal phases.
- `isoWorld{...}`: `IsoWorld.updateInternal()` and `updateWorld()` phases.
- `isoCell{...}`: `IsoCell.updateInternal()` and `ProcessObjects()` phases.
- `parallelWorld{...}`: guarded safe IsoWorld async batch.
- `movingBucket{...}`: moving-object bucket update counts and timings.
- `movingTypes{...}`: class distribution inside moving-object bucket updates.
- `movingStartFrame{...}` / `movingStartTypes{...}`: scheduler scan and bucket-build telemetry.
- `vehicle{...}`: vehicle part and Lua timing from `BaseVehicle`.

## Findings So Far

- Vanilla `Threading.Animation=true` is unsafe: it caused Lua timed-action calls from `ForkJoinPool.commonPool-worker-*`.
- Scheduler-wide async `MovingObjectUpdateScheduler.update()` is unsafe: `BaseVehicle.update()` reaches `BaseVehicle.updatePart()` -> `callLuaVoid()` -> Kahlua, causing the same wrong-thread Lua failure.
- Vanilla `Threading.World=true` remains unsafe: its update thread can reach DB/vehicle paths that call Lua.
- Guarded safe IsoWorld async is stable but low-impact: `parallelWorld` usually has low wait/skips/errors and does not attack the main hotspot.
- Production hotspots often resolve to `stateUpdate -> isoWorld -> updateWorld -> currentCell -> movingObjects`, sometimes `serverMapPre/serverMapPost`, and occasionally `gameTime/updateStuff` spikes.
- Local driving tests showed chunk/map spikes, not vehicle update spikes: `serverMapPreMaxMs` dominated the spike and `movingTypes.BaseVehicle` stayed cheap locally.
- Vehicle-specific telemetry is needed on production before optimizing vehicle logic.

## Current Known Issues To Fix Before Relying On Newest Telemetry

Source inspection identified two telemetry visibility problems:

- `vehicleLog()` exists but was not appended in `maybeLog()`, so `vehicle{...}` did not appear in logs.
- `movingTypeLog()` was resetting `movingStartFrame` counters before `movingStartFrameLog()` printed, causing `movingStartFrame{calls=0}` even when hooks may have been active.

These should be fixed before the next telemetry run used for diagnosis.

## Safety Conclusions

Do not offload any path that can reach:

- `LuaManager.caller.protectedCallVoid`
- `LuaManager.thread`
- `KahluaThread.pcall`
- vehicle part update Lua
- timed-action/player animation Lua

Do not use scheduler-wide async for live `IsoMovingObject.update()` or `postupdate()`.

Prefer safer approaches:

- telemetry first
- pure Java compute off-thread with main-thread apply
- main-thread Lua queues with strict budgets
- per-subsystem patching instead of global scheduler async

## Recommended Next Review Targets

- Fix telemetry visibility:
  - append `vehicleLog()` to `maybeLog()`
  - stop `movingTypeLog()` from resetting `movingStartFrame`
- Re-run local and production with fixed telemetry.
- Inspect production `vehicle{...}` together with `movingTypes.BaseVehicle`, `isoCell.movingObjects`, and `serverMapPre/serverMapPost`.
- If vehicle Lua is expensive, do not run Lua async; consider batching, throttling, or budgeting vehicle part Lua on the main thread.
- If vehicle non-Lua Java is expensive, identify pure calculations that can be moved to worker compute with main-thread apply.

## Review Notes For Other Agents

- Treat decompiled Build 42.19.0 Java as the source of truth.
- Validate script deploy lists whenever adding a Java override with inner classes; loose classpath overrides must include generated `$...` classes.
- Use `patchAsyncSaveTelemetry.ps1 -DryRun` or `patchAsyncSaveTelemetry.sh --dry-run` before deployment.
- Require a full server/client process restart after deploying Java class overrides; already-loaded JVM classes will not refresh from disk.
- Watch for any log line containing `Lua code called from the wrong thread` and immediately revert the relevant async experiment.