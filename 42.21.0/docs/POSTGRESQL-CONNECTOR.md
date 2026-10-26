# PostgreSQL JDBC dependency for 42.21.0

Recorded-media capture and reconstruction use the vanilla 42.21.0 instance API
`ZomboidRadio.getInstance().getRecordedMedia()`. Calling `getRecordedMedia` as a
static method caused `LIVE_GRAPH_CAPTURE_FAILED` for nested items such as VHS
`ComboItem`s with a nonnegative media index, and also prevented reconstruction of
saved media IDs. Items without media still bypass lookup. Unknown indices/IDs
remain explicit failures rather than being silently removed. The mod-data/media
fixture covers nested item capture, stable media IDs in JDBC, reconstruction,
and missing-media failures; the native relational fixture checks method modifiers
against the exact game JAR.

Player-scoped native-ID continuity, lifecycle removal, journal identity metadata,
and external-reference policy are described in
[player item lifecycles](docs/persistence-player-items.md).

## Outage resilience: accounts and players

The connector owns an ordered local journal and two daemon workers: one flushes
detached operations to disk, the other replays them into PostgreSQL. Simulation
continues during a database outage. New login, second-factor, profile-load,
character-create and player-connect requests receive the existing `AccessDenied`
response while recovery/backlog work remains. There is no SQLite fallback.
Vehicles retain their existing persistence behavior and are not journaled here.

**Provision before deploying:** apply
[`tools/postgres-persistence-receipt.sql`](tools/postgres-persistence-receipt.sql)
through the backend migration process. The game never runs this DDL. Every replayed
mutation claims its operation UUID in `public.apocbr_persistence_receipt` in the
same transaction as its data changes. This prevents duplicate execution after a
lost commit response or restart before the local acknowledgement. Keep receipts
for as long as any journal/backup could be replayed; restoring PostgreSQL without
its matching receipts invalidates this guarantee.

Startup waits for PostgreSQL and replays the journal **before** account bootstrap,
role initialization and login admission. Missing receipt schema or invalid
credentials stop startup with a migration/configuration error. Runtime states are
`STARTING`, `AVAILABLE`, `UNAVAILABLE`, `RECOVERING`, `DRAINING`, `CLOSED`. Recovery
probes back off from 250 ms to 30 seconds during normal runtime. Connection failures
retain the head for recovery. Operation-specific runtime failures are durably
quarantined so unrelated replay, logins and account commands can continue; see
[runtime failure isolation](docs/persistence-runtime.md). SQLState classes `22`/`23`
are quarantined immediately; other non-connectivity failures get five attempts.
Only the affected player's reload stays blocked until a newer complete capture
commits. Startup and shutdown use the five-attempt discard policy described below. Startup
still requires a successful database/receipt-schema probe before executing entries;
failed probes do not count as attempts or discard journal data. Login stays closed until recovery work drains. Player
profile/creation/connection phases also check that identity/slot for staged
writes and unsent relational mutations. Routine healthy-server writes do not close
admission for unrelated identities.

Account/player foreground calls keep their synchronous contracts. Pool acquisition
uses a maximum 250 ms foreground wait. Ordinary foreground socket operations use
at most 250 ms; connection packet phases use a separate 3,000 ms socket cap and
share a 5,000 ms workflow deadline across nested calls. The login socket cap is
longer than the graph statement's two-second query timeout, avoiding the previous
250 ms socket abort during otherwise healthy profile reads.
Set `-Dapocbr.postgres.loginWorkflowTimeoutMillis=<milliseconds>` to tune that total
budget (clamped to 500..30,000 ms). Set
`-Dapocbr.postgres.loginNetworkTimeoutMillis=<milliseconds>` to tune the login
socket cap (clamped to 250..30,000 ms, and always limited by the remaining workflow
deadline). Pool acquisition retains its 250 ms cap even in login scopes. Nested
calls inherit the outer scope's deadline and socket cap; neither scope type can
extend an existing workflow. Other foreground account workflows retain a
500 ms deadline. A local workflow expiration reports `HYT00` and
`DeadlineExceededException`; it aborts the request without marking the whole
connector unavailable. Actual transport failures still close admission for
recovery. `08006` still indicates a failed connection; it is not ignored or
reclassified just because a foreground read failed. `FOREGROUND_JDBC_FAILURE`
adds the configured socket cap and deepest exception type, without driver
messages, SQL, credentials or player values, so a subsequent socket timeout can
be distinguished from other connection failures. Account errors include the JDBC
method and sanitized diagnostic fields.
Login still runs on the server thread: the larger socket budget permits longer
individual calls and cumulative tick stalls. This is a scoped tolerance fix, not
asynchronous login or a hard real-time guarantee. Subsequent calls fail before borrowing while the
connector is unavailable/recovering. Background writes retain normal pool/socket
budgets. Vehicle and dictionary leases bypass foreground admission/deadlines.

The `ServerPlayersVehicles` I/O thread also runs in an explicit background scope,
so lazy player-database validation and persistence work use normal pool/socket
budgets during recovery. Its 500 ms pacing runs after successful and failed
iterations. Repeated worker failures are logged at most once per 30 seconds, with
`PLAYER_VEHICLE_WORKER_RECOVERED` after the next successful iteration. SQLState
`08001` during initialization reports connection availability rather than claiming
that a migration is missing; missing-table/column states retain the schema hint.

