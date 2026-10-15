# Relational player reconstruction and asynchronous reads

Target: dedicated server, Project Zomboid 42.21.0.

`ServerPlayerDB.loadPostgresPlayer` now reads the active complete `player_rel` head through `ApocBRPlayerRelationalReader`, then constructs the engine player through `ApocBRPlayerRelationalReconstruction`. It does not read `public.networkplayers.data` or invoke `IsoPlayer.load`. SQLite loading keeps its native binary path. The exact-version baselines reviewed were `decompiled/zombie/savefile/ServerPlayerDB.java`, `characters/IsoPlayer.java`, `characters/IsoGameCharacter.java`, and the inventory, component, visual, health and animal loaders they invoke.

The reader uses the existing PostgreSQL connector and one prepared SQL statement for the entire graph. A materialized head selection scopes each table; a server-side reference map replaces graph UUID columns with integer ordinals in the returned JSON rows. Graph UUIDs remain in database joins; external dictionary namespace/view UUIDs are projected literally to preserve exact opaque-payload origins. Hashable joins to the reference map avoid a correlated reference scan for every returned field. Component byte payloads use hexadecimal transport; there is no whole-character binary serialization step. Embedded animal zone identifiers inside structured state still retain their semantic UUID string.

One statement also prevents mixing rows from different committed revisions, including live updates that mutate an existing snapshot. Under PostgreSQL Read Committed, a SELECT sees a snapshot as of the statement's start. See [PostgreSQL transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html).

The read rejects incompatible/incomplete heads, count mismatches and excessive graph sizes. Reconstruction rejects invalid ownership, unreachable items, cycles, missing registry entries and unsupported state. Read/reconstruction failures do not delete persistence rows or fall back to a stale native blob. A missing relational slot returns no player, following the caller's existing load-error handling. Existing data therefore needs a complete schema-1, codec-4 head before switching to PostgreSQL player mode.

Reconstruction restores character state, descriptor, stats, traits, XP, learned/read content, fitness, health, thermal state, visuals, nested inventory, subtype state, equipment, structured mod data and supported entity/animal state. It uses constructors, native setters and version-pinned field writes. Inventory links follow the native load path rather than executing gameplay item-add callbacks. Engine construction and final registration belong on the simulation thread.

Relational sync now also runs in PostgreSQL player mode, and reconstructed players register with its existing worker. This preserves the existing scalar sampling and graph reconciliation path after login. Keep `apocbr.postgres.playerRelational.enabled` and its mutation/reconciliation settings enabled when relying on relational state. Preserve `apocbr.postgres.playerRelational.sourceDatasetId` (or the original save-directory-derived dataset identity) across migration: worker receipt validation rejects a different source dataset.

## Current threading and latency

The existing login SELECT is synchronous. `GameServer` drains normal packets in its main loop; `receivePlayerConnect` calls `ServerPlayerDB.serverLoadNetworkCharacter` before adding the player to connection/world maps. The `ApocBR-player-relational` worker handles synchronization writes and backfill, not this SELECT. The new synchronous reader preserves that caller contract for this first implementation.

Current local connector limits are a 2,000 ms pool acquisition timeout, 3 s driver connection timeout, 3 s socket timeout, and a 2 s statement timeout on the new query. These are separate limits, not a single end-to-end login deadline. Normal pool wait, query planning/execution, network transfer, JSON decoding and reconstruction all contribute to login latency; the synchronous call currently parks simulation during that work. No live-database latency or bandwidth measurements were taken. Compact references remove UUID repetition, but JSON field names, parsing, database planning and reference-map work must still be measured before claiming an overall throughput improvement.

`ApocBRPlayerRelationalReader.loadAsync(..., Executor)` is implemented and returns a `CompletableFuture<Profile>`. It runs blocking JDBC on the supplied executor and returns only detached immutable rows after returning the connection lease. This API is available for the next change; the login caller does not use it yet.

## Reusable SELECT pattern for the next change

