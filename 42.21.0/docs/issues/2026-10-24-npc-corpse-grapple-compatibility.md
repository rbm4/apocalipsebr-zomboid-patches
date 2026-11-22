# NPC corpse grapple compatibility assessment

Target: Project Zomboid 42.21.0, server-owned detached `IsoPlayer` NPCs.
Status: historical pre-implementation gaps addressed by the implementation below;
live multiplayer compatibility verification remains open.
The server implementation is now described in [native NPC corpse dragging](../features/2026-10-24-npc-corpse-grapple.md).
The tables below describe gaps before that implementation; multiplayer proof
remains outstanding.

Native dragging is a viable integration candidate, but the current hauling
adapter cannot simply call `pickUpCorpse` and continue its existing execution.
The principal changes are body identity/lifecycle, server authority and relay,
remote-player state presentation, and movement clearance. A server-only solution
using existing packet formats is the first approach to validate; it is not yet
proven to work with unmodified clients for every animation/streaming phase.

## Verified native flow

1. Vanilla `media/lua/shared/TimedActions/ISGrabCorpseAction.lua:complete` writes
   `lastPlayerGrabbed` and calls `pickUpCorpse(body, "BwdDrag")`. The Lua action
   supplies player UI, turning, reachability, sound and timed-action preparation;
   it is not an appropriate complete executor for a detached server NPC.
2. [IsoGameCharacter.pickUpCorpse](../../decompiled/zombie/characters/IsoGameCharacter.java)
   calls `IsoDeadBody.Grappled`. That converts the body through `reanimate()`,
   marks the new zombie grapple-only, and starts the bidirectional grapple.
3. [IsoDeadBody.reanimate](../../decompiled/zombie/iso/objects/IsoDeadBody.java)
   transfers the inventory container, worn and attached items, visual data and
   mod data; allocates a zombie online ID; registers the zombie; sets the network
   `reanimatedBodyId`; and removes the original corpse. This is a native object
   replacement, not continued movement of the original `IsoDeadBody`.
4. [BaseGrappleable](../../decompiled/zombie/core/skinnedmodel/BaseGrappleable.java)
   binds both participants. `LetGoOfGrappled` clears the relationship and invokes
   the target's release callback. On a dedicated server,
   [IsoZombie.onZombieGrappleEnded](../../decompiled/zombie/characters/IsoZombie.java)
   calls `die()` for grapple-only zombies, producing a new corpse.
5. [PlayerDraggingCorpse](../../decompiled/zombie/ai/states/PlayerDraggingCorpse.java)
   owns pickup/drag/laydown player state parameters and animation events. Its
   remote `setParams` branch restores the target and flags from received state
   parameters; an absent target aborts/reset the grapple. Its `exit` can release
   the body. Multiple player action-group nodes map to this same Java state.
6. [AnimLayer](../../decompiled/zombie/core/skinnedmodel/advancedanimation/AnimLayer.java)
   synchronizes paired animation tracks and body offsets. Native assets progress
   from generic `BwdDrag` into `BwdDragHead`/`BwdDragLegs` variants. Position
   updates alone do not establish this paired animation state.
7. [ZombiePacket.set](../../decompiled/zombie/network/packets/character/ZombiePacket.java)
   carries `grappledBy` and `sharedGrappleType` when the grappler is an `IsoPlayer`.
   [NetworkZombieSimulator](../../decompiled/zombie/popman/NetworkZombieSimulator.java)
   can bind a newly created remote zombie to that player. The player also needs
   compatible state presentation; this zombie packet is not the entire protocol.

## Current compatibility gaps and required changes

