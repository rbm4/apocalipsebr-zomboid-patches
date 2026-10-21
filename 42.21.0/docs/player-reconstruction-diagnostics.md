# PostgreSQL character reconstruction diagnostics (42.21.0)

Reconstruction logs are always emitted on profile/player loads under
`[ApocBR][Reconstruction]`. No extra JVM property is required. A `request` UUID
correlates the outer `CLIENT_PROFILE` or `PLAYER_CONNECT` operation with its
`READ_PROFILE` and `RESTORE_PLAYER` phases. Each event includes the slot, stage
and elapsed time within that phase. These are diagnostics; validation, restoration,
login policy and storage formats are unchanged.

Follow one request from `BEGIN` to `PAYLOAD_READY` (client profile) or
`PLAYER_READY` (server registration):

- `LOOKUP` records the requested world/save label and identity mode. `identityRef`
  is a deterministic UUID derived from the identity, allowing correlation without
  logging the username or Steam ID. `NO_PROFILE` means the exact world, identity
  mode, identity and slot query found no selected relational head. A source server
  being online does not make a head stored under a different lookup tuple match.
- `READ_BLOCKED` means the existing persistence admission/pending-player guard
  rejected the read. The event includes current connector availability.
- `HEAD` records snapshot/character/dataset UUIDs, completion status, schema,
  codec and source world version. `GRAPH`, `ROWS_READ` and `COUNT` record graph
  size and expected/actual object, item, container and edge counts.
- `DICTIONARY_PREPARE` and `DICTIONARY_READY` identify the exact source server,
  namespace and view for each distinct attributed opaque value. Zero views is
  valid for graphs with no attributed opaque values. Reconstruction builds normal
  items from stable names and the local scripts; an available source dictionary
  cannot supply a mod/item definition missing from the destination server.
- Restoration stages cover ownership validation, player creation, scalar fields,
  perks, sequences, health, visuals, inventory, equipment, components, finalization
  and table coverage. `FAILED` includes the last table/field and item/component
  context where available, controlled validation reasons, exception/cause types,
  SQLStates and up to six source frames for each of four causes. Missing or changed
  item definitions also emit `ITEM_TYPE_MISMATCH` with full type and runtime classes.
- `MISSING_REGISTRY` names the registry class and bounded, sanitized definition key
  that could not resolve. For traits, `FAILED` also reports `table=character_trait`,
  `field=trait_key` and `reason=Missing registry key`. Restore the defining mod and
  its registry initialization on the destination server, or perform an explicit
  data migration if that definition was intentionally retired. Reconstruction
  continues to reject unavailable definitions rather than silently dropping them.
- `RESTORED` confirms engine reconstruction. `ENCODE_GROW` is a normal client
  buffer resize; `PAYLOAD_READY` records encoded byte count and destination
  dictionary provenance. `REGISTER_PLAYER`/`PLAYER_READY` cover server registration.

Dictionary-specific failures additionally emit `[ApocBR][Dictionary]` events:
`SOURCE_READ_FAILED` identifies the source-view query/registry/override/entry stage,
`PREPARE_FAILED` identifies the exact view that could not enter the cache, and
`DECODE_FAILED` identifies an unresolved ID, missing local definition or script
version mismatch. Correlate them by source server, namespace and view with the
reconstruction request immediately around them. Dictionary preparation is skipped
for already prepared views, but the reconstruction still emits `DICTIONARY_READY`.

Raw SQL, driver messages, credentials, arbitrary row values, modData keys and opaque
payload bytes are omitted. Stage/summary events are emitted once per phase; there
is no successful per-item log stream. Include the complete request and related
dictionary events when reporting a failure after deployment.

Validation: `tools/test_postgres_player_reconstruction.py` injects missing heads,
incompatible heads, count mismatches, SQL failures, unavailable source dictionaries,
ownership failures and field restoration failures. It checks correlation, context
cleanup and omission of identities/values/driver messages. Existing player profile,
dictionary decoding and dictionary publication fixtures cover the affected callers.
Trait coverage checks both native gameplay flags and known-trait membership for
vanilla and registered mod traits, a native save/load round trip, and explicit
diagnostics when a saved trait definition is unavailable. Trait restoration uses
the native `CharacterTraits.add` path to keep both state representations consistent.

## Confirmed failure: database identity projected into InventoryItem

The supplied log for request `f07d8c39-e44c-4c8c-a94d-1e02fe29c3f8` loaded a
complete graph and passed all count and ownership checks. It then failed at
`RESTORE_INVENTORY`, `field=persistedItemId`, `subject=item:8:Base.Eraser`.
The reader adds `persisted_item_id` as a server-only UUID seed for sync registration;
it is not an InventoryItem property. Item projection now excludes that metadata
while preserving it in the detached profile for `registerPostgresPlayer`.
The regression restores native item IDs from a reader-shaped row containing the
UUID and verifies that the UUID remains in the row. No data migration is required.

## New-character persistence handoff (including respawn)

`READ_BLOCKED` immediately after `CreatePlayerPacket` can mean that the new life
was sent to the client before its asynchronous relational capture committed.
Previously the following player-connect reloaded the slot and returned `null`
while its save was pending, producing the vanilla character-profile load error.

With PostgreSQL relational journaling enabled, creation now uses
`ApocBRCreatedPlayerHandoff` on the server simulation thread. It waits for the
previous slot's saves/mutations, runs vanilla creation once, and submits the
complete new-life capture. Existing journal workers perform the database work.
Only after the per-slot persistence guard clears is the normal CreatePlayer
response sent. A failed/quarantined capture never satisfies that guard.

The following connection consumes the exact committed server-created instance
once, scoped to the original connection/GUID, identity, world, and player slot.
It does not reconstruct a second player from a database head that may already
have another pending write. The original tracking session remains bound to the
new life. Returning-character logins still use the existing reconstruction path.
The new player is detached from square/cell simulation while waiting and restored
when attached, so zombies cannot attack an unowned pending character.

Follow `[ApocBR][CreatedPlayer]` events by `request` and `slot`:
`WAIT_OLD_SAVE`, `WAIT_NEW_SAVE`, `RESPONSE_SENT`, then `ATTACHED`.
Disconnects or changed connection identities produce `CANCELLED`; capture
rejection and exceptions fail closed. At most 128 slots are retained. The total
wait, including claiming the response, defaults to 30 seconds; adjust
`-Dapocbr.postgres.createdPlayerTimeoutMillis=<milliseconds>` within 1–120 seconds
for slower test environments. Expiry produces `TIMEOUT` and denies the connection.
The new flow does not change packet formats, storage schemas, or non-journal
creation behavior, and does not deploy automatically.

Regression: `py -3 tools/test_postgres_created_player_handoff.py` exercises the
production coordinator with deterministic persistence/engine boundaries,
including old/new save barriers, delayed completion, one-shot attachment,
duplicate requests, disconnects, identity/world changes, timeouts, capture
rejection, secondary slots/Steam coop, and queue capacity. Existing player and
journal resilience fixtures cover the underlying save/read and replay paths.
Live verification still requires dying and creating a replacement character,
disconnecting during creation, and testing delayed/unavailable PostgreSQL.
