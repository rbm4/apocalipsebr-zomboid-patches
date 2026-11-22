# NPC furniture affordances, rest policy and seating implementation plan

Date: 2026-11-10. Target: **42.22.2 dedicated server**, presenting NPC furniture
interaction to stock clients through synthetic vanilla packets.

Status: **proposed code changes, supported by local source inspection**. This
report adds no runtime Java, schema or deployment changes. Synthetic state
packets are the selected implementation direction; exact seating transition
payloads and live multiplayer behavior remain implementation validation work.

Implementation started 2026-11-11: see the maintained
[furniture foundation guide](../features/2026-11-11-npc-furniture-foundation.md)
for implemented source boundaries, confirmed behavior decisions, validation and
remaining native integration. This report remains the overall proposal.

Native room-scoped discovery and fair authoritative capture were connected on
2026-11-11; see the [native perception guide](../features/2026-11-11-npc-furniture-native-perception.md)
for implemented behavior, lifecycle identity, additional user decisions and
validation. The [seating execution guide](../features/2026-11-11-npc-seating-execution.md)
now records the mechanical slice, manual server API, source verification and
remaining multiplayer acceptance. The [autonomous rest integration](../features/2026-11-12-npc-autonomous-rest.md)
added 2026-11-12 implements the long seated routine, Sargento G profile migration
and stable seated greeting/dialogue/trade handling. Shared patrol opportunities
and low-endurance-specific rest policy remain subsequent work. The original proposal below is
historical scope, not a claim that all stages have shipped.

## Intended behavior and architecture

A profile registering `rest` receives the furniture observations and execution
services needed by that module. Composition enables the capability; module
policy selects when and where to use it. Each actor receives fresh module and
execution state. Profiles without a furniture consumer add no discovery work.

Support two policies using the same seating mechanics:

- **Seated post:** acquire a suitable seat within an assigned indoor station and
  remain seated during normal service, including compatible dialogue/trade.
  Return after interruptions when safe. Danger, damage and lifecycle removal
  retain priority over the station intent.
- **Opportunistic rest:** patrol/walk normally, then choose a safe seat during an
  idle opportunity or for endurance recovery. Stand after a bounded rest or when
  safety, higher-priority activity or patrol obligations change.

The A-life separation is durable intent -> observed affordance -> selected
activity -> physical execution. A vendor's durable intent is serving a station;
sitting is its local activity. A patrol actor can select that same activity for
another reason. Furniture discovery and reservation contracts can later support
stoves/workstations through separate capability adapters. Stair movement belongs
to navigation/traversal; future world affordances may describe stair access
without implementing vertical navigation inside the rest module.

Follow the [NPC architecture contract](../../../42.21.0/docs/features/2026-10-20-npc-engine-architecture.md),
[profile/lifecycle contract](../../../42.21.0/docs/features/2026-10-20-npc-profiles-lifecycle.md)
and carried [server-owned async authority contract](../../../42.22.1/docs/features/2026-11-05-npc-async-authority.md).
The [target documentation index](../README.md) records port/build status. Earlier
guides establish inherited contracts, not new 42.22.2 runtime verification.

## Source findings affecting implementation

All source links below refer to the inspected 42.22.2 target.

