# PostgreSQL JDBC dependency for 42.21.0

## Character snapshot mirror

`src/zombie/savefile/ApocBRPostgresConnector.java` now implements an optional
PostgreSQL character snapshot mirror. SQLite remains the active save/load store.
The mirror receives the same bytes captured by vanilla `NetworkCharacterData`,
not a second `IsoPlayer.save()` call. `ServerPlayerDB.process()` queues those bytes
before its SQLite write and calls the connector after each write and at the end
of every worker pass (including when there are no new SQLite saves).

SQL runs on the existing `ServerPlayersVehicles` worker. There is no additional
gameplay handoff or SQL writer thread. HikariCP's internal maintenance/connection
creation threads manage the pool; they do not capture players or run mirror writes.
Native save triggers, interval, profile loads and SQLite connection are preserved.
PostgreSQL receives the capture even if the subsequent SQLite write fails.

Configuration before server startup:

When invoking Java directly (including VS Code tasks), put all `-Dapocbr.postgres.*`
arguments **before** `zombie.network.GameServer` and include both `apocbr-lib` JARs
in that invocation's `-cp` argument. Arguments after the main class are game
arguments, not JVM properties. Direct Java tasks bypass the patched batch launcher.

```text
-Dapocbr.postgres.enabled=true
-Dapocbr.postgres.serverKey=pve
-Dapocbr.postgres.poolSize=8
```

Supply the **complete JDBC URL** through `APOCBR_POSTGRES_URL` (preferred) or
`-Dapocbr.postgres.url=...`. Example placeholder only:

```text
jdbc:postgresql://db.example:5432/game?user=game_writer&password=URL_ENCODED_PASSWORD&sslmode=verify-full
```

Do not commit real credentials. URL-encode query parameter values as required.
The connector passes this URL to pgJDBC instead of assembling host/port/database
fields. It overrides connect/socket timeouts to three seconds, enables TCP
keepalive and sets `ApplicationName=ApocBR-<serverKey>`. Environment URL takes
precedence over the JVM property. Connector defaults disabled; enabling requires
a valid URL and a lowercase server key matching `[a-z0-9_-]{1,80}`. Invalid
configuration/dependency failure disables the mirror for that process lifetime.

Hikari pool maximum defaults to **8**, configurable 1–32, with minimum idle 2
(or the maximum if smaller), acquisition timeout 2 seconds, validation timeout
1 second and max connection lifetime 30 minutes. Connections have autocommit
disabled. The pool initializes on the existing worker and acquires asynchronously
without waiting for DB availability at startup. Eight is a configurable starting
limit, not a measured capacity guarantee: this phase has one writer and normally
uses one connection at a time. The game's existing SLF4J 2 API supplies Hikari's
logging dependency; it does not need a second bundled SLF4J API JAR.

### Backend schema contract

The Spring backend owns migrations; the engine executes no DDL. Create the
following contract in the chosen PostgreSQL database before enabling writes
(reference SQL, not an executed migration):

```sql
CREATE TABLE public.networkplayers (
    server_key text NOT NULL,
    world text NOT NULL,
    identity_mode text NOT NULL CHECK (identity_mode IN ('username', 'steam_coop')),
    identity_key text NOT NULL,
    player_index integer NOT NULL CHECK (player_index BETWEEN 0 AND 3),
    username text NOT NULL,
    steamid text,
    name text,
    x real, y real, z real,
    worldversion integer NOT NULL,
    is_dead boolean NOT NULL,
    split_username text,
    data bytea NOT NULL CHECK (octet_length(data) BETWEEN 1 AND 1048576),
    captured_at timestamptz NOT NULL,
    mirrored_at timestamptz NOT NULL,
    PRIMARY KEY (server_key, world, identity_mode, identity_key, player_index)
);
```

`identity_key` is username for ordinary dedicated servers, or Steam ID for Steam
coop mode, preserving vanilla selection semantics. Server/world namespaces prevent
independent servers from replacing each other's rows. The writer role needs
schema USAGE and table INSERT/UPDATE/SELECT permissions. No schema-version probe
exists yet: missing/incompatible tables produce SQL failures and retained work.

Each write uses one parameterized `INSERT ... ON CONFLICT DO UPDATE` transaction
with query timeout 2 seconds and explicit commit/rollback. All native metadata
and the complete binary payload are included. Null/empty/over-1-MiB snapshots are
rejected by the mirror without replacing prior PostgreSQL data. This does not fix
the vanilla SQLite path's invalid-payload handling.

