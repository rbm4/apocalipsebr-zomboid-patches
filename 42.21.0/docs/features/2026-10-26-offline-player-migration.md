# Offline SQLite character migration — 42.21.0 server

`ApocBRPlayerMigration` provides an opt-in, one-shot startup backfill for local
characters that have not logged in recently. It uses the existing native decode,
load-quality collection, detached snapshot, relational capture and atomic
publication code. It does not simulate a player connection or create login history.

## Configuration and rollout

Migration is disabled by default. Add this JVM argument before the Java main class:

```text
-Dapocbr.postgres.playerMigration.enabled=true
```

The existing `apocbr.postgres.enabled=true` and
`apocbr.postgres.playerRelational.enabled=true` are prerequisites. The latter
defaults to true. PostgreSQL connectivity, migrations and normal relational-sync
permissions must already work; this worker adds no tables or grants. It can run
with either SQLite or PostgreSQL gameplay player loading selected.

Run the preferred server first, inspect its drain summary and resolve failures,
then run subsequent servers. Existing complete imports are retained. Collisions
from other source datasets are logged and the incoming import is discarded;
the existing PostgreSQL character and local source rows are preserved. Disable
the flag after completing migration. Restarting with it enabled rescans the local
source and skips complete imports rather than overwriting them.

Source dataset identity follows normal login sync: explicit
`apocbr.postgres.playerRelational.sourceDatasetId`, or a UUID derived from the
current save-directory string. Keep that identity stable and distinct per source
save. Shared slot identity remains `(world, identity_mode, identity_key,
player_index)`; dedicated servers use login usernames and Steam coop uses SteamID.
The survivor display name is only a diagnostic label. Different `world` values
are distinct namespaces in the existing reader; sharing PostgreSQL alone does not
make different world names resolve to the same character.

## Targeted verification of the October 26 failures

The completed local run is analyzed in the
[October 26 validation report](../reports/2026-10-26-player-validation-local-analysis.md),
including the 84-profile outcome index and remaining diagnostic limits.
The [follow-up run](../reports/2026-10-26-player-validation-follow-up.md) verifies
76 profiles after compatibility fixes and identifies eight remaining item-creation
failures, with no remaining trait warnings in that run.

The operator's failure export contains **84 distinct character/snapshot pairs**:

| Failure | Entries | Diagnostic coverage |
| --- | ---: | --- |
| `EQUIPMENT_TARGET_MISMATCH` | 66 | Positions-only classification and up to eight expected/actual native-ID differences. |
| `KEYS_MISMATCH_item` | 11 | Missing/unexpected key counts and up to eight keys per side, including Java types to expose numeric representation differences. |
| Item factory/runtime-class validation | 6 | Reconstruction `ITEM_TYPE_MISMATCH` identifies the full type, expected class and actual class or `MISSING`; `FAILED` includes table/subject and stack context. |
| Immutable item sequence append (local ID 432) | 1 | Nested `UnsupportedOperationException` identified an immutable collection. Restoration now replaces factory defaults with a fresh mutable list for each saved item sequence. `ITEM_SEQUENCE_FAILED` reports table, field, item ordinal/native ID, full type, collection class and value class if restoration still fails. |

The cohort is embedded in
[`ApocBRPlayerValidationTargets.java`](../../src/zombie/savefile/ApocBRPlayerValidationTargets.java).
It contains only the original local audit IDs, character UUIDs and snapshot UUIDs,
with no hardcoded account or survivor names. All other characters are outside
this execution mode; the operator reports their migration and verification succeeded.
The supplied failure-only export does not independently measure that success rate.

After deploying the updated server classes, start the server with:

```text
-Dapocbr.postgres.enabled=true
-Dapocbr.postgres.playerRelational.enabled=true
-Dapocbr.postgres.playerMigration.enabled=true
-Dapocbr.postgres.playerMigration.mode=validate-failed
```

