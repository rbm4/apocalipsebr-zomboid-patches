# Stationary server-owned humans — 42.21.0

This experimental server-only scaffold advertises a stationary human through the
existing remote-player protocol. Clients use their own `IsoPlayer` implementation
to render the appearance, clothing, facing and idle animation. No client Java
patch or client Lua is required for that presentation.

The startup guide is opt-in. Its greeting brain selects a nearby visible visitor,
faces them, holds attention, waves, says `hi`, and restores its post and facing.
It remains stationary throughout. Seated poses, walking, combat, conversational
dialogue, trading, database subscriptions and cross-server replication are not
implemented. Compilation and isolated tests pass; appearance, greeting playback
and streaming still require a live multiplayer test with an unmodified 42.21.0 client.

## Enabling the guide

Build/deploy using the usual server patch procedure. Add these **JVM arguments**
to the server launch configuration, before the Java main class:

```text
-Dapocbr.guide.enabled=true
-Dapocbr.guide.x=14294
-Dapocbr.guide.y=11862
-Dapocbr.guide.z=0
"-Dapocbr.guide.name=Apocalipse Guide"
-Dapocbr.guide.facing=SE
-Dapocbr.guide.outfit=Tourist
-Dapocbr.guide.female=false
```

The default location is the requested world position `14294, 11862, 0`.
Only `-Dapocbr.guide.enabled=true` is required to use it; all other arguments
above are optional overrides. Coordinates are world positions, not cell/chunk
numbers; adding `.5` would center an actor on a tile, but these defaults preserve
the exact coordinates supplied. An enabled guide with invalid configuration logs
a single startup failure and is not created. Optional properties use the values
above as defaults. Facing
accepts `N`, `NW`, `W`, `SW`, `S`, `SE`, `E`, `NE`; outfit must name a vanilla
outfit available for the selected sex. The vanilla outfit system randomizes its
clothing once at construction, so clothes can vary after a server restart.

Configuration is read on the first active server tick. Restart to change startup
properties. Without `apocbr.guide.enabled=true`, the existing server behavior
continues without a guide. Successful creation logs:

```text
[ApocBR][ServerHumans] Spawned key=guide id=1024 at=<x>,<y>,<z>
```

## Package and lifecycle

All new implementation lives under `src/zombie/apocalipse/humans/`:

| Class | Responsibility |
| --- | --- |
| `ServerHumanDefinition` | Validated immutable identity, location and appearance input; optional startup configuration. |
| `ServerHumanFactory` | Main-thread creation/removal, identity allocation, registry and viewer reconciliation. |
| `ServerHuman` | Owns the presentation `IsoPlayer` and its viewers; construction and cleanup. |
| `ServerHumanTransport` | Vanilla `ConnectedPlayer`, `PlayerUpdateReliable` and `PlayerTimeout` packets. |
| `ServerHumanActuator` | Main-thread perception, target validation, facing, emote, speech and post restoration. |
| `ServerHumanEmotePacket` | Constructs the existing `State` packet with native emote parameters/events. |
| `brain/HumanObservation` | Immutable visitor snapshots; identity includes the player instance ID. |
| `brain/HumanAction`, `HumanActionExecution`, `HumanActuator` | Typed commands, execution lifecycle and engine port. |
| `brain/HumanStateMachine`, `GuideBrain` | Reusable transition base and the greeting policy. |

There are three engine integration changes:

1. The existing patched `GameServer` invokes `ServerHumanFactory.update()` once
   per active tick, after world initialization.
2. `PlayerDataRequestPacket` first asks the factory to resolve a presentation ID.
   All other IDs continue through the complete original 42.21.0 handler.
3. The existing patched `ChatServer` adds `sendHumanSay()` to route the actual
   say channel to initialized real recipients, without looking up the NPC as a
   logged-in chat author. Existing chat handlers and backend hooks are preserved.