| Existing source | Finding and required response |
| --- | --- |
| [ServerHumanProfiles](../../src/zombie/apocalipse/humans/ServerHumanProfiles.java), [HumanModuleRegistry](../../src/zombie/apocalipse/humans/profiles/HumanModuleRegistry.java) | Register `rest` with validated immutable settings and a fresh allowlisted factory. No actor-key/name policy. |
| [HumanModuleBinding](../../src/zombie/apocalipse/humans/profiles/HumanModuleBinding.java) | Module parameters are at most 16 nonnegative `Long` values, each <= 86,400,000. String station names and negative coordinates need another representation. |
| [HumanModuleRuntime](../../src/zombie/apocalipse/humans/brain/HumanModuleRuntime.java), [WalkingModule](../../src/zombie/apocalipse/humans/brain/WalkingModule.java) | Lower priority numbers win; selected modules keep control while eligible. Walking stays eligible even while paused, so lower-priority rest can starve. |
| [GroupPatrolModule](../../src/zombie/apocalipse/humans/groups/GroupPatrolModule.java) and profile registration | Shared patrol owns waypoint progression; validation currently puts it after other modules. Rest needs explicit patrol opportunities and cannot advance the shared route itself. |
| [ServerHumanPerception](../../src/zombie/apocalipse/humans/ServerHumanPerception.java), [HumanObservation](../../src/zombie/apocalipse/humans/brain/HumanObservation.java) | Current perception concerns living contacts, territory and support. Add a separate detached furniture frame and discovery budget. |
| [HumanActivityController](../../src/zombie/apocalipse/humans/brain/HumanActivityController.java) | Dialogue/trade suspends autonomous modules and calls `actuator.face(visitor)`. Seated interaction requires explicit posture retention and constrained facing. |
| [ServerHumanEmotePacket](../../src/zombie/apocalipse/humans/ServerHumanEmotePacket.java), [ServerHumanMedicalPacket](../../src/zombie/apocalipse/humans/ServerHumanMedicalPacket.java) | Direct builders already create vanilla `StatePacket` payloads without a fake incoming-client path. Use this pattern for seating. |
| [ServerHumanTransport](../../src/zombie/apocalipse/humans/ServerHumanTransport.java) | Announcement reconstructs several active capabilities. Extend it for seating; audit movement flags and prediction so updates preserve posture. |
| [PlayerSitOnFurnitureState](../../decompiled/zombie/ai/states/PlayerSitOnFurnitureState.java) | Native parameters are `dir`, `sit_object`, `before_sit_dir`. Enter uses geometry; execute checks furniture removal/occupancy; animation events advance visual idle. |
| [PlayerGetUpState](../../decompiled/zombie/ai/states/PlayerGetUpState.java) | Parameters include `force`, `moving`, `iso_direction`. Cleanup clears furniture state and can eject the character from solid furniture. Standing must account for safe exit positioning. |
| [StatePacket](../../decompiled/zombie/network/packets/actions/StatePacket.java) | Client entry can wait for square proximity/state readiness; `isReady()` explicitly supports movement-to-furniture seating. Transition ordering matters. |
| [PZNetKahluaTableImpl](../../decompiled/zombie/network/PZNetKahluaTableImpl.java) | Existing parameter serialization supports `IsoObject` through `NetObject`. Use its exact wire representation. |
| [SeatingManager](../../decompiled/zombie/seating/SeatingManager.java) | Position counts/translations exist, but the character-based approach helper reads model, animation and deferred movement data. Verify dedicated-server availability before calling it. |
| [IsoObject](../../decompiled/zombie/iso/IsoObject.java), `isFurnitureOccupied()` | Vanilla checks other seated players on the object's square and compares the whole furniture object. This is not a slot reservation service. |
| [ServerHumanPhysiology](../../src/zombie/apocalipse/humans/ServerHumanPhysiology.java) | Idle already recovers logical endurance. Seating must use this owner once. Fatigue/hunger/thirst metabolism is not enabled here. |

## New components

The following names are proposed, not existing APIs. Paths are relative to
`src/zombie/apocalipse/humans/`.

| New component | Owner and responsibility |
| --- | --- |
| `furniture/FurnitureAffordances.java` | Immutable target tokens, affordance identifiers, candidate/approach values, coverage, timestamps and work counters. Initial implemented affordance: `SIT`. |
| `furniture/FurnitureDemand.java` | Detached demand aggregated from enabled modules/capabilities: kinds, radius, cadence and bounds. |
| `furniture/FurnitureReservations.java` | Factory-shared bounded leases, qualified by actor incarnation, target generation and compatible slot identity. |
| `furniture/FurnitureSelector.java` | Pure bounded candidate ranking using explicit settings, area, safety, distance and recent failures. |
| `rest/RestSettings.java`, `rest/RestModule.java` | Validated policy, eligibility, hysteresis/cooldown, chosen objective and request lifecycle. Module implements `HumanModule`. |
| `rest/HumanRestPort.java` | Injected detached snapshot/immutable request boundary and fresh execution handles/status, following the existing healing port pattern. |
| `furniture/SeatingExecution.java` | Fresh per-execution phase, deadlines, cancellation and exactly-once transition commits; native operations through an injected adapter. |
| `ServerHumanFurniture.java` | Server-owned bounded discovery, private native target bindings and initial seat classification/revalidation. |
| `ServerHumanSeating.java` | Per-actor native seating mechanics, posture ownership, geometry, cleanup and compatible interaction handoff. |
| `ServerHumanSeatingPacket.java` | Synthetic vanilla sit/get-up payloads and current-phase reconstruction; no rest policy. |