The default mode remains `migrate`; unknown mode names refuse startup of this
worker. `validate-failed` never enters the SQLite backfill loop, reads local
player/account databases, checks last-login recency, or publishes player data.
It resolves each allowlisted active character in the current world using a
parameterized PostgreSQL query, then uses the normal reader/reconstructor,
detached capture, client serializer and verification assertions. It runs once
per startup, sequentially, with one second between terminal outcomes.

The logged snapshot must still be the active head. A changed head reports
`SNAPSHOT_CHANGED` and `actualSnapshot`, without reconstructing a different
snapshot. An inactive, missing or different-world character reports
`VALIDATION_TARGET_NOT_ACTIVE`; incompatible identity modes report
`VALIDATION_TARGET_IDENTITY_DENIED`. The original local ID is an audit label,
never a PostgreSQL lookup key. Because the exact snapshot is pinned, validation
does not require the current machine's SQLite source dataset ID to match the
old import. Existing dictionary/completeness checks remain in force.

Online/connecting accounts are skipped on the server thread and login admission
is reserved through reconstruction as in ordinary migration. A skipped target
is not verified; it may be retried on a later startup. Database/lookup failures
are isolated per target as `VALIDATION_TARGET_FAILED`; reconstruction/comparison
failures retain the normal `VERIFICATION_FAILED` diagnostics. Success logs
`VERIFIED`. The final summary must have `migrated=0`; `verified`,
`verificationFailed`, `skipped` and `failed` distinguish all outcomes. This mode
does not repair, delete, quarantine or overwrite stored player profiles.

Collect both migration and reconstruction logs for diagnosis; the item type and
sequence details are emitted by reconstruction, with request UUIDs and head
metadata for correlation:

```bash
sudo journalctl --since '10 minutes ago' --no-pager -o cat \
  | grep -E '\[ApocBR\]\[(PlayerMigration|Reconstruction)\]' \
  | tee player-validation-diagnostics.log
```

Disable `apocbr.postgres.playerMigration.enabled` after collecting the results.
Do not leave it enabled with mode `migrate`, which would resume the ordinary scan.

### Reconstruction compatibility fixes (October 26)

The follow-up to the local validation report implements these policies in both
normal relational login and temporary validator reconstruction:

- A saved trait whose `CharacterTrait` definition no longer exists is omitted
  from the reconstructed player and logs `MISSING_TRAIT_SKIPPED; traitKey=...`.
  Valid traits still restore their flags and known-trait entries. This exception
  applies only to unavailable traits, not missing items, body locations or other
  registries. Unexpected lookup/codec exceptions still fail reconstruction.
- Hand items restore by assigning `leftHandItem`/`rightHandItem`, following exact
  42.21.0 `IsoPlayer.load`. Deserialization does not fire `OnEquipPrimary` or
  `OnEquipSecondary`, so mod callbacks cannot replace saved magazine attachments
  or reapply weapon modifiers. Existing final weapon-variable/model setup remains.
- Equipment comparison sorts each kind by its saved position and compares compact
  ranks for worn/attached rows. Slot identity, native item ID and relative retained
  order remain strict. Negative/duplicate positions and duplicate slots fail.
  Hand positions retain their fixed primary/secondary meaning. Database positions
  are not rewritten; gaps alone no longer cause verification failure.
- Dynamic `Base.<worldSprite>` furniture with saved `Moveable` class and matching
  sprite state is instantiated through `Base.Moveable` and native sprite setup
  before saved fields are applied. Identity, weight/light state and ownership
  remain preserved. Invalid sprites log `MOVEABLE_RECONSTRUCTION_FAILED` and fail;
  unrelated missing item definitions are not replaced with generic furniture.

Verification carries the exact trait keys skipped during that reconstruction
to its comparison. Only those source trait rows may be absent; unrecorded trait
loss or extra traits still fail. `TRAITS_SKIPPED_MISSING=N` reports the permitted
omission count after the trait assertion passes. Validation remains read-only;
it does not delete trait rows from retained PostgreSQL snapshots. Normal future
gameplay persistence can publish the reconstructed character's current traits.

