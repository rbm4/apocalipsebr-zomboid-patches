# NPC worker: live-access inventory and snapshot boundary

Status: source investigation and proposed migration contract, 2026-10-26.
An [experimental live-world worker](npc-async-worker.md) now exists separately;
the snapshot publisher and detached data flow proposed here remain unimplemented.
Target: dedicated-server 42.21.0, including the current working-tree speech work.

The goal is to remove decision and planning CPU work from the server simulation
thread without replacing it with full-world copying. A worker can continue
planning during a slow server tick. Authoritative movement, damage and packet
publication still advance when the server thread can execute them. This design
does not guarantee smooth physical motion when server ticks fall below 10 Hz.

## Investigation scope and current flow

Reviewed the native adapters under `src/zombie/apocalipse/humans`, their injected
ports, movement/traversal dependencies, factory lifecycle and the persistence,
event-content, dialogue and speech boundaries they call. The inventory below
groups accesses by owner and operation; it is not a Java concurrency proof or
an exhaustive enumeration of transitive vanilla internals. Every listed native
adapter is excluded from worker execution unless explicitly split in migration.

`GameServer` calls `ServerHumanFactory.update()` once per active server tick.
`tick()` enforces owner-thread access, consumes persistence/profile updates and
then gates the active NPC loop at `BRAIN_UPDATE_NANOS = 100 ms`. That loop runs
physiology, perception, viewer management, dialogue/trade, behavior/action
execution, speech, corpse dragging, presentation and transport. Routine motion
publication has a 500 ms heartbeat; turns and presentation changes publish early.
This describes the synchronous baseline. The experimental worker now supplies an
independent clock and runs the existing tick including planning inside actuators;
it does not implement the detached ownership proposed below.

References: [factory](../src/zombie/apocalipse/humans/ServerHumanFactory.java),
[architecture](../NPC-ENGINE-ARCHITECTURE.md),
[lifecycle](../NPC-PROFILES-LIFECYCLE.md).

## Live-access inventory

Paths in this table are relative to `src/zombie/apocalipse/humans/`. "Capture"
means detached values produced on the simulation thread. "Retain" means keep
the operation on that thread, publishing only its status/result when needed.
Native references reached through `human`, injected callbacks and ports count as
live access just as much as explicit singleton access.

