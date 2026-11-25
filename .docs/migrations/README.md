# Feature migration workflow and status

## Decision: 2026-11-21

The user selected the original JAR plus Byte Buddy agent as the target for future
ports, replacing the previous requirement to support the patched production
classpath. Legacy sources remain reference evidence. This setup does not delete
those sources, remove installed overrides, deploy the agent, or port behavior.

## Starting status

The user subsequently selected the complete PostgreSQL feature, including
unfinished vehicle paths and the NPC gameplay runtime, as the active migration.
Use agent-owned adapters/state and keep game classes structurally vanilla.
The [feature contract](../features/postgresql.md) tracks implementation and gaps.

| Area | Status | Next bounded decision |
| --- | --- | --- |
| [Workshop check and restart hook](../features/server-lag-diagnostics.md) | Asynchronous query and continuation hooks verified against the original JAR and exercised with a Steam fixture | Validate actual query and restart behavior on a Steam-enabled dedicated server. |
| [Server telemetry](../features/server-lag-diagnostics.md) | Slow-tick diagnostics and method-level phase timers wired. Legacy inner-loop instrumentation remains partial. | Capture a representative lag spike and port remaining counters at their owning feature boundaries. |
| [Server packet diagnostics](../features/network-diagnostics.md) | Receive context and both server-only logging hooks ported for the original 42.22.2 JAR. | Confirm emitted summaries during a connected dedicated-server session. |
| [First five optimization ports](../features/server-optimizations.md) | Enabled by default with original-JAR hooks and packaged behavior fixtures validated | Measure under a representative dedicated-server workload. |
| [Client chunk-map cap](../features/client-chunk-map.md) | Default-off original-JAR sizing hook exercised in forked JVMs | Validate launcher startup, connected driving and far-zoom coverage. |
| [Server cell perimeter lookups](../features/server-cell-streaming.md) | Original-JAR perimeter hook with independent switch and playerless streaming validation | Measure under a connected workload before rollout. |
| [Cell unload scan optimization](../reports/2026-11-23-unload-scan-and-async-review.md) | Indexed ISO bucket removal and opt-in cell relevance exercised on the original 42.22.2 server | Measure connected-client churn and pending-save contention. |
| [Chunk integration and detachment](../reports/2026-11-22-chunk-lifecycle.md) | Original-JAR container checks and adjacency cleanup with independent startup switches | Break down warm object lifecycle callbacks and measure cell burst size under connected load. |
| [Parked vehicle cadence](../features/parked-vehicle-cadence.md) | Default-enabled original-JAR scheduler hook with an isolated paired benchmark | Validate connected driving, towing and modded vehicle workloads. |
| [Server simulation, LOS and zombie transmission](../features/server-simulation-hotspots.md) | Enabled by default for the original 42.22.2 JAR | Validate gameplay and measure live-server work under representative load. |
| [Java pathfinding lock](../features/java-pathfinding.md) | Original-JAR hooks implemented and verified in a forked JVM | Exercise routing and graceful shutdown in a running game. |
| PostgreSQL and NPC runtime | Module and account hooks registered; integrated migration still open | Complete journal/recovery/async, player/vehicle/dictionary and NPC contracts before full cutover. |
| [PostgreSQL dictionary setup](../features/postgresql-dictionary.md) | Publication/cache/listener and native decode hooks wired; live acceptance pending | Validate mixed historical payloads with real clients and engine-role SQL. |
| [PostgreSQL player authority](../features/postgresql-players.md) | Profile/connect, durable creation and live/final-save hooks implemented; defaults off | Complete real PostgreSQL/grant and connected-client acceptance before cutover. |
| Iso-object scheduling and thumpable no-work predicate | Suggested candidate, not started | Audit `IsoCell.ProcessIsoObject`, helpers and lifecycle dependencies before choosing a boundary. |
| Other optimizations, networking, persistence, NPCs and integrations | Not declared migrated | Select each feature and dependency closure in its own session. |
| [NPC actors, gameplay and persistence](../features/npc-engine.md) | Factory/actor graph, native hooks and durable NPC coordinator implemented; full port in progress | Close active-body, packet/authority semantics, isolated-database and client/runtime acceptance gaps. |
| [Server Lua APIs and service workers](../features/server-lua.md) | Hooks and targeted original-JAR execution implemented; live acceptance pending | Validate real mods/services and integrate the separately owned player reconstruction path; legacy Lua generator calls are retained through method-table adapters. |
| [RCON command for server Lua](../features/server-lua.md#rcon-command-for-server-lua) | Annotated command and Lua event wired through original-JAR hooks and covered by the packaged Lua smoke fixture | Verify with an authenticated RCON client on an empty dedicated server. |
| [Server Lua entity persistence](../features/lua-mod-entities.md) | Original Lua initialization and shared-pool integration exercised with isolated PostgreSQL | Validate with the full dedicated-server mod set and native player login. |

The candidate above is a recommendation, not approval to execute a queue.

## Per-feature workflow

1. Select the behavior and explicit exclusions. Read root entry points, modules,
   related advice, tests and documentation before editing.
2. Compare the exact legacy patched source with its same-build vanilla baseline.
   Separate optimization intent, telemetry, unrelated features and decompiler noise.
3. Trace direct callers, modified fields, server/client execution, thread ownership,
   queues, reuse, invalidation, unload and shutdown dependencies as applicable.
4. Choose safe original-JAR instrumentation boundaries. If no clear boundary exists,
   document the constraint and obtain direction before expanding scope.
5. Implement in the root Maven project, carrying relevant telemetry with the
   feature. Preserve vanilla behavior except for explicitly selected changes.
6. Exercise actual production code and transformed vanilla behavior, including
   continued original execution or deliberate skipping, exceptions and lifecycle.
7. Record exact JAR provenance, commands, results, runtime gaps and rollback.
   Mark implementation, artifact validation, deployment and runtime validation
   separately. Register the feature guide and evidence in these indexes.

Use the [per-feature template](feature-template.md). Do not copy complete legacy
replacement classes into the agent or treat custom-only sources as dependency-free.

## Evidence

See the [reports index](../reports/README.md) for the historical 42.22.2 inventory.
Its verifier currently stops on `Context file drift: pom.xml`; preserve the dated
snapshot and establish current provenance when selecting each feature.
