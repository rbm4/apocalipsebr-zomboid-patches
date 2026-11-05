# Server-owned humans — 42.21.0

This experimental server-only scaffold advertises a human through the
existing remote-player protocol. Clients use their own `IsoPlayer` implementation
to render the appearance, clothing, facing and idle animation. No client Java
patch or client Lua is required for that presentation.

The startup guide is opt-in. It roams within its spawn building on the same floor,
pauses to face a nearby visible visitor, holds attention, says `Olá!` while waving, then
resumes roaming after a cooldown. Seated poses, combat, cross-server replication are not implemented. Optional
[mod-driven NPC dialogue](2026-10-20-npc-dialogue.md) adds right-click choices, timed-action
walk-up, exclusive conversations and server-controlled reactions. Native guide trades are supported as described below. Compilation and isolated tests pass; appearance, greeting playback
and streaming still require a live multiplayer test with an unmodified 42.21.0 client.

## Architecture contract

All NPC engine features must follow the
[object-oriented architecture contract](2026-10-20-npc-engine-architecture.md): separate
policy, injected content, immutable action plans, execution state, engine
capabilities and transport. Generic bounded `Sequence`/`Wait`/`Say` actions now
support reusable multi-phrase behavior with cancellation through the existing
brain lifecycle. Role selection belongs to registered profile data and the composition root.
See [profiles and lifecycle](2026-10-20-npc-profiles-lifecycle.md) for immutable revisioned
profiles, guide/roaming-greeter/stationary registrations, shared interaction
ownership, dormant/active/dead logical state, presentation-ID bounds and the
future PostgreSQL persistence boundary. No profile persistence is installed yet.

## Enabling the guide

The optional [PostgreSQL event controller](2026-10-19-lobby-event-control.md) sends a separate
announcement after the wave. When both exist, it speaks the active operation's
estimated remaining time first, waits four seconds, then speaks the next
operation countdown as a second phrase. Content comes from one cached snapshot
through an injected provider; no operation-specific actuator method is used. Apply manager runtime V13 first.
It uses a cached server-thread snapshot and the existing speech transport;
database polling and lobby activation do not depend on the guide or its visitors.
Other actor keys keep their original speech. Enable it separately as documented
in that contract, with the extraction mod and backend migration installed.

Build/deploy using the usual server patch procedure. Add these **JVM arguments**
to the server launch configuration, before the Java main class:

```text
-Dapocbr.guide.enabled=true
-Dapocbr.guide.profile=lobby-guide
-Dapocbr.guide.x=14294
-Dapocbr.guide.y=11862
-Dapocbr.guide.z=0
"-Dapocbr.guide.name=Apocalipse Guide"
-Dapocbr.guide.facing=SE
-Dapocbr.guide.outfit=Tourist
-Dapocbr.guide.female=false
```

The default location is the requested world position `14294, 11862, 0`.
Only `-Dapocbr.guide.enabled=true` is required for presentation; with PostgreSQL enabled, apply the sales migration below or disable trades; all other arguments
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

The actor implementation lives under `src/zombie/apocalipse/humans/`; its typed sales persistence adapter is `savefile/ApocBRHumanSales`:

| Class | Responsibility |
| --- | --- |
| `ServerHumanDefinition` | Validated immutable identity, location and appearance input; optional startup configuration. |
| `ServerHumanFactory` | Main-thread creation/removal, identity allocation, registry and viewer reconciliation. |
| `ServerHuman` | Owns the presentation `IsoPlayer` and its viewers; construction and cleanup. |
| `ServerHumanTransport` | Vanilla `ConnectedPlayer`, `PlayerUpdateReliable` and `PlayerTimeout` packets. |
| `ServerHumanActuator` | Main-thread perception, target validation, facing, emote, speech and post restoration. |
| `ServerHumanEmotePacket` | Constructs the existing `State` packet with native emote parameters/events. |
| `ServerHumanRoaming` | Indoor route selection and home-building confinement. |
| `ServerHumanMovement` | Physical route execution, native collision/occupancy checks, walking gait/speed and prediction. |
| `movement/IndoorRoutePlanner` | Original bounded cardinal-grid search for short reachable routes. |
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

Every 500 ms, relevant fully connected clients receive a reliable player
heartbeat, including routine movement updates. Changes in facing or moving/stopped
state (including blocked movement) are published at the 100 ms brain cadence.
Position integration and collision checks remain at that cadence. Walking carries
the native movement flag and collision-checked native prediction; there are no vehicle-hit or unrelated action
flags. Straight movement advertises speed/direction and at most one second of
prediction (16 eighth-tile units at 2 tiles/second), covering
the 500 ms send interval plus jitter allowance. Lookahead crosses connected,
collinear tile centers, but stops short of the next corner or route endpoint
with a 0.15-tile margin and is disabled if the segment is
blocked or too short. Stopping sends static prediction again. Clients advance
the predicted target each frame through their existing remote-player code.
Routine movement and idle heartbeat cadence is two updates/second per relevant
connection per actor, with immediate turn/start/stop exceptions bounded by the
10 Hz brain cadence. Clipping can shorten prediction near corners; smoothness
under latency and packet loss still requires in-game validation. The prediction fields
already exist in both static and moving packet layouts. A new
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

