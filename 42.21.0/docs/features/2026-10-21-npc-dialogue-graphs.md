# Cached NPC dialogue graphs (42.21.0)

The 2026-10-26 [respawn roster refresh](2026-10-26-npc-dialogue-roster-refresh.md)
adds incarnation-aware client rebinding, scalar lifecycle generation detection,
targeted refresh delivery and bounded recovery. Dialogue definitions and persisted
flags are unchanged; no new migration or lock/unlock policy is introduced.

## Emergency interruption

`HumanBehavior.emergency(observation)` is a pure safety query, valid even while
the behavior is suspended. `HumanModuleRuntime` asks its configured modules
without selecting a module, advancing its cycle timers or executing actions.
`EngageZombiesModule` reports a currently engageable territory zombie, using the
existing freshness/visibility/territory policy independently of attack retry
timers. `SurvivalModule` reports crowd/vital pressure or an unfinished recovery.
Other behaviors default to no emergency; this does not introduce player
hostility or add combat capabilities to profiles that lack them.

The factory continues bounded perception sampling during dialogue. On its next
simulation update, `ServerHumanDialogue` checks emergency policy before its
ordinary range/emote handling. It removes the session, stops the dialogue emote,
resumes the activity controller and emits `OnApocBRHumanDialogueClosed` with
reason `threat`. Normal runtime arbitration then runs in the same factory tick,
so configured survival priority still precedes engagement. The existing Lua
close-event relay sends the client its ordinary close notification.

Begin rejects a new lease while emergency policy is active. Token-based requests
also recheck policy before speech, graph effects or arrival, preventing requests
between factory updates from retaining an unsafe lease. An interrupted token and
graph cursor cannot be resumed; a later conversation requires a new lease once
policy permits it. No timers, graph cursors or emergency checks are persisted.
Trade ownership is unchanged. Future player threats must be introduced through
server-authoritative sensing and an explicit policy before they can interrupt.

Regression coverage executes production factory/perception/dialogue/runtime code
for a zombie arriving during conversation and same-tick combat takeover; approach
and open-session cancellation on survival pressure; emote cleanup, token replay,
re-reservation refusal and recovery. Pure module tests cover suspended monitoring,
stale sightings, territory limits, neutral players, priority and stopped runtime.
Live multiplayer UI closure and native combat playback still require validation.

Verification: `test_human_dialogue.py` (66 assertions plus PTBR/EN language runs),
`test_human_survival.py` (101), `test_human_engagement.py` (52),
`test_server_humans.py` (92), `test_human_brain.py` (72), `test_human_tasks.py`
(29), and `test_human_trading.py` (538) passed. The 42.21.0 server `-DryRun`
compiled 258 sources / 797 class files against the installed game JAR. No game
deployment was performed. The standalone brain fixture now includes the task
package required by the current shared actuator contract.

## Graph authority and persistence

The NPC worker reads V17's published graph catalog, V18 labels and scoped account facts before
publishing its startup snapshot. `ApocBRNpcDialogues` owns JDBC mapping;
`HumanDialogueCatalog` owns immutable detached content. `ApocBRNpcStore` owns
account-fact caches/capture and journals detached SQL through the existing
scope epoch fence. The catalog's atomic generation changes only for changed data.

`ServerHumanDialogue.install()` exposes bridge v4 and observes catalog generations
on the simulation thread. Changes emit the existing roster invalidation event.
The trusted server Lua facade adds:

| Function | Meaning |
| --- | --- |
| `dialogues()` | `{database,ready,generation}`; status only, no multilingual graphs. |
| `dialogueRoster(player)` | Personalized root views with resolved labels and available options. |
| `dialogueBegin(player,id,revision,node,option)` | Validate root choice, acquire normal lease and pin the engine graph. |
| `dialogueView(player,id,token)` | Resolved current/root presentation after arrival. |
| `dialogueSelect(player,id,token,node,revision,option,sequence)` | Validate order, range, facts and graph; execute response and advance. |
| `language()` / `text(key)` | NPC-only language and cached database label resolution. |
| `dialogueFlags(player,id,dialogueId)` | Cached earned facts for the authenticated account and live NPC. |
| `remember(player,id,token,dialogueId,flag)` | Capture a monotonic fact only for an arrived, nearby, owned session. |

Roster/nearby metadata now includes the actor's profile ID on the server. Only
Engine-filtered single-language content reaches clients. Conditions/effects remain trusted
server data. Existing leases, range/visibility checks and vanilla packet layouts
are retained. `HumanDialogueSession` pins a graph revision and owns cursor/order,
condition filtering and transitions. `ServerHumanDialogue` validates the live
actor/session, accepts account facts and executes speech. Lua transports only
identifiers into the engine and resolved presentation back to clients. No brain
queries SQL; the worker never accesses a live player or Kahlua table.

NPC language defaults to **PTBR**, independently of `user.language`, global
`Translator` state and client settings. Optional `-Dapocbr.npc.language=EN`
changes this NPC-only policy at restart; PTBR needs no JVM configuration.
`HumanLanguage` resolves requested locale, PTBR, then the graph's authored fallback.
It logs the effective configured locale at bridge installation and the first
translation fallback. Normalized NPC locale IDs are uppercase (`PTBR`, `EN`).
V18 defaults new graph fallback locales to PTBR without rewriting published graphs.

V18's `npc_engine.label_text` stores multilingual automatic greetings, operation
announcements and dialogue interface labels. The worker publishes labels and
graphs atomically after commit, refreshing on the existing 30-second cycle.
Standalone mode uses shipped EN/PTBR defaults for these labels. No global game
language is changed. Label placeholders are substituted in one pass.

Wire graph schema 2 contains only the current and root node, resolved node text,
available option IDs/titles/descriptions and resolved interface labels. It omits
alternate locales, future nodes, next-node edges, flags and unselected replies.
Selected responses return as plain text. HumanList framing remains protocol 2.
Graph revision and locale/content remain pinned for an open conversation; cursors
and tokens are never persisted. A terminal selection is sent before the normal
finish/close event. Lua compatibility registration remains for standalone flat menus.

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

Apply backend migrations V17 and `V18__Npc_Server_Language.sql` after V15/V16, then
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

Bridge v4 verification: dialogue authority/graph tests passed 52 assertions,
with separate JVM runs for default PTBR under `user.language=EN` and explicit
NPC EN, fallback, database labels and generation changes. Profiles (56), server
humans (80), event content (256), sequences (31), brain (72), roaming (548),
trading (538) and module runtime (32) fixtures passed. Isolated PostgreSQL
V15-V18/JDBC/worker checks passed 45 assertions; the backend Flyway migration
suite passed six tests using distinct migration/engine roles. These are isolated
tests, not live-server results.
The final full-workspace `42.21.0/patchApocalipseBr.ps1 -DryRun` passed against
the actual game JAR: 193 sources, 622 class files. A separate temporary baseline
plus this NPC patch also compiled successfully (192 sources, 620 class files).
No game deployment or production migration was performed.
