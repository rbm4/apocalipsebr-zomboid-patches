# Offline SQLite character migration — 42.21.0 server

`ApocBRPlayerMigration` provides an opt-in, one-shot startup backfill for local
characters that have not logged in recently. It uses the existing native decode,
load-quality collection, detached snapshot, relational capture and atomic
publication code. It does not simulate a player connection or create login history.

## Configuration and rollout

Migration is disabled by default. Add this JVM argument before the Java main class:

```text
-Dapocbr.postgres.playerMigration.enabled=true
```

The existing `apocbr.postgres.enabled=true` and
`apocbr.postgres.playerRelational.enabled=true` are prerequisites. The latter
defaults to true. PostgreSQL connectivity, migrations and normal relational-sync
permissions must already work; this worker adds no tables or grants. It can run
with either SQLite or PostgreSQL gameplay player loading selected.

Run the preferred server first, inspect its drain summary and resolve failures,
then run subsequent servers. Existing complete imports are retained. Collisions
from other source datasets are logged and the incoming import is discarded;
the existing PostgreSQL character and local source rows are preserved. Disable
the flag after completing migration. Restarting with it enabled rescans the local
source and skips complete imports rather than overwriting them.

Source dataset identity follows normal login sync: explicit
`apocbr.postgres.playerRelational.sourceDatasetId`, or a UUID derived from the
current save-directory string. Keep that identity stable and distinct per source
save. Shared slot identity remains `(world, identity_mode, identity_key,
player_index)`; dedicated servers use login usernames and Steam coop uses SteamID.
The survivor display name is only a diagnostic label. Different `world` values
are distinct namespaces in the existing reader; sharing PostgreSQL alone does not
make different world names resolve to the same character.

## Selection and recent logins

The source is `<currentSaveDir>/players.db`, table `networkPlayers`. The worker
captures the maximum local ID at startup and walks rows in ID order, one at a
time, using short read-only SQLite connections. It does not retain all names or
character blobs in memory, hold a read transaction throughout migration, create
missing source files, or follow newly added rows indefinitely. A row for another
world or an invalid identity/slot is denied and logged.

For each username it reads `whitelist.lastConnection` from the **local**
`<cachedir>/db/<servername>.db`, even when gameplay account access uses PostgreSQL.
It interprets the native `yyyy-MM-dd HH:mm:ss` value in the server JVM timezone.
A login within the preceding seven days, including exactly seven days, skips
every character slot for that account. This implements the operator's assumption
that recently logged-in accounts have already synced; it does not verify a
PostgreSQL snapshot for those skipped rows. The field is account-level and updates
during login admission, before character acceptance. See
[successful-login history](2026-10-21-player-login-origin.md) for the separate
accepted-connection audit.

Missing/empty/malformed dates, duplicate account rows, future dates and ambiguous
or nonexistent local times are treated as unknown and remain eligible. An
unreadable/missing account database is also logged and treated as unknown. These
cases do not stop other characters from migrating. Survival time and migration
time are never substituted for historical login dates.

## Threading, exclusion and persistence

The first server-loop update starts a dedicated daemon worker, including on an
empty or paused server. SQLite, PostgreSQL, hashing, graph projection and audit
file writes run on that worker. Native `IsoPlayer` construction, binary loading
and snapshot copying run through a single pending continuation on the server
thread. One large character can therefore still occupy a server tick during
native decoding/copying; asynchronous persistence does not make native engine
objects safe to construct on a JDBC thread.

Before loading, the server thread checks live players, connection usernames,
coop SteamIDs and login attempts registered before asynchronous authentication.
Online or connecting accounts are skipped. It reserves the eligible account
until the persistence attempt finishes. A new matching login during that short
reservation receives the existing temporary database access denial and must retry;
unrelated logins continue. Reservations are local to this server, not distributed
session locks across servers. Complete-import publication uses the relational
per-slot transaction lock, shared across source datasets, and checks the head
again before committing. An existing foreign source is never overwritten.

The worker does not register the temporary player for live mutation tracking or
call the native login load method's destructive error branch. Incomplete captures
are rejected before publication. A failed commit, bad source data or collision is
logged and processing advances; there is no automatic retry loop for a failed
row during that run. Atomic publication plus repeatable source reads allow manual
retry on a later restart. The worker waits for each terminal outcome, then waits
**one fixed second** before selecting the next character, including after skips,
collisions and failures. Shutdown interrupts pending continuations/delays and
joins the worker before persistence pools drain or close.

## Audit

The append-only UTF-8 file is `<currentSaveDir>/player-migration.log`, mirrored to
the normal server log. Each run logs startup settings and dataset identity,
selection, recent-login decisions, loading, capture completion, persistence,
denials, collisions and failures. Character records include local row ID, login
username, survivor display name and slot. Control characters and field separators
in names are sanitized. Credentials, raw player bytes and raw SQL exception
messages are not logged.

`COLLISION_DISCARDED` identifies incoming characters rejected for a foreign source
dataset. `FAILED` reports the exception type and SQLState when available.
`DRAINED` reports selected, migrated, skipped, collision and failure totals;
`STOPPED` marks interrupted execution and `ABORTED` marks an enumeration/startup
failure. Zero failures/collisions in a drain summary does not validate recently
skipped accounts. The worker flushes every file event before proceeding; audit
file failures stop the run rather than silently dropping collision reports.

## Validation

- `python -B tools/test_postgres_player_migration.py`: actual migration worker,
  real temporary read-only SQLite databases, server-thread native-load boundary,
  recency parsing/boundary, unknown dates, collision/failure isolation, online and
  pre-auth exclusion, login reservation, one-second pacing, source preservation,
  and shutdown while awaiting a server continuation. PostgreSQL/engine ports use
  controlled fixtures.
- `python -B tools/test_postgres_player_relational.py`: production relational
  capture/JDBC behavior, including shared slot locks across source datasets.
- Exact 42.21.0 game-JAR compile via `patchApocalipseBr.ps1 -DryRun`.

Live migration, real mod inventory decoding and concurrent login timing still
require staging-server validation. No live data migration or deployment is part
of implementation verification.

Related: [relational schema and sync](2026-10-14-player-relational-schema.md),
[PostgreSQL reconstruction](2026-10-15-player-relational-login.md).
