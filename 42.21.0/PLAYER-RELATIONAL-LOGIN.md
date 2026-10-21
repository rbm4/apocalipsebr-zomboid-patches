# Relational player reconstruction and asynchronous reads

Target: dedicated server, Project Zomboid 42.21.0.

`ServerPlayerDB.loadPostgresPlayer` now reads the active complete `player_rel` head through `ApocBRPlayerRelationalReader`, then constructs the engine player through `ApocBRPlayerRelationalReconstruction`. It does not read `public.networkplayers.data` or invoke `IsoPlayer.load`. SQLite loading keeps its native binary path. The exact-version baselines reviewed were `decompiled/zombie/savefile/ServerPlayerDB.java`, `characters/IsoPlayer.java`, `characters/IsoGameCharacter.java`, and the inventory, component, visual, health and animal loaders they invoke.

The reader uses the existing PostgreSQL connector and one prepared SQL statement for the entire graph. A materialized head selection scopes each table; a server-side reference map replaces graph UUID columns with integer ordinals in the returned JSON rows. Graph UUIDs remain in database joins; external dictionary namespace/view UUIDs are projected literally to preserve exact opaque-payload origins. Hashable joins to the reference map avoid a correlated reference scan for every returned field. Component byte payloads use hexadecimal transport; there is no whole-character binary serialization step. Embedded animal zone identifiers inside structured state still retain their semantic UUID string.

One statement also prevents mixing rows from different committed revisions, including live updates that mutate an existing snapshot. Under PostgreSQL Read Committed, a SELECT sees a snapshot as of the statement's start. See [PostgreSQL transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html).

The read rejects incompatible/incomplete heads, count mismatches and excessive graph sizes. Reconstruction rejects invalid ownership, unreachable items, cycles, missing registry entries and unsupported state. Read/reconstruction failures do not delete persistence rows or fall back to a stale native blob. A missing relational slot returns no player, following the caller's existing load-error handling. Existing data therefore needs a complete schema-1, codec-4 head before switching to PostgreSQL player mode.

Reconstruction restores character state, descriptor, stats, traits, XP, learned/read content, fitness, health, thermal state, visuals, nested inventory, subtype state, equipment, structured mod data and supported entity/animal state. It uses constructors, native setters and version-pinned field writes. Inventory links follow the native load path rather than executing gameplay item-add callbacks. Engine construction and final registration belong on the simulation thread.

Relational sync now also runs in PostgreSQL player mode, and reconstructed players register with its existing worker. This preserves the existing scalar sampling and graph reconciliation path after login. Keep `apocbr.postgres.playerRelational.enabled` and its mutation/reconciliation settings enabled when relying on relational state. Preserve `apocbr.postgres.playerRelational.sourceDatasetId` (or the original save-directory-derived dataset identity) across migration: worker receipt validation rejects a different source dataset.

## Current threading and latency

`ApocBRAsyncLogin` now suspends PostgreSQL login phases through `CompletableFuture` results on a dedicated two-thread JDBC executor. `LoginPacket` performs account/Steam-ban/owner admission reads on that executor, then resumes connection decisions on the simulation thread. Accepted account creation/password/last-connection/Steam-ID writes run in a second worker phase before the response. Worker account writes execute directly and preserve ordering within the login, rather than acknowledging an account that has only been queued for creation.

`GoogleAuthPacket` and `GoogleAuthKeyPacket` also defer their database work. Second-factor proofs require the matching successful first-factor login and expire after five minutes. Role/capability snapshots are captured on the simulation thread for workers; successful authentication resolves the current engine role again on completion. Username encoders are per invocation, and date formatting is synchronized.

The native Steam callbacks require immediate integer return values and run during the server loop. With PostgreSQL accounts they admit transport only, without JDBC. `LoginPacket` checks the Steam session, Steam ban and owner ban on its worker before granting client-connect; Google-key registration checks them too. Google-code authentication requires a matching prior login proof. No client protocol change is required.

`LoadPlayerProfilePacket` reads the detached relational graph and prepares source dictionaries on the JDBC executor. A fresh response object is built on the simulation thread using `buildPostgresClientProfile`; the reusable request handler is never retained. Player-connect copies the remaining packet bytes, reads a fresh graph asynchronously, then reconstructs/registers the player and performs world integration on the simulation thread. `ConnectPacket` sends initial world state and metadata only from the successful connection continuation, preserving their order after character attachment. Newly created players still use the durable `ApocBRCreatedPlayerHandoff` without another query. Existing persistence pending-player checks run before the graph read and again before reconstruction.

### Bounds and lifecycle

