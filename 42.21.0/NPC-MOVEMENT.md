# NPC movement engine and roaming patterns

Implemented for server Build 42.21.0. Movement mechanics and condition resolution
are separate from roaming; live player animation and dedicated-server playback still require
validation. It introduces no database migration or deployment.

## Ownership

| Component | Responsibility |
| --- | --- |
| `RoamModule` / legacy `GuideBrain` | Decide when to request roaming and how long to pause; cancel when preempted. |
| `ServerHumanRoaming` | Choose a route through `IndoorRoutePlanner`, seed route variety, bind the home building and restrict roaming to it. |
| `ServerHumanMovement` | Per-NPC physical movement engine: follow a supplied route, integrate position/facing, enforce traversal/collision/occupancy, apply resolved gait/speed/animation, and clip prediction. |
| `MovementModifiers` | Immutable intent, settings and condition contracts; pure resolution of pace, physical speed, animation modifiers and decision reason. |
| `MovementGrid` | Shared tile and traversal-policy contract used by both planners and the movement engine. |
| `HumanActionExecution` / `ServerHumanActuator` | Start the requested pattern, advance its route through generic `updateMovement`, and stop on completion/failure/cancellation. |
| `ServerHumanTransport` | Encode the engine's resolved movement snapshot in the existing vanilla player packet. |
| `ServerHumanFactory` | Schedule simulation/heartbeat and publish immediate direction or moving-state changes. |

Roaming no longer owns the waypoint cursor, position integration, speed,
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
The policy cannot override physical safety checks. Roaming passes itself as the
policy to retain its existing home-building and home-square availability rules.
A different future pattern may supply another constraint or just physical traversal.

Routes are immutable copies with at most 256 waypoints. They contain cardinally
adjacent tile centers on the actor's configured floor, optionally starting with
its current tile. Empty, oversized, diagonal and skipped-tile routes are refused.
An invalid replacement stops the prior route. Physical checks are repeated during
execution because a valid planned edge can become blocked or unloaded.

This is a bounded same-floor walking executor, not a general pathfinder. It
does not select destinations, open doors, climb stairs or cross floors. Path
planning stays separate; `IndoorRoutePlanner` retains its 256-visited/12-edge
roaming search limits. The engine does not depend on that planner or its limits.
No direct call may bypass existing activity ownership: new autonomous patterns
must still run through the behavior/action/controller contracts.

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