During verification, selected reconstruction events are now copied into the
append-only `player-migration.log` as `RECONSTRUCTION_DIAGNOSTIC`, with the local
ID, character/snapshot UUIDs and reconstruction request context. This includes
missing-trait skips, item-type mismatches, missing registry definitions, item-list
failures, invalid furniture sprites and the bounded `FAILED` context/stack. Copying works on success and on
exception, independently of whether the general server DebugLog file is enabled.
The server thread collects detached strings; the migration worker performs the
file writes after future completion. Per profile, at most 32 detail messages of
4096 characters each are retained, followed by an explicit truncation marker.
Exact skipped-trait keys for comparison are retained independently of this log
cap. Ordinary login still emits these warnings through the normal reconstruction
logger. No credentials, arbitrary exception messages or opaque payloads are copied.

## Selection and recent logins

The source is `<currentSaveDir>/players.db`, table `networkPlayers`. The worker
captures the maximum local ID at startup and walks rows in ID order, one at a
time, using short read-only SQLite connections. It does not retain all names or
character blobs in memory, hold a read transaction throughout migration, create
missing source files, or follow newly added rows indefinitely. A row for another
world or an invalid identity/slot is denied and logged.

For each username it reads `whitelist.lastConnection` from the **local**
`<cachedir>/db/<servername>.db`, even when gameplay account access uses PostgreSQL.
It interprets the native `yyyy-MM-dd HH:mm:ss` value in the server JVM timezone.
A login within the preceding seven days, including exactly seven days, skips
every character slot for that account. This implements the operator's assumption
that recently logged-in accounts have already synced; it does not verify a
PostgreSQL snapshot for those skipped rows. The field is account-level and updates
during login admission, before character acceptance. See
[successful-login history](2026-10-21-player-login-origin.md) for the separate
accepted-connection audit.

Missing/empty/malformed dates, duplicate account rows, future dates and ambiguous
or nonexistent local times are treated as unknown and remain eligible. An
unreadable/missing account database is also logged and treated as unknown. These
cases do not stop other characters from migrating. Survival time and migration
time are never substituted for historical login dates.

## Threading, exclusion and persistence

The first server-loop update starts a dedicated daemon worker, including on an
empty or paused server. SQLite, PostgreSQL, hashing, graph projection and audit
file writes run on that worker. Native `IsoPlayer` construction, binary loading
and snapshot copying run through a single pending continuation on the server
thread. One large character can therefore still occupy a server tick during
native decoding/copying; asynchronous persistence does not make native engine
objects safe to construct on a JDBC thread.

Before loading, the server thread checks live players, connection usernames,
coop SteamIDs and login attempts registered before asynchronous authentication.
Online or connecting accounts are skipped. It reserves the eligible account
until persistence and reconstruction verification finish. A new matching login during that short
reservation receives the existing temporary database access denial and must retry;
unrelated logins continue. Reservations are local to this server, not distributed
session locks across servers. Complete-import publication uses the relational
per-slot transaction lock, shared across source datasets, and checks the head
again before committing. An existing foreign source is never overwritten.

The worker does not register the temporary player for live mutation tracking or
call the native login load method's destructive error branch. Incomplete captures
are rejected before publication. A failed commit, bad source data or collision is
logged and processing advances; there is no automatic retry loop for a failed
row during that run. Atomic publication plus repeatable source reads allow manual
retry on a later restart. The worker waits for each terminal outcome, then waits
**one fixed second** before selecting the next character, including after skips,
collisions and failures. Shutdown interrupts pending continuations/delays and
joins the worker before persistence pools drain or close.

## Post-commit reconstruction verification

After each successful commit the worker reads the active relational profile using
the normal login reader. It requires the character, snapshot and source dataset
IDs to match the expected import. A changed head is a verification failure;
the worker does not certify a different character or overwrite the competing head.
The reader also performs its normal completeness, graph-count and dictionary checks.

