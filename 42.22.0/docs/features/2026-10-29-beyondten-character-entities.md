# BeyondTen character-scoped Lua entity persistence

Implemented 2026-10-29 for the 42.22.0 dedicated server. This extends the
[42.21 entity scaffold](../../../42.21.0/docs/features/2026-10-26-lua-mod-entities.md).
The Lua mod retains its original mastery calculations and GlobalModData cache.
PostgreSQL is preferred during initialization and receives subsequent dirty batches.
This does not remove GlobalModData or introduce client database access.

## Ownership and command flow

ClientController -> ClientMasteryService handles retries and applies Sync/SyncPerk
to the local player's presentation mirror. Client requests are only vanilla
`sendClientCommand(player, "BeyondTen", "RequestSync", {})` calls. ServerController
receives the authenticated player from `OnClientCommand`, rate-limits requests,
ignores their payload, and invokes ServerMasteryService. Neither character UUIDs
nor revisions, repository names, arbitrary operations or XP are accepted from clients.

The existing Client.lua/Server.lua remain the owners of XP arithmetic, reservoirs,
bonuses and mirrors. Small hooks defer initialization, mark dirty state, request
flushes and invalidate death callbacks. ServerMasteryService owns persistence state.

## Trusted identity and detached requests

[ApocBRPlayerRelationalMutations](../../src/zombie/savefile/ApocBRPlayerRelationalMutations.java)
provides `EntityContext(candidate, session, character)` from its actual TRACKED/
DirtyQueue binding. Restored characters are bound through
`ApocBRPlayerRelationalSync.registerPostgresPlayer()` using `persisted_character_id`.
New lives can be bound before their initial transaction commits; a UUID alone is
not evidence that a character can yet be queried.

[ApocBRModEntities](../../src/zombie/savefile/ApocBRModEntities.java) adds server Lua
functions `character(player)` and `submitCharacter(model, operation, player, session, rows)`.
The Lua facade exposes `resolveCharacter(player, callback)` and repository methods
`loadOrCreateForCharacter`/`saveAllForCharacter`. The scoped methods inject
`character_id` from Java and reject an explicitly supplied key. Rows contain
mapped values without that key, plus a string revision. At most 256 rows are accepted.
Existing generic repository operations remain available to trusted server Lua.

The bridge checks the server thread and session at admission, then copies scalar
rows and the identity into immutable worker inputs. The replay worker never reads
the live player or Lua tables. It checks the synchronized detached session binding,
locks the matching persisted player_slot using world/identity mode/account/slot,
and verifies active_character_id against the bound UUID. Missing/not-yet-published
lives return IDENTITY_PENDING. Stale bindings return STALE_SESSION. The slot lock
is held through commit, serializing scoped initialization and life replacement.
This is not a distributed ownership lease for two servers using the same life.

Lua delivers completions on `OnTickEvenPaused`, including PauseEmpty servers.
Callbacks check the current UUID/session again. A service-entry identity check
also prevents callbacks from an earlier death/reset from mutating a new cache.

## Initialization and saves

1. Read/sanitize only the existing server GlobalModData record, retaining aliases.
   Never seed from inbound player ModData.
2. Resolve the trusted identity and submit loadOrCreate with legacy seed rows.
3. In one transaction, validate the mapping and select all rows for the character.
   Existing rows win in full, including opaque temporarily-disabled perk IDs.
4. Only if no rows exist, insert seeds with revision 1. Seeds include real
   zero-valued catalog perks up to the 256-row bound. A local record marked with
   another character UUID seeds zero XP, not the previous life's progress.
5. Return the committed collection, replace the cached table contents in place,
   mark local records with the character UUID, replay deferred changes in order,
   publish the player mirror and full client sync, and enable mastery bonuses.

The service retains up to 4096 initialization changes per player and 512 entries.
It defers native mastery awards, setters, clears and recovered-XP calls while
loading. Legacy synchronous APIs cannot return the future asynchronous result;
the recovered-XP handler reports zero applied while its work is deferred.
At queue saturation it logs rather than growing without bound; native XP events
not enqueued are left in the raw XP map for the normal delta scan after recovery.

Five-second dirty snapshots and OnSave requests use a generic character snapshot
tracker and the scoped patch operation. Only changed rows are submitted and only
changed mutable fields appear in UPDATE SET lists; affected rows commit or roll
back together. Inserts expect revision zero and updates compare/increment their
current revision. Changes made while a batch is in flight keep a newer dirty
generation. Clearing patches zeroes for previously nonzero perks, retaining
initialization evidence; ordinary gameplay does not delete rows. Empty-load import
requires this policy. Death clears all local aliases and invalidates the service.

