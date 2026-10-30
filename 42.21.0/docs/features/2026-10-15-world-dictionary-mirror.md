# V6/V7 dictionary publication and change listener — 42.21.0

Implemented scope: mirror the completed vanilla dictionary into the actual backend V6/V7 `player_rel.world_dictionary_*` tables, then observe other servers' ready-view metadata using PostgreSQL LISTEN/NOTIFY. Vanilla WorldDictionary.bin, local allocation and client packets remain unchanged. Untagged decoding retains native lookup behavior. Exact source views are now cached for player-attached payload decoding; see [decode integration](2026-10-15-world-dictionary-decode.md).

## Startup publication

`WorldDictionary.init()` calls `ApocBRWorldDictionaryMirror.afterRebuild(data)` immediately after successful native `data.save()` and before native backup cleanup. The normal world-start and soft-reset callers both cross this hook. The exact 42.21.0 WorldDictionary baseline was copied with the repository baseline tool in local commit `ddd49ff`; no push or deployment was performed.

`ApocBRWorldDictionarySnapshot` parses the freshly generated native client cache into detached typed records, using the target-version DictionaryInfo/DictionaryStringInfo/DictionaryScriptInfo native field readers and ByteBlock boundaries. Core info availability is taken from the current live dictionary because the native info encoder does not serialize `isLoaded`. This avoids exposing private script/string registry maps or changing how vanilla compiles the dictionary. It does not store that binary cache in PostgreSQL. Strings/flags, ordered overrides, empty registers, counters and signed long script hashes become typed V6 columns.

The snapshot is bounded by the native 5 MiB cache size, 128 registers and 200,000 entries. The Generic string descriptor is pinned to the target engine's maxLen=64; unknown string-register descriptors fail capture rather than guessing a length. Other game versions/descriptors require an explicit adapter update.

`ApocBRWorldDictionaryDatabase.publish` takes a caller-owned transactional lease, provisions the namespace as needed, locks its namespace row, and validates the existing mirrored allocation history. It then compares the completed snapshot with the latest ready view at the namespace's current revision for the same server/save. Identical snapshots reuse that view and revision, including after a process restart: no revision, view, state, override or journal rows are added, and no change notification is sent. A `REUSED` log identifies this path. Comparison reads use the publication query timeout and the same connection/transaction, including with poolSize=1.

Reuse requires matching identities, register descriptors, global and view counters, availability/mod flags, script hashes, and ordered overrides. Registry/entry iteration order does not matter; empty registers and nullable fields remain significant. Any difference publishes a new revision and immutable ready view through the existing nine-table transaction. Comparison read failures abort startup rather than assuming equality. Revisions remain monotonic for actual publications. No schema migration is needed; existing duplicate views remain intact for historical payload decoding. Payload migration and historical-view collection are separate work.

Identity rows are only inserted for newly seen IDs; they are never rewritten. A changed stable key/kind/module/name under an existing ID, lower counter, missing historical identity/register or changed register descriptor rolls back that publication. The mirror then automatically publishes the rebuilt dictionary in a fresh namespace, allowing a new save to start with the same server/save configuration. Old namespaces and views remain intact for decoding stored payloads. The same namespace cannot be used by another server/save key. This is a mirror of one local allocation history, not centralized ID allocation for multiple independent vanilla worlds.

V6's publication trigger validates completeness, counters and override ordering before a view becomes ready. The connector issues `pg_notify('apocbr_dictionary_changed', event)` in that same transaction. Other SQL or validation failures roll back publication and are propagated through the native dictionary startup error path; they never trigger namespace rollover. Native file persistence has already succeeded, but the configured mirrored server does not continue startup with an unmirrored dictionary. No runtime DDL or migration execution is performed.

## Configuration

The feature is active on dedicated servers with saving enabled when:

```text
-Dapocbr.postgres.enabled=true
-Dapocbr.postgres.dictionary.enabled=true
-Dapocbr.postgres.serverId=3
```

`dictionary.enabled` defaults to true under the master PostgreSQL flag. Apply backend V6 and V7 before starting with this feature enabled. Set it false to retain the prior native-only behavior.