## Indoor roaming and greeting state machine

Walking mechanics now belong to the per-NPC `ServerHumanMovement` engine.
Roaming supplies its selected route and building constraint; transport consumes
the movement engine's snapshot. See [2026-10-21-npc-movement.md](2026-10-21-npc-movement.md) for the
implemented capability boundary, cancellation semantics and direct-route tests.

```text
IDLE -> ROAMING -> IDLE
IDLE/ROAMING -> FACING_VISITOR -> HOLDING_ATTENTION -> SALUTING -> SPEAKING
     -> RETURNING_HOME -> COOLDOWN -> IDLE
```

After a three-second idle pause, the guide chooses a reachable indoor destination
and walks at 2 tiles per real second, increased from 1.2. This is
an initial walking-speed tuning value; real player speed also depends on native
animation and character conditions. Movement consumes leftover elapsed time
across waypoints instead of pausing/discarding a tick at every tile center.
A route search visits at most 256 tiles
and plans at most 12 cardinal steps; there is no diagonal corner cutting.
Selection is reproducible from the actor ID and route sequence. The implementation
is original; it does not load or copy A-Life code.

Routes use the spawn square's `BuildingDef` and fixed floor, including connected
rooms of that building. Missing squares, exterior/other-building tiles, stairs,
floor holes, solid furniture and blocked boundaries are excluded. Existing engine
collision APIs (`testCollideAdjacentAdvanced` and `PolygonalMap2.lineClearCollide`)
validate each route edge, and actual movement is revalidated every step. Occupied
destination tiles and newly closed/blocked doors stop the route. The guide never
opens doors or climbs stairs/windows. It waits and retries another short route;
it does not teleport past an obstacle or force chunks to load.

The building is bound lazily when the spawn square loads. Outdoor/unloaded spawn
points keep the guide stationary. Loss of the loaded area or relevant viewers
cancels roaming. Elapsed movement time is capped to 150 ms per update so a long
server stall cannot produce a large jump. The presentation remains detached from
normal server player simulation; collision-aware position integration belongs to
this controller, not a fake logged-in client.

The observer considers real connected living players within eight tiles of the
guide's current position, on the
same floor, with line of sight. Invisible players, missing squares, walls and
closed doors are excluded. Selection uses distance, breaking ties by online ID.
The selected identity stays locked for the interaction; a reused online ID with
a different player instance cannot inherit an old greeting.

Facing is applied immediately and continuously tracks the selected visitor's
current position throughout attention, waving and speech. Attention is independent
of the currently executing action, so waving does not freeze the target point or
restart the animation. The guide holds attention for one second, then submits
native `wavehi` as an emote substate for 2.5 seconds, sends the
literal `hi` through say chat once, and restores the original facing at the point
where it stopped to greet. The legacy `ReturnHome` action restores this local
stopping point; it does not jump back to spawn. The duration is a server-side
timeout, not a client animation completion acknowledgement. A three-second
cooldown separates interactions. Each visitor character also has a two-minute
cooldown, measured from successful speech (or the initial attempt if it fails).
Leaving and returning does not clear that timer. Once it expires, the same
visitor becomes eligible again even if still nearby. Other visitors have
independent timers. The bounded, in-memory history is cleared on actor removal
and server restart; it identifies character instances, not persistent accounts.

An eligible visitor interrupts a running roaming action before facing starts.
After greeting/cooldown, the guide resumes choosing indoor routes. Routes that
succeed or become blocked settle into another idle pause. Each action starts as
`PENDING`, runs without blocking, and settles as
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
online IDs. No subscription, outdoor travel or multi-floor traversal is implemented here.
Guide sales reuse the existing connector journal and replay worker.

## Compatibility limits and validation

