# Dialogue-assigned NPC tasks

Build 42.21.0, server only. Authored dialogue effects are implemented; dedicated
server/client movement and cancellation playback still require in-game validation.
Apply manager migrations V26-V27 before starting this engine. The existing
ExtractionMapUtils dialogue bridge v4 and resolved schema 2 carry the conversation;
no new client packet, Java patch or Lua callback is required.

## Ownership

`HumanTaskCommand` is immutable data. `Kind` names are stored as stable strings,
never ordinals. `ApocBRNpcDialogues` loads and validates the command and parameters
on the SQL worker into the pinned graph. Public dialogue views expose no effects.
SQL cannot name an executable class or script.

`ServerHumanDialogue` validates the existing authenticated conversation, node,
revision, sequence, proximity and cooldown, then preflights the task capability
and destination before flags or speech. Only after the existing reaction succeeds
does it assign intent and advance the graph. Repeated sequences are rejected and
cannot assign a second task. This is activation-local at-most-once acceptance,
not a durable quest receipt or a transaction spanning speech, facts and tasks.

`AssignedTaskModule` owns one actor's assignment and execution state. Its
`Snapshot` reports command, status and reason. `HumanModuleRuntime.module(Class)`
provides typed capability discovery; role/NPC names do not select implementations.
`ServerHumanTasks` supplies native position, player identity resolution, movement
scope checks and transition diagnostics through `HumanTaskWorld`.

The profile must enable `assigned-task`, with no module parameters and a
`background` movement scope. Its priority follows survival/combat and precedes
background walking. Only the selected module actuates. Dialogue and trade retain
exclusive activity ownership. Task choices must close dialogue: accepting intent
does not start movement while the conversation holds the actor.

## Commands

| Kind | Authored parameters | Runtime behavior |
| --- | --- | --- |
| `HOLD_POSITION` | Optional `durationMs`, integer 0–86400000, default 0 | Suppress background walking; 0 holds indefinitely. Timed holds count selected execution time, excluding preemption. Defense may reposition the actor. |
| `MOVE_TO` | Required integer `x`, `y`, `z`; nonnegative X/Y below Integer.MAX_VALUE, Z −32…31 | One scoped route; success returns control to routine. |
| `FOLLOW_PLAYER` | Optional integer `distanceTiles`, 1–8, default 2 | Bind the authenticated conversation participant's online ID plus character instance identity. Choose a permitted stopping tile near them, and replan at most once a second. |
| `RESUME_ROUTINE` | None | Cancel the assignment and allow ordinary arbitration. |
| `CLEAR_CORPSES` | Required destination `x`, `y`, `z`; optional `radiusTiles` 0-2 (default 2); optional complete `sourceX`, `sourceY`, `sourceZ` | Search a square radius around the NPC's current tile as it roams, unless a fixed source is explicitly authored. Gather one corpse at a time and haul it to the authored destination. |

Unknown kinds/parameters, partial coordinates and invalid values reject the
content. SQL publication applies equivalent validation, including mandatory
conversation closure. Parameters currently represent integers; the keyed string
storage leaves future handlers room for validated objective/zone identifiers.
Supporting a new kind requires a code implementation, validation, matching SQL
allowlist, editor enum and regression coverage. A database row alone cannot add
an engine capability. Quest rewards are not implemented here.

## Corpse cleanup (V27)

Native pickup/drag animation compatibility is assessed separately in
[NPC corpse grapple compatibility](docs/npc-corpse-grapple-compatibility.md).
The native runtime now uses [phased grapple dragging](NPC-CORPSE-GRAPPLE.md);
the assessment records the original investigation.

Destination coordinates are dialogue metadata, not engine constants. Example:

```json
{"kind":"CLEAR_CORPSES","parameters":{"x":"270","y":"14437","z":"0","radiusTiles":"2"}}
```