Keep the affordance API small. A future stove adapter must validate fuel/power,
inventory and effects independently. Discovery never authorizes an operation or
loads an arbitrary implementation from database-provided code.

## Profile composition and furniture perception

1. Validate `RestSettings` and derive the module's demand for `SIT`. Aggregate
   demand at composition time, leaving room for other consumers later. Avoid
   scattering checks for the rest module ID across native services.
2. `ServerHuman` assembles optional furniture/seating services and binds the
   fresh `RestModule` port, following healing's per-actor injection pattern.
3. Capture furniture on the server owner. Add a furniture frame to
   `HumanObservation`, retaining empty defaults for existing constructor callers.
   `ServerHumanPerception.observe()` assembles it alongside independent contact
   sensing. Do not force furniture into zombie/player contact memory.
4. Select from detached candidates. Resolve and revalidate the current native
   object before reservation, approach acceptance and sitting commit.

Frames distinguish complete, partial and unavailable coverage, with sample age.
Missing/unloaded squares cannot mean an empty or safe room. Start with loaded,
same-floor indoor squares inside the configured permitted area. Observed approach
candidates still require route, collision and final occupancy validation.

Proposed initial hard work ceilings: radius 12 tiles, 64 square visits and 128
object visits per actor sample, 32 published candidates and 1-second routine
sampling. Add a factory-wide ceiling of 256 square/512 object visits per
authoritative pass, fair resumable cursors and explicit partial coverage. Bound
native bindings, failed-target memory and reservations too. Verify these bounds
in fixtures; actual runtime cost remains a measurement task.

Target tokens combine location with a generation-qualified server binding and
slot identity. Coordinates, sprite names or list indexes alone cannot identify
removal/replacement at the same position. Recheck membership, attachment,
capability/sprite and relevant revision before commits. Tokens are transient;
native references stay private to the server adapter.

## Rest policy and arbitration

Register rest below defensive/medical/required task policy and above background
walk/group patrol. Validate relative ordering against the actual enabled modules.
Rest may preempt walking only when eligible; a failed attempt or unavailable seat
must yield during backoff rather than retain selection and block patrol forever.

Initial modes are `POST` and `OPPORTUNISTIC`. Proposed numeric parameters, within
the current binding map limits: `mode` (enum code), `searchRadius`,
`scanIntervalMs`, `idleDelayMs`, `minRestMs`, `maxRestMs`, `retryMs`, `cooldownMs`,
`startEndurancePermille`, `stopEndurancePermille`, and
`retainSeatDuringInteraction` (boolean code). Validate units/ranges, start < stop,
and compatible settings. Document units despite the historical database column
name `value_ms`. POST uses repeated bounded service intervals while retaining a
valid seat; do not represent permanence with an infinite timer.

For the initial post, require a bounded station area using the existing profile
walking-area representation plus the actor's home anchor. Select within that
area. Exact named stations/multiple workstations need a later typed configuration
extension; do not squeeze identifiers or arbitrary coordinates into Long settings.

Opportunistic eligibility requires an idle opportunity or low endurance, fresh
safety facts, an acceptable candidate, no incompatible commitment and no active
cooldown. Maintain eligibility while execution runs. Minimum dwell and separate
start/stop endurance thresholds prevent oscillation; danger bypasses minimum dwell.
Observe authoritative motion/activity through the port rather than waiting for
`WalkingModule` to relinquish selection while paused.

Optional rest yields to an active patrol leg. For shared patrol, expose the
coordinator's dwell/hold/recovery opportunity and revision through
`GroupPatrolPort`/`ServerHumanGroups`; zero speed alone is insufficient. Endurance
rest can request interruption under explicit policy. Opportunity changes cancel
optional rest, and a resting member never advances the shared waypoint. Preserve
regroup and recovery obligations.

## Seating execution and cancellation

