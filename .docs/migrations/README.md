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
| Agent foundation and Workshop hook | Existing implementation | Validate the new original-JAR launch in the intended runtime. |
| Basic telemetry | Partial implementation | Preserve measured semantics as individual features add phases/counters. |
| PostgreSQL and NPC runtime | Module and account hooks registered; integrated migration still open | Complete journal/recovery/async, player/vehicle/dictionary and NPC contracts before full cutover. |
| [PostgreSQL dictionary setup](../features/postgresql-dictionary.md) | Publication and paginated foreign-view cache/listener wired into original startup; live acceptance pending | Port payload provenance/native decode hooks before enabling player reconstruction. |
| Iso-object scheduling and thumpable no-work predicate | Suggested candidate, not started | Audit `IsoCell.ProcessIsoObject`, helpers and lifecycle dependencies before choosing a boundary. |
| Other optimizations, networking, persistence, NPCs and integrations | Not declared migrated | Select each feature and dependency closure in its own session. |
| [NPC actors, gameplay and persistence](../features/npc-engine.md) | Factory/actor graph, native hooks and durable NPC coordinator implemented; full port in progress | Close active-body, packet/authority semantics, isolated-database and client/runtime acceptance gaps. |
| [Server Lua APIs and service workers](../features/server-lua.md) | Hooks and targeted original-JAR execution implemented; live acceptance pending | Validate real mods/services and integrate the separately owned player reconstruction path; legacy Lua generator calls are retained through method-table adapters. |

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
