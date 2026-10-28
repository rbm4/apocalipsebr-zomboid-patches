# Build 42.22.0 documentation

42.22.0 is the current server development target (pre-release). The vanilla
baseline is `../decompiled/zombie/`; replacement sources belong in `../src/zombie/`.
The source tree contains 281 Java files: 50 replacements whose vanilla
baselines are unchanged and 200 ApocalipseBR support classes, copied byte for byte
from 42.21.0, plus thirty-one individually approved merges, including PlayerDamagePacket.
Reviews 10-11 and 20 retired LoadGridsquarePerformanceWorkaround, LootRespawn and IsoGenerator to vanilla
with no source overrides. Another 14 changed reviews and 3 removed targets await decisions. Reviews 01-04
and 06-09, 12-13, 15-16, 18-19, 22-26, 28-32 and 34 passed target fixtures; SpriteConfig's diagnostic-only changes passed source
verification; IsoWorld, IsoAnimal, IsoDoor, ZombieCountOptimiser and WorldDictionary also passed source checks. Exact-target JAR compilation and runtime verification remain pending.
This directory is under migration and is not a deployable or validated release.

Start with the class-by-class migration report before carrying over any source.
The [42.21.0 documentation](../../42.21.0/docs/README.md) remains the historical
feature and configuration reference; those guides describe the previous build,
not implemented 42.22.0 behavior. Migrate a guide alongside its feature and update
this index when the target implementation has been verified.

## Features

| Date | Document |
| --- | --- |
| 2026-10-27 | [Subsystem ownership, staged sources and individual merge queue](features/2026-10-27-subsystem-port-tracker.md) |

No complete feature has been compiled and runtime-verified for this target yet.

## Reports

| Date | Document |
| --- | --- |
| 2026-10-28 | [Review 35/48: PlayerEffectsPacket (proposed native-field ownership guard)](reports/2026-10-28-merge-35-player-effects-packet.md) |
| 2026-10-28 | [Review 34/48: PlayerDamagePacket (applied; ownership fixture passed)](reports/2026-10-28-merge-34-player-damage-packet.md) |
| 2026-10-28 | [Review 33/48: WorldDictionary (applied; source checks passed)](reports/2026-10-28-merge-33-world-dictionary.md) |
| 2026-10-28 | [Review 32/48: VehiclesDB2 (applied; PostgreSQL gaps deferred/disabled)](reports/2026-10-28-merge-32-vehicles-db.md) |
| 2026-10-28 | [Review 31/48: VehicleParts (applied; extracted vehicle checks passed)](reports/2026-10-28-merge-31-vehicle-parts.md) |
| 2026-10-28 | [Review 30/48: BaseVehicle (applied; extracted vehicle checks passed)](reports/2026-10-28-merge-30-base-vehicle.md) |
| 2026-10-28 | [Review 29/48: ZombiePacket (applied; population fixture passed)](reports/2026-10-28-merge-29-zombie-packet.md) |
| 2026-10-28 | [Review 28/48: NetworkZombieAI (applied; cache/resolver fixture passed)](reports/2026-10-28-merge-28-network-zombie-ai.md) |
| 2026-10-28 | [Review 27/48: ZombieCountOptimiser (applied; source checks passed)](reports/2026-10-28-merge-27-zombie-count-optimiser.md) |
| 2026-10-28 | [Review 26/48: PathFindBehavior2 (applied; threshold fixture passed)](reports/2026-10-28-merge-26-path-find-behavior.md) |
| 2026-10-28 | [Review 25/48: IsoGameCharacter (applied; helper fixture passed)](reports/2026-10-28-merge-25-iso-game-character.md) |
| 2026-10-28 | [Review 24/48: IsoPlayer (applied; expiry/bookkeeping fixture passed)](reports/2026-10-28-merge-24-iso-player.md) |
| 2026-10-28 | [Review 23/48: IsoMannequin (applied; encoder fixture passed)](reports/2026-10-28-merge-23-iso-mannequin.md) |
| 2026-10-28 | [Review 22/48: IsoDeadBody (applied; wakeup fixture passed)](reports/2026-10-28-merge-22-iso-dead-body.md) |
| 2026-10-28 | [Review 21/48: IsoDoor (applied; source checks passed)](reports/2026-10-28-merge-21-iso-door.md) |
| 2026-10-28 | [Review 20/48: IsoGenerator (retired to vanilla; cleanup pending)](reports/2026-10-28-merge-20-iso-generator.md) |
| 2026-10-28 | [Review 19/48: IsoThumpable (applied; no-work fixture passed)](reports/2026-10-28-merge-19-iso-thumpable.md) |
| 2026-10-28 | [Review 18/48: DesignationZoneAnimal (applied; topology fixture passed)](reports/2026-10-28-merge-18-designation-zone-animal.md) |
| 2026-10-28 | [Review 17/48: IsoAnimal (applied; source checks passed)](reports/2026-10-28-merge-17-iso-animal.md) |
| 2026-10-28 | [Review 16/48: MovingObjectUpdateScheduler (applied; lifecycle fixture passed)](reports/2026-10-28-merge-16-moving-object-scheduler.md) |
| 2026-10-28 | [Review 15/48: IsoCell (applied; cadence fixture passed)](reports/2026-10-28-merge-15-iso-cell.md) |
| 2026-10-28 | [Review 14/48: IsoWorld (applied; source checks passed)](reports/2026-10-28-merge-14-iso-world.md) |
| 2026-10-28 | [Review 13/48: ServerMap (applied; queue fixture passed)](reports/2026-10-28-merge-13-server-map.md) |
| 2026-10-27 | [Review 12/48: IsoChunk (applied; unload fixture passed)](reports/2026-10-27-merge-12-iso-chunk.md) |
| 2026-10-27 | [Review 11/48: LootRespawn (retired to vanilla; deployment cleanup pending)](reports/2026-10-27-merge-11-loot-respawn.md) |
| 2026-10-27 | [Review 10/48: LoadGridsquarePerformanceWorkaround (retired to vanilla; deployment cleanup pending)](reports/2026-10-27-merge-10-load-gridsquare.md) |
| 2026-10-27 | [Merge review 09/48: IsoObject (applied; object fixture passed)](reports/2026-10-27-merge-09-iso-object.md) |
| 2026-10-27 | [Merge review 08/48: IsoGridSquare (applied; grid fixture passed)](reports/2026-10-27-merge-08-iso-grid-square.md) |
| 2026-10-27 | [Merge review 07/48: InventoryItem (applied; parser fixture passed)](reports/2026-10-27-merge-07-inventory-item.md) |
| 2026-10-27 | [Merge review 06/48: ItemContainer (applied; target fixture passed)](reports/2026-10-27-merge-06-item-container.md) |
| 2026-10-27 | [Merge review 05/48: SpriteConfig (applied; source verification passed)](reports/2026-10-27-merge-05-sprite-config.md) |
| 2026-10-27 | [Merge review 04/48: CraftLogicSystem (applied; target fixture passed)](reports/2026-10-27-merge-04-craft-logic-system.md) |
| 2026-10-27 | [Merge review 03/48: GameEntityManager (applied; target fixture passed)](reports/2026-10-27-merge-03-game-entity-manager.md) |
| 2026-10-27 | [Merge review 02/48: EngineEntityManager (applied; target fixture passed)](reports/2026-10-27-merge-02-engine-entity-manager.md) |
| 2026-10-27 | [Merge review 01/48: GameEntity (applied; target fixture passed)](reports/2026-10-27-merge-01-game-entity.md) |
| 2026-10-27 | [42.21.0 to 42.22.0: every replacement and support class](reports/2026-10-27-port-42-21-to-42-22.md) |

