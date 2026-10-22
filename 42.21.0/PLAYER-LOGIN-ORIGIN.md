# Successful-login history and lobby teleport

Status: implemented for dedicated-server Build 42.21.0; awaiting live A-to-C validation.

## Ownership

`GameServer.receivePlayerConnectReady` records the connection immediately after
the existing fully-connected user log, before its completion callback. Both
SQLite and PostgreSQL reconstruction paths reach this accepted-connection hook.
Rejected connections, temporary client-profile reconstruction and Lua getters do
not create audit events. No client Java changes are required.

`ApocBRPlayerLogin.successfulLogin` captures only detached identity data and the
optional known persistent character UUID. The ordinary durable persistence journal
stores `successful-login-v1`; `ApocBRSuccessfulLogin` runs JDBC on journal replay.
No dictionary, item encoding, snapshot completeness or extraction/lobby setting
is consulted. The optional UUID getter reads an existing in-memory binding without
waiting for imports. Missing identities still get an account/world/slot audit row.

Backend Flyway migration V19 creates `player_rel.successful_login` and the
transactional function `player_rel.record_successful_login`. The function locks
the account/world/mode/slot, finds its previous occurrence and inserts the event
with `previous_server_id`. Event UUID replay returns that same previous result.
Commit loss therefore cannot duplicate an occurrence or compare it to itself.
JDBC publishes the result only after commit; a weak context keeps it attached to
the accepted player without sharing game objects with workers.

The slot identity follows existing persistence semantics: world, `username` or
`steam_coop`, identity key and player index. Username comparison is exact. Known
character UUIDs exclude other known lives. If either UUID is missing, history
uses slot identity; distinguishing unknown lives requires a separate stable
incarnation identity. No graph/table foreign key is required to record a login.

## API and Lua behavior

- `ApocBRPlayerLogin.isReady(player)`: false while the accepted event is pending;
  true after commit, including a first occurrence with no predecessor. Disabled
  history returns true for geometry fallback. Non-server-thread calls return false.
- `getPreviousServerId(player)`: cached previous server, or 0 if unknown/pending.
  Check readiness first. Does not read or write character mod-data or SQL.
- `hasChangedServer(player)`: true only for a ready known previous ID differing
  from the accepted connection's current ID.
- `teleportToLobby(player,x,y,z)`: existing server-thread native teleport, finite
  destination/living/connection guards and one-shot acknowledgement unchanged.

ExtractionMapUtils owns `LoginLobbyState` and the relocation policy. Its server
handler waits for the audit result before checking server change and geometry.
The client retains its 15-attempt, one-second budget; exhausted pending results
preserve the saved position. Lobby settings gate relocation only. Auditing works
without the extraction mod installed on the source server.

## Rollout and failure behavior

1. Apply backend `V19__Create_Successful_Login_History.sql` in game_runtime.
2. Install the 42.21.0 server patch on A, C and other participating servers.
3. Enable PostgreSQL connectivity and set stable unique registered
   `apocbr.postgres.serverId` values. `apocbr.postgres.loginHistory.enabled`
   defaults to true even if PostgreSQL player loading/relational sync is off.
4. Install the updated extraction Lua on the destination server and clients.

The migration explicitly grants schema/table/sequence/function privileges to
`pz_server_01`; the backend remains migration owner. Existing role bootstrap/default
privileges remain as described in AGENTS.md and the backend datasource guide.
Old `ApocBR.LastConnectedServerId.v1` values are ignored and no history is invented.
The first audited connection has unknown previous server.

`[ApocBR][LoginHistory] QUEUED` and `RECORDED` always log event UUID, slot/current
server or previous/current/changed result. No account identities or credentials
are printed. Queue errors log `QUEUE_FAILED`. At most 512 pending result listeners
are retained; overflow still journals the audit and reports `RESULT_CAPACITY`.
Such a player has unavailable origin and the Lua retry limit preserves position.

Game acceptance does not wait for JDBC. Local staging precedes normal journal
fsync (usually its 25ms writer cadence); sudden termination before fsync can lose
an event. After fsync normal replay/retry applies; exhausted audit operations are
quarantined for inspection rather than discarded. A queue failure is visible and
leaves origin unavailable; it does not undo an already accepted gameplay login.

Cross-server history uses captured `logged_at`; `recorded_at` is database ingestion
time and `login_sequence` breaks equal-time ties. Synchronize host clocks. Offline
journals are not visible to other servers until committed. Late replay cannot
replace a newer latest row, but cannot retroactively change a delivered decision.
Recording shares the existing journal's ordered replay/backlog. No new blocking
SQL runs on the simulation thread. Audit retention is operator-managed.

## Verification

- `python -B tools/test_player_login_origin.py`: production bridge/store/journal,
  absent UUIDs, known UUID enrichment, duplicate acceptance, cached origin,
  worker publication after commit, commit failure, journal recovery, same/different
  servers, first occurrence, queue failure, thread guards and native teleport.
- `python -B tools/test_postgres_player_reconstruction.py`: 531 reconstruction checks.
- `python -B tools/test_postgres_resilience.py`: production persistence coordinator
  and journal replay; fixture dependencies include the current dialogue catalog.
- `42.21.0/patchApocalipseBr.ps1 -DryRun`: full compile against configured 42.21.0
  game JAR; no deployment.
- Extraction `tests/login-position.lua` and `tests/login-lobby-state.lua`: pending
  origin retries and existing authoritative/native movement policy.
- Backend `SuccessfulLoginMigrationTest`: isolated PostgreSQL with distinct
  migration/engine roles; replay, previous-server result, missing graphs/UUIDs,
  life/slot/account/world separation, constraints, rollback and concurrency.

See backend `docs/successful-login-history.md` for audit queries and migration checks.
No live database migration or server deployment is performed by these checks.

Validation on 2026-10-21: all checks above passed, including 24 bridge/store/journal
checks, 531 reconstruction checks, persistence resilience/recovery fixtures,
the final full patch dry-run compile, and both backend migration tests on disposable
PostgreSQL 14.24 with zero skips. Extraction `tests/login-and-maps.lua` also passed.
