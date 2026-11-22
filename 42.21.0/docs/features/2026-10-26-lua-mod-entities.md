# Server Lua entity bridge (42.21.0 scaffold)

Created 2026-10-26. Server target only. This is an explicit repository scaffold,
not an enabled BeyondTen persistence replacement. Existing BeyondTen source,
global ModData, XP handlers, character mirrors and client commands are unchanged.

## Ownership and execution

`GameServer` installs `ApocBRModEntities` immediately after `LuaManager.init`,
before mod files load. The bridge records that server/Lua thread. Calls from
clients or other threads are rejected. No network packet or client command
exposes this service. SQL and JDBC operations exist only in server Java.

Lua declarations describe logical models, physical table names, fields and IDs.
Java restricts tables to `mod_data`, validates identifiers, and generates prepared
SQL. Values always use bound parameters. Trusted server mods can access tables
in that schema; namespaces are not an isolation mechanism between server mods.

Submission copies scalar keys/values into detached immutable Java structures.
It does not acquire a connection, wait for JDBC, retain a live player or retain
a mutable Lua table. The existing `ApocBR-postgres-replay` worker executes requests
using the shared connector/pool. No additional thread, pool or database engine
is introduced. One mod request runs between durable journal entries; neither
lane starves the other while the database remains available.

The request lane holds at most 256 queued tasks. The bridge separately reserves
at most 256 requests including completed-but-unconsumed results. Models are limited
to 16, fields to 32, composite IDs to 4 fields, and strings to 4096 characters.
These limits bound retained input/result data. Statement timeout is two seconds;
connection acquisition/transport retain the connector's existing worker limits.

The Lua `OnTick` hook polls up to 32 results, removes pending callbacks, then
invokes them on the owning Lua thread. A callback failure is caught and logged
without values, SQL or driver diagnostics; it does not interrupt other deliveries.
Workers never call Lua. This does not make callbacks safe to mutate NPC-worker-owned
state; that subsystem still requires its own ownership handoff.

## Declarative mapping and repository API

New files in the local BeyondTen mod:

- `42/media/lua/server/ApocBR/Entities.lua`: reusable entity/repository facade.
- `42/media/lua/server/BeyondTen/Persistence/MasteryEntity.lua`: declaration and
  commented usage example. Loading it makes no database requests and installs no
  player/XP hooks. With no Java bridge, no mastery repository is registered.

Example declaration (already present in the scaffold):

```lua
local Mastery = ApocBREntities.define("BeyondTen.Mastery", {
    table = "beyondten_mastery",
    fields = {
        character_id = { type = "uuid", id = true },
        perk_id = { type = "string", id = true },
        xp = { type = "number" },
    },
})
```

Supported types are UUID strings, strings, double-precision numbers, exact
integers and booleans. Integers must remain within Lua's exact double range;
revisions are decimal strings to preserve bigint precision. Fields are required,
non-null scalars. Nested tables, arbitrary expressions, SQL strings, nullable
fields, joins, collection queries and automatic DDL are intentionally unsupported.

Call `Mastery:validate(callback)` after database startup to check the physical
mapping asynchronously. The same validation runs before every operation: mapped
columns and JDBC types, bigint revision column, and exact primary-key columns.
Validation does not certify arbitrary database triggers, checks, grants or extra
columns. Migrations must ensure no additional required fields prevent insertion.
The backend owns the initial table through
[V32__Create_Mod_Entities.sql](../../../../apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V32__Create_Mod_Entities.sql).
Its game-runtime Flyway flow applies the migration and grants the existing
`pz_server_01` engine role schema usage and row CRUD privileges. The engine receives
no DDL grant from V32. No separate executable schema copy is maintained here.
Adding the migration file does not itself apply it to a running database;
runtime registration never creates or modifies tables.

```lua
Mastery:find({character_id = trustedCharacterId, perk_id = "Aiming"}, function(result)
    if result.status == "OK" then
        local entity = result.entity
        entity:set("xp", entity:get("xp") + 5)
        entity:save(function(saved)
            -- saved.status == "OK" means the transaction committed.
        end)
    elseif result.status == "NOT_FOUND" then
        -- Missing is distinct from a failed query. Initialization is caller policy.
    end
end)
```