Construction happens at zero coordinates to avoid automatic world simulation
registration. Constructor additions are detached before setting the configured
position. The actor lives in the factory registry, **not** `GameServer.Players`,
`IDToPlayerMap`, account/username maps, connection slots, or server moving-object
lists. This avoids account saves, sleep votes, fake connections and chunk pins.
There is no physical server-side collision body or authoritative combat actor.
Forced god mode is included in the client's appearance data, and ordinary server
player lookups cannot resolve a presentation actor as a damage target.

Every 500 ms, relevant fully connected clients receive a static reliable player
update with no movement, prediction distance, vehicle hit or action flags. A new
viewer first receives the full appearance. The heartbeat keeps idle characters
from expiring and lets vanilla packet handling restore them after chunk loads.
Reload requests reannounce the appearance and current emote state. Leaving
relevance stops updates but preserves client identity, like ordinary remote
players; vanilla removes inactive/unloaded world bodies and restores them when
updates return. This matters because `RangeBasedChat` caches the speaker object
by username. Recreating it on each range transition would leave speech attached
to an old object. Reconnecting clients receive the appearance again. Disconnected
connection instances are forgotten. Explicit factory removal sends a timeout to
all connected clients that have seen the actor, including clients out of range.
The wire username is exactly the definition's username, with no numeric suffix.
For the startup guide, `apocbr.guide.name` sets both username and display name.
Names are compared case-insensitively using Unicode NFC normalization and stay
reserved for the server process lifetime, even after removal. Creating another
actor with an already-used name fails rather than adding a suffix or reusing a
stale speech-cache identity. Names conflicting with an online player at creation
are also rejected. Restarting clears the reservations along with client sessions.

## Greeting state machine

```text
IDLE -> FACING_VISITOR -> HOLDING_ATTENTION -> SALUTING -> SPEAKING
     -> RETURNING_HOME -> COOLDOWN -> IDLE
```

The observer considers real connected living players within eight tiles, on the
same floor, with line of sight. Invisible players, missing squares, walls and
closed doors are excluded. Selection uses distance, breaking ties by online ID.
The selected identity stays locked for the interaction; a reused online ID with
a different player instance cannot inherit an old greeting.

Facing is applied immediately and continuously tracks the selected visitor's
current position throughout attention, waving and speech. Attention is independent
of the currently executing action, so waving does not freeze the target point or
restart the animation. The guide holds attention for one second, then submits
native `wavehi` as an emote substate for 2.5 seconds, sends the
literal `hi` through say chat once, and restores the exact home coordinate and
original facing. The duration is a server-side timeout, not a client animation
completion acknowledgement. It never walks away from its post. A three-second
cooldown separates interactions; a visitor who remains visible is greeted only
once until they leave perception and return. Other unhandled visitors can then
be selected.

Each action starts as `PENDING`, runs without blocking, and settles as
`SUCCEEDED`, `FAILED` or `CANCELLED`. Visitor loss cancels attention/emoting and
returns home without speaking. Failed actions also return home; a failed home
restoration stops the brain. Removal cancels an active emote and permanently
stops that brain. State transitions have explicit allowed edges and expose their
last from/to state, reason and monotonic timestamp. One-shot commands never
execute again after settling.

Native `State` packets carry `PlayerEmoteState`, the `emote`/`playing` parameters
and `EventEmote`. New/reloading viewers receive the current pose, but old speech
is not replayed. Local speech is sent once per connection, including split-screen,
within 30 tiles on the same floor. Disabled say chat and recipients without chat
initialization are respected. No fake sender account, chat login or client receive
handler is created. The chat log can show the internal wire username as author.

This first brain executes synchronously every 100 ms. Changed facing is published
at that cadence; unchanged idle actors retain the 500 ms network heartbeat.
There are no worker threads or blocking waits. The brain package has no engine
imports and is tested through its actuator port. Production actuator calls must
remain on the simulation thread. Future asynchronous decisions must emit commands
and consume execution results through a bounded handoff; moving the current
actuator calls to a worker would be unsafe.

