# Build 42.22.0 documentation

42.22.0 is the current server development target (pre-release). The vanilla
baseline is `../decompiled/zombie/`; replacement sources belong in `../src/zombie/`.
The source tree contains 303 Java files: 250 original carry-overs, 45 individually
approved replacement merges, and eight additional combat/trading helpers/replacements.
Fifteen copied support sources were adapted in reviews38/40/49/50/51/53/54/55, and one copied
identical-baseline replacement was adapted in review48. GameServer integration
and the coupled packer writer adaptation are applied. Reviews 10-11 and 20 retired three
changed replacements to vanilla, and reviews38/43 resolved removed WeaponHit/Trading through
native hit and packet-specific trading adapters. All 48 numbered decisions are
complete; one removed target design and copied-support repairs remain open.
Reviews 01-04, 06-09, 12-13, 15-16, 18-19, 22-26, 28-32, 34-38, 40-44, 46 and 48 passed target
fixtures with explicit doubles; other applied merges passed source checks.
The installed JAR is identified as42.22/world251; a303-source compilation failed
with four initial errors; applied52-54 resolve all four. The actual approved303
sources compile successfully (895 class files). Runtime verification remains
pending. The prior collision thread-local fix passed the target four-thread test.
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
| 2026-10-28 | [Cross-build character reconstruction: failure paths and recovery design](reports/2026-10-28-cross-build-character-reconstruction.md) |
| 2026-10-28 | [Follow-up review56: square mutation tracking/entity-ID cache (unapplied; review queue stopped)](reports/2026-10-28-merge-56-square-mutation-tracking.md) |
| 2026-10-28 | [Follow-up review55: event-control engine metadata (applied)](reports/2026-10-28-merge-55-event-control-metadata.md) |
| 2026-10-28 | [Pathfinding thread-local audit: prior collision fix preserved](reports/2026-10-28-pathfinding-thread-local-audit.md) |
| 2026-10-28 | [Follow-up review 54: zombie native path argument (applied; full build passes)](reports/2026-10-28-merge-54-zombie-path.md) |
| 2026-10-28 | [Follow-up review 53: NPC transport native player field (applied)](reports/2026-10-28-merge-53-npc-transport.md) |
| 2026-10-28 | [Follow-up review 52: IsoDoor decompiler compile repair (applied)](reports/2026-10-28-merge-52-iso-door-compile.md) |
| 2026-10-28 | [Full build checkpoint: identified target JAR; four compilation errors](reports/2026-10-28-build-check.md) |
| 2026-10-28 | [Follow-up review 51: dictionary build/version and namespace guard (applied)](reports/2026-10-28-merge-51-dictionary-build-version.md) |
| 2026-10-28 | [Follow-up review 50: relational dataset build metadata (applied)](reports/2026-10-28-merge-50-relational-dataset-build.md) |
| 2026-10-28 | [Follow-up review 49: reconstruction hand-field compatibility (applied)](reports/2026-10-28-merge-49-reconstruction-hand-fields.md) |
| 2026-10-28 | [Review 48/48: GameServer (applied; startup/login/NPC hooks and packer API)](reports/2026-10-28-merge-48-game-server.md) |
| 2026-10-28 | [Review 47/48: IngameState (applied; telemetry scopes)](reports/2026-10-28-merge-47-ingame-state.md) |
| 2026-10-28 | [Review 46/48: LuaManager (applied; cache/server API source repairs)](reports/2026-10-28-merge-46-lua-manager.md) |
| 2026-10-28 | [Review 45/48: AdvancedAnimator (applied; optional-folder guard)](reports/2026-10-28-merge-45-advanced-animator.md) |
| 2026-10-28 | [Review 44/48: ChatServer (applied; NPC speech/general bridge)](reports/2026-10-28-merge-44-chat-server.md) |
| 2026-10-28 | [Review 43/48: TradingUIUpdateStatePacket (applied; trade state)](reports/2026-10-28-merge-43-trading-update-state.md) |
| 2026-10-28 | [Review 42/48: TradingUIRemoveItemPacket (applied; offer withdrawal)](reports/2026-10-28-merge-42-trading-remove-item.md) |
| 2026-10-28 | [Review 41/48: TradingUIAddItemPacket (applied; NPC offer)](reports/2026-10-28-merge-41-trading-add-item.md) |
| 2026-10-28 | [Review 40/48: RequestTradingPacket (applied handshake)](reports/2026-10-28-merge-40-request-trading.md) |
| 2026-10-28 | [Review 39/48: PlayerHitSquarePacket (applied; miss diagnostic)](reports/2026-10-28-merge-39-player-hit-square.md) |
| 2026-10-28 | [Review 38/48: PlayerHitPlayerPacket (applied; reserved IDs and NPC combat)](reports/2026-10-28-merge-38-player-hit-player.md) |
| 2026-10-28 | [Review 37/48: SyncPlayerStatsPacket (guard applied; ownership retained)](reports/2026-10-28-merge-37-sync-player-stats.md) |
| 2026-10-28 | [Review 36/48: PlayerStatsPacket (applied; ownership fixture passed)](reports/2026-10-28-merge-36-player-stats-packet.md) |
| 2026-10-28 | [Review 35/48: PlayerEffectsPacket (applied; ownership fixture passed)](reports/2026-10-28-merge-35-player-effects-packet.md) |
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
Applied: [SyncPlayerStats ownership guard](reports/2026-10-28-merge-37-sync-player-stats.json).
Applied: [review38 NPC combat and reserved IDs](reports/2026-10-28-merge-38-player-hit-player.md); all 16 files match the approved diff and 1,725 assertions passed after application. IDs 768..1023, 192 real connection slots, 64 active NPCs and 256 fresh IDs per process are approved. Applied: [review39 miss diagnostic](reports/2026-10-28-merge-39-player-hit-square.md), baseline ae6e61a and source-equivalence verified. Applied: [review40 trade handshake](reports/2026-10-28-merge-40-request-trading.md), all four files match and 131 assertions passed again. Applied: [review41 add-item](reports/2026-10-28-merge-41-trading-add-item.md), baseline 300b619, candidate equality and 105 assertions. Applied: [review42 remove-item](reports/2026-10-28-merge-42-trading-remove-item.md), baseline a78d82e, candidate equality and 120 assertions. Applied: [review43 state/finalization](reports/2026-10-28-merge-43-trading-update-state.md), baseline d26bfff, candidate equality and 195 assertions. Applied: [review44 ChatServer](reports/2026-10-28-merge-44-chat-server.md), baseline 196d5da, candidate equality and 43 assertions. Applied: [review45 AdvancedAnimator](reports/2026-10-28-merge-45-advanced-animator.md), baseline 2b55f41 and source-equivalence checks. Applied: [review46 LuaManager](reports/2026-10-28-merge-46-lua-manager.md), baseline aca4626, candidate equality and 33 assertions. Applied: [review47 IngameState](reports/2026-10-28-merge-47-ingame-state.md), baseline d95d7a9 and scope/source equivalence. Applied: [review48 GameServer and packer API](reports/2026-10-28-merge-48-game-server.md), 824 counted assertions plus continuation/source checks. Follow-ups49-55 are applied, including API/build repairs, namespace validation and version metadata. Actual303-source build passed after54; proposed56 square tracking also builds and passes33 focused cache/constructor assertions. Review56 remains unapplied.

## Issues

| Date | Issue |
| --- | --- |
| 2026-10-28 | [Dictionary startup build constraint: backend V34 correction](issues/2026-10-28-dictionary-build-constraint-startup.md) |

Remaining limitations are tracked in the subsystem tracker and linked reports.
