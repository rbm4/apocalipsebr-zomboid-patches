# Backend messages and batched zombie kills

Implemented source target: 42.22.1 dedicated server, 2026-11-04. This implements
the producer/migration portion of the [inbox proposal](../reports/2026-11-04-backend-message-inbox-and-zombie-kills.md).
Backend business handlers and game-engine message consumers remain future work.

## Ownership and mod dependency

`ApocalisebrPack/42.14/media/lua/server/ApocBR/Entities.lua` now owns the shared
entity facade. BeyondTen's Build 42 `mod.info` requires **APOCALISEBR_PACK**;
`require "ApocBR/Entities"` still resolves the same Lua module through the required
mod's search path. The BeyondTen copy is removed, leaving one completion poller.
The separate Build 41 root package is unchanged. Mastery behavior, snapshots and
five-second dirty flushes remain unchanged; see the [mastery guide](2026-10-29-beyondten-character-entities.md).

Server code in the same pack supplies `ApocBR/BackendMessages` and the
`ApocBR.BackendMessage` append-only mapping for `mod_data.backend_message`.
Only `ZombieKillsRecorded.v1` is currently allowlisted, with payload `{count=N}`,
where N is a positive integer at most 2147483647. Its canonical JSON text is
`{"count":N}`. Additional/nested payload fields are rejected. New event contracts
must explicitly extend validation; there is no arbitrary command dispatcher.

## Producer API and identity

```lua
require "ApocBR/BackendMessages" -- server Lua only
local context = BackendMessages.context(player)
if context.status == "OK" then
    local publication = BackendMessages.publish("ZombieKillsRecorded.v1", context,
        { count = 5 }, function(result)
            -- OK means committed (possibly an identical replay).
            -- Retain publication on temporary/uncertain failure and retry later.
        end)
    -- publication:retry(callback) reuses the private UUID and captured values.
end
```

`context(player)` calls the existing trusted relational character lookup in Java;
it performs no JDBC. Unbound/dead players return IDENTITY_PENDING. The returned
table carries an opaque detached Java capability plus display/session identity.
`append` accepts that capability, not a Lua/client UUID. Java injects character,
session, configured `apocbr.postgres.serverId`, world, identity mode/key and player
slot. Attempts to supply those fields in the append values are rejected. Lua
copies the capability reference; the capability contains scalars only, never a
live player, connection or Lua table.

This deliberately differs from live mastery writes: captured historical events
may finish/retry after the player disconnects, dies or changes life. They retain
the old character UUID. There is no active-life worker recheck that could discard
that fact. The table has no character FK because relational replacement may delete
old life rows; source identity is retained for later backend resolution. An event
can arrive before its character's initial snapshot is published; future consumers
must defer unresolved identities rather than attributing them to a new life.

No OnClientCommand publication path exists. The pack's modules are server guarded;
[ApocBRModEntities](../../src/zombie/savefile/ApocBRModEntities.java) enforces its
registered server Lua thread. Clients receive neither publication capabilities
nor database APIs through this feature.

## Append and acknowledgement contract

The shared facade supports `appendOnly=true` definitions and
`repository:append(context, messageId, values, callback)`. Messages use exactly
one UUID key, `message_id`. Generic save/patch/delete and character collection
operations cannot mutate append-only models. The Java bridge also refuses a
mutable mapping of `backend_message`. Existing mutable entity behavior is retained.

`publish` generates a UUID once through Java and privately freezes the context and
serialized payload. It returns a publication handle even when admission is
rejected. `retry` resubmits the identical detached message. One request can be in
flight per handle. The producer must retain the handle after failure.

The worker validates the mapped schema, executes INSERT ON CONFLICT DO NOTHING
and commits. On conflict it reads the immutable mapped columns: exact ID/content
equality returns OK/duplicate=true; different contents return CONFLICT. Replay
does not update revision, consumer status or error counters. A lost commit
acknowledgement returns OUTCOME_UNKNOWN and may safely retry this same handle.
Do not call `publish` again to retry a failed batch: that creates a different ID.
Payload comparison is exact text equality, not JSON semantic equality.

Execution uses the existing bounded persistence replay lane: 256 outstanding
requests, two-second SQL timeouts, and at most one mod request between durable
journal entries. Completions run through the sole facade's OnTickEvenPaused
handler, including PauseEmpty servers. No new per-kill worker thread is created.

## Zombie kill cache

`ApocalisebrPack/42.14/media/lua/server/ZKC_ServerHandler.lua` now accepts only
server OnZombieDead callbacks with a connected real IsoPlayer attacker. It does
not accept client counters. It buffers by captured session + character UUID and
publishes nonempty counts after 30 seconds of wall-clock time. There is no
100-kill threshold, all-player heartbeat, stat/position/XP collection or file I/O.
The old `ZKC_PlayerData.txt` export is no longer written or consumed by this mod.

