# Player mutation coverage and measurements — 42.21.0

Capture codec **4**, schema version **1**, existing backend V5 tables. Older
receipts need another export on a subsequent native load. No DDL is introduced.
These changes extend capture and mutation reconciliation; they do not remove
native persistence or certify a reconstruction implementation.

## Native persistence coverage

Every table emitted by the codec already participates in whole-graph comparison,
including collection insertion/deletion/reorder and optional-row removal. Extending
the codec therefore also extends live dirty detection. Defaults remain **30 seconds**
for scalar sampling and graph reconciliation; native final-save/disconnect samples
bypass that interval. This is reconciliation, not comprehensive mutation-site hooks.
One-shot full copying still costs CPU on the simulation thread.

Nested structured values now dispatch by explicit type, including actual
`KahluaTable` checks. Owned inventory-item/container and current-player references
use typed `value_node` reference rows and restore the existing object identity.
Changing a reference participates in the ordinary graph delta. Unsupported Java
values and references outside the accepted graph remain capture failures; see
[typed modData values](2026-10-14-postgresql-connector.md#typed-moddata-values-and-object-references).

The reviewed writers are the exact-version IsoMovingObject, IsoGameCharacter and
IsoPlayer saves, the existing concrete inventory subtype writers, GameEntity's
component save dispatch, and the nested IsoAnimal writer. Missing reflected fields
abort capture. Unknown item/component/animal writers are not certified complete.

Codec 4 closes these previously unsupported or omitted cases:

| State | Persistence and mutation behavior |
| --- | --- |
| Moving-object offsets | `character_state.source_offset_x/y` now contain current `offsetX/Y`, rather than forced NULL; scalar and graph dirty detection include them. |
| Player extra-information flags | Six `character_cheat` rows: `extra.GodMod`, `extra.GhostMode`, `extra.Invisible`, `extra.NoClip`, `extra.ShowAdminTag`, `extra.CanHearAll`. Values come from the exact native `getExtraInfoFlags()` bit mask. Reconstruction must apply native permission-aware restoration semantics. |
| `entity.UiConfig` | Nullable `xuiSkinName`, nullable `entityStyleName`, Boolean `uiEnabled`. Script/UI pointers are derived, not persisted objects. |
| `entity.CraftBench` | Nullable `recipeTagQuery`; `fluidInputChannels` and `energyInputChannels`, each with `enumClass`, `nativeBits`, and selected enum `names`. |
| Empty native components | Explicit presence plus an empty object for `LuaComponent`, `Parts`, `Signals`, `TestComponent`, `CraftRecipeComponent`, `ContextMenuConfig`, `CraftBenchSounds`, `WallCoveringConfig`. These exact classes inherit the zero-payload `Component.save/load` contract. Runtime script-derived sounds/settings are not extra saved fields. |
| Carried animals | `item.embeddedAnimal`, typed component/value nodes; contract below. |
| Native stored-world-object bytes | `item.byteData`, an opaque byte-array leaf with deep byte comparison. This restores the actual InventoryItem field; it is not a backup player serialization. World-object reconstruction on placement still uses the engine's separate native format. No portability or relational world-object claim is made. |
| Shared acyclic Lua tables | Independent value trees at each path, matching native Kahlua serialization. Changes to a shared child update each persisted path. Cycles still fail capture. |

Together with Attributes, Script and Durability, the ten added component families
cover every component that the 42.21.0 ComponentType registry marks valid for an
InventoryItem. FluidContainer remains separately supported as before. World-only
craft logic/resources/sprite components are outside that eligibility set; attaching
unreviewed types through a mod still fails closed.

`item.save_type=-1` is intentional: InventoryItem.save writes that constant sentinel
in this build. It is not an omitted getter or an inferred subtype identifier.

## Carried-animal typed state

The owner is the AnimalInventoryItem UUID. The native animal ID and relationship
IDs remain scalar domain identifiers, never Java references. The exact root keys:

- `x`, `y`, `z`, `direction`, `type`, `breed`, `customName`, `itemId`, `animalId`,
  `female`, `hoursSurvived`, `attachBackToMother`, `motherAnimalId`, `milkRemoved`,
  `stressLevel`, `health`, `virtualId`, `migrationGroup`, `petTimer`, `wild`, `onlineID`.
- `zoneId`: nullable UUID string; `savedAtGameTimeMillis`: native game-calendar
  timestamp. An initial native-load copy preserves `timeSinceLastUpdate` until
  offline updates have run; live/new-character copies use the current game clock,
  matching native save. That clock advances even for an otherwise idle animal
  and can legitimately cause one node update.
- `stats`: stable CharacterStat ID to value; `modData`: detached typed table;
  `playerAcceptance`: native short player IDs to float values.
- `genome`: native gene map key to `{id,name,allele1,allele2}`. Each allele contains
  `name`, `currentValue`, `trueRatioValue`, `dominant`, nullable `geneticDisorder`.
  The derived `used` allele flag is not written by the native animal writer.
- `data`: `age`, `size`, `pregnant`, `canHaveMilk`, `milkQuantity`, `maxMilkActual`,
  `preferredHutchPosition`, `fertilizedTime`, `fertilized`, `weight`,
  `lastPregnancyTime`, `lastMilkTimer`, `lastImpregnateTime`, `clutchSize`, plus
  conditional `pregnantTime`, `woolQty`, `eggsToday` (NULL when the native writer
  omits that conditional value). Long timers preserve their full integer width.
- `attachedTree`: nullable `{x,y}`; `hook`: nullable `{x,y,z}`, using the same
  saved-coordinate versus current-hook-square branch as native save. Pending
  loaded reattachment coordinates are retained when external tree/hook objects
  have not yet been mounted, rather than silently clearing those relationships.

Animal subtype writer overrides are rejected. Reconstructors must restore native
relationships, derived genome/breed state and game-clock semantics; capture tests
alone do not prove that restoration or cross-player/world crash atomicity.

## Reading LIVE_METRICS

Existing `samples`, `graphSamples`, main-thread time/max fields, `writes`, `writeMs`,
cache and graph delta counts remain cumulative. `writes` is successful worker
acknowledgements, including unchanged graphs; it is not a SQL statement count.
`writeMs` includes worker projection, pool wait, binding, SQL and commits.

New JDBC measurements wrap the existing connection/statement calls. They issue no
monitoring queries and record no player IDs, item values or Lua keys in log output.

| Metric | Meaning |
| --- | --- |
| `txCommits`, `writeTx`, `readTx`, `txRollbacks` | Explicit successful JDBC commits; commits that executed DML; commits without DML; explicit successful rollbacks. `readTx` can include SELECT FOR UPDATE/advisory locks: it does not assert PostgreSQL READ ONLY mode or zero WAL. |
| `jdbcQueries`, `jdbcUpdates`, `jdbcCommands`, `jdbcBatches` | Calls to executeQuery, executeUpdate, execute (including SET commands), executeBatch. These are client API executions, not measured server network round trips. |
| `batchEntries` | Logical entries submitted in JDBC batches. Driver rewriting/pipelining may change server statements and network messages. |
| `jdbcAffectedRows`, `jdbcUnknownRows` | Nonnegative affected-row counts returned by DML, including metadata/fork copies; number of SUCCESS_NO_INFO results for which row counts are unknown. Successful statements later rolled back still consumed work and remain included here. |
| `jdbcFailures`, `jdbcMs` | Execution/commit/rollback failures and cumulative elapsed time inside those calls. Contract guards such as a legitimate zero-row update can cause a patch rollback without a JDBC execution error. |
| `poolLeases`, `poolBorrowMs`, `poolMaxBorrowUs`, `poolFailures` | Successful borrows, cumulative borrow time, maximum borrow time, failed borrows. |
| `initialCommits`, `initialRows` | Initial/re-export transactions and graph rows, separate from steady mutation activity. |
| `windowSeconds`, `windowPlayers` | Actual reporting-window duration and distinct successfully processed sessions, capped at 512. No per-session identifiers are logged. |
| `txPerSecond`, `writeTxPerSecond`, `jdbcCallsPerSecond`, `changedRowsPerSecond` | Window rates for commits, DML commits, JDBC executions and committed logical row changes. Rollbacks remain separately visible. |
| `topTables` | Up to ten table/operation counts for committed logical changes in the current reporting window; includes scalar-only batches. |
| `topColumns` | Up to twelve changed-column counts in that window. Creates/deletes are in topTables; column rankings describe updates. |
| `topComponents` | Up to eight component kinds responsible for value-node changes, separating fluids, Lua and carried animals without logging member keys. |
| `oldestPendingMs`, `workerBusyPercent` | Age of the oldest pending work item, and the window fraction spent processing mutation batches. Busy percentage excludes initial-export work, so it is not total worker utilization. |
| `failedPatches`, `deferredPatches` | Failed mutation attempts and guarded noncommits retained for retry. |
| `duplicateItemsSkipped` | Cumulative later distinct owned-item occurrences rejected for a repeated native ID during projection, including repeated captures. The first item in depth-first source order is retained; this is not a committed deletion or distinct-player count. See [duplicate-ID handling](2026-10-14-postgresql-connector.md#duplicate-native-inventory-ids). |
| `scalarCaptureFailures`, `graphCaptureFailures`, `incompleteGraphs`, `incompleteReasons` | Capture errors and rejected incomplete graphs, with window rankings of bounded issue codes. An empty SQL queue alone is not evidence of full capture coverage. |

Table/column/component rankings reset each reporting window. Transaction/JDBC
totals remain cumulative. No-op graph acknowledgements do not increment committed
logical row changes; they can still require read transactions and locks.

For a representative measurement, let initial exports/forks and cache warm-up
settle, then compare multiple windows with known active player counts and scripted
inventory, crafting, vehicle-seat, health, component and carried-animal changes.
Use transaction rates, batch entries, pool wait, queue age and CPU together.
Multiplying one player's row count by 200 players or ten times as many vehicles
does not establish sustainable throughput: scheduling, activity, batching, cache
capacity and latency change the result. Vehicles need their own coverage audit and
measurements; this change does not instrument vehicle persistence.

## Verification and limits

The real-JAR fixture audits every native inventory-valid component class, validates
the eight empty payloads, checks immutable component/animal copies and mutations,
checks animal field paths against native types, exercises flags/offsets/byte arrays,
and compares shared-table behavior with native Kahlua save/load. Fake-JDBC fixtures
verify commit/rollback classification, executions, batches, metadata row counts,
component attribution, reporting-window reset and unchanged SQLException behavior.

These are capture/dirty/JDBC regression checks, not a live database benchmark or a
proof of relational reconstruction. The dirty queue remains volatile; cross-player
atomic trade/transfer and complete crash recovery still require separate work.
Keep native persistence while those contracts and round trips are validated.
