# Native player load versus relational reconstruction: step matrix

Created 2026-10-29. Target: **42.22.0 / world format 251**, server working tree,
after the [item weight repair](../features/2026-10-29-player-item-weight-roundtrip.md).
Updated later on the same date for the
[component/equipment lifecycle implementation](../features/2026-10-29-player-reconstruction-lifecycle.md).
Further updated for [RGB/visual and identity/XP baselines](../features/2026-10-29-player-reconstruction-visuals-and-progression.md).
This is a source comparison and tracking baseline, not a runtime parity certificate.
No Java behavior, database, deployment or game installation changed for this report.

Use this document for current step tracking. The
[earlier audit](2026-10-29-player-reconstruction-deserialization-audit.md) preserves
the investigation and pre-fix weight evidence;
[cross-build recovery](2026-10-28-cross-build-character-reconstruction.md) tracks
the distinct missing-content/migration policy. Stable row IDs below should be
retained when a finding is fixed, with its test and verification date recorded.

## Reading the matrix

| Status | Meaning |
| --- | --- |
| Mapped | Corresponding saved values/operation found in source. Does **not** establish full behavioral parity or test coverage. |
| Gap | Native operation/state has no equivalent in the inspected reconstruction path. Impact can depend on the item, constructor or mod. |
| Different | Deliberately different representation, stricter failure handling, or extra relational state. Verify the stated policy rather than copying bytes literally. |
| Review | A path exists, but constructor effects, setter side effects, order or nested leaf behavior still need a differential fixture. |
| Fixed/unit | The prior gap is repaired and has focused target unit coverage. Native runtime composition remains unverified. |

Rows follow **native order within each loader**. Calls expand into the following
sections; nested fields with the same handling are listed together. This compares
the native player load closure, including carried items/components/animals; it
does not compare every loader in the world/chunk/vehicle save system. Headers,
counts and legacy version branches are included as representation decisions.
Opaque embedded payloads remain an explicit deferred-load boundary.

Relational abbreviations refer to these source files:

- **R**: [Reconstruction](../../src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java): `player`, `inventory`, `equipment`, `components`, `project`, `set` and specialized helpers.
- **S**: [Snapshot](../../src/zombie/savefile/ApocBRPlayerRelationalSnapshot.java): owner-thread detached state, including optional raw `itemWeight`.
- **C**: [Capture](../../src/zombie/savefile/ApocBRPlayerRelationalCapture.java): typed tables, bindings and structured component nodes.
- **K**: [Codecs](../../src/zombie/savefile/ApocBRPlayerRelationalCodecs.java): supported entity/animal state.
- **Schema**: [explicit columns](../../src/zombie/savefile/ApocBRPlayerRelationalSchema.java).
- **Reader**: [JDBC graph read](../../src/zombie/savefile/ApocBRPlayerRelationalReader.java).
- **ServerDB**: [server load/client profile encode](../../src/zombie/savefile/ServerPlayerDB.java).

R's general setter prefers direct fields to setters. Therefore “column exists”
does not imply the native setter or its side effects ran. Its table-coverage
check catches unconsumed populated tables, not fields never captured or omitted
derived-state operations. No section below treats a passing row-count check as
proof that the corresponding native load is complete.

## Entry and native execution order

Native existing-player materialization is `IsoPlayer.load` ->
`IsoGameCharacter.load` -> `IsoMovingObject.load`, then descriptor, human visual,
inventory/items, stats, health, XP, equipment and remaining character/player fields.
The inheritance chain returns before player-specific worn/hand references are
applied. Embedded animals have their own loader, not this ordinary player chain.

Relational order is ownership validation -> fresh player/descriptor -> character
scalars/stats/traits/perks/sequences -> health -> visuals -> inventory -> components
-> item visual finalization -> equipment -> populated-table coverage -> hand
owner/weapon and XP checker state -> player finalization. Descriptor publication
occurs only on the authoritative continuation after tracking attachment. This reordering
is material when one stage consumes another stage's derived state.

| ID | Native load step | Relational equivalent | Status / tracking implication |
| --- | --- | --- | --- |
| ENTRY-01 | Read native profile bytes with their saved world version | Reader selects complete schema/codec graph and prepares referenced source dictionaries | Different: typed restoration does not run native version branches; old opaque payloads still need source versions. |
| ENTRY-02 | Consume player serialization/object-ID bytes | Construct target `IsoPlayer`, set server slot, register graph character identity | Different: no binary discriminator needed. |
| ENTRY-03 | Reject malformed binary sizes; native item loaders can skip failed records | Reader bounds rows/bytes/counts; R validates ownership and references and rejects unreconstructable graph | Different: native partial load is not permission to publish a partial relational character. |
| ENTRY-04 | Dictionary numeric IDs feed native factories | Saved full types and stable registry names feed target factories/registries | Different: target IDs may change; old source IDs are provenance, not target identity. |
| ENTRY-05 | Registry-ID item factory uses `isFirstTimeCreated=false` | String factory uses first-creation=true | Review: reaches `GameEntity.onFirstCreation`; inspected target component tree has no hook overrides, so no proven duplicate callback from this flag alone. |
| ENTRY-06 | Native profile creates one materialized player per caller | ServerDB builds a temporary player for native client-profile encoding and separately reconstructs the authoritative server player | Different: evaluate both results; client native load can normalize server-restored state. |
| ENTRY-07 | Subsequent native saves encode the resulting object | Relational registration enables mutations; custom raw weight has its own structured state | Different: do not call a successful graph read or retained snapshot ID a backup of all subsequent revisions. |

## Moving object and descriptor

Native: [IsoMovingObject.load](../../decompiled/zombie/iso/IsoMovingObject.java)
and [SurvivorDesc.load](../../decompiled/zombie/characters/SurvivorDesc.java).

