# Server-owned NPC dialogue ? 42.21.0

Implemented server-side; awaiting dedicated-server/client validation. The matching
ExtractionMapUtils Lua mod supplies context menus, timed actions and dialogue UI.
No client Java replacement or network packet format change is required.

## Engine boundary

`ServerHumanDialogue` is a new custom class, not a replacement for a vanilla
class. It installs a server-only `ApocBRHumanAPI` Kahlua table during the first
factory tick. Table entries are `JavaFunction`s with explicit argument counts;
this avoids copying/changing the game's `LuaManager` solely to expose a facade.
Verified against the exact 42.21.0 `LuaManager`, `LuaEventManager`/`Event`, and
`ISWorldObjectContextMenuLogic` decompiled sources. The factory/logical lifecycle and actor
classes remain the lifecycle owners; no actor enters normal account maps.

Lua calls these table functions using **dot syntax**:

| Function | Result/purpose |
| --- | --- |
| `roster()` | Array of every factory NPC: `id`, stable `key`, `name`; no player range/floor filter, coordinates or session state. |
| `list(player)` | Array of same-floor actors within 32 tiles: `id`, stable `key`, `name`, `x/y/z`. |
| `begin(player, id)` | Exclusive approach lease; actor metadata plus `token`, or an `error` table. |
| `arrive(player, id, token)` | Boolean; validate approach and open conversation. |
| `react(player, id, token, text, emote)` | Boolean; apply trusted server Lua speech/emote to an open conversation. Pass nil for absent text/emote. |
| `finish(player, id, token)` | Boolean; release only that visitor's matching lease. |

`ApocBRHumanAPI.version` is 2. The existing `list`/lease APIs remain compatible. These are trusted server APIs: clients use standard
Lua commands and cannot call the facade directly. The mod validates registered
NPC/option keys, ordering, throttles and callbacks before asking Java to react.
The engine never accepts a client-provided player identity or text as authority.
Registration is in `ApocExtractionMapUtils.HumanDialogs.register`, with hooks via
`HumanDialogs.on`; see the mod's `.docs/features/npc-dialogue.md` for examples.

Java registers `OnApocBRHumanDialogueReserved`, `OnApocBRHumanDialogueOpened`,
and `OnApocBRHumanDialogueClosed`, each receiving `(player, metadata)` on the
server simulation thread. Metadata includes actor ID/key/name/position and token;
closed metadata also has `reason`. These events appear on the first initialized
factory tick, so Lua must wait until they exist before calling `.Add`. The mod
subscribes from `OnTick`. Definitions/hooks can be registered at mod load time.

## Cached roster subscriptions (bridge v2)

The matching Lua mod sends `HumanList` once per local character with an assigned
online ID. The server stores that authenticated subscriber and replies with a
cached world-wide public roster and process-local revision. Empty snapshots are
complete; no periodic client roster traffic is needed. Out-of-order older
snapshots are ignored by the client. Characters far from the guide still learn
its ID, so moving into its vicinity later does not require another request.

The server-only `roster()` facade returns public identities for the factory's
bounded registry (at most 64 presentation IDs). It never returns coordinates,
lease tokens, player connection details or Lua callbacks. The existing nearby
`list(player)` and authoritative `begin`/arrival/reaction checks remain in place.
The new roster does not authorize remote interaction or require client Java.

`ServerHumanFactory.spawn/remove` emit `OnApocBRHumanRosterChanged` with no
arguments, on the simulation thread after successful registry changes. Failed
or missing removals emit no event. Lua subscribes once the custom event exists,
invalidates its cache and coalesces changes into one rebuild on the next tick.
`HumanDialogs.register` also invalidates it; server integrations can explicitly
call `ApocExtractionMapUtils.HumanDialogs.pushRoster()` to schedule an update.
Subscribers receive a full replacement snapshot, including removals and empty
rosters. Disconnected/replaced visitor instances are dropped during fan-out or
periodic cleanup. When Java is not ready, Lua retains initial subscribers and
pushes on bridge readiness rather than asking clients to retry.