`ZKC_Config.lua` defaults: `enabled=true`, `flushIntervalMs=30000`,
`retryIntervalMs=30000`, `maxEntries=512`. The one-second scheduler check uses
OnTickEvenPaused, not accelerated game minutes. OnSave also requests a flush;
it is not an acknowledged database save barrier.

Each entry keeps one frozen publication and a separate accumulating count. New
kills during an in-flight request are never cleared by its acknowledgement.
Admission rejection, unavailable bridge/database and OUTCOME_UNKNOWN retain the
batch and retry after 30 seconds. CONFLICT, SCHEMA_ERROR and INVALID_DATA block
that entry and log the condition; no automatic overwrite occurs. Successful clean
entries are removed. Detached entries continue after disconnect/death.

Kills before relational binding are held separately against the actual player
object, within the shared 512-entry limit. They merge only after identity resolves
while that player is connected/alive. Death/disconnect before binding discards
them with a throttled diagnostic, preventing reassignment to another life.
Pending counts are capped at signed-int maximum. At capacity new-life kills are
dropped with diagnostics; buffers never grow without bound.

Buffers and accepted mod requests remain **memory only**. Hard crashes or extended
database outages can lose uncommitted kills. No durable mod journal or synchronous
shutdown barrier was added. Repeated native death callbacks creating separate
publications are not deduplicated by message UUID; native death event behavior
still requires gameplay validation. No pooled zombie reference is kept as a
permanent deduplication key.

## Migration and future processing contract

Backend-owned migration:
`apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V36__Create_Backend_Messages.sql`.
It creates the inbox, immutable-content trigger, error history and failed-event
view. Mapped scalars use the existing types; payload is validated JSON **text**,
not a new JSONB Lua mapping. Revision stays 1. Database defaults initialize
recipient=`backend`, status=`pending`, availability/creation timestamps and zero
failures. `recipient=game_engine` reserves routing vocabulary; no game listener,
consumer poll, engine acknowledgement permission or backend publisher is enabled.

The migration owner (`apocalipsebr`) owns DDL and processing state. The engine
(`pz_server_01`) receives column-level INSERT/SELECT on immutable producer fields,
USAGE on mod_data, and no UPDATE/DELETE/error-table/sequence/function rights.
V36 revokes table grants inherited from creator defaults before granting these
columns. Role membership, schema ownership or unrelated broad grants must still
be managed by administrators; the migration does not remove unrelated grants.

The future single backend worker should process its database effects and mark a
message processed with `processed_at` in one transaction **when those effects use
the same datasource/transaction**. No lease or claim-token columns exist. Website
app transactions remain separate today; ordinary @Transactional cannot make them
atomic with this queue. Cross-datasource rewards still require an idempotent
receipt in the reward transaction or a deliberate persistence consolidation.

On processing failure: roll back business work (or roll back to a savepoint), then
insert one row in `mod_data.backend_message_error` and commit that diagnostic.
Its trigger atomically increments failure_count, retains last error code/detail
and timestamp, and changes status to failed on **failure six** (strictly >5).
The error row and counter roll back together. Failed/processed events reject new
error entries. The ready partial index includes only pending events; the separate
`mod_data.backend_message_failed` view exposes quarantined records. Error history
stores every committed failure. A failed transaction cannot persist its own error
record without a rollback/savepoint boundary. Retry scheduling via available_at,
manual requeue policy and retention remain backend work. Do not delete IDs while
producer retries can still occur, or a replay could become a fresh event.

No rewards, balance changes, file importer removal in the backend, leases,
LISTEN/NOTIFY, backend worker or client endpoint were implemented. Cutover must
drain/checkpoint old files and ensure only one source awards kills.

## Verification

- `py -3.12 tools/test_backend_messages.py`: production Java append with actual
  PostgreSQL in a disposable cluster, V32/V36 as migration owner, separate engine
  role, broad creator-default grant revocation, immutable triggers, real committed
  duplicates/uncertain outcomes, validation and sixth-error quarantine; actual Lua
  producer/facade under transport/game doubles for batching, retry and isolation.
- `py -3.12 tools/test_mod_entities.py --version 42.22.1`, scoped fixture with
  `APOCBR_TEST_VERSION=42.22.1`, `test_entity_patches.py` and
  `test_beyondten_persistence.py`: mutable entity and mastery regressions pass.
- `42.22.1/patchApocalipseBr.ps1 -BuildOnly`: 305 sources / 903 class files pass
  against installed 42.22.1 JAR. Retained in
  `42.22.1/artifacts/build_20261104_130312`; installation unchanged.

No migration was applied to a deployed database. Native Kahlua opaque-context
round trips, event ordering/death/disconnect, mod load dependency and paused/live
server delivery still require dedicated-server validation. Tests use actual JDBC
but game/identity doubles; Lua tests use Lupa rather than native Kahlua.
