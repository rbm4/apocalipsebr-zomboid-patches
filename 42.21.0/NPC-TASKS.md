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
| `CLEAR_CORPSES` | Required destination `x`, `y`, `z`; optional `radiusTiles` 0-2 (default 2); optional complete `sourceX`, `sourceY`, `sourceZ` | Capture the NPC's tile when the dialogue selection is accepted unless a fixed source is authored. Gather human/zombie corpses in the fixed square radius, one at a time, and move them along scoped routes to the authored destination. |

Unknown kinds/parameters, partial coordinates and invalid values reject the
content. SQL publication applies equivalent validation, including mandatory
conversation closure. Parameters currently represent integers; the keyed string
storage leaves future handlers room for validated objective/zone identifiers.
Supporting a new kind requires a code implementation, validation, matching SQL
allowlist, editor enum and regression coverage. A database row alone cannot add
an engine capability. Quest rewards are not implemented here.

## Corpse cleanup (V27)

Destination coordinates are dialogue metadata, not engine constants. Example:

```json
{"kind":"CLEAR_CORPSES","parameters":{"x":"270","y":"14437","z":"0","radiusTiles":"2"}}
```

Author these as a `dialogue_choice_behavior` row and keyed
`dialogue_behavior_parameter` rows for the same choice. Radius 2 means a 5x5
square centered on the captured NPC tile, on the same floor. Moving the NPC or
participant after acceptance does not move the search center. Optional fixed
source coordinates must be provided together. Source/destination must be inside
the profile's loaded cleanup scope. The destination tile is excluded from the
search, even if it overlaps the source area.
After the area is clear, the NPC returns to the captured source through the
cleanup scope before completing, so its ordinary patrol can resume inside its
original movement area. Return route failure is reported explicitly.

`AssignedTaskModule` delegates this objective to one `CorpseCleanupExecution`.
`HumanCorpsePort` is the injected capability; `ServerHumanCorpses` owns bounded
native discovery (25 squares, 128 static objects per call), transient body tokens,
cross-NPC claims and native corpse relocation. Discovery resumes through deep
piles instead of rescanning the first 128 objects forever. Removed/reanimated
bodies invalidate their tokens and are never reconstructed. Animal/fake-dead
bodies are excluded. Corpses and their inventories remain world-owned; movement
uses the same corpse object and native remove/add replication at reached tiles.

This first implementation is tile-by-tile hauling alongside the walking actor,
not the player's full native grapple/drag animation. Vanilla `pickUpCorpse`
reanimates an `IsoDeadBody` as a grapple-only zombie and depends on the native
animation/grapple lifecycle, which these detached server actors do not run.
No body item conversion, teleport to the destination or client-side timer is used.
Climb/fence/window traversal while hauling fails safely instead of pulling a body
through the obstacle. Route rejection, failure, unloading and the 120-second
per-route deadline release ownership and terminate with an explicit task reason.

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
`RESUME_ROUTINE` and actor shutdown cancel. Failed routes terminate rather than
retrying each tick; a new order may retry. Debug logs report acceptance and
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

- `python tools/test_human_tasks.py`: production parameter validation, task
  execution, preemption, following, failure and authenticated dialogue integration.
- `python tools/test_human_corpses.py`: cleanup parameters, relocation, fixed NPC
  source, interruption/resumption, route failure, contention, bounded scans and
  native inventory/world identity preservation.
- `python tools/test_human_dialogue.py`: existing conversation authority and wire views.
- `python tools/test_human_profiles.py` and `python tools/test_human_walking.py`:
  profile/controller and scoped movement regressions.
- `APOCBR_TEST_POSTGRES_BIN=Z:/postgresql/bin python tools/test_npc_persistence.py --manager-test`:
  disposable-cluster migration/JDBC/grants and manager aggregate publication.
- `powershell -File 42.21.0/patchApocalipseBr.ps1 -DryRun`: target-JAR compilation.

Manually validate two clients giving orders, follow movement near doors/obstacles,
target disconnect/death, task replacement, defense preemption and actor reactivation.

Cleanup verification: `test_human_corpses.py` passed 34 assertions; tasks (29),
dialogue (66 plus PTBR/EN runs), walking (32), profiles (56), brain (72), survival
(101), server humans (92) and engagement (52) passed. Disposable PostgreSQL/JDBC
checks passed 69 assertions as the engine role; the manager suite passed 41 tests
including destination editing, source validation and immutable publication. Five
editor tests passed. The target 42.21.0 game-JAR dry compilation passed (261
sources, 803 classes). No live migration or game deployment was performed.
Multiplayer corpse replication, inventory access while hauling, doorway behavior
and visual presentation still need dedicated-server validation.