The default journal location is
`<Zomboid saves>/Multiplayer/<serverName>/postgres-journal`; override it with
`-Dapocbr.postgres.journalDirectory=<absolute-path>`. Keep this directory persistent
and private to the server: operations include player data and account updates.
Only one process can own a journal. It contains version-one serialized detached
operations, UUIDs, timestamps, CRC32C records and an atomically replaced checkpoint.
Replay preserves FIFO/transaction ordering around isolated rejected entries. It truncates incomplete final appends
on restart, rejects corrupt complete records, and rotates drained generations.
Do not edit or remove pending journal files to unblock startup.

RAM staging is limited to 256 operations/64 MiB of estimated input. A dedicated
writer flushes every 25 ms; overflow spills to local disk on the submitting thread,
without waiting for PostgreSQL. Large captures, serialization and filesystem I/O
can still consume time. Records are durable only after file flush; native captures,
dirty/coalesced state and operations still staged in RAM can be lost on forced
termination. Disk is assumed sufficient. An I/O failure is reported and never
acknowledges unsaved work; no disk-capacity management policy is implemented.

Journaled operations include native character snapshots, initial relational
captures/new lives, live scalar/graph batches, player-store conversion/deletion,
and independent account writes. Relational captures are saved **before** projection
because alias reads need PostgreSQL. Serialization preserves object identity and
opaque byte-payload provenance. Initial captures do not expire during an outage.
Periodic captures still coalesce before journal handoff; this is state persistence,
not an audit history of every intermediate simulation mutation. Session registration
refuses replacement while prior mutations remain pending.

Independent account writes include allow-list changes, explicit password/display
updates, 2FA reset, last-connection timestamps, log aggregation/deletion, tickets,
IP/Steam bans, existing role/default-role updates and capability replacement. String-returning queued methods return
`Database update queued; pending persistence`, never a committed-success result.
Void methods retain their interfaces. Operations requiring authorization/existence
reads or generated IDs remain synchronous and fail unavailable for retry. For
example, new account/role creation, password verification, role reassignment and
2FA registration are not accepted blindly during an outage. Deferred player-store
conversion returns `-1` because its eventual affected-row count is unavailable.

Graceful shutdown queues final player samples, closes admission, waits before
synchronous role saving, then drains relational work before closing workers and
the shared pool. During startup replay and `DRAINING`, each journal operation gets at most five failed
execution attempts total, separated by 250 ms. The fifth failure permanently
discards the operation using the durable journal checkpoint, logs `REPLAY_DISCARDED`,
and allows the next entry to run. A success on the fifth attempt is committed normally.
Normal runtime failures before draining do not consume this budget. Startup only
opens admission after all entries have committed or been discarded. Synchronous shutdown
database work also stops after five database failures and logs
`DRAIN_DATABASE_WORK_DISCARDED`. Empty shutdown drains do not require a reachable
database. This deliberately permits loss of failed account/player updates so a bad
entry cannot keep startup or shutdown retrying that operation indefinitely.
See [startup and shutdown retry details](docs/persistence-shutdown.md) for limits and validation.
Existing final vehicle Reset still precedes pool closure.

`ApocBRPostgresConnector.persistenceStatus()` exposes state, RAM staging count/bytes,
durable count/bytes, oldest pending age and in-flight replay. Legacy `mirrorStatus()`
does not represent this backlog. `[ApocBR][Persistence]` logs availability changes,
rate-limited replay progress/defer/block events and shutdown wait status. They
contain operation UUID/type, SQLState and exception type, without bound values.
`REPLAY_QUARANTINED` records durable runtime rejection; `blockedPlayers` counts
player reload guards. Quarantine archives are excluded from active backlog totals.
Detailed `REPLAY_FAILED`/discard events now include bounded cause/stack diagnostics;
incomplete graph rejections include their stage and sanitized issue codes. Live
capture failures include static field/type context. See
[capture diagnostics](docs/player-capture-diagnostics.md) for interpretation and validation.
`LIVE_METRICS.journaledPatches` counts capture handoffs; journal commits are owned by the
replay adapter rather than the old relational worker's JDBC counters.

`python tools/test_postgres_resilience.py` exercises the production journal,
coordinator, foreground deadlines, login guard and native/account replay adapters
with fake JDBC. Existing account/player/relational fixtures remain SQL/engine
boundary tests; their inactive lifecycle stub is explicit in
`tools/postgres_test_support.py`. The relational fixture additionally round-trips
real detached captures through the journal, including opaque provenance and session
replacement retention. Full deployed multiplayer/outage validation is still required.

## Authoritative server accounts and administration

The patched `network/ServerWorldDatabase.java` now routes its existing account,
access-control and administration API to PostgreSQL through
`savefile/ApocBRServerDatabase.java` and the same Hikari connector. This is an
**authoritative replacement** of `Zomboid/db/<serverName>.db`, not an account mirror.
Multiplayer character persistence also routes to PostgreSQL when its player switch is enabled (see below).

