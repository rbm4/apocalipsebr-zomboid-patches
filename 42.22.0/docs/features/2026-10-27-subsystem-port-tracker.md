# Subsystem migration tracker: 42.21.0 to 42.22.0

Created 2026-10-27. Current task: stage the existing implementations, then review
each changed replacement individually with the user. Priorities are engine
optimizations, the NPC engine and PostgreSQL; telemetry verification is secondary.
Every subsystem remains in scope, including smaller fixes and diagnostic features.

## Current checkpoint and workflow

The authorized carry-over copies **50 old patched classes with byte-identical
vanilla baselines and 200 ApocalipseBR-only support classes**. Every copied file
retains the exact old patched bytes, including known obsolete APIs and build
literals. This is source staging, not a compatibility repair or deployable build.
The new source tree is intentionally incomplete until the reviewed merges land.

Current review checkpoint: **01-09 and 12 merged; 10-11 retired to target vanilla; 13 ServerMap next**.
There are 260 Java files present (250 exact carry-overs plus ten reviewed merges),
and two approved retirements with no target source override.
The target using-player fixture passed 190,141 assertions, including pending-add
reset; the old-target fixture passed 190,136. These execute production methods
with engine stubs and do not establish full-class or runtime compatibility.
The target engine-removal fixture passed 1,032,972 assertions; the existing full
42.21.0 unload suite passed 1,495,716. No exact-JAR or native-runtime verification
is claimed. Review 03 also corrects a historical registry attribution: the
audited GameEntityManager patch batches simulation passes and adds timing scopes;
it does not contain the registry-reconciliation implementation previously described.
The target manager lifecycle fixture passed 31 assertions using extracted manager
methods and the actual EngineEntityManager/clock/throttle with engine doubles.
It covers batching, client suppression, refreshed lookups, pending cancellation,
meta loading and immediate/deferred release; exact-JAR/runtime checks remain open.
The target crafting fixture passed 21 assertions with the complete CraftLogicSystem
and real clock/throttle, using explicit bucket/recipe/resource/world doubles.
Review 04 was verified after the power interruption. Review 05 is applied and
source-verified: its only changes suppress three diagnostics while retaining sync
format 251. No SpriteConfig gameplay fixture was needed or run. Review 06 is applied with
31 target inventory/container assertions passing; insertion/fluid/native integration
remains pending. The user accepts these staged dependencies while preserving
individual review of subsequent classes. Review 07 is applied with 35 parser/
provenance/fluid assertions passing, combining loadByteCount with quality diagnostics
and new payload metadata 251. The user explicitly expects PostgreSQL reconstruction
to consume these hooks later; full routing/reconstruction remains pending.
Review 08 is applied with 357 room/lighting/scratch fixture assertions passing;
full CalcVisibility/native LOS remain untested. The target List traversal does
not retire the separate identity/mutation cache work. Review 09 proposes a
List-compatible ID cache that uses native fallback on current plain lists and
activates only on mutation-tracked lists. Review 09 is now applied and its 31
cache/decode-boundary assertions passed; native routing/registry/reconstruction
and actual square-adapter integration remain pending. Review 10 proposes using
target vanilla without an override because the old reader patch is obsolete;
review 10 source retirement is now approved. Its family is recorded in the
[retirement manifest](../../tools/retired-class-families.json), which deployment
scripts do not yet consume. Cleanup/backup implementation and validation remain
required before deployment; no installed files were changed. Review 11 is also approved/retired and recorded in the manifest. The user
explicitly retired only the reader patch; the broader lookup/ID cache remains
in scope. Review 12 IsoChunk is approved/applied on 2026-10-28; its extracted unload
fixture passed 437,322 assertions across 6,000 differential scenarios.
Full-JAR and native runtime verification remain pending.

The [copy receipt and per-class ownership/dependency ledger](../reports/2026-10-27-carryover.json)
tracks all 301 Java source files, their original hashes, subsystem, baseline
classification, copy state and references to other patched/support classes.
It also covers version-local tools and library hashes. The original
[compatibility audit](../reports/2026-10-27-port-42-21-to-42-22.md) remains the
baseline comparison evidence. Its initial empty-tree status is historical.

The user explicitly requested this copy-only step after baseline comparison.
Changed vanilla replacements are not copied wholesale. Each of the 48 changed
replacement files has a numbered review below, including clean textual merges.
For each review, explain old vanilla behavior, patch intent, new vanilla changes,
the proposed combination, shared subsystems and necessary validation. Discuss
one class at a time and record the user's decision before applying that merge.
Do not infer acceptance of the remaining queue from acceptance of one class.

After those reviews, address the three removed-class designs, support-class API
adaptations, additional patch points, fixtures and target integration. Dependencies
that would make an approved merge unusable must be identified during its review;
ordering can change by agreement. Never mark a whole subsystem complete merely
because its source files have been copied or one owning class has merged.

States: `copied-unverified`, `awaiting-merge-discussion`, `awaiting-removed-class-design`,
`adaptation-required`, `merged-unverified`, `retired-use-target-vanilla`,
`compile-verified`, and `runtime-verified`.
The ledger preserves copy provenance separately from known adaptation work.

