# NPC furniture foundation: observations and interaction leases

Target: **42.22.2 server**. Status: initial foundation implemented in source on
2026-11-11. The production fixture and exact target-JAR compilation pass. Nothing
was deployed. Native furniture classification, rest module policy, seating
geometry, sit/stand packets and interaction handoff remain subsequent work in
the [implementation plan](../reports/2026-11-10-npc-furniture-rest-implementation-plan.md).
Existing profiles do not acquire sitting behavior from this change.

## Confirmed behavior decisions

- A busy seat causes the NPC to try another seat, then yield to its routine.
  Assigned vendors retry inside their station. No persistent exact-seat queue.
- Include Front, Left and Right approaches, subject to native geometry and
  obstruction validation. The detached representation supports all three.
- Roaming NPCs may rest during patrol pauses and at low endurance; these triggers
  will be configurable in the rest profile/module.

These are requirements for the remaining execution work. The current selector
supports occupied/refused-seat exclusion and all three approach values, but
does not itself move an actor, retry a policy or authorize sitting.

## Implemented ownership and boundaries

| Component | Responsibility / dependencies |
| --- | --- |
| [InteractionReservations](../../src/zombie/apocalipse/humans/interactions/InteractionReservations.java) | First-operation-thread-owned atomic leases over typed resource keys, allowing factory construction before its runtime owner is initialized. Bounded capacity, expiry, renewal, identity-safe release and owner cleanup. Contains no world/NPC/packet policy. |
| [CrossingReservations](../../src/zombie/apocalipse/humans/traversal/CrossingReservations.java) | Existing window/fence/door reservation contract now delegates to that shared primitive. Preserves crossing plus landing exclusion, nine-second expiry, receipt fields and traversal cancellation behavior. Its resource bound is 256, above the maximum simultaneous need of the 64-actor factory. |
| [FurnitureReservations](../../src/zombie/apocalipse/humans/furniture/FurnitureReservations.java) | Separate furniture resource namespace using the same primitive. At most 64 resources; five-second renewable leases. Different direction/slot tokens for the same generation/location initially exclude the whole object to match conservative vanilla occupancy. |
| [FurnitureBindings](../../src/zombie/apocalipse/humans/furniture/FurnitureBindings.java) | Server-private native identity storage, maximum 2048 bindings. Generation-qualified detached tokens; movement, revision change, removal/re-add, eviction and clear cannot revive an old token. |
| [FurnitureAffordances](../../src/zombie/apocalipse/humans/furniture/FurnitureAffordances.java) | Immutable observation DTOs: target token, kind, positions/approaches, orientation, observed occupancy, per-candidate time, frame coverage and work counts. Initial kind is `SIT`. No live object in a frame. |
| [FurnitureDemand](../../src/zombie/apocalipse/humans/furniture/FurnitureDemand.java) | Module-declared immutable sensing needs. Aggregate kinds, maximum radius and minimum sampling interval. Empty demand requires no scans. |
| [FurnitureScanner](../../src/zombie/apocalipse/humans/furniture/FurnitureScanner.java) | Construction-thread-owned resumable discovery over an injected world port. Budgets native square/object visits and publishes detached frames. No seat selection or hostility policy. |
| [FurnitureScanBudget](../../src/zombie/apocalipse/humans/furniture/FurnitureScanBudget.java) | Authoritative-pass shared square/object counters, with construction-thread checks. Future factory orchestration resets and distributes it. |
| [FurnitureSelector](../../src/zombie/apocalipse/humans/furniture/FurnitureSelector.java) | Pure bounded ranking of fresh observed candidates/approaches, excluding occupied/refused targets and different floors. A choice still requires live validation and reservation. |
| [HumanModule](../../src/zombie/apocalipse/humans/brain/HumanModule.java), [HumanModuleRuntime](../../src/zombie/apocalipse/humans/brain/HumanModuleRuntime.java) | Generic sensing-demand declaration and aggregation. Defaults to none; no module ID or actor-name branching in the sensor. |
| [HumanObservation](../../src/zombie/apocalipse/humans/brain/HumanObservation.java) | Adds a detached furniture frame while retaining previous constructors, including the eight-argument signature. Existing callers receive `Frame.EMPTY`; living-contact sensing remains independent. |

Reservation services share an implementation, not a global untyped key space.
Crossing rules stay in traversal; furniture rules stay in its adapter. A future
executor can use atomic resource lists for additional exclusivity when needed.
No persistent waiter queue, native worker port or new thread is introduced.