`apocbr.postgres.serverDatabase.enabled` defaults to the value of
`apocbr.postgres.enabled`. Thus enabling PostgreSQL now selects PostgreSQL accounts
as well as players/vehicles, unless their individual switches are disabled. Set
`-Dapocbr.postgres.serverDatabase.enabled=false` to retain SQLite accounts explicitly.
Account mode requires the master connector enabled and the same complete JDBC URL.
Changing the backend requires a server restart; there is no automatic fallback.

The contract matches the backend's existing
`db/game-runtime-migration/V2__Create_Server_Accounts.sql`:

| Table in public | Engine behavior routed |
| --- | --- |
| whitelist | Accounts, password representation, authentication type, 2FA key, Steam/owner IDs, last connection, display name, role |
| allowedsteamid | Steam allow list |
| bannedid | Steam and shared-game-owner ban checks |
| bannedip | IP bans |
| role | Role definitions and generated IDs |
| capabilities | Role capabilities |
| defaultroles | Default-role assignments |
| userlog | Warnings, moderation and other user logs/counters |
| tickets | Support tickets, answers and viewed state |

PostgreSQL startup bypasses native SQLite file creation, `sqlite_master` probes,
legacy schema alterations and duplicate cleanup. It validates all nine table/column
contracts using read-only queries, then preserves vanilla administrator lookup,
creation and command-line password update behavior. Backend migrations and data
import must run first. Missing tables/columns stop startup; connectivity outages
hold startup until PostgreSQL returns. Startup never continues against a different
database. No engine DDL or automatic import is executed.

The native SQL outside bootstrap uses unquoted names; PostgreSQL folds them to the
migration's lowercase identifiers. Connections explicitly select `public`.
All 52 database-accessing API methods use per-call leases; nested methods on the
same thread share one lease. No PostgreSQL physical connection is stored in the
vanilla shared `conn` field. Statements retain autocommit, matching the existing
account store's per-statement durability (multi-statement native workflows are
not newly made atomic). Closing a call returns the connection and any outstanding
statements to the pool even when native code returns early or catches an error.

These APIs are **synchronous**, unlike character snapshot queues: the engine needs
the whitelist/ban/role result before allowing login or responding to an admin action.
They retain the original calling thread, including the main thread, with the
foreground budgets and outage gate described above. Background leases retain the
two-second pool and three-second pgJDBC socket/connect budgets.

Account authentication remains vanilla: existing password encodings and second-factor
data are used unchanged, and bans/capabilities still determine access. SQL or pool
errors cannot authorize login; PostgreSQL account-limit query failures deny new
accounts instead of applying vanilla's permissive error fallback. Aggregate results
are advanced with `ResultSet.next()` before reading them, as required by pgJDBC.
The secondary account-limit character check now calls the player storage API, which
uses PostgreSQL or SQLite according to the player backend switch.

PostgreSQL account exceptions retain SQLState but omit the driver exception chain
and row details before reaching native loggers, so password/2FA fields cannot be
printed through PostgreSQL DETAIL. Startup logs
`[ApocBR][Postgres] server account database ready; backend=PostgreSQL` after validation
and administrator bootstrap. Pool unavailability during authentication logs
`AUTH_DATABASE_UNAVAILABLE; login denied`. The old native administrator-password
debug print was removed.

For cutover, stop writers and copy the full server DB data, preserving whitelist
passwords/2FA values, all role IDs and default-role/capability associations, ticket
answer IDs and usernames exactly. Reset PostgreSQL identity sequences above imported
IDs. V2 preserves a globally unique whitelist username; do not merge conflicting
usernames or different servers' role IDs blindly. There is no server-key partition.
An existing `-adminpassword` option still updates the administrator password on startup,
as it did in vanilla. This change has not performed a data import or started a server.

`python tools/test_postgres_server_database.py` executes the actual patched database
class and connector with small engine fixtures and fake JDBC connections, covering
schema validation, whitelist lookup, good/bad credentials, new accounts, Steam/IP
bans, role capabilities/defaults, support/log writes, per-call/nested lease cleanup,
safe failures, backend opt-out and unavailable-connection denial. Real PostgreSQL
integration and imported-account login/2FA/RCON behavior still need an isolated server
trial with the backend migrations and copied data.

## Authoritative vehicles

`vehicles/VehiclesDB2.java` now selects a PostgreSQL `IVehicleStore` for dedicated
servers. `savefile/ApocBRVehicleDatabase.java` routes its SQL through the shared
connector/pool. This replaces `vehicles.db` for that mode; it is not a write mirror.

`apocbr.postgres.vehicles.enabled` defaults to **false**, independently of the
master switch. Vehicles use the native SQLite `vehicles.db` path for the current
observation phase. PostgreSQL vehicle storage requires an explicit
`-Dapocbr.postgres.vehicles.enabled=true`; remove any existing true override or set
it to false to retain SQLite.
Client/single-player selection remains the original SQLite store. Switching stores
requires restart. There is no PostgreSQL-error fallback to SQLite and no engine
schema creation or data import.

The contract matches the backend's `V3__Create_Vehicles.sql` exactly:

```text
public.vehicles: id, wx, wy, x, y, inmeta, worldversion, data
```