## What was evaluated before copying

The inventory starts with all 101 old vanilla replacements and isolates their
intent using `42.21.0/decompiled` versus `42.21.0/src`. All 200 support classes are
assigned an owner and cross-referenced with those integration points. The old
documentation index, feature-guide responsibilities, investigation implementation
updates and issue limitations supply the subsystem map; source wins where a
historical proposal or an older section describes a superseded implementation.
No 42.20.3 fallback was needed because the immediate old baseline is available.

This is source archaeology and dependency planning, not an exhaustive proof of
all algorithms, threading or binary compatibility. In particular:

- Source includes implemented indexes and later follow-ups that early performance
  proposals describe as future work. Do not reimplement those proposals blindly.
- The PostgreSQL connector guide retains earlier synchronous-login/byte-store
  descriptions. Current `ApocBRAsyncLogin`, packet continuations and
  `ServerPlayerDB` show bounded JDBC workers, server-thread integration and
  relational reconstruction. Preserve the current paths.
- The NPC worker is a live-world concurrency experiment. Its snapshot migration
  report is a plan, not an implemented detached-world boundary.
- Measured 42.21.0 timings do not establish performance on 42.22.0.
- `SpriteConfig` suppresses selected diagnostics; it is not a simulation speedup.
  `WorldSimulation` primarily adds attribution while preserving fixed physics
  stepping. Track these separately from candidate-selection optimizations.

## Optimization subsystems

The owning ID is a navigation aid, not exclusive ownership. `GameServer`,
`IsoWorld`, `IsoCell`, character classes and the entity registry connect several
rows. The per-class ledger also records referenced subsystems.

| ID | Current 42.21.0 implementation and vanilla comparison | Port boundary / required checks |
| --- | --- | --- |
| OPT-ENTITY | `EntitySimulation` aggregates elapsed 100 ms ticks; `MetaSimulationThrottle` phases meta work with aggregate owed ticks. `EntityBucket`/`ServerEntitySimulationIndex` select due members rather than visiting every meta entity. `GameEntityManager` runs one simulation-system pass for the elapsed tick batch. Craft/furnace/mashing/fluid/resource systems consume effective deltas; drying retains cleanup semantics. `LogisticsSystem` disables its simulation updater. | Pending adds, cancellation, flags, ID changes, unload-to-meta and effective elapsed time. New upstream lifecycle behavior must survive. |
| OPT-USING | Vanilla `UsingPlayerUpdateSystem` scans its whole bucket. `ServerUsingPlayerIndex` tracks active users in live bucket-position order; `GameEntity` assignment/reset/receive hooks maintain membership. Unknown accessor overrides remain conservative candidates. | Preserve callback activation/removal/swap ordering, ten-tile/floor/death rules and new entity lifecycle. First review starts here. |
| OPT-REGISTRY | `PZArrayList` adds identity positions, mutation versions and conservative raw-array fallback. `IsoObject` caches the vanilla ID inputs. The current GameEntityManager patch has no custom registry-reconciliation/retirement implementation; the historical report must not be treated as proof of current code. | Upstream removes PZArrayList and changes List descriptors. Rebuild mutation/identity contracts against the actual source and new upstream lookup checks. Assess shifted-ID consistency during the port without assuming the historical implementation is present. |
| OPT-MOVE | `ServerMovingObjectSet`/`Index` track active type membership; scheduler and bucket use identity positions, lifetime validation and tombstones instead of repeated global membership searches. | Maintain dispatch order, callback mutation, remove/re-add, compaction and multiplier restoration. Preserve upstream chasing-zombie counters. |
| OPT-PLAYER | Inventory update index narrows candidates; player/vehicle spatial queries and sound-stress index reduce discovery work; thermal scratch/clothing cache is per character. Lua callback mutation versions avoid repeated contains scans on stable lists. | Invalidations on public mutations, equipment/container changes and callbacks; retain vanilla client paths. Thermal switch repair was a verified old decompiler repair, not an intentional thermal rebalance. |
| OPT-ANIMAL | Type/perception grid, `BaseAnimalBehavior` bookkeeping split, connected-zone topology cache and relevant-client update coverage replace broad candidate scans. Animal update batches split overflow iteratively. Feeding-trough changes are tracked separately. | New zone Set/snapshot API, perception cadence, live geometry, ownership/relevance and lossless batching. Do not alter upstream baby doMeta nesting without a separate decision. |
| OPT-ZOMBIE | Ownership/authorization indexes and cadence coverage, enum-value caches, packet bookkeeping/preparation split, and bounded frozen-byte async sends. Culling skips zero/fulfilled quotas. | Main thread owns world selection; workers receive frozen bytes only. `ZombiePacket` changes writer and grapple identity; copied `NetworkZombiePacker` already needs adaptation. Retain retry/admission/disconnect/shutdown ordering. |
| OPT-LOS | `ServerLOS` has bounded parallel dispatch tied to the four explicit `LosUtil` slots, with task/lifetime bookkeeping. Player updates consume LOS results and authority queries. | Slot ownership/reuse, result generation, movement invalidation, connection retirement and native concurrency. |
| OPT-PATH | Dirty vehicle graph batching, 0.01-tile position tolerance, early path-length threshold query and per-thread collision scratch/vector ownership. | New farming-avoidance request/fallback behavior; collision scratch isolation does not make world geometry or all native caches thread-safe. |
| OPT-VEHICLE | Parts-only parked cars may use SIXTEENTH; idle device/light capability lists preserve numeric part order; VehiclePart hooks invalidate lists. Model-part indexing and same-call square reuse reduce bookkeeping. | This includes an intentional old cadence change. Preserve Lua minute arguments/deadlines, capability mutations, passengers/towing/crop checks and new vehicle fields. |
| OPT-STREAM | ServerMap indexes relevant cells and tracks cross-tick Load2 completion; engine removal uses identity swap positions; unloading keeps native object/meta teardown. | New void Load2 and queue/error cleanup plus save-finally behavior; preserve NPC and transport shutdown barriers. |
| OPT-WORLD | World/cell loop integration, no-work thumpable skip, server-only vocal traversal guards, corpse nearby-player prefilter, building counting and sound expiration fast paths. | Shared collection/index hooks and object lifecycle. Full corpse deadline scheduling and cross-tick vehicle square caching are not shipped guarantees. Fixed Bullet steps remain unchanged. |