Author these as a `dialogue_choice_behavior` row and keyed
`dialogue_behavior_parameter` rows for the same choice. Radius 2 means a 5x5
square centered on the NPC's current tile at each passive search, on the same
floor. Roaming moves the discovery center; the dialogue participant's position
has no effect. Explicit optional fixed source coordinates must be provided
together and override mobile discovery. Search center/destination must be inside
the profile's loaded cleanup scope. The destination tile is excluded from the
search, even if it overlaps the source area.
The cleanup order remains assigned until `RESUME_ROUTINE` (the existing
"go back to normal" dialogue option), another replacement order, or actor shutdown.
It lasts for the current activation only; restart/reactivation starts the normal
routine. Clearing the area does not succeed/finish this task. After hauling, the
NPC returns to the position where the current cleanup batch was discovered once, then waits with reason
`cleanup_waiting_corpses`. Empty and deferred-only searches poll at most every
two seconds so newly fallen bodies can be collected without another order.
During those waits the routine keeps its existing ownership and roaming timer.
Bounded discovery runs through the passive `HumanModule.monitor` hook before
arbitration: empty, partial, unavailable and deferred-only scans never select
`assigned-task`, cancel the walk, or restart its pause. Only a concrete body
approach/haul, a due retry, or a return after actual work competes for movement.
The intent never requests an initial return merely because an empty area was
checked while the actor was roaming away from its source. Idle scans do not
repeatedly recall the actor to the source. A selected body and the work's return
point remain stable through hauling and defense preemption; moving the actor
does not abandon that body. New discovery follows the actor again after the
work is settled. Return-route failure retains intent and retries.

`AssignedTaskModule` delegates this objective to one `CorpseCleanupExecution`.
`HumanCorpsePort` is the injected capability; `ServerHumanCorpses` owns bounded
native discovery (25 squares, 128 static objects per call), transient body tokens,
cross-NPC claims and native corpse relocation. Discovery resumes through deep
piles instead of rescanning the first 128 objects forever. Removed/reanimated
bodies invalidate their tokens and are never reconstructed. Animal/fake-dead
bodies are excluded. Inventory custody remains native-owned; a logical body token
survives conversion to a grapple-only zombie and back to a replacement corpse.

The native implementation now uses phased pickup, attached dragging and laydown.
`ServerHumanCorpseDrag` owns conversion and release behind `HumanCorpsePort`;
`ServerCorpseDragAuthority` retains owner-null replication separately from combat.
The executor waits for pickup/drop statuses and immediately releases on defense
or cancellation. Legacy/custom ports retain relocation defaults. Paired client
animation and streaming still require dedicated-server validation. See
[NPC-CORPSE-GRAPPLE.md](NPC-CORPSE-GRAPPLE.md) for ownership, native behavior,
phase timing, packet replay, physical limits and verification.
Climb/fence/window traversal remains unsupported while dragging.
Each route attempt has a 120-second deadline. Cleanup is
retained as intent across route rejection/failure and temporary unloading, rather
than failing the assignment on the first obstruction.

Pickup tries body-relative head-end, feet-end, side and center tiles derived
from the native corpse angle, subject to the same scope and clear cardinal
body-movement edge. These are tile-level grab approaches, not new grapple
animations. A failed approach or delivery route releases the claim, waits one
second and replans; there are five attempts per body. After five failures the
native adapter defers that exact corpse for five real-time minutes, then discovery selects
another available body. A dropped body already outside the original source
radius remains tracked for later delivery. Removed/reanimated bodies are pruned
without recreation. Transient retry memory is bounded to 128 bodies per actor;
exhausting that bound waits with `cleanup_waiting_deferral_capacity` and releases
the claim rather than allocating without limit or terminating the order.

If only deferred bodies remain, the assignment stays active and yields the
runtime to routine modules while polling for new work and the earliest due retry.
The search follows the roaming NPC; the dialogue-authored destination stays
fixed. Deferred in-flight bodies remain tracked outside the current radius,
and moving the search center does not reset their cooldown or forget them. Return routes also retry: five short attempts, then a five-minute backoff,
without abandoning the intent. Temporary scope/loading loss waits one second.
An empty search emits `cleanup_waiting_corpses`; the order does not complete.
A new order or `RESUME_ROUTINE` can replace/cancel this intent at any time.

