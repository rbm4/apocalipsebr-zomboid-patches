# Build 42.22.1 documentation

42.22.1 is the current server source target. All 305 Java sources, dependencies,
tools and historical documentation have been carried forward from 42.22.0.
GameServer includes the reviewed upstream integrity-report startup and revision
update. The Windows build supports actual compilation with retained artifacts
through `-BuildOnly -ArtifactDir PATH`.

The actual 2026-11-04 build passed: **305 sources / 902 class files**. The
[retained ZIP](../artifacts/ApocBR-server-42.22.1.zip) and
[build receipt](reports/2026-11-04-port-build-receipt.json) record the output;
the installation was not modified.

Start with the [port execution and artifact report](reports/2026-11-04-port-execution-and-artifact.md)
and the [vanilla compatibility audit](reports/2026-11-04-port-42-22-0-to-42-22-1.md).
Persistence/event build labels remain 42.22.0 for this exact carry-forward; exact
42.22.1 database provenance requires a coordinated follow-up. Runtime validation
and inherited cleanup/concurrency/reconstruction limitations remain open.

Documents dated before 2026-11-04 were copied from 42.22.0 with their original
dates and historical results. Copying them does not repeat their tests on 42.22.1.

## Features

| Date | Document |
| --- | --- |
| 2026-11-05 | [Squad cohesion, moving passive recovery and immersive state-change speech](features/2026-11-05-squad-cohesion-healing-and-state-speech.md) |
| 2026-11-05 | [Async NPC authority: detached planning, server-owned execution and deadlock prevention](features/2026-11-05-npc-async-authority.md) |
| 2026-11-05 | [NPC movement: simulation-tick publication, shared snapshots and native prediction](features/2026-11-05-npc-movement-replication.md) |
| 2026-11-05 | [Squad-aware fight or flight: capable nearby allies, recovery and crowd pressure](features/2026-11-05-npc-squad-fight-or-flight.md) |
| 2026-11-05 | [Retreat rendezvous: gradually flank toward squadmates actively providing assistance](features/2026-11-05-npc-retreat-squad-rendezvous.md) |
| 2026-11-04 | [Squad combat assistance: A-life threat reports, last-known leads and bounded helper authority](features/2026-11-04-npc-squad-combat-assistance.md) |
| 2026-11-04 | [Shared group patrol: Umbrella R.P.D. Alpha, health capacity and migrations](features/2026-11-04-npc-group-patrol.md) |
| 2026-11-04 | [NPC self-treatment, bleeding and stock-client first-aid packets](features/2026-11-04-npc-healing.md) |
| 2026-11-04 | [NPC overhead names: display names with spaces](features/2026-11-04-npc-display-names.md) |
| 2026-11-04 | [Agente V: apoc-dev lobby NPC and cached greeting pools](features/2026-11-04-agente-v-dev-lobby.md) |
| 2026-11-04 | [Server Lua dialogue actions, pooled responses and safehouse travel](features/2026-11-04-dialogue-lua-actions.md) |
| 2026-11-04 | [Lobby startup streaming bridge](features/2026-11-04-lobby-startup-streaming.md) |
| 2026-11-04 | [Bounded server network diagnostics and repeat summaries](features/2026-11-04-network-diagnostics.md) |
| 2026-11-04 | [Unsigned runtime deployment: signed original preservation and repeatable launcher updates](features/2026-11-04-unsigned-runtime-deployment.md) |
| 2026-11-04 | [Backend messages: immutable appends and 30-second zombie-kill batches](features/2026-11-04-backend-messages.md) |
| 2026-10-29 | [SpriteConfig persistence and quarantine recovery (42.22.0 server)](features/2026-10-29-player-sprite-config-and-quarantine-recovery.md) |
| 2026-10-29 | [Reconstruction: item RGB, visual finalization, descriptor and XP baselines](features/2026-10-29-player-reconstruction-visuals-and-progression.md) |
| 2026-10-29 | [Reconstruction: subtype initialization and health/time baselines](features/2026-10-29-player-reconstruction-subtypes-and-health.md) |
| 2026-10-29 | [Player reconstruction: component membership and hand-item lifecycle](features/2026-10-29-player-reconstruction-lifecycle.md) |
| 2026-10-29 | [Player item weight mutation round trips (42.22.0 server)](features/2026-10-29-player-item-weight-roundtrip.md) |
| 2026-10-29 | [Historical dictionaries during the 42.22.0 upgrade](features/2026-10-29-historical-dictionary-compatibility.md) |
| 2026-10-29 | [BeyondTen character-scoped Lua entity persistence](features/2026-10-29-beyondten-character-entities.md) |
| 2026-10-27 | [Subsystem migration tracker: 42.21.0 to 42.22.0](features/2026-10-27-subsystem-port-tracker.md) |