This requires updating the **server Java patch and both sides' Lua mod**.
Bridge v1 lacks `roster()` and its lifecycle event; the new Lua logs
`roster_deferred reason=roster_api_unavailable` until v2 is installed. There is
no proximity-filtered one-time fallback, because it would miss distant NPCs.
Dynamic spawn/removal and dialogue changes subsequently reach subscribed
clients without reconnecting. Native player-presentation packet formats and
all dialogue lease authority are unchanged. Lua logs stay behind the sandbox
`debugLog` option; no per-tick roster scan/broadcast is added.

## NPC engine architecture

NPC definitions now select an independent registered `profileId`; the runtime
holds a generic behavior/activity controller instead of assuming `GuideBrain`.
[Profiles and lifecycle](NPC-PROFILES-LIFECYCLE.md) documents dormant/active/dead
logical state, capability flags and the future PostgreSQL boundary. Dialogue
services retain their authentication and lease validation. Profiles with
`dialogue=false` are omitted from the public roster and rejected by the API.

All NPC behavior follows the [architecture contract](NPC-ENGINE-ARCHITECTURE.md).
Autonomous operation speech now uses an injected content provider and generic
`Sequence(Say, Wait, Say)` actions with a four-second inter-phrase pause.
Dialogue/trade state transitions cancel the active composite, discarding any
unsent second phrase while preserving existing lease authority and cleanup.

## Brain and leases

`HumanActivityController.DIALOGUE` suspends the selected autonomous behavior while the visitor
approaches and chooses options. It cancels existing greeting actions through the
existing transition/action runner. The session controller faces the actual player
on each 100-ms factory update. Dialogue and native trade cannot steal each other's
state. Finishing restores the stopped post through the existing return/cooldown
path, and suppresses an immediate duplicate automatic greeting.

The simulation-thread registry has at most one lease per actor and visitor.
Tokens are process-local sequence numbers; identity also requires the exact
`IsoPlayer` and owning connection. Tokens are not payment receipts or secrets.
Session removal precedes the close event to make callbacks/repeated closes safe.
NPC IDs retain the factory's existing no-reuse-per-process policy.

Discovery is read-only. Reservation requires same-floor visibility within 8 tiles.
Approach expires after 30 seconds. Arrival and reactions require distance at most
2.5 tiles; active sessions close beyond 3 tiles. Idle conversations expire after
60 seconds and accepted reactions refresh that timeout. Speech is limited to 256
characters; emote names to 40 ASCII alphanumeric/underscore characters. Emotes
settle after 2.5 seconds. No background threads, sleeping, per-tick persistence or
external calls are added.

Factory updates expire sessions, detect death/disconnect/replaced player or
connection/visibility loss, track facing and settle emotes. `ServerHuman.dispose`
closes dialogue before stopping the brain/removing presentation. Client path
failure/cancel/queue removal and panel close request release; server deadlines
cover lost cancellations. Existing native trade, money receipts and backend
wallet conversion are unchanged by dialogue.

## Validation

From the patch repository:

```powershell
python tools/test_human_dialogue.py
python tools/test_human_brain.py
python tools/test_server_humans.py
python tools/test_human_trading.py
.\42.21.0\patchApocalipseBr.ps1 -DryRun
```

Production Java dialogue tests pass 34 assertions for discovery, exclusive leases,
trade exclusion, approach/arrival validation, tokens, wrong visitors/floors,
reaction limits, emote settlement, timeouts, disconnect, despawn, lifecycle events
and Lua argument dispatch. Existing brain/lifecycle/trading fixtures also pass.
The patch compiles against the real local 42.21.0 game JAR using JDK 25 in dry-run
mode; no deployment was performed. The matching Lua mod passes 68 dialogue/client-flow assertions, 27 roster
subscription/cache/push assertions and 5 shared debug-gate assertions. Client
fixtures assert zero extra requests across ten idle minutes and empty snapshots;
server fixtures assert zero roster scans/messages over 1000 steady-state ticks,
coalesced multi-subscriber updates and preserved nearby-start validation.
Java tests cover world roster independence from range/floor, public fields,
zero-argument bridge dispatch, v2 advertisement and lifecycle invalidation.
The 42.21.0 dry run compiles 171 production Java sources against the game JAR;
no classes or runtime classpaths were deployed.

Still test actual menus/picking, UI and split-screen rendering, native pathfinding,
NPC stop/facing/animation replication, contention, disconnect and despawn in game.
Fixtures mock native engine boundaries and do not establish runtime behavior.