Normal phases: `ACQUIRE -> APPROACH -> VALIDATE -> SIT_DOWN -> SEATED ->
STAND_UP -> RELEASE -> COMPLETED`. Failure/cancellation return explicit terminal
status and reason. Mutable progress, timers and native bindings belong to fresh
executions and services, never reusable settings/actions.

- Reserve before moving and renew while valid. NPC leases cannot override player
  occupancy or invalid geometry. Although keys support slots, initially exclude
  the whole object when native occupancy treats it as one seat. Independent sofa
  slots require verified native object/slot mapping and player coexistence.
- Use existing `MovementRequest.To` navigation to a validated approach position.
  Sitting alignment is a bounded native transition, not arbitrary teleportation.
- Resolve seat orientation and Front/Left/Right approach from target seating data.
  Verify dedicated-server metadata/model availability. If the character helper
  cannot run there, provide a server-safe geometry adapter using verified native
  data and bounded supported definitions. Do not initialize rendering or invent
  offsets for every sprite. Unsupported geometry returns unavailable.
- Keep the reservation through normal standing and validated exit. New movement
  or combat owners must wait for bounded posture cleanup before moving. Module
  cancellation can hand cleanup to the seating service, which continues ticking
  even after that module is suspended.
- Damage/hit reaction, death, despawn, invalid furniture and unload use an abort
  path with idempotent cleanup and correct reaction precedence. Never play get-up
  over a death/hit reaction. Release flags, private references and leases even
  if presentation fails. Interrupted ownership cannot leave an orphan animation.

## Seated dialogue/trade

The module owns autonomous rest intent; the seating service owns physical posture.
Add an explicit compatible-interaction handoff at the activity boundary. Cancel
approach/transient execution on session entry; a stable seated posture may remain
under a service-held posture lease while dialogue/trade owns activity. Release or
return it when the session ends and create fresh autonomous execution state.

Make interaction facing posture-aware: keep seated body alignment and use supported
head attention where available, otherwise retain facing. Current unconditional
whole-actor visitor facing can rotate an NPC away from its chair. Incompatible
motion requests first stand/abort. Session timeout, disconnect, emergencies,
blocked/stopped activity and lifecycle cleanup must close all posture ownership.

Continue using existing bounded `Sequence`, `Wait` and `Say` for ordered content.
Initial rest uses an injected capability port like healing. If seating later gets
a `HumanAction` description, its mutable lifecycle belongs in execution state;
do not enable competing movement/posture lanes through generic `Parallel`.

## Synthetic vanilla transport

Build packets directly in the established emote/medical style. Sitting uses
`PlayerSitOnFurnitureState` parameters; standing uses `PlayerGetUpState`
parameters. Trace exact action-context event names, main/substate flags and
sit-exit/get-up-enter ordering against target Java and game action/animation
data. Class names alone do not establish the complete event sequence.

Use native `IsoObject`/`NetObject` serialization. Do not call `processServer()`
as a fake incoming client or fabricate an authenticated player connection.
Server-owned posture fields/phases remain authoritative; packets present results.
Do not wait for a remote animation event to commit server state. Establish bounded
server phase timing and interruption rules against target animation data.

Extend `ServerHumanTransport.announce()` after actor creation to reconstruct the
current posture. Stable seated reconstruction must include native idle phase
variables as necessary, avoiding replay of an incompatible approach. Trace the
exact additional existing state parameters/update payload before finalizing it.
Account for unresolved furniture/world streaming with bounded retry, relevance
and incarnation checks. Reliable delivery alone does not prove an object is ready.

Audit `movementPacket()`, native `NetworkPlayerVariables` and `Variables` so
heartbeat/position updates retain seating flags, zero travel and seat alignment.
Announcement during stand/abort reconstructs that current state. Never replay a
stale sit after control changes, despawn, replacement or a new incarnation.

## Existing-code change map

Paths below are under `src/zombie/apocalipse/humans/` unless qualified.