## Reports

| Date | Document |
| --- | --- |
| 2026-11-05 | [Permanent async NPC deadlock fix draft: detached decisions and server-owned native execution](reports/2026-11-05-npc-async-deadlock-permanent-fix-draft.md) |
| 2026-11-05 | [NPC running replication: animation root motion, clipped prediction and publication cadence](reports/2026-11-05-npc-running-replication.md) |
| 2026-11-05 | [Squad assistance run: helpers follow distress but never reach engagement](reports/2026-11-05-squad-assistance-following-without-engagement.md) |
| 2026-11-05 | [Server-controlled sprinter speed: measured gait model and implementation proposal](reports/2026-11-05-server-zombie-sprinter-speed.md) |
| 2026-11-04 | [Backend message inbox and batched zombie-kill delivery proposal](reports/2026-11-04-backend-message-inbox-and-zombie-kills.md) |
| 2026-11-04 | [42.22.1 port execution and retained build artifact](reports/2026-11-04-port-execution-and-artifact.md) |
| 2026-11-04 | [42.22.0 to 42.22.1: vanilla changes and patch carry-forward](reports/2026-11-04-port-42-22-0-to-42-22-1.md) |
| 2026-10-29 | [Player reconstruction versus native deserialization: 42.22.0](reports/2026-10-29-player-reconstruction-deserialization-audit.md) |
| 2026-10-29 | [Native player load versus relational reconstruction: step matrix](reports/2026-10-29-native-player-load-reconstruction-matrix.md) |
| 2026-10-28 | [Pathfinding collision scratch isolation:42.22.0 verification](reports/2026-10-28-pathfinding-thread-local-audit.md) |
| 2026-10-28 | [Follow-up review56: square-object mutation tracking and entity-ID cache](reports/2026-10-28-merge-56-square-mutation-tracking.md) |
| 2026-10-28 | [Follow-up review55: event-control engine metadata](reports/2026-10-28-merge-55-event-control-metadata.md) |
| 2026-10-28 | [Follow-up review 54: zombie native path-request argument](reports/2026-10-28-merge-54-zombie-path.md) |
| 2026-10-28 | [Follow-up review 53: NPC native player-update transport](reports/2026-10-28-merge-53-npc-transport.md) |
| 2026-10-28 | [Follow-up review 52: IsoDoor decompiler compile repair](reports/2026-10-28-merge-52-iso-door-compile.md) |
| 2026-10-28 | [Follow-up review 51: dictionary build/world-version metadata](reports/2026-10-28-merge-51-dictionary-build-version.md) |
| 2026-10-28 | [Follow-up review 50: relational dataset build metadata](reports/2026-10-28-merge-50-relational-dataset-build.md) |
| 2026-10-28 | [Follow-up review 49: reconstruction hand storage names](reports/2026-10-28-merge-49-reconstruction-hand-fields.md) |
| 2026-10-28 | [Review 48/48: GameServer and coupled packer API adaptation](reports/2026-10-28-merge-48-game-server.md) |
| 2026-10-28 | [Review 47/48: IngameState](reports/2026-10-28-merge-47-ingame-state.md) |
| 2026-10-28 | [Review 46/48: LuaManager](reports/2026-10-28-merge-46-lua-manager.md) |
| 2026-10-28 | [Review 45/48: AdvancedAnimator](reports/2026-10-28-merge-45-advanced-animator.md) |
| 2026-10-28 | [Review 44/48: ChatServer](reports/2026-10-28-merge-44-chat-server.md) |
| 2026-10-28 | [Review 43/48: TradingUIUpdateStatePacket](reports/2026-10-28-merge-43-trading-update-state.md) |
| 2026-10-28 | [Review 42/48: TradingUIRemoveItemPacket](reports/2026-10-28-merge-42-trading-remove-item.md) |
| 2026-10-28 | [Review 41/48: TradingUIAddItemPacket](reports/2026-10-28-merge-41-trading-add-item.md) |
| 2026-10-28 | [Review 40/48: RequestTradingPacket / NPC trading handshake](reports/2026-10-28-merge-40-request-trading.md) |
| 2026-10-28 | [Review 39/48: PlayerHitSquarePacket](reports/2026-10-28-merge-39-player-hit-square.md) |
| 2026-10-28 | [Review 38/48: PlayerHitPlayerPacket / NPC combat integration](reports/2026-10-28-merge-38-player-hit-player.md) |
| 2026-10-28 | [Review 37/48: SyncPlayerStatsPacket connection ownership](reports/2026-10-28-merge-37-sync-player-stats.md) |
| 2026-10-28 | [Review 36/48: PlayerStatsPacket](reports/2026-10-28-merge-36-player-stats-packet.md) |
| 2026-10-28 | [Review 35/48: PlayerEffectsPacket](reports/2026-10-28-merge-35-player-effects-packet.md) |
| 2026-10-28 | [Review 34/48: PlayerDamagePacket](reports/2026-10-28-merge-34-player-damage-packet.md) |
| 2026-10-28 | [Review 33/48: WorldDictionary](reports/2026-10-28-merge-33-world-dictionary.md) |
| 2026-10-28 | [Review 32/48: VehiclesDB2](reports/2026-10-28-merge-32-vehicles-db.md) |
| 2026-10-28 | [Review 31/48: VehicleParts](reports/2026-10-28-merge-31-vehicle-parts.md) |
| 2026-10-28 | [Review 30/48: BaseVehicle](reports/2026-10-28-merge-30-base-vehicle.md) |
| 2026-10-28 | [Review 29/48: ZombiePacket](reports/2026-10-28-merge-29-zombie-packet.md) |
| 2026-10-28 | [Review 28/48: NetworkZombieAI](reports/2026-10-28-merge-28-network-zombie-ai.md) |
| 2026-10-28 | [Review 27/48: ZombieCountOptimiser](reports/2026-10-28-merge-27-zombie-count-optimiser.md) |
| 2026-10-28 | [Review 26/48: PathFindBehavior2](reports/2026-10-28-merge-26-path-find-behavior.md) |
| 2026-10-28 | [Review 25/48: IsoGameCharacter](reports/2026-10-28-merge-25-iso-game-character.md) |
| 2026-10-28 | [Review 24/48: IsoPlayer](reports/2026-10-28-merge-24-iso-player.md) |
| 2026-10-28 | [Review 23/48: IsoMannequin](reports/2026-10-28-merge-23-iso-mannequin.md) |
| 2026-10-28 | [Review 22/48: IsoDeadBody](reports/2026-10-28-merge-22-iso-dead-body.md) |
| 2026-10-28 | [Review 21/48: IsoDoor](reports/2026-10-28-merge-21-iso-door.md) |
| 2026-10-28 | [Review 20/48: IsoGenerator](reports/2026-10-28-merge-20-iso-generator.md) |
| 2026-10-28 | [Review 19/48: IsoThumpable](reports/2026-10-28-merge-19-iso-thumpable.md) |
| 2026-10-28 | [Review 18/48: DesignationZoneAnimal](reports/2026-10-28-merge-18-designation-zone-animal.md) |
| 2026-10-28 | [Review 17/48: IsoAnimal](reports/2026-10-28-merge-17-iso-animal.md) |
| 2026-10-28 | [Review 16/48: MovingObjectUpdateScheduler](reports/2026-10-28-merge-16-moving-object-scheduler.md) |
| 2026-10-28 | [Review 15/48: IsoCell](reports/2026-10-28-merge-15-iso-cell.md) |
| 2026-10-28 | [Review 14/48: IsoWorld](reports/2026-10-28-merge-14-iso-world.md) |
| 2026-10-28 | [Review 13/48: ServerMap](reports/2026-10-28-merge-13-server-map.md) |
| 2026-10-28 | [Cross-build character login:42.21.0 relational state on42.22.0](reports/2026-10-28-cross-build-character-reconstruction.md) |
| 2026-10-28 | [Build42.22.0 source compilation checkpoint](reports/2026-10-28-build-check.md) |
| 2026-10-27 | [42.21.0 to 42.22.0 source compatibility audit](reports/2026-10-27-port-42-21-to-42-22.md) |
| 2026-10-27 | [Review 12/48: IsoChunk](reports/2026-10-27-merge-12-iso-chunk.md) |
| 2026-10-27 | [Review 11/48: LootRespawn](reports/2026-10-27-merge-11-loot-respawn.md) |
| 2026-10-27 | [Review 10/48: LoadGridsquarePerformanceWorkaround](reports/2026-10-27-merge-10-load-gridsquare.md) |
| 2026-10-27 | [Merge review 09/48: IsoObject](reports/2026-10-27-merge-09-iso-object.md) |
| 2026-10-27 | [Merge review 08/48: IsoGridSquare](reports/2026-10-27-merge-08-iso-grid-square.md) |
| 2026-10-27 | [Merge review 07/48: InventoryItem](reports/2026-10-27-merge-07-inventory-item.md) |
| 2026-10-27 | [Merge review 06/48: ItemContainer](reports/2026-10-27-merge-06-item-container.md) |
| 2026-10-27 | [Merge review 05/48: SpriteConfig](reports/2026-10-27-merge-05-sprite-config.md) |
| 2026-10-27 | [Merge review 04/48: CraftLogicSystem](reports/2026-10-27-merge-04-craft-logic-system.md) |
| 2026-10-27 | [Merge review 03/48: GameEntityManager](reports/2026-10-27-merge-03-game-entity-manager.md) |
| 2026-10-27 | [Merge review 02/48: EngineEntityManager](reports/2026-10-27-merge-02-engine-entity-manager.md) |
| 2026-10-27 | [Merge review 01/48: GameEntity](reports/2026-10-27-merge-01-game-entity.md) |

