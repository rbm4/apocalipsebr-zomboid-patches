# Feature catalog

This catalog distinguishes current implementation from proposed migration work.
No runtime performance improvement is established by this catalog.

| Feature | Root implementation | Current scope and limits |
| --- | --- | --- |
| Workshop update restart notification | `features/workshop` | Existing notification hook and HTTP integration; not a port of the rest of the engine. |
| Server telemetry basics | `features/telemetry` | Existing tick/loop timing, Lua event timing, online-player and active-cell zombie gauges, snapshot reporting. Optimization phases/counters, moving-object histogram, pending-cell gauge and JVM metrics remain unported. |
| [PostgreSQL and NPC runtime](postgresql.md) | `features/postgres` | Module and synchronous account hooks registered, with executed transformation tests. Complete journal/recovery, player/vehicle/dictionary and NPC runtime migration remains open. |
| Legacy engine optimizations and other patches | None declared complete here | Require separately selected feature ports and validation against the original JAR. |

## Adding a feature

Create `features/<feature>.md` from the
[migration template](../migrations/feature-template.md). Describe the production
contract, instrumentation boundaries, configuration, state ownership, metric
definitions, validation, and runtime gaps. Link the guide from this catalog and
update the [migration status](../migrations/README.md).

Port each optimization with its relevant telemetry. Method duration alone cannot
recover counters for skipped work or internal decisions. Retain metric names only
when their populations, timing boundaries, units and reset semantics match.
Unavailable measurements must not masquerade as measured zero values.
