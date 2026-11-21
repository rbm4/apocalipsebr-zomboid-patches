# PostgreSQL and NPC runtime migration

Status: **in progress; account hooks and dictionary setup registered, complete PostgreSQL/NPC runtime port still unfinished**.
Date: 2026-11-21. Target: original 42.22.2 JAR plus root Maven agent.

## Authorized feature scope and architecture

The user selected the complete PostgreSQL feature, including unfinished vehicle
paths, and explicitly included the NPC gameplay runtime. Account-only and
storage-only scopes were rejected. The implementation may proceed internally in
layers, but the complete feature must not be described as finished or deployed
until its connected behaviors are covered.

The user selected agent-owned adapters and state with structurally vanilla game
classes. Keep `AgentRuntime.disableClassFormatChanges()`. Replace legacy added
game-class APIs with feature-owned contracts; do not inject replacement fields,
methods or nested classes into the game. Preserve the NPC composition and native
authority contracts from the historical guides until their active equivalents
are documented here.

## Source behavior

Evidence comes from `42.22.2/src` and matching `42.22.2/decompiled` sources:

| Owner | Behaviors that belong to this complete migration |
| --- | --- |
| `ApocBRPostgresConnector`, `ApocBRForegroundDatabase`, `ApocBRServerDatabase` | Shared bounded pool, per-thread nested leases, shared request deadlines, sanitized failures, account schema validation and SQL routing. |
| `ServerWorldDatabase`, async login/admin helpers and packet handlers | PostgreSQL account bootstrap, roles, authentication/second factor, bans, administrative operations, fail-closed behavior, detached role snapshots and server-thread continuations. |
| Persistence coordinator, journal and operation adapters | Startup replay before admission, durable receipts, outage/recovery, operation ordering, pending-player guards, runtime quarantine, bounded startup/shutdown policies and pool close ordering. |
| `ServerPlayerDB`, player adapters, capture/reconstruction and mutation owners | Native/relational persistence, safe character creation handoff, identities, reconnect/reconstruction, imports/migration, ongoing mutations and final snapshots. |
| World dictionary adapters and serialization boundaries | Publication, immutable view identity, historical dictionary reads, provenance capture, decoder binding, item/object/vehicle integration and shutdown. |
| `VehiclesDB2`, `ApocBRVehicleDatabase` | Store selection, used IDs/chunks, native buffer capture, SQL saves/loads/meta/delete, chunk repair and provenance-aware decode; preserve worker/main-thread integration. |
| NPC factory, profiles, state, actions, authority, transports and stores | Full gameplay runtime plus detached persistence, relationships, dialogue, speech, mortality and sales integration. Preserve server-thread native authority and detached planning. |

This table is an integration checklist, not proof that each boundary has been
implemented. Other hooks/dependencies must be resolved by tracing the owners.

## Implemented foundation

Production code is under `features/postgres`:

- `PostgresConnections`: bounded Hikari pool with foreground/background
  acquisition paths, transaction leases, `public` schema and terminal shutdown.
- `ForegroundDatabase`: shared nested deadlines and failure marker, bounded
  network/query waits, thread ownership, sanitized JDBC failures and cleanup
  after expiration. JDBC failures remain observable even if vanilla catches them.
- `AccountConnections`: one per-thread lease across nested native account calls,
  reverse-order scope closing and release on the outermost return.
- `AccountSchema`: checks the nine legacy account table/column contracts with
  SELECTs only. This does not validate all write privileges or all feature schemas.
- `PersistenceJournal` and detached account operation DTOs: root-owned port of
  the engine-independent custom journal, with explicit descriptor aliases for
  legacy account entries. Compatibility tests compile the actual legacy journal
  in an isolated test class loader. Unknown legacy operation types abort reading
  without acknowledgement; player/NPC operation migration remains open.
- `AccountReplay`: account mutations and receipt claim share one transaction.
  Tests cover rollback and a lost commit response, so retrying the same UUID
  does not duplicate committed writes. This is not yet connected to a replay
  worker or a vanilla database hook.
- `AgentConfiguration`: declares the foundational PostgreSQL settings and keeps
  the JDBC URL out of configuration string representations and error messages.
- Maven bundles and relocates PostgreSQL, Hikari and their SLF4J dependency so the
  original-JAR launch does not require the old `apocbr-lib` classpath for these
  libraries. The game JAR is not bundled.

