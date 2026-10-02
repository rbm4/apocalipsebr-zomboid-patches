# Server telemetry (schema 2)

This extends the existing server NDJSON telemetry in the 42.21.0 patch tree.
Compile against the intended build before deployment; compilation against a local
JAR does not establish compatibility with a differently versioned live server.
The payload includes the runtime game version to remove ambiguity in captures.

Existing properties remain available. Output defaults to `apocbr-telemetry.ndjson`.
JVM settings: `-Dapocbr.telemetry.intervalMs=30000`,
`-Dapocbr.telemetry.path=/path/to/apocbr-telemetry.ndjson`, and
`-Dapocbr.telemetry.lua.enabled=true` for optional Lua event timings.
Emission happens at the end of an outer server loop; disk writes remain on the
bounded asynchronous writer queue. `dropped` counts lost telemetry records.

## Interpretation

- `intervalMs`: actual elapsed reporting window.
- `tick`: completed simulation cycles. `avgMs` includes worker waiting and packet
  processing in the outer iteration that ran simulation, but excludes preceding
  packet-only iterations. It is elapsed time, not CPU time.
- `overBudgetTicks`: recorded cycles exceeding 100 ms.
- `loop`: all outer iterations, including packet-only iterations and their sleep.
  `totalMs` excludes telemetry emission; `telemetry.emit` reports that cost in the
  following reporting window.
- `phases`: calls, totalMs, average per call, and maximum per call. Names describe
  measured call sites. Timings are inclusive: never sum all phases as though they
  were independent. Lua timings also overlap their containing Java phase and may
  overlap other Lua events through nesting.
- `counters`: interval totals, reset after emission. Missing counters mean no
  observations yet. Packet drained counts include packets subsequently dropped;
  `packets.dropped` reports drops by the normal-queue overload branch only.
- `world`: end-of-window gauges. `loadedCells` is the engine list size, including
  cells pending activation; `pendingCells` is the loading list size. Zombies and
  movingObjects are active cell list sizes, not virtual population totals.
- `jvm`: used/committed/max heap bytes and GC collection count/time deltas. GC time
  comes from MXBeans; it is not a precise sum of stop-the-world pauses.

## Phase relationships

`network.zombies.awaitAndSend` contains `network.zombies.workerWait` and
`network.zombies.send`. `map.postupdate` can also contain these zombie phases,
plus `network.zombies.auth`, `map.cell.update`, `map.cell.unload`,
`map.losSuspendWait`, and `map.saveCompletions`.

`map.preupdate` contains `map.cell.integrate`, `map.cell.loadVehicles`,
`map.losSuspendWait`, and `map.saveAll` when those operations occur.
`simulation.update` contains the IngameState world simulation and its Lua events.
Other top-level phases cover incoming networking, collision state, vehicles,
RCON, player relevance/chunk downloads, player networking, social systems,
backups, and asynchronous filesystem/world-map updates. Smaller uninstrumented
operations and idle sleep can leave a remainder in `loop.totalMs`.

`chunks.newIntegrated` and `chunks.existingIntegrated` classify chunks using the
engine's `isNewChunk()` (the addZombies flag), immediately before grid integration.
They count integration attempts, not successful background generation jobs or
unique coordinates. `cells.integrated` counts completed cell integrations;
`cells.unloaded` counts completed unload calls. These metrics do not directly
measure background disk read, world generation, or recalculation worker duration.

## Diagnosing a fresh save

Capture several windows while players explore, then while the same players stay
in already visited areas. Compare new chunk integrations and pending cells with
`map.preupdate`/`map.cell.integrate` time. A simultaneous fall in new integrations,
map integration time, and tick overruns supports generation/streaming as a cause.
Large `map.cell.update` totals instead suggest ongoing loaded-world work.
Large zombie worker wait/send totals point toward zombie synchronization.
High GC deltas need a JVM recording to distinguish pauses from concurrent work.

Do not identify a mod from an event aggregate alone: the Lua metrics time all
callbacks together. Live validation is still required for overhead, threading,
and the actual bottleneck. No scheduling, packet, save, or gameplay behavior is
intentionally changed by this instrumentation.
