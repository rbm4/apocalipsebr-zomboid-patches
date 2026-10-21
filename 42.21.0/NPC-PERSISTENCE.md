# NPC modules and PostgreSQL persistence (42.21.0 server)

Implemented foundation; compile and isolated tests pass. Dedicated-server validation is still required. No manager controllers, services or views are included. Migration: `apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V15__Create_Npc_Engine.sql`.

Physical/needs state additionally requires `V20__Persist_Npc_Physical_State.sql`. See [NPC-STATE.md](NPC-STATE.md) for the single-writer reducer, native bridge and condition projection.

## Ownership and flow

```text
Database profiles -> NPC worker -> immutable configuration -> simulation-thread registry
Logical NPC (scope + npc_key) -> lifecycle -> activity ownership -> module arbitration
                                                        -> action executor -> capability port -> IsoPlayer
Simulation mutations -> detached checkpoint -> NPC worker -> shared local journal -> PostgreSQL replay worker
```

`IsoPlayer` is a replaceable presentation. NPC identity, lifecycle, activation count, last bound profile revision and position belong to the NPC engine. There is no account, connection, player-save graph or player death/respawn persistence for these actors. Presentation online IDs are neither stored nor restored. Native entity disposal never deletes logical NPC state.

Outer lifecycle remains `DORMANT -> SPAWNING -> ACTIVE -> DORMANT`, with `ACTIVE -> DEAD`. A restored ACTIVE/SPAWNING record is first normalized to DORMANT, then activated with a fresh runtime/presentation if permitted by `desired_active`. DEAD remains terminal. Persisted DORMANT stays dormant. Removal writes a tombstone; retired keys and usernames are reserved rather than resurrected implicitly. `desired_active=false` suppresses startup activation without deleting state. Instance configuration is loaded at startup, not continuously reconciled.

## Shared module runtime

`HumanModuleRegistry` owns allowlisted module implementations and validators. `HumanProfile` is immutable data: module bindings, priorities, eligibility cycle intervals, module parameters, perception radius/sample interval/LOS policy, speech provider IDs and dialogue/trade permissions. Database rows never contain executable Java or Lua. The database module catalog describes implementations supplied by this engine version; adding a catalog row does not install a capability.

`HumanModuleRuntime` is common to every modular profile. It samples eligible candidates in ascending priority, breaking ties by module ID. Eligibility is a pure read of observation/module memory. Only the winner may actuate. A higher priority module cancels the old owner's action before starting. Selection cycles are per module; a selected module advances at the engine's 100 ms simulation cadence so movement/attention and cancellation remain responsive. The scheduler checks at most 16 modules per NPC. Exceptions are contained by `HumanActivityController`, which cancels the runtime and cleans every module.

Current registered modules:

| Module | Default priority | Responsibility | Configuration |
| --- | --- | --- | --- |
| `greet` | 100 | Select a visitor, face, hold attention, wave, say greeting, announce ordered phrases, return and cool down | attentionMs, salutationMs, cooldownMs, visitorCooldownMs, announcementPauseMs |
| `roam` | 1000 | Safe indoor route while no higher priority module owns control | roamPauseMs |

The existing tested `GuideBrain` supplies the greeting cycle through `greetingOnly()`; it no longer owns roaming in modular profiles. Its legacy combined behavior ID remains available for source compatibility. Lobby guide and roaming greeter built-ins now use the shared module pipeline. Database stationary profiles have no modules; the legacy Java stationary built-in remains IdleBehavior. Per-visitor greeting history and action execution are independent for each NPC.

Perception samples connected visible real players within the profile radius (default 8, maximum 32) on its configured cycle (default 100 ms); snapshots contain at most the nearest 128 visitors. Cached perception may be up to one sampling interval old. Action authority (`face`, dialogue and trade validation) resolves live identity/range/visibility independently. Dialogue/trade acquire exclusive activity ownership and suspend every autonomous module. Availability blocking cancels active movement/emotes and refuses new leases.

Future running, weapon use, zombie attraction or threat reactions must register reusable policy modules and implement real primitive action/capability ports, with success/failure/cancellation and authority checks. They are not implemented by this foundation. High priority reaction modules can preempt lower priority work through the same scheduler; do not fork the engine per bot role. Extend observation with detached threat facts and persist semantic memory through explicit codecs when those features exist. Do not write world scans, SQL or role-specific checks into generic executors/actuators. See the mandatory [architecture contract](NPC-ENGINE-ARCHITECTURE.md).

## Persistence contract and limits

The game thread samples logical/presentation values every 30 seconds by default, compares them to the last immutable checkpoint and offers only changed snapshots. Configure `-Dapocbr.npc.persistence.checkpointMs=30000` at JVM startup to change this interval (clamped to 1000..60000ms). This matches the default player relational scalar sampling interval; physical simulation and debug mutation logs still run at their existing cadence. Activation, dormancy, permanent removal and death checkpoint immediately. Dormant registrations are captured at the next checkpoint. The worker coalesces by logical key and monotonically increasing mutation revision (at most 256 pending keys). JDBC never runs on the simulation thread. No SQL is generated for unchanged checkpoints.