`id` is supplied by the game, not generated by PostgreSQL. Startup validates the
columns and reads persisted IDs/chunk coordinates into the native used-ID and
seen-chunk sets before vehicle allocation. Preserve IDs, coordinates, binary
payloads, format versions and `inmeta` during import. The table has no world/server
column: it is one native vehicle persistence namespace, so independent worlds must
not share it without a later explicit namespace design.

The engine still captures with `BaseVehicle.save()` and the native `VehicleBuffer`.
The new store persists the same bytes; it does not change field layout, save
triggers, frequency, object IDs, header checks or client packets. It preserves
native existence lookup followed by INSERT/UPDATE by ID, explicit commit/rollback,
and deletion. No batching/coalescing/new retry protocol is added in this baseline.

Chunk and meta reads use the native coordinate/`inmeta` predicates. Each operation
borrows its own connection in `public` with autocommit disabled and a two-second
statement timeout. Loaded bytes are materialized and the connection returned
before native deserialization callbacks run. Buffers are operation-local, rather
than a shared mutable SQLStore buffer. This also permits a callback's native
bad-vehicle deletion to acquire a connection without holding the read lease.

Normal dedicated-server chunk loads still follow
`loadChunkMain -> ServerPlayersVehicles -> QueueLoadChunk.processMain`. Main-thread
integration still checks the chunk/request token and loaded vehicle IDs.
Initialization, direct native streaming calls and final Reset keep their existing
caller/thread behavior; this change does not make every native call asynchronous.
The private SQLStore remains available for disabled and offline modes. The obsolete
local-player import from old `vehicles.db` is not run against the PostgreSQL store.

Native failure behavior is deliberately preserved: queued writes log failures and
are not given a new durable retry queue; load errors are logged by the store/queue,
and native vehicle-deserialization failures can delete the offending row. These
are baseline limitations, not improved recovery guarantees. SQL exceptions omit
credential-bearing driver messages/row details and retain SQLState. Startup emits
`[ApocBR][Postgres] vehicle database ready; backend=PostgreSQL`.

Shutdown checks the legacy character mirror queue on worker termination but leaves
the shared pool open until after `VehiclesDB2.Reset()`, which can flush remaining
vehicle work. Only then does `ServerMap` call `ApocBRPostgresConnector.closePool()`.

`python tools/test_postgres_vehicles.py` tests actual SQL/helper code and the native
store adapter with fake JDBC: empty initialization, native IDs/bytes, insert/update,
chunk movement, chunk/meta reads, removal, rollback, safe errors, connection release,
store selection, buffer-position preservation, completion guards and shutdown order.
Actual PostgreSQL imports, driving, chunk unload/reload and shutdown still need an
isolated server trial. No server deployment, import or database writes were performed
when implementing this store.

## Authoritative multiplayer players

The SQLite-to-relational initial sync implementation, V5 entity/column contract,
configuration and limits are in [PLAYER-RELATIONAL-SCHEMA.md](PLAYER-RELATIONAL-SCHEMA.md).
It writes `player_rel` after successful SQLite loading; the optional PostgreSQL
byte-store implementation described here is a separate opt-in path.

`savefile/ServerPlayerDB.java` now routes its native multiplayer persistence API
through `ApocBRPlayerDatabase` and the shared connector. There is no character
mirror enqueue in the native save path and no SQLite player connection opened in
PostgreSQL mode. The public `conn` field remains for binary compatibility with
SQLite consumers, but is null in PostgreSQL mode; the core consumers below no
longer rely on it.

`apocbr.postgres.players.enabled` defaults to **false**, independently of the master
`apocbr.postgres.enabled` setting. Native SQLite multiplayer players remain the
source for the planned initial relational backfill. Explicitly set
`-Dapocbr.postgres.players.enabled=true` only to opt into the authoritative PostgreSQL
byte store described below. An existing explicit true must be removed or changed
to false to use SQLite. Client/single-player `localPlayers` behavior remains unchanged. Backend
changes require a restart and do not fall back automatically on SQL errors.

### Configuration and schema

The same complete JDBC URL is used for accounts, vehicles and players:

```text
-Dapocbr.postgres.enabled=true
-Dapocbr.postgres.poolSize=8
-Dapocbr.postgres.url=jdbc:postgresql://db.example:5432/game?user=game_writer&password=URL_ENCODED_PASSWORD&sslmode=verify-full
```

`APOCBR_POSTGRES_URL` takes precedence over the URL JVM property. Pass JVM `-D`
arguments before `zombie.network.GameServer` when using direct Java tasks. The
runtime launcher setup deploys both dependencies automatically; direct Java tasks
must include both `apocbr-lib` JARs in their own classpath. No `serverKey` property
is required or stored.

The shared Hikari pool defaults to maximum 8 (configurable 1-32), minimum idle 2
(or the maximum if smaller), acquisition timeout 2 seconds, validation timeout
250 ms and lifetime 30 minutes. pgJDBC connect/socket timeouts are 3 seconds,
with TCP keepalive and `ApplicationName=ApocBR-game-persistence`. This is an initial
capacity setting, not a measured runtime guarantee.

The player table matches the backend's V1 migration exactly:

```sql
CREATE TABLE public.networkplayers (
    id integer GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    world text, username text, playerindex integer, name text, steamid text,
    x real, y real, z real, worldversion integer, data bytea,
    isdead boolean, splitusername text
);
CREATE INDEX inpusername ON public.networkplayers (username);
```

