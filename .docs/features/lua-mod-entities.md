# Server Lua entity persistence

The server Lua module installs `ApocBRModEntities` at the normal exit of the
original Build 42.22.2 `LuaManager.init()`. Vanilla loads server Lua afterward
through `GameServer.doMinimumInit()`. This restores the global used by
ApocalisebrPack's `ApocBR/Entities` facade and the Wallet bootstrap.
The legacy 42.22.2 [entity class](../../42.22.2/src/zombie/savefile/ApocBRModEntities.java)
defined this contract. The matching original
[server startup](../../42.22.2/decompiled/zombie/network/GameServer.java) and
[simulation event](../../42.22.2/decompiled/zombie/gameStates/IngameState.java)
establish the installation and polling order.

The facade still receives row mapping and revision-based CRUD, scoped collection
reads, bounded JSON, document load and mutation, character repositories, and
append-only backend messages. Wallet uses the manager's existing V54 document
tables and `mod_data.mutate_entity_document` routine. PostgreSQL assigns the
document revision and recognizes retries with the original mutation UUID.
Character requests derive the character and session from `PlayerMutations`.
They revalidate that binding on the worker and lock the matching
`player_rel.player_slot` row before checking the active character. A detached
message token can still publish for its original character after disconnect.

The Lua owner copies inputs before submission. One FIFO worker borrows
transactional connections from the shared agent pool and publishes results
after the transaction. Its 256 reservations include results waiting to be
consumed. The Lua facade polls up to 32 results through vanilla's
`OnTickEvenPaused`, including when empty-server simulation is paused.
Mutations whose commit result could not be determined return `OUTCOME_UNKNOWN`.
Shutdown stops admission, gives the worker up to 10 seconds to drain, then
interrupts it and logs any discarded queued requests. The worker shuts down
through the Lua lifecycle or the pre-close callback on the PostgreSQL pool.

## Configuration and database objects

- `apocbr.lua.enabled` controls the Lua hook. The bridge is exposed to server
  Lua only. With `apocbr.postgres.enabled=false`, persistence requests return
  `UNAVAILABLE`, and data-only helpers such as JSON remain available.
- `apocbr.postgres.serverId` supplies the registered server ID for scoped
  records, Wallet documents and character/message provenance. Scope combines
  that ID with the native save-world for document and collection reads.
- Character repositories and message identity need the live binding supplied by
  [PostgreSQL player authority](postgresql-players.md). Without a bound character,
  identity calls return `IDENTITY_PENDING`.
- Manager migrations own the `mod_data` tables and functions. Wallet needs the
  game-runtime V54 mutation migration and the engine role's table and function
  grants. Mapped repositories need the grants for their tables. Character
  operations also need the player schema grants for the slot lock and character
  lookup. The agent performs no DDL.

## Verification

`mvn package` exercises the original-class transformation checks for the
`LuaManager.init()` hook. `tools/Test-LuaAgent.ps1` launches the shaded agent
against the original archive and checks client isolation, disabled PostgreSQL
admission and continued Lua initialization.

`LuaEntityOriginalSmokeMain` also runs with the pack's `ApocBR` Lua directory
and the Wallet's `media/lua` directory as extra arguments after its cache path.
Use a disposable PostgreSQL database with manager game-runtime migrations for
the player slot/character schema, Mastery, backend messages, fixed-power areas
and Wallet documents. Set server ID 1 and connect as the engine role. Its
fixture expects a username slot for `fixture-user` in `entity-fixture` with
character `00000000-0000-0000-0000-000000000012` and no Mastery rows, plus a
fixed-power area with ID `own` for server 1 in that world. Run from the game
runtime directory with the original JAR first, the shaded agent and only the
compiled smoke fixture on the classpath.

On Windows with Java 25 and PostgreSQL 17.6, that fixture exercised Wallet
bootstrap and a committed mutation, stale revision, batch rollback, scoped
collection reads, missing schema, live character seed and patch, append replay
after unregistering the player, and draining a write at worker shutdown. The player fixture seeds
the agent binding directly. Full dedicated-server startup with the installed mod
set, native player login, and Wallet gameplay remain live acceptance checks.
Original archive SHA-256 is
`3C4A7C5F149D784758C6FB5F8FA85F3A21D572E38BC081400E89035F8120D287`.