| System | Current behavior / conflict | Required integration |
| --- | --- | --- |
| Cleanup execution | `CorpseCleanupExecution` grabs by moving a world corpse to an approach tile, then moves it after route steps. It assumes a corpse token resolves throughout. | Explicit pickup, dragging and dropping phases with pending/success/failure results. Keep passive searches, mobile discovery, retries and defense priorities. Never begin walking through a pending pickup/drop animation. |
| Native body capability | `ServerHumanCorpses.resolve` requires the original corpse's static-list membership and `ObjectIDManager` identity. Conversion invalidates both immediately. Claims and deferrals are keyed by that corpse object. | An activation-local logical body handle whose binding can transition corpse -> grapple-only zombie -> replacement corpse. Reserve it throughout conversion/drop; rebind claims, deferrals and diagnostics to the exact resulting object. |
| Drop identity | Current `release` only removes a claim; there is no conversion or completion result. | Release the native relationship and observe the resulting corpse. `IsoGameCharacter.addOnDiedListener(listener, true)` receives the exact corpse created by `becomeCorpse`; register it before release and validate the session/generation in the callback. Do not guess by selecting a nearby corpse. |
| NPC execution lifecycle | Factory deliberately does not run the normal player's full update/animation/input loop. | A bounded per-NPC drag session owns logical phases and authoritative body placement. Do not enable the complete local-player simulation or fabricate a player connection to obtain animation events. |
| Player transport | `ServerHumanTransport` publishes position/prediction, gait, aim, emotes and traversal, but no drag state. | Explicit native `StatePacket` construction for `PlayerDraggingCorpse`, including target, shared type, continue flags, bearing and non-throw defaults. Replay current dragging state when a viewer first sees/resees the NPC. |
| Zombie authority | `NetworkZombieManager` prefers a grappling player's connection. Our NPC has no connection; the manager can fall through to another viewer. Combat control intentionally rejects grapple-only zombies. | A separate corpse-drag authority lease, checked before ordinary owner selection and transfer. Do not turn the body into a combat pursuit session or relax combat eligibility. Server ownership must survive spectators joining/leaving. |
| Zombie relay | Packer relay/request paths generally require a client owner or `ServerZombieController.controls`. Owner-null drag bodies satisfy neither combat condition. | Extend relay eligibility, bounded relay scheduling and packet preparation for the drag authority lease. Preserve the packet's native corpse-conversion and grapple fields. Null ownership by itself is insufficient. |
| Receive authority | Zombie receive handling rejects packets from a connection that is not the current owner. | Keep drag bodies owner-null under the lease, preserving receive rejection. Audit all owner-transfer and hit/release paths so a viewer cannot become simulator or overwrite server drag motion. |
| Clearance | `BodyOccupancy` ignores corpses/giblets/dead actors, but an animated grapple-only zombie has positive health. Combat attack-lane checks also treat live characters as blockers. | Ignore the actor's own session-bound dragged body for its route/prediction/body clearance. Define conservative physical treatment for other actors; do not make every living zombie nonblocking. Audit line-of-fire separately from target eligibility. |
| Perception/combat | Perception and the combat zombie adapter already exclude grapple-only zombies. | Preserve these exclusions through conversion and pooling, and verify helper bodies cannot trigger NPC defense or be acquired for pursuit. |
| Pace and presentation | NPC route integration and player prediction use our movement resolver; native drag state sets `WalkSpeedGrapple` (0.8 normal, 1.2 skeleton). | Add an explicit dragging movement condition and calibrate actual speed to native presentation. Limit pace, stop for pickup/drop, suppress conflicting aim/emote/traversal, and keep prediction consistent. Native animation values are not automatically tiles-per-second multipliers for our engine. |

## Protocol and animation risks to prove first

**Do not use the ordinary `StatePacket.setData` as the shortcut.** It invokes
the state's `setParams` on the sender. For a remote NPC that can restore stale or
missing target parameters, mutating or aborting the server relationship while
building a packet. Follow the explicit packet-builder pattern already used by
`ServerHumanAimPacket` and `traversal/TraversalStatePacket`. Native locality is
determined through `NetworkPlayerComponent`/`isLocalPlayer`, not merely the
public `remote` field; do not change locality to bypass this behavior.

**Creation order matters.** State parameters serialize the zombie reference by
ID; `ZombieID.parse` resolves it from the client's existing zombie map. If the
drag state arrives before the helper zombie exists, its target can be null and
the remote player state aborts. Zombie creation and player state messages can
use different network delivery paths. Sending them consecutively does not prove
creation-before-reference. Prototype late-viewer replay, bounded state resends,
and behavior under reordered/delayed creation before promising client-free
compatibility. If vanilla cannot recover reliably, evaluate a minimal client
integration rather than pretending a server send order is an acknowledgement.

**Animation completion cannot be the authority.** There is no local NPC player
driving pickup/variant/laydown events on the server. Determine which native
animation conditions remote clients advance and which phase/type changes the
server must publish. Keep authoritative bounded phase deadlines and emergency
release independent of any one viewer's animation or feedback. Reuse the native
head/legs and front/back assets; inspect their events/offsets before fixing
timings. No full server animation update loop is proposed by default.

