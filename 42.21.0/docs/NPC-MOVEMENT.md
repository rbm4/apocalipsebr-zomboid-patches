# NPC movement engine, navigation and walking patterns

Implemented for server Build 42.21.0. Movement mechanics and condition resolution
are separate from roaming; live player animation and dedicated-server playback still require
validation. It introduces no database migration or deployment.

## Ownership

| Component | Responsibility |
| --- | --- |
| `WalkingModule` / objective modules | Decide when to request a configured pattern or destination; cancel when preempted. |
| `WalkingPatternRegistry` / `WalkingPattern` | Allowlisted pattern factories, destination progression and navigation scope. Each actor receives fresh pattern instances. |
| `ServerHumanWalking` | Resolve immutable requests, plan a route, own pattern completion/cancellation and provide live-world navigation facts. |
| `DestinationRoutePlanner` | Bounded same-floor A* to a requested destination; no destination policy or native actuation. |
| `ServerHumanMovement` | Per-NPC physical movement engine: follow a supplied route, integrate position/facing, enforce traversal/collision/occupancy, apply resolved gait/speed/animation, and clip prediction. |
| `MovementModifiers` | Immutable intent, settings and condition contracts; pure resolution of pace, physical speed, animation modifiers and decision reason. |
| `MovementGrid` | Shared tile and traversal-policy contract used by both planners and the movement engine. |
| `HumanActionExecution` / `ServerHumanActuator` | Start the requested pattern, advance its route through generic `updateMovement`, and stop on completion/failure/cancellation. |
| `ServerHumanTransport` | Encode the engine's resolved movement snapshot in the existing vanilla player packet. |
| `ServerHumanFactory` | Schedule simulation/heartbeat and publish immediate direction or moving-state changes. |

Patterns do not own the physical waypoint cursor, position integration, speed,
prediction, occupancy or native collision calls. Transport no longer reads
roaming internals. There is one moving-state owner, the movement engine.

## Calling the capability

The engine is a package-private native service assembled for each `ServerHuman`,
alongside roaming and the actuator. World access stays on the factory's simulation
thread. Pattern selection stays above the capability; profiles do not receive
live engine objects and workers do not access them.

`movement.start(route, now)` executes a caller-selected walking route using physical
traversal alone. `movement.start(route, policy, now)` additionally restricts that
route through a `MovementGrid`. The policy is a transient simulation-thread
reference held only until completion, stop or failure; it is never persisted.
The policy cannot override physical safety checks. The selected walking pattern
supplies its scope to preserve its building/area rules.
A different future pattern may supply another constraint or just physical traversal.

Routes are immutable copies with at most 256 waypoints. They contain cardinally
adjacent tile centers on the actor's configured floor, optionally starting with
its current tile. Empty, oversized, diagonal and skipped-tile routes are refused.
An invalid replacement stops the prior route. Physical checks are repeated during
execution because a valid planned edge can become blocked or unloaded.

## Moving-object occupancy

Vanilla `IsoGridSquare.isFree(true)` rejects any nonempty moving-object list,
including the NPC itself and dead/zero-health characters awaiting native removal.
The movement adapter relaxes that result only when all observed occupants are
self, actual `IsoDeadBody` objects, `IsoZombieGiblets` debris or dead/zero-health game characters. Static
geometry remains mandatory; live characters, unknown objects, unexplained
occupancy and lists over 64 objects remain blocked. The same interpretation
controls normal route steps, origin centering and prediction. Tactical body
clearance also ignores corpses/dead characters while retaining its work budget.

`body_occupied` is a locomotion failure, not corpse discovery. DEBUG failures now
include the actual attempted `occupancyTile` (which may still be the origin),
total occupant count and up to four object classes/IDs/positions, blocker flags
and character health/death flags. The next waypoint remains in `to` for route
diagnosis. This avoids describing occupants of a different tile when a step is
blocked before it crosses the origin boundary.

Regression fixtures model vanilla moving-list occupancy and execute production
movement across stale dead occupants while confirming live/static/unknown
blockers still stop it. The corpse fixture covers self, zero-health actors,
corpse objects, tactical clearance and origin-square diagnostics.
The regression run passed: corpse/occupancy (48), roaming (571), survival (101),
walking (32), tasks (29), dialogue (66 plus language checks), server humans (92),
weapon combat (116), tactics (19) and traversal (93). The target 42.21.0 game-JAR
dry compilation passed (261 sources, 803 classes). These are isolated checks;
the reported live blocker identities and corpse search results still need
verification with the updated server patch. No deployment or database change
was performed for this fix.