## Issues

| Date | Document |
| --- | --- |
| 2026-11-05 | [Eight-zombie contact, tactical retries and hidden bandaging](issues/2026-11-05-eight-zombie-contact-and-bandage-presentation.md) |
| 2026-11-05 | [Patrol separation: independent area-return routes while Vector remains in healing](issues/2026-11-05-patrol-separated-area-return-and-healing.md) |
| 2026-11-05 | [Confirmed server deadlock: NPC weapon equip versus Lua dialogue API factory lock](issues/2026-11-05-npc-lua-factory-deadlock.md) |
| 2026-11-05 | [Separated fleeing squadmate: distress beyond nearby assistance range and stable retreat relief](issues/2026-11-05-squad-distress-outside-assistance-radius.md) |
| 2026-11-04 | [NPC patrol encounter: retreat oscillation, missing group support and deployed dwell mismatch](issues/2026-11-04-npc-patrol-combat-runaway.md) |
| 2026-11-04 | [ServerZombiePath rejected by Java polygon mover classification](issues/2026-11-04-server-zombie-path-unsupported-mover.md) |
| 2026-11-04 | [Lobby startup cleanup finishes before virtual zombies appear](issues/2026-11-04-lobby-cleanup-zero-removals.md) |
| 2026-10-29 | [RequestUserLog rejected with SQLState 55000](issues/2026-10-29-userlog-simulation-thread-sql.md) |
| 2026-10-28 | [Dictionary startup blocked by database build constraint](issues/2026-10-28-dictionary-build-constraint-startup.md) |
