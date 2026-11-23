# Feature catalog

This catalog distinguishes current implementation from proposed migration work.
No runtime performance improvement is established by this catalog.

| Feature | Root implementation | Current scope and limits |
| --- | --- | --- |
| [Server hotfixes](hotfixes.md) | `features/hotfixes` | Case-insensitive XML folder/file lookup port for the original 42.22.2 JAR, enabled by default and reversible. |
| [Server packet diagnostics](network-diagnostics.md) | `features/networkdiagnostics` | Rate-limits repeated invalid-slot errors and inconsistent-packet warnings on the original 42.22.2 JAR. |
| [Workshop update checks and restart notification](server-lag-diagnostics.md) | `features/workshop` | Asynchronous engine query, bounded result processing, and existing restart delivery. Live Steam validation is pending. |
| [Server telemetry](server-lag-diagnostics.md) | `features/telemetry` | Slowest-tick phase and callback attribution, Lua event timing, JVM/world gauges, and optimization metrics. |
| [Java pathfinding lock](java-pathfinding.md) | `features/pathfinding` | Selects the built-in Java pathfinder by default, with dedicated-server worker cleanup. |
| [PostgreSQL and NPC runtime](postgresql.md) | `features/postgres` | Scoped account hooks and async login/admin continuations registered, with executed transformation tests. Complete journal/recovery, player/vehicle/dictionary and NPC runtime migration remains open. |
| [PostgreSQL dictionary setup](postgresql-dictionary.md) | `features/postgres/dictionary` | Native publication, exact historical cache/listener and source-aware native decoder hooks implemented; real cross-server acceptance remains pending. |
| [PostgreSQL player authority](postgresql-players.md) | `features/postgres/player`, `features/postgres/PlayerJournal` | Profile/connect reconstruction, durable creation, live/final persistence and lifecycle hooks wired; defaults off, real database/client acceptance pending. |
| [Server optimization ports](server-optimizations.md) | `features/optimization` | Five ports enabled by default and exercised in a forked original-JAR fixture. Live workload measurements remain open. |
| [Server cell perimeter lookups](server-cell-streaming.md) | `features/optimization/streaming` | Four original-JAR lookup sites in cell integration, with an independent startup flag. |
| [Chunk integration and detachment](../reports/2026-11-22-chunk-lifecycle.md) | `features/optimization/streaming` | Synchronous container and adjacency changes with independent startup switches and a paired real-server comparison. |
| [Parked vehicle cadence](parked-vehicle-cadence.md) | `features/optimization/vehicle` | Original dedicated-server scheduler bucket selection, enabled by default with an independent startup flag. |
| [Server simulation, LOS and zombie transmission](server-simulation-hotspots.md) | `features/optimization` | Enabled by default with independent startup flags. |
| [NPC actors, gameplay and persistence](npc-engine.md) | `features/npc`, `features/postgres/NpcPersistence` | Factory/actor graph and original-game hooks connected; scoped durable NPC SQL/sales coordinator added. Full behavioral, database and live-client acceptance remains open. |
| [Server Lua APIs and service workers](server-lua.md) | `features/lua` | Registered Lua/solar/generator/streaming/event/radio/API/login-audit hooks and async account/2FA/admin workers; original-JAR execution checks present, full live acceptance pending. |
| [Server Lua entity persistence](lua-mod-entities.md) | `features/lua/entities` | Wallet documents, generic and scoped repositories, character-backed entities and append-only messages. Exercised with original Lua and isolated PostgreSQL. |

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