Key old evidence:
[simulation review](../../../42.21.0/docs/reports/2026-10-03-simulation-optimization-review.md),
[implemented production follow-ups](../../../42.21.0/docs/reports/2026-10-04-production-hotspots.md),
[player/packet work](../../../42.21.0/docs/reports/2026-10-05-player-zombie-packet-hotspots.md),
[unload follow-ups](../../../42.21.0/docs/reports/2026-10-05-unload-simulation-algorithm-review.md),
[registry contracts](../../../42.21.0/docs/reports/2026-10-05-iso-entity-registry-reconciliation.md),
[vehicle changes](../../../42.21.0/docs/reports/2026-10-05-vehicle-update-followup.md),
[async transport](../../../42.21.0/docs/features/2026-10-12-async-zombie-sending.md).

## NPC engine subsystems

These are custom engine classes, not a vanilla NPC feature. `IsoPlayer` is their
native presentation; the factory deliberately separates logical identity from
ordinary account/player maps. Copying the 123 human-engine files alone cannot
activate the feature without its vanilla integration and database adapters.

| ID | Responsibility and existing ownership | Remaining integration |
| --- | --- | --- |
| BOT-CORE | Factory/profile registry, per-incarnation composition, exclusive activity, module arbitration and reusable immutable actions with fresh executions. Async worker targets 10 Hz; synchronous startup option remains. | GameServer startup/tick/Lua installation, access guard, failure-stop behavior and shutdown before world teardown. |
| BOT-STATE | Immutable needs/physical state, reducer/store, condition projection, native physiology and fight/flight policy. | Native stats/body import/export, single-writer/authorized-thread access and stale-incarnation rejection. |
| BOT-MOVE | Walking patterns/objectives, bounded route planners, physical movement, obstacle classification, crossing/landing reservations and native state presentation. | Character/climb/door APIs, per-thread collision helpers, loaded geometry, fractional realz and cancellation. |
| BOT-COMBAT | Engagement/tactics, inventory/aim/weapon phases and exactly-once native damage/ammo plus packet presentation. | Removed WeaponHit and changed TargetHitList/PlayerHit contracts, miss setter and target authorization. Copy retains obsolete calls until reviewed. |
| BOT-ENCOUNTER | Affiliation/account memory, challenges, authenticated incoming attacks, retaliation and PvP policy. | New hit packet direction/handling and target resolution; vulnerability and retaliation eligibility remain separate policies. |
| BOT-INTERACT | Cached dialogue/language, exclusive trade sessions, tasks, corpse cleanup and native dragging. | New trading request/accept/reject protocol, typed destination resolution, server-authoritative inventory and persistence-before-credit. |
| BOT-SPEECH | Detached speech decisions/catalogs, bounded repetition, acknowledged delivery and content providers. | ChatServer native delivery plus persisted speech state and roster refresh. Content must not take over actor selection or weapon timing. |
| BOT-DEATH | One death transition, corpse/loot effects, persisted respawn deadlines and fresh logical activation. | Native corpse changes, checkpoint persistence and cancellation/ownership release. |
| BOT-ZOMBIE | Server zombie pursuit/strikes and corpse-drag helper authority through the victim port. | Ownership/relay hooks, new native hit model and grapple serialization; do not let normal client auth reclaim controlled actors. |

Start with [architecture](../../../42.21.0/docs/features/2026-10-20-npc-engine-architecture.md),
[worker contract](../../../42.21.0/docs/features/2026-10-26-npc-async-worker.md),
[snapshot migration limits](../../../42.21.0/docs/reports/2026-10-26-npc-async-snapshot-map.md)
and [collision issue](../../../42.21.0/docs/issues/2026-10-26-npc-collision-scratch-race.md).
The old index retains the detailed movement/state/combat/dialogue/speech/death
guides; keep their historical build scope until each capability is verified here.