The executor owns attempts and phase timing; `HumanCorpsePort` supplies approach
points, deferred discovery and retry diagnostics. `ServerHumanCorpses` keys its
cooldowns by native object identity, radius and destination;
it never puts native objects in brain observations. `AssignedTaskModule` owns the
assignment and passively samples discovery. Eligibility remains pure and only
becomes true for ready physical work. The runtime polls intents without
selecting them; higher-priority defense still wins arbitration. DEBUG
`corpse retry` reports body token, attempt count, failure reason and monotonic
retry deadline; task reasons expose approaching, hauling, retrying, returning and
waiting phases. No new client packet, SQL migration or dialogue metadata field
is needed for this policy update.

Defense/dialogue/trade suspension stops the route and releases its body claim,
leaving the corpse at its last reached tile. Resumption revalidates/reclaims that
body, including when it is already outside the original search radius. A changed
or missing body is abandoned. Replacement/resume-routine/shutdown release the
claim without deleting or duplicating the body. No transient body references,
claims or cleanup progress are persisted across activation/restart.

The optional `corpse-cleanup` profile movement slot constrains cleanup routes;
profiles without it use `background`. Other task/patrol requests still use their
normal scope. `MovementRequest.To` names a validated profile slot and retains its
one-argument background constructor. Unknown explicit slots fail closed.
V27 publishes a new Leon profile revision with a separate cleanup area including
the configured drop-off; the indoor patrol scope remains unchanged. It clones
the published militia orders into Leon's own orders graph and adds a PTBR/EN
cleanup option. Existing custom dialogue bindings are retained. The coordinates
270/14437/0 exist only in this seeded dialogue metadata, not the execution code;
an editor can publish any permitted same-floor destination. Published revisions
stay immutable and active actors need reactivation/restart to bind a new scope.

Commands replace the current assignment after dialogue suspension has cancelled
its current action. Survival/combat interruption cancels the route and retains
intent; subsequent selection replans from the current position. Task status is
`ASSIGNED`, `RUNNING`, `SUSPENDED`, then `SUCCEEDED`, `FAILED` or `CANCELLED`.
`RESUME_ROUTINE` and actor shutdown cancel. Ordinary move/follow routes terminate
on failure; corpse cleanup uses the bounded retry policy above. Debug logs report acceptance and
execution transitions, including `route_rejected`, `route_failed`,
`outside_movement_scope` and `target_unavailable`.

Movement uses existing scoped `MovementRequest.To` and obstacle traversal. It
does not expand indoor/area limits or add floor transitions. A follow target that
disconnects, dies or is replaced cannot silently rebind by name or recycled ID.
Leaving all permitted nearby stopping positions fails the assignment. This is a
local follow command, not a complete escort quest with progression/persistence.

## Persistence and rollout

V26 persists effect definitions/parameters alongside immutable dialogue revisions.
It publishes new militia/Leon profile heads above existing drafts, preserving
module settings, movement areas, cheats, trade/survival data and all old history.
The guide gains no task capability. Existing custom dialogue bindings are retained.
New militia bindings use an orders submenu, then hold/follow/resume choices.
Selecting the context-menu entry only opens that submenu after arrival.

Assignments, native target identities and timers are deliberately activation-local:
restart/reactivation resets the task and resumes the authored routine. A future
persistent quest/objective needs its own logical identity, recovery policy and
worker/journal boundary. Do not persist native player IDs or monotonic timers.

Deploy V26 and this server patch together; reactivate actors or restart to bind
the new profile revision. Runtime performs no JDBC calls in dialogue or task ticks.

## Verification