This is reference SQL only. The backend owns migrations/imports; engine startup
validates the columns using a read-only query and stops on an incompatible store.
Import existing bytes, format versions, names, slots, dead flags and Steam values
unchanged. Preserve/reset identity sequences above imported IDs. There are no
extra server/identity/timestamp columns or required conflict-target unique keys.

### Operations and threading

- Native save triggers and `NetworkCharacterData` capture remain unchanged. Normal
  saves enter the original queue; `ServerPlayersVehicles` calls `process()` to
  hand them to the connector journal. PostgreSQL execution occurs on replay only.
- New-character creation still invokes `ServerPlayerDB.process()`, but it now
  submits local detached work without JDBC. Its response is not a commit receipt;
  subsequent connection phases deny admission while initial work remains pending.
- `process()` serializes local handoff between native callers, with no PostgreSQL
  work under that monitor. `saveFinishWait()` waits for native queue handoff;
  graceful shutdown separately waits for journal and relational persistence.
- Writes reuse the lookup-insert/update implementation: a transaction-scoped
  `SHARE ROW EXCLUSIVE` table lock, lookup by vanilla identity, insert missing
  players with PostgreSQL-generated ID or update the selected row, then commit.
  Statements have a two-second timeout. This baseline serializes player writers.
- The lookup key is `(username,world,playerindex)` normally, or
  `(steamid,world,playerindex)` for Steam coop. There is no server partition.
  Duplicate matching records are reported as `21000`, not selected arbitrarily.
- `serverLoadNetworkCharacter()` reconstructs the server player from the complete
  compatible relational head, restoring slot, remote and dead state without native
  blob deserialization. `LoadPlayerProfilePacket` also uses that relational head:
  a temporary reconstructed player is saved into the vanilla wire layout using
  the current server dictionary and current world version. It never forwards
  `public.networkplayers.data`, whose IDs may belong to an imported dictionary.
  This temporary player is not registered for mirroring/live tracking. The
  subsequent player-connect does the normal server-player registration. Client
  payload growth is bounded by `maxSourceMiB`; the relational slot supplies the
  split username. A missing relational head means no profile, not an auto-created
  default character or legacy-blob fallback. Clients keep the vanilla decoder.
- PostgreSQL server-player registration now binds live tracking directly from
  the validated login profile's persisted character UUID and source dataset UUID.
  It skips the initial import snapshot, receipt SQL and import queue entirely,
  then starts the capture/handoff worker. It must not derive an existing life's dataset from
  the current SQLite save path or unrelated `sourceDatasetId` setting. The
  initial-import path remains for SQLite backfills and newly created lives.

- Steam conversion, Steam lookup, account-limit counts and server-side
  `PlayerDBHelper.containsNetworkPlayer/removePlayer` now use PostgreSQL. This
  covers native profile lookup and Lua existence/deletion helpers without a local
  `players.db` file check. Offline helpers retain their SQLite branches.
- Profile/loading APIs remain synchronous to preserve their return contracts.
  Reads borrow independent connections and never use a shared physical player
  connection. Character reconstruction runs in the same native caller context.

Native saves remain queued until journal acceptance; database failures retain the
journal head for retry. Invalid null/empty/over-1-MiB captures are rejected rather
than overwriting valid data. A deserialization failure
still takes the native deletion branch, now with an explicit PostgreSQL transaction.
Null data fails before that branch. This is not a quarantine/revision-history design.

The earlier connector mirror queue methods remain available for compatibility and
isolated tests, but native player operations no longer feed them. Its pending count
therefore does not report the native player queue. Worker shutdown checks that legacy
queue, and shared pool closure remains after final native vehicle Reset.

### Diagnostics and validation

Look for `[ApocBR][Postgres] connector enabled`, `player database ready;
backend=PostgreSQL`, and the `[ApocBR][Persistence]` availability/replay events.
The older `player snapshot committed` / `PLAYER_SQL_FAILURE` messages belong to
the direct compatibility helper, not journal replay.
Failure messages preserve SQLState and bounded, redacted server diagnostics without
bound snapshot bytes or driver DETAIL fields. `42P01` identifies a missing relation;
`42703` identifies missing columns. Check the target from the same database:

```sql
SELECT current_database(), to_regclass('public.networkplayers');
```

`python tools/test_postgres_players.py` executes actual player DB, helper and profile
packet code with small engine/JDBC fixtures. It covers empty/new users, single
capture plus native queue commit, existing profile/character reads, slot misses,
profile response fields, death, Steam coop conversion, account counts, file-independent
Steam lookup, Lua helper removal, duplicates and database failures without fallback.
`test_postgres_connector.py`, `test_postgres_server_database.py` and
`test_postgres_vehicles.py` cover the shared connector and other backends.

These are isolated tests, not proof of a lossless real-game character round trip.
Import/reconnect, representative modded player loads, respawn, disconnect/restart,
backup behavior and outage handling need an isolated server trial. No import,
production PostgreSQL query, deployment or restart was performed for this change.

## Bundled PostgreSQL driver

