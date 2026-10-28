# Review 21/48: IsoDoor

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. Review 20 IsoGenerator is
retired to native vanilla. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/iso/objects/IsoDoor.java).
- [Old patch](../../../42.21.0/src/zombie/iso/objects/IsoDoor.java).
- [Target vanilla](../../decompiled/zombie/iso/objects/IsoDoor.java).
- [Candidate diff](2026-10-28-merge-21-iso-door.diff).

Keep the existing three player-null guards in ToggleDoorActual. The three-way
merge is clean. The entire candidate equals target vanilla with exactly those
three conditions changed; exact vanilla baseline commit is `d334b7d`. Primary owner:
INTEGRATION; relevant consumers include non-player/NPC and zombie door actions.

## Failure trigger and behavior

ToggleDoorActual accepts IsoGameCharacter and derives a nullable IsoPlayer via
Type.tryCastTo. A non-animal character can pass the surrounding condition without
being a player. Native smart-zombie thumping can call ToggleDoor with an IsoZombie,
and custom NPC/native interaction paths must respect the same contract.

The three existing paths trigger local-player music after a door changes state.
Guarding player != null before player.isLocalPlayer prevents a null dereference
for non-player actors. Real local players retain music events; remote players
retain their existing behavior. Door changes, sounds, path invalidation, Lua
container events and rendering invalidation remain untouched. This is a targeted
correctness fix, not a performance optimization or a promise that every other
door action accepts every actor type.

## Upstream retained

Keep IForceableDoor and the native new forcing/chance/struggle methods, damage
and damageCheck(owner) flow, destruction sound distinctions, destruction behavior,
isNorth API rename, tutorial check and new constants/fields. Do not restore the
old class wholesale to obtain the null guards. Other forcing casts or nullable
branches are outside the three existing guard changes and remain native.

## Verification and remaining checks

Source checks passed: zero merge conflicts; candidate exactly equals target
vanilla plus three null guards; ToggleDoorActual equals the old patched method.
No Java gameplay fixture was added for these three short-circuit conditions.
Exact-JAR compilation and native single/double/garage-door interactions by local
players, remote players, NPCs and smart zombies remain runtime checks. No game
installation or database was changed.

User approved: "yes". Applied-candidate equality and source checks passed.
No installed files were changed. Approval applies to this class only.
