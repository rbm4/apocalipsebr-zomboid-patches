# Byte Buddy server telemetry: vanilla-backed basics

The first telemetry port uses the exact 42.22.2 decompiled server classes as
instrumentation targets, without porting the optimizations or their custom
telemetry call sites. It populates the legacy schema-3 `tick` and `loop` fields
and adds server-thread gauges to the agent payload:

- `tick` counts completed simulation cycles and measures from the beginning of
  the outer `GameServer` iteration through the simulation completion boundary.
  Packet-only iterations do not count as ticks. `overBudgetTicks` counts cycles
  exceeding 100 ms.
- `loop` counts every outer iteration, including packet-only iterations and
  their sleep; `totalMs` excludes telemetry snapshot construction and delivery.
- `playersOnline` is the larger of vanilla `GameServer.Players.size()` and
  `GameServer.IDToPlayerMap.size()`, sampled when a snapshot is due.
- `world.zombies` is the `IsoWorld.instance.currentCell.getZombieList().size()`
  gauge at snapshot time. It counts active zombies in the loaded current cell,
  not virtual or whole-world population. The property is omitted while there is
  no current cell.

The Byte Buddy visitor instruments only the target `GameServer.main` method. It
discovers the `done`-controlled loop header, the cycle-start `System.nanoTime()`,
the vanilla `WorldMapVisitedServer.update()` completion point, and the loop
back-edge. When the patched source also contains
`ApocBRServerTelemetryLite.recordTick/recordLoop`, the visitor reuses the already
computed elapsed values at those call sites. That preserves the legacy tick
endpoint (including work between the vanilla update and `recordTick`) and keeps
legacy loop timing ahead of its own telemetry emission. Without those
patch-specific calls, the vanilla completion and back-edge are used instead.
Instrumentation is disabled with an explicit diagnostic if the expected
42.22.2 anchors do not match.

The 30-second snapshot pulse runs after the loop duration is recorded. Player
and world state are read only when a snapshot is due. If the required fields or
native getter are unavailable, snapshot construction fails with an explicit
diagnostic rather than fabricating a zero population. `gameVersion` comes from
vanilla `Core.getVersionNumber()` unless `apocbr.telemetry.gameVersion` is
explicitly configured.

The agent has no compile-time dependency on game classes. Online players are
read through reflective access to the exact public 42.22.2 `GameServer` fields;
the current-cell zombie gauge uses the public `IsoWorld`/`IsoCell` members.

## Deliberate limits

The `tick` shape and cycle population now match the legacy `count`, `rate`,
`avgMs`, `maxMs`, and `overBudgetTicks` semantics when the legacy timing calls
are present. The `loop` timing uses the legacy elapsed argument when available.
Patch-specific `phases` and `counters`, pending-cell count, moving-object
histogram, and JVM measurements are not ported by this tranche and remain
empty/unavailable in the agent payload. Lua event timing remains its existing,
separate vanilla hook.

The 42.22.2 decompiled tree contains no `ApocBRServerTelemetryLite` calls; those
recording sites were added to 45 patched/custom source files. Thirty-one have
vanilla counterparts and fourteen are custom-only. Measurements derived from
custom indexes, schedulers, traversal counters, and patch-only state are deferred
to a later migration session rather than inferred from unrelated vanilla work.
The [source baseline and impact report](../reports/2026-11-18-byte-buddy-migration-baseline/2026-11-18-byte-buddy-migration-baseline.md)
retains the class inventory and per-source diffs.

## Verification

`mvn test` covers tick/loop aggregation and reset, schema-3 payload values,
current-cell zombie sampling, and existing telemetry delivery behavior.
`AgentSmokeMain` additionally runs a structural `GameServer.main` double with
both simulation and packet-only iterations to verify the packaged bytecode
transform and its helper linkage. These checks are not a live dedicated-server
runtime test. Before treating measurements as production telemetry, launch the
matching 42.22.2 server with the agent and compare `tick` and `loop` values with
the legacy telemetry during active simulation, packet-only periods, and
over-budget cycles; also confirm player/zombie gauges and absence of
transformation/linkage errors.

See the [agent foundation](2026-11-17-byte-buddy-agent-foundation.md) for
packaging and startup instructions, and the inherited
[schema-3 telemetry definitions](../../../42.21.0/docs/features/2026-10-01-telemetry.md)
for the legacy payload's broader metric semantics.