Clients see an ordinary remote human, so native player labels/list entries and
player-oriented interaction menus can appear. The factory does not log a real
user into chat, Steam, accounts or persistence; this is a presentation scaffold,
not a complete fake player session. Existing client mods may make stronger
assumptions about players than vanilla does. Interactions requiring a real server
player/account are outside this implementation except for the explicit trading adapter below.

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
python .\tools\test_human_roaming.py
python .\tools\test_human_trading.py
python .\tools\test_postgres_resilience.py
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
The roaming fixture shares the engine stubs and compiles the actual route planner,
controller and transport. It checks bounded/deterministic search, building/floor
confinement, floor holes/stairs/obstacles, occupancy, dynamic blockage/unload,
movement speed, route completion, post retention, current-position perception,
walking flags and factory-driven translation. Speed/prediction regressions check
0.2-tile movement per 100 ms across tile centers, full two-tile/one-second
prediction on straight corridors, and continued clipping at corners and obstacles.
The native timing contract is verified against this build's decompiled
`network/fields/character/Prediction.java` (`distance / (speed * 8)`); server
integration and transport share the same speed. The brain fixture also verifies
route interruption, resumption and failure recovery.
The dry run compiles all server patches against the game JAR without deploying.
These checks do not initialize native game systems or prove client rendering.

Validation for the 2026-10-20 speed/prediction adjustment: the commands above
passed 548 roaming, 78 lifecycle/packet and 72 brain assertions; the server patch
`-DryRun` compiled all 169 Java sources successfully against the installed game
JAR. No deployment or live multiplayer validation was performed. Confirm walking
pace and pauses near corners under actual client latency after applying the patch.

Before claiming runtime support, start a test server and verify with two clients:

1. Both see the same clothed human at the configured coordinate/facing.
2. It walks within the building/floor, pauses between short routes, and remains
   visible for several minutes without crossing furniture/walls/closed doors.
3. Leave and re-enter streaming range, reconnect, and join after startup.
4. Approach during walking with two visitors: verify an immediate stop, facing,
   wave with exactly one `Olá!`, separate timing follow-up, restored facing, cooldown and
   resumed roaming. The same visitor should not be repeatedly greeted while visible.
5. Leave during attention/waving, try occlusion and different floors, then return
   after unloading the guide's area. Verify speech still attaches to its body.
6. Try attacks and native player menus; check both server and client logs.
7. Restart with the guide disabled; verify ordinary login, saves and sleep remain
   unaffected and no guide remains.

No server deployment or live multiplayer test was performed during implementation.

## Guide trades and backend credit receipts

All guide speech is in pt-BR, including `Olá!`. With PostgreSQL persistence active,
trading defaults to enabled; set `-Dapocbr.guide.trade.enabled=false` to retain the
guide without trading or requiring its table. The manager's game-runtime migration
`V10__Create_Human_Sale.sql` creates `player_rel.apocbr_human_sale`;
`2026-10-19-human-sales-schema.sql` is its engine reference contract. Apply migrations through
the backend migration role before starting a PostgreSQL server with the startup
guide and trades enabled. The existing `public.apocbr_persistence_receipt` migration
(manager V8) is also required; that receipt table remains in `public`. No DDL runs
on the simulation thread. The connector's startup
probe validates the sales columns, so missing migrations fail before admission.
`apocbr.postgres.serverId` must identify this server, as for the existing connector.
The feature requires the existing journal-backed persistence lifecycle, not the
legacy in-memory mirror alone. Trades are refused while persistence is unavailable.

If startup reports `42P01` after V10 has been applied, older engine builds still
queried `public.apocbr_human_sale`. Use the corrected 42.21.0 patch, whose startup
probe and sale replay both target `player_rel.apocbr_human_sale`. Do not create a
second sales table in `public` or edit an already-applied Flyway migration. Pending
typed `human-sale-v1` journal records retain their format and use the corrected
adapter on replay. A subsequent shutdown `ServerMap.grid` null-pointer exception
is a separate consequence of aborting before world initialization; the migration
contract fix does not change that shutdown hook.

Contract verification: all SQL statements in the reference V10/V11 contracts
match the manager migrations (ignoring comments and the reference transaction
wrapper). `tools/test_postgres_resilience.py` exercises production startup
validation with the sales table available only in `player_rel`, missing-table
rejection, disabled trades, and sale replay/deduplication. That fixture,
`tools/test_human_trading.py`, `tools/test_lobby_event_control.py`, and the 42.21.0
patch script's real game-JAR `-DryRun` compilation pass. Live database startup
still requires validation after deploying the rebuilt patch.

The vanilla client opens its ordinary trading window. The server accepts on the
guide's behalf and explains: it accepts the visitor's money and valuables, **only
Base.Money counts toward credit, and every other offered item is destroyed without
credit**. The visitor adds items, seals the offer, and clicks the normal final
confirmation button. Editing an offer resets consent. The guide seals its empty
side only in response to a valid sealed visitor offer. It does not finalize on
seal alone. Closing the UI cancels without consumption.

