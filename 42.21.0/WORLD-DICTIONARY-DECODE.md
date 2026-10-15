# Player-attached payload dictionary selection — 42.21.0

Native byte formats remain unchanged. Relational item reconstruction restores each opaque leaf's `source_server_id`, `source_dictionary_namespace_id` and `source_dictionary_view_id` as buffer metadata. The bytes are decoded when their native consumer needs them; reconstruction itself does not eagerly instantiate corpses or world objects contained in items.

## Selection and lifetime

`ApocBRWorldDictionaryDecode.enter(input)` selects an immutable source view for the complete native decode call. A thread-local scope is restored on success and exceptions; nested payloads may select a different view without changing another thread's dictionary. There is no global dictionary swap and no try-A/try-B decoding.

- A payload with provenance uses the exact server/namespace/view tuple, including an older publication or older save incarnation from the same server. Server ID alone and the latest notification are insufficient.
- A payload with explicit unknown provenance uses this engine's native dictionary. This is also the default for ordinary SQLite/native data with no source context. This fallback is the requested compatibility behavior; it does not establish that arbitrary unknown foreign bytes are portable.
- Nested reads of the same native stream inherit its decode context. When `InventoryItem.load` extracts another byteData buffer from that stream, it attaches the enclosing origin for deferred decoding. An independently reconstructed opaque leaf has explicit metadata, including explicit unknown, and therefore overrides the enclosing context.
- A known origin that is not prepared, a missing source dictionary ID, unavailable required local item/entity definition, or incompatible script definition fails explicitly. It never falls back to native IDs or queries SQL from a decoder. Source lookup failures remain attached to the scope so native item-load exception handling cannot silently turn a failed foreign decode into a successful partial result. Original opaque bytes are retained.

## Native maintenance points

| Boundary / lookup | Behavior |
| --- | --- |
| `InventoryItem.loadItem` (full factory overload) | Scopes item creation and the complete virtual subtype loader, including nested bags, equipment and attachments. |
| `InventoryItem.load` | Scopes direct base-item reads and carries the enclosing origin onto extracted byteData. |
| `IsoObject.load(ByteBuffer,int)` | Scopes the complete virtual object loader. This covers item byteData consumers, including corpse, mannequin and other world-object restoration through this API. |
| `IsoDeadBody.load` / `IsoMannequin.load` (three arguments) | Also scope direct calls to their concrete loaders. |
| `WorldDictionary` ID-to-item/entity/sprite/object accessors | Resolve through the active source view; native getters run when no source view is selected. Factory `ItemInfo`/`EntityInfo` resolves source IDs to stable types and uses definitions installed on the receiving engine. |
| `StringDictionary.StringRegister` numeric getters / `loadString` | Use the source named register; standalone tagged string reads establish their own scope. |
| `ScriptsDictionary.ScriptRegister` numeric getters / `loadScript` | Resolve source IDs to stable names, then use the receiving engine's script only when its published version hash matches. Standalone tagged reads establish their own scope. |

Name-to-ID allocation and serialization remain native, including when code invokes them inside a decode scope. Materialized items retain receiving-engine script/registry identities. Re-encoding a materialized object produces local dictionary IDs. Copying an opaque child preserves its source and is not a transcode.

The StringDictionary and ScriptsDictionary replacements use exact 42.21.0 decompiled baselines, committed separately with the repository baseline tool (`7ed78e7`, `795f5af`). Existing patches to the other engine classes remain in place.

## Preparation and latency

`ApocBRWorldDictionaryDatabase.load(origin)` reads a ready compatible view using all three origin fields. Registry counters come from that view's registry-state rows. Identity rows are filtered by its identity revision; availability/mod flags, overrides and script hashes come from that exact view. Four bounded JDBC queries load header, registers, overrides and entries. Dictionary leases now have a socket timeout longer than their query timeout; see the [publication timeout correction](WORLD-DICTIONARY-MIRROR.md#dictionary-publication-timeout-correction). A failed read rolls back and does not expose a partial dictionary.

The process cache contains immutable views and uses one future per origin to coalesce concurrent preparation. It is bounded to 256 views and 1,000,000 identity entries in total. It does not evict a view that an already loaded opaque item may still need; exhausting the budget fails preparation visibly. A process restart clears the cache. These bounds need an explicit capacity change for larger fleets/histories.

The local completed snapshot is installed after publication. The existing LISTEN worker prepares newly discovered foreign heads before exposing their updated metadata. Historical views referenced by a login are prepared by `ApocBRPlayerRelationalReader.load` after returning its graph connection, which also avoids deadlock with poolSize=1. Each distinct origin is prepared once, rather than once per item. All-unknown graphs need no dictionary SELECTs.

`Reader.loadAsync(..., executor)` includes both graph fetching and dictionary preparation on the supplied I/O executor. `ApocBRWorldDictionaryDecode.prepareAsync(origins, executor)` exposes the same preparation pattern for later consumers. Completion means native decode can use memory-only lookups; decoding does not wait on an unfinished future. Failed preparation can be retried before admission without consuming the bytes.

The existing synchronous `ServerPlayerDB` login entry point still invokes `Reader.load` synchronously. A cold historical-view fetch there can add login latency. Changing that engine admission contract to consume the existing future remains separate work; the LISTEN cache does not make that caller asynchronous.

## Limits and verification

This implements dictionary selection for player-attached native payloads carrying provenance. It does not change the client profile packet, carry sidecars through arbitrary native world files/network packets, translate independent registries such as recorded media, reconcile different installed mods or solve vehicle instance-ID collisions. Native compatibility and script version checks remain enforced. A missing sidecar follows the explicitly requested native fallback.

`tools/test_postgres_dictionary_decode.py` exercises actual patched dictionary getters/register loaders and the JDBC repository: colliding IDs across servers, native encoding IDs, nested explicit/inherited contexts, thread isolation, script mismatch rejection, exception cleanup, sticky swallowed errors, exact ready-view selection, historical revision filtering, rollback, sanitized failures, asynchronous preparation, in-flight rejection and zero-query warm decoding. Existing player/reconstruction/dictionary fixtures cover their integration; the target `patchApocalipseBr.ps1 -DryRun` checks all sources against the actual game JAR. Full corpse/mannequin gameplay round trips and multi-server login still require a running server with the same installed definitions.

Validation completed: the decode, dictionary publication/listener, player routing, reconstruction, and relational capture/mutation/worker fixtures passed. The final game-JAR dry run compiled 133 sources into 490 classes. No deployment or live PostgreSQL queries were performed.
