# Build 42.21.0 documentation

Current server development has moved to the [42.22.0 pre-release target](../../42.22.0/docs/README.md).
See the [class-by-class migration audit](../../42.22.0/docs/reports/2026-10-27-port-42-21-to-42-22.md)
before carrying over patches. This index and its guides retain their 42.21.0 scope.

This is the documentation index for the 42.21.0 patch target. Server work is the
default; diagnostic client features are identified in their titles and guides.

Feature guides describe maintained behavior and contracts. Reports preserve
investigations and measurements; they may contain proposals rather than shipped
behavior. Issues include diagnoses and resolutions, not only currently open bugs.

Filenames use `YYYY-MM-DD-topic.md` (or `.sql` for reference SQL). Dates below
are original Git addition dates recovered during the 2026-10-26 reorganization,
not claims about deployment dates or the last edit. Keep a maintained guide's
filename stable; record later changes/verification in its content. Add every new
document to the appropriate table, newest date first.

## Starting points

- [NPC architecture](features/2026-10-20-npc-engine-architecture.md)
- [NPC async worker and sync startup flag](features/2026-10-26-npc-async-worker.md)
- [NPC live-access and snapshot migration inventory](reports/2026-10-26-npc-async-snapshot-map.md)
- [Telemetry definitions](features/2026-10-01-telemetry.md)
- [Immutable async zombie sending](features/2026-10-12-async-zombie-sending.md)

The NPC simulation worker executes the existing live-world NPC tick and remains
experimental. The async zombie-sending pipeline uses a different ownership and
snapshot contract; the two features must not be treated as interchangeable.

## Features

| Date | Document |
| --- | --- |
| 2026-10-26 | [Server Lua entity bridge scaffold](features/2026-10-26-lua-mod-entities.md) |
| 2026-10-26 | [Offline SQLite character migration](features/2026-10-26-offline-player-migration.md) |
| 2026-10-26 | [NPC dialogue roster refresh after respawn](features/2026-10-26-npc-dialogue-roster-refresh.md) |
| 2026-10-26 | [NPC speech engine (42.21.0 server)](features/2026-10-26-npc-speech-engine.md) |
| 2026-10-26 | [NPC death, corpse loot and respawn (42.21.0, V30)](features/2026-10-26-npc-death-respawn-loot.md) |
| 2026-10-26 | [Experimental NPC simulation worker](features/2026-10-26-npc-async-worker.md) |
| 2026-10-25 | [Player retaliation (42.21.0, server only)](features/2026-10-25-npc-player-retaliation.md) |
| 2026-10-25 | [Player encounters and affiliation memory (42.21.0)](features/2026-10-25-npc-player-encounters.md) |
| 2026-10-24 | [Native NPC corpse dragging](features/2026-10-24-npc-corpse-grapple.md) |
| 2026-10-24 | [Dialogue-assigned NPC tasks](features/2026-10-24-npc-tasks.md) |
| 2026-10-23 | [Client zombie movement capture — 42.21.0](features/2026-10-23-client-zombie-sampler.md) |
| 2026-10-22 | [Tactical combat and escape coordination](features/2026-10-22-npc-tactics.md) |
| 2026-10-22 | [Server-controlled zombie interactions](features/2026-10-22-npc-zombie-control.md) |
| 2026-10-21 | [Successful-login history and lobby teleport](features/2026-10-21-player-login-origin.md) |
| 2026-10-21 | [Server human obstacle traversal](features/2026-10-21-npc-traversal.md) |
| 2026-10-21 | [Server human fight or flight](features/2026-10-21-npc-survival.md) |
| 2026-10-21 | [NPC state store, reducers and condition projections](features/2026-10-21-npc-state.md) |
| 2026-10-21 | [NPC perception and short-term contact memory](features/2026-10-21-npc-perception.md) |
| 2026-10-21 | [NPC movement engine, navigation and walking patterns](features/2026-10-21-npc-movement.md) |
| 2026-10-21 | [Initial weapon combat backend](features/2026-10-21-npc-combat.md) |
| 2026-10-21 | [Engagement policy and combat handoff scaffold](features/2026-10-21-npc-engagement.md) |
| 2026-10-21 | [Cached NPC dialogue graphs (42.21.0)](features/2026-10-21-npc-dialogue-graphs.md) |
| 2026-10-20 | [Server-owned NPC dialogue ? 42.21.0](features/2026-10-20-npc-dialogue.md) |
| 2026-10-20 | [NPC profiles, activity ownership and logical lifecycle](features/2026-10-20-npc-profiles-lifecycle.md) |
| 2026-10-20 | [NPC modules and PostgreSQL persistence (42.21.0 server)](features/2026-10-20-npc-persistence.md) |
| 2026-10-20 | [NPC engine object-oriented architecture](features/2026-10-20-npc-engine-architecture.md) |
| 2026-10-20 | [Lobby Event Metadata (reference SQL)](features/2026-10-20-lobby-event-metadata.sql) |
| 2026-10-20 | [Human Sales Money Bundles (reference SQL)](features/2026-10-20-human-sales-money-bundles.sql) |
| 2026-10-19 | [Server-owned humans — 42.21.0](features/2026-10-19-server-humans.md) |
| 2026-10-19 | [PostgreSQL lobby event control — 42.21.0](features/2026-10-19-lobby-event-control.md) |
| 2026-10-19 | [Lobby Event Control (reference SQL)](features/2026-10-19-lobby-event-control.sql) |
| 2026-10-19 | [Human Sales Schema (reference SQL)](features/2026-10-19-human-sales-schema.sql) |
| 2026-10-17 | [Startup and shutdown persistence retries (42.21.0 server)](features/2026-10-17-persistence-shutdown.md) |
| 2026-10-17 | [Runtime replay failure isolation (42.21.0 server)](features/2026-10-17-persistence-runtime.md) |
| 2026-10-17 | [Player-scoped item lifecycles (42.21.0 server)](features/2026-10-17-persistence-player-items.md) |
| 2026-10-15 | [World Dictionary Schema (reference SQL)](features/2026-10-15-world-dictionary-schema.sql) |
| 2026-10-15 | [V6/V7 dictionary publication and change listener — 42.21.0](features/2026-10-15-world-dictionary-mirror.md) |
| 2026-10-15 | [Shared world dictionary: backend migration handoff](features/2026-10-15-world-dictionary-schema.md) |
| 2026-10-15 | [Relational player reconstruction and asynchronous reads](features/2026-10-15-player-relational-login.md) |
| 2026-10-15 | [Player-attached payload dictionary selection — 42.21.0](features/2026-10-15-world-dictionary-decode.md) |
| 2026-10-15 | [Player mutation coverage and measurements — 42.21.0](features/2026-10-15-player-relational-mutation-coverage.md) |
| 2026-10-14 | [PostgreSQL JDBC dependency for 42.21.0](features/2026-10-14-postgresql-connector.md) |
| 2026-10-14 | [Player relational persistence contract — 42.21.0](features/2026-10-14-player-relational-schema.md) |
| 2026-10-13 | [Engine observation bridge: 42.21.0 server](features/2026-10-13-backend-bridge.md) |
| 2026-10-12 | [Immutable asynchronous zombie sending (42.21.0 server)](features/2026-10-12-async-zombie-sending.md) |
| 2026-10-01 | [Server telemetry (schema 3)](features/2026-10-01-telemetry.md) |