- Two daemon JDBC workers, at most 32 pending connection phases, and a bounded executor queue with rejection rather than caller-runs behavior.
- At most one pending phase per connection. Same-phase retransmissions are coalesced; a different phase while pending is denied.
- A worker retains its result capacity until the simulation consumes/discards it. At most two completed graphs can await integration.
- Eight-second absolute phase deadline, including queueing. Existing `loginWorkflowTimeoutMillis` / `loginNetworkTimeoutMillis` budgets are established inside each worker. Dictionary preparation uses those worker deadlines too. SQL failures caught inside vanilla account methods still mark the workflow failed.
- At most two continuations per tick; stop starting another after two milliseconds. One reconstruction is indivisible and can exceed that time. This does not promise stall-free character reconstruction or serialization.
- Completion validates the exact active connection object/GUID, primary username, identity string, Steam/owner IDs, world and delayed-disconnect state. Expired/disconnected requests release capacity and discard results. Future cancellation does not forcibly interrupt an already executing JDBC call; driver/query deadlines bound that work.
- Runtime PostgreSQL connection borrowing from the simulation thread fails promptly with SQLState `55000`. Startup still performs its required synchronous initialization. Shutdown cancels pending logins and stops login workers before the existing final database drain/role saves.

The defensive runtime gate also applies to synchronous admin/Lua callers that have not been converted to deferred requests: those calls fail instead of parking simulation. This change does not make their synchronous return contracts asynchronous. Mixed SQLite-account/PostgreSQL-player configurations can likewise fail if a synchronous account call reaches PostgreSQL; use PostgreSQL accounts with PostgreSQL characters for the fully deferred login path.

The vanilla client waits about ten seconds for profile loading. Queueing and query execution still affect the joining client's latency. There were no live database latency measurements; graph JSON parsing, reconstruction and serialization require representative runtime profiling.

`ApocBRPlayerRelationalReader.loadAsync(..., Executor)` remains available to other callers. The integrated login coordinator owns its futures, deadline scopes and completion lifecycle, using the synchronous detached reader inside its bounded workers.

## Verification and remaining runtime checks

- `python tools/test_postgres_async_login.py`: production coordinator under blocked workers, duplicate/out-of-order requests, stale sessions, failures, deadlines, bounded admission, phase chaining, bounded retained graphs and shutdown; extracted production `ConnectPacket.parse` verifies deferred world/metadata ordering.
- `python tools/test_postgres_server_database.py`: actual account/Steam/owner admission, snapshot roles, failures and connection leases.
- `python tools/test_postgres_player_reconstruction.py`: actual target-JAR scalar writes, captured structured-value reconstruction, native attributes/enum sets, ownership/cycle validation, JDBC lease cleanup, incompatible-head rejection, ordinal SQL projections and future-result handling.
- `python tools/test_postgres_players.py`: native save/profile protocol and server relational-load routing, dead players, coop identity and failure handling. Engine reconstruction is stubbed in this routing fixture and covered separately by the native-field/component fixture.
- `python tools/test_postgres_player_relational.py`: existing capture, mutation, graph-delta, size-budget and worker regressions.
- `./42.21.0/patchApocalipseBr.ps1 -DryRun`: compile all replacement classes against the actual 42.21.0 game JAR; no live deployment.

There was no live PostgreSQL query execution or full multiplayer login test. Validate the query plan and payload size against representative real inventories, including large nested bags. Exercise save/disconnect/immediate-reconnect ordering; complete versus incomplete and older-codec heads; death/respawn and split-screen/coop; registry/mod changes; equipped weapons and attachments; visual/body state; fluid filters, attribute variants, radio/media, map symbols and embedded animals. Native constructor side effects, world registration, Lua initialization and disconnect cleanup require an actual server runtime.

## Source dictionaries for opaque item state

Opaque value nodes now retain their exact server/namespace/view context. The reader prepares those dictionary views after releasing its graph connection; loadAsync includes this work on the supplied executor. Reconstructed byteData remains lazy, and the native item/object consumer selects its source dictionary when decoding. Unattributed data uses the native dictionary. See [decode integration](WORLD-DICTIONARY-DECODE.md) for covered dictionary decode boundaries.


## Validation of deferred login integration (2026-10-20)

The 42.21.0 patch script compiled all 171 replacement sources against the local
real game JAR with `-DryRun`; no game installation was deployed. These fixtures
passed: `test_postgres_async_login.py`, `test_postgres_server_database.py`,
`test_postgres_connector.py`, `test_postgres_players.py`,
`test_postgres_player_reconstruction.py`, `test_postgres_created_player_handoff.py`,
`test_postgres_resilience.py`, `test_postgres_dictionary_decode.py`,
`test_postgres_world_dictionary.py`, and `test_postgres_runtime_deployment.py`.
The routing fixture now checks the transmitted profile response rather than the
cached request handler's fields. No live multiplayer/PostgreSQL login or runtime
latency comparison was performed. Runtime trials must include Steam owner bans,
first/second-factor authentication, concurrent logins, disconnect during each
phase, outage/timeouts, immediate reconnect, new-character handoff and coop slots.
