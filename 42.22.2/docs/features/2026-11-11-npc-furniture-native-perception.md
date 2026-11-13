# NPC native furniture perception

Target: **42.22.2 dedicated server**. Implemented in source 2026-11-11 on top of
the [furniture foundation](2026-11-11-npc-furniture-foundation.md), following the
[overall implementation plan](../reports/2026-11-10-npc-furniture-rest-implementation-plan.md).
Production fixtures and exact-JAR compilation pass; live game validation and
deployment remain pending. This step implements native sensing and composition,
not rest policy, reachable seating geometry or sit/stand presentation. The later
[seating execution slice](2026-11-11-npc-seating-execution.md) adds geometry and
mechanics in source, with live stock-client acceptance still pending.

## Confirmed behavior and scope

The user selected **current-room-only** discovery. A demanded sensor captures
furniture on the NPC's current floor and in the same native room identity as its
current square. Missing/outdoor origin rooms are unavailable. An explicit
background walking area further restricts discovery. A new room or actor tile
invalidates cached observations immediately, before the routine cadence expires.

The user also selected **continue other behavior on sensing failure**. An
unexpected per-observer capture error clears its furniture frame, disables its
furniture sensing until reactivation and logs once. It does not stop autonomous
activity, dialogue or trade. The failed observer leaves the capture scheduler;
other observers and unrelated behavior continue normally.

The earlier choices remain requirements for the next step: Front/Left/Right
approaches, try another seat then return to routine, and opportunistic rest during
patrol pauses or at low endurance. This step publishes native entry-side facts
without claiming that an approach is reachable or that an interaction succeeded.

## Ownership and composition

| Component | Responsibility |
| --- | --- |
| [ServerHumanFurniture](../../src/zombie/apocalipse/humans/ServerHumanFurniture.java) | Factory-owned actor registration, per-consumer observers, lifecycle cleanup and capture failure isolation. No rest/module-ID/actor-name policy. |
| [ServerHumanFurnitureWorld](../../src/zombie/apocalipse/humans/ServerHumanFurnitureWorld.java) | Shared native identity/occupancy adapter with current-room views. Binds native objects to detached tokens and revalidates bindings on server-owned resolution. |
| [ServerHumanSeatingCatalog](../../src/zombie/apocalipse/humans/ServerHumanSeatingCatalog.java) | Bounded metadata cache over native `SeatingManager` data. Identifies real seat data, orientation, offsets and allowed sides without using rendering/model state. |
| [FurnitureCaptureScheduler](../../src/zombie/apocalipse/humans/furniture/FurnitureCaptureScheduler.java) | Bounded shared capture budget and fair observer progression. It contains no native queries or behavioral policy. |
| [ServerHuman](../../src/zombie/apocalipse/humans/ServerHuman.java) | Attaches fresh sensing state after activation/restoration and removes it in activity-cleanup `finally`, including cleanup failures. |
| [ServerHumanFactory](../../src/zombie/apocalipse/humans/ServerHumanFactory.java) | Lazily constructs the service on its initialized server owner; captures after admitted mutations/groups and before autonomous observation/action updates. |
| [ServerHumanPerception](../../src/zombie/apocalipse/humans/ServerHumanPerception.java) | Adds the captured immutable furniture frame to the aggregate `HumanObservation`. Living-contact sensing remains separate. |

`HumanBehavior.furnitureDemand()` now owns the generic default contract; modules
inherit it and `HumanModuleRuntime` still aggregates their demands. There is no
special-case rest ID in perception/composition. A future allowlisted rest module
declares its need and receives native observations through the existing aggregate
observation path. Each incarnation gets a new observer/scanner.

Profiles with no demand allocate no per-actor scanner and trigger no furniture
world/catalog/occupancy capture. The service still registers their presentation
actors so a later consumer can account for a detached seated NPC that is absent
from native square membership. No new sensing-only or unfinished `rest` module
has been registered in production profiles. Existing profiles therefore retain
empty furniture frames until a consuming capability is added.