## PostgreSQL subsystems

| ID | Current source contract | Port boundary / required checks |
| --- | --- | --- |
| DB-CORE | Shared Hikari/pgJDBC connector; bounded foreground leases/deadlines; detached durable journal, transactional receipts, replay, quarantine and startup/draining policies. | Preserve availability/login gates, FIFO/rejection behavior, operation identity and worker shutdown ordering. Retain current retry/discard policy rather than inventing indefinite retries. |
| DB-ACCOUNT | ServerWorldDatabase delegates account/role/admin APIs to PostgreSQL; worker login gets a detached role view. | Current native API contracts and role initialization; no silent SQLite fallback. |
| DB-LOGIN | Two JDBC workers, bounded pending phases, identity/session/world validation, second-factor proof and simulation-thread continuations; created-player handoff prevents premature activation. | New GameServer and packet flow, retransmissions, timeouts/disconnects and server-thread construction. |
| DB-PLAYER | Scalar/whole-graph mutation reconciliation, native item identity, codecs/schema, one-statement read, relational reconstruction, load-quality diagnostics, login origin and offline SQLite migration/verification. | 251 native format, renamed reflected hand fields, equipment/media/component contracts, publication/life/session fences and incomplete captures. Default source switches must be checked in code. |
| DB-DICT | Typed dictionary publication, exact namespace/view provenance, listener/cached source views and scoped native decode. | WorldDictionary hook, scripts/string readers, item/mannequin/corpse/vehicle opaque bytes, explicit unknown-origin policy and old/new build namespace separation. |
| DB-VEHICLE | Optional authoritative VehicleStore adapter over the shared connector, preserving native byte payload provenance. | New coordinate repair/validation and vehicle serialization milestones 250/251 must reach the PostgreSQL path as well as SQLite. |
| DB-NPC | Cached definitions/profile/state/dialogue/relationship/speech/mortality adapters and trade sales, using detached snapshots and shared persistence services. | Preserve logical rather than ephemeral native IDs, incarnation/revision fences, cache refresh and receipt semantics. |
| DB-MOD | Server-thread Lua declaration/repository scaffold with validated prepared SQL and bounded async callbacks. | GameServer pre-mod-load installation. This remains a scaffold, not a shipped BeyondTen mastery migration. |

Source switches verified: master connector is opt-in; relational capture defaults
true when the master switch/server guard is satisfied; player byte-store and
vehicle backend switches default false; NPC async defaults true. Do not enable
these features or change deployment configuration during staging.

Old guides: [connector and outage contracts](../../../42.21.0/docs/features/2026-10-14-postgresql-connector.md),
[relational graph](../../../42.21.0/docs/features/2026-10-14-player-relational-schema.md),
[reconstruction/login](../../../42.21.0/docs/features/2026-10-15-player-relational-login.md),
[item identity](../../../42.21.0/docs/features/2026-10-17-persistence-player-items.md),
[dictionary decode](../../../42.21.0/docs/features/2026-10-15-world-dictionary-decode.md),
[NPC persistence](../../../42.21.0/docs/features/2026-10-20-npc-persistence.md),
[offline migration](../../../42.21.0/docs/features/2026-10-26-offline-player-migration.md),
[Lua scaffold](../../../42.21.0/docs/features/2026-10-26-lua-mod-entities.md).

The old dictionary schema proposal is not the authoritative executable backend
migration. Preserve actual backend table names, engine-role grants and creator
default privileges. No SQL is executed during this copy. Version-local receipt
DDL is carried over as an unapplied asset; it is not the complete grants/bootstrap
contract or authorization to run it.

## Other features and secondary targets

| ID | Existing feature / integration |
| --- | --- |
| APP-EVENT | PostgreSQL lobby event schedule/control, login history/origin and native lobby teleport, with cached operation announcements supplied through a content port. |
| APP-BRIDGE | Opt-in backend observation/radio bridge; bounded detached observations and virtual-thread HTTP delivery, with chat/lifecycle/world hooks. |
| FIX-SAFEHOUSE | Membership validation against the expected safehouse and ownership transfer that rejects owning another house rather than mere membership. Three unchanged-baseline replacements are copied together. |
| FIX-LOADING | Missing animation directories handled as optional; XML filename case recovery only for a missing path with an unambiguous match. Preserve ordinary errors. |
| FIX-TROUGH | Feeding-trough fluid/container handling, including the old non-empty-container guards. |
| NETWORK-GUARDS | Receive-direction, connection/player ownership and sentinel checks before player damage/effect/stat mutation; port these to the new composed identity fields. |
| OBS-TELEMETRY | Schema-3 server capture, batched object/vehicle timers and optional Lua/lookup counters. Secondary verification priority, but copied now because the primary implementations call these classes. |
| OBS-CLIENT | Client zombie sampler/writer and its IsoPlayer hook within the unsuffixed source tree. This does not bring the separate 42.21.0-client target into scope. |
| INTEGRATION | GameServer, IngameState and LuaManager composition/startup/loop glue; shared hooks are tracked explicitly instead of counted as an independent completed feature. |

