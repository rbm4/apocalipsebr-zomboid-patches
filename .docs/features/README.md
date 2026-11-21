# Feature catalog

This catalog distinguishes current implementation from proposed migration work.
No runtime performance improvement is established by this catalog.

| Feature | Root implementation | Current scope and limits |
| --- | --- | --- |
| Workshop update restart notification | `features/workshop` | Existing notification hook and HTTP integration; not a port of the rest of the engine. |
| Server telemetry basics | `features/telemetry` | Existing tick/loop timing, Lua event timing, online-player and active-cell zombie gauges, snapshot reporting. Optimization phases/counters, moving-object histogram, pending-cell gauge and JVM metrics remain unported. |
| [PostgreSQL and NPC runtime](postgresql.md) | `features/postgres` | Scoped account hooks and async login/admin continuations registered, with executed transformation tests. Complete journal/recovery, player/vehicle/dictionary and NPC runtime migration remains open. |
| [PostgreSQL dictionary setup](postgresql-dictionary.md) | `features/postgres/dictionary` | Native publication, exact historical cache/listener and source-aware native decoder hooks implemented; real cross-server acceptance remains pending. |
| [PostgreSQL player authority](postgresql-players.md) | `features/postgres/player`, `features/postgres/PlayerJournal` | Profile/connect reconstruction, durable creation, live/final persistence and lifecycle hooks wired; defaults off, real database/client acceptance pending. |
| Legacy engine optimizations and other patches | None declared complete here | Require separately selected feature ports and validation against the original JAR. |
| [NPC actors, gameplay and persistence](npc-engine.md) | `features/npc`, `features/postgres/NpcPersistence` | Factory/actor graph and original-game hooks connected; scoped durable NPC SQL/sales coordinator added. Full behavioral, database and live-client acceptance remains open. |
| [Server Lua APIs and service workers](server-lua.md) | `features/lua` | Registered Lua/solar/generator/streaming/event/radio/API/login-audit hooks and async account/2FA/admin workers; original-JAR execution checks present, full live acceptance pending. |

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