### Queue, retries and shutdown

Queue bounds are 64 snapshots and 16 MiB of payload bytes; snapshot bytes are
defensively copied. A drain handles up to four writes, serially. On failure,
the head remains queued and retries after five seconds, ahead of its successors.
Connection loss during commit retries the same replacement upsert and capture
time: row state is idempotent under one writer, not an exactly-once event protocol.
Do not add non-idempotent trigger side effects or run multiple writers for the
same namespace. There are no life/session fences or historical revisions here.

Failure messages include safe codes, SQLState, counts and queue size.
SQL failures now also include pgJDBC's structured server message, schema/table/
column/constraint and query position, with URLs/credential assignments redacted
and text bounded. PostgreSQL DETAIL/WHERE and bound parameters are excluded.
The parameterized UPSERT template is logged alongside the error at the same
first-failure/every-12-failures cadence; no prepared statement with values is logged.

`42P01` means an undefined relation, not an empty table or a conflicting player
key. The query targets **`public.networkplayers`** explicitly: a similarly named
table in another schema, a different JDBC database or quoted mixed-case table does not
satisfy this contract. A missing conflict-target unique constraint instead usually
produces `42P10`; a unique violation is `23505`.
Check from a connection to the same database:

```sql
SELECT current_database(), to_regclass('public.networkplayers');
```

A null relation result means the backend migration must create the expected
table (see the schema contract above). If it resolves but `42P01` persists, inspect
the new server message for another missing relation, such as one referenced by a
table trigger. Empty tables are supported: every snapshot executes INSERT with
ON CONFLICT DO UPDATE, creating a new row or replacing the existing matching row.
No pre-existing player record or SQLite ID is required. These are PostgreSQL
[error code definitions](https://www.postgresql.org/docs/current/errcodes-appendix.html)
and [upsert semantics](https://www.postgresql.org/docs/current/sql-insert.html).

The worker logs disabled mode once, enabled pool initialization, first successful
connection acquisition, and each acknowledged snapshot commit (count/byte size).
Pool initialization alone does not prove database connectivity. These messages
use `[ApocBR][Postgres]`; the first write/connection log needs a captured save.
`ApocBRPostgresConnector.mirrorStatus()` exposes pending count/bytes, acknowledged
commits, failures, rejected captures, last error and closed state without SQL.
Full/closed queues reject new mirror snapshots with counters and throttled logs;
SQLite continues. A persistent DB/schema error can therefore fill the mirror
queue. No durable spool exists; pending bytes disappear on process crash.

Worker termination runs a best-effort flush with a ten-second deadline checked
between individual writes, then closes the pool. An individual JDBC call can
extend this deadline within its timeouts. The first failed final attempt stops
flushing and reports pending work. The vanilla stop flag is not changed.
`saveFinishWait()`/SQLite backup completion is not a PostgreSQL commit barrier.
PostgreSQL delay also delays the shared vehicle persistence worker; measure it
under load before using this mirror as an authoritative backend.

### Runtime dependencies and verification

Both the PostgreSQL driver and `lib/HikariCP-7.1.0.jar` are included automatically
in the compiler and server runtime classpaths during normal script deployment.
HikariCP is licensed
under [Apache-2.0](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.1.0/LICENSE).
Its download from Maven Central was checked against the published SHA-1; SHA-256:

```text
2be45c641b841e81df87292e181f2c203500c5f0e9a1676f135927387f3cf40b
```

Run `python tools/test_postgres_connector.py` for actual-source isolated tests of
successful commits, rollback, uncertain commit retry, FIFO ordering, identity
selection, byte immutability, queue limits, disabled mode and real Hikari linkage
against the game's SLF4J API. These use fake JDBC connections, not a live database.
Use the version script's `-DryRun` for real-game-JAR compilation. Actual PostgreSQL
SQL/schema integration, outage behavior under server load and restart/reconnect
scenarios still require isolated runtime testing.

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

Validation: the Windows dry build compiled 100 patched sources into 381 class
files against the actual game JAR. A temporary Java smoke check imported
`PGSimpleDataSource` and `Driver`, accepted a PostgreSQL JDBC URL and verified
driver registration on the game's bundled Java 25 runtime, without opening a
database connection. Linux runtime compilation remains unverified.
