# Server human obstacle traversal

Implemented for server Build 42.21.0 in `zombie.apocalipse.humans.traversal`.
Production-code fixtures and the real game-JAR compilation validate server logic
and native signatures. Dedicated-server visual playback has not been validated.
No client Java classes, packet formats, database schemas or live installations
are changed by this implementation.

## Ownership and dependencies

| Component | Owns |
| --- | --- |
| `TraversalEdge` | Transient observation: cardinal endpoints, floor, kind and native obstacle identity. |
| `CrossingReservations` | Canonical bidirectional edge and destination leases; identity-safe release and nine-second expiry. |
| `TraversalWorld` | Factory-owned leases and active detached-player occupancy. |
| `TraversalExecution` | Per-actor waiting, preparation, commitment, completion, timeout and cancellation through an injected port. No game initialization or networking. |
| `HumanTraversal` | Native geometry queries, safe door/window interaction, bounded actor-local failure exclusions and authoritative landing. |
| `TraversalStatePacket` | Existing vanilla State format and native fence/window parameters and events. |
| `ServerHumanMovement` | Route cursor, ordinary walking, condition permission and ownership handoff to traversal. |

The factory injects one shared `TraversalWorld` into every active presentation.
Disposal unregisters its detached IsoPlayer. No static registry, worker, SQL,
native player ownership or chunk pinning is introduced. Route policies retain
home-building and combat-territory restrictions. All live reads/writes stay on
the existing simulation thread; transient references, leases and monotonic clocks
are never persisted.

## Initial coverage

- Ordinary `IsoDoor` and door-like `IsoThumpable`: inspect native opening permission,
  barricades and obstruction; call native ToggleDoor once and verify it opened.
- `IsoWindow`: open/unlocked windows and safely broken windows with glass removed.
  An unlocked, nonpermanent closed window may be opened using native ToggleWindow.
  Never toggle a locked window: that native method itself would clear its lock.
- `IsoWindowFrame` and window-like `IsoThumpable`: native passage validation.
- Low objects returned by `getHoppableTo`, including qualifying constructed fences.
  Tall fences, nonhoppable walls, stairs, counter landings, unsupported floors,
  water and the A-life `fixtures_railings` exclusion remain blocked.

Thumpability alone does not grant vault permission. Picking locks, breaking
windows/doors/barricades, removing glass, climbing tall fences, ropes, stairs and
floor changes are outside this initial passable-obstacle scope. These require
additional edge kinds/handlers rather than loosening ordinary walking collision.

Planning never opens objects or claims crossings. Native door predicates require
a current square: the adapter temporarily assigns the queried source with
`setCurrent` and restores the previous reference in `finally`. In 42.21 this
method is a reference assignment; it does not add moving-object membership.
Native character update/climb methods are not run on the server presentation.

## Planning and execution

`MovementGrid.traversalCost` defaults to the existing boolean connection contract.
Native policies supply relative effort: walk 1, door 2, fence 5, window 6.
Both route planners use bounded deterministic Dijkstra searches, retaining their
existing node/depth limits and destination rules. The short combat withdrawal
permits only ordinary walk edges. Costs are initial tuning, not measured timings.

At an action edge the actor centers on the source square, clears ordinary gait
and prediction, then begins traversal. It acquires both the canonical crossing
and destination lease, rechecks obstacle identity, policy and landing, and prepares
the object. Opposite directions conflict; different crossings into one destination
also conflict. Release compares the exact lease, so stale cleanup cannot release
a replacement. Lease contention and occupied landings wait at most two seconds.

Landing checks combine native floor/static/occupancy checks, PolygonalMap2
`canStandAt` (including native obstacle/vehicle geometry), and the factory's
detached-human positions. The native square moving-object list alone cannot see
these bots. Revalidation runs before commitment; after commitment another actor
entering that square does not undo an already completed crossing.