| Owner / entry points | Shared or native accesses | Worker input / retained operation |
| --- | --- | --- |
| `ServerHumanFactory.update/tick`, activation/removal/checkpoint APIs | `IsoWorld.instance.currentCell`, `GameServer` flags, names/IDs/player routing, UDP connections; mutable lifecycle maps, shared traversal world, persistence health and queues | Capture actor roster, activation, profile revision, availability and control epoch. Retain construction, removal, respawn authority, state mutations, persistence integration and checkpoints. |
| `ServerHuman` constructor, `isRelevant`, `stopActivities`, `dispose` | `IsoPlayer`/descriptor/cell lists, native stats, connections, viewer/presentation maps, emitters, controllers and adapters | Capture self identity/position and availability. Retain all native lifecycle and connection ownership. Do not hand `ServerHuman` to worker. |
| `ServerHumanPerception.observe/update/scan/see` | `GameServer.Players`, ID map, connection ownership/disconnect state; cell squares, moving-object lists, `ServerMovingObjectSet` membership; live player/zombie identity, life, position, invisibility and grapple-only state; `LosUtil.lineClear` | Capture candidates/visibility/coverage and stable identity generations. Existing `HumanSenses.Frame` and `HumanObservation` are detached value boundaries. Retain native discovery/rays initially; memory reduction/sorting can later consume raw captured facts on worker. |
| `ServerHumanPerception.resolveZombie/eligible/visible` | Sensor-local native binding map, native membership and ID reuse, current target life, loaded squares and LOS | Retain final target resolution and attack validation. Worker receives validity/visibility facts, never `IsoZombie` or membership tokens. |
| `ServerHumanTerritory.contains/observe` | Walking scope calls ultimately reach native grid and actor position | Capture territory membership of contacts and self for first migration. Later evaluate profile scope against detached navigation. Do not assume this is only a coordinate rectangle. |
| `ServerHumanActuator.face/tactical` | Player ID map, target resolution, LOS, actor/target position, direction and aim changes | Worker issues face/posture intentions. Retain current identity/range/LOS checks and native application. |
| `ServerHumanActuator.startMovement/updateMovement/stopMoving/returnHome` | Live walking/movement engine; actor coordinates, forward direction, stored post position; synchronous return values | Split requests from acknowledged execution state. Retain physical integration and authoritative stop/return behavior. |
| `ServerHumanActuator` emote/speech/corpse/combat/survival/task ports | Delegates into native adapters and transport; direct capability access exposes synchronous world calls | Replace worker-facing capabilities with detached queries and immutable commands; do not wrap live actuator in a worker proxy that delegates synchronously. |
| `ServerHumanWalking.context/start/scope/permitted/sameBuilding` | Actor position/ID; mutable per-pattern cursors; planners call live `MovementGrid`; `getBuildingDef()` identity comparison | Capture self/home/floor/seed, immutable profile pattern specs, navigation page references and building tokens. Move pattern/planner state to worker only with completion/cancellation feedback. Retain physical route acceptance. |
| `ServerHumanMovement.available/walkable/connected/traversalCost/square` | Actor cell and floor; `HumanTraversal` geometry/edge inspection | Capture loaded/walkable flags and directed traversal edges in shared pages. Current `MovementGrid` implementation is never worker-safe. |
| `ServerHumanMovement.bodyClear/occupancyAllows/freeOfBlockingBodies` and `traversal/BodyOccupancy` | Square static flags and occupant lists; occupant type/identity/position/life; self exclusion and reciprocal grapple exceptions; bounded geometric tests | Capture nearby blocker values with identities, categories, positions and required relationship flags, not only zombie contacts or tile occupancy bits. Retain exact immediate movement checks. |
| `ServerHumanMovement.start/update/refresh/predictionDistanceUnits` | Mutable route progress; collision queries via `PolygonalMap2`; actor coordinates, animation variables/network AI; traversal execution and transport callbacks | Capture route ID, progress, status/failure, actual pose, resolved speed/gait and traversal status. Retain physical execution, native prediction validation and replication. |
| `traversal/HumanTraversal.walkable/inspect/walkingEdge/doorOpenable` | Squares, solid floor/stairs/water flags; doors/windows/frames/thumpables/fences and their locks/barricades/glass/sprites; adjacent collision and polygonal map; per-actor refusal cache | Capture detached directed edge kind/cost and per-actor restrictions. `doorOpenable` uses `at(...)`, temporarily changing `player.currentSquare`; this query cannot run on worker. |
| `traversal/HumanTraversal.prepare/landing`, `TraversalExecution`, `TraversalWorld`, `CrossingReservations` | Native obstacle mutations, landing geometry/body checks, actor positions, shared crossing leases, traversal commitment and refusal deadlines | Retain native interaction and reservation authority. Worker receives availability hints and execution results. `TraversalEdge.obstacle` is `Object` and may hold a native object: never publish existing native edges. |
| `ServerHumanSurvival.escape/openingTarget/routeProblem/failed` | Native self pose, movement status/speed/failure, live walkability/occupants/body-clear/edge costs; mutable refusal/progress state | Capture pose, movement feedback, threat contacts and detached navigation/blockers. Move retreat search/scoring and refusal policy to worker; retain route start and movement checks. |
| `ServerHumanCombat.begin/validate/Session.update` | Logical health, perception sample/target binding, territory, native target; `ServerZombieController.engage`, backend execution | Capture selected-target facts and execution status. Retain target/age/territory validation, native zombie authority and weapon execution initially. |
| `ServerHumanCombatApproach` | Live target/self coordinates, current awareness, native walkability/occupants/body clearance, attack-line queries, physical movement | Capture target pose, contacts and navigation plus bounded candidate attack-lane results. Move placement/withdrawal search and tactics once their inputs are detached. Retain movement and final lane checks. |
| `ServerHumanAttackLine.clear` | Loaded-square checks and `LosUtil` for ranged; `PolygonalMap2` for melee; actor/target floor/position | Capture current attack eligibility or bounded requested candidate-lane answers. A cardinal path graph does not reproduce native arbitrary attack rays. Retain final strike check. |
| `ServerHumanWeaponBackend`, `brain/HumanWeaponCombat` through its injected operations | Inventory, target death/distance, approach, face/aim/stop, commit; closures retain native actor/target | Keep native weapon phase execution first; publish phase/result and weapon descriptors. A pure-looking phase class is unsafe with these live injected operations. Later split scheduling only with explicit commit acknowledgements. |
| `ServerHumanInventory.weapons/describe/item/reserve` | Native item/container identity, condition/ammo/chamber/jam/range; `InventoryItemFactory` | Capture existing `HumanCombatInventory.Weapon` values, equipped ID and inventory revision. `weapons()` calls `initialize()` and CREATES items on first access; initialize deliberately on simulation thread. |
| `ServerHumanInventory.equip/spendRound/reload/releaseForCorpse` | Native hand slots, inventory contents, ammunition and equipment replication | Retain item validation/mutation. Worker requests operations and consumes results; never predicts success by changing authoritative counts. |
| `ServerHumanCombatNetwork`, `ServerHumanCombatAudio` | Native actors/targets/weapons/body state; `Rand`, `CombatManager`, hit fields, damage/endurance, sound parameters and packets; speech hooks | Retain hit rolls, authoritative damage, ammo/endurance and packet/audio creation. Publish detached hit/miss/damage/kill events, not packet fields or engine objects. |
| `ServerHumanRetaliation.observe/resolve/begin` | Player ID map, native protection/life/safety/faction/faction-PvP; `ServerOptions`, `NonPvpZone`, LOS, territory; weapon operations closures | Capture current aggressor identity and eligibility/visibility facts, including melee/ranged permission separately. Retain fresh PvP authorization at strike. |
| `ServerHumanPlayerAttacks`, `ServerHumanPlayerDamage`, `ServerHumanZombieVictims` | Packet fields, connections, current ID routing, attacker/victim identity, native damage/armor/body and state projection, victim registrations | Retain authenticated intake, immediate damage and native authority. Emit ordered detached aggression/damage facts and invalidate old control immediately. These paths must not mutate worker-owned modules directly. |
| `ServerHumanPhysiology.update/project`, `state/HumanStateStore` | Native health/body/stats/gait; logical state reducer and owner-thread dispatch | Reuse immutable `HumanState` reference in published frame, including revision. Retain native loss import, state single writer and stat projection. Pure calculators/reducer may be evaluated on copies, but worker cannot dispatch into current store. |
| `ServerHumanTasks.position/locate/permitted/accepts/apply` | ID map, followed player's identity/life/connection/position, walking scope and current task module | Capture explicit follow target even outside perception, self position, scope answers and accepted task/version. `HumanTaskWorld.approach` hides repeated `permitted()` calls: use detached scope/grid. Retain issuer authorization; deliver accepted assignments as events to worker. |
| `ServerHumanCorpses.find/resolve/approaches/permitted` | Static-moving-object lists; `ObjectIDManager`; corpse identity/position/angle/type/fake-dead state; native claims/deferred maps; movement scope | Capture bounded search results, completeness/loading/retry time, adapter-local body token and approach candidates. Retain discovery/claims first; do not scan all corpses or snapshot native maps. Qualify tokens by actor incarnation and request ID. |
| `ServerHumanCorpses.claim/beginDrag/updateDrag/endDrag/move/release`, `ServerHumanCorpseDrag` | Shared corpse claims, native bodies/zombie representation, `ServerMap.zombieMap`, `NetworkZombieManager`, `ServerCorpseDragAuthority`, square membership, packets | Retain claim arbitration and full drag lifecycle. Publish claim/drag/drop result and body handle changes; worker controls task policy, not physical corpse progression. |
| `ServerHumanEncounters.players/armed/key/authorized` | Live visible players, heading/aim/hands/Steam ID/username, native ID map and visibility; persistence health, relationship memory | Capture account key/display name, armed/aimed-at-self facts, relationship state and availability. Share base player facts once per publication; keep actor-relative range/aim/LOS separate. |
| `ServerHumanEncounters.claim/release/record`, `encounters/HumanRelationships`, `ServerHumanDeaths` reputation | Shared affiliation/account claims, semantic memory and pending persistence events; killer identity/native corpse/loot creation | Retain one relationship/claim authority; publish relevant states/revisions and acknowledge outcomes. Do not copy the full up-to-100000-entry relationship map per frame. Keep death/loot/respawn commits on simulation thread. |
| `ServerHumanSpeech.context/attacked/damage/attack/zombieKilled/update` | Player account/display identity, weapon category, health/control mode, ID map; event time, speech memory/catalog, native output callbacks | Capture `SpeechContext` and ordered event facts at source, including both clocks. Worker may select lines/cooldowns; delivery/cancellation acknowledgement controls `engine.delivered`. Preserve the attacked return value's existing callers when splitting. |
| `speech/HumanSpeechMemory`, `HumanSpeechEngine`, static standalone speech memory | Synchronized shared semantic state/pending records; per-engine cooldown/decision state and random supplier | Existing memory is synchronized, but that does not transfer ownership of engine/output state. Choose one semantic mutation authority; use relevant states or commands with acknowledgements. Do not snapshot the full up-to-100000-entry memory. |
| `speech/HumanSpeechCatalog`, `dialogue/HumanDialogueCatalog`, `HumanLanguage` | Volatile catalog publication; immutable speech pools; dialogue labels/profile maps, locale and logging | Share validated immutable catalog revisions by reference, pin one revision per decision batch. Audit nested dialogue maps for deep immutability before worker use. Locale is startup configuration. |
| `OperationAnnouncementProvider.lines` -> `ApocBREventControl.guideAnnouncementLines` | Cached controller state, freshness, applied receipt, wall clock, language catalog | Capture resolved immutable lines with freshness/deadline, or publish a detached schedule DTO. A cache-only API is not automatically a cross-thread API; do not give worker the event controller. |
| `ServerHumanDialogue` | `LuaManager` environment/tables, sessions, native players/ownership/distance, catalogs, persisted flags; direct activity/task/emergency calls | Retain Lua and session authority. Publish exclusive-control epoch/mode/visitor and accepted tasks. Worker must not share `HumanActivityController` or `HumanModuleRuntime` with callbacks. |
| `ServerHumanTrading` | Native connection/player/inventory ownership, sessions/offers; `ServerPlayerDB`, sales/provenance, `Core.gameSaveWorld`, Steam mode; direct activity/emergency calls | Retain validation, item consumption, sale receipt and session authority. Publish control mode/availability/results, not offers' native items or connections. |
| `ServerHumanTransport`, all `ServerHuman*Packet`, `traversal/TraversalStatePacket` | UDP connection relevance/player lists, packet fields, native player/weapon/animation/grapple state; `ChatServer` range and delivery | Retain transport and viewer/equipment caches. Worker only needs output capability/delivery feedback, not a copied UDP engine. Native prediction and packet construction stay together. |
| `ServerHumanProfiles`, profile/module/walking registries, `HumanLog`, persistence APIs | Mutable registration/configuration, injected adapter factories; store/current/health, logging configuration and diagnostics | Freeze and publish selected immutable profile/config revisions at activation. Do not expose factories/registries or logging suppliers closing over live humans. Existing persistence worker remains separate from decision worker. |

