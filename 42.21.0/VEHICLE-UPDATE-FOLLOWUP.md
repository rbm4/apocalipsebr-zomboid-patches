# Vehicle update follow-up, 42.21.0

Reviewed 2026-10-05 against exact-version BaseVehicle and VehicleParts. Vehicle
update chains consume 23,967.211 ms / 530,968 attempts in sequences 327–336:
8.366 ms/tick, 0.04514 ms/attempt. These are inclusive elapsed chains, not CPU.
New BaseVehicle.update stage timers are defined in TELEMETRY.md. This batch adds
measurement only to vehicle logic; algorithm changes below await stage evidence.

## Candidate: selective idle device/light parts

BaseVehicle.drainBatteryUpdateHack scans every part when the engine is stopped
to select turned-on radio/device parts or active lights. Preserve order, maintain
a sparse selected view and update it on light/device activation, inventory-item
replacement, part/script rebuild, load and network receipt. Lightbar battery work
still runs after that traversal, including any duplicate battery update the old
path performs. Do not cache only engine-running state: active device membership
can change while the engine remains stopped.

The new parts timer includes the full-update branch too. Add total/selected part
counts if this timer dominates before implementing the selective view. Actual
membership maintenance must cost less than the small ordinary vehicle part list.

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