| ID | Native step, in order | Reconstruction/storage | Status / tracking implication |
| --- | --- | --- | --- |
| MOVE-01 | Consume source offsets; read position, adding current world save offsets | `character_state` contains captured world coordinates; R sets current/last/next X/Y and current/last Z directly | Different: coordinates are already materialized world values; adding source offsets again would translate twice. Relocated-world policy needs a separate fixture. |
| MOVE-02 | Restore forward isometric direction | Stable direction string -> enum -> `setForwardIsoDirection` | Mapped. |
| MOVE-03 | Conditional Lua table creation and native table load | `lua.modData` structured nodes applied in R.components, after inventory/equipment | Mapped values; Review timing if constructor/equipment hooks read modData earlier. |
| DESC-01 | Conditional descriptor presence | R always constructs `SurvivorDesc(true)`, then projects descriptor rows | Different: missing descriptor semantics are not explicitly reproduced; ordinary player profiles should have one. |
| DESC-02 | Restore descriptor ID and insert into `survivorDescriptors` | Saved descriptor projected and validated; authoritative ServerDB continuation publishes it after tracking | Fixed/unit: temporary copies do not publish. Native map replacement semantics retained. |
| DESC-03 | Restore forename/surname/torso, gender and profession | `character_descriptor` and stable profession conversion | Mapped values. |
| DESC-04 | `doStats()` and advance static `idCount` when needed | Constructor supplies generated stats; authoritative publication advances next ID above saved ID | Fixed/unit for allocation watermark. Different: no second random doStats replay; these are generated traits, not missing persisted stats. |
| DESC-05 | Clear/restore extras | `descriptor_extra` appended to new descriptor | Review: fresh-constructor defaults and absence must be compared with native clearing. |
| DESC-06 | Resolve perk IDs and restore descriptor XP boosts | Shared `character_perk.descriptor_xp_boost` -> descriptor map | Mapped; missing saved perk is stricter in R. |
| DESC-07 | Read voice fields for >=208; older files get generated defaults | Typed voice fields captured after import -> projection | Different: legacy normalization belongs to native import; target relational reads do not replay old binary branches. |
| DESC-08 | Set descriptor's character instance | `descriptor.setInstance(player)` | Mapped. |

## Character-level load

Native: [IsoGameCharacter.load](../../decompiled/zombie/characters/IsoGameCharacter.java).
Visual, inventory, health and XP calls expand below.

| ID | Native step, in order | Reconstruction/storage | Status / tracking implication |
| --- | --- | --- | --- |
| CHAR-01 | Load moving-object superclass and optional descriptor | MOVE/DESC rows | See preceding matrix. |
| CHAR-02 | Load human visual | R.visuals, after health/perks | Mapped values; see VIS rows. |
| CHAR-03 | Load root inventory; clear/copy returned saved-item list | R.inventory creates containers/items and appends root items to fresh `savedInventoryItems` | Review: native list includes compressed-load placeholders; relational list contains surviving graph items. See INV-04. |
| CHAR-04 | Restore asleep and force wake time | `character_state.asleep`, `force_wake_up_time` | Mapped. |
| CHAR-05 | Non-zombie stats, BodyDamage, XP loads | `character_stat`, health tables and `character_perk` | Ordinary player target; see HEALTH/XP. Zombie load branch is outside player reconstruction. |
| CHAR-06 | Resolve primary/secondary indexes through `includingObsoleteItems` | Graph `equipment` item identities assigned directly | Different representation; avoids positional ambiguity for complete graphs. |
| CHAR-07 | `setEquipParent`, avoiding duplicate registration when both hands share one item | Native load helper with register=false during reconstruction; authoritative ServerDB promotion after tracking | Fixed/unit: shared item registers once; temporary copies do not register. Runtime manager lifecycle remains pending. |
| CHAR-08 | Select `useHandWeapon` or null | `restoreEquipmentState` sets it from primary hand separately from animation variable | Fixed/unit: weapon/nonweapon/empty/secondary-only cases covered. |
| CHAR-09 | Apply on-fire flag via `SetOnFire` | R defers `SetOnFire` to finalization | Mapped operation; Review changed ordering. |
| CHAR-10 | Restore antidepressant/beta/pain/sleeping effects and timers | `character_effects` projection | Mapped. |
| CHAR-11 | Restore read-book progress | `character_book_progress` -> fresh `ReadBook` entries | Mapped. |
| CHAR-12 | Infection reduction, known recipes, last sleep hour | `character_effects`, `character_recipe`, `character_state` | Mapped. |
| CHAR-13 | Smoke, beard and hair growth timers | `character_state` | Mapped. |
| CHAR-14 | Restore legacy cheat booleans through setters; gates at 202/217/230 | `character_cheat` legacy rows call corresponding setters | Mapped operations; source-version gates normalized at import. |
| CHAR-15 | Sneaking and death drag-down setters | `character_state` direct fields where available | Mapped: both inspected native setters are simple field assignments. |
| CHAR-16 | Restore literature history via `addReadLiterature(title,day)` | `character_literature` directly populates map | Mapped for fresh empty map; native helper preserves an existing entry rather than overwriting it. Constructor/default-content edge needs comparison. |
| CHAR-17 | >=222 clear/restore print media | `character_print_media` appended to new list | Review default clearing; values mapped. |
| CHAR-18 | Last animal pet timestamp | `character_state.last_animal_pet` | Mapped. |
| CHAR-19 | >=231 PlayerCheats.load adds decoded enum flags | `character_cheat` set rows add stable enum names to `cheats.cheats` | Mapped operation: native private `set` is also an EnumSet addition. |

## Stats, traits, XP and health

Native: [Stats](../../decompiled/zombie/characters/Stats.java),
[CharacterTraits](../../decompiled/zombie/characters/traits/CharacterTraits.java),
[IsoGameCharacter.XP](../../decompiled/zombie/characters/IsoGameCharacter.java),
[BodyDamage](../../decompiled/zombie/characters/BodyDamage/BodyDamage.java),
[Thermoregulator](../../decompiled/zombie/characters/BodyDamage/Thermoregulator.java).

