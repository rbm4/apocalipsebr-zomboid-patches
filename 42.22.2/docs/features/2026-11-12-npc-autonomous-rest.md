# NPC autonomous seated routine

Target: **42.22.2 dedicated server**. Implemented and verified in source on
2026-11-12. Exact-JAR compilation and production-bytecode/isolated PostgreSQL
fixtures pass. Deployment and live stock-client animation acceptance are pending.

This optional `rest` module composes the existing [seating mechanics](2026-11-11-npc-seating-execution.md)
and [current-room furniture perception](2026-11-11-npc-furniture-native-perception.md).
It implements the long seated routine from the [furniture plan](../reports/2026-11-10-npc-furniture-rest-implementation-plan.md).
Shared patrol opportunities, low-endurance-specific hysteresis and other furniture
activities remain separate work. Only profiles explicitly binding `rest` scan for
its seats. There is no actor-name/key policy in the engine.

## Policy and settings

`rest/RestSettings` validates immutable numeric profile parameters. All durations
are real milliseconds, independent of the game-time multiplier.

| Parameter | Default | Bounds |
| --- | --- | --- |
| `minSeatMs` | 600000 (10 minutes) | 1000..86400000 |
| `maxSeatMs` | 1200000 (20 minutes) | minSeatMs..86400000 |
| `walkMs` | 30000 | 1000..86400000 |
| `retryMs` | 30000 | 1000..60000 |
| `radius` | 8 tiles | 1..12 |
| `clearTiles` | 8 tiles | 1..24, no greater than profile perception radius |

Each fresh `rest/RestModule` owns its random generator, execution handle, bounded
32-token refusal set and monotonic retry/cooldown timers. It requests SIT sensing
once per second, selects the nearest fresh unoccupied approach with the shared
`FurnitureSelector`, then starts the shared native seating capability. Native
execution revalidates reachability, occupancy, room/area and reservations. Dwell
starts after sitting settles. Duration is drawn inclusively between the configured
bounds. Normal completion yields to walking for `walkMs`; it does not force a
particular route or guarantee completion of a walk within that time.

Missing/stale/occupied furniture and unsafe/unavailable awareness leave walking
eligible. Selection requires the existing healing safety predicate: fresh bounded
zombie awareness and no recent zombie within `clearTiles`, with a living, mobile,
non-downed actor. Selected rest cancels if that predicate fails. The native service
independently handles damage, invalid furniture, downing/death and lease loss.

Rejected approach/geometry/occupancy requests and failed executions refuse the
candidate and try another after one second. Refusals expire after `retryMs` from
the last failure; when all available candidates are refused, routine walking runs
until a fresh retry. Work and memory remain bounded. A disabled native scanner
retains the existing continue-other-behavior failure policy.

## Arbitration, interactions and cancellation

`ServerHumanProfiles` registers the allowlisted factory. Validation requires a
background walking scope, puts rest after defense/healing/tasks/encounters/greeting
and before ordinary walking, and rejects shared `group-patrol` composition until
its explicit rest-opportunity contract exists. Sargento G uses greet=100,
rest=500 and walk=1000; the always-eligible walker cannot starve rest.

`HumanSeating.Execution.seated()` exposes a server-owner-qualified stable posture
without exposing native objects. Rest interruption/suspension keeps a stable owned
seat when the last autonomous safety observation was safe; approach/transient
phases are cancelled. The native seating service continues ticking while greeting,
dialogue or trade owns activity, so leases, dwell, exit and cleanup remain active.
Resumption reuses that actor's handle and processes its terminal result if it
finished during the interaction. The dwell clock continues during conversations;
it is not extended by an interaction. A seated actor can therefore stand when its
ordinary dwell expires during a session.