This is a bounded same-floor route executor. It opens passable doors and delegates
windows and low fences to the separate [traversal subsystem](NPC-TRAVERSAL.md).
It does not select destinations, climb stairs or cross floors. Path
planning stays separate; `IndoorRoutePlanner` retains its 256-node/12-edge
roaming search limits. The engine does not depend on that planner or its limits.
No direct call may bypass existing activity ownership: new autonomous patterns
must still run through the behavior/action/controller contracts.

## Generic requests and profile selection

`HumanAction.Move(MovementRequest)` replaces the proof-of-concept `Roam` action.
`HumanActuator.startMovement(request, now)` enters the navigation adapter;
`updateMovement` advances it, and `stopMoving` cancels it. Combat retains its
own destination policy and supplies routes to the same physical movement engine.
There is no generic `startRoaming` capability or native `ServerHumanRoaming` service.

An objective can request `MovementRequest.To(Point)` or
`MovementRequest.Pattern(slot)`. Profiles bind up to eight semantic slots to
immutable `WalkingPatternSpec` values. The `walk` module schedules `background`
using its `pauseMs` parameter; pattern factories are registered in code, never
loaded as classes/scripts from database data. Navigation to an objective respects
the profile background scope. Requests are bounded to the actor floor, 256
discovered nodes and 64 steps for objective navigation. Failure returns to the
owning objective/module; no implicit teleport or unlimited repath loop is added.

Initial patterns:

- `indoor-wander`: choose reachable destinations in the home building. Optional
  inclusive rectangle restricts that building further. This preserves the lobby.
- `area-wander`: choose reachable destinations within `radiusTiles` of home and
  an optional inclusive rectangle, without requiring a building.
- `waypoint-circuit`: navigate to ordered same-floor points. Advance the cursor
  only after successful arrival; failure/cancellation retries the same point.

The radius is used by area wandering; indoor wandering uses building membership.
Circuit points must lie inside any assigned rectangle. A new pattern implements
`WalkingPattern` and registers its validator/factory without changing physical
movement, traversal, transport or generic action execution. A merchant objective
can issue destination requests or select another profile slot through this same port.

`HumanObservation.movementAvailable` reports physical loaded-space availability.
It no longer consults indoor wandering or requires a viewer. Patterns separately
validate their own domains. Background movement can advance in loaded space even
without current viewers, but never pins chunks. Perception and territory use
the configured scope; building confinement is not a global combat assumption.

## Revisioned configuration and Leon

Manager migration V23 creates the pattern catalog, revisioned profile pattern
bindings and ordered points. It publishes new heads for legacy `roam` profiles,
mapping that module to `walk`, `roamPauseMs` to `pauseMs`, and explicitly selecting
`indoor-wander`. Existing published history, current settings, module priorities,
disabled state and pinned trade/speech bindings are preserved, including when
unpublished drafts have higher revision numbers.

V24 seeds server-scoped startup defaults for Leon: server `apoc-extraction`, key
`leon`, username `NPC_Leon`, spawn `(268,14412,0)`, profile `leon-militia`.
His background pattern is indoor wandering intersected with inclusive bounds
`x=239..314`, `y=14403..14426`; combat pursuit uses the same domain. Dialogue and
trading are disabled. Initial appearance defaults are male, `CostumeBeastMom` outfit,
facing south. No new weapon loadout is introduced by this refactor.

The worker materializes `server_spawn` into its actual world on startup, before
reading instances. It selects the exact server key and inserts absent instances
only: existing coordinates, desired activity, retirement, death and runtime
history remain authoritative. No world name is guessed and no live spawn is
performed by the migration. Install V23/V24 before starting this engine build.

## Walking ownership verification

`tools/test_human_walking.py` exercises production pattern selection, bounded
objective A*, outdoor scope, building confinement, rectangle enforcement,
independent circuit cursors, cancellation and generic action integration.
`tools/test_npc_persistence.py` runs the production JDBC reader/worker and actual
new migrations in a disposable PostgreSQL cluster using separate manager and
engine roles. It checks server/world targeting and nonoverwriting seed behavior.
Its `--manager-test` option also runs the new Flyway migration and aggregate
publication integration fixtures. Existing movement, brain/action, lifecycle,
perception, combat, dialogue/trade and module-runtime fixtures remain required.
Real-JAR `-DryRun` verifies signatures; live path/animation behavior remains an
in-game check. No deployment or production database write is part of these tests.

This refactor passed the following checks:

| Command / fixture under `tools/` | Result |
| --- | --- |
| `test_human_walking.py` | 32 pattern/objective ownership assertions. |
| `test_npc_persistence.py --manager-test` | 61 production JDBC/worker assertions and 35 manager unit, controller, template, Flyway and PostgreSQL integration tests. |
| `test_human_roaming.py`, `test_human_traversal.py` | 571 and 93 assertions. |
| `test_human_brain.py`, `test_human_sequences.py`, `test_npc_runtime.py` | 72, 31 and 33 assertions. |
| `test_human_profiles.py`, `test_server_humans.py` | 56 and 80 assertions. |
| `test_human_perception.py`, `test_human_engagement.py`, `test_human_weapon_combat.py` | 145, 46 and 67 assertions. |
| `test_human_dialogue.py`, `test_human_trading.py` | 52 plus EN/PTBR catalogue checks, and 538 assertions. |
| `test_human_movement_modifiers.py`, `test_human_state.py` | 111 modifier and 116 state assertions, plus checkpoint logging variants. |
| Manager `node --test src/test/js/npc-editor.test.cjs` | Three editor tests passed. |
| `42.21.0/patchApocalipseBr.ps1 -DryRun` | 234 source files compiled against the real game JAR; no deployment. |

For rollout, stop the old NPC runtime before publishing V23/V24, then start the
updated engine and manager. The previous engine does not recognize the `walk`
module. Validate Leon's actual home-building footprint, bounds, combat interruption
and obstacle presentation in game after rollout.

## Motion and presentation

Unmodified walking remains 2 tiles/second. Each update integrates at most
150 ms, consumes leftover distance across tile centers and updates real position
and facing together. A backwards clock contributes no new movement budget.
The current native walking animation is selected by the movement flag, using the
engine's resolved gait snapshot. The snapshot also supplies prediction speed
and distance, so packet construction has no separate roaming-speed constant.

Routine motion still runs at 100 ms and publishes at 500 ms. Starts, stops and
turns, gait/speed changes and animation-modifier changes publish immediately.
Prediction still covers up to one second on straight
connected edges, uses eighth-tile units, and stops before turns/obstructions with
the existing endpoint headroom. Distance encoding is bounded to a positive byte.
Clients receive the existing vanilla packet contract without a Java client patch.

The engine supports WALK, RUN and SPRINT presentation. Native flags drive run,
sprint and sneak; `WalkInjury` drives signed limp presentation. The native
PlayerInjuries packet supplies remote walk/run animation caches. It is sent on
viewer announcement and when animation values change, not every movement tick.
Per-connection caches are removed on disconnect/disposal and reset on reannouncement.
Independent facing/strafe/aim state remains a later capability. Neither profiles
nor roaming should set native movement variables or mutate coordinates directly.

## Movement modifier contract

Production health/endurance/injury inputs now come from `HumanStateStore` through
`HumanConditionCalculator`. Raw environment/effect inputs remain separate so
projected cold factors cannot compound on subsequent ticks. See
[NPC-STATE.md](NPC-STATE.md) for mutation ownership and V20 persistence.


`MovementModifiers` is a pure, engine-independent contract. Immutable `Conditions`
group `Health`, `Context` and `Effects`; immutable `Settings` control permissions,
burst style, physical speeds, run rhythm and endurance thresholds. Each NPC engine
owns its own requested pace, current conditions and rhythm origin. No executable
callbacks or live game objects enter these values.

The simulation-thread environment/effect producer supplies raw inputs through `movement.conditions(...)`
and tuning through `movement.settings(...)`. Start a route with
`movement.start(route, traversalPolicy, Pace.RUN, now)` or change the current
request with `requestPace`. Existing roaming calls still request WALK. Conditions
are resolved at each movement update and refreshed by the factory while idle or
interacting, so window expiry and injury presentation do not require active roaming.