| Existing files | Planned changes |
| --- | --- |
| `ServerHumanProfiles`, `profiles/HumanModuleRegistry` | Register rest, validate settings/order/area, derive sensing demand and add example post/patrol-rest profiles. |
| `ServerHuman` | Compose/bind optional services per incarnation; deterministic cleanup. |
| `ServerHumanFactory` | Own reservations and global scan budget, capture demands, tick seating during suspended activity, order posture updates with motion/announcement and clean up before removal/shutdown. |
| `ServerHumanPerception`, `brain/HumanObservation` | Assemble detached furniture frame and retain compatible empty constructor defaults. |
| `brain/HumanActivityController`, `ServerHumanActuator` | Add posture handoff, motion gates and facing rules. Audit dialogue/trade/emergency entry/closure callers. |
| `ServerHumanWalking`, `ServerHumanMovement` | Respect posture transitions and integrate validated approach/exit alignment while retaining route/traversal ownership. |
| `groups/GroupPatrolPort`, `GroupPatrolModule`, `ServerHumanGroups` | Publish explicit rest opportunities/revisions and preserve regroup/recovery/shared route contracts. |
| `ServerHumanTransport` | Send/reconstruct seating transitions, audit native flags and prediction, clear stale viewer state. |
| `ServerHumanPhysiology`, state reducers | Confirm seating uses idle recovery once; initial release adds no recovery multiplier. |
| `zombie.savefile.ApocBRNpcStore` | Reuse current validated module parameter intake where sufficient. Audit checkpointed seated coordinates so activation reacquires a safe approach instead of restoring inside solid furniture. |

Persist profile/station intent through existing detached configuration boundaries.
Do not persist live seat tokens, leases, executors, native IDs, connections or
monotonic deadlines. No schema migration is assumed for the initial numeric
settings. Any later typed station schema needs detached intake, explicit migration
ownership and engine-role grants under repository rules.

Keep `decompiled/` unchanged. Start with support classes and existing patch seams;
an additional vanilla replacement, if needed, requires exact-baseline review.

## Implementation sequence and validation

1. Furniture foundation: immutable contracts/demand, bounded discovery, identity
   invalidation and reservations. Verify rest profiles get frames and unrelated
   profiles add zero scan work.
2. Complete seating slice: one supported chair, approach, synthetic sit/idle/stand,
   abort and late observer. Establish headless geometry and native serialization.
3. Rest modes: post/opportunistic settings, safety, cooldown/hysteresis and walking
   arbitration; add example profile compositions.
4. Interaction/shared patrol: posture-compatible dialogue/trade, facing, explicit
   patrol opportunities, interruption, checkpoint/reactivation and shutdown.
5. Target fixtures/build, multiplayer acceptance and an implemented feature guide
   linked from this report and the version index.

Proposed fixtures: `tools/test_human_furniture.py`, `test_human_rest.py`,
`test_human_seating.py`, exercising actual **42.22.2** production implementations
and packet builders. Existing harnesses often hardcode 42.22.1; parameterize
affected runners or add explicit target cases before treating them as evidence
for new code. Keep engine/world/network doubles small and disclose their limits.

Required cases: bounded/fair discovery, partial/unavailable/stale frames,
no-demand zero work, removal/replacement at identical coordinates, unload/reload,
lease contention/expiry/player occupancy, per-actor state isolation, profile
rejections, rest starvation/backoff, patrol opportunity changes, normal/quick
stand, repeated cancellation, motion blocked during cleanup, exactly-once packet
transitions, dialogue/trade handoff and facing, damage/death precedence, late
viewer reconstruction and stale incarnation rejection. Exercise native furniture
reference/state parameter serialization, not only packet constructor strings.

Run affected profile/brain/movement/traversal/dialogue/trade/group/physiology and
async-authority fixtures against the selected target. Validate server ownership
and both async startup modes; this feature introduces no native worker access.
Compile with the 42.22.2 script's non-deploying build option and real target JAR.
Record actual commands/results during implementation. This documentation-only
change requires no Java build and makes no gameplay verification claim.

Dedicated-server stock-client acceptance: chair/sofa orientations, competing NPCs,
player occupancy, furniture moved/destroyed, blocked approach/exit, indoor floor
boundaries, seated vendor dialogue/trade, ordinary/emergency patrol resumption,
damage/death during transitions, simultaneous viewers, late join, relevance/chunk
reload, restart and fresh incarnation. Check alignment, sliding/stuck animation,
native flags, lease cleanup and work counts under a furnished lobby load.