| ID | Native step, in order within its loader | Reconstruction/storage | Status / tracking implication |
| --- | --- | --- | --- |
| STAT-01 | Iterate `ORDERED_STATS`, invoke `Stats.set` | Saved stable stat keys resolve through `CharacterStat.getById`; invoke `set` | Mapped operation; target-only/missing stats require differential coverage. |
| XP-01 | Traits reset, resolve saved traits, call `add` | R.traits resolves names and calls `add`, skips missing definitions with diagnostic | Mapped flag/list update; Review no explicit reset of constructor traits. |
| XP-02 | Total XP, level and last level | `character_state` nested XP fields | Mapped values. |
| XP-03 | Clear XP map; resolve perks; insert XP | `character_perk.xp` into fresh map | Mapped values; R rejects a missing perk where native skips unknown ones. |
| XP-04 | Preserve old perk levels, clear perk list, restore levels | New `PerkInfo` entries inserted | Mapped saved levels; Review constructor list clearing. |
| XP-05 | Emit `LogLevelPerk` for changed levels | Explicit suppression during synthetic load on temporary and authoritative paths | Different by policy: avoid replaying gameplay/mod hooks while restoring existing levels. Ordinary future level events unchanged; mod-specific load-hook needs separate design. |
| XP-06 | Clear multiplier map and `addXpMultiplier` for saved ranges | Same add method with stored min/max | Mapped operation; Review fresh-map baseline. |
| XP-07 | Clamp inconsistent total XP to current-level threshold | Same threshold check/setTotalXP after saved progression | Fixed/unit: boundary cases compared with target native branch; valid totals preserved. |
| XP-08 | `getNetworkCharacterAI().updateXpChecker()` | Clear three checker maps, then native refresh | Fixed/unit: loaded perk XP/descriptor boosts/multipliers replace stale constructor entries. |
| HEALTH-01 | Per-part cut/bitten/scratched/bandaged/bleeding/deep wound/infection/health through native setters | `body_part` projects the represented fields directly | Review: field coverage exists; side effects/order of native setters need a differential body fixture. |
| HEALTH-02 | Conditional bandage life, wound infection and splint factor | Nullable columns in `body_part` retain constructor defaults on primitive-null | Mapped values; Review conditional/default equivalence. |
| HEALTH-03 | Wound timers, alcohol/pain, glass, XP flags, stitches, fracture, bullets, burn state, bandage/splint items | Corresponding `body_part` columns | Mapped values; native repeats cut-time input, represented as resulting field value rather than duplicate bytes. |
| HEALTH-04 | Wetness/stiffness and >=227 comfrey/garlic/plantain factors | `body_part` columns | Mapped. |
| HEALTH-05 | `setBodyPartsLastState()` after loading all body parts | No matching copy/update | Gap: previous-body-part baseline remains constructor state. |
| HEALTH-06 | Cold fields, >=222 sneeze/cough timer, infection/pain/cold reduction fields | `body_damage` projection | Mapped values; Review setter effects for nontrivial setters. |
| HEALTH-07 | `calculateOverallHealth()` in main-fields load | R invokes it during finalization and sets player health | Mapped operation, later order; dead profile additionally forces zero. |
| HEALTH-08 | Optional thermoregulator payload; native null-owner case reads a temporary object | R restores/constructs an owned thermoregulator when rows exist | Different: preserves represented thermal state on player; missing rows leave target defaults. |
| THERMO-01 | Set point, metabolic rate/real/target, heat deltas, damage/counter/duration; gates 243/249 | `thermoregulator` columns | Mapped values. |
| THERMO-02 | Resolve body-part node; temperature/skin/heat/primary/secondary/insulation/wind/wetness, gates 241/243 | `thermal_node` by stable body-part key | Mapped values; R fails unresolved node whereas native logs and continues. |

## Player-specific return from superclass load

Native: [IsoPlayer.load](../../decompiled/zombie/characters/IsoPlayer.java),
[Nutrition.load](../../decompiled/zombie/characters/BodyDamage/Nutrition.java),
[Fitness.load](../../decompiled/zombie/characters/BodyDamage/Fitness.java),
[PlayerCraftHistory](../../decompiled/zombie/characters/PlayerCraftHistory.java).

| ID | Native step, in order | Reconstruction/storage | Status / tracking implication |
| --- | --- | --- | --- |
| PLAYER-01 | Hours survived; gender; `InitSpriteParts` | Hours field projected; gender/sprites initialized during finalization | Mapped, reordered. |
| PLAYER-02 | Generate speech color; client MP color/options branch | No explicit speech-color reinitialization; no client options writes | Review constructor color; client options are outside server relational load. This color is generated, not missing persisted data. |
| PLAYER-03 | Zombie kill count | `character_state.zombie_kills` | Mapped. |
| PLAYER-04 | Worn count/location/item index, checking known body location | `equipment` worn rows resolve registry and call `wornItems.setItem` | Mapped represented relation; R is stricter on missing registry and ownership, not a native invalid-index skip. |
| PLAYER-05 | `onWornItemsChanged()` before player hand indexes | Same call during finalization, after R.equipment and components | Review callback timing and visibility of restored components/modData. |
| PLAYER-06 | Restore player-level primary/secondary indexes | Same graph hand identities; direct target primary/secondary fields | Mapped identities; CHAR-07/08 remain gaps. |
| PLAYER-07 | Set `Weapon` variable; survivor kills; `initSpritePartsEmpty` | Same finalization methods/variable and stored kill count | Mapped operations, reordered. |
| PLAYER-08 | Nutrition calls setters for calories/protein/lipids/carbs/weight | `character_nutrition` directly writes fields | Review: setters clamp nutrients; weight below 35 also clamps, damages and emits an event. Valid in-range values can agree; do not replay damage blindly for a temporary player. |
| PLAYER-09 | Chat mute, tag prefix/color, display name, tag/PvP flags | `character_presentation` projection with RGB bindings | Mapped values; Review where field writes bypass presentation setters. |
| PLAYER-10 | >=239 auto-drink; consume legacy reserved byte | `character_state.auto_drink`; no relational reserved byte | Mapped value / Different encoding. |
| PLAYER-11 | Optional vehicle return X/Y/seat/running; force Z=0 | `character_vehicle_return` restored in finalization and Z=0 | Mapped. Live capture samples current vehicle rather than stale previous-load return fields. |
| PLAYER-12 | Mechanics long-key/long-value map | `character_mechanics` casts both back to long | Mapped. |
| PLAYER-13 | Fitness stiffness increment/timer, regularity, bodypart list and exercise timers | `fitness_value`, `fitness_body_part`; integer exercise timestamps restored to long | Mapped values; typed numeric handling has focused historical fixtures, not full target login proof. |
| PLAYER-14 | Already-read books by dictionary ID, skip unresolved | `character_read_book` stable full types | Different representation; policy for removed definitions needs migration review. |
| PLAYER-15 | Known media line GUIDs | `character_media_line` | Mapped. |
| PLAYER-16 | >=203 voice type | `character_state.voice_type` | Mapped value; getter/field/setter resolution must remain target-compatible. |
| PLAYER-17 | >=228 craft history entries | `character_craft_history` -> fresh native entry/map | Mapped values; leaf constructor/default behavior requires runtime comparison. |