| Input | Movement consequence |
| --- | --- |
| Immobile, downed, bleeding out, zero health | Effective speed zero; preserve the owned route but clear moving flags/prediction. Recovery can resume it without catch-up movement. This does not implement death or medical simulation. |
| Signed leg injury | Force walking; limp-side variable retained, walking speed/tempo multiplied by 0.66. Takes precedence over sneak presentation and all fast overrides. |
| Allow-run / allow-sprint | Physical permission gates. Flight cannot bypass them. |
| Endurance fraction | Gates jogging, sprint bursts and flight. Default thresholds normalize inspected A-life values 18/140 and 34/140. No endurance drain/recovery is generated here. |
| Flight window and preferred fast pace | Explicit finite override, still subject to physical/permission/endurance limits. |
| Slow sweeping | Ordinary RUN requests become WALK; walking tempo factor 0.72. Flight precedes this gate. An explicit SPRINT request may still receive a permitted burst. |
| Post-hit escape window | Requests running when not guarding and endurance permits. Wounds, immobility and slow sweep remain stronger constraints. |
| Admitted sprint window | Upgrade fast movement to SPRINT for sprinters, RUN for joggers; sneaking suppresses sprint. Admission requires target eligibility, distance eligibility and tactical pacing permission. |
| Danger posture | Allows sustained jogging above the jog threshold; does not invent a fast request from ordinary WALK. |
| Optional run rhythm | Continuous by default. `Rhythm.INTERMITTENT` supports the inspected 850 ms / 14 s run window without replaying missed cycles. |
| Low health | Below 65%, apply relative walk/run/sprint tempo ratios from the inspected A-life gait tuning. |
| Sneaking | Native sneak flag and 0.85 walk factor unless a leg wound owns presentation. |
| Terrain, cold, equipment and stride factors | Explicit finite named factors composed once with physical speed and animation. Vegetation producers can supply 0.75; neutral inputs use 1. No UID jitter or environmental sampling is silently introduced. |

Inputs validate finite fractions, signed injury, factor ranges, ordered positive
speeds, rhythms and endurance thresholds. Composed physical speed is capped at
8 tiles/second and animation factors receive the same cap. Timed effects use an
explicit enabled flag plus monotonic deadline, with expiry at the exact deadline;
zero is not treated as an epoch. They are runtime-only, not persistent timestamps.

Resolution exposes a reason for diagnostics. Precedence is deliberate:
immobility -> leg wound -> run permission -> flight -> slow sweep -> hit escape
-> admitted burst -> danger/endurance/run rhythm. GUARD is slow intent that
suppresses hit escape; it does not implement weapon stance. A SPRINT request with
no admitted burst falls back to running subject to endurance and run rhythm.

Defaults preserve guide walking. Initial configurable RUN/SPRINT speeds are
4/6 tiles per second; these are starting tuning values, not measured vanilla
equivalents. The new gaits and limp clips need two-client visual calibration.
Settings remain Java configuration; no values were put into V15 millisecond
parameters, and no migration or profile publication was made.

### Evidence and adaptation boundaries

The installed A-life source was inspected at Workshop 3803984183,
`ProjectALifeNPCs/42.20/media/lua/`:

- `server/ProjectALife/Movement/ALifeMovement.lua:142`: pace precedence,
  flight/hit/sprint windows, endurance/posture and periodic running.
- `shared/ProjectALife/Shells/ALifeGait.lua:78`, `:145`: sprinter/jogger,
  stealth/limp downgrade and target/distance/resistance admission.
- `shared/ProjectALife/Shells/ALifeAIFence.lua:908`: low health, stride,
  sweep, cold, limp and sneak tempo factors.
- `shared/ProjectALife/Records/ALifeBehaviorSettings.lua:4`: 140 endurance
  maximum, thresholds, vegetation/limp tuning and run rhythm.
- `server/ProjectALife/Movement/ALifeEndurance.lua`: fitness, damage, cold,
  drain/recovery and retreat state are condition producers, not locomotion math.

This is our native-player implementation inspired by those concepts, not a copy
of the zombie-shell controller. Central state now supplies and persists physical/needs
inputs, with bleeding/endurance steps and native health-loss ingestion. Automatic
threat/environment/equipment producers and detailed native wound codecs remain
future work; this movement contract itself does not own persistence. The source's armed-limp clip toggle, custom aiming
poses, target selection, sprint lease granting, cold simulation and reinforcement
or combat behavior remain outside this movement change. A-life's flight endurance
check uses a different scale from its ordinary thresholds; ours consistently uses
normalized fractions. Cold/terrain/equipment factors affect all resolved gaits
coherently rather than reproducing shell-specific animation exceptions.

## Completion and cancellation

`updateMovement` returns RUNNING until the route completes, SUCCEEDED on reaching
the final center, and FAILED when no route exists or a live physical/policy check
fails. A temporary immobility condition keeps the action RUNNING without moving.
An active obstacle crossing instead cancels safely on immobility; see the
traversal contract for commitment, reservations and native state packets.
Completion and failure stop motion, clear the route/policy/prediction and
capture the physical stopping point for existing greeting recovery. Stop resets
requested pace to WALK and clears native run/sprint flags. Physical conditions
remain owned by their producers and may remain visible while idle; effects still
expire by their deadlines.