## What can already be reused

`HumanObservation`, `HumanSenses.Frame/Contact/Identity/Work`, `HumanTerritory`,
`HumanState`, weapon descriptors, `SpeechContext`, immutable action definitions,
profile data and pure movement-condition calculations are useful DTOs or inputs.
They still need a coherent publication envelope. `HumanObservation` currently
lacks the actor's full pose, control epoch, task/follow facts, weapon state,
navigation pages and execution results.

Mutable `HumanContactMemory`, behavior/modules, pattern cursors, action
executions, speech engines and tactics can have a worker owner after their ports
are detached. Their current injected actuators/providers must be replaced first.
Do not share those mutable objects across server callbacks and the worker.
`HumanActivityController` currently serves synchronous dialogue/trade entry
points; retain a simulation-side control gate and communicate its transitions.

## Proposed publication layout

Publish one immutable `NpcWorldFrame` through a dedicated simulation-to-worker
`AtomicReference`, owned by a new NPC runtime bridge composed by the factory.
The names here are proposed contracts, not existing Java classes.

| Frame component | Contents | Capture policy |
| --- | --- | --- |
| Envelope | world/session epoch, publication sequence, capture monotonic time, wall time, catalog/config revision references | Once per completed bounded capture; worker reads one reference and pins it for a decision batch. Include per-component sample time when data was captured earlier. |
| Actor roster | logical key, activation/incarnation, profile revision, state revision, control epoch, active/dead/blocked/mode facts | Small list; authoritative roster absence invalidates old actors. Restart changes world epoch. |
| Actor observations | self pose/home/floor, health/state reference, senses/awareness/territory, relevant player facts, weapons, follow/task state, execution status references | At most once per actor due for sampling; reuse unchanged immutable components. Mark age/partial/unavailable explicitly. |
| Shared nearby facts | Detached entity/account facts needed by the union of active actors' sensing and explicit task targets | Deduplicate by stable lifetime handle; an explicit follow target is not required to be visible. Do not copy every entity in every loaded cell. |
| Navigation pages | Bounded spatial pages on needed floors; loaded/walkable/building flags, directed edge kinds/costs; separate dynamic blocker samples and actor restrictions | Incremental, shared across overlapping NPC regions; revisions and sample times per page. Missing page/edge is unknown, never traversable by default. |
| Event/results watermark | Highest input sequence represented; references to bounded acknowledged event/result delivery | State replacement cannot discard unprocessed damage, assignment or completion events. |