**Server placement and client offsets must agree.** Native `AnimLayer` can move
the visible body's position to the paired offset. Server motion must track a
compatible collision-checked continuous offset, preserve floor/square membership,
and replicate stable positions. Otherwise clients can snap the body between
server corrections and animation offsets. The old remove/add-corpse movement
loop must not run in parallel with native dragging.

**Conversion has observable effects.** Native reanimation touches registration,
descriptor, player-corpse links, statistics and Lua/container events. Native
release uses the zombie death/corpse path. Audit local death/reward/telemetry
consumers and inventory access; do not count helper conversions as ordinary NPC
combat kills or assume every mod-data field round-trips without verification.

## Proposed ownership and execution contract

Keep command metadata and the existing cleanup intent. Add a dedicated
server-native drag adapter/session, composed per NPC, behind an extended
`HumanCorpsePort` capability. The pure executor requests begin/poll/drop and
consumes immutable statuses; it never sees `IsoZombie`, network packets or live
animation objects. The adapter owns native identity conversion and authoritative
placement; transport owns packet construction and per-viewer presentation; a
shared authority boundary exposes only whether a zombie is controlled for
combat or corpse dragging. Keep the two session types distinct.

Suggested phases: reserved -> picking-up -> dragging -> putting-down -> released,
with an immediate abort/release path from every live phase. Maintain a fresh
session generation and native instance/membership checks. Preserve the source
body until conversion succeeds; after successful conversion, cleanup must never
recreate that removed original body. Transfer custody through the native
inventory/container lifecycle and verify the replacement corpse on release.

An ordinary destination drop may finish its laydown presentation. Defense, hit
reaction, death, despawn, dialogue/trade suspension and shutdown require an
immediate authoritative release; never delay defense waiting for a viewer's
animation. Set the session's releasing state before invoking native callbacks to
handle synchronous reentry and repeated cancellation. Release the authority lease
only once native conversion/cleanup is settled. Body tokens and monotonic phase
timers remain activation-local; no schema or dialogue graph change is required.

Preserve current route safety initially: same floor, no fence/window/stair
dragging. Walking through a door still needs body-offset clearance; actor-only
clearance does not prove a dragged body's swept path is safe. Stop and release
before a route edge requiring unsupported traversal. A retry must recover the
replacement corpse handle, not the invalid original reference.

## Implementation and verification sequence

1. **Protocol proof first:** one NPC, one human/zombie corpse, one native pickup,
   stationary attachment, short straight movement, release. Publish the native
   helper zombie and explicit player drag state with server authority. Validate
   on two unmodified clients, including a client arriving after pickup. Check
   body inventory/visual preservation. This decides whether a client patch is
   actually necessary before changing the general task contract.
2. **Session and port contract:** introduce logical body handles, conversion/drop
   callback mapping, explicit statuses, authority lease and bounded deadlines.
   Exercise production session code with injected native/network ports. Cover
   rejected pickup, missing zombie ID, conversion failure, duplicate callbacks,
   inventory custody, idempotent abort and pool/identity reuse.
3. **Movement and intent integration:** use the native session instead of tile
   relocation, exclude only the relevant dragged body from self-clearance,
   derive dragging pace/prediction, and release before combat/dialogue/trade or
   unsupported traversal. Preserve passive mobile search and five-minute deferral
   against the replacement corpse handle.
4. **Transport/relevance and streaming:** cover creation-before-reference failure,
   delayed/reordered packets, repeated phase messages, relevance exit/reentry,
   reconnect, multiple viewers and owner reassignment attempts. Verify both the
   pickup and drop remove/replace exactly one world body for every viewer.
5. **Rollout gates:** production regressions for cleanup, movement, traversal,
   combat/survival, packet ownership, zombie authority/relay and NPC lifecycle;
   target 42.21.0 game-JAR dry compilation; then dedicated-server runtime tests
   for four head/legs/front/back variants, skeletons, doors/turns, interrupted
   pickup/drop, death/despawn, no viewers and chunk unload/reload.

This assessment was originally documentation-only. The subsequent implementation
and production regression coverage are documented separately above. It is not a
claim of completed two-client visual/streaming validation; the protocol proof
scenarios remain rollout gates.
