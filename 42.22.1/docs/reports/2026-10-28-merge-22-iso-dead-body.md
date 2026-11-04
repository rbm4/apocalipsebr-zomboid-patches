# Review 22/48: IsoDeadBody

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. Review 21 IsoDoor is applied.
See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/iso/objects/IsoDeadBody.java).
- [Old patch](../../../42.21.0/src/zombie/iso/objects/IsoDeadBody.java).
- [Target vanilla](../../decompiled/zombie/iso/objects/IsoDeadBody.java).
- [Candidate diff](2026-10-28-merge-22-iso-dead-body.diff).

Keep the existing dictionary decode scope, corpse counter, wakeup eligibility
prefilter and default switch arm on target vanilla. The three-way merge is clean;
applied source equals the reviewed candidate after exact vanilla baseline `c64153c`. Primary owner: DB-DICT; shared owners: corpse
wakeup checks, telemetry and later PostgreSQL reconstruction.

## Existing changes and purpose

Wrap the complete corpse load(ByteBuffer,int,boolean), including super.load and
corpse-specific contents/visuals, in ApocBRWorldDictionaryDecode.enter(input).
This keeps source dictionary context active across the whole decode, including
nested calls. try-with-resources restores prior context on normal completion or
exception; support Scope.close can also report recorded dictionary failures.
It does not prepare database views, migrate payloads or prove reconstruction
works by itself. Those remain other integrations.

Count the current corpse snapshot for telemetry. For fake-dead server wakeup,
retain the per-corpse player-list traversal and order but reject absent squares,
null/dead/ghost/invisible/in-vehicle players and squared distances outside [4,16]
before calling ServerLOS.isCouldSee. Keep the final native eligibility predicate
unchanged. This reduces expensive LOS calls without introducing a shared cached
player list or changing the client traversal. Boundary/NaN behavior follows the
same comparisons as the final native predicate; arbitrary side effects or
concurrent player-state changes are not covered by that reasoning.

Keep the old switch default yielding 0 for unhandled reanimation sandbox values.
This is an existing source/default handling addition, not a simulation speedup;
known switch cases remain unchanged.

## Upstream retained

Keep MAX_BONES/BONE_ENTRY_SIZE and the native guard rejecting negative bone
counts, counts above 256, or buffers with fewer than 44 remaining bytes per bone.
Retain the new container load exception diagnostics, rendering cleanup and
updated debug-text offset. Do not restore the old unguarded ragdoll allocation.

## Verification and dependencies

The [candidate fixture](../../../tools/test_dead_body_port.py) reconstructs the
candidate without creating an override. It passed **12,500 assertions across
5,000 differential wakeup cases**, using extracted native/candidate traversal
methods and the exact native final predicate with player/square/LOS doubles.
Cases cover server/client traversal, eligibility flags, nulls, distance edges,
NaN/infinity, and fewer/equal LOS calls. A distant player performs no optimized
LOS query. Source checks retain the native final predicate, bone guard and load
scope. This does not execute full corpse load, dictionary resolution, ragdoll
allocation, telemetry or reanimation.

Copied dictionary helpers and applied IsoObject/InventoryItem hooks are staged
integration dependencies. PostgreSQL source provenance/routing/reconstruction,
full target-JAR compilation, native wakeup/LOS behavior and corruption handling
remain pending validation. No installation or database changes were made.

User approved: "yes". Applied-candidate equality and 12,500 wakeup/LOS assertions
passed again. Native load validation is retained; full decoding/native integration
remains pending. Approval applies to this class only.