## Inventory container, compression and item creation

Native: [ItemContainer.load](../../decompiled/zombie/inventory/ItemContainer.java),
[CompressIdenticalItems.load](../../decompiled/zombie/inventory/CompressIdenticalItems.java),
[InventoryItem.loadItem](../../decompiled/zombie/inventory/InventoryItem.java),
[InventoryItemFactory](../../decompiled/zombie/inventory/InventoryItemFactory.java).

| ID | Native step, in order | Reconstruction/storage | Status / tracking implication |
| --- | --- | --- | --- |
| INV-01 | Container type and explored flag | `container` projection on root/new nested containers | Mapped. |
| INV-02 | Compression clears item/obsolete lists, reads groups and identical counts | One relational item object per saved instance, ordered ownership edges | Different: compression is transport encoding, not a missing gameplay field. |
| INV-03 | Bounded sized item record, dictionary short, sentinel byte, target factory, subtype `load` | Reader limits and graph IDs; `createSavedItem` plus exact runtime-class and saved sentinel validation | Different: no binary length to consume, strict missing-item/class handling. |
| INV-04 | Failed group creates null placeholders in saved/obsolete lists | Complete relational graph has no failed-item placeholders | Different: graph equipment references replace native indexes; selective omissions need a separate dependency-aware policy. |
| INV-05 | Reload each identical instance; overwrite subsequent native item IDs from ID list | Distinct item rows preserve native IDs and graph identities | Mapped result; duplicate-native-ID policy is explicit rather than arbitrary aliasing. |
| INV-06 | Assign each item's parent container | `restoreContainerItem` appends to both lists and calls `item.setContainer` | Mapped parent link; avoids the bag's hidden contents-container field. |
| INV-07 | Looted flag, capacity, dirty=false; return saved-item list | `container` columns and final dirty reset | Mapped values; R uses fresh root list rather than native compressed saved-list semantics. |

## Base InventoryItem.load: defaults and every payload flag

Native source: [InventoryItem](../../decompiled/zombie/inventory/InventoryItem.java),
`load(ByteBuffer,int)`. `H` means outer byte header; `B` means nested integer bits.
The target's inactive/reserved branches are retained here for tracking.

| ID | Native step/flag | Reconstruction/storage | Status / tracking implication |
| --- | --- | --- | --- |
| ITEM-01 | Read native ID and allocate H | `item.native_id` -> `id`; no header object | Mapped ID / Different encoding. |
| ITEM-02 | Reset uses/name/condition/color/capacity/modData/activation/repair/name/custom flags/IDs/range/RGB/worker/wet/favorite/stash/infection/ammo/attachment/capacity/media/rotations/scale/initialized defaults | Full item columns overlay factory fields; uses/condition/weight have explicit phase | Review: binary “flag absent” defaults versus current factory values need subtype/default tests. Missing modData/visual/sequence rows do not universally clear factory-created state. |
| ITEM-03 | H1: int uses >=220, short uses earlier | Explicit `restoreItemScalars`, native uses setter | Fixed/unit: deterministic before custom override; legacy width normalized by import. |
| ITEM-04 | H2: no action >=220, discard old byte otherwise | No relational state | Different: obsolete wire slot. |
| ITEM-05 | H4: `setConditionWhileLoading` | Same method after projection | Fixed/unit: clamp and broken flag restored. |
| ITEM-06 | H8: load ItemVisual | `visual` and `visual_body_value`; owner back-reference restored | Mapped values; final synchronization missing at ITEM-32. |
| ITEM-07 | H16: unpack RGBA, set color/custom flag and tint | RGBA columns bind to `col`, custom flag; visual has independent tint rows | Review: simultaneous native color/tint normalization not reproduced in the same order. |
| ITEM-08 | H32: item capacity | `item.item_capacity` | Mapped. |
| ITEM-09 | H64 enables nested B; B1 loads Lua table | `lua.modData` structured nodes, restored later | Mapped values; timing/default clearing at ITEM-02. |
| ITEM-10 | B2/B4/B8: activation, repairs, name | Corresponding `item` columns | Mapped values. |
| ITEM-11 | B16: validate byte count, allocate/fill/flip byteData | `item.byteData` opaque bytes -> wrapped buffer with source provenance | Mapped bytes/provenance; native contents are deferred, not deserialized by relational R. |
| ITEM-12 | B32: count and dictionary IDs for extra items | `item_extra` stable types; R builds per-item list once | Mapped; empty/absent sequence versus factory defaults remains Review. |
| ITEM-13 | B64: custom name flag | `item.custom_name` | Mapped. |
| ITEM-14 | B128: nonnegative custom actual mass -> actual/weight/custom=true | Explicit scalar phase; separate `item.weight` preserves live raw weight afterward | Fixed/unit / Different extra fidelity. Old rows fall back to shared actual mass; historical missing raw weight is unrecoverable. |
| ITEM-15 | B256: key ID | `item.key_id` | Mapped. |
| ITEM-16 | B512: no native branch | No relational state | Different: unused flag, not an omitted field. |
| ITEM-17 | B1024: remote ID/range | `remote_control_id`, `remote_range` | Mapped. |
| ITEM-18 | B2048: separate RGB fields, col/custom flag, visual tint | Optional raw snapshot RGB and structured item.rgb restored independently of col and tint | Fixed/unit: new captures preserve independent values. Old custom data uses known col fallback; historical differences remain unrecoverable. |
| ITEM-19 | B4096/B8192: worker and wet cooldown | `worker`, `wet_cooldown` | Mapped. |
| ITEM-20 | B16384/B32768/B65536: favorite/stash/infection | Corresponding columns | Mapped values. |
| ITEM-21 | B131072: current ammo | `current_ammo_count` | Mapped value; contents mass uses target ammo definition. |
| ITEM-22 | B262144/B524288/B1048576: attached slot/type/model | Corresponding item columns | Mapped values; equipment relation is separately restored. |
| ITEM-23 | B2097152: max capacity | `max_capacity` | Mapped. |
| ITEM-24 | B4194304: recorded media short | Stable media ID resolved through target RecordedMedia | Different ID representation; missing target media rejects restore. |
| ITEM-25 | B8388608: old Z rotation only when version <232 | Captured resulting `world_z_rotation` | Different: import consumes legacy branch; no active world251 payload for this bit. |
| ITEM-26 | B16777216/B33554432: world scale/initialized | `world_scale`, `initialised` | Mapped values; factory initialization may already have run before projection. |
| ITEM-27 | B67108864: load entity components | Structured `entity.*` -> R.components | See COMP matrix. |
| ITEM-28 | Remove factory components absent from loaded list | Authoritative saved membership releases extras and restores saved kinds | Fixed/unit: nonempty historical rows infer membership; new explicit marker also preserves empty. Historical absence retains defaults. |
| ITEM-29 | B134217728: animal tracks | `animal_track` -> fresh `AnimalTracks` | Mapped values; leaf serialization representation must remain target-pinned. |
| ITEM-30 | B268435456/B536870912: texture name/model index | `Texture.getSharedTexture`, stored model index | Mapped. |
| ITEM-31 | B1073741824: float XYZ rotations >=232; integer legacy YX otherwise | Stored resulting rotations | Mapped target values / Different legacy decoding. |
| ITEM-32 | Release headers and `synchWithVisual()` after state load | Per-item native synchronization after saved component/RGB state and before equipment | Fixed/unit: native clothing tint/blood/dirt paths covered; asset loading/rendering/random-model/runtime composition pending. |

