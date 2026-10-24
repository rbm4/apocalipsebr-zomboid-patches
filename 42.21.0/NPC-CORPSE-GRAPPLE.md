# Native NPC corpse dragging

Target: 42.21.0 dedicated server. Implemented behind the existing activation-local
cleanup intent; paired animation and streaming still require two-client runtime
validation. No client Java class, new packet type, SQL migration or dialogue
metadata change is introduced.

## Ownership

| Owner | Responsibility |
| --- | --- |
| `CorpseCleanupExecution` | Pure intent/execution phases, approach retries, selected logical body token, mobile search, delivery, return and cancellation. |
| `HumanCorpsePort` | Explicit `beginDrag`, `updateDrag`, `endDrag` statuses. Default implementations preserve relocation semantics for legacy/custom ports. |
| `ServerHumanCorpses` | Discovery, claims, logical-token rebinding, deferral and native session composition. The same token can resolve an original corpse, an attached body at the actor's tile, then its replacement corpse. |
| `ServerHumanCorpseDrag` | One native conversion, timed pickup/laydown, authoritative continuous placement, native death callback and immediate emergency release. |
| `ServerHumanDragPacket` / transport | Explicit native `PlayerDraggingCorpse` state parameters; replay to new/relevant viewers and bounded state refresh. No `StatePacket.setData` or fake local player. |
| `ServerCorpseDragAuthority` | Up to 128 identity-checked helper leases, separate from combat pursuit, owner-null relay admission and packet position correction. |
| `ServerHumanMovement` | Drag pace cap, backward-facing travel with independent prediction heading, unsupported-edge rejection and own-body clearance. |

All session state and native operations belong to the simulation thread.
The factory ticks the session after behavior movement, including while the NPC
stands still. Actor disposal explicitly closes corpse mechanics even if task
execution was driven outside the activity runtime. Claims, timers and helper
references are never persisted.

## Native lifecycle

Pickup requires an owned, valid corpse and a clear same/cardinal tile reach edge.
The adapter calls native `IsoDeadBody.reanimate()` and retains its returned zombie
before attempting the grapple. This follows the native corpse-grab conversion,
while allowing rollback if grapple acceptance fails. It transfers inventory and
visual custody through the native implementation rather than cloning items.
The helper is marked grapple-only, initialized for corpse presentation and bound
to the NPC through native `Grappled`. It is marked `onKillDone` because the body
has already died: release must not rerun kill rewards, loot generation or
`OnZombieDead` for this presentation helper. Native reanimation's own registration,
descriptor and statistics behavior remains in use.

The normal native pickup/drag/laydown assets and `PlayerDraggingCorpse` wire state
drive client presentation. Pickup holds movement for 1.5 real seconds; normal
drop holds it for 1.2 seconds. These are bounded server phase timings, not a
claim of measured animation completion on every client. Emergency cancellation
does not wait for laydown animation.

Head/legs selection follows the vanilla bearing threshold (absolute bearing at
least 90 degrees selects head). After pickup the server clears the helper's floor
flag, as required by the paired drag animation conditions. Placement uses native
drag asset offsets: 0.3 tiles for head/back, head/front and legs/back, 0.7 for
legs/front; head/front and legs/back reverse body yaw. Movement faces backward
while the prediction vector continues to point along the actual route. Dragging
caps physical pace at 80% of configured walking speed and cannot run/sprint.
Presentation timing, stride and transitions still need in-game calibration.

Normal completion requests laydown, then normalizes the body to the actor's drop
position and calls native `LetGoOfGrappled`. The helper becomes a corpse through
the native death path. A one-shot `addOnDiedListener` maps the exact replacement
corpse back to the same logical token and transfers the claim; there is no
nearest-corpse guess or reconstruction of the removed original body.

Defense, dialogue/trade suspension, task replacement, route failure and shutdown
release immediately. Repeated release is idempotent. Failed grapple acceptance
also releases the converted helper, preserving a recoverable corpse handle.
Native-instance/map changes fail closed: clear the NPC's stale relationship and
do not kill or take control of a replacement entity with recycled IDs.

## Networking and physical limits

Authority is retained before ordinary zombie ownership selection, including debug
owner rotation. Direct transfers to viewers are refused while the lease is live.
The existing receive-side owner check continues to reject spectator zombie
updates. Helpers are explicitly admitted to relay/request snapshots despite having
no client owner; relays run at most every 100ms. Packet preparation preserves
native corpse/grapple flags and replaces stale client-style prediction with the
authoritative server position.

Native state is replayed on relevance announcement and refreshed at most every
250ms during a live session. This permits recovery when the first state packet
references a helper not yet created on a client; it is not an acknowledgement
protocol or a proof against all packet-loss/reordering cases. Exit messages omit
the now-dead helper reference. A presentation-send failure cannot skip native
release. Two-client late-viewer/reconnect/reordering checks remain required.

Only the actor's actual paired grapple-only body is ignored for self-clearance;
unrelated living bodies remain blockers. The short body offset falls back to
the actor's position when its tile edge is not an ordinary clear walking edge.
Fences, windows and other non-walk/non-door traversal are refused while dragging.
Doors, tight turns, actual paired offset alignment and native chunk unload/reload
must be exercised on a dedicated server. This implementation does not pin chunks
or claim that an already-unloaded native body can be safely reconstructed.

## Verification

`tools/test_human_corpses.py` compiles the production native adapter/session,
authority and packet builder with explicit native engine doubles. It checks
conversion and inventory custody, stable logical handles, head bearing/floor
state, pickup/drop boundaries, bounded replay, late-viewer payload, backward
movement/prediction, rejection rollback, interruption, disposal and complete
executor delivery. These doubles do not prove rendered animation or native
inventory serialization.

`test_server_zombie_control.py` exercises the production drag lease/packet
correction and invalid-incarnation rejection. `test_zombie_auth_algorithms.py`
checks manager transfer/rotation guards with an injected drag-lease capability.
Packet preparation and ownership regressions remain applicable, along with
roaming, traversal, survival, dialogue, task and factory lifecycle checks.
Compile with `patchApocalipseBr.ps1 -DryRun` against the target game JAR before
deployment. No live deployment is performed by this change.

The earlier [compatibility assessment](docs/npc-corpse-grapple-compatibility.md)
records the pre-implementation investigation and remaining protocol proof goals.

Verified for this implementation: corpse cleanup/native adapter (125 assertions),
server zombie control/drag authority (131 plus disabled-controller case), zombie
authorization (3041), packet algorithms (45359), ownership guards (56), NPC
lifecycle (92), roaming (571), traversal (93), survival (101), engagement (52),
weapon combat (116), tasks (29), perception (166), dialogue (66 plus PTBR/EN).
The target game-JAR dry build passed with 265 sources and 810 class files.

With `-Dapocbr.npc.logLevel=DEBUG`, look for `corpse drag key=...` phases
`pickup`, `dragging`, `dropping`, `released`, including helper zombie/instance
IDs. Task reasons expose `cleanup_picking_up` and `cleanup_putting_down` while
physical movement is paused. These logs establish server lifecycle progress;
they do not establish that both clients rendered the paired animation correctly.
