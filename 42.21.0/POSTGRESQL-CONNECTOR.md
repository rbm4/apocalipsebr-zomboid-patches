# PostgreSQL JDBC dependency for 42.21.0

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
import must run first. Missing tables/columns or unavailable connections stop
startup rather than continuing against a different database. No engine DDL or
automatic import is executed.

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
Preserving those return contracts means calls can wait on the original calling
thread, including the main thread. Connection acquisition is bounded by the pool's
two-second timeout; JDBC statements get a two-second query timeout, and pgJDBC uses
the configured three-second socket/connect timeouts. Multiple statements can extend
total API-call latency. This is not a new asynchronous login protocol.

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
1 second and lifetime 30 minutes. pgJDBC connect/socket timeouts are 3 seconds,
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
  commit PostgreSQL directly. There is no second serialization or mirror queue.
- New-character creation still calls `ServerPlayerDB.process()` directly. This is
  intentionally synchronous, so the first PostgreSQL row is committed before the
  native creation response/later character lookup. It is not a new login handoff.
- `process()` is serialized between its native callers to keep FIFO processing
  and prevent simultaneous main/worker drains. `saveFinishWait()` also waits for
  a polled operation still committing inside that method.
- Writes reuse the lookup-insert/update implementation: a transaction-scoped
  `SHARE ROW EXCLUSIVE` table lock, lookup by vanilla identity, insert missing
  players with PostgreSQL-generated ID or update the selected row, then commit.
  Statements have a two-second timeout. This baseline serializes player writers.
- The lookup key is `(username,world,playerindex)` normally, or
  `(steamid,world,playerindex)` for Steam coop. There is no server partition.
  Duplicate matching records are reported as `21000`, not selected arbitrarily.
- `serverLoadNetworkCharacter()` reads PostgreSQL and calls the existing
  `IsoPlayer.load()` with stored bytes/version, restoring slot, remote and dead
  state. `LoadPlayerProfilePacket` reads the same store and preserves response
  fields and wire layout. A missing row still means no profile, not an auto-created
  default character.
- Steam conversion, Steam lookup, account-limit counts and server-side
  `PlayerDBHelper.containsNetworkPlayer/removePlayer` now use PostgreSQL. This
  covers native profile lookup and Lua existence/deletion helpers without a local
  `players.db` file check. Offline helpers retain their SQLite branches.
- Profile/loading APIs remain synchronous to preserve their return contracts.
  Reads borrow independent connections and never use a shared physical player
  connection. Character reconstruction runs in the same native caller context.

As with the requested native baseline, failed saves are logged after being polled;
there is no new authoritative retry/spool protocol. Invalid null/empty/over-1-MiB
captures are rejected rather than overwriting valid data. A deserialization failure
still takes the native deletion branch, now with an explicit PostgreSQL transaction.
Null data fails before that branch. This is not a quarantine/revision-history design.

The earlier connector mirror queue methods remain available for compatibility and
isolated tests, but native player operations no longer feed them. Its pending count
therefore does not report the native player queue. Worker shutdown checks that legacy
queue, and shared pool closure remains after final native vehicle Reset.

### Diagnostics and validation

Look for `[ApocBR][Postgres] connector enabled`, `player database ready;
backend=PostgreSQL`, `player snapshot committed`, and `PLAYER_SQL_FAILURE`.
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
