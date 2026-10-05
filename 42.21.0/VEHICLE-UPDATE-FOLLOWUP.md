# Vehicle update follow-up, 42.21.0

Reviewed 2026-10-05 against exact-version BaseVehicle and VehicleParts. Vehicle
update chains consume 23,967.211 ms / 530,968 attempts in sequences 327–336:
8.366 ms/tick, 0.04514 ms/attempt. These are inclusive elapsed chains, not CPU.
BaseVehicle.update stage timers are defined in TELEMETRY.md. The synchronous
parked-car implementation below follows the initial measurement batch. Runtime
savings still require representative captures from the deployed revision.

## Implemented: parts-only cars may use SIXTEENTH

The server scheduler no longer treats needPartsUpdate alone as a FULL reason.
All remaining original activity guards and occupied seats still require FULL.
An idle, empty, stationary car continues to call updateParts (not the idle device
branch) when needPartsUpdate is true, at SIXTEENTH frequency. The original
VehicleParts minute boundary, lastUpdated and elapsed-minute Lua arguments remain
unchanged. This is an intentional cadence change: callbacks and signal-device
updates on these cars can run later, especially at low server tick rates.
Vanilla engine cooling integrates elapsed minutes and clears needPartsUpdate when
cold. Custom callbacks/device behavior must be checked in multiplayer.

## Implemented: persistent idle device/light capability view

The server idle branch visits only parts that have device data or a light, in
original numeric part order. A car with no such parts has an O(1) empty traversal
after its first build. Ordinary cars with inactive lights/radios still perform
O(K) live activation checks, where K is capability-bearing entries rather than
all P parts. This is not a cache of active state.

VehicleParts.add/clear and VehiclePart device replacement/creation, spotlight
creation and loaded-light creation invalidate the view. Callback changes rebuild
and resume at the next vanilla numeric index, including nested traversals.
Duplicate parts and the final lightbar battery update retain original ordering.
Protected field writes introduced by external Java mods must invalidate the view;
normal Lua APIs and native load paths are covered. Full part updates are unchanged.

## Implemented: bounded bookkeeping improvements

- Physics-state reconciliation reuses a square already queried in the same call
  when it matches the final z. No square survives into a future tick, so streaming
  and floor mutations remain visible. This generally removes one of three queries,
  not the entire reconciliation block.
- Server updateTransform lazily builds a reusable first-identity part/model map
  at the first attached-model parent lookup in a call, replacing repeated list
  searches in its model loop. Cars without those lookups do not build it. Original
  matrix calculations, model order, duplicate-first semantics and wheel inputs
  remain unchanged. It applies only when a model slot exists; no inactive transform
  cache is claimed. The map is cleared after use.
- updateSounds returns after gameplay world-sound work when the server has no
  emitter. Existing sound objects are updated first; emitter-present paths remain
  unchanged. This avoids an irrelevant local-listener lookup.
- Work counters accumulate in the existing vehicle telemetry batch; scheduler
  classifications and actual attempts are attributed separately by level.

Regression fixtures execute production selectors/hooks/part deadlines, compare
vanilla idle traversal across activation and callback mutations, and verify square
results and first-model lookup. The full game-JAR dry run also compiles both added
replacement classes. No asynchronous worker or live deployment was introduced.

## Candidate: selective idle device/light parts

Capability selection is implemented above. A further active-only view would need
complete hooks for DeviceData and VehicleLight state changes, including direct
native writes and network receipt. Keep lightbar battery work after the traversal,
including any duplicate battery update the old path performs.

The parts timer includes the full-update branch too. Use the new capability visit
and rebuild counters before extending to active membership; maintenance must cost
less than the small ordinary vehicle part list.

## Candidate: separate irrelevant parts from full part updates

VehicleParts.update traverses every part; updatePart first runs signal-device work
and headlight reconciliation, then gates its Lua update callback using game minutes.
Parts without any relevant work could be excluded with an ordered persistent view,
but signal work must not be moved behind the minute gate. Preserve lastUpdated,
GameTime.checkHours, callback mutation behavior, lights/battery dependencies and
the existing owner/part script interfaces. Device/light predicates alone do not
identify all callback-bearing parts.

## Candidate: dedicated-server visual bookkeeping

BaseVehicle.update runs alpha/visibility and world-light bookkeeping. Investigate
the consumers and ServerGUI branch before skipping it. updateSounds also emits
gameplay world sounds; the entire method is not cosmetic. Keep part callbacks,
passenger positions, crop intersections and authority decisions. A broad idle-car
skip is not a valid algorithmic replacement.

## Remaining: cross-tick physics square and crop reconciliation

An unchanged JNI transform does not imply unchanged floor/square availability.
Cross-tick caches require streaming, floor and footprint-content invalidations.
Crop checks must see plants added beneath a stationary car. The current patch
only reuses same-call square queries and retains crop scans. Postupdate animation
and its model lookups are also still separate from the updateTransform improvement.

## Already indexed or already guarded

- IsoCell.vehicles is a HashSet subclass; its membership checks are O(1) expected.
- VehicleParts.getPartById uses partsById, and engine/battery references are cached.
- Physics already has rest/activation conditions. Fixed Bullet steps are measured
  separately; changing their integration delta would alter collisions.
- VehicleSounds creation excludes multiplayer server/client contexts. This does
  not imply that all BaseVehicle sound work is absent or safe to remove.

## Reading the next capture

Compare eight stage totals with vehicle attempts and current chain totals. The
physicsState child is transform/controller/engine work, not Bullet stepping.
bookkeeping combines sounds, visibility, world lights and passenger transforms;
split that scope further only if it is substantial. Carried animals are measured
under animals and can invoke their own nested perception/update telemetry.
Batch maxima cannot establish which vehicle stalled or whether it shared a tick
with loading/unloading. A tick-correlated trace/JVM profile remains the way to
distinguish active work from waits, contention and allocation stalls.

No expected millisecond savings, higher-load capacity or live speedup is claimed.