IDs 1024–1087 are reserved for this scaffold, above vanilla's 255 connection slots
times four player IDs (0–1019). Allocation also checks existing server ID maps.
IDs are never recycled during the process lifetime, preventing late packets from
being associated with a replacement actor. There is a budget of 64 creations per
process, including removed actors; exceeding it fails explicitly. IDs stay modest
because client voice code allocates storage indexed by online ID. Do not enlarge
this range to `Short.MAX_VALUE`: its allocation loop uses a signed short.

## Factory API and future integration

After its first server update, simulation-thread Java code can create/remove
actors independently of the startup guide:

```java
ServerHumanDefinition definition = new ServerHumanDefinition(
    "guide-east", "__apocbr_guide_east", "East Guide",
    10000.5F, 10000.5F, 0, IsoDirections.S, false, "Tourist"
);
short presentationId = ServerHumanFactory.getInstance().spawn(definition);
ServerHumanFactory.getInstance().remove("guide-east");
```

The factory rejects duplicate keys/usernames and calls from other threads. It is
not exposed to Lua. A future database subscriber should hand immutable snapshots
to a bounded main-thread queue, then extend this lifecycle/transport with updates.
Remote identities should be namespaced by source server rather than reusing their
online IDs. No worker, queue, subscription, database schema, or movement support
is implemented here.

## Compatibility limits and validation

Clients see an ordinary remote human, so native player labels/list entries and
player-oriented interaction menus can appear. The factory does not log a real
user into chat, Steam, accounts or persistence; this is a presentation scaffold,
not a complete fake player session. Existing client mods may make stronger
assumptions about players than vanilla does. Interactions requiring a real server
player/account are outside this implementation.

Baselines inspected are `decompiled/zombie/characters/IsoPlayer.java`,
`IsoGameCharacter.java`, `SurvivorDesc.java`, `NetworkPlayerAI.java`, and the
42.21.0 `ConnectedPacket`, `PlayerPacket`, `PlayerDataRequestPacket`,
`PlayerTimeoutPacket`, `Prediction`, and `PlayerID` implementations.
The greeting additionally traces `StatePacket`, `CharacterID`, `StateID`,
`PlayerEmoteState`, `RangeBasedChat`, `SayChat`, `ChatBase`, `ChatServer`,
`LosUtil`, the vanilla emote menu, and player action-group transitions.

Verification:

```powershell
python .\tools\test_server_humans.py
python .\tools\test_human_brain.py
.\42.21.0\patchApocalipseBr.ps1 -PZDir "Z:\SteamLibrary\steamapps\common\ProjectZomboid" -DryRun
```

The lifecycle fixture compiles the production human classes with small engine
stubs and checks configuration, thread ownership, duplicate identities, reserved
ID allocation/exhaustion, constructor detachment, viewer transitions, reconnects,
reload requests, cleanup, cadence, outbound idle/emote packet fields, observation
filters, facing units, incarnation checks, split-screen speech and chat gating.
The actual production chat bridge method is extracted into its fixture. It also
checks that the vanilla data-request handler is preserved after the fallback.
The separate brain fixture compiles the engine-independent production package
and exercises transitions, timing, deterministic selection, action outcomes,
cancellation, removal, failure recovery, continuous target tracking through wave
and speech, and suppression of repeat greetings. The lifecycle fixture moves a
live visitor during attention and salutation and checks the actual outbound heading
and the separation between tracking updates and unchanged idle heartbeats.
The dry run compiles all server patches against the game JAR without deploying.
These checks do not initialize native game systems or prove client rendering.

Before claiming runtime support, start a test server and verify with two clients:

1. Both see the same clothed human at the configured coordinate/facing.
2. It remains stationary and visible for several minutes.
3. Leave and re-enter streaming range, reconnect, and join after startup.
4. Approach with two visitors: verify facing, wave, exactly one `hi`, restored
   facing, cooldown, and no repeated greeting while the same visitor stays.
5. Leave during attention/waving, try occlusion and different floors, then return
   after unloading the guide's area. Verify speech still attaches to its body.
6. Try attacks and native player menus; check both server and client logs.
7. Restart with the guide disabled; verify ordinary login, saves and sleep remain
   unaffected and no guide remains.

No server deployment or live multiplayer test was performed during implementation.
