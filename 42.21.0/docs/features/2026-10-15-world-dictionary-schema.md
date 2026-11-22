# Shared world dictionary: backend migration handoff

Target: Project Zomboid dedicated server 42.21.0, world serialization version 249, WorldDictionary file format 1. The backend schema is implemented by `apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V6__Create_World_Dictionary.sql`. All nine tables live in the existing **`player_rel` schema**, with a **`world_dictionary_` table prefix**; no `world_dict` schema is created. The [original reference DDL](2026-10-15-world-dictionary-schema.sql) retains the proposal names and is not an executable migration or the authoritative physical naming contract. Use backend V6 for actual DDL, triggers and JDBC table names. The game persistence adapter is still future work.

Scope agreed with the user: fresh saves adopt an existing dictionary template/namespace before writing world data. Existing-world remapping and arbitrary cross-build migration are out of scope. PostgreSQL owns dictionary data as typed rows, not a WorldDictionary.bin bytea backup. Native getters continue using in-memory maps. Binary packets for unmodified clients may still be generated from those maps.

## Actual backend migration (2026-10-15)

The user's schema decision supersedes the original separate-schema proposal. Physical tables are:

| Logical name | Actual PostgreSQL relation |
| --- | --- |
| namespace | `player_rel.world_dictionary_namespace` |
| revision | `player_rel.world_dictionary_revision` |
| registry | `player_rel.world_dictionary_registry` |
| entry | `player_rel.world_dictionary_entry` |
| runtime_view | `player_rel.world_dictionary_runtime_view` |
| registry_state | `player_rel.world_dictionary_registry_state` |
| entry_state | `player_rel.world_dictionary_entry_state` |
| mod_override | `player_rel.world_dictionary_mod_override` |
| journal | `player_rel.world_dictionary_journal` |

`GameRuntimeDataSourceConfig` applies V6 through `classpath:db/game-runtime-migration`, with history in `public.game_runtime_schema_history`, at manager startup when `game-runtime.datasource.enabled=true`. Existing profile/default behavior is unchanged: `prod` and the `postgres` overlay enable it; plain `local` leaves it disabled. V1-V5 are unchanged. No new datasource, schema, property, dependency, HTTP API or automatic import is introduced. The migration creates empty structures; the connector supplies namespace UUIDs and bootstraps revision zero and the seven native registers transactionally. It does not seed game IDs or hashes.

Database functions `player_rel.world_dictionary_guard()` and `player_rel.world_dictionary_validate_publication()` enforce namespace-serialized writes, append-only identities/revisions/journal, immutable namespace identity and register descriptors, forward-only counters/revision pointers, and immutable ready views and child rows. Dictionary truncation is rejected. A ready revision cannot later acquire backdated entries/registers. Ordinary engine roles must not own tables, change DDL or disable triggers.

Views must be inserted as staging. Updating to ready with `published_at` validates the locked current identity revision, all seven required native registers, complete captured registry counters equal to the current counters, allocator high-water marks except sprites, required info/string/script states, no state from a future revision, and contiguous overrides with `overrides_present=true`. Checks and composite FKs retain exact kind/domain ownership. The engine still owns deterministic allocation, local reconciliation, Java UTF-16 length validation, codec compatibility, binding definitions and pinning the committed ready view. Acquire the namespace `FOR UPDATE` before reading/allocating, even though write triggers also lock it.

V6 does not change payload provenance. Backend V7 now adds `player_rel.server` with stable integer IDs, view `server_id` binding, and per-payload server/namespace/view tuples on native player/vehicle rows and opaque value leaves. See [the implemented provenance handoff](../reports/2026-10-15-world-dictionary-provenance-handoff.md) for exact names, initial IDs and unknown-origin policy. Relational source_dataset/snapshot records remain unchanged; do not reinterpret `player_rel.item.native_id` as a dictionary ID. See the manager's `docs/world-dictionary.md` for the maintained backend guide and isolated test command.

