# Origin server and dictionary context for binary payloads

Backend migration V7 now implements this storage contract in `apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V7__Add_Server_And_Payload_Provenance.sql`. It has been validated on a disposable database; deployment still occurs through normal enabled game-runtime Flyway startup. V1-V6 remain unchanged. The Java patch now propagates V7 provenance through dictionary publication and existing player/vehicle/opaque-leaf storage; player-attached native decoders now select cached source dictionaries. Client profile transport and other persistence boundaries remain separate work. Do not alter native byte-array layouts.

## Actual V7 implementation and initial IDs

| player_rel.server.id | server_key from game-servers.json |
| --- | --- |
| 1 | apoc-1 |
| 2 | apoc-2 |
| 3 | apoc-dev |

These are permanent explicit integer assignments from the catalog's real `servers` list, including disabled dev. The migration seeds them literally for reproducible Flyway history; it does not read mutable JSON or reassign IDs on startup/order changes. Future keys need a new migration with an unused positive ID. Registry updates, deletion and truncation are rejected. Consumers use `server_id` / `source_server_id` directly and keep namespace/view UUIDs for exact decode context.

V7 adds the origin tuple to `public.networkplayers`, `public.vehicles`, and `player_rel.value_node`, with optional positive `payload_world_version` on opaque leaves. It adds no provenance fields to typed items/components or outer relational snapshots/datasets. Existing bytes retain all-NULL unknown origin, even if their containing server is known. The existing native snapshot importer continues writing its original columns; no origin is guessed. Per the requested engine compatibility policy, unknown origin falls back to the native dictionary. It is never labelled as verified local origin; arbitrary unknown foreign bytes still have no portability guarantee. Known but unavailable source views fail explicitly.

The migration enriches known historical dictionary views while holding an exclusive table lock and temporarily disabling only the V6 guard/publication row triggers for that one UPDATE; both are restored before commit. This avoids republishing historical revisions. The ready-view guard remains active after migration. An unmatched immutable historical view needs a controlled verified future migration before it can be used as known origin.

The backend cannot determine the actual encoder from arbitrary bytes. Writers must write bytes plus the tuple atomically, preserve the tuple on unchanged copies and replace it after actual re-encoding. Upgrade legacy byte-overwriting writers before attributed data enters cross-server reads. The Java patch now covers leaf capture, weak in-memory byteData provenance, schema-driven deltas/forks and load/write mappings. Native player-attached decoders now use scoped source views; profile packet transport still requires separate work. `vehicles.id` remains the legacy global integer PK: server provenance does not solve multi-server native vehicle ID collisions or define stable ownership.

Maintained backend details and isolated verification: [payload provenance guide](../../../../apocalipsebr-manager/manager/docs/payload-provenance.md). No deployed database, native payload layout or game Java patch is changed by this backend schema work.

## Server identity and dictionary binding

V7 creates **`player_rel.server`** with positive **`integer id`** PK, unique case-sensitive `server_key text COLLATE "C"`, and creation time. Compact integer IDs are the backend contract for direct consumer input, replacing the earlier UUID suggestion. Configure the engine with `-Dapocbr.postgres.serverId=<id>`. The old dictionary.serverKey property is retired; Java supplies numeric IDs and the V7 trigger fills canonical keys. Multiple restarts of the same logical server retain that integer ID/key. Multiple save/dictionary incarnations may belong to it.

V7 adds a `server_id integer` FK to `player_rel.world_dictionary_runtime_view`, while retaining its existing server_key/save_key compatibility fields. Existing views are backfilled only by exact unique registry-key equality. Unknown historical keys remain NULL. New views resolve registered key-only input to ID, or accept ID-only input and fill the canonical compatibility key; mismatched and unknown identities fail. A unique constraint on `(namespace_id,id,server_id)` lets payloads reference the exact namespace/view/server tuple. The existing PK `(namespace_id,id)` remains valid. New Java consumers should supply server_id directly. The Java mirror now uses ID-only inserts, numeric discovery filters and numeric notification fields. Legacy key-only consumers remain compatible for registered keys through the binding trigger.

V6's `namespace_id` identifies a dictionary allocation history; `runtime_view.id` identifies one immutable publication of that history. `identity_revision` alone is scoped to the namespace and is not a globally unique server/dictionary identifier. Server ID alone cannot decode bytes after a reset or definition change.

## Payload-bearing tables requiring new provenance columns

Implemented column group on the three payload stores below:

```text
source_server_id                 integer
source_dictionary_namespace_id   uuid
source_dictionary_view_id        uuid
```

V7 adds an all-null-or-all-present check, a server FK, and a composite FK from these columns to the exact runtime view/server tuple. All three fields are stored; payload rows do not duplicate free-form server keys. Text keys remain in the central registry and dictionary view for diagnostics/compatibility. Known origins must target a ready view. Native player/vehicle constraints are immediate; value-node FKs and final-row ready validation defer like the V5 staging graph.

| Table | Payload / why it needs the group | Existing version metadata |
| --- | --- | --- |
| `public.networkplayers` | `data bytea` is a complete native player snapshot. The native profile packet still reads it. Origin belongs to the bytes' last actual encoder, not whichever server happens to SELECT the row. | `worldversion` already exists. |
| `public.vehicles` | `data bytea` is a complete native vehicle snapshot containing native references and potentially nested inventories/components. | `worldversion` already exists. |
| `player_rel.value_node` | `opaque_value bytea` is the remaining opaque-leaf store in the relational player graph. Current production capture uses it for component kind `item.byteData`; corpse/mannequin payloads can preserve an older/foreign dictionary even after the outer player is saved locally. Provenance must travel with this leaf. | Parent player snapshot has `source_world_version`, but an embedded byteData payload may have its own native header/version. V7 adds nullable `payload_world_version integer`; populate it only if the native format has been identified; never confuse component.codec_version with world serialization version. |