## Concrete item loaders

All subtype rows include their superclass ITEM steps. Dedicated schema rows usually
hold the final values rather than the native bit headers and absent-field defaults.

| ID | Native source and ordered work | Reconstruction/storage | Status / tracking implication |
| --- | --- | --- | --- |
| BAG-01 | [InventoryContainer](../../decompiled/zombie/inventory/types/InventoryContainer.java): base -> contents ID -> reduction -> allocate/clear contents -> links/capacity -> contents.load -> synchWithVisual | `bag_state`, `container`, ownership edges; `restoreBag` sets bag/contents links and invokes reduction setter | Mapped links/reduction; nested capture tested. Final synchronization still ITEM-32. |
| WEAPON-01 | [HandWeapon](../../decompiled/zombie/inventory/types/HandWeapon.java): clear all parts **before** base load | Target factory state retained until graph attachments added | Gap: no explicit clearing of factory/default parts or their active-light/sight caches. |
| WEAPON-02 | Reset range/clip/damage/recoil/aim/reload/chance/angles/timer/blood/clip/chamber/jam; derive sprite/melee/explosive state | `weapon_state` covers saved scalar fields; target factory supplies other derived state | Mapped saved values; Review `isMelee`/`isExplosive` and constructor defaults under changed scripts. |
| WEAPON-03 | Bits1/2/4/8/16/32/64/128/256/512: range, clip, damage, timing/chance and minimum angle setters | Corresponding `weapon_state` columns | Mapped values; setter behavior needs differential checks beyond field compatibility. |
| WEAPON-04 | Bit1024: old full serialized parts; bit33554432: current registry-only parts; attach with doChange=false | Separate part item rows and attachment edges; `attachWeaponPart(part,false)` | Different: richer relational part state; doChange=false correctly avoids reapplying stat modifiers. |
| WEAPON-05 | Bits2048/4096/65536/131072/262144: fire mode, cyclic rate, explosion/max angle/blood | Corresponding columns | Mapped values. |
| WEAPON-06 | Bits524288/1048576/2097152: clip/chamber/jam; force containsClip=false if no magazine type | Direct fields from columns | Gap: missing target-definition magazine consistency correction. |
| WEAPON-07 | Bits4194304/8388608/16777216: weapon sprite/min/max sight | Corresponding columns | Mapped values. |
| FOOD-01 | [Food](../../decompiled/zombie/inventory/types/Food.java): base -> reset nutritional/gameplay defaults -> read age/last-aged | `food_state` restores final fields | Mapped values; absence/default semantics need non-weight fixtures. |
| FOOD-02 | H1/2/4/8/16/32: nutrients, hunger/base hunger/unhappy/boredom/thirst | Corresponding columns | Fixed/unit for consumption and weight-dependent fields; not a complete food gameplay fixture. |
| FOOD-03 | Nested bits1..128: heat/cook timers, cooked/burnt/cookable/dangerous flags, poison detection | Corresponding columns | Mapped values. |
| FOOD-04 | Bits256/512/1024: spice list, poison power, chef | `food_spice`, scalar food columns | Mapped; per-item sequence initialization avoids appending duplicate defaults when rows exist. |
| FOOD-05 | Bits2048..131072: spoil ages, pain/flu/sickness, poison flag/use amount | Corresponding columns | Mapped values. |
| FOOD-06 | Bits262144..33554432: freezing/frozen/update, rotten/compost, microwave/fatigue/endurance | Corresponding columns | Mapped values; inspected `setFrozen` is a simple field assignment, so bypassing that setter alone is not a gap. |
| FOOD-07 | Bit67108864: milk qty/type; bit134217728: hatch fields/genome/mother, fertilized=true | Food columns plus `egg_gene`/`egg_allele` | Mapped values; allele leaf semantics not fully differentially tested. |
| FOOD-08 | Bit268435456: stress change | `food_state.stress_change` | Mapped. |
| FOOD-09 | Server lastAged==-1 -> current world hours; animal corpse -> initAnimalIcons | No matching finalization | Gap: timestamp and icon initialization omitted. |
| DRAIN-01 | [DrainableComboItem](../../decompiled/zombie/inventory/types/DrainableComboItem.java) inherits base serialization; uses setters derive weight | Explicit uses/custom phase plus raw weight extension | Fixed/unit: saved override wins independently of key order. Script/useDelta remain target-definition dependencies. |
| CLOTH-01 | [Clothing](../../decompiled/zombie/inventory/types/Clothing.java): base -> sprite/dirt/blood/wetness/last wet update -> patch count/parts | `clothing_state`, `clothing_patch` | Mapped values; clothing patch field/type compatibility needs native round trip. |
| CLOTH-02 | synchWithVisual, then lastWetnessUpdate=current world hours | Final native synchronization now runs; saved timestamp still retained | Partly fixed/unit: visual aggregates rebuilt. Wetness time baseline remains open under priority 6. |
| BOOK-01 | [Literature](../../decompiled/zombie/inventory/types/Literature.java): base -> reset page/progress/write/custom-pages/lock/recipes -> decode page width/count/progress | `literature_state`, `literature_page`, `literature_recipe` | Mapped values; Review missing-row clearing of factory learned recipes/custom pages. |
| BOOK-02 | >=226 short header/learned recipes; older byte header | Store decoded values, no native header | Different: normalized import. |
| ALARM-01 | [AlarmClock](../../decompiled/zombie/inventory/types/AlarmClock.java) and [AlarmClockClothing](../../decompiled/zombie/inventory/types/AlarmClockClothing.java): base -> hour/minute/set/ringSince/forceDontRing>=205 -> ringSound=-1 | `alarm_state` projects persisted fields | Mapped values; Review constructor ringSound reset after factory initialization. |
| KEY-01 | [Key](../../decompiled/zombie/inventory/types/Key.java): base -> key ID -> number of keys | Base item key field and `key_state` | Mapped. Padlock defaults come from current definition. |
| MOVEITEM-01 | [Moveable](../../decompiled/zombie/inventory/types/Moveable.java): base -> worldSprite -> `ReadFromWorldSprite` including Radio | Special `createSavedItem` initializes only exact dynamic `Base.<sprite>` Moveable; later `moveable_state` projects sprite | Gap: general ordinary/custom/Radio sprite-derived name/grid/drop/weight state lacks the same initialization. Factory `Moveables.*` can cover some cases. |
| MOVEITEM-02 | If no CustomItem, derive type/fullType from sprite | Dynamic Base special case; ordinary factory/full-type handling otherwise | Review all namespace/custom-item variants; do not assume Base-only rule is general. |
| MOVEITEM-03 | Restore light flag and conditional battery/bulb/power/delta/RGB | `moveable_state` | Mapped values. |
| RADIO-01 | [Radio](../../decompiled/zombie/inventory/types/Radio.java): Moveable load -> allocate device if null -> conditional DeviceData.load -> set parent | R.radios assumes factory `deviceData`, projects it, restores presets/media | Review missing-device allocation, parent rebind and absent-payload defaults. General Moveable gap also applies. |
| RADIO-02 | [DeviceData](../../decompiled/zombie/radio/devices/DeviceData.java): name/twoway/transmit/mic/volume/device flags/channel ranges/battery/power/use/headphones | `radio_state` with explicit field bindings | Mapped values. |
| RADIO-03 | Conditional presets; media index/type/item; noTransmit | `radio_preset`, max-presets reset, stable media-ID resolution, remaining radio columns | Mapped values; missing media is stricter than raw short retention. |
| MAP-01 | [MapItem](../../decompiled/zombie/inventory/types/MapItem.java): base -> map ID -> symbols version1/2 and font table | `map_state`, `map_symbol`; text/texture constructor APIs | Different representation: stable text-layer names replace font indexes. |
| MAP-02 | [WorldMapBaseSymbol](../../decompiled/zombie/worldMap/symbols/WorldMapBaseSymbol.java): XY/anchor/scale -> setRotation -> RGBA/collide/perspective/zoom/ranges | `map_symbol` projects saved fields; colors divided by255 | Gap: direct rotation field skips cosine/sine cache recomputation; Review zoom clamp/default semantics. |
| MAP-03 | [Text](../../decompiled/zombie/worldMap/symbols/WorldMapTextSymbol.java)/[texture](../../decompiled/zombie/worldMap/symbols/WorldMapTextureSymbol.java): restore text/translation/layer or symbol ID | Correct addText/addTexture APIs, then projection | Mapped values; C filters non-user-defined/network-owned symbols, so full live symbol list is not universally retained. Compare native save eligibility before treating that as loss. |
| ANIMALITEM-01 | [AnimalInventoryItem](../../decompiled/zombie/inventory/types/AnimalInventoryItem.java): base -> construct animal -> native animal.load -> initAnimalData | `item.embeddedAnimal`, K.animal/R.restoreAnimal -> initAnimalData | Mapped major path; see ANIMAL matrix. Raw item.weight extension applies after components. |