## What the audit establishes

The dictionary is a type/name registry with persistent numeric allocations, metadata, and some script version hashes. It does not contain entire item definitions or serializer implementations, and its IDs are not primary keys for individual item instances. Access to the dictionary resolves references inside bytes; the corresponding native loader is still needed to interpret the field layout.

All source links below refer to the exact target-version vanilla tree, never another game build.

| Class / method | Responsibility |
| --- | --- |
| [ScriptManager.Load](../../decompiled/zombie/scripting/ScriptManager.java#L1550) | Opens registration with `WorldDictionary.StartScriptLoading()` and closes it with `ScriptsLoaded()` around script loading. |
| [GameEntityScript](../../decompiled/zombie/scripting/entity/GameEntityScript.java#L156) | Calls `WorldDictionary.onLoadEntity(this)` after loading an entity definition; items take the item-registration branch. |
| [WorldDictionary.onLoadEntity / onLoadItem](../../decompiled/zombie/world/WorldDictionary.java#L91) | Collects full type, module, name, current mod, vanilla history, obsolete status, ordered previous mod overrides, and runtime script references. |
| [IsoWorld](../../decompiled/zombie/iso/IsoWorld.java#L2047) | Calls `WorldDictionary.init()` during world initialization. |
| [WorldDictionary.init](../../decompiled/zombie/world/WorldDictionary.java#L234) | Resets maps, loads persisted data, reconciles current definitions, collects object names and strings, parses named registers, saves data and logs. |
| [DictionaryData](../../decompiled/zombie/world/DictionaryData.java#L246) | Owns shared item/entity allocation, both lookup directions, object-name allocation, persistent counters, file load/save and client packet cache. |
| [DictionaryInfo](../../decompiled/zombie/world/DictionaryInfo.java#L16), [ItemInfo](../../decompiled/zombie/world/ItemInfo.java), [EntityInfo](../../decompiled/zombie/world/EntityInfo.java) | Persistent item/entity metadata. `ItemInfo.scriptItem` and `entityScript` are runtime references rebound from ScriptManager. |
| [StringDictionary.StringRegister](../../decompiled/zombie/world/StringDictionary.java#L97), [DictionaryStringInfo](../../decompiled/zombie/world/DictionaryStringInfo.java) | Independent ID allocator/maps per named string register; persisted value, registry ID and loaded flag. |
| [ScriptsDictionary.ScriptRegister](../../decompiled/zombie/world/ScriptsDictionary.java#L150), [DictionaryScriptInfo](../../decompiled/zombie/world/DictionaryScriptInfo.java) | Independent ID allocator/maps per named script register; persisted name, registry ID, signed 64-bit version hash and loaded flag. |
| [VersionHash](../../decompiled/zombie/world/scripts/VersionHash.java) | Hashes the UTF-8 version-description string with SHA-256 and retains Guava `HashCode.asLong()`. Persist the actual Java long; do not replace it with a SHA-256 hex string or recompute it in SQL. |
| [DictionaryDataClient](../../decompiled/zombie/world/DictionaryDataClient.java) | Uses server-supplied mappings, binds local scripts and performs no dictionary file persistence. |
| [WorldDictionaryLogger](../../decompiled/zombie/world/logger/WorldDictionaryLogger.java) | Writes diagnostic registration/removal/version-change logs. It is not replayed to build the authoritative dictionary. |

### Allocation domains and exact native limits

| Registry family | Register name | ID allocation | Valid allocated IDs | Exhausted next-ID value |
| --- | --- | --- | --- | --- |
| `info` | empty string | One shared counter for **both item and non-item entity entries** | 0..32766 | 32767 |
| `object` | empty string | One counter for erosion object names | 0..126 | 127 |
| `sprite` | empty string | Persisted mapping/counter; see caveat below | Nonnegative Java int | No active vanilla allocator observed here |
| `string` | `Generic` | Counter belonging to this named register | 0..32766 | 32767 |
| `script` | `SpriteConfigs` | Counter belonging to this named register | 0..32766 | 32767 |
| `script` | `SpriteOverlayConfigs` | Its own counter | 0..32766 | 32767 |
| `script` | `ContextMenuConfigs` | Its own counter | 0..32766 | 32767 |

These names and capitalization come from the static register declarations. Allow additional named string/script registers without adding a new table. PostgreSQL integer columns intentionally represent both allocated IDs and the one-past-the-end counter; checks prevent generating IDs vanilla cannot use. Do not use one SQL identity/sequence across these domains. Although `nextInfoId` has a negative field initializer, `WorldDictionary.init()` calls `reset()`, which sets it to zero before loading or building a dictionary. Negative-ID legacy import is excluded from this fresh-save contract.

Important sprite correction: `spriteNameToIdMap`, `spriteIdToNameMap`, and `nextSpriteNameId` are serialized, but the audited DictionaryData class populates these maps on load and falls back to `IsoSpriteManager` when a mapping is absent. It does not allocate new sprite IDs from `nextSpriteNameId` in the inspected registration path. Preserve these fields, including empty maps and zero counters, but do not invent a competing SQL sprite allocator. Shared WorldDictionary data alone does not standardize every engine sprite ID.

## Implemented relational model

Nine tables under `player_rel`, created by backend game-runtime migration V6 after V5 creates the player relational schema. Every logical name below maps to `player_rel.world_dictionary_<name>` (for example, `entry` means `player_rel.world_dictionary_entry`). V6 specifies the authoritative columns, keys, constraints, indexes and triggers. `namespace`/`revision`/`runtime_view` are orchestration additions; they are not fields that already exist in the native file.

| Table | Purpose / principal columns |
| --- | --- |
| `namespace` | Shared dictionary identity: UUID `id`, unique case-sensitive `name`, `game_build`, `world_version`, `dictionary_format_version`, `current_revision`, creation time. Several worlds intentionally share this ID. |
| `revision` | Committed identity-catalog revisions: `(namespace_id, revision)` PK, reason, creation time. Revision zero bootstraps an empty namespace. |
| `registry` | Allocation domains: `(namespace_id, family, register_name)` PK, `next_id`, optional `string_max_length`, `created_revision`. Contains rows even for empty registers. |
| `entry` | Append-only identities: `(namespace_id, family, register_name, native_id)` PK, `kind`, `stable_key`, optional `module_name`/`local_name`, `created_revision`. Unique stable name within each allocation domain. |
| `runtime_view` | One immutable published startup result: namespace, UUID view ID, identity revision, server/save keys, staging/ready state, optional mod manifest SHA-256, creation/publication times. |
| `registry_state` | Frozen per-view header counters for each allocation domain. Preserves the startup snapshot even after another server allocates more IDs. |
| `entry_state` | Per-view item/entity mod metadata and availability; string/script loaded flags; script hashes. Composite FK to the exact shared identity. |
| `mod_override` | Ordered prior mod IDs for each item/entity in a view. Composite FK to its entry state, position starting at zero, unique mod IDs per entry. |
| `journal` | Append-only audit events tied to a namespace revision and optional view. JSONB detail is allowed here for before/after diagnostics, never as the dictionary's main storage. |

Identity layout examples (illustrative IDs, not seed data):

```text
family   register_name          native_id  kind    stable_key
info     ''                     17         item    Base.Axe
info     ''                     18         entity  SomeModule.SomeEntity
object   ''                     4          object  <erosion object name>
string   Generic                17         string  <registered string>
script   SpriteConfigs          17         script  <script full type>
script   SpriteOverlayConfigs   17         script  <another script full type>
```

The repeated 17 is valid across independent domains. Assigning info ID 17 separately to both an item and an entity is prohibited because they share the info allocator. The implemented contract additionally rejects a full-type key reused as a different kind in that shared domain.

`stable_key` for an info entry is the full type, with separately stored module and local name satisfying `stable_key = module_name || '.' || local_name`. For other families it is the native name/string, without normalization. Preserve case, spaces, prefixes and exact register names. Do not use citext, lowercasing or Unicode normalization. The DDL uses deterministic `COLLATE "C"` for identity text.

### Complete native-field mapping

| Native data | Relational source (logical names above) |
| --- | --- |
| `DictionaryData.nextInfoId` | `registry(info,'').next_id`; published copy in `registry_state` |
| `mextObjectNameId` (native spelling) | `registry(object,'').next_id` / view copy |
| `nextSpriteNameId` | `registry(sprite,'').next_id` / view copy; retained metadata, not a newly implemented allocator |
| Item and entity ID/name maps | `entry` where `family='info'`; `kind` selects the native map/class |
| `registryId`, `name`, `moduleName`, `fullType` | `entry.native_id`, `local_name`, `module_name`, `stable_key` |
| `modId`, `existsAsVanilla`, `isModded`, `obsolete`, `removed` | Info-family `entry_state.mod_id`, `exists_as_vanilla`, `is_modded`, `obsolete`, `removed` |
| Info `isLoaded` | `entry_state.is_loaded` is a captured runtime result; it is **not** written by native DictionaryInfo.save. Recompute from this startup, never treat another server's true value as proof of a local script. |
| `modOverrides` | `entry_state.overrides_present` plus ordered `mod_override` rows. This preserves null versus present/empty lists as well as order. |
| Sprite and object name maps | `entry` families `sprite` and `object` |
| `StringRegister.name`, `nextId`, `maxLen` | `registry.register_name`, `next_id`, `string_max_length` (`maxLen` is an engine descriptor, not a binary-file field) |
| String `registryId`, `string`, `isLoaded` | `entry.native_id`, `stable_key`; `entry_state.is_loaded` |
| `ScriptRegister.name`, `nextId` | Script-family registry and its per-view counter |
| Script `registryId`, `name`, `version`, `isLoaded` | `entry.native_id`, `stable_key`; `entry_state.script_version`, `is_loaded` |
| Mod/module compression lists in file header | Derive from the selected view's info records and override rows; their binary array positions are encoding details, not stable identities |
| `scriptItem`, `entityScript`, script-register `script` | Not stored. Bind actual loaded engine definitions by stable name after SQL load. |
| Registration load lists, unset lists, backup path, packet cache | Temporary engine state. Not canonical rows. Rebuild the packet cache from the fully published in-memory view. |

No opaque `bytea` field is needed in this schema. Database JSONB is limited to audit metadata. This does not prohibit retaining native bytes in other object tables alongside an explicit namespace/view reference.

`StringDictionary.Generic` uses `String.length() < 64`, not <=64. That length is Java UTF-16 code units, whereas PostgreSQL `char_length` counts characters. The game-side registration path must validate the exact native rule; do not silently substitute a different SQL Unicode length test. Its max length and those of future registers are descriptors supplied by the engine patch, not mutable web settings.

`DictionaryScriptInfo.version` is a signed Java long, including possible negative values; PostgreSQL `bigint` preserves it. Never route it through a JavaScript Number in an API; use JDBC long, a backend 64-bit integer, or a decimal string at a JSON boundary. A 64-character manifest SHA-256 is separate orchestration metadata, not this native version hash.

## Required write and publication semantics

1. Bootstrap a namespace and revision zero atomically; seed the seven registry rows listed in the SQL comments. Generate the namespace UUID in the application. Migration creates structures, not guessed vanilla IDs, item definitions or script hashes.
2. A startup collects current engine registrations without SQL calls inside individual getters. Open a short transaction, `SELECT ... FOR UPDATE` the namespace row, and serialize namespace allocation/publication against other participating servers. Once locked, reload current mappings before reconciling; do not allocate against an earlier unlocked snapshot.
3. For each new stable key, allocate from its registry's `next_id`, check the family limit **before** incrementing, insert the identity and advance the counter in the same transaction. Existing keys retain IDs. Sort new keys deterministically per registry for reproducibility, although existing IDs always win. Item and entity batches both use the same info counter. On failure, rollback everything; never publish uncommitted native IDs into the live world.
4. If the catalog changed, insert a new revision, set each new registry/entry's `created_revision` to that revision, and advance `namespace.current_revision`. Revisions are serialized by the namespace row lock, not obtained from a global sequence. A view with only changed availability/hashes can use the existing identity revision.
5. Build a staging `runtime_view` from the chosen template/previous view plus this server's actual loaded definitions. Copy historical metadata needed for absent entries, but recompute loaded/removed/obsolete state for this server. Never let absence on B mutate A's view or delete the shared identity. Keep each server's resolved override owner/order and script versions in its own view.
6. Freeze `registry_state` and every info/string/script `entry_state` belonging to the catalog at `identity_revision`. Object/sprite entries need no state row. Publication must check registry/entry `created_revision <= identity_revision`, each counter is beyond its allocated IDs where an allocator exists, all required rows are present, override positions are contiguous, and overrides require `overrides_present=true`. The SQL row constraints alone do not enforce these cross-row completeness conditions. The sprite counter is exempt from an allocation-high-water inference because no active allocator was observed.
7. Mark the view ready with `published_at` and commit. Afterwards populate/bind the game-thread maps and packet cache from the committed result. Published view rows, their state, overrides and counters are immutable. New startup/mod changes create a new view. Keep a stable view ID for the entire running server session; a reader must not fetch the latest view halfway through object decoding.
8. Identity rows and committed revisions are append-only: never delete, rename, reclassify, renumber or reuse them. Allocation counters never move backward. Backend permissions or triggers must enforce these update/delete rules; ordinary PK/FK/CHECK declarations do not make a row immutable. Do not use cascading deletion. Journal entries describe events but loaders query the catalog/view rows directly.

PostgreSQL [row locks](https://www.postgresql.org/docs/current/explicit-locking.html) support serialization of the namespace transaction. [Unique and foreign-key constraints](https://www.postgresql.org/docs/current/ddl-constraints.html) enforce identity and ownership boundaries; cross-row publication checks require transactional application validation or deliberate triggers, not a CHECK calling a table-reading function.

Vanilla string/script parse methods do not comprehensively clear historical stored loaded flags on every path. Do not copy that behavior into a shared global availability flag. Treat `is_loaded` as this view's explicit engine reconciliation result, validate missing definitions, and test client behavior using the emitted view. Ordinary missing items are marked removed locally; missing script definitions must not be falsely advertised as present to clients, which check script existence and versions.

## Read contract and relation to player/object persistence

- Configure a dictionary namespace UUID independently from the save name, server address, account identity, or `player_rel.source_dataset.id`. New worlds can share the namespace without sharing those other identities.
- Decode a native reference using `(namespace_id, family, register_name, native_id)`. Info decoding also checks `kind` before selecting an ItemInfo versus EntityInfo. Loaded usability/mod metadata come from the pinned runtime view, not just the existence of an identity row.
- To rebuild one view, read the ready view metadata, registry descriptors/captured counters, all catalog entries with `created_revision <= identity_revision`, state rows and ordered overrides. Use one transactionally consistent snapshot. Return detached rows, then construct runtime maps/rebind definitions on the thread responsible for engine initialization.
- If a later revision only adds identities, earlier IDs keep their meanings. An old running server still lacks the new definitions/view; identity stability is not permission to transfer a new item into that session. Require restart/synchronization and compatible actual definitions before accepting such data.
- Add an explicit dictionary namespace and origin view reference to future native-payload provenance. Attach them to the appropriate player source dataset/snapshot, vehicle dataset or stored embedded-object record. The backend agent should plan that binding with the owning persistence migration; V6 does not rewrite the existing player_rel player/source contract or add provenance foreign keys.
- `player_rel.item.source_registry_id` can then be checked against the corresponding info-family identity; `full_type` remains a useful stable name. `player_rel.item.native_id` is an individual item's runtime ID, not a dictionary registry ID; **do not** point it at this catalog or allocate it from dictionary counters.
- Keeping the same dictionary identity lets native nested object loaders resolve dictionary references. It does not parse an arbitrary byte payload through SQL. A source-context native decode can still feed the existing detached relational player capture.

## Compatibility boundary

WorldDictionary.bin is fully representable by the rows above at the semantic level. The file's byte-block framing, compression indexes, iteration order and optional-field bit encoding can be regenerated; byte-for-byte file reproduction is not the requirement. Client packet shape remains native, including `DICTIONARY_PACKET_END`.

WorldDictionary is not every registry in the engine. Recorded-media indexes, item instance IDs, animal IDs, component type IDs/enums, world/square references and IsoSpriteManager fallback IDs are separate dependencies. `WVER` identifies an embedded serialization format; sharing dictionary mappings does not replace that format reader or ensure two mod serializers write the same fields. The audit does not certify arbitrary native item/corpse/vehicle bytes portable merely because they reference this namespace.

Engine script/version hashes must still be computed by the installed definitions. Do not suppress client hash mismatches or centrally rewrite reported hashes to force acceptance. Fresh worlds sharing one template still need compatible actual definitions and relevant native serializer versions.

## Backend-agent task and acceptance criteria

Backend migration V6 implements the nine typed tables with the keys/ranges above, using `player_rel.world_dictionary_*` names. DDL stays in the backend migration, not Java runtime DDL. The future Java adapter must use those actual qualified names, including every join/foreign-key reference; do not issue SQL against `world_dict` or unprefixed dictionary tables. The game connector uses JDBC directly; a new HTTP API is not required for decoding.

Validate at least:

1. Two concurrent registrations of the same key return one identity; different info keys cannot receive the same ID across item/entity kinds. Retrying a transaction does not invent a different mapping for an existing key.
2. Independent script/string registers may reuse numeric IDs; one info registry may not. Case-distinct native names remain distinct.
3. Allocation boundary checks: info/string/script last allocated 32766 then next=32767; object last allocated 126 then next=127. Attempts beyond the limits fail without changing the catalog or counters.
4. New mods append IDs; removed/reintroduced mods retain IDs; server-local absence changes only the new view, not another ready view. A source template remains available for subsequent fresh worlds.
5. Signed 64-bit script hashes round-trip exactly, including negative values; ordered mod overrides and null versus present/empty lists survive. Empty registers and all captured counters survive.
6. Rollback leaves no partial view/revision; ready views cannot be edited; identities cannot be renumbered/reused/deleted; publication rejects a missing state row, an entry from a later revision or inconsistent override rows.
7. Later Java adapter fixture: reconstruct maps from SQL-shaped rows, encode using the native `saveToByteBuffer`, load with target-version native/client readers and compare semantic maps/flags/hashes/counters. Native script objects are rebound separately; no database column pretends to serialize them.

Backend validation on 2026-10-15: all 39 tests passed with no skips across `WorldDictionaryMigrationTest`, `PlayerRelationalMigrationTest`, `GameRuntimeMigrationTest` and `GameRuntimeDataSourceConfigTest`, using Java 25 and the Maven wrapper against a disposable PostgreSQL 14.24 cluster. V5-to-V6 upgrade and repeat migration are covered. Verification used offline Maven with resource copying skipped after explicitly copying final V6 alongside the existing V1-V5 test resources. The tests cover physical schema placement, existing player-record preservation, shared and independent ID domains, native boundaries, case-sensitive names, signed hashes, overrides, immutable publication, failed-publication rollback and concurrent lock/reload registration. No deployed database or game server was contacted. No game Java patch changed, so no target-JAR compile applies to this handoff. Native codec round-trip and real multi-server startup tests remain future adapter work.