`PostgresModule` is now registered by `AgentRuntime`. When enabled, it installs
the account hooks described below. The separately registered
[`DictionaryModule`](postgresql-dictionary.md) now publishes the completed native
dictionary and prepares paginated exact foreign views with a notification listener.
Historical payload decoding and player reconstruction remain open, along with
the remaining full-feature persistence/runtime contracts.
Setting `apocbr.postgres.enabled` does not make the complete port available yet.
No installed game files or live database have been changed by this work.

## Registered hooks

| Native owner/boundary | Agent implementation and behavior |
| --- | --- |
| `ServerWorldDatabase.create()` | `PostgresAdvice.Bootstrap` calls the agent-owned `AccountBootstrap` and skips SQLite DDL/file setup. Validates existing account tables, preserves native role initialization/password hashing, and creates or updates the administrator through PostgreSQL. |
| `ServerWorldDatabase.connect()/close()` | Connect validates a scoped lease; close skips the native shared-connection path. Pool shutdown belongs to server/JVM lifecycle. |
| Account methods reading `ServerWorldDatabase.conn` | `AccountConnectionVisitor` redirects field reads to `PostgresRuntime.connection`. `AccountCall` advice shares a scoped lease across nested calls and releases it on normal/exceptional return. The native singleton field remains untouched and original account method bodies continue. Uncovered connection accesses reject transformation. |
| `authClient`, `authOwner`, `googleAuthClient` | Authentication advice uses the login workflow budget and produces `AccessDenied` on acquisition failure or SQL failure caught by vanilla. Native credential, role, Steam-ban and second-factor checks otherwise continue. |
| `isNewAccountAllowed` and the account-side player Steam-ID lookup | Agent-owned adapters perform the dependent PostgreSQL `public.networkplayers` reads. The account-limit adapter preserves count/priority policy, advances result sets correctly and fails closed. Account-side lookup avoids constructing the native SQLite player database. These reads require the existing PostgreSQL player table/data; player writes/reconstruction remain unported. |
| Native numeric `setString` account bindings | `AccountSqlTypes` narrowly binds whitelist IDs and userlog amounts as JDBC integers for PostgreSQL. Other native bindings are retained. |
| `GameServer.main` entry/exit | Entry requires confirmed account transformation before startup proceeds; exit closes the pool on normal/exceptional return. |
| `ServerMap.QueuedQuit` exit | Pool shutdown follows vanilla shutdown work, including role saves and vehicle reset. A JVM shutdown hook covers abnormal termination. |

Enabled module initialization preflights the actual classpath's account bytes
without defining game classes before the transformer is installed. All three
instrumented original-JAR owners retain their field/method structure. No helper
is placed in a signed native package.

The account adapters use native autocommit semantics and return failure rather
than queueing account writes during an outage. The separate [server Lua/service
worker migration](server-lua.md) now adds async login, two-factor and admin
user-log continuations; other native administrative calls remain synchronous. It is **not** the
completed legacy journal/recovery/async contract. Until that coordinator and all
operation types are migrated, bootstrap checks the legacy journal checkpoint
read-only and refuses pending or invalid journal state before borrowing/writing.
It does not discard, acknowledge, or replay pending records as a shortcut.

Hooks are startup configuration, not hot-switchable. Enable account hooks with
`-Dapocbr.postgres.enabled=true`, supply the JDBC URL through
`APOCBR_POSTGRES_URL` or `-Dapocbr.postgres.url=...`, and leave
`apocbr.postgres.serverDatabase.enabled` true (its enabled-master default).
The log distinguishes module registration from enabled/disabled account hooks.
See the [hook implementation and validation report](../reports/2026-11-21-postgresql-hook-registration.md).

## Configuration implemented so far

| Setting | Contract |
| --- | --- |
| `-Dapocbr.postgres.enabled=true` | Master enable flag; default false. Currently enables the implemented account hooks, not the remaining persistence/NPC ports. |
| `-Dapocbr.postgres.url=...` | JDBC PostgreSQL URL; `APOCBR_POSTGRES_URL` takes precedence. No credentials in YAML or committed documentation. |
| `-Dapocbr.postgres.poolSize=8` | Range 1..32. |
| `-Dapocbr.postgres.serverDatabase.enabled=true` | Account setting defaults true when the master flag is enabled. |
| `-Dapocbr.postgres.loginWorkflowTimeoutMillis=5000` | Clamped to 500..30000 ms. Nested scopes inherit an existing deadline. |
| `-Dapocbr.postgres.loginNetworkTimeoutMillis=3000` | Clamped to 250..30000 ms; login/bootstrap acquisition and network operations are further limited by the remaining workflow budget. |
| `-Dapocbr.postgres.journalDirectory=...` | Existing journal location override. Default matches the legacy save-dir/Multiplayer/server-name/postgres-journal path. The current bootstrap requires it to be drained and valid. |