## Known work retained in copied files

The copy deliberately leaves `NetworkZombiePacker`'s obsolete raw-ByteBuffer
serializer call, four WeaponHit-dependent NPC classes, the NPC miss setter,
old trading accept/reject calls, reflected hand-field names and old dataset/
dictionary build literals intact. They are marked `adaptation-required` in the
ledger even though copy hashes match. Other copied files remain unverified;
the known list is a minimum, not a complete target API audit.

The old fixture scripts at repository `tools/` generally pin 42.21.0. They remain
reference coverage, not 42.22.0 validation. The engine-only removal fixture now accepts `--version 42.22.0 --engine-only`
without selecting the remaining old-version world tests. The using-player fixture now accepts
`--version 42.22.0` and verifies target production classes/extracted GameEntity
methods against explicit engine stubs; see review 01 for coverage and limits. The copied version-local async sender
fixture resolves its own version directory; review its engine stubs and serializer
assumptions before using it as target compatibility evidence.

## Validation at this checkpoint

Check exact copy hashes, unchanged old-source/baseline hashes, absence of all
unapproved changed/removed replacements, local links, unique class coverage and
queue coverage. No full Java compilation is claimed while required replacement
classes and known API adaptations are missing. Compilation with the exact target
JAR and adapted production fixtures belongs to subsequent integration checkpoints.
No server, client, database or deployment settings are modified by staging.

## Subsystem counts and merge queue

Generated inventory tables follow. Counts describe source ownership and staging,
not completed feature ports. The individual per-class ledger is authoritative for
copy provenance; record future merge decisions in both places.

### Ownership counts

| Subsystem | All files | Copied | Merged | Retired to vanilla | Awaiting review | Removed target |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| APP-BRIDGE | 5 | 5 | 0 | 0 | 0 | 0 |
| APP-EVENT | 1 | 1 | 0 | 0 | 0 | 0 |
| BOT-COMBAT | 23 | 20 | 0 | 0 | 2 | 1 |
| BOT-CORE | 30 | 30 | 0 | 0 | 0 | 0 |
| BOT-DEATH | 4 | 4 | 0 | 0 | 0 | 0 |
| BOT-ENCOUNTER | 9 | 9 | 0 | 0 | 0 | 0 |
| BOT-INTERACT | 21 | 16 | 0 | 0 | 4 | 1 |
| BOT-MOVE | 22 | 22 | 0 | 0 | 0 | 0 |
| BOT-SPEECH | 10 | 9 | 0 | 0 | 1 | 0 |
| BOT-STATE | 14 | 14 | 0 | 0 | 0 | 0 |
| BOT-ZOMBIE | 7 | 7 | 0 | 0 | 0 | 0 |
| DB-ACCOUNT | 2 | 2 | 0 | 0 | 0 | 0 |
| DB-CORE | 5 | 5 | 0 | 0 | 0 | 0 |
| DB-DICT | 10 | 7 | 0 | 0 | 3 | 0 |
| DB-LOGIN | 9 | 9 | 0 | 0 | 0 | 0 |
| DB-MOD | 1 | 1 | 0 | 0 | 0 | 0 |
| DB-NPC | 6 | 6 | 0 | 0 | 0 | 0 |
| DB-PLAYER | 26 | 25 | 1 | 0 | 0 | 0 |
| DB-VEHICLE | 2 | 1 | 0 | 0 | 1 | 0 |
| FIX-LOADING | 2 | 1 | 0 | 0 | 1 | 0 |
| FIX-SAFEHOUSE | 3 | 3 | 0 | 0 | 0 | 0 |
| FIX-TROUGH | 1 | 1 | 0 | 0 | 0 | 0 |
| INTEGRATION | 8 | 0 | 1 | 0 | 7 | 0 |
| NETWORK-GUARDS | 5 | 1 | 0 | 0 | 4 | 0 |
| OBS-CLIENT | 2 | 2 | 0 | 0 | 0 | 0 |
| OBS-TELEMETRY | 3 | 3 | 0 | 0 | 0 | 0 |
| OPT-ANIMAL | 7 | 5 | 0 | 0 | 2 | 0 |
| OPT-ENTITY | 15 | 12 | 3 | 0 | 0 | 0 |
| OPT-LOS | 2 | 2 | 0 | 0 | 0 | 0 |
| OPT-MOVE | 4 | 3 | 0 | 0 | 1 | 0 |
| OPT-PATH | 4 | 3 | 0 | 0 | 1 | 0 |
| OPT-PLAYER | 8 | 7 | 1 | 0 | 0 | 0 |
| OPT-REGISTRY | 5 | 0 | 2 | 1 | 1 | 1 |
| OPT-STREAM | 2 | 0 | 0 | 0 | 2 | 0 |
| OPT-USING | 3 | 2 | 1 | 0 | 0 | 0 |
| OPT-VEHICLE | 3 | 1 | 0 | 0 | 2 | 0 |
| OPT-WORLD | 5 | 2 | 0 | 0 | 3 | 0 |
| OPT-ZOMBIE | 12 | 9 | 0 | 0 | 3 | 0 |