`ServerHumanTrading` owns one session per guide and one NPC session per visitor.
The actor enters `WAITING_TRADE_ACCEPTANCE` with the typed `WaitForTrade` action,
stops roaming/emoting, and faces the visitor continuously. Confirmation transitions
to `RECORDING_SALE`, then `RETURNING_HOME` and the usual cooldown/roaming. Distance
is at most three tiles with native line of sight, on the same floor. Disconnect,
death, actor removal, loss of visibility/distance, or a three-minute timeout cancels
the session. The native trade request/add/remove/state packet replacements preserve
their vanilla bodies after an NPC-only hook. `Trading` and `RequestTradingPacket`
resolve an NPC only as a remote trade target. No global PlayerID resolution, account
map, or native TradingManager session is added for the NPC. Ordinary player trades
continue through the vanilla code.

Ownership is validated against the sending connection and current live player
instance. The server takes actual inventory instances, caps offers at 500 distinct
items, and revalidates every item immediately before consumption. As with the
native add-item packet, items must be directly in the player's main inventory.
Place money from bags into that inventory before offering it. An offered bag and
its contents are destroyed together, but money inside it is **not** separately
credited: only explicitly offered `Base.Money` and `Base.MoneyBundle` instances
count. Each note is worth 1 and each bundle is worth 100 in Base.Money units.
No item price,
client-supplied quantity, equipped item semantics, or backend balance is trusted.

Before deploying bundle support, migrate the manager app with V58 and game-runtime
database with V12, then deploy the updated manager credit service and server patch.
`2026-10-19-human-sales-schema.sql` remains the original note-only baseline; apply
`2026-10-20-human-sales-money-bundles.sql` afterwards for standalone setup (the same upgrade
as manager V12). Startup validates the new bundle column before allowing trades.

For offers containing money, a detached `ApocBRHumanSales.Sale` is written and
fsynced to the connector's local journal **before** any offered item is removed.
A journal failure refuses the whole offer and removes the failed staged sale, so
it cannot later record a refused offer. This is a synchronous local disk barrier;
there is no JDBC lease or SQL on the simulation thread. It can briefly stall a tick
and flush earlier staged journal work. Database work uses the existing replay
worker. All offered items are removed through the authoritative inventory; native
inventory-removal packets synchronize the owner, and ServerPlayerDB captures the
post-sale character. An offer with neither currency type still destroys all confirmed
items and saves inventory, but emits **no sales INSERT**.

Each money receipt includes a stable `sale_id`, source server/world, username,
optional Steam ID, split-screen slot, NPC key, sale timestamp, exact money item
IDs, bundle IDs, and quantity. `native_item_ids` contains all offered currency IDs;
`bundle_item_ids` is the subset identifying bundles. Quantity is the equivalent
number of notes: `native_item_ids.size + 99 * bundle_item_ids.size` (up to 50,000
for 500 bundles). Backend pricing then converts that value to account credit.
Legacy journal records default to no bundles and retain their note-only value.
`ON CONFLICT (sale_id) DO NOTHING` and the
existing operation receipt transaction make SQL replay idempotent. Sale receipts
that cannot replay are retained in journal quarantine, including startup/shutdown
retry exhaustion; they are not discarded by the generic five-attempt policy.
They require operational recovery and are not automatically retried from quarantine.

The schema leaves rows `pending`. The backend owns pricing, `credit_amount`,
`credit_currency`, `credited_at`, and `backend_reference`. It should claim pending
rows using `FOR UPDATE SKIP LOCKED`, use `sale_id` as the unique ledger idempotency
key, and update the balance/ledger and receipt status in the same backend database
transaction. Username is an account identifier within this server; Steam ID is
optional and split-screen slots must not silently become separate credit accounts.
No backend worker, wallet update, or price calculation is implemented by this patch.

**Crash-consistency limitation:** the durable sales receipt and the native/relational
player inventory save are separate operations, not one atomic database transaction.
A crash after receipt durability but before inventory removal/save can leave a
receipt for restored items. A crash after consumption can also require inventory
reconciliation. UUIDs prevent replay of a receipt from crediting twice; they do not
solve this inventory/receipt boundary or prevent a restored item being resold under
a new sale UUID. Reconcile receipts against authoritative inventory persistence
before enabling automatic production credits; use the schema's `review` status for
uncertain deliveries. `CONSUMPTION_REQUIRES_REVIEW` logs identify runtime consumption
or capture failures by sale UUID. This patch does not claim exactly-once inventory
consumption across process/power failure.

Validation adds the production trade-adapter fixture and sale tests in the actual
journal/coordinator fixture: mixed/trash-only offers, money-only receipts, consent
invalidation, ownership/range, duplicate acceptance, disconnect/timeout/removal,
failed queue submission, durable sale round trips/restart, fsync failure cleanup,
SQL parameterization, and idempotent transaction replay. Still test native UI,
item destruction, cancel/reconnect behavior and pt-BR chat with an unmodified
42.21.0 client before live use. No database migration or server deployment was run.