Cancellation is idempotent through `stopMoving`; action failures, preemption,
dialogue/trade suspension and disposal all use the same engine stop. Per-NPC
waypoints, clocks and gait state are not shared. Native player simulation remains
detached and no additional chunks are pinned.

## Verification

The condition-contract update passed nine affected fixtures, including 111 new
modifier/precedence/native-presentation/cadence assertions. The other fixtures
passed: movement/roaming (571
assertions), factory/packets (80), dialogue (52 plus EN/PTBR catalog checks),
trading (538), brain/actions (72), sequences (31), profiles/control (56), and
module runtime (32). `42.21.0/patchApocalipseBr.ps1 -DryRun` compiled all 196
server Java sources against the actual game JAR. No deployment was performed.

`tools/test_human_roaming.py` compiles the production engine and adapter with
world stubs. It exercises route selection, preserved walking speed and cadence,
straight/corner prediction, obstruction/occupancy/unload failures and generic
cancellation. Direct-engine cases demonstrate movement across building boundaries
without a loaded roaming home, immutable route ownership, malformed route refusal,
elapsed-time bounds, per-NPC isolation, presentation snapshot transport and
additional policies that cannot bypass native collision checks.

Related regression fixtures cover action cancellation, profile/module scheduling,
factory lifecycle, dialogue and trading suspension. Compile the complete server
patch using `42.21.0/patchApocalipseBr.ps1 -DryRun` against the actual game JAR.
Stub assertions establish server behavior and packet fields, not visual playback.
`tools/test_human_movement_modifiers.py` additionally checks precedence, timed
effects, invalid inputs, consistent speed/animation caps, conditional running,
wounded gait, temporary immobilization/resumption, native flags and caches,
per-viewer animation sends, and immediate updates for pace or speed-only changes.
Manual checks remain two-client walk/run/sprint/limp footfall alignment, corners,
late viewer load and interruption by greeting, dialogue and trade.

Related: [architecture](NPC-ENGINE-ARCHITECTURE.md),
[physical capability investigation](docs/npc-physical-capabilities-and-combat.md).

`traversal/BodyOccupancy` owns the common native-body predicate and bounded
square occupancy check. Ordinary movement, prediction, combat clearance,
survival planning and traversal landing use it, so giblets cannot become targets
or physical occupants. Static geometry and live/unknown moving objects retain
their checks. The existing 64-object safety budget still fails closed when a
square cannot be fully classified within that budget.

## Returning to the designated area

The background walking adapter detects an origin outside its profile scope
before asking the pattern to wander or advance a waypoint. It delegates to
`AreaReturnPlanner`, a pure bounded same-floor A* search through loaded physical
space. The profile scope is the destination predicate, not a restriction on the
outside approach path. Rectangle profiles guide the search toward a permitted
nearest boundary coordinate when available; otherwise the home anchor guides
it. Indoor profiles target their original building. Ordinary explicit destination
orders retain their existing scope validation.

Each recovery search admits at most 256 discovered nodes and expands at most
256 queue entries, with at most 64 edges in a leg. If the domain is farther away,
a partial leg is returned only when it reduces distance to the domain anchor.
The next background request continues recovery; blocked/unloaded space returns
failure to the existing walking pause/retry schedule. There is no teleport,
chunk pinning or unlimited search. Physical obstacles, occupancy, doors and
traversal checks apply during both planning and execution.

`ServerHumanWalking` owns the transient recovery flag and native grid adapter;
`AreaReturnPlanner` depends only on a movement grid, inside predicate and anchor.
A recovery leg never calls the authored pattern's completion callback, so it
cannot skip a waypoint. On arrival inside the scope, subsequent background
requests resume normal scoped behavior. Preemption/cancellation stops recovery
through the existing movement lifecycle and leaves pattern progression intact.
No per-NPC key check or new persistence field is used.

DEBUG logs report `area return key=...; from=...; anchor=...; goal=...; steps=...`
and `area return settled ...; success=...; inside=...`. A successful partial leg
can report `inside=false`; the following background request continues inward.

Regression coverage in `test_human_walking.py` includes outside rectangle and
indoor origins, blocked-edge detours, bounded long-distance partial progress,
actual reentry/resumed roaming, and preserving the pending circuit waypoint.
`test_human_survival.py` verifies unrestricted physical escape and return after
release, with survival/engagement still ahead of background walking arbitration.
