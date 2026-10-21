# Cached NPC dialogue graphs (42.21.0)

The NPC worker reads V17's published graph catalog and scoped account facts before
publishing its startup snapshot. `ApocBRNpcDialogues` owns JDBC mapping;
`HumanDialogueCatalog` owns immutable detached content. `ApocBRNpcStore` owns
account-fact caches/capture and journals detached SQL through the existing
scope epoch fence. The catalog's atomic generation changes only for changed data.

`ServerHumanDialogue.install()` exposes bridge v3 and observes catalog generations
on the simulation thread. Changes emit the existing roster invalidation event.
The trusted server Lua facade adds:

| Function | Meaning |
| --- | --- |
| `dialogues()` | `{database,ready,generation,profiles}`; cached graphs keyed by profile. |
| `dialogueFlags(player,id,dialogueId)` | Cached earned facts for the authenticated account and live NPC. |
| `remember(player,id,token,dialogueId,flag)` | Capture a monotonic fact only for an arrived, nearby, owned session. |

Roster/nearby metadata now includes the actor's profile ID on the server. Only
Lua-filtered public content reaches clients. Conditions/effects remain trusted
server data. Existing leases, range/visibility checks and vanilla packet layouts
are retained. The Lua registry pins a graph revision for each conversation; Java
retains actor/connection/movement authority. No brain queries SQL or owns content
selection, and the worker never accesses a live player or Kahlua table.

ExtractionMapUtils' **Lua** OnCreatePlayer/OnGameStart hook initiates the
ordinary HumanList client command after the online ID exists. The extraction Lua
handler authenticates it, calls memory-backed Java getters and replies with bot
identities plus localized graph data. There is no new Java login event. The older
`ApocBRPlayerLogin` helper is request-driven; ServerPlayerDB no longer calls it.

Profiles/graphs refresh on the existing 30-second worker cycle. Invalid/missing
V17 content fails closed at startup instead of spawning the Lua starter guide.
Runtime read failures preserve the last complete immutable catalog and trigger
the store's existing health/availability gates. Whole catalogs publish after
transaction commit, never partially. Graphs are bounded to 32 nodes, 128 choices,
12 choices/node, four locales, 256 KiB text; SQL publication and JDBC both validate.
The loader additionally verifies Java's UTF-16 256-character speech boundary.

Account facts are scoped by server/world/NPC/dialogue and Steam ID (or exact
username). Cached facts load at startup. Requests read memory; capture queues a
detached fact for the next worker pass, which submits an idempotent insert through
the shared journal. Old epochs cannot write after a different owner takes over.
Up to 100,000 scoped facts / 128 facts per NPC-dialogue-account are supported.
Facts survive death, reconnect and content revisions once persisted. Cursors and
leases reset. Administrative resets need a restart. Capture is asynchronous:
new facts can be lost on crash before worker/journal staging. No reward/credit
settlement guarantees are implied.

Apply backend migration `V17__Create_Npc_Dialogue_Graphs.sql` after V15/V16, then
deploy the server patch and ExtractionMapUtils on clients/server. Existing NPC
persistence flags/pool/credentials are reused. Without SQL NPC persistence,
legacy Lua registration remains available. No live migration or deployment was
performed during implementation.

Verification:

```powershell
$env:APOCBR_TEST_POSTGRES_BIN = 'Z:\postgresql\bin'
python tools/test_npc_persistence.py --manager-test
python tools/test_human_dialogue.py
python tools/test_server_humans.py
python tools/test_player_login_origin.py
python tools/test_postgres_players.py
.\42.21.0\patchApocalipseBr.ps1 -DryRun
```

The persistence fixture compiles the real mapper/worker, starts a temporary local
PostgreSQL cluster, migrates as apocalipsebr, reads/writes as pz_server_01, and
tests seed content, publication immutability, missing fallback/bindings, cache
readiness, account isolation, journal facts, epoch fencing and reconnect/shutdown.
`--manager-test` also runs the backend's targeted Flyway/JUnit test through Maven
against a separate private database. It skips unrelated main compilation/resource
copying and uses cached Maven dependencies. Lua graph/wire/UI tests reside in
ExtractionMapUtils. Runtime picking, UI layout, native teleport/anti-cheat,
multiplayer contention and crash timing still need dedicated-server validation.