1. On the simulation thread, validate the packet, copy its remaining login fields, and create a pending request keyed by connection generation and player slot. Do not retain the pooled packet/ByteBuffer or an engine player for the JDBC worker.
2. Submit `reader.loadAsync(world, identity, slot, coop, boundedJdbcExecutor)`. Use an explicit executor with a bounded queue, rejection handling and concurrency sized against the shared pool. Do not use a caller-runs rejection policy, which would execute JDBC on the simulation thread under pressure. Keep login reads separate from the existing reconciliation queue so large exports do not serialize logins.
3. Put success/failure in a bounded completion queue. In the main loop, drain a bounded number/time budget of completions, check that the same connection generation remains connected and authenticated, and that its slot is still empty. Ignore disconnected, expired or superseded requests.
4. Construct the player, apply server-side role/flags, and run the remainder of `receivePlayerConnect` on the simulation thread. Account for overlapping creation, disconnect, reconnect and duplicate packets. Never call `join()` or `get()` while waiting in the simulation loop.
5. Apply an end-to-end request deadline and release pending-request capacity on every exit. Future timeout/cancellation alone does not stop JDBC: retain statement/socket deadlines and, where needed, explicitly cancel the statement. Do not let a late completion reconnect a timed-out session. Shutdown must stop accepting requests, settle pending requests and return all pool leases.

An ordinary `thenAccept`/`whenComplete` may execute on the thread completing the future, so it should only enqueue detached results; it must not mutate the game world. Async methods without an explicit executor use the common pool. See the [Java 25 CompletableFuture contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletableFuture.html). JDBC result sets and statements stay inside their owning worker lease; see [pgJDBC query handling](https://jdbc.postgresql.org/documentation/query/).

The same split can be reused for other remote SELECT operations: detached request, bounded JDBC executor, detached result, bounded main-thread integration. The native `LoadPlayerProfilePacket` still uses `ApocBRPlayerDatabase.load` and native bytes for the client-facing profile protocol; it is a separate SELECT/call site to include in a later complete asynchronous-login audit. Native snapshot writes and relational reconciliation remain separate transactions with existing sampling/queue delay. A read-after-save barrier for immediate reconnect and shutdown durability is also a separate required consideration before treating the relational head as strongly current.

## Verification and remaining runtime checks

- `python tools/test_postgres_player_reconstruction.py`: actual target-JAR scalar writes, captured structured-value reconstruction, native attributes/enum sets, ownership/cycle validation, JDBC lease cleanup, incompatible-head rejection, ordinal SQL projections and future-result handling.
- `python tools/test_postgres_players.py`: native save/profile protocol and server relational-load routing, dead players, coop identity and failure handling. Engine reconstruction is stubbed in this routing fixture and covered separately by the native-field/component fixture.
- `python tools/test_postgres_player_relational.py`: existing capture, mutation, graph-delta, size-budget and worker regressions.
- `./42.21.0/patchApocalipseBr.ps1 -DryRun`: compile all replacement classes against the actual 42.21.0 game JAR; no live deployment.

There was no live PostgreSQL query execution or full multiplayer login test. Validate the query plan and payload size against representative real inventories, including large nested bags. Exercise save/disconnect/immediate-reconnect ordering; complete versus incomplete and older-codec heads; death/respawn and split-screen/coop; registry/mod changes; equipped weapons and attachments; visual/body state; fluid filters, attribute variants, radio/media, map symbols and embedded animals. Native constructor side effects, world registration, Lua initialization and disconnect cleanup require an actual server runtime.

## Source dictionaries for opaque item state

Opaque value nodes now retain their exact server/namespace/view context. The reader prepares those dictionary views after releasing its graph connection; loadAsync includes this work on the supplied executor. Reconstructed byteData remains lazy, and the native item/object consumer selects its source dictionary when decoding. Unattributed data uses the native dictionary. See [decode integration](WORLD-DICTIONARY-DECODE.md) for covered boundaries and remaining client-protocol/synchronous-login limits.