All mutable services and native queries stay on the server owner, with thread
checks. Factory construction before owner initialization does not construct these
native services. The worker receives only the immutable furniture frame carried
inside a detached observation; no world view, catalog, actor, binding registry or
capture callback becomes a worker port. There is no new async job, RPC, thread,
blocking future, database query or persistence schema.

## Native seat facts and geometry boundary

Classification requires native seating positions. Sprite-name conventions and
ordinary furniture properties alone do not make an object sittable. The catalog
publishes the native primary facing and its position, conservatively retaining
the existing one-candidate-per-object foundation contract.

Native [SeatingFile.Tileset.merge](../../decompiled/zombie/seating/SeatingFile.java)
retains the **first nonempty tile** in source order.
[SeatingManager](../../decompiled/zombie/seating/SeatingManager.java) supplies the
merged count/facing and original per-source position data. The adapter follows
that source order, checks count/facing consistency and maps stored translation
`(x,y,z)` to seating world offset `(x,z,y)`. Any present `BlockLeft`/`BlockRight`
property excludes that side, matching the native adjacent-position test.

`FurnitureAffordances.Seat` carries copied finite offsets, height and an immutable
allowed-side set. Candidate `approaches` may now be empty: this explicitly means
that seat data is known but reachable geometry has not been resolved. The current
native adapter emits empty approaches. `FurnitureSelector` consequently cannot
select those candidates for movement until a geometry provider enriches them.
The earlier candidate constructor remains source-compatible.

The catalog caches at most 512 sprite/name/tileset/index revisions and supports
at most eight native positions per tile and 512 metadata sources. Unsupported,
missing or inconsistent data is unavailable, not a guessed seat. Metadata is a
world-session snapshot; live seating-editor changes with unchanged sprite keys
require cache reset/reactivation of the shared capture world or server restart.
World replacement and last-consumer cleanup clear the cache. The catalog does not
call the native character/model-dependent approach helper or initialize rendering.

## Occupancy and bounded work

One shared occupancy capture samples at most 128 native players and 64 registered
NPC presentations per authoritative capture pass. Objects referenced by a live
seated/entering character are conservatively occupied for the whole object. This
also covers detached NPCs that vanilla square-list occupancy would miss. If the
roster exceeds those limits, unobserved occupancy is treated conservatively as
occupied, never advertised as a free seat. Sensing is still not a reservation;
the executor must recheck current occupancy immediately before a sit commit.

The scanner retains its 64-square/128-object per-observer limits, and all
observers share 256 square visits/512 object inspections per pass. The scheduler
resumes after the last observer that actually consumed budget, rather than after
an observer that merely returned its cached frame. With 64 continuously demanding
observers each consuming 64 squares, every observer gets work within 16 passes
(1.6 seconds at the existing 10 Hz authority cadence). This is a work/fairness
bound under that fixture, not a measured gameplay performance result.

Each enabled observer also performs one origin-square lookup per pass to detect
room changes. These at most 64 origin lookups are additional to the scan-square
budget. Object inspection uses its already-captured square/list and does not
hide another square-object scan inside the budgeted call. The once-per-pass
bounded occupancy capture and cached metadata queries are separate from object
inspection counts.

Native square object lists are ordinary `ArrayList`s in this target. The adapter
reports `UNTRACKED_REVISION` rather than inventing a version or scanning a whole
list for a hash. A dense unversioned list resumed across passes makes the sweep
partial even after the cursor completes. Mutation-tracked lists, where supplied,
use their real version and restart a resumed square when that version changes.
Fresh frame publication cannot refresh older candidate timestamps.

## Native identity and lifecycle extension

The existing patched [IsoObject](../../src/zombie/iso/IsoObject.java) gains a
generic transient `long` lifetime revision and an additive read-only getter.
`setSquare` increments it when the square identity changes; `reset`, `addToWorld`,
`removeFromWorld` and `removeFromSquare` advance it at their lifecycle boundaries.
It is not reset during pooling, serialized or sent in packets. This costs one
runtime long per object and constant work at those boundaries. No NPC service,
logical key or rest policy is referenced by the native class.