### Individual merge reviews

Review 01 [GameEntity](../reports/2026-10-27-merge-01-game-entity.md) is approved/applied; its target using-player fixture passed.
Review 02 [EngineEntityManager](../reports/2026-10-27-merge-02-engine-entity-manager.md) is approved/applied; its target removal fixture passed.
Review 03 [GameEntityManager](../reports/2026-10-27-merge-03-game-entity-manager.md) is approved/applied; its target lifecycle fixture passed.
Review 04 [CraftLogicSystem](../reports/2026-10-27-merge-04-craft-logic-system.md) is approved/applied; its target crafting fixture passed.
Review 05 [SpriteConfig](../reports/2026-10-27-merge-05-sprite-config.md) is approved/applied; source verification passed.
Review 06 [ItemContainer](../reports/2026-10-27-merge-06-item-container.md) is approved/applied; its target fixture passed.
Review 07 [InventoryItem](../reports/2026-10-27-merge-07-inventory-item.md) is approved/applied; its target parser fixture passed, with reconstruction integration pending.
Review 08 [IsoGridSquare](../reports/2026-10-27-merge-08-iso-grid-square.md) is approved/applied; its grid fixture passed.
Review 09 [IsoObject](../reports/2026-10-27-merge-09-iso-object.md) is approved/applied; its object fixture passed.
Review 10 [LoadGridsquarePerformanceWorkaround](../reports/2026-10-27-merge-10-load-gridsquare.md) is approved/retired to vanilla; stale-override cleanup remains tracked and unimplemented.
Review 11 [LootRespawn](../reports/2026-10-27-merge-11-loot-respawn.md) is approved/retired to vanilla; cleanup remains pending.
Review 12 [IsoChunk](../reports/2026-10-27-merge-12-iso-chunk.md) is approved/applied, preserving upstream and unload optimization/telemetry; its target fixture passed.
The remaining 36 entries await individual discussion. Exact-JAR compilation/runtime remain pending.