The server thread reconstructs a temporary player with the login reconstructor,
including its ownership, reference and table-coverage checks. It copies the
reconstructed state and encodes a client profile with the same bounded serializer
used by ordinary login. No connection is created, no login audit is recorded and
no live mutation tracker is registered. The worker then projects the detached
reconstructed state and requires a complete graph before comparing it to the
database profile:

- Inventory item, container, equipment and item-location row counts.
- Equipment target identity using native item IDs across reader ordinals and UUIDs.
- Item types, condition, uses and ammunition; trait keys and stat values.
- Perk levels, XP, boosts and multipliers; body-part health and core wound flags.
- Death state, survival hours, kill counts, total XP and XP levels.

These are explicit runtime checks, independent of JVM `-ea`. Floating-point
comparisons allow a maximum of `1e-5` absolute or `1e-6` relative difference to
accommodate float/JSON/JDBC representation. This is a selected consistency check,
not a byte-for-byte comparison or proof that every mod field survives reconstruction.

Already-complete imports encountered during the scan also undergo verification,
allowing a later run to retry reconstruction without importing the character again.
The seven-day and online/connecting skip rules remain in force. Verification checks
online status before reconstruction and retains the account reservation until
the outcome is logged. Recent accounts are not covered by this migration pass.

Reconstruction, serialization and comparison exceptions are expected per-character
outcomes: the worker logs them, retains both the committed PostgreSQL snapshot and
the SQLite source, releases the reservation and advances after the fixed interval.
There is no deletion, rollback of an already committed import, or automatic repair.
An interrupted verification is logged as `STOPPED`, rather than certified as success.

## Audit

The append-only UTF-8 file is `<currentSaveDir>/player-migration.log`, mirrored to
the normal server log. Each run logs startup settings and dataset identity,
selection, recent-login decisions, loading, capture completion, persistence,
denials, collisions and failures. Character records include local row ID, login
username, survivor display name and slot. Control characters and field separators
in names are sanitized. Credentials, raw player bytes and raw SQL exception
messages are not logged.

`COLLISION_DISCARDED` identifies incoming characters rejected for a foreign source
dataset. `FAILED` reports the exception type and SQLState when available.
Persistence failures also include world, identity mode and source dataset, bounded
stack frames, SQL vendor code and PostgreSQL schema/table/column/constraint
identifiers when supplied by the driver. Both JDBC `nextException` and cause
chains are inspected with cycle detection and an eight-entry limit per SQL
diagnostic; a capped chain reports `sqlChainTruncated=true`. These details also
apply to SQL verification failures. In particular, `23505` plus the constraint
name distinguishes slot/account uniqueness from an inventory or snapshot key
conflict; it is not automatically classified as `COLLISION_DISCARDED`.
Raw SQL, driver messages, PostgreSQL DETAIL/WHERE and duplicate-key values are
excluded. Diagnostics are appended to `player-migration.log` and mirrored to the
server log without changing collision handling, transaction behavior or retries.
`VERIFICATION_STARTED`, `ASSERTION_PASSED`, `VERIFIED` and `VERIFICATION_FAILED`
record the character and expected snapshot IDs. Verification failures include
their stage, typed assertion code when applicable, exception types, nested causes
and bounded stack frames. Arbitrary exception messages are omitted; normal
reconstruction diagnostics also remain in the server log with detailed table/field
context. `migrated` counts commits even if their later verification fails.
As of 2026-10-26, `EQUIPMENT_TARGET_MISMATCH` also logs
`equipmentPositionsOnly`, `equipmentDifferenceCount` and up to eight
`equipmentDiffN` entries. Each entry identifies `kind/slot@position` and the
expected/actual native item IDs (`NONE` means that comparison key is absent).
Slot tokens are sanitized and capped at 160 characters. Following the compatibility
fix, worn/attached positions in this diagnostic are normalized ranks rather than
raw sparse offsets. A relative order change still fails.
`equipmentPositionsOnly=true` means the multisets of `(kind, slot, native_id)`
are equal when positions are excluded. It does not certify later item/state
assertions, which have not run yet. Actual slot or target changes still fail.