## Human and item visual leaves

Native: [HumanVisual](../../decompiled/zombie/core/skinnedmodel/visual/HumanVisual.java)
and [ItemVisual](../../decompiled/zombie/core/skinnedmodel/visual/ItemVisual.java).

| ID | Native step | Reconstruction/storage | Status / tracking implication |
| --- | --- | --- | --- |
| VIS-01 | HumanVisual.clear; optional hair/beard/skin colors | `human_visual` optional RGB -> ImmutableColor | Mapped values; Review default/absence clearing on player-created visual. |
| VIS-02 | Body hair/skin texture/zombie rot/skin texture name/beard/hair models | `human_visual` scalar projection | Mapped. |
| VIS-03 | Bounded body blood/dirt/hole arrays | `visual_body_value` by body-part key | Mapped representation; R rejects unknown channels/parts rather than native excess-byte ignore. |
| VIS-04 | BodyVisual list of loaded ItemVisuals | Owned `visual` rows and ordered bodyVisual additions | Mapped values; native load list timing differs from R. |
| VIS-05 | `setNonAttachedHair`, optional natural hair/beard colors | Field projection and color construction | Review setter effects; colors mapped. |
| VIS-06 | ItemVisual type/alternate/clothing names; optional tint/base texture/choice/hue/decal | `visual` projection with explicit item-type setter and hue null sentinel | Mapped. |
| VIS-07 | Blood/dirt/holes/basic/denim/leather patch byte channels | `visual_body_value` with six channel allowlist | Mapped values; subtype aggregate recalculation remains ITEM-32. |

