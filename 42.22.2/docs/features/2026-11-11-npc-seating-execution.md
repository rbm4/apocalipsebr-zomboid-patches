# NPC seating execution

Target: **42.22.2 dedicated server**. Source implementation and production-code
fixtures verified 2026-11-11. Exact installed target-JAR compilation passes;
deployment and live stock-client acceptance remain pending.

This stage adds reserve/approach/sit/dwell/stand mechanics to the
[furniture foundation](2026-11-11-npc-furniture-foundation.md) and
[native furniture perception](2026-11-11-npc-furniture-native-perception.md).
The [overall plan](../reports/2026-11-10-npc-furniture-rest-implementation-plan.md)
still owns the subsequent rest module, vendor station and patrol integration.
The [autonomous rest integration](2026-11-12-npc-autonomous-rest.md), added
2026-11-12, now registers an optional rest module and provides the manager
migration for Sargento G. Shared patrol rest remains subsequent work.

## Ownership and composition

| Component | Responsibility |
| --- | --- |
| `seating/HumanSeating`, `HumanAction.Sit` | Immutable request/token/side/duration and capability contract. No native references or policy. |
| `seating/SeatingGeometry` | Pure entry, anchor and exit calculations for native facing and Front/Left/Right. |
| `seating/SeatingExecution` | Fresh execution state, reservation renewal, approach deadline, transitions, dwell, cancellation and exactly-once cleanup. Injected mechanical port. |
| `ServerHumanSeatingGeometry` | Server-owned native room/area, object identity, collision, occupancy and passage validation. |
| `ServerHumanSeating` | Per-incarnation native mechanics, existing walking-port integration, pose ownership and recovery. |
| `ServerHumanSeatingAssets`, `seating/StockSeatingAssets` | Cold stock-animation compatibility verification. |
| `ServerHumanSeatingPacket`, `ServerHumanSeatingTransport` | Vanilla state/variable presentation and bounded viewer reconstruction. |
| `HumanActivityController` | Generic exclusive manual ACTION ownership; autonomous suspension, cancellation and resumption. |
| `ServerHumanFactory`, `ServerHuman` | Lifecycle, authoritative ticking, safe checkpoints and capability cleanup. |
| `ServerHumanSeatingCommands` | Trusted server-Lua manual entry point. No client command receiver. |

The capability is lazy. `HumanActuator.seating()` defaults to unsupported for
other adapters. Reusable actions contain no mutable execution state. All live
objects, walking, collision, Lua and packets remain on the server owner; no new
worker access, thread, database schema or persisted token/lease is introduced.
The shared furniture reservation service reuses the interaction-lease pattern
already used for traversal, without treating a seat as a crossing queue.

## Manual server API

The factory installs version 1 of `ApocBRSeating` into the server Lua environment
on its owner tick and reinstalls it after environment replacement:

```lua
-- Supply an active logical NPC key and the exact chair tile.
ApocBRSeating.sitAt("npc-key", x, y, z, "Front", 10000)
ApocBRSeating.status("npc-key")
ApocBRSeating.stand("npc-key")
```

Use dot calls. `sitAt` requires exactly six arguments, integer coordinates,
an exact `Front`, `Left` or `Right` side and integer duration in milliseconds
from 1 to 86,400,000. It returns command acceptance, not completed seating;
inspect `status` for phase/reason. `stand` requests cancellation and safe exit.
Unknown actors return `nil` status and false command acceptance. An actor whose
seating capability has never started reports `NOT_STARTED`.

The explicit request examines one requested square, at most 128 objects, within
12 tiles, the current room/floor and the actor's permitted walking area. It does
not enable a broad furniture scanner. A retained actor protects the shared native
binding registry until execution/recovery ends, even without a perception module.
A sensor disabled by failure remains unavailable to this capability.

Manual ACTION ownership suspends autonomous behavior. Dialogue/trade cannot
steal that activity, and those existing activities reject seating. Autonomous rest now retains stable seating through compatible dialogue/trade;
see the [rest integration](2026-11-12-npc-autonomous-rest.md). Requests also reject traversal, an existing seat/recovery, dead actors,
unsupported assets, invalid geometry and live occupancy.

## Geometry and execution

Front and both authored side approaches use the target native facing/offset data.
Pure geometry reproduces the target `SeatingManager` rotation and stock deferred
motion, including outside alignment for solid seats. Native validation checks
room/area, object incarnation, loaded membership, other solid objects, walls,
closed doors/windows and body clearance. Approaching uses existing navigation,
then validated subtile alignment. Occupancy is rechecked before committing.

The phase sequence is APPROACH, SIT_DOWN, SEATED, WAIT_EXIT, STAND_UP, FINISHED.
Approach has a 15-second deadline. Transitions use the verified stock one-second
motion plus blend allowance (1.25 seconds). Authoritative position follows the
bounded transition path; idle remains at the seat anchor. Normal physiology
continues unchanged; there is no additional rest recovery multiplier.

