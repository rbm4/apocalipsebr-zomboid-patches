# NPC dialogue roster refresh after respawn

Status: implemented for 42.21.0 and ExtractionMapUtils, isolated checks passed;
live dedicated-server/client validation remains required. No migration is needed.
Existing stable NPC keys and V30 respawn configuration already supply persisted
identity. This change introduces no locked dialogues, entitlements or unlock API.

## Ownership and delivery

`ServerHuman` assigns an immutable UUID incarnation to each constructed actor.
`ServerHumanDialogue` includes it alongside logical `key` and current native `id`
in public rosters, database-filtered rosters and session descriptions. It is an
ephemeral binding token, not an account identity or persisted NPC state field.

`rosterChanged()` atomically increments a process-local generation before emitting
the existing `OnApocBRHumanRosterChanged` event. The additive bridge v4 function
`ApocBRHumanAPI.rosterRevision()` reads that scalar without scanning live actors,
touching Lua tables or querying SQL. Lifecycle operations retain their existing
factory lock/owner contract. The async worker can advance the generation; Lua
reads it and performs roster construction/delivery on the normal Lua/server thread.
Vanilla LuaEventManager already queues non-main-thread events. The event is retained;
the scalar detects lifecycle changes independently of event subscription/delivery.
This does not claim the existing live-world worker is generally thread-safe.

ExtractionMapUtils server Lua compares the scalar on tick and coalesces changes
into the existing cached roster rebuild. Generation is checked before/after actor
discovery and before personalized delivery. A mismatched build is discarded and
rebuilt next tick. Successful idle ticks perform scalar comparisons, not actor
rescans or periodic roster broadcasts. Death sends a replacement without the NPC;
respawn sends its fresh binding to every connected subscriber.

Each subscriber retains pending delivery until framing and all send calls return
success. Failures are logged and retried at most once per second. One subscriber's
failed send cannot abort delivery to others. Global lifecycle changes bypass an
old retry delay. Frames retain protocol 2 bounds and monotonic delivery revisions.
Old bridges without the scalar/incarnation metadata retain event-based operation.

`HumanDialogs.refreshRoster(player)` is a trusted server-Lua integration point
that queues a personalized full view for one currently authenticated character,
even without an open conversation. It does not mutate content or grant choices.
It is suitable for a future targeted content-refresh service, while unlock policy
and persistence remain outside this change.

## Client binding and repair

The client maintains native-ID and stable-key indexes per local player. Complete
rosters atomically replace both indexes. Older/equal deliveries are ignored and
multipart fragments never replace the last complete view. A removed/replaced
incarnation closes its existing conversation, removes its panel and invalidates
queued dialogue actions. Context-menu callbacks resolve the latest actor by key
and recheck the selected option before sending Begin, rather than reusing a
captured actor table. The server retains all session/choice validation.

Discovery has up to three attempts per outstanding recovery, separated by at
least two seconds, with a ten-second reply/assembly timeout. Unknown NPC clicks
and stale menu callbacks can request bounded repair. Incomplete frames are dropped
on timeout; a newer frame batch gets its own timeout. Successful idle clients do
not poll. A replacement local character clears its indexes and gets a new budget.

## Rollout and verification

Deploy the server Java patch plus ExtractionMapUtils on server and clients, then
restart/reconnect. No client Java patch, database migration or modification to
dialogue graph definitions is required. Existing lifecycle diagnostics are joined
by `bridge_roster_changed`, `roster_rejected`, `roster_resync`, `binding_changed`
and the existing roster receive/apply logs under the mod's debugLog switch.

2026-10-26 checks: Java dialogue 69 assertions, mortality/respawn 32, worker async
31 and sync 26 (including intentional worker-failure injection); Lua roster 45,
dialogue client/server 89, database graphs/framing 25, panel 11 and label checks 11.
Login/map regression passed. Full target game-JAR `patchApocalipseBr.ps1 -DryRun`
passed with 296 Java sources and 874 class files. Source whitespace checks passed.
These are isolated results; no live deployment occurred.

Commands: `python tools/test_human_dialogue.py`,
`python tools/test_human_death_respawn.py`,
`python tools/test_npc_simulation_worker.py`; from the ExtractionMapUtils repository,
`npm exec --yes --package=fengari-node-cli -- fengari tests/human-roster.lua`
and the matching human-dialogue/graphs/panel and login-and-maps Lua fixtures, plus
`python tests/human-dialogue-labels.py`.

Live acceptance: keep two clients connected through death and respawn; verify old
menus disappear, the replacement's options work without reconnecting, an open
old-life conversation is closed, and split-screen recipients remain isolated.
