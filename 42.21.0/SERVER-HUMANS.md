# Stationary server-owned humans — 42.21.0

This experimental server-only scaffold advertises a stationary human through the
existing remote-player protocol. Clients use their own `IsoPlayer` implementation
to render the appearance, clothing, facing and idle animation. No client Java
patch or client Lua is required for that presentation.

The startup guide is opt-in. It stands at its configured world coordinates; this
does not implement a seated pose, dialogue, AI, trading, database subscriptions,
or cross-server replication. Compilation and isolated tests pass; appearance and
streaming still require a live multiplayer test with an unmodified 42.21.0 client.

## Enabling the guide

Build/deploy using the usual server patch procedure. Add these **JVM arguments**
to the server launch configuration, before the Java main class:

```text
-Dapocbr.guide.enabled=true
-Dapocbr.guide.x=14294
-Dapocbr.guide.y=11862
-Dapocbr.guide.z=0
"-Dapocbr.guide.name=Apocalipse Guide"
-Dapocbr.guide.facing=S
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

There are only two engine integration changes:

1. The existing patched `GameServer` invokes `ServerHumanFactory.update()` once
   per active tick, after world initialization.
2. `PlayerDataRequestPacket` first asks the factory to resolve a presentation ID.
   All other IDs continue through the complete original 42.21.0 handler.

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
Reload requests reannounce the appearance. Leaving relevance sends a timeout;
returning or reconnecting receives a new appearance. Disconnected connection
instances are forgotten. Explicit factory removal notifies current viewers and
releases the actor's descriptor and sound state.

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

Verification:

```powershell
python .\tools\test_server_humans.py
.\42.21.0\patchApocalipseBr.ps1 -PZDir "Z:\SteamLibrary\steamapps\common\ProjectZomboid" -DryRun
```

The isolated fixture compiles all four production classes with small engine
stubs and checks configuration, thread ownership, duplicate identities, reserved
ID allocation/exhaustion, constructor detachment, viewer transitions, reconnects,
reload requests, cleanup, cadence, and outbound idle packet fields. It also
checks that the vanilla data-request handler is preserved after the fallback.
The dry run compiles all server patches against the game JAR without deploying.
These checks do not initialize native game systems or prove client rendering.

Before claiming runtime support, start a test server and verify with two clients:

1. Both see the same clothed human at the configured coordinate/facing.
2. It remains stationary and visible for several minutes.
3. Leave and re-enter streaming range, reconnect, and join after startup.
4. Try attacks and native player menus; check both server and client logs.
5. Restart with the guide disabled; verify ordinary login, saves and sleep remain
   unaffected and no guide remains.

No server deployment or live multiplayer test was performed during implementation.