Safe publication requires the entire reachable graph to be immutable. No
`IsoPlayer`, `IsoZombie`, square, chunk, native item, connection, `KahluaTable`,
native `TraversalEdge.obstacle`, mutable view or callback retaining those objects
may cross the boundary. Primitive arrays need encapsulation and no reuse while
any published frame/worker retains them. Publishing a record does not itself
make nested objects immutable.

The worker loads `latestFrame.get()` and processes only a newer publication or
due work with explicit timers. Signal it without blocking the simulation thread;
do not busy-spin on the same frame. It can continue bounded searches against a
pinned frame between server ticks. It must not interpret that as new world
observations, advance physical movement, or replay missed attacks. Old captures
have absolute age limits; known death/control invalidation takes precedence.

## Navigation capture without replacing search with copying

1. Start with existing native perception/rays and physical execution retained.
   Publish existing observations once; remove redundant adapter resampling from
   worker-facing ports. This provides a migration boundary, not a claim that all
   expensive work has moved.
2. Build pages for the union of requested bounded search regions, with a server
   budget across ALL NPCs. Share a page between nearby NPCs. Retain pages by
   revision, evict by bounded working-set policy, and let searches request missing
   pages for later publications without synchronous server callbacks.
3. Capture static tile/edge/building data separately from dynamic occupants.
   Represent same-building equality with session-local tokens, not `BuildingDef`
   references. Include non-zombie blockers and NPCs absent from normal cell lists.