| Review | Replacement relative to zombie/ | Primary owner | Text merge | Decision / state |
| --- | --- | --- | --- | --- |
| 01 | [entity/GameEntity.java](../../src/zombie/entity/GameEntity.java) | OPT-USING | clean | Approved/applied; target fixture passed; full-class/runtime pending |
| 02 | [entity/EngineEntityManager.java](../../src/zombie/entity/EngineEntityManager.java) | OPT-ENTITY | clean | Approved/applied; target fixture passed; target-JAR/runtime pending |
| 03 | [entity/GameEntityManager.java](../../src/zombie/entity/GameEntityManager.java) | OPT-ENTITY | clean | Approved/applied; target fixture passed; target-JAR/runtime pending |
| 04 | [entity/components/crafting/CraftLogicSystem.java](../../src/zombie/entity/components/crafting/CraftLogicSystem.java) | OPT-ENTITY | clean | Approved/applied; target fixture passed; target-JAR/runtime pending |
| 05 | [entity/components/spriteconfig/SpriteConfig.java](../../src/zombie/entity/components/spriteconfig/SpriteConfig.java) | INTEGRATION | clean | Approved/applied; source verified; target-JAR/runtime pending |
| 06 | [inventory/ItemContainer.java](../../src/zombie/inventory/ItemContainer.java) | OPT-PLAYER | clean | Approved/applied; target fixture passed; target-JAR/runtime pending |
| 07 | [inventory/InventoryItem.java](../../src/zombie/inventory/InventoryItem.java) | DB-PLAYER | 1 conflict region(s) | Approved/applied; parser fixture passed; reconstruction/target-JAR pending |
| 08 | [iso/IsoGridSquare.java](../../src/zombie/iso/IsoGridSquare.java) | OPT-REGISTRY | 9 conflict region(s) | Approved/applied; grid fixture passed; adapter/native integration pending |
| 09 | [iso/IsoObject.java](../../src/zombie/iso/IsoObject.java) | OPT-REGISTRY | clean | Approved/applied with List adaptation; object fixture passed; adapter/native pending |
| 10 | [LoadGridsquarePerformanceWorkaround.java](../../decompiled/zombie/LoadGridsquarePerformanceWorkaround.java) | OPT-REGISTRY | 1 conflict region(s) | Retired to target vanilla; no source override; deployment cleanup pending |
| 11 | [LootRespawn.java](../../decompiled/zombie/LootRespawn.java) | OPT-REGISTRY | 1 conflict region(s) | Approved: retired to target vanilla |
| 12 | [iso/IsoChunk.java](../../decompiled/zombie/iso/IsoChunk.java) | OPT-STREAM | 1 conflict region(s) | Approved/applied; fixture passed |
| 13 | [network/ServerMap.java](../../decompiled/zombie/network/ServerMap.java) | OPT-STREAM | 4 conflict region(s) | Awaiting individual discussion |
| 14 | [iso/IsoWorld.java](../../decompiled/zombie/iso/IsoWorld.java) | OPT-WORLD | 2 conflict region(s) | Awaiting individual discussion |
| 15 | [iso/IsoCell.java](../../decompiled/zombie/iso/IsoCell.java) | OPT-WORLD | clean | Awaiting individual discussion |
| 16 | [MovingObjectUpdateScheduler.java](../../decompiled/zombie/MovingObjectUpdateScheduler.java) | OPT-MOVE | 2 conflict region(s) | Awaiting individual discussion |
| 17 | [characters/animals/IsoAnimal.java](../../decompiled/zombie/characters/animals/IsoAnimal.java) | OPT-ANIMAL | clean | Awaiting individual discussion |
| 18 | [iso/areas/DesignationZoneAnimal.java](../../decompiled/zombie/iso/areas/DesignationZoneAnimal.java) | OPT-ANIMAL | 2 conflict region(s) | Awaiting individual discussion |
| 19 | [iso/objects/IsoThumpable.java](../../decompiled/zombie/iso/objects/IsoThumpable.java) | OPT-WORLD | clean | Awaiting individual discussion |
| 20 | [iso/objects/IsoGenerator.java](../../decompiled/zombie/iso/objects/IsoGenerator.java) | INTEGRATION | 1 conflict region(s) | Awaiting individual discussion |
| 21 | [iso/objects/IsoDoor.java](../../decompiled/zombie/iso/objects/IsoDoor.java) | INTEGRATION | clean | Awaiting individual discussion |
| 22 | [iso/objects/IsoDeadBody.java](../../decompiled/zombie/iso/objects/IsoDeadBody.java) | DB-DICT | clean | Awaiting individual discussion |
| 23 | [iso/objects/IsoMannequin.java](../../decompiled/zombie/iso/objects/IsoMannequin.java) | DB-DICT | clean | Awaiting individual discussion |
| 24 | [characters/IsoPlayer.java](../../decompiled/zombie/characters/IsoPlayer.java) | INTEGRATION | clean | Awaiting individual discussion |
| 25 | [characters/IsoGameCharacter.java](../../decompiled/zombie/characters/IsoGameCharacter.java) | INTEGRATION | clean | Awaiting individual discussion |
| 26 | [pathfind/PathFindBehavior2.java](../../decompiled/zombie/pathfind/PathFindBehavior2.java) | OPT-PATH | clean | Awaiting individual discussion |
| 27 | [popman/ZombieCountOptimiser.java](../../decompiled/zombie/popman/ZombieCountOptimiser.java) | OPT-ZOMBIE | clean | Awaiting individual discussion |
| 28 | [characters/NetworkZombieAI.java](../../decompiled/zombie/characters/NetworkZombieAI.java) | OPT-ZOMBIE | 1 conflict region(s) | Awaiting individual discussion |
| 29 | [network/packets/character/ZombiePacket.java](../../decompiled/zombie/network/packets/character/ZombiePacket.java) | OPT-ZOMBIE | 1 conflict region(s) | Awaiting individual discussion |
| 30 | [vehicles/BaseVehicle.java](../../decompiled/zombie/vehicles/BaseVehicle.java) | OPT-VEHICLE | clean | Awaiting individual discussion |
| 31 | [vehicles/VehicleParts.java](../../decompiled/zombie/vehicles/VehicleParts.java) | OPT-VEHICLE | clean | Awaiting individual discussion |
| 32 | [vehicles/VehiclesDB2.java](../../decompiled/zombie/vehicles/VehiclesDB2.java) | DB-VEHICLE | clean | Awaiting individual discussion |
| 33 | [world/WorldDictionary.java](../../decompiled/zombie/world/WorldDictionary.java) | DB-DICT | clean | Awaiting individual discussion |
| 34 | [network/packets/character/PlayerDamagePacket.java](../../decompiled/zombie/network/packets/character/PlayerDamagePacket.java) | NETWORK-GUARDS | 1 conflict region(s) | Awaiting individual discussion |
| 35 | [network/packets/character/PlayerEffectsPacket.java](../../decompiled/zombie/network/packets/character/PlayerEffectsPacket.java) | NETWORK-GUARDS | 1 conflict region(s) | Awaiting individual discussion |
| 36 | [network/packets/character/PlayerStatsPacket.java](../../decompiled/zombie/network/packets/character/PlayerStatsPacket.java) | NETWORK-GUARDS | 1 conflict region(s) | Awaiting individual discussion |
| 37 | [network/packets/SyncPlayerStatsPacket.java](../../decompiled/zombie/network/packets/SyncPlayerStatsPacket.java) | NETWORK-GUARDS | 1 conflict region(s) | Awaiting individual discussion |
| 38 | [network/packets/hit/PlayerHitPlayerPacket.java](../../decompiled/zombie/network/packets/hit/PlayerHitPlayerPacket.java) | BOT-COMBAT | 1 conflict region(s) | Awaiting individual discussion |
| 39 | [network/packets/hit/PlayerHitSquarePacket.java](../../decompiled/zombie/network/packets/hit/PlayerHitSquarePacket.java) | BOT-COMBAT | clean | Awaiting individual discussion |
| 40 | [network/packets/RequestTradingPacket.java](../../decompiled/zombie/network/packets/RequestTradingPacket.java) | BOT-INTERACT | 2 conflict region(s) | Awaiting individual discussion |
| 41 | [network/packets/TradingUIAddItemPacket.java](../../decompiled/zombie/network/packets/TradingUIAddItemPacket.java) | BOT-INTERACT | 1 conflict region(s) | Awaiting individual discussion |
| 42 | [network/packets/TradingUIRemoveItemPacket.java](../../decompiled/zombie/network/packets/TradingUIRemoveItemPacket.java) | BOT-INTERACT | 1 conflict region(s) | Awaiting individual discussion |
| 43 | [network/packets/TradingUIUpdateStatePacket.java](../../decompiled/zombie/network/packets/TradingUIUpdateStatePacket.java) | BOT-INTERACT | 1 conflict region(s) | Awaiting individual discussion |
| 44 | [network/chat/ChatServer.java](../../decompiled/zombie/network/chat/ChatServer.java) | BOT-SPEECH | 1 conflict region(s) | Awaiting individual discussion |
| 45 | [core/skinnedmodel/advancedanimation/AdvancedAnimator.java](../../decompiled/zombie/core/skinnedmodel/advancedanimation/AdvancedAnimator.java) | FIX-LOADING | clean | Awaiting individual discussion |
| 46 | [Lua/LuaManager.java](../../decompiled/zombie/Lua/LuaManager.java) | INTEGRATION | clean | Awaiting individual discussion |
| 47 | [gameStates/IngameState.java](../../decompiled/zombie/gameStates/IngameState.java) | INTEGRATION | clean | Awaiting individual discussion |
| 48 | [network/GameServer.java](../../decompiled/zombie/network/GameServer.java) | INTEGRATION | 1 conflict region(s) | Awaiting individual discussion |

