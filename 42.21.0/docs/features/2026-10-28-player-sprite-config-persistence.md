# Player inventory SpriteConfig persistence (42.21.0 server)

The relational player serializer now captures `SpriteConfig` components attached
to carried items, including `Moveable` items. Previously the capture produced
`UNSUPPORTED_COMPONENT_SpriteConfig`, rejected the full live graph with SQLState
22000, and quarantined the operation with a persistent player reload block.

The observed operation `79ecc031-2c3e-48a4-b5ae-367c14323a42` contained a
`Base.appliances_cooking_01_32` Moveable carrying SpriteConfig. This is a missing
codec, not evidence that the item itself is corrupt. See the
[quarantine inspector](../issues/2026-10-28-quarantine-item-inspection.md) and
[runtime isolation contract](2026-10-17-persistence-runtime.md).

## Native contract and implementation

Baseline: [42.21.0 SpriteConfig](../../decompiled/zombie/entity/components/spriteconfig/SpriteConfig.java)
`save`, `load`, `readFromScript`, `initObjectInfo`, and `isMultiSquareMaster`, plus
[ScriptsDictionary](../../decompiled/zombie/world/ScriptsDictionary.java).
No vanilla replacement class was added or changed for this addition.

[Codecs](../../src/zombie/savefile/ApocBRPlayerRelationalCodecs.java) explicitly
supports the component and stores `hasScript`; when present, it also stores
`scriptFullType`, `scriptVersion` (an exact long), and `wasLoadedAsMaster`.
The latter is sampled from native `isMultiSquareMaster()`, as native save does;
it is not copied from an old load flag. Script identity uses
`getScriptObjectFullType()`, the key of the native sprite script register, rather
than a short ID whose meaning depends on the dictionary instance.

The existing snapshot branch detaches this map on the owning capture thread.
Projection/JDBC workers consume scalar state only. No live script, owner, world
object, or sprite/cache object crosses that boundary. Capture uses the existing
`entity.SpriteConfig` structured component/value-node tables. The format addition
keeps codec version 4 and schema version 1 so existing complete heads remain
readable; no SQL migration or global codec-version upgrade is needed. Old binaries
cannot be assumed to reconstruct this newly supported component.

[Reconstruction](../../src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java)
resolves the full script name through `ScriptsDictionary.spriteConfigs`, invokes
native `readFromScript`, restores the saved master flag and rebuilds owner-derived
caches with `initObjectInfo`. Reconstruction runs under the existing game-thread
ownership contract. Missing or malformed script state fails closed. As in native
42.21.0 load, a changed script version does not itself reject restoration: the
current resolved script is used. An absent script explicitly clears any existing
configuration and master flag. No item deletion or login-guard bypass is added.

## Existing quarantine and deployment

This codec fixes new captures after deploying both changed class families and
restarting the server. Earlier detached snapshots omitted SpriteConfig-specific
state; they cannot be made complete by enabling this codec. The companion
[discard-and-reload policy](2026-10-28-player-save-discard-recovery.md) retires old
player rejection markers at startup without replaying these captures. The player
loads their last committed state, abandoning changes in rejected snapshots.

For the Linux production server, deploy using the version's normal build flow
with `/opt/pzserver` as the game installation directory. Do not copy 42.22.0
classes into the 42.21.0 server. Preserve the journal before operational recovery.

## Validation

- `python tools/test_postgres_player_relational.py`: 2,017 relational checks,
  two six-check size scenarios and 267 worker checks passed. New coverage verifies
  complete graph projection, stable script identity, exact long version, absent
  scripts and detachment from later live mutation. Existing unsupported-component
  rejection remains covered.
- `python tools/test_postgres_player_reconstruction.py`: 576 checks passed,
  including actual native SpriteConfig save-byte equivalence after codec restore,
  dictionary-name resolution, master flag, version mismatch, absent/missing scripts
  and missing-state rejection. These use the game JAR with fixture boundaries.
- `./42.21.0/patchApocalipseBr.ps1 -DryRun`: all 301 server sources compiled
  successfully against the installed game JAR with JDK 25.0.4; 891 class files
  enumerated for dry deployment. No installed game classes were changed.

Live multiplayer save/reconnect and moving furniture from inventory back into
the world still require deployment validation. Fixtures do not reproduce the full
world streaming/client synchronization lifecycle or recover the production archive.