4. Split actor-independent geometry from door eligibility, refused edges,
   landing/crossing availability and profile policy. `HumanTraversal.inspect`
   mixes these today; its result is not an actor-independent permanent cache.
5. Initially use bounded rolling refresh with sample ages and conservative
   unknowns. Incremental invalidation requires verified hooks for unload/reload,
   construction/destruction, door/window/lock/barricade changes and mod mutations.
   No complete invalidation coverage has been established by this investigation.
6. Native LOS/attack/collision queries remain authoritative. A detached graph can
   support approximate route proposals; exact candidate attack lanes require a
   faithful detached geometry model or bounded asynchronous query requests.
   Do not precompute all pairs of tiles/targets to preserve a synchronous API.

## Events, commands and feedback

Use a separate bounded simulation-to-worker event/result channel. Assign sequence
numbers; retain entries until acknowledged. Relevant events include accepted
task changes, attacks/damage/kills, interaction preemption, claim results and
execution completion/failure. Publish event occurrence time and source identity
at intake; do not reconstruct intermediate damage from the latest health value.
Speech attack bookkeeping and relationship outcomes can have persistence effects.

Publish replaceable worker intentions through a separate latest-output mailbox;
use bounded identified commands with acknowledgements for one-shot operations.
Commands carry world epoch, actor key/incarnation, control epoch, execution/command
ID, relevant target lifetime handle, input sequence/sample age and dependencies.
Use revisions relevant to an operation: requiring an exact health revision on
every route proposal would reject useful work whenever physiology advances.