The NPC worker stages two trusted parameterized UPSERT statements as an existing `ApocBRPersistenceOperations.Account` operation. Thus NPC writes use the existing ordered local disk journal, transaction receipt, retry, replay and shutdown machinery without entering player tables. SQL writes both definition-if-absent and runtime state atomically, including V20 physical/needs columns. Configuration already maintained in the database is not overwritten by checkpoints. Runtime fields are engine-owned; do not edit them in a profile-maintenance form.

One retained worker connection holds a PostgreSQL advisory lock for `(server_key,world)`. The process obtains a monotonically increasing scope epoch on initial load. Checkpoint statements acquire a shared scope-row lock and verify that epoch; new ownership must wait for in-flight writes. Stale/duplicate mutation revisions and writes from old epochs cannot overwrite newer state, including late journal replay. Reconnect reacquires the session lock only if the epoch remains unchanged. Another owner or changed epoch stops automatic recovery; restart/reload is required. The worker explicitly releases session locks before returning its connection to the pool.

Availability is checked through the shared journal and scope heartbeat. When unhealthy, the simulation pauses autonomous actions, closes exclusive leases and blocks new ones; a normal reconnect under the same epoch resumes policies. Worker errors log exception type/SQLSTATE only, without JDBC credentials or bound values. Invalid initial profile/identity batches reject NPC startup; fix configuration and restart. Invalid later profile batches retain the last accepted registry. The local journal keeps its existing queue limits, disk staging, quarantine and shutdown discard policy; it is not unlimited storage.

Shutdown stops the NPC producer and stages remaining detached checkpoints before draining/closing the shared journal. This drains already captured snapshots; it does not capture fresh native actors on the shutdown worker. Routine movement and physical mutations since the last simulation checkpoint (up to roughly 30 seconds by default), plus any values not yet captured/staged, can be lost on a crash or shutdown without a final lifecycle checkpoint. This is snapshot persistence, not a journal entry for every movement step. Greetings, current targets, open dialogue/trade leases, native animation state and monotonic timers deliberately reset on activation/restart. `runtime_state.memory` is a bounded versioned extension point only; v1 has no durable module-memory codec. V20 persists typed physical/needs state. Inventory, weapons and relationships remain future extensions.

Limits: 256 logical records including tombstones on load, 256 published profiles, 16 modules per profile, 16 parameters per module, 64 native presentation IDs over one process lifetime. Deactivate/reactivate consumes another presentation ID (existing transport safety constraint).

## Enable and bootstrap

1. Apply V15 through the manager's game-runtime Flyway datasource. Create login `pz_server_01` before migration; the migration intentionally fails if it is missing. The normal migration owner is `apocalipsebr` and must have CREATE in game_runtime and permission to grant its new objects. Grants and creator default privileges are included for schema, tables and sequences.
2. Deploy the 42.21.0 server patch separately. This implementation has not deployed it.
3. Keep the existing PostgreSQL connector/journal settings and add `-Dapocbr.npc.persistence.enabled=true`. At least one existing PostgreSQL server/player persistence feature must enable the shared journal. No new credentials or pool are introduced.
4. Set `-Dapocbr.npc.serverKey=apoc-extraction` (default GameServer.serverName); world is `Core.gameSaveWorld`. Keep the same scope on subsequent boots.
5. For first boot, keep your existing `-Dapocbr.guide.enabled=true` and name/coordinates/outfit/profile settings. The worker inserts this actor only if `(scope,'guide')` is absent; existing database configuration wins. The migration seeds the guide PROFILE, not a globally active NPC at an assumed server/world. Subsequent boots restore the database actors even if JVM guide bootstrap is disabled.

With persistence disabled, existing JVM bootstrap works in memory using the same modular behaviors. Profiles are not silently loaded from a second datasource, and persistence-enabled startup does not fall back to a duplicate JVM guide while SQL is unavailable.

## Maintenance transactions and validation

`profile` holds a stable key and current revision pointer; `profile_revision` holds flags/perception/content metadata; `profile_module` holds enabled/priority/cycle; `profile_parameter` holds typed millisecond values. `module_catalog` and `module_parameter` provide known modules, parameter names/defaults/ranges for a future form. Defaults describe shipped code; existing profiles explicitly seed their own values. Changing catalog metadata does not install code or change a published profile.

Published revisions and all their bindings/parameters are immutable. A form should create a new revision, copy/edit its modules and parameters, then update `profile.current_revision` in the same transaction. The trigger serializes publication against child edits; pointers cannot go backwards. A newly published profile is polled within approximately 30 seconds. Active actors retain their bound immutable profile until deactivation/reactivation; no mid-action hot swap. Backend edits of actor identity/home/desired_active apply on restart. Do not delete referenced profiles or change logical keys.

Verification: `tools/test_npc_runtime.py` exercises scheduling, priority/cycle bounds, preemption, interaction/availability gates, instance isolation and restored logical lifecycle. `tools/test_npc_persistence.py` starts an isolated temporary PostgreSQL cluster (set APOCBR_TEST_POSTGRES_BIN), applies V15 as apocalipsebr, queries as pz_server_01 and tests grants, publication immutability, mutation ordering, epoch fences, worker load/coalescing/shutdown and real session loss/reconnect. Existing dialogue/trading/roaming and shared journal resilience fixtures remain required, plus `42.21.0/patchApocalipseBr.ps1 -DryRun` against the real game JAR. Tests never connect to the production database.