The native adapter must use one shared bindings/reservation service for the
factory's actors so the same object has the same generation across observers.
It must verify live square/object membership, capability revision, supported
slot, occupancy, geometry and actor incarnation immediately before effects.
`resolve()` finds a private binding; it does not prove current world validity.
Tokens/leases are never persisted or accepted as trusted client authority.

## Discovery bounds and freshness

Demand radius is at most 12 tiles; routine interval is 1000–60000 ms. Each update
visits at most 64 squares and 128 objects; a shared budget permits at most 256
square and 512 object visits before reset. Frames hold at most 32 candidates,
each with at most three approaches. A circle cursor resumes between updates,
including within a dense square. If object membership/order revision changes on
a resumed square, the scanner restarts that square's object cursor.

The world port's count/revision queries must be constant-time and inspect must
be bounded. The port must return a candidate for the requested square/kind with
the actual observation timestamp. The current scanner checks this contract;
native implementation of that port is pending. It must not hide another full
object scan inside one budgeted `inspect` call.

Partial sweeps, missing squares, truncation and expired candidates prevent a
complete-coverage claim. A completely unavailable area or denied initial budget
reports unavailable. Freshly emitted frames cannot refresh candidates captured
earlier in a long sweep. Candidates expire after two seconds for selection;
frame freshness is checked separately. Origin changes invalidate the cached
scan immediately, even before its routine sampling deadline.

The shared budget enforces a global ceiling; fair allocation among actors still
requires the future factory round-robin integration. The current fixture proves
bounded work and per-observer scan progression, not global scheduling fairness
or measured gameplay performance.

## Failure and cancellation contracts

- Reservation conflict/capacity returns no lease without partially acquiring any
  resources. Expired receipts cannot renew; releasing an old receipt cannot
  release a newer claimant. Explicit owner cleanup uses incarnation identity.
- Traversal retains its existing bounded wait, completion, cancellation and
  exception cleanup through the unchanged `TraversalExecution` lifecycle.
- Furniture policy will own alternate-seat attempts/backoff. The selector returns
  no choice if observations are unavailable/stale or every eligible seat is
  occupied/refused. It never waits or starts an action.
- Bindings evict oldest retained objects at capacity, invalidating their tokens.
  Generation numbers are not recycled by clear, removal or object reuse.
- Clearing a scanner releases retained observations/progress. Native port
  exceptions propagate to the caller; there is no swallowed failure or fabricated
  empty success. Actor/lifecycle cleanup of the future native adapter is pending.

The async planner may receive immutable frames as part of a copied observation;
it receives no scanner, binding registry, lease service or world port. All mutable
services here stay with their bound server owner. No worker job or native
capability has been added to the existing detached planner.

## Validation

`py tools/test_human_furniture.py` compiles actual 42.22.2 production sources and
passes **5265 assertions** at this checkpoint. It exercises atomic conflict,
renewal/expiry, stale release, owner/thread cleanup, capacity, identity
invalidation/reuse, per-observer/global scan budgets, no-demand zero work,
unavailable/partial frames, cadence/origin changes, resumed list mutation,
truncation, candidate aging, defensive copies, side selection, module demand,
arbitration/backoff and observation-constructor compatibility.

The same fixture exercises production `TraversalExecution` with small native
operation doubles and compares 4000 deterministic randomized lease operations
with the exact preceding 42.22.1 `CrossingReservations` source. Acquisition,
ownership, expiry and receipt results match. It also verifies opposite crossing
and shared landing exclusion, floor isolation, one-shot landing, idempotent
cancel and cleanup when native begin throws.

Only unrelated actuator/profile-identifier boundaries and native operations are
doubled. This fixture does not test native furniture classification, geometry,
pathfinding, packet serialization, live sessions, streaming or multiplayer.

Exact target-JAR build: `powershell -NoProfile -ExecutionPolicy Bypass -File
42.22.2/patchApocalipseBr.ps1 -BuildOnly`. The initial build compiled **347 sources
and 1007 classes**, with only the pre-existing deprecation/unchecked notices;
artifacts are ignored under `42.22.2/artifacts/build_20261111_001758/`. The final
build after the server-owner check changes also passed with the same counts;
retained output is `42.22.2/artifacts/build_20261111_002304/`. The script exited
with code zero and performed no deployment.

## Next implementation boundary

Implement the server-native world adapter and globally fair capture scheduling,
then registered rest settings/policy, profile composition and the per-actor
seating executor. Validate dedicated-server geometry from target native seating
and animation data for all three sides. Extend native packet presentation,
posture-aware movement/facing, session handoff, patrol opportunities and lifecycle
cleanup as specified in the plan. No new `rest` profile/module is registered
until its native capability is available and validated.