`Mastery:new(values)` creates an unsaved entity with revision `"0"`. `get`, `set`,
`isDirty`, `getRevision`, `save(callback)` and `delete(callback)` are explicit
operations. IDs cannot be changed through setters. A setter updates memory and
marks it dirty; it does not submit SQL. Save captures the current generation and
full field set. Successful acknowledgement advances revision and clears only the
captured generation: later edits remain dirty. Each entity allows one in-flight
save/delete. There is no automatic flushing, retry or identity map between finds.

Insert uses revision zero and `ON CONFLICT DO NOTHING`. Update/delete include the
expected revision in their predicate; update increments it transactionally. A
stale record returns `CONFLICT`. Reload/reconciliation belongs to the consumer.
Hard delete is available for repository testing/administration, not character
death/transfer policy: delete/reinsert resets revisions and permits ABA conflicts.
Character ownership fencing and tombstones must be designed before live integration.

## Results, admission and durability

Accepted submissions return true from Lua and deliver callbacks later through
`OnTick`. Admission rejection calls the callback immediately with `BUSY` or
`UNAVAILABLE` and returns false. Malformed declarations/requests raise synchronously
without admission. The PostgreSQL coordinator must already be initialized and
AVAILABLE; this scaffold does not start the database engine from a Lua call.

Results use `status`: `OK`, `NOT_FOUND`, `CONFLICT`, `SCHEMA_ERROR`,
`DATABASE_ERROR`, `INVALID_DATA`, or `OUTCOME_UNKNOWN`. Successful finds include
`values`, `revision` and a Lua `entity`; successful saves include the new revision.
No SQL, credentials or raw driver messages are returned. A commit/connection-close
failure after a write commit begins is conservatively `OUTCOME_UNKNOWN`: reload
and reconcile instead of blindly replaying.

Requests share the replay worker but **are not durable journal entries**. Admission
does not mean saved; only successful completion acknowledges commit. Pending work
does not survive process crashes and there is no automatic retry after ambiguous
commit. This is a deliberate scaffold boundary, not a claim of exactly-once writes.
Accepted requests may wait through coordinator recovery. There is no cancellation
or callback timeout yet. If Lua stops polling, retained callbacks/results consume
their reservations and eventually produce BUSY rather than unbounded growth.

Shutdown closes admission and drains accepted tasks before stopping the worker.
Results can remain undelivered once Lua ticks stop; committed writes are not undone
by absent callbacks. The journal's existing startup/recovery/discard behavior is
otherwise preserved. Installing the bridge again while requests remain outstanding
is rejected; live Lua reload is not supported for pending repositories.

## Integration still required

Before routing any BeyondTen XP through this service, add trusted character/life
identity resolution, ownership fencing across servers, load gating, stale-session
completion checks, XP batching, acknowledged transfer flush, death/reset tombstones,
and one-time legacy import. The example takes an already trusted character UUID;
it does not infer identity from a client, online ID, SurvivorDesc ID or username.
There is currently no automatic application to canonical XP or client sync.

## Validation

- `python tools/test_mod_entities.py`: actual Java mapping/SQL/transaction boundary
  with mocked JDBC, and actual Lua modules under Lupa. Covers detached inputs,
  prepared parameters, conflicts, schema/PK mismatches, missing records, invalid
  values, commit uncertainty, admission bounds, wrong-thread access, dirty edits
  during save, callback failures, and passive scaffold loading.
- `python tools/test_postgres_resilience.py`: existing production coordinator/journal
  fixtures plus request-lane saturation, replay-thread execution, exception survival
  and closed admission.
- `42.21.0/patchApocalipseBr.ps1 -DryRun`: compiles against the installed game JAR.

Dedicated-server validation remains required: Kahlua module loading/event order,
real PostgreSQL migrations/permissions/constraints, pool saturation, outage recovery,
Lua callback timing and shutdown with accepted requests. Lupa is not the game's
Kahlua VM. The JDBC fixture is not a live PostgreSQL integration test.