The report links the machine-readable inventory, upstream source changes and old
patch-intent diffs used as evidence.

Current artifacts: [copy receipt and 301-class subsystem ledger](reports/2026-10-27-carryover.json)
and [applied GameEntity diff](reports/2026-10-27-merge-01-game-entity.diff).
Also: [applied EngineEntityManager diff](reports/2026-10-27-merge-02-engine-entity-manager.diff)
and [applied GameEntityManager diff](reports/2026-10-27-merge-03-game-entity-manager.diff).
Also: [applied CraftLogicSystem diff](reports/2026-10-27-merge-04-craft-logic-system.diff).
Also: [applied SpriteConfig diff](reports/2026-10-27-merge-05-sprite-config.diff).
Also: [applied ItemContainer diff](reports/2026-10-27-merge-06-item-container.diff).
Also: [applied InventoryItem resolution](reports/2026-10-27-merge-07-inventory-item.diff).
Also: [applied IsoGridSquare resolution](reports/2026-10-27-merge-08-iso-grid-square.diff).
Also: [applied IsoObject diff](reports/2026-10-27-merge-09-iso-object.diff).
Retirement: [LoadGridsquare decision](reports/2026-10-27-merge-10-load-gridsquare.json)
and [class-family cleanup manifest](../tools/retired-class-families.json) (not yet consumed by scripts).
Applied: [IsoChunk diff](reports/2026-10-27-merge-12-iso-chunk.diff).
LootRespawn: [approved retirement](reports/2026-10-27-merge-11-loot-respawn.json).
Applied: [ServerMap review](reports/2026-10-28-merge-13-server-map.md) and [diff](reports/2026-10-28-merge-13-server-map.diff).
Applied: [IsoWorld review](reports/2026-10-28-merge-14-iso-world.md).
IsoGenerator: [approved retirement](reports/2026-10-28-merge-20-iso-generator.json).
VehiclesDB2 gap repairs are user-deferred while the vehicle PostgreSQL backend remains disabled by default; production settings were not changed.
Next: [PlayerEffectsPacket review](reports/2026-10-28-merge-35-player-effects-packet.md) and [candidate diff](reports/2026-10-28-merge-35-player-effects-packet.diff).

## Issues

Migration blockers are tracked in the report until implementation begins.