Declare remaining settings in `AgentConfiguration` as their behaviors are ported.
Login/bootstrap connection acquisition uses this configured network limit, not
the ordinary foreground-call cap of 250 ms. Nested calls inherit the owning
workflow and cannot extend its deadline or limit. This matters for cold pools:
the first asynchronous physical connection may take longer than 250 ms even
when the configured workflow permits it. Enabled account startup logs the
effective workflow/acquisition limits and pool size without the JDBC URL or
credentials. Pool/driver exception wrappers are inspected for a valid SQLState
before using the generic `HY000` fallback; raw driver details remain excluded.
Do not treat old launcher flags as proof those features exist in the agent.

## Compatibility constraints and open implementation work

Player reconstruction must follow dictionary support: historical view loading,
payload provenance, executable native decode hooks and a proven client payload
compatibility contract are prerequisites. Native item saving copies embedded
`byteData` unchanged, so re-encoding the outer player does not by itself prove
that the initial client can decode historical nested payloads. See the
[dictionary-first reconstruction context](../reports/2026-11-21-player-reconstruction-context.md)
for dependency order, evidence and remaining gaps.

The [source audit](../reports/2026-11-21-postgresql-vanilla-audit.md) identifies
missing game-class APIs and the signed-JAR packaging constraint. In particular,
preserve pending legacy journal records: Java-serialized class names are part of
the existing on-disk format. Moving helper packages requires an explicit tested
legacy reader/conversion strategy, not just moving source files. The account
record reader now has such coverage; other legacy record types remain open. Existing SQL
dictionary/data provenance labels also remain historical; do not globally rename
them to this migration's date/version.

Vehicle gaps remain open. The legacy PostgreSQL store bypasses native SQLStore
startup chunk repair, and carrying provenance on a buffer is not sufficient to
prove that each native decoder is bound to that provenance. Complete both paths,
including corrupt/mismatched rows and pooled-buffer reuse, before enabling it.
The audit does not establish any new vehicle world/ID namespace contract.

Current tests execute module-produced account bytecode with fake JDBC, including
bootstrap, native-body continuation, nested reads, authentication failure,
account limits, integer bindings and cutover rejection. Original-JAR account and
server lifecycle transformation tests check unchanged member structure. The
packaged `PostgresAgentSmokeMain` exercises real premain registration, shaded
helper linkage, telemetry coexistence and shutdown with fake JDBC and game doubles.
Packaging checks also exercise the shaded JDBC service without loose dependency
JARs. Isolated PostgreSQL role/grant tests, complete journal compatibility,
async/outage recovery and dedicated-server gameplay acceptance remain required.

## Schema and engine-role requirements

The engine role is `pz_server_01`; backend migrations normally create objects as
`apocalipsebr`. This foundation executes no DDL. Future schema changes must include
explicit grants or verified creator defaults for the actual object-creating role.
For `public` and `player_rel`, preserve schema USAGE/CREATE, table/sequence ALL
PRIVILEGES and matching creator-role default privileges as specified in
[AGENTS.md](../../AGENTS.md). Those grants do not confer ownership/ALTER/DROP.

Validate feature queries, writes and sequence use as the engine role in an
isolated database with a separate migration owner. Do not contact the production
database merely to validate this port. Provision backend schema before engine
startup; do not transplant SQLite DDL into PostgreSQL.

## Telemetry and rollback

Port feature measurements with their exact populations, timing boundaries,
ownership and reset semantics. No PostgreSQL phases/counters have been wired to
the agent telemetry yet. Do not emit zeros for unimplemented measurements or
run the legacy telemetry emitter alongside the agent emitter as a shortcut.

There is no deployed PostgreSQL feature to roll back at this stage. Once
implemented, define a coordinated cutover/rollback covering journal generations,
pending operations, receipts and dictionary provenance. Removing `-javaagent`
alone cannot undo database writes or make SQLite authoritative again.