The exact vanilla baseline is
[42.22.2 IsoObject](../../decompiled/zombie/iso/IsoObject.java). This change extends
the existing replacement instead of copying over prior ID/decode patches. Apart
from revision increments, existing lifecycle bodies retain their behavior. Native
ID formulas/cache and save/network formats remain unchanged.

A private stamp combines square/list identity, current list index, sprite/name/
tileset revision, copied seat data and that lifetime revision. Stamp equality
uses list identity rather than `List.equals`, avoiding a hidden object-list scan.
Resolution rechecks the current loaded square, list membership at the captured
index and native revision before returning the private object. Removal/re-add,
pooled reuse, shifted membership, sprite changes and world/square replacement
invalidate stale tokens. Invalid requested slots cannot erase a valid binding.

Raw remove/re-add of the exact same object at the exact same index, bypassing all
native lifecycle hooks and preserving every field, cannot be detected on an
untracked list. The adapter makes no completeness guarantee for a resumed native
list. Use the native lifecycle APIs for world mutations; normal lifecycle paths
are qualified by the new revision. Final execution must still validate actor
incarnation, room/area, freshness, geometry and occupancy at its effect boundary.

## Diagnostics and validation

`ServerHumanFactory.getInstance().furniturePerception(key)` returns the latest
detached frame on the server owner, without scanning. It returns `null` for an
unknown/inactive actor and `Frame.EMPTY` for an actor without an active furniture
sensor. This exposes no native reference or interaction authority.

| Command | Result |
| --- | --- |
| `py tools/test_human_furniture.py` | 5265 production foundation/module-demand/traversal assertions pass. |
| `py tools/test_human_furniture_native.py` | 106 production catalog/native adapter/composition/scheduler assertions pass. |
| `py tools/test_iso_object_port.py --version 42.22.2` | 31 target object ID/cache/decode assertions pass. The runner now accepts an exact target and includes its actual new lifetime field when extracting `setSquare`. Its original 42.22.0 target also passes. |
| `py tools/test_npc_async_authority.py --version 42.22.2` | Production authority/planning fixture passes in default async, explicit sync and explicit async modes: 213/203/213 checks. The runner now supports exact target selection and includes the new immutable observation DTO dependencies. |
| `powershell -NoProfile -ExecutionPolicy Bypass -File 42.22.2/patchApocalipseBr.ps1 -BuildOnly` | Target-JAR compilation passes: 351 sources / 1019 classes. Only deprecation/unchecked notices. No deployment. Final artifact directory is recorded with the final verification below. |

Final verification on 2026-11-11: all four target fixture commands above passed;
the final BuildOnly exited with code zero and retained 1019 classes under
`42.22.2/artifacts/build_20261111_005329/` (ignored), with compiler logs beside
the classes. All 80 local Markdown link targets and version-index coverage
checks passed. No deployment or live database change was performed.

The native fixture uses small explicit doubles for world/square membership,
metadata values and actor/profile composition. The adapter, catalog, scheduler,
scanner, bindings and immutable DTOs are production code. The native lifetime
field/getter, `setSquare` and `removeFromSquare` are extracted from production;
reset/world-entry/world-exit hook placement is source-checked. Factory call
ordering, aggregate-observation wiring and cleanup wiring are source-checked and
linked by the real-JAR build. Full factory/game startup is not simulated by the
native sensing fixture.

Remaining runtime checks: native seating metadata availability after actual
server startup, current-room changes through doors/stairs, chunk unload/reload,
player sit/stand observation, furniture pickup/replacement/reuse, representative
mod seating overrides and scan fairness/work under a furnished lobby load. No
NPC moves or furniture interactions are performed by this sensing step. The later
[execution guide](2026-11-11-npc-seating-execution.md) records the implemented
mechanical boundary. Next implement registered rest policy and profile/patrol
composition from the overall plan.