Temporary admission/database/identity failures retry after five seconds. Conflicts,
uncertain commits, stale sessions, schema and invalid-data errors stop automatic
overwrites and log the status, retaining GlobalModData. Stale sessions revoke
readiness. Reconnect/restart reloads PostgreSQL; no automatic conflict merge is
claimed. Clean offline entries are released; dirty disconnected entries attempt
flush while their binding remains valid.

## Generic snapshot patch API

```lua
-- Ordinary entities retain the last loaded/committed scalar snapshot automatically.
entity:set("xp", entity:get("xp") + amount)
entity:patch(function(result) -- only changed non-ID fields are sent
end)

-- Character collections retain one snapshot per mapped composite key.
local snapshot = repository:characterSnapshot(loaded.rows)
snapshot:patch(player, context, currentRows, function(result)
    -- result.rows contains only changed rows, with updated string revisions.
end)
```

The generic facade caches previous committed and current captured scalar values.
Callers supply full current rows; it computes field deltas and supplies revisions
from the committed baseline rather than trusting caller revisions. Inserts carry
all fields. A successful acknowledgement advances only captured values, never newer
live edits. Failures, conflicts and uncertain outcomes retain the old baseline.
Changing and reverting before submission produces no patch; reverting while a
write is in flight still requires a compensating patch after acknowledgement.
Only one patch may be in flight per entity or tracker.

Unchanged calls immediately deliver `OK, unchanged=true` without a database request
or revision increment. Omitted collection rows are not deleted. The scoped Java
operation returns only affected rows/revisions and avoids reading the entire
collection before and after a patch. It retains active-life/session checks, mapping
validation, bounded inputs, prepared values and atomic revision checks. Full save
operations remain available explicitly. Mirror publication/RequestSync are separate
from persistence dirtiness, so presentation repair does not trigger database writes.

## Schema and durability limits

The existing backend-owned V32 table `mod_data.beyondten_mastery` is unchanged:
UUID character_id + text perk_id primary key, double precision xp, bigint revision.
No new DDL or grants are required. Existing engine SELECT/INSERT/UPDATE permissions
are required. Mapping validation remains log-only at startup and runs on operations.
There is currently no FK to player_rel.character; old-life mastery rows can remain
after replacement deletes relational lives. Active-life checks prevent reuse by a
new character. Cleanup/FK lifecycle policy remains future backend migration work.

Mod requests use an in-memory lane, not durable journal records. A five-second
interval is not a guaranteed maximum loss window during outages. An OnSave request
is not a synchronous commit barrier; accepted writes may finish after world save.
Transfers/shutdown and multi-server ownership need an explicit acknowledged flush
and fencing design before claiming lossless handoff. Conflict/uncertain outcomes
must not be retried by overwriting the database with an old full local snapshot.

## Verification

- `python tools/test_mod_entities.py --version 42.22.0`: existing production Java
  generic operations and actual Lua entity lifecycle, including paused polling.
- `python tools/test_character_mod_entities.py`: production scoped SQL/transactions
  with JDBC/identity doubles: DB precedence, atomic seed admission, detached values,
  injected identity, batch rollback, ambiguous commits, stale sessions/lives,
  spoofed keys and collection bounds.
- `python tools/test_entity_patches.py`: actual generic Lua entity/collection
  snapshots with transport doubles: no-ops, changed rows/columns, false/zero values,
  bigint revision strings, inserts, in-flight edits/reverts, retries and invalid
  acknowledgement retention. The scoped JDBC fixture also checks partial UPDATE
  columns, absence of collection rereads, patch inserts and atomic patch rollback.
- `python tools/test_beyondten_persistence.py`: actual Shared/Server/Client,
  controllers, services and entity facade with game/transport doubles: database
  precedence, queued XP, save generations, clear-to-zero, command isolation,
  failures/retry, new lives, stale death/session callbacks and client retries.
- `python tools/test_postgres_resilience.py --version 42.22.0`: production
  coordinator/journal regression passes, including mod admission, recovery and drain.
- `42.22.0/patchApocalipseBr.ps1 -DryRun`: full production source build against the
  installed game JAR passes, producing 902 class files on the current working tree.
  No deployment performed.

Live PostgreSQL behavior, native XP/event ordering, reconnects, load under outages
and process shutdown still require dedicated-server validation. Lupa is not Kahlua;
JDBC proxies verify SQL/control flow, not PostgreSQL locking or schema permissions.
