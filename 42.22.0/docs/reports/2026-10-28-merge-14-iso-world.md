# Review 14/48: IsoWorld

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. The source matches
the reviewed candidate; vanilla baseline commit is `2264d72`. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and applied merge

- [Old vanilla](../../../42.21.0/decompiled/zombie/iso/IsoWorld.java).
- [Old patch](../../../42.21.0/src/zombie/iso/IsoWorld.java).
- [Target vanilla](../../decompiled/zombie/iso/IsoWorld.java).
- [Candidate diff](2026-10-28-merge-14-iso-world.diff).

Keep the old simulation telemetry scopes and the minimal Java decompiler repairs.
Use target startup control flow throughout, including the newly nested client
initialization block. Do not restore the old initialization method wholesale.
The two textual conflicts overlap repairs to client X/Y coordinates, not a
changed simulation optimization.

## What our patch does

Telemetry measures cell simulation, contact resolution, inline animation work,
waiting for asynchronous animation, designation zones, vehicle networking,
physics, hutches, climate, buildings, databases and virtual animals. It preserves
the existing asynchronous animation branch and profiler scopes. These additions
attribute time; they do not introduce scheduling, throttling or a faster world
simulation algorithm. Primary owner: OPT-WORLD, with OBS-TELEMETRY support.

The old source also repairs invalid Java emitted by the decompiler: boolean
literals represented as casts from 0/1 and integer player coordinates incorrectly
assigned to boolean variables. Keep false/true literals and distinct integer
playerX/playerY locals before the client floor-search loop. These are source
compilation repairs, not a claim that the shipped game executes invalid Java.

## Upstream retained

Preserve world version 251 and its new version constants; new tile-definition
paths/constants; GameMode/ResourceLocation setters and return type; ArrayList
zombie model collections with List sorting instead of PZArrayList/TimSort; the
new startup client guard and initialization nesting; and other target tutorial,
asset-loading and rendering changes. No PZArrayList API is reintroduced.

## Source verification and limits

All five telemetry-bearing methods have identical bodies in old and new vanilla:
updateWorld, FinishAnimation, update, updateInternal and updateThread. Their old
patched bodies can therefore be retained exactly. Source assertions verify the
candidate contains every old telemetry phase in the same order. Replacing those
five methods with their target vanilla bodies leaves exactly target vanilla plus
the enumerated decompiler repairs, proving no other target startup/engine changes
were overwritten. No runtime speedup is inferred from this comparison.

No new Java gameplay fixture was run for this telemetry/source-repair proposal.
Exact-JAR compilation and native startup/animation/telemetry verification remain
pending. Supporting telemetry is staged; related IsoCell and scheduler ports
remain separate reviews. The broader square-position cache is unaffected.

User approved: "yes". Applied candidate equality and the described source
assertions passed after application. No installed files were changed.