## Entity/component leaves and membership

Native: [GameEntity.loadEntity](../../decompiled/zombie/entity/GameEntity.java)
and [Component](../../decompiled/zombie/entity/Component.java). C/K determine
supported payloads; unsupported capture issues refuse complete publication.

| ID | Native ordered step or leaf | Reconstruction/storage | Status / tracking implication |
| --- | --- | --- | --- |
| COMP-01 | Clear output loaded list; read component count | Iterate `component` rows; require complete coverage/current codec | Different representation. |
| COMP-02 | Per-component ByteBlock; numeric ComponentType; reuse or allocate | Stable kind name; reuse matching class/simple name or allocate by ComponentType | Mapped selection; collisions/renames are a compatibility boundary. |
| COMP-03 | Load payload, attach newly loaded component; catch/log native failure and skip block safely | Decode structured values; failures abort reconstruction | Different fail-closed policy. |
| COMP-04 | Connect once after native component loop | Group saved payloads by owner; restore final membership/state, then connect once | Fixed/unit: intermediate reconstruction connects removed; factory initialization and other native callbacks still exist. |
| COMP-05 | InventoryItem removes components not in loaded list | Native releaseComponent used before missing saved kinds are attached | Fixed/unit, same finding as ITEM-28. New marker explicitly represents empty; no new component codecs are implied. |
| COMP-06 | [AttributeContainer](../../decompiled/zombie/entity/components/attributes/AttributeContainer.java): clear -> decode storage modes/IDs -> typed instance loads | `restoreAttributes` clears, resolves stable names/type kinds, converts byte/short/int/float/enums/sets and puts values | Mapped supported values; binary storage modes unnecessary. Unsupported value types fail capture. |
| COMP-07 | [FluidContainer](../../decompiled/zombie/entity/components/fluids/FluidContainer.java): reset capacity/filters/flags, Empty(false), header/capacity | `restoreFluid` empties and applies saved state | Mapped major steps; Review script/default differences when optional state absent. |
| COMP-08 | [FluidInstance](../../decompiled/zombie/entity/components/fluids/FluidInstance.java): allocate; enum/string fluid identity; default/override RGB; amount; set parent | Stable fluid string, fresh instance, saved RGB/amount and parent | Mapped; missing fluid rejects rather than native dropping unknown-fluid entry. |
| COMP-09 | Fluid whitelist/blacklist, flags/name/rain; recalculateCaches(true) | Restore filters and fields; same cache recalculation | Mapped operation. Fluid weight is not simply missing cache recalculation. |
| COMP-10 | [Durability](../../decompiled/zombie/entity/components/combat/Durability.java): reset, current/max HP, MaterialType | Saved HP/material with enum conversion | Mapped values. |
| COMP-11 | [EntityScriptInfo](../../decompiled/zombie/entity/components/script/EntityScriptInfo.java): originalIsItem/originalScript, then resolve current `script` reference | Original fields restored only | Gap: derived reference can retain factory script inconsistent with restored identity. |
| COMP-12 | [UiConfig](../../decompiled/zombie/entity/components/ui/UiConfig.java): reset names -> read names -> setSkin -> uiEnabled | K captures names/enabled; generic R writes fields | Gap: resolved `skin` is not rebuilt from saved name; factory skin may differ. `getSkin` fallback is not the same saved-skin resolution. |
| COMP-13 | [CraftBench](../../decompiled/zombie/entity/components/crafting/CraftBench.java): recipe query and fluid/energy channel stores | Stable names plus saved nativeBits; restore uses nativeBits | Mapped current enum layout; Review future enum reordering because names are not used to rebuild bits. |
| COMP-14 | [SpriteConfig](../../decompiled/zombie/entity/components/spriteconfig/SpriteConfig.java): script presence, dictionary script, version, saved master flag, readFromScript | Stable script name/version/master; R reads script, assigns flag, initializes object caches | Fixed/unit supported state. R explicitly rejects missing scripts instead of native logging; version mismatch remains accepted as native. |
| COMP-15 | LuaComponent, Parts, Signals, TestComponent, CraftRecipeComponent, ContextMenuConfig, CraftBenchSounds, WallCoveringConfig | Explicit empty payload whitelist in K | Mapped empty native load contract; do not assume all fields on those classes are native saved payloads. Recheck inheritance on source changes. |
| COMP-16 | Other native types: Resources/crafting logic/MetaTag/SpriteOverlayConfig and additional unsupported state | No universal codec; records issues/incomplete coverage | Different supported subset: fail capture. ComponentType inventory restrictions mean class existence does not establish a valid carried-item example. |
| COMP-17 | Native byteData can be consumed later by other native loaders | Raw bytes/provenance retained; not interpreted by R.components | Review deferred decoder/layout/dictionary/mod compatibility; not closed by table coverage. |
| COMP-18 | Native Lua tables deserialize supported scalar/table forms | Structured nodes restore table/object/array/scalars, owned references and opaque data | Different extended representation; cycles/unsupported/unowned references have explicit capture/validation policies. Full modData compatibility is not proven here. |

## Embedded animal load

Native: [IsoAnimal.load](../../decompiled/zombie/characters/animals/IsoAnimal.java),
[AnimalGene](../../decompiled/zombie/characters/animals/AnimalGene.java) and
[AnimalAllele](../../decompiled/zombie/characters/animals/AnimalAllele.java).
K.animal captures a structured live representation; R.restoreAnimal is the reader.