### Removed replacement designs, after the merge queue

- `zombie/network/fields/hit/WeaponHit.java`: not copied; upstream replacement contract needs a separate design decision.
- `zombie/network/fields/Trading.java`: not copied; upstream replacement contract needs a separate design decision.
- `zombie/util/list/PZArrayList.java`: not copied; upstream replacement contract needs a separate design decision.

### Exact support assets

- [tools/postgres-persistence-receipt.sql](../../tools/postgres-persistence-receipt.sql): copied-exact; not executed.
- [tools/Set-PostgresRuntime.ps1](../../tools/Set-PostgresRuntime.ps1): copied-exact; not executed.
- [tools/set_postgres_runtime.py](../../tools/set_postgres_runtime.py): copied-exact; not executed.
- [tools/test_async_zombie_sender.py](../../tools/test_async_zombie_sender.py): copied-exact; not executed.
- [lib/HikariCP-7.1.0.jar](../../lib/HikariCP-7.1.0.jar): already-present-exact; not executed.
- [lib/postgresql-42.7.14.jar](../../lib/postgresql-42.7.14.jar): already-present-exact; not executed.

### Copied files already known to need adaptation

| Copied source | Required later work |
| --- | --- |
| [zombie/apocalipse/humans/ServerHumanCombatNetwork.java](../../src/zombie/apocalipse/humans/ServerHumanCombatNetwork.java) | Removed WeaponHit model and changed native hit processing. |
| [zombie/apocalipse/humans/ServerHumanHitPacket.java](../../src/zombie/apocalipse/humans/ServerHumanHitPacket.java) | Removed WeaponHit and obsolete zombie-hit setter. |
| [zombie/apocalipse/humans/ServerHumanMissPacket.java](../../src/zombie/apocalipse/humans/ServerHumanMissPacket.java) | Obsolete PlayerHitSquarePacket.set overload. |
| [zombie/apocalipse/humans/ServerHumanPlayerDamage.java](../../src/zombie/apocalipse/humans/ServerHumanPlayerDamage.java) | Removed WeaponHit payload model. |
| [zombie/apocalipse/humans/ServerHumanPlayerHitPacket.java](../../src/zombie/apocalipse/humans/ServerHumanPlayerHitPacket.java) | Removed WeaponHit and obsolete PvP setter. |
| [zombie/apocalipse/humans/ServerHumanTrading.java](../../src/zombie/apocalipse/humans/ServerHumanTrading.java) | Removed RequestTradingPacket.accept/reject and changed identity fields. |
| [zombie/popman/NetworkZombiePacker.java](../../src/zombie/popman/NetworkZombiePacker.java) | Snapshot calls removed ZombiePacket.write(ByteBuffer). |
| [zombie/savefile/ApocBRPlayerRelationalDatabase.java](../../src/zombie/savefile/ApocBRPlayerRelationalDatabase.java) | Old game_build literal for newly captured datasets. |
| [zombie/savefile/ApocBRPlayerRelationalReconstruction.java](../../src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java) | Reflective hand fields renamed in new IsoGameCharacter. |
| [zombie/savefile/ApocBRWorldDictionaryDatabase.java](../../src/zombie/savefile/ApocBRWorldDictionaryDatabase.java) | Old 42.21.0/249 namespace SQL literals. |