For climbs the server owns an explicit phase clock: fence 2.0 seconds, window
2.6 seconds, commitment at 60 percent. Those are initial presentation timings,
not A-life watchdog values. Position stays at a valid source until commitment,
then moves once to the validated destination center, where it settles. Only this
specific edge bypasses the ordinary walking segment collision test. No actor is
left at an intermediate point inside the obstacle. A six-second active watchdog
rejects stale execution after a long simulation stall.

Native State Enter packets describe the real source/destination, direction and
portal with EventClimbFence/EventClimbWindow. Walking/running prediction is zero
during traversal and never looks ahead across an action edge. Position is sent
immediately at commitment. State Exit carries success after commitment or back
before commitment, and cancels pending native enters. Native client animation
events finish the visual state; they never authorize server movement. Client
completion is not acknowledged. Announcements before commitment replay the native
enter; announcements after commitment use current position and do not rewind to
replay that crossing. Late-viewer animation continuity is intentionally limited.

## Failure and cancellation

Completion returns movement control to the route; the next update consumes a
reached waypoint or walks through an opened door. Combat replanning does not
replace an active crossing. Changed openings, blocked landings, preparation
failure, expired leases and timeouts fail the owned route. Short actor-local
15-second edge exclusions (at most 64 entries) prevent immediately repeating
physical failures. Busy/occupied outcomes do not poison an edge. Exceptions
release leases before propagating to existing activity failure handling.

Stop, replacement, dialogue/trade preemption, immobility during a crossing and
disposal cancel the execution. Before commitment the actor stays at the source;
after commitment it stays at the destination. Cleanup releases both reservations,
sends the native exit, and lets movement capture the valid greeting recovery
position. Ordinary nontraversal immobility still preserves its paused walking route.

## Evidence and validation

Vanilla 42.21.0 references: PathFindBehavior2.checkDoorHoppableWindow,
IsoGridSquare.isPlayerAbleToHopWallTo/getHoppableTo/isFree, IsoDoor/IsoThumpable
opening checks, IsoWindow/Frame passage checks, ClimbOverFenceState,
ClimbThroughWindowState/PositioningParams, StatePacket and NetworkState.
All remain unmodified. The installed A-life 42.20 ALifeObstacleTraversal.lua
provided design references for classification, reservations, landings and failure
recovery; its zombie-shell controller and client-authority execution are not copied.

`python tools/test_human_traversal.py` executes production reservation/lifecycle,
native adapter, movement, planners and packet builders with small engine stubs.
Also run roaming, lifecycle, movement-modifier, combat, dialogue and trading
fixtures, then `42.21.0/patchApocalipseBr.ps1 -DryRun` against the real game JAR.

Manual validation still required: doors in both orientations and lock/inside/key
cases; windows/frames/constructed fences in all four directions; two actors using
opposite sides or converging landings; player/zombie/vehicle occupation; portal
change/unload; dialogue/trade/death interruption before/after commitment; two-client
animation/position alignment, cancellation and late relevance. Native client
animation duration versus the server phase clock is the principal calibration risk.

Regression results for this implementation (standalone scripts under `tools/`):

| Script | Result |
| --- | --- |
| `test_human_traversal.py` | 93 assertions passed: leases, lifecycle, native classification, four-direction fence/window execution, removed-portal exit, packet fields and weighted routing. |
| `test_human_roaming.py` | 571 passed. |
| `test_server_humans.py` | 80 passed. |
| `test_human_movement_modifiers.py` | 111 passed. |
| `test_human_weapon_combat.py` | 67 passed. |
| `test_human_dialogue.py` | 52 passed, plus EN/PTBR catalogue checks. |
| `test_human_trading.py` | 538 passed. |
| `test_human_engagement.py` | 46 passed. |
| `test_human_profiles.py` | 56 passed. |

The target-version patch script's `-DryRun` compiles the full source set against
the installed 42.21.0 `projectzomboid.jar`; it performs no deployment.
