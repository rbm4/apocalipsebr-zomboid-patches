# NPC movement engine and roaming patterns

Implemented for server Build 42.21.0. This change extracts the current walking
mechanics; live player animation and dedicated-server playback still require
validation. It introduces no database migration or deployment.

## Ownership

| Component | Responsibility |
| --- | --- |
| `RoamModule` / legacy `GuideBrain` | Decide when to request roaming and how long to pause; cancel when preempted. |
| `ServerHumanRoaming` | Choose a route through `IndoorRoutePlanner`, seed route variety, bind the home building and restrict roaming to it. |
| `ServerHumanMovement` | Per-NPC physical movement engine: follow a supplied route, integrate position/facing, enforce traversal/collision/occupancy, resolve walking gait/speed, and clip prediction. |
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

`movement.start(route, now)` executes a caller-selected route using physical
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

The preserved walking profile is 2 tiles/second. Each update integrates at most
150 ms, consumes leftover distance across tile centers and updates real position
and facing together. A backwards clock contributes no new movement budget.
The current native walking animation is selected by the movement flag, using the
engine's `IDLE`/`WALK` gait snapshot. The snapshot also supplies prediction speed
and distance, so packet construction has no separate roaming-speed constant.

Routine motion still runs at 100 ms and publishes at 500 ms. Starts, stops and
turns publish immediately. Prediction still covers up to one second on straight
connected edges, uses eighth-tile units, and stops before turns/obstructions with
the existing endpoint headroom. Distance encoding is bounded to a positive byte.
Clients receive the existing vanilla packet contract without a Java client patch.

This extraction deliberately keeps walking output. RUN/SPRINT, injury-dependent
speed, independent facing/strafe and additional animation modifiers are subsequent
extensions to the movement engine and its presentation adapter. Neither profiles
nor roaming should set native animation variables or mutate coordinates to add
those features. New pace transitions will also need immediate publication when
pace changes without a start/stop/turn.

## Completion and cancellation

`updateMovement` returns RUNNING until the route completes, SUCCEEDED on reaching
the final center, and FAILED when no route exists or a live physical/policy check
fails. Completion and failure stop motion, clear the route/policy/prediction and
capture the physical stopping point for existing greeting recovery.

Cancellation is idempotent through `stopMoving`; action failures, preemption,
dialogue/trade suspension and disposal all use the same engine stop. Per-NPC
waypoints, clocks and gait state are not shared. Native player simulation remains
detached and no additional chunks are pinned.

## Verification

The extraction passed all eight affected fixtures: movement/roaming (571
assertions), factory/packets (80), dialogue (52 plus EN/PTBR catalog checks),
trading (538), brain/actions (72), sequences (31), profiles/control (56), and
module runtime (32). `42.21.0/patchApocalipseBr.ps1 -DryRun` compiled all 195
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
Manual checks remain two-client walking/footfall alignment, corners, late viewer
load and interruption by greeting, dialogue and trade.

Related: [architecture](NPC-ENGINE-ARCHITECTURE.md),
[physical capability investigation](docs/npc-physical-capabilities-and-combat.md).