| Property | Meaning / default |
| --- | --- |
| `apocbr.postgres.serverId` | Required positive `player_rel.server.id`: 1=apoc-1, 2=apoc-2, 3=apoc-dev. No key/name fallback or catalog-order allocation. Shared by dictionary publication and payload encoders. |
| `apocbr.postgres.dictionary.saveKey` | Save/allocation-history label; defaults to Core.gameSaveWorld. |
| `apocbr.postgres.dictionary.namespaceId` | Optional initial namespace UUID. Otherwise deterministically derived from the numeric server ID/save-key pair. History conflicts automatically select a replacement saved with the world. |

Save keys must have 1..128 characters. A fresh save may reuse the configured namespace UUID and save key: incompatible history automatically selects a new namespace. `ApocBRWorldDictionaryNamespace.txt` in the world save folder stores the configured and replacement UUIDs. It is written atomically before the retry, so restarts and failed publication retries reuse the replacement. Keep this file with save backups. Changing the configured namespace ignores a marker for a different configured UUID. A rollover logs `HISTORY_REPLACED`; `PUBLISHED` reports the effective namespace. No database migration or manual dictionary deletion is required. These keys identify origin; they are not authentication credentials or player account identities. V7 supplies the immutable server registry. Java inserts only `server_id`; the V7 binding trigger fills the compatibility `server_key` on runtime views. Numeric IDs are used in ownership checks, discovery and notification JSON. The old `apocbr.postgres.dictionary.serverKey` property is retired.

The default namespace seed now uses `vanilla-dictionary:server-id:<id>:<save>`. To continue an existing V6 allocation history, configure `apocbr.postgres.dictionary.namespaceId` explicitly to its existing UUID before switching properties. This avoids creating a separate mirror namespace for the same local save. A new allocation history receives a fresh namespace automatically when it conflicts with the existing mirror.

Pool configuration and credentials are the existing connector settings. Table names are fully qualified with `player_rel`; pooled sessions still use their existing public default schema.

## Listener and recovery

Updated 2026-10-29: foreign discovery joins namespace metadata and accepts only
42.21.0/world249/format1, matching the exact-view loader. A shared database may
retain development's 42.22.0 dictionaries without sending the 42.21 listener
into preparation failures and one-second reconnect retries. Unsupported views
are filtered before the discovery budget is applied. Explicit payload references
to unsupported dictionaries still fail preparation; this does not enable
42.22-to-42.21 character or binary-payload compatibility.

After successful publication, `ApocBR-dictionary-listener` starts as a daemon. It owns one extra physical PostgreSQL connection built from the same configured datasource, outside Hikari's query-pool limit, and uses auto-commit for LISTEN. Query operations borrow separate short-lived transactional pool leases, so even poolSize=1 does not deadlock behind a permanently leased listener connection. Include this additional connection per engine when sizing PostgreSQL connection limits.

The listener commits its subscription first, then discovers current foreign ready heads. It repeats discovery after notifications, after every reconnect, and once per 60 seconds as a missed-notification/external-writer recovery check. Notification waiting uses pgJDBC `getNotifications(1000)`; it does not query dictionary tables each second. Event payloads are wake-up hints; canonical metadata is read from the V6/V7 tables. Multiple pending notifications returned together cause one reconciliation.

Foreign discovery returns at most 256 newest ready heads, one per namespace/server/save tuple, excluding the local numeric server ID and unmapped historical views. It atomically replaces an immutable metadata list only after a successful query. Failed refreshes retain the last known metadata. At the budget limit, discovery fails visibly instead of silently truncating; larger fleets need an explicit bound/filter update. A reconnect uses a one-second backoff, and repeated failure logs are limited to one per 30 seconds. The listener now prepares exact foreign views in its worker before exposing new heads. Historical payload views are also prepared during relational login; readiness and cache bounds are detailed in [decode integration](2026-10-15-world-dictionary-decode.md).