With `-Dapocbr.npc.logLevel=DEBUG`, native discovery emits `corpse search` on
selection and completed scans: source, radius, destination, selected body token
and tile, object/corpse counts, invalid/scope/drop-off/other-claim skip counts,
and loading status. It does not log each discovery tick. A completed search means
only that the bounded scan reached the end, not that the order has finished or
that any corpse was hauled. `cleanup_waiting_corpses` retains the order. `movement failed ... body_occupied` describes
moving-object collision separately. See [occupancy diagnostics](NPC-MOVEMENT.md#moving-object-occupancy).

- `python tools/test_human_tasks.py`: production parameter validation, task
  execution, preemption, following, failure and authenticated dialogue integration.
- `python tools/test_human_corpses.py`: cleanup parameters, relocation, fixed NPC
  discovery, interruption/resumption, route failure, contention, bounded scans and
  native inventory/world identity preservation.
- `python tools/test_human_dialogue.py`: existing conversation authority and wire views.
- `python tools/test_human_profiles.py` and `python tools/test_human_walking.py`:
  profile/controller and scoped movement regressions.
- `APOCBR_TEST_POSTGRES_BIN=Z:/postgresql/bin python tools/test_npc_persistence.py --manager-test`:
  disposable-cluster migration/JDBC/grants and manager aggregate publication.
- `powershell -File 42.21.0/patchApocalipseBr.ps1 -DryRun`: target-JAR compilation.

Manually validate two clients giving orders, follow movement near doors/obstacles,
target disconnect/death, task replacement, defense preemption and actor reactivation.

Cleanup verification: `test_human_corpses.py` passed 72 assertions; tasks (29),
dialogue (66 plus PTBR/EN runs), walking (32), profiles (56), brain (72), survival
(101), server humans (92) and engagement (52) passed. Disposable PostgreSQL/JDBC
checks passed 69 assertions as the engine role; the manager suite passed 41 tests
including destination editing, source validation and immutable publication. Five
editor tests passed. The target 42.21.0 game-JAR dry compilation passed (261
sources, 804 classes). No live migration or game deployment was performed.
Multiplayer corpse replication, inventory access while hauling, doorway behavior
and visual presentation still need dedicated-server validation.

Intent retry regression coverage includes alternate endpoints after a blocked
approach, no replanning during backoff, five-failure deferral, processing another
body during cooldown, automatic expiry/retry, blocked dump and return routes,
scheduler handoff to routine and back, native rotated head/feet endpoints,
in-flight deferred bodies outside the source radius and claim release. The
current intent update passed corpse (72), tasks (29), dialogue (66 plus language),
survival (101) and roaming (571) checks and the target-JAR dry compilation above.
Five-minute delays use monotonic real time, not accelerated world time.

Continuous-order regression coverage checks new corpses after an empty scan,
explicit resume cancellation, no repeated idle return routes, and discovery
while another body is deferred. Native corpse discovery remains a separate
bounded `HumanCorpsePort` capability rather than mixing corpses into the living
player/zombie contact memory. By default it samples around the current NPC tile;
selected and deferred bodies retain their handles during delivery even outside
that moving area.

Current activation-only continuous cleanup verification: corpse cleanup (81),
perception (166), task lifecycle (29), dialogue (66 plus PTBR/EN), survival (101),
traversal (93), server-human lifecycle (92), engagement (52) and roaming (571)
assertions passed. Target-JAR dry compilation passed with 262 sources and 805
class files. No live deployment was performed; dedicated-server validation is
still needed for the actual corpse replication and presentation.

Passive-intent regression reproduces a roaming pause longer than the two-second
scan interval using the production `WalkingModule` and `HumanModuleRuntime`.
Repeated empty and partial scans preserve walking selection and actual movement;
finding a body switches to cleanup, defense preempts/releases it, and ending
defense resumes cleanup. Resume-routine cancels future monitoring too.

Mobile-discovery regressions cover movement away from the dialogue position,
new bodies near the roaming actor, return to the latest work origin, and native
deferred-body recovery across changed search centers at the original deadline.
An authored fixed source remains supported as an explicit override.
