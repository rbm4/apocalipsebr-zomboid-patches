# Build 42.22.0 documentation

42.22.0 is the current server development target (pre-release). The vanilla
baseline is `../decompiled/zombie/`; replacement sources belong in `../src/zombie/`.
The source tree contains 259 Java files: 50 replacements whose vanilla
baselines are unchanged and 200 ApocalipseBR support classes, copied byte for byte
from 42.21.0, plus nine individually approved merges through IsoObject.
Review 10 retired LoadGridsquarePerformanceWorkaround to vanilla with no source
override. Another 38 changed reviews and 3 removed targets await decisions. Reviews 01-04
and 06-09 passed target fixtures; SpriteConfig's diagnostic-only changes passed source
verification. Exact-target JAR compilation and runtime verification remain pending.
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
| 2026-10-27 | [Review 11/48: LootRespawn (vanilla-only proposal awaiting discussion)](reports/2026-10-27-merge-11-loot-respawn.md) |
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
Next: [LootRespawn no-override proposal](reports/2026-10-27-merge-11-loot-respawn.json).

## Issues

Migration blockers are tracked in the report until implementation begins.
