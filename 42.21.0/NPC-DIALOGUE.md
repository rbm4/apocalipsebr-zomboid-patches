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
`ISWorldObjectContextMenuLogic` decompiled sources. The existing factory and actor
classes remain the lifecycle owners; no actor enters normal account maps.

Lua calls these table functions using **dot syntax**:

| Function | Result/purpose |
| --- | --- |
| `list(player)` | Array of same-floor actors within 32 tiles: `id`, stable `key`, `name`, `x/y/z`. |
| `begin(player, id)` | Exclusive approach lease; actor metadata plus `token`, or an `error` table. |
| `arrive(player, id, token)` | Boolean; validate approach and open conversation. |
| `react(player, id, token, text, emote)` | Boolean; apply trusted server Lua speech/emote to an open conversation. Pass nil for absent text/emote. |
| `finish(player, id, token)` | Boolean; release only that visitor's matching lease. |

`ApocBRHumanAPI.version` is 1. These are trusted server APIs: clients use standard
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

## Brain and leases

`GuideBrain.DIALOGUE` suspends autonomous greeting/roaming while the visitor
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

Production Java dialogue tests pass 26 assertions for discovery, exclusive leases,
trade exclusion, approach/arrival validation, tokens, wrong visitors/floors,
reaction limits, emote settlement, timeouts, disconnect, despawn, lifecycle events
and Lua argument dispatch. Existing brain/lifecycle/trading fixtures also pass.
The patch compiles against the real local 42.21.0 game JAR using JDK 25 in dry-run
mode; no deployment was performed. The Lua mod has its own 43-assertion harness.

Still test actual menus/picking, UI and split-screen rendering, native pathfinding,
NPC stop/facing/animation replication, contention, disconnect and despawn in game.
Fixtures mock native engine boundaries and do not establish runtime behavior.