The simulation thread drains commands under a work budget, validates live
preconditions and publishes explicit accepted/rejected/running/terminal results.
An enqueue success is not movement/equip/claim/hit/speech success. Existing
boolean-returning ports and `HumanCombat.Execution` references need asynchronous
contracts; do not block on futures or return optimistic success from a proxy.
Keep terminal acknowledgements until consumed; replaceable progress can coalesce.

Death, despawn, exclusive dialogue/trade, persistence failure and administrative
stop invalidate control on the simulation thread immediately, before processing
old worker output. Cancel/stop authority must work even when the worker stalls.
The worker subsequently consumes the new epoch and reconciles its private state.
Keep shared corpse/crossing/relationship claims under one authority, with handles
rather than cross-thread object identity as ownership tokens.

Queue exhaustion must have an explicit policy: reject new optional work, retain
already accepted critical events, or block further autonomous command execution
for that actor and require state reconciliation. Never silently drop a committed
one-shot result or turn exhaustion into an unbounded backlog. No server waits,
worker joins in the tick, or unbounded catch-up loops.

## Capture budgets and measurements required before migration claims

Current perception constants are per-actor per-sample limits: 128 player checks,
128 tiles, 256 bodies, 48 zombie candidates, and up to 32 player plus 16 zombie
ray checks. At 64 active actors, coincident samples permit 8192 player checks,
8192 tile visits, 16384 scanned bodies and 3072 sight checks, before other work.
These are nominal loop allowances, not measured cost or complete upper bounds:
retained-binding validation/refresh, deduplication, ray lengths and downstream
territory calls add work. Corpse searches visit up to 128 objects per invocation
inside radius at most two; they also process retained/deferred state.

Stagger due actor samples and add global capture/page/query budgets. Measure
actual nested work, not only published record counts. Do not blindly deep-copy
the entire map, catalogs, profiles, memory stores, inventories or viewer lists.
Reuse immutable references; capture actor-relative facts only where required.

Record capture CPU and elapsed time by family, candidates/rays/tiles/edges/body
visits, pages rebuilt/reused/evicted, bytes allocated/retained and partial samples.
Record worker CPU, elapsed decision/search time, queue depth, observation age,
input skips, event/result acknowledgement lag, command age/rejections and actual
server execution/replication time. Compare equal NPC workloads and slow-tick
scenarios; worker throughput alone does not establish reduced server cost.

## Migration order and acceptance conditions

1. Add phase measurements and immutable bridge DTOs/mailboxes with no behavior
   ownership change. Capture follows native event intake/execution consistently;
   drain prior worker output before capturing resulting state.
2. Move pure selection/behavior policy behind detached ports. Keep weapon,
   movement/traversal, corpse, state, session and transport execution on server.
   Marshal every direct module mutation from task/attack/session callbacks.
3. Move bounded navigation and tactics using shared pages and query feedback.
   Remove expensive planning from actuator start calls, not just the factory.
4. Consider speech decision ownership separately; preserve event ordering,
   repetition memory, delivery acknowledgement and persistence semantics.

Required production-code fixtures for implementation: immutable publication and
no live references, coherent lifetime/control fencing, replacement without lost
events, exactly-once one-shot effects, bounded queues/capture/search, unload and
page staleness, target reuse, worker stalls/failure/shutdown, dialogue/trade/task
preemption and eventual cancellation. Compare route/attack behavior against the
existing native adapters where approximation changes semantics. Compile against
the exact game JAR and validate multiplayer timing, geometry and slow server
ticks at runtime. This documentation-only investigation runs no Java fixtures
and makes no measured performance claim.