## Reports

| Date | Document |
| --- | --- |
| 2026-10-26 | [Player validation follow-up: 76 verified, Trelai clothing and dynamic Moveable failures](reports/2026-10-26-player-validation-follow-up.md) |
| 2026-10-26 | [Local player validation: sparse equipment positions, magazine callbacks and compatibility failures](reports/2026-10-26-player-validation-local-analysis.md) |
| 2026-10-26 | [NPC worker: live-access inventory and snapshot boundary](reports/2026-10-26-npc-async-snapshot-map.md) |
| 2026-10-21 | [NPC physical capabilities, combat and relationships](reports/2026-10-21-npc-physical-capabilities-and-combat.md) |
| 2026-10-21 | [Militia patrol, zombie perception and combat design](reports/2026-10-21-npc-militia-patrol-and-threat-perception.md) |
| 2026-10-21 | [A-life combat implementation map and our IsoPlayer roadmap](reports/2026-10-21-npc-alife-combat-implementation-map.md) |
| 2026-10-20 | [Installed Project A-Life runtime and persistence comparison](reports/2026-10-20-npc-alife-runtime-investigation.md) |
| 2026-10-17 | [Vanilla item identity investigation (42.21.0)](reports/2026-10-17-item-identity-investigation.md) |
| 2026-10-15 | [World dictionary PostgreSQL integration map — 42.21.0](reports/2026-10-15-world-dictionary-integration-map.md) |
| 2026-10-15 | [Origin server and dictionary context for binary payloads](reports/2026-10-15-world-dictionary-provenance-handoff.md) |
| 2026-10-05 | [Vehicle update follow-up, 42.21.0](reports/2026-10-05-vehicle-update-followup.md) |
| 2026-10-05 | [Unload and simulation algorithm review: sequences 327–336](reports/2026-10-05-unload-simulation-algorithm-review.md) |
| 2026-10-05 | [Player updates and zombie packet preparation: 42.21.0 investigation](reports/2026-10-05-player-zombie-packet-hotspots.md) |
| 2026-10-05 | [Audited array access and explicit mutation notification, 2026-10-05](reports/2026-10-05-iso-entity-registry-reconciliation.md) |
| 2026-10-04 | [Production hot spots: first optimized 42.21.0 snapshots](reports/2026-10-04-production-hotspots.md) |
| 2026-10-03 | [simulation.update algorithm review](reports/2026-10-03-simulation-optimization-review.md) |

## Issues

| Date | Document |
| --- | --- |
| 2026-10-26 | [NPC collision scratch race and per-thread helpers](issues/2026-10-26-npc-collision-scratch-race.md) |
| 2026-10-24 | [NPC corpse grapple compatibility assessment](issues/2026-10-24-npc-corpse-grapple-compatibility.md) |
| 2026-10-19 | [PostgreSQL character reconstruction diagnostics (42.21.0)](issues/2026-10-19-player-reconstruction-diagnostics.md) |
| 2026-10-17 | [Player capture failures and replay rejection (42.21.0 server)](issues/2026-10-17-player-capture-diagnostics.md) |