| ID | Native step, in order | Reconstruction/storage | Status / tracking implication |
| --- | --- | --- | --- |
| ANIMAL-01 | setIsAnimal(true); zone UUID lookup; world coordinates/direction | Target type/breed constructor, saved zone lookup/direction; saved coordinate projection | Mapped major state; Review current/last/next position coherence after integer constructor coordinates and float field overlay. |
| ANIMAL-02 | Stats; type/definition/breed; custom name | Constructor resolves type/breed; stats through set; custom name restored | Mapped values; missing animal definitions fail target construction. |
| ANIMAL-03 | Lua modData; item ID; `init(breed)` | Constructor initialization precedes restored modData/item ID | Review ordering: native init occurs after those saved values, R's constructor before them. |
| ANIMAL-04 | Female descriptor, animal ID, genome/alleles; checkGeneticDisorder | Same stored fields; fresh genes/alleles; checkGeneticDisorder | Mapped major operations; gene/allele conditional serialization details need differential leaf tests. |
| ANIMAL-05 | Optional attached-tree coordinates | Live tree or pending coordinates captured; restore pending references | Mapped intent; runtime reattachment pending. |
| ANIMAL-06 | setAge, hours survived, saved update timestamp, setSize | setAge/setSize plus stored hours; live snapshot uses capture-time game milliseconds | Different live-capture timing policy; Review simulation/offline aging equivalence. |
| ANIMAL-07 | Mother ID/follow-back, pregnancy flag/time | Both fields represented and restored | Mapped major state; R prefers explicit mother animal ID if present. |
| ANIMAL-08 | Milk flag/quantity/max, milk removed, preferred hutch; conditional wool | R stores fields directly after age/size | Review: native `setMilkQuantity` and preferred-position setter are bypassed; derived/clamp effects not established. |
| ANIMAL-09 | Fertilized fields/conditional eggs today; stress/acceptance map | K/R structured fields and map | Mapped values. |
| ANIMAL-10 | Weight/pregnancy/milk/impregnation times; setHealth; virtual ID/migration/clutch | Structured scalars | Mapped values; setter effects require native integration. |
| ANIMAL-11 | setOnHook, pending hook coordinates | Same setOnHook and coordinate restoration | Mapped intent; world hook resolution pending runtime. |
| ANIMAL-12 | petTimer>=236; setWild>=245; onlineID>=247 client-only | Pet/wild captured, online ID excluded from R assignment | Different: no stale server network-ID reuse. Review wild setter/constructor effects. |
| ANIMAL-13 | AnimalInventoryItem.initAnimalData after animal.load | Same helper invoked, then final raw-weight extension if present | Mapped derived item update; full native animal/weight integration remains pending. |

## Action tracker

These are follow-up items, **not** automatic authorization to change behavior.
“Source gap” is not proof that a particular player's data was damaged.

| Track | Related steps | Current state | Next evidence needed |
| --- | --- | --- | --- |
| TRACK-01 | ITEM-03/05/14, DRAIN-01, FOOD-02 | Fixed in source; 1,630 focused checks previously passed | Exact target runtime: use -> mutation commit -> server rebuild -> client native profile -> reconnect. Historical missing raw weight stays unrecoverable. |
| TRACK-02 | ITEM-18/32, CLOTH-02, VIS-07 | RGB/final visual sync fixed/unit; wetness timestamp still open | RGB-only SQL deltas, native tint/blood/dirt and journal fallback covered. Real assets/models/bags/wetness/runtime checks still required. |
| TRACK-03 | ITEM-28, COMP-04/05 | Fixed/unit: membership marker, native release, saved payload before new attachment and final owner connect | 56-check lifecycle fixture includes mutation SQL round trips. Real native callback/manager/world behavior still needs runtime validation. |
| TRACK-04 | CHAR-07/08, PLAYER-05 | Owner/weapon helpers fixed/unit; remaining finalization callback timing review | Shared/distinct/secondary-only/empty hands and temporary/live registration flags covered; real events and full GameServer/logout composition remain pending. |
| TRACK-05 | DESC-02/04, XP-05/07/08 | Descriptor publication/watermark and XP normalization/checker fixed/unit; load perk-event replay intentionally suppressed | 143-check visual/progression/lifecycle suite covers thresholds, checker map replacement, ID watermark and temporary nonpublication. Real authoritative login/mod composition still pending. |
| TRACK-06 | HEALTH-01/02/05/06, PLAYER-08 | Open baseline gap and setter review | Native-vs-R body snapshots, post-load health tick, nutrition range limits; ensure temporary reconstruction does not duplicate damage/events. |
| TRACK-07 | MOVEITEM-01/02, RADIO-01, FOOD-09 | Open subtype initialization | Ordinary/dynamic/custom-item Moveables, Radio sprite/device parent, lastAged=-1 and animal corpse icons. |
| TRACK-08 | WEAPON-01/02/06 | Open parts/consistency gaps | Factory-default parts then saved removal; active sight/light; no-magazine containsClip; verify no modifier applied twice. |
| TRACK-09 | MAP-02, COMP-11/12/13 | Open derived caches/layout review | Nonzero symbol rotation cos/sin; saved entity script differing from factory; named UI skin differing from factory; enum channel compatibility. |
| TRACK-10 | ITEM-02/12, DESC-05, XP-01/03/04/06, BOOK-01, VIS-01 | Open absent/empty/default semantics review | Constructor/mod defaults plus empty saved lists/maps/components. Distinguish intentional script defaults from state that native load explicitly clears. |
| TRACK-11 | ANIMAL-01/03/06/08/12/13, COMP-17/18 | Open nested/native integration | Animal lifecycle/setter effects, offline timing, opaque payloads and representative modData with source provenance. |
| TRACK-12 | ENTRY-01/03/04/06/07, INV-04 | Separate migration/client/durability boundaries | Preserved DB copy, exact old dictionaries, missing-content dependency policy, failed load isolation and client/server comparison. |

## Verification and maintenance

This report was checked against current target decompiled and replacement sources.
It adds no new gameplay tests; documentation-only work does not rerun the Java suite.
The preceding repair recorded 1,630 weight mutation/reconstruction checks. Current
visual/progression verification passes 143 checks (including 56 lifecycle checks),
the updated 1,763-check mutation fixture, 17 SpriteConfig checks and the persistence
resilience suite. Those fixtures have
engine/JDBC boundaries and do not validate all Mapped rows above. The default
installed JAR now reports world251 and the 303-source / 895-class target dry build
passes. The earlier world249 build failure is historical. Runtime parity is not claimed.

For each fix, retain the row/track ID, record the changed helper/storage contract,
link a native-versus-relational assertion and distinguish source, unit, exact-JAR,
database and live-client evidence. Recheck the whole containing load method when
upstream changes a flag/default or adds a subtype. A new state column or successful
table consumption alone must not close a derived-state or ordering finding.