For `value_node`, provenance is nullable only for an explicitly unknown historical opaque leaf. Non-opaque values should have these columns null; an origin tuple must require `value_kind='opaque' AND opaque_value IS NOT NULL`. Future native writers must supply an exact tuple before the leaf is declared safely portable. The migration must not invent dictionary context from opaque bytes or assign the current server just because it is copying an old leaf. Define an explicit reject/quarantine policy for unknown legacy origin before cross-server decoding is enabled.

Store provenance at the leaf rather than only on component/item/snapshot: a character graph can contain payloads from different origins, and the dictionary of its latest outer save does not necessarily describe each retained byte array. If future component contracts guarantee one binary payload per component, a component-level representation could work, but the current generic typed-node schema allows opaque leaves and must retain each leaf's context independently.

## Tables that do not need decoding-origin columns now

- `player_rel.item`, `container`, `equipment`, subtype tables, health/stats/visual tables: typed relational data, not native binary payloads. `item.native_id` is an instance ID and must not become a dictionary FK. `source_registry_id` is separate dictionary provenance that can be checked through the source context when needed.
- `player_rel.component`: describes structured component codec/coverage. Its opaque bytes live in value_node; do not add the same mandatory tuple to both and allow them to diverge.
- `player_rel.snapshot` / `source_dataset`: useful places for optional capture-server auditing or source context defaults, but they cannot replace exact provenance on a retained opaque leaf. A snapshot has `source_world_version` and a source blob hash, not the native player byte array itself.
- `player_rel.world_dictionary_*` identity/state/journal child tables already inherit a namespace and view where relevant. Server identity is derived from the view; no need to duplicate the key on each dictionary entry.
- Account, role, allow/ban tables and `public.game_runtime_imports`: no dictionary-dependent binary state requiring this tuple. No per-payload migration is needed there.
- Native chunk/map/world files are not PostgreSQL payload tables in this migration chain. Their foreign opaque content must eventually be converted locally or retain a separate durable sidecar; this migration does not solve those storage paths.

## Additional key/copy constraints for the migration agent

1. `public.vehicles` currently has PK `id` only; two independently allocated servers can produce the same vehicle ID. Adding source_server_id does not fix collisions. Decide separately whether the vehicle identity is a global UUID with native aliases or a composite owner/save/vehicle key. Origin of the current encoding and identity/ownership are different concepts; do not use a changing last-encoder field as the vehicle's stable primary identity.
2. Keep existing schema deployment compatible while game writers are upgraded: initially allow explicit unknown origin on existing rows; validate/backfill only known sources, then tighten new-write rules deliberately. Do not mark all historical bytes as produced by the most recent dictionary view.
3. Write bytes plus origin in the same transaction. Re-encoding fully materialized state changes the origin tuple to the encoder's local view. Copying unchanged bytes preserves the tuple. Native item.byteData now uses a weak identity attachment carried through relational capture/reconstruction. Native file/network round trips and arbitrary mod buffer replacements do not transport this sidecar.
4. V5's live reconciliation and snapshot fork explicitly enumerate columns from `ApocBRPlayerRelationalSchema`. The Java schema metadata now includes the leaf provenance columns, so detached capture, SQL load/write, deltas and fork copies carry them.
5. Player/vehicle byte-store writers and profile/vehicle readers must likewise include the new columns. The Java byte-store writers and SQL readers now include them; native profile packets still use their original wire format.
6. Prevent deletion of a referenced published view. Test two distinct opaque origins inside one player snapshot, leaf moves/copies, rollback, view/server FK mismatches, unchanged-payload preservation and re-encoding under a new view.

Implemented V7 migration scope: central server registry; runtime-view server binding; provenance tuple on networkplayers, vehicles and value_node; optional opaque-leaf world version; keys/checks/indexes and a deliberate legacy-null policy. Dictionary notifications are already sent by the mirror's publication transaction on channel `apocbr_dictionary_changed`; V6 has no built-in NOTIFY trigger. Other publication writers must emit an equivalent committed hint if immediate discovery is required.

## Backend validation (2026-10-15)

All 56 tests passed with no skips across PayloadProvenanceMigrationTest, WorldDictionaryMigrationTest, PlayerRelationalMigrationTest, GameRuntimeMigrationTest, GameRuntimeDataSourceConfigTest and GameRuntimeImportServiceTest against disposable PostgreSQL 14.24, using Java 25 and the Maven wrapper. Verification used offline Maven with resource copying skipped after copying the final V7 migration and catalog alongside the existing V1-V6 test resources. Tests cover upgrade/repeat migration, catalog seed identity, legacy backfill, invalid tuples, opaque-only provenance, mixed origins within one snapshot, copy/move preservation, re-encoding and rollback, deferred ready-state validation and existing importer compatibility. No live migration or game deployment was performed. The Java fixtures now verify in-memory provenance, mixed origins, reconstruction and schema-driven delta/fork propagation. Native file/network round trips and real multi-server decoding remain future engine work.

## Player-attached decode implementation

See [2026-10-15-world-dictionary-decode.md](../features/2026-10-15-world-dictionary-decode.md) for the exact-view repository/cache, native lookup hooks, nested byteData provenance, native fallback, preparation futures, cache bounds and remaining synchronous login latency. No native byte layout was changed.

## Related feature documentation

[Shared world dictionary: backend migration handoff](../features/2026-10-15-world-dictionary-schema.md).