`localView()` exposes the local committed view; `foreignViews()` exposes discovered metadata. These are process-memory observations, not permission to decode a payload using an arbitrary latest view. Pool shutdown invokes listener shutdown, interrupts the daemon, closes its dedicated session, and prevents an old listener generation from publishing metadata after replacement. Existing query/socket timeouts bound any in-flight work.

External dictionary writers must emit the same notification after ready publication if they need immediate discovery; V6 itself does not install a notification trigger. The minute reconciliation still recovers such writes. Decoding uses each attributed payload's explicit source namespace/view, rather than notification delivery order or a decode-failure-triggered refresh.

The implementation follows the [PostgreSQL LISTEN startup ordering](https://www.postgresql.org/docs/current/sql-listen.html), [transactional NOTIFY semantics](https://www.postgresql.org/docs/current/sql-notify.html) and [pgJDBC dedicated notification-session behavior](https://jdbc.postgresql.org/documentation/server-prepare/#listen--notify).

## V7 payload propagation

After the ready-view transaction commits, the mirror publishes an immutable `ApocBRPayloadProvenance(serverId,namespaceId,viewId)` in memory. Native player/vehicle encoding captures it before serialization and carries it with the detached queue buffer. SQL INSERT/UPDATE binds bytes and all three provenance fields together; changing the current view while a save waits in the queue does not change the queued origin. Native profile/vehicle SQL readers return the tuple. Existing conversion/account-only mutations leave it alone; raw bytes supplied without a verified origin explicitly write NULLs rather than retaining a stale previous tuple.

The new mannequin replacement uses the exact 42.21.0 decompiled baseline, copied separately in local commit `332a91d`, with a compile-only rename of a conflicting decompiler variable. `InventoryItem.storeInByteData` and `IsoMannequin.setCustomSettingsToItem` attach the encoder view and known payload version 249 without changing native byte layouts. `ApocBRPayloadProvenance` keeps a weak identity association with the original ByteBuffer/byte[]; mutable ByteBuffer content hashes are never map keys. Detached copies retain that immutable metadata. Capture writes it to the V7 `value_node` columns; schema-driven graph mutations, JDBC reload and snapshot forks include the columns. Reconstruction restores the association for the next capture. Dictionary UUIDs are projected literally by the login SELECT, while object graph references remain integer ordinals.

Existing unknown byteData stays unknown; the configured local ID is never inferred for bytes merely read/copied from SQL, SQLite, packets or world files. With publication disabled or before the first successful publication, no local origin is available. Versions known at actual encoding may still be recorded without an origin. Mod code replacing/copying opaque buffers must explicitly preserve metadata using `copy`, or provide verified metadata using `remember`; arbitrary native binary round trips do not transport this in-memory sidecar. An outer player/vehicle re-encoding also does not transcode retained foreign embedded buffers.

Player-attached native decoders now select the exact cached source view when provenance is present, and the native dictionary when it is absent. Profile packets and native vehicle-ID collisions remain separate work. See [decode integration](2026-10-15-world-dictionary-decode.md) for covered entry points, nested contexts and latency.

## Verification

- `python tools/test_postgres_world_dictionary.py`: native encoder-to-typed snapshot capture, detached state, empty registers, negative script hash, malformed framing rejection; JDBC publication/notification ordering, unchanged restart reuse without added snapshots/notifications, order-independent comparison, changes to mutable fields/override order/counters/identities, missing-state and comparison-failure handling, rollback/lease cleanup and history-conflict rejection; listener initial discovery, notification wake-up, idle waits without SELECTs, reconnection recovery, immutable metadata and shutdown.
- `test_postgres_connector.py`, `test_postgres_players.py`, `test_postgres_vehicles.py`: provenance binding/read/copy, unknown replacements, queue view changes, retry/rollback and native adapters.
- `test_postgres_player_relational.py`, `test_postgres_player_reconstruction.py`: mixed opaque origins, provenance-only deltas, detached metadata, fork columns, external UUID projection and reconstruction.
- Target script: `./42.21.0/patchApocalipseBr.ps1 -DryRun`, using the actual 42.21.0 projectzomboid.jar and bundled JDBC/Hikari libraries.

The six PostgreSQL fixtures above passed. The final target-JAR dry run compiled 130 Java sources into 478 class files; no deployment occurred. `git diff --check` passed.

No live PostgreSQL migration or publication was executed. The backend records its isolated PostgreSQL migration verification in the provenance handoff. These Java writers still require a migrated-database integration run followed by a two-server test covering startup, simultaneous publication, notification/disconnect recovery, namespace conflicts, soft reset and shutdown. No deployment was performed.

## Diagnosing failed publication

A failed publication rolls back the whole transaction; zero visible dictionary rows after a startup error is expected. The exception now includes its operation stage, SQLState, numeric server ID, namespace/view UUIDs and snapshot counts. Structured PostgreSQL diagnostics include the server message, schema/table/column/constraint and error position. Failed JDBC batches include their nested server errors. Raw driver/batch messages, bound INSERT values, PostgreSQL DETAIL/WHERE and raw exception cause messages are omitted; credential-like text in structured messages is redacted. Application-owned history-validation reasons are retained explicitly.

Examples: `stage=VERIFY_NAMESPACE_OWNER; SQLState=42703` points at a missing V7 server_id column; `stage=CREATE_RUNTIME_VIEW; SQLState=23503` with `Unknown dictionary server ID` points at the configured catalog ID; `stage=VALIDATE_HISTORY` retains the specific namespace/history conflict. Constraint failures name their constraint when the driver supplies it. Connection acquisition failures are also classified. Keep the full new exception message for diagnosis rather than only its Java stack frames. The old generic exception contains insufficient information to identify which database operation failed.

A read-only audit of the reported local servertest WorldDictionary.bin decoded seven registers and 6,856 entries and found no violations in the checked typed-value ID/counter ranges, mod/type fields, script-version presence or override uniqueness. This checks the file representation only; it does not establish the deployed database schema, privileges, catalog IDs or trigger outcome. No live database was queried during that audit.

## Dictionary publication timeout correction

`stage=PUBLISH_READY_VIEW; SQLState=08006` means the connection was lost while awaiting the UPDATE that marks the runtime view ready. All preceding insert stages had returned; the publication trigger validates the completed dictionary at this UPDATE, and NOTIFY has not yet run. The old shared socket read timeout was 3 seconds, shorter than the dictionary's 5-second query timeout. A client-side socket timeout is consistent with a delay here, no structured PostgreSQL server error, and server-side connection-reset/open-transaction EOF logs; SQLState alone does not prove that specific cause.

Dictionary write/publication statements now use `apocbr.postgres.dictionary.publicationTimeoutSeconds`, default 30 seconds, accepted range 5..300. This is a per-statement/batch budget, not a whole-publication deadline. Dictionary history/read/discovery queries retain 5 seconds and the namespace lock timeout remains 2 seconds. A dictionary pool lease sets its socket/network timeout to the configured publication timeout plus a 15-second response/cancellation margin (45 seconds by default). Hikari restores the original timeout when the lease is returned; other player/vehicle leases retain the 3-second baseline. The dedicated listener still uses its original physical-session configuration.

Failure diagnostics now include total/stage elapsed milliseconds, the publication query budget, bounded nested exception class names and controlled transport categories such as `SOCKET_READ_TIMEOUT`, `UNEXPECTED_EOF` or `SOCKET_FAILURE`. No raw driver/I/O cause text is logged. A SocketTimeoutException cause confirms a socket-read timeout; other 08006 failures remain distinguishable. This aligns the client timeout budgets without disabling publication validation or bypassing database constraints. A publication that still exceeds its query budget needs database-side trigger/query-plan measurement.

Verification covers the real Hikari lease reset, configurable/bounded publication timeouts, transport-cause redaction and a ready-view timeout that rolls back before notification. No live PostgreSQL publication or deployment was performed.

Related report: [World dictionary PostgreSQL integration map — 42.21.0](../reports/2026-10-15-world-dictionary-integration-map.md).