For example, the reported 2026-10-26 failure for local ID 239 reached
`COMPARE_PERSISTED_STATE`: profile identity, native reconstruction, snapshot
copying, client encoding, complete recapture and the four row counts succeeded.
The old log alone cannot distinguish a reordered equipment list from a changed
slot or target. Native `WornItems.setItem` and `AttachedItems.setItem` insert in
location-group order; hand setters also trigger Lua equip events, so the exact
divergence must be measured before choosing a repair. Read the retained expected
equipment with a read-only query (the example snapshot may since have been pruned):

```sql
SELECT e.kind, e.slot, e.position, i.native_id, i.full_type
FROM player_rel.equipment e
JOIN player_rel.item i ON i.snapshot_id = e.snapshot_id AND i.id = e.item_id
WHERE e.snapshot_id = '96146fa4-a5a6-4d7d-855e-b32fc2e70b3b'::uuid
ORDER BY e.kind, e.position, e.slot;
```

This query shows persisted expectations only; the enhanced runtime diagnostic
supplies the reconstructed side. A later migration run verifies already-complete
imports without reimporting them, subject to the existing recency/online skips.
Verification failure retains the published head and does not quarantine it from
normal relational login. A successful commit is therefore not proof of a
successful round trip. Do not delete or reimport the snapshot just to retry
verification.

`DRAINED` reports selected, migrated, skipped, collision and migration-failure totals,
plus separate `verified` and `verificationFailed` totals;
`STOPPED` marks interrupted execution and `ABORTED` marks an enumeration/startup
failure. Zero failures/collisions in a drain summary does not validate recently
skipped accounts. The worker flushes every file event before proceeding; audit
file failures stop the run rather than silently dropping collision reports.

## Validation

- `python -B tools/test_postgres_player_migration.py --diagnostics-only`: actual
  PostgreSQL driver error metadata, nested JDBC batch errors, cyclic/long chains,
  and exclusion of raw SQL/messages/duplicate values. No server/database required.

- `python -B tools/test_postgres_player_migration.py`: actual migration worker,
  real temporary read-only SQLite databases, server-thread native-load boundary,
  recency parsing/boundary, unknown dates, collision/failure isolation, online and
  pre-auth exclusion, login reservation, one-second pacing, source preservation,
  post-commit verification, reconstruction/read/encoding/identity exceptions,
  assertion mismatches, retained failed-verification snapshots, and shutdown while
  awaiting either native-load or verification continuations; exact 84-ID validation
  cohort without SQLite or persistence, changed heads, inactive/online/identity
  exclusions, lookup failure isolation, reservation and bounded key/equipment
  diagnostics, sparse-position acceptance with relative-order/target rejection,
  and skipped-trait transfer from reconstruction through worker verification.
  PostgreSQL/engine ports use
  controlled fixtures.
- `python -B tools/test_postgres_player_relational.py`: production relational
  capture/JDBC behavior, including shared slot locks across source datasets.
- Exact 42.21.0 game-JAR compile via `patchApocalipseBr.ps1 -DryRun`.
- `python -B tools/test_postgres_player_reconstruction.py`: production reconstruction
  checks, parameterized validation-slot lookup/lease release, and immutable item
  recipe lists replaced without duplicate defaults or mutation of shared script data;
  missing-trait skipping, bounded scoped diagnostic capture, and two-handed native
  field restoration without gameplay setters, attachment replacement or modifier changes.
- `python -B tools/test_postgres_players.py`: native/profile path and shared client serializer.

Live migration, real mod inventory decoding and concurrent login timing still
require staging-server validation. No live data migration or deployment is part
of implementation verification.

Related: [relational schema and sync](2026-10-14-player-relational-schema.md),
[PostgreSQL reconstruction](2026-10-15-player-relational-login.md).