`ServerHumanActuator` preserves chair alignment during facing, and treats greeting
return-to-post as already satisfied while a furniture pose/recovery owns position.
`GuideBrain` speaks then waits instead of attempting a full-body wave in that pose;
standing greetings retain their existing wave/speech behavior. External activity
blocking explicitly cancels seating even if suspension retained it. Stop/removal
cancels the handle; native mechanics owns safe get-up and exactly-once cleanup.
Incompatible motion still requests native get-up before moving. Rest is not an
additional combat authority, and suspended policy does not sample threats during
an exclusive conversation; existing native damage/lifecycle handling remains active.

## Ownership and persistence

All module execution, random state, live seating handles, furniture resolution,
Lua and transport run on the existing authoritative server thread. The async
worker acquires no new native port. `HumanObservation.position` is a detached
position used for pure ranking; existing constructor signatures remain available
with a null position, which disables new autonomous seat selection for legacy
callers. Passive module monitoring only updates detached safety/refusal state and
never actuates. No new thread, queue, database table or packet format is introduced.

Only profile intent/settings are durable. Seat tokens, RNG progress, handles,
refusals, monotonic timers and leases are transient. Existing safe outside-seat
checkpoints and fresh-incarnation reactivation rules remain in force.

## Sargento G activation

The manager migration
[V53__Sargento_G_Seated_Routine.sql](../../../../apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V53__Sargento_G_Seated_Routine.sql)
registers the module/parameters and clones the current published
`sargento-g-lobby` profile into a new revision above any existing drafts. It adds
rest=500 with the defaults above, preserving source modules, movement, flags,
survival/encounter and trade policy. It does not alter spawn/world checkpoints,
dialogue or other profiles. Existing immutable revisions remain untouched. Grants
retain engine-role reads of configuration; no new objects require sequence grants.

Deploy the compatible engine, apply manager game-runtime Flyway through its normal
workflow, then restart/reactivate the actor through the NPC lifecycle to bind the
new profile. Existing processes without `rest` must not load this revision.
The actor needs supported reachable furniture in its **current native room** and
floor. Outdoor/tent tiles without a native room remain unavailable by the earlier
current-room discovery decision; the migration does not manufacture room metadata.
No deployment, live database change or server restart was performed for this work.

## Verification (2026-11-12)

- `patchApocalipseBr.ps1 -BuildOnly`: 373 sources / 1064 classes against the installed
  target JAR; retained under `42.22.2/artifacts/build_20261112_015322/` (ignored).
- `py tools/test_human_rest_target.py --classes 42.22.2/artifacts/build_20261112_015322/classes`:
  29 assertions against actual target-built rest, registration, scheduler,
  controller and greeting code. Mechanical execution is a fake port.
- `py tools/test_human_brain_target.py --classes 42.22.2/artifacts/build_20261112_015322/classes`:
  72 brain and 31 sequence assertions.
- `py tools/test_human_seating.py`: 86 production geometry/execution/action/asset assertions.
- `py tools/test_human_seating_native.py`: 33 native adapter/presentation/recovery assertions;
  verifies the new stable-seat handoff query against the production native adapter.
- Furniture foundation/native fixtures: 5265 / 106 assertions.
- Async authority fixture `--version 42.22.2`: 213 / 203 / 213 assertions in
  default, false and true startup modes.
- Manager Maven `NpcSargentoGRestMigrationTest,NpcSargentoGMigrationTest`: six tests,
  no failures/errors/skips, disposable localhost PostgreSQL 17.6 databases with
  distinct migration and engine roles. Upgrade, Flyway repeat/validation,
  engine-role settings, old revision immutability and preserved content/spawns pass.
  Maven used `-o -Dmaven.main.skip=true -Dmaven.resources.skip=true`; unchanged main
  classes were reused and test sources compiled. SQL is read directly from source.

These checks do not prove stock-client packet application or animation playback.
Live acceptance must check the lobby's room metadata and chairs, entry/exit alignment,
10?20 minute dwell and short-walk cycle, seated greetings/dialogue/trade, danger or
damage, seat contention/removal, blocked exits, late viewers and restart/reactivation.
