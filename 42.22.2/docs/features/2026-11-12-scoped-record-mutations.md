# Scoped record mutations for wallet persistence

Created 2026-11-12. Target: server 42.22.2. Implemented; isolated tests passed;
live deployment pending. Supersedes the recurring snapshot path described in
[wallet entity checkpoints](2026-11-11-wallet-entity-persistence.md).

`ApocBRModEntities` now exposes `documentLoad(documentId)` and
`documentMutate(documentId, expectedRevision, mutationId, operations)` through
the owner-thread-only Lua API. The pack facade wraps these as
`Entities.document(id):load(callback)` and `:mutate(...)`. Existing entity,
character and append-only repositories are unchanged.

Java captures the registered engine server ID and current native save-world
before admission, serializes the Lua array to immutable JSON and uses the
existing bounded persistence replay worker. At most 256 completion reservations
exist across old and new APIs, with 32 completions drained per Lua poll. No
player/Lua object crosses into JDBC, and poll/identity remain in-memory operations.

One runtime batch can contain at most 256 `{collection,key,payload}` or
`{collection,key,deleted=true}` operations. Payload is bounded JSON text wrapping
one record. Revision-zero initialization can import up to 100,000 records once;
the aggregate 32 MiB Java character JSON bound still applies. JDBC performs one
prepared `mod_data.mutate_entity_document` call, with the existing two-second
statement timeout. This path has no repeated JDBC schema/PK introspection.
SQL executes on the existing game_runtime connection and commits before the Lua
result is queued. No runtime schema DDL is introduced.

The manager's V54 migration owns the fixed schema/function. A small scope row
provides revision locking and the last mutation UUID/body digest. Named records
are upserted/deleted by primary key atomically. An identical last mutation can
be replayed after `OUTCOME_UNKNOWN`; a conflicting body/revision fails closed.
The wallet replays the identical batch after five seconds, without reloading or
reserializing unrelated history. Startup loads records and the scope revision
using one SQL statement/snapshot, capped at 100,000 records and 32 MiB aggregate
record/key characters; overflow returns LIMIT_EXCEEDED without partial results.

The wallet serializes business operations on the owning Lua thread. It persists
only declared changed records, rebuilds derived queues on load and excludes
HTTP timers/request handles from payment entities. Existing custody/payment
commit barriers and backend idempotency keys are retained. Native inventory
cannot share a SQL transaction; the existing interrupted-handoff limitation
continues to apply.

## Deployment

Stop both wallet-enabled server instances. Apply manager V54 through the
game-runtime datasource, then deploy Java patches, pack facade and wallet as a
coordinated update and restart both JVMs. V54 imports old snapshots atomically
and rejects subsequent v1 snapshot writes. The old projection tables become
historical archives, so downgrading the wallet without an explicit data
conversion is unsupported. No production changes were performed here.

- [Mod persistence and failure behavior](../../../../ApocalipseBR-ExtractionMapUtils/.docs/features/wallet-entity-persistence.md).
- [Manager schema and migration tests](../../../../apocalipsebr-manager/manager/docs/wallet-entities.md).

## Validation

- `py -3 tools/test_mod_entities.py --version 42.22.2`: **348 Java checks** plus
  existing Lua repository lifecycle/scoped collection checks. New checks include
  detached input, engine scope capture, one SQL call, startup records, missing
  state, ambiguous commit, admission and batch validation.
- `py -3 tools/test_wallet_store_kahlua.py`: actual installed game Kahlua runs
  the production pack/store/services fixture; **35 assertions** with async
  database/API doubles. Includes zero writes for unchanged admission retries.
- Manager `WalletMutationMigrationTest`: **7 PostgreSQL 17.6 tests**, no skips:
  migration/rerun, original data, atomic rollback, replay/CAS, deletion/scope,
  3,000 unchanged historical records, concurrent writers and import/runtime bounds.
- Exact target-JAR `patchApocalipseBr.ps1 -BuildOnly` compilation passed; no loose
  classes were deployed. Final artifact path is recorded in the task result.

Source/fixture verification does not measure live CPU reduction. Dedicated-server
load/restart, actual item replication and real database/API outage smoke tests
remain necessary after the coordinated rollout.