User decisions implemented here:

- Unsupported/modified animation assets make seating unavailable; no guessed
  vanilla offsets are applied to unknown motion.
- A blocked exit tries the original side and then other permitted sides. If all
  fail, it probes once per second and retains the reservation while waiting.
- New body obstruction during a transition stalls progress until clearance;
  the actor does not move through it.

Cancellation before posing releases immediately. Cancellation while posing asks
for safe stand-up and keeps ticking independently after the requesting action
ends. Motion/combat gates cancel seating and wait for mechanical ownership to
release; internal approach walking is explicitly permitted. Facing/full-body
emotes cannot overwrite the owned pose.

Damage, downing, death, invalid furniture/world identity or lease loss abort the
execution. Cleanup does not force get-up over a hit reaction. If an alive actor
is left inside solid furniture, a private recovery gate retains the source and
blocks new motion while retrying safe clearance after reaction/down/immobility
ownership releases. A chair now occupied by a player keeps its occupied flag.
Removal closes the capability and releases its resources. Alive checkpoints use
the safe outside approach rather than restoring inside the chair; death keeps
the actual death location. Seat tokens, executions and timers are not persisted.

## Asset compatibility and presentation

`tools/generate_npc_seating_assets.py` derives the generated fingerprint manifest
from installed stock assets and verifies 63 native motion/duration cases. The
current manifest covers 181 assets / 8,803,334 bytes, including inherited animation
nodes, clips, get-up, relevant transition and action-group configuration.
The cold compatibility check reads each asset once (2 MiB per-file and 16 MiB
total caps), rejects changed/missing files, added nodes in the seating/get-up
inventories and preferred FBX/GLB clip replacements. There is no per-frame file
I/O. Compatibility is cached for the process; live animation hot reload requires
a restart and is not supported by this mechanical slice.

Transport uses existing vanilla State and VariableSync contracts with native
furniture references and server-owned player IDs. Transition movement broadcasts
are suppressed while presentation owns motion. A viewer entering relevance
receives an entry reconstruction at a viewer-only position; this never changes
the authoritative actor. Stable seating is reconstructed after the blend window.
Receipts are bounded to 256 viewers, with at most three entry attempts spaced
2.5 seconds apart for object streaming. Healthy viewers are not repeatedly reset
to the entry pose. Normal completion clears get-up and variables without replaying
SitExit after standing, which would restore a stale furniture reference.

There is no native seating acknowledgement. Arbitrarily delayed object streaming,
actual packet serialization/application and animation/physics playback still
require dedicated-server stock-client testing; fixture doubles do not prove them.
This stage changes no vanilla packet format and requires no client patch.

## Verification

| Command | Result on 2026-11-11 |
| --- | --- |
| `py tools/test_human_seating.py` | 86 production geometry/execution/action/asset assertions. |
| `py tools/test_human_seating_native.py` | 33 production native seating/presentation/recovery assertions. |
| `py tools/generate_npc_seating_assets.py` | Generated manifest matches; 181 fingerprints and 63 motion checks. |
| `py tools/test_human_furniture.py` | 5265 foundation/module-demand/traversal assertions. |
| `py tools/test_human_furniture_native.py` | 106 catalog/native adapter/scheduler assertions. |
| `py tools/test_npc_async_authority.py --version 42.22.2` | 213/203/213 checks in default/false/true startup modes. |
| `py tools/test_iso_object_port.py --version 42.22.2` | 31 object cache/decode-boundary assertions. |
| `py tools/test_human_brain_target.py --classes 42.22.2/artifacts/build_20261111_022949/classes` | 72 brain and 31 sequence assertions against target-built production bytecode. |
| `powershell -NoProfile -ExecutionPolicy Bypass -File 42.22.2/patchApocalipseBr.ps1 -BuildOnly` | 362 sources / 1045 classes; exit zero. |

Build output and compiler logs are retained in ignored
`42.22.2/artifacts/build_20261111_022949/`. Only deprecation/unchecked notices.
No deployment or live database change was performed.

The pure harness uses production action/controller/executor/geometry code and an
oracle extracted from exact target native rotation math. Native fixtures compile
production mechanics, geometry, source adapter, transport, packet parameters and
Lua bridge against small engine doubles, with actual lifetime methods extracted
from patched `IsoObject`. They cover cancellation, lease loss, bounded retries,
alternate exits, real-player priority, reaction recovery and command validation.
They do not start the game, exercise the network serializer or load client models.

Before treating this as runtime-ready, test stock chairs/sofas in all orientations
and allowed sides; player/NPC contention; damage/death during each phase; blocked
approach/exit; pickup/destruction; shutdown/restart; multiple/late/re-relevant
viewers; and delayed object/chunk loading. Check alignment, flags, residual
furniture references and lease cleanup. The optional rest module/profile and stable seated interaction integration are
now implemented in the [rest guide](2026-11-12-npc-autonomous-rest.md). Patrol
pause/endurance-specific policy remains subsequent work.