`lib/postgresql-42.7.14.jar` is the upstream PostgreSQL JDBC driver, downloaded
from the [official pgJDBC download page](https://jdbc.postgresql.org/download/).
Artifact: `org.postgresql:postgresql:42.7.14`. License:
[BSD-2-Clause](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.14/LICENSE).

Pinned SHA-256:

```text
73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c
```

The official download was checked against Maven Central's published SHA-1.
The SHA-256 above records the downloaded artifact for subsequent verification.

Both versioned patch scripts explicitly include the game JAR and this driver in
the Java compiler classpath. Missing driver files fail compilation setup with a
clear error; revert does not require the driver. Paths containing spaces are
passed as one classpath argument. Updating the driver requires updating both
scripts and this document together. Builds use the bundled file, with no driver
download during compilation.

Java sources can now import pgJDBC classes, such as
`org.postgresql.ds.PGSimpleDataSource`, when compiled by these scripts.
Database provisioning and game-schema migrations belong to the Spring backend.

Verify compilation without deploying:

```powershell
.\42.21.0\patchApocalipseBr.ps1 -PZDir 'Z:\SteamLibrary\steamapps\common\ProjectZomboid' -DryRun
```

The compiler classpath is separate from the server runtime classpath. Normal
deployment now copies both dependency JARs into `<PZDir>/apocbr-lib` and configures
the runtime classpath automatically:

- Windows: `tools/Set-PostgresRuntime.ps1` patches `ProjectZomboidServer.bat`,
  inserting a managed `set "PZ_CLASSPATH=%PZ_CLASSPATH%;..."` block immediately
  before the server Java command. It quotes the `-cp` argument for installations
  with spaces and preserves the original classpath order and other launch options.
- Linux: `tools/set_postgres_runtime.py` updates existing `ProjectZomboid64.json`
  and/or `ProjectZomboid32.json` used by the stock native launcher, appending the
  two relative JAR entries while preserving all other JSON values. Python 3 is
  required. Custom direct `java -cp` launch scripts are not patched by this helper.

Repeated deployment replaces managed entries rather than duplicating them. Each
launcher gets a first-write `.apocbr-runtime.bak` backup. Dry-run validates launcher
support and reports dependency deployment without modifying installation files.
Revert removes managed entries and only the two named JARs, preserving unrelated
launch options; Windows retains safe classpath quoting. Unknown Windows launcher
formats or missing Linux descriptors fail before class deployment. No server is
started or restarted by these helpers. Database enablement/URL settings remain
explicit deployment configuration.

`python tools/test_postgres_runtime_deployment.py` tests both actual helpers in
temporary installations: JAR copies, classpath updates, repeated apply, dry run,
revert, spaces in paths, backups and preservation of independent launcher edits.

Validation: the Windows dry build compiled 114 patched sources into 428 class
files against the actual game JAR. A temporary Java smoke check imported
`PGSimpleDataSource` and `Driver`, accepted a PostgreSQL JDBC URL and verified
driver registration on the game's bundled Java 25 runtime, without opening a
database connection. Linux runtime compilation remains unverified.

### Server-only player propagation audit (42.21.0)

The login response is generated in memory; it does not require writing the
reconstructed payload into local SQLite. PostgreSQL player mode does not open
`players.db`. The client receives the existing packet ID, fields, byte count
and native player serialization, with IDs from the active server dictionary
already distributed by `ConnectionDetails` / `WorldDictionary.saveDataForClient`.

| Server path | Data sent and authority |
| --- | --- |
| `LoadPlayerProfilePacket.processServer` | Existing-character full snapshot: relational head -> reconstructed temporary player -> native save -> vanilla response. Covers all four slots and Steam coop; no legacy blob fallback. |
| `ConnectPacket` / `ConnectCoopPacket` -> `GameServer.receivePlayerConnect` | Loads the actual server player through the PostgreSQL branch of `serverLoadNetworkCharacter`; no stored blob is forwarded. |
| `GameServer.sendPlayerConnected` -> `ConnectedPacket` | Sends selected state, descriptor, visuals, safety and XP from the live server player. It does not resend a stored full-player buffer. |
| `CreatePlayerPacket` | Sends a native save of a newly created server player, after the normal creation/save path. Initial relational creation remains a distinct operation. |
| `EquipPacket`, attached-item, inventory-container and trading packets | Serialize live item objects through native item save methods, not `networkplayers.data`. |
| `SyncItemModDataPacket`, item fields, player stats/fields/health packets | Serialize selected live state through native codecs; they do not load historical player blobs. |
| `PlayerInventoryPacket` | Admin inspection reads a live player, or falls through the same PostgreSQL-aware server loader for an offline player, then sends inventory fields. |

The legacy byte-array reader remains available in `ApocBRPlayerDatabase.load`
for compatibility tooling, but the production PostgreSQL login response does
not call it. SQLite response/load code remains gated behind the disabled
PostgreSQL-player branch. Gameplay still uses normal engine networking after
login; persistence authority does not replace vanilla action validation.

`tools/test_postgres_players.py` checks the actual response using the original
profile parser loaded separately from the game JAR and native ByteBuffer
reader/writer (with a small string-helper boundary stub). Coverage includes all
four slots, Steam coop, incompatible legacy bytes and a missing relational head
despite an existing legacy blob. Reconstruction/native player-save engine
boundaries are stubbed in this routing fixture; a real login remains necessary
to validate the complete inventory/mod-data payload with installed mods.
## Diagnosing live mutation retries

With the active journal, use `[ApocBR][Persistence]` replay events and
`persistenceStatus()` from the outage-resilience section above. The following
`LIVE_WRITE_*` diagnostics describe the direct relational worker path retained
for inactive-lifecycle fixtures and older deployments. Journal handoff is reported
as `journaledPatches`; it does not mean the PostgreSQL transaction committed.

`LIVE_WRITE_FAILED` reports exceptions; `SQLState=LOCAL` identifies a Java
runtime exception or linkage error rather than a JDBC SQLState. Diagnostics
include the processing stage, exception types, controlled issue codes and
bounded stack locations, excluding raw exception messages and player values.

Each pending queue entry receives a process-local `operation` number. Failure
and `LIVE_WRITE_DEFERRED` messages include the consecutive `attempt` since the
last successful commit, `pendingAgeMs`, `retryInMs`, `graphRevision`, `hasGraph`
and scalar `fields` count. Repeated failure/defer messages are limited to one
per entry per 30 seconds, so one player cannot suppress another's diagnostic.
Retry delays grow from 2 seconds to a maximum of 30 seconds; persistent errors
can keep work pending indefinitely. No diagnostic change drops pending work.

`LIVE_WRITE_RECOVERED` confirms a successful commit after unsuccessful attempts.
`queueDrained=true` means the entry was fully acknowledged; `false` means newer
state remains queued. New captures can supersede old captures within the same
operation, so recovery does not mean every intermediate snapshot was written.
Operation numbers correlate logs only within the current server process and
do not expose usernames, session IDs or persisted character identifiers.

After deploying/restarting, inspect all three outcomes together:

```bash
journalctl --since "20 minutes ago" --no-pager --grep='LIVE_WRITE_(FAILED|DEFERRED|RECOVERED)'
```

### Duplicate native inventory IDs

Projection follows the native `ItemContainer.AddItem(InventoryItem)` policy:
keep the first item and reject a later distinct object with the same native ID,
rather than overwriting the first or failing the entire player write. The scope
is the captured player's owned tree. Traversal follows each container's source
list order, descending into a bag's contents or a weapon's attachments before
continuing with the next item. "First" refers to this snapshot traversal, not
the oldest database row or a provenance/creation-time claim. Cross-player
ownership arbitration is unchanged.

A rejected bag or weapon contributes no rows for itself or its descendants;
contents are never merged into the retained item. Equipment pointing to the
rejected object is omitted by the existing object-identity check, never rebound
to the retained item by native ID. Source positions can have gaps after filtering.
Repeated ownership of the same accepted object and cycles still reject capture.
The retained graph keeps unique native IDs, its existing UUID aliases and valid
counts, allowing other state such as fitness to commit in the same transaction.

This policy applies to initial and live projection, using the detached state the
engine supplies. It changes neither live inventory nor IDs, native serialization,
client packets or engine synchronization. No client provenance is required.
Snapshots still copy source state before projection, so rejected descendants can
consume copy/alias-read work and remain subject to existing snapshot budgets.

`DUPLICATE_NATIVE_ITEM_SKIPPED` logs the native ID, relation, source position and
depth at most once per 30 seconds. `LIVE_METRICS.duplicateItemsSkipped` counts
rejected item occurrences, including repeated captures; it does not count distinct
lost items or committed deletions. A graph containing only this normalized
condition is complete; other unsupported state still prevents publication.

`python tools/test_postgres_duplicate_items.py` runs without a game JAR or live
PostgreSQL. It compiles complete production capture/snapshot/queue/delta/database
classes against small engine boundary stubs, reuses the relational fixture, and
compares keep-first behavior with the exact-version vanilla insertion method.
Checks include nested bags, attachments, equipment, immutable snapshots, ownership
guards, JDBC batch bindings for fitness/inventory and dirty-queue acknowledgement.
The full `test_postgres_player_relational.py` suite includes the same regression;
its native compatibility checks and a patch dry build require the real 42.21.0 JAR.

### Typed modData values and object references

Structured copying checks each nested value explicitly. Scalars, byte arrays,
lists and maps retain their existing codecs; only an actual `KahluaTable` is
traversed through its table iterator. An unrelated Java object is never assumed
to implement `iterator()`. This fixes the accidental
`Missing capture method zombie.inventory.types.Clothing.iterator` path when the
clothing is a supported reference to an owned inventory item.

Supported engine references are `InventoryItem` (including Clothing and other
subtypes), `ItemContainer`, and the current player. They must resolve by object
identity to the accepted inventory ownership tree/current character. The copier
collects detached references and binds them after all source items and equipment
have been copied, allowing forward references and self references without copying
another item or retaining live engine state. Worker projection resolves them
against the accepted item UUID aliases and player/container IDs. Reference rows
are charged against the ordinary graph budgets before their targets are bound.

The existing `value_node.value_kind='reference'` and `reference_object_id` FK store
the relationship. No SQL migration is needed. A reference change updates that FK;
the referenced item's state stays in its normal item/subtype tables. References
do not add inventory ownership edges or change the existing edge-count definition.
Reconstruction creates the owned objects first, then restores table references
to those same instances through the reader's ordinal identity map. Shared acyclic
Lua tables still restore by value; references to the same item restore by identity.
Lua table cycles remain rejected, while an item's table can reference that item
without recursively serializing its ownership graph.

`UNSUPPORTED_STRUCTURED_VALUE` rejects Java types without a supported codec.
References to items/containers absent from the accepted ownership tree, including
equipment-only objects and rejected duplicates, project as typed nulls. They do
not block the player graph, target another item's UUID, or add inventory owners.
The runtime mod-data table remains unchanged; reload restores these values as
nil/absent. `LIVE_METRICS.externalRefsSkipped` counts these projections.
Other unsupported Java types still require reviewed codecs. Keys retain the
existing scalar-key contract.

Detached reference targets survive the outage journal's serialization/filter and
are resolved again during replay. Snapshot/schema codec metadata stays at 4/1,
using the already declared reference variant; deploy the updated capture and
reconstruction together. Earlier patch readers cannot decode the newly emitted
reference values, and earlier journal readers lack the new reference DTO class.
Existing table-only heads and journal records remain readable by this patch.
This implements explicit relational references; it is not a claim that native
Kahlua serialization preserves every arbitrary Java userdata value.

`python tools/test_postgres_moddata_references.py` exercises actual table
interfaces, forward/backward/self and nested references, alias identity,
immutable snapshots, journal encode/decode with its real input filter, structured
reconstruction, targeted JDBC updates and invalid targets. It compiles production
capture/snapshot/database classes and extracts the production structured reader
methods against small engine boundaries. The native reconstruction fixture also
checks the new reference branch; full game-JAR compilation and live round trips
remain required for deployment validation.

### Exercise regularity persistence investigation

Equipment references are resolved by object identity against the captured owned
inventory tree. A hand/worn/attached pointer outside that tree is omitted rather
than rejecting the whole player graph. Native `IsoPlayer.save/load` similarly
writes an invalid inventory index for unreachable hand/worn links and ignores
them on load. Capture does not add such an item to inventory or substitute a
different object with the same native ID; live equipment is left untouched.
Ownership and cycle checks remain enforced. A later capture includes
the equipment normally if the actual item becomes reachable again.

`EQUIPMENT_REFERENCE_SKIPPED` reports the relation kind, sanitized slot, native
item ID and whether that ID also exists in owned inventory, at most once per
30 seconds. `equipmentRefsSkipped` is a cumulative count of references observed
and omitted during projection, including repeated snapshots; it is not a count
of lost items, players or committed changes. `LIVE_WRITE_RECOVERED` identifies
successful commits after the previous unreachable-equipment failure.

`samples>0`, `pending>0`, but `writes=failedPatches=deferredPatches=graphSamples=0`
can identify pending scalar work stranded before character binding. The old
PostgreSQL login relied on a separate import receipt for that binding; a receipt
failure left sampling active while the entry was ineligible for processing.
The direct registration above removes that dependency for PostgreSQL loads.
Look for `LIVE_TRACK_READY; source=PostgreSQL; initialSync=skipped`, followed by
increasing graph/write counters. `unboundPending` exposes remaining entries with
no matching character binding, and `graphReconcileEnabled` reports whether full
graph reconciliation is configured on. Cache baselines only appear after graph
work succeeds; increasing the cache limit does not make an unbound entry eligible.

The exercise progress bar reads `Fitness.regularityMap`; body-part stiffness is
a different value. The local vanilla shared Lua `ISFitnessAction.serverStart`
initializes the server fitness exercise, and `exeLooped` calls
`Fitness.exerciseRepeat`. That increments regularity and sends `PF_Fitness=32`
to the client. A client bar update therefore is not a database commit receipt.

Persistence follows `NetworkPlayerManager.update` -> mutation `observe/endTick`
-> `Snapshot.copyLive` -> capture `fitness_value` rows with
`map_name='regularityMap'` -> graph delta -> `Database.applyFields` commit.
The default graph cadence is 30 seconds; the smaller scalar sample does not
include fitness maps. Login reconstructs these rows into `Fitness` and uses
its native save/load codec for the unmodified client.

Disconnect calls `serverUpdateNetworkCharacter`, which queues the native save
and requests `finalSample`. This queues relational work asynchronously; neither
that method nor the ordinary disconnect waits for a relational commit. Native
`networkplayers.data` being saved does not make the relational head current.
Any incomplete inventory/component graph rejects the entire graph, including
regularity updates. The outage-resilience change now refuses replacement of a pending dirty session,
blocks login while capture/journal work remains, and drains final captures during
graceful shutdown. Ordinary disconnect still does not synchronously await a commit.

Use `tools/inspect_postgres_player_fitness.sql` against the live database after
exercise, after logout, and after login. It reports the current head, commit
timestamps, source dataset, regularity and soreness values. If regularity is
absent/old before login, investigate capture/write failures and pending work;
if it is persisted correctly but the client bar resets, investigate the login
profile/runtime deployment. Existing retained snapshots do not provide a full
history because live heads are updated in place.

Fixtures verify native regularity increments, numeric reconstruction of all
four fitness maps, fitness wire round trips and actual JDBC batch bindings for
regularity graph inserts. They do not prove a successful commit on the live
server or execution of its multiplayer exercise actions.
