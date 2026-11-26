# PostgreSQL player authority and historical payload decoding

Date: 2026-11-21. Target: original 42.22.2 archive and the root Maven agent.
Implementation is wired into the installed module pipeline. Dedicated-server,
real PostgreSQL engine-role and connected-client acceptance remain pending.
Player authority defaults off; this guide does not authorize deployment.

## Source and ownership

The behavior references are the matching `42.22.2/src` and `decompiled` dictionary,
InventoryItem, IsoObject, ServerPlayerDB, profile/create/connect packets,
GameServer and NetworkPlayerManager sources. The implementation lives in
`features/postgres/dictionary`, `features/postgres/player` and `PlayerJournal`.
No original archive, legacy source tree, or installed override is changed.

`DictionaryDecodeModule` and `PlayerModule` are registered in `AgentModules.all()`.
Both preflight original bytecode and declare their executable hook checks.
The player startup gate is native-free: helper linkage is deferred until after
transformer installation, so initialization cannot load an uninstrumented IsoObject.
Neither adds native methods, fields or nested classes. Legacy added APIs are
replaced by agent-owned scopes, detached requests, identity bindings and handoff
state. Persisted schema 1 / codec 4 and historical dictionary identities remain
unchanged. The existing `sqlite-save:` dataset prefix is retained as an identity,
not a claim that current authority reads SQLite.

## Native boundaries

| Original entry point | Enabled behavior |
| --- | --- |
| InventoryItem.storeInByteData | Capture the published encoding view and world version on native byteData. |
| InventoryItem.loadItem, load; IsoObject.load and factoryFromFileInput | Enter source-aware decoding scopes and restore them on normal and exceptional exits. A validated byteData assignment anchor propagates the containing origin only to a newly allocated nested buffer. Reused payloads retain their own provenance. |
| WorldDictionary numeric lookups; string/script register readers, get and getInfoForID | Resolve IDs in the prepared exact source view. Items/entities resolve destination definitions by type; scripts also require an exact version match. Unknown IDs, missing definitions, unprepared views and native decode failures fail closed, including errors swallowed inside vanilla item loading. |
| LoadPlayerProfilePacket.processServer | Copy request values, read the relational graph on bounded account workers, prepare exact dictionary views after releasing the graph lease, reconstruct on the server thread, and send a fresh native reply. Absence remains distinct from corruption/unavailability. Slot four preserves vanilla's absent reply that terminates a four-character scan. |
| ConnectPacket.parse; GameServer.receivePlayerConnect; ServerPlayerDB.serverLoadNetworkCharacter | Copy pooled request bytes, defer JDBC work, recheck connection/world/identity/slot admission, bind a reconstructed player for the native load call, and resume the original acceptance method. Initial world state and metadata follow successful acceptance. Direct callers also enter this path. |
| CreatePlayerPacket.processServer, immediately after native database process() | Detach the pooled request, coalesce retransmissions, finalize an attached dead occupant, and wait for older writes before running original creation. Capture the new life durably and delay its response until replay commits. Retain that exact server instance outside simulation until connect. Cancellation removes it. |
| ServerPlayerDB.create, snapshot constructors, process, serverUpdateNetworkCharacterInt | Skip SQLite initialization; preserve native snapshot capture and queue processing. Snapshot origin/world/identity are captured before queue handoff. Journal detached native mirror writes to public.networkplayers with transactional receipts. |
| NetworkPlayerManager.update; ServerPlayerDB.serverUpdateNetworkCharacter(IsoPlayer,...) | Preserve native simulation/networking, observe live players with the legacy sampling budgets, capture graph reconciliation and final state, and journal scalar/graph mutations in order. A final capture that cannot be retained fails explicitly. |
| ServerPlayerDB.saveFinishWait / close; ServerMap.QueuedQuit; GameServer$1.run | Drain captured work during the native quit phase. After vanilla shutdown, stop remaining workers and player replay before releasing dictionaries and the shared pool. |

Temporary reconstruction suppresses Lua callbacks and cell/process-item
publication through a scoped guard. It constructs an unpublished descriptor and
restores equipment ownership without live registration. Persisted character,
dataset and item identities are bound before authoritative acceptance; descriptor
and equipment publication occur at that boundary. Fire restoration sets saved
state without replaying random burn damage; accepted burning actors are then
registered. Successful-login audit records can use the authoritative character UUID.

## Persistence and compatibility

The reader uses one SQL statement / MVCC view with graph-count, codec, ownership
and coverage checks. A slot with a missing head, or a native-only save awaiting
relational import, is an error rather than an absent character. No automatic
SQLite backfill or fallback to a binary profile is introduced here.

The new player journal is an independent generation below `agent-players` in the
configured PostgreSQL journal directory. It uses the existing journal framing,
fsync, pending-player tracking and acknowledgement machinery, with an explicit
detached-type whitelist and agent player DTOs. It preserves opaque provenance
through snapshot serialization. Receipt claims and mutations commit together;
lost commit acknowledgements retry the same operation UUID. Data and constraint
rejections (SQLSTATE classes `22` and `23`) and live mutations for a replaced
character are preserved under `quarantine/<operation-id>.record`. Replay then
advances, allowing reads of the last committed state once remaining writes for
the slot clear. The quarantine log includes the operation UUID, kind, SQLSTATE,
and PostgreSQL schema, table and constraint identifiers when available. Connection
failures and other unclassified errors leave the record pending for retry.
Unknown legacy player journal descriptors still fail without acknowledgement.

On restart, use the same player journal directory and keep its checkpoint and
generation files together. `REPLAY_OPENED` reports the durable backlog restored
from disk. `REPLAY_PROGRESS` and `REPLAY_PENDING` report counts, byte size, oldest
record age, pending player slots, replay totals and the operation UUID and kind.
`AVAILABLE` confirms the worker caught up and the PostgreSQL probe passed. Compare
`replayed` and `quarantined` to see which records were applied or already had a
receipt and which need separate investigation. Quarantined records stay on disk
and are excluded from automatic replay. Inspect the PostgreSQL constraint before
retrying one against newer player state.

When a live capture can attribute its graph issues to carried items, replay
commits the independent character rows and preserves the last committed
inventory, equipment and graph counts. Inventory moves and removals cannot be
inferred from a partial item graph. The original capture goes to quarantine
after the transactional receipt commits. A restart between that commit and
quarantine recognizes the receipt and archives the capture without applying it
twice. `LIVE_ITEM_PERSISTENCE_FAILED` includes the operation and character
UUIDs, slot, item type, native item ID, and capture issue.

Carried sprite overlays store their script name and applied style. Resources
and drying logic use bounded vanilla component payloads with the source
dictionary view and world version. The payload includes resource group contents
and native drying recipe progress and wetness. Embedded opaque item data with
unknown or mixed provenance causes the inventory capture to be retained for
inspection. Historical incomplete captures may lack the component state
required for reconstruction.

Other carried components can use the same native writer when the relational
codec is missing or cannot capture its state. The fallback stores the registered
vanilla component type with the bounded payload, world version and exact
dictionary view. Reconstruction verifies the registered type and source server,
invokes the native loader, and checks that it consumed the payload before
finishing component attachment. These generic payloads are supported on the
source server. Cross-server loading of generic payloads remains outside this
contract. If encoding fails, the live save retains the last committed inventory
and logs the failure while applying independent character changes.

Progress and health reports are limited to one every 30 seconds during a steady
state. The worker also logs startup, the first retry and recovery immediately.
Reports include shared Hikari active, idle, total and waiting counts, and reuse
the relational player's transaction, JDBC-call and changed-row metrics. These
relational metrics cover initial and live graph writes. The pool gauges cover all
users of the shared pool. Gathering metrics adds no database queries. A shutdown
drain reports its starting, completed or timed-out backlog separately.

Creating a replacement while a dead character still occupies the slot first
journals the final relational graph and the native mirror. After durable capture,
the old object stops producing writes. The existing co-op connection flow clears
the native slot, then the connect hook consumes the committed replacement.

Creation, native mirrors, scalar changes and complete graph reconciliation share
the player journal. The first simulation observation starts each player's scalar
and reconciliation timers and establishes the scalar baseline within the existing
tick budget. At each scalar interval the game thread compares current fields
with that baseline and coalesces pending values by field. Routine dispatch
respects queue eligibility and retry backoff. The worker groups scalar updates by
row and projects full graph captures to relational deltas. Native saves and
shutdown can force pending captures into the journal before the dispatch deadline.
Native mirrors preserve account-side queries, while profile
loading reconstructs from `player_rel`. The engine issues no schema DDL. Provision
the existing backend player/dictionary/receipt schema and the engine role grants
described in [AGENTS.md](../../AGENTS.md). Real queries must still be accepted as
the distinct engine role in an isolated database; fake JDBC is not grant validation.

Login transaction acquisition, JDBC calls and waits for shared dictionary
preparation inherit the existing workflow deadline. Pool-size-one reads release
their graph lease before dictionary acquisition. Other dictionary publication and
background replay retain their own budgets.

Shutdown callbacks run in reverse registration order while the pool remains
available for final commits. The player module marks native quit entry/exit so
the independent PostgreSQL JVM hook cannot close the pool ahead of vanilla role
saves and player captures. Native quit and journal drain have bounded waits;
failure leaves durable records for recovery and reports an error.

## Configuration

All production JVM settings are declared in `AgentConfiguration`.

| Setting | Default / contract |
| --- | --- |
| apocbr.postgres.players.enabled | false; requires the PostgreSQL master flag. |
| apocbr.postgres.dictionary.decode.enabled | true with dictionary setup. |
| apocbr.postgres.playerRelational.enabled | true; required by player authority. |
| apocbr.postgres.playerRelational.mutations.enabled / reconcile | true; disabling either while enabling authority fails initialization. |
| apocbr.postgres.playerJournal.directory | Optional explicit player journal directory; default is the PostgreSQL journal's agent-players child. Never share it between running processes. |
| apocbr.postgres.playerRelational.sourceDatasetId | Optional UUID; default retains the legacy deterministic save-path identity. Returning characters use their persisted dataset. |
| apocbr.postgres.createdPlayerTimeoutMillis | 30000; clamped 1000..120000. |
| apocbr.postgres.playerRelational.maxGraphMiB / maxSnapshotMiB / maxSourceMiB / queuedMiB | 128 / 32 / 8 / 64, with legacy bounded ceilings. |
| apocbr.postgres.playerRelational.maxRows / maxNodes / importWriteBudgetMs | 500000 / 1000000 / 60000, with legacy bounded ceilings. |
| apocbr.postgres.playerRelational.mutations.sampleMs / reconcileMs / cacheMiB | 30000 / 30000 / 64, with legacy bounded ceilings. |

Account hooks and async account workers must also be enabled. Existing PostgreSQL
URL/environment, server ID, dictionary publication and login-budget settings
remain applicable. Flags are startup settings, not hot switches.

## Validation and remaining acceptance

The backlog recovery build passed 22 focused tests on Windows / Java 25. Its
restart fixture reopened three durable records, archived a `23505` head, and
committed both successors in order. The logs reported `durable=3` on reopen and
`replayed=2`, `quarantined=1`, and `durable=0` after recovery. The shaded observer
verified 45 hook contracts against the original 42.22.2 JAR. Evidence is in
`target/player-backlog-package.log` and
`target/player-backlog-artifact-4fa69f18f6294b48b3643b54927e6dcd/`.

Recorded on Windows / Java 25, 2026-11-22, for the respawn and replay changes:

- `mvn -B -ntp -Dtest=PlayerJournalTest,PersistenceJournalTest,PlayerHookTest,PlayerReaderTest package`
  passed all 13 tests and produced the shaded agent.
- The packaged observer verified 45 player and dictionary-decode hook contracts
  against the original 42.22.2 archive, including the dead-occupant save guard.
  Evidence is in `target/player-handoff-artifact-1d2e2c61db654bc283974d9d0d4be76d/`.
  Agent SHA-256 is `b26b1cf4cf7fad8e987483de69738834ecb18036feb3a55f8d1f7f2bffebaed4`.

Recorded on Windows / Java 25, 2026-11-21, against original 42.22.2:

- `mvn "-Dpz.game.jar=Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar" "-Dmaven.jar.forceCreation=true" package`: 110 tests, zero failures/errors, one historical patched-runtime check skipped; shaded packaging passed.
- A separate JVM ran the packaged production agent followed by the test observer,
  with only the original archive and observer on its application classpath.
  Explicit non-initializing loads verified all 45 player/decode hook contracts,
  including with NPC and Lua modules enabled. Evidence:
  `target/player-port-artifact/evidence-e7541a1dff4d44748238e4bf24f00fda`.
- Shaded archive inspection found no game classes, test fixtures or unrelocated
  PostgreSQL/Hikari classes; the relocated JDBC service registration is present.
- Original archive SHA-256:
  `3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287`.
  Agent SHA-256:
  `ce80c1dfe63b3d4f5e059e54a21ff65b3ad1ec96263e89b71ec649efc5257a27`.
  Build output: `target/player-port-final-package.log`; packaged probe output:
  `target/player-port-artifact/console.log`.

Focused tests validate every declared transformation against the original archive
without native structural changes. An isolated JVM executes the installed modules
with original player/item/string/script classes, a disposable cache and fake JDBC.
The mutation-window fixture runs the transformed player traversal with two
different first-observation times. It verifies distinct deadlines, coalescing,
changed-column SQL on the journal worker across successive windows, and no scalar
write for an unchanged window.
Carried-component scenario A saves an item with `CraftLogic` while the native
fallback's source dictionary is unavailable
through original `ServerPlayerDB.save()` and transformed `saveFinishWait()`.
It verifies position updates, the native mirror, the item failure log, quarantine
and restored slot availability. Scenario B uses a synthetic item with the original
overlay, resources and drying components. It compares vanilla item serialization
with relational capture and reconstruction, then checks that the save reaches
the journal worker without an additional quarantine. The generic fallback case
round-trips `CraftLogic` and `MetaTag` native state and verifies a later save in
the same session reaches the inventory writer. The fixture uses a real
journal and fake JDBC. Real database state after restart and a connected client
remain part of dedicated-server acceptance.
It exercises relational capture/reconstruction, source-ID remapping, nested opaque
provenance, pooled item reuse, profile absence/presence/corruption, native reply
parsing/player loading, durable creation handoff, retransmission coalescing and
final saves. The respawn fixture creates a replacement while the dead occupant
still holds the slot, checks save order, and verifies that a late native save
cannot overwrite the replacement. It releases the slot in the fixture before
the transformed connect call. Request/continuation tests cover copied buffers
and stale connections.

The connection fixture deliberately substitutes a small native acceptance tail,
world-state sender, metadata sink and quit tail; it proves production deferral/resumption
and ordering, not live chat, RakNet, map download or full vanilla acceptance.
The original unmodified acceptance bytes are separately transformed/validated.
Journal tests cover restart, opaque metadata retention, lost commit replies,
and duplicate-key quarantine with later replay and admission recovery.

The original-player fixture reads matching installed resources (including
stdlib.lua and hair definitions) and sets its cache to a temporary directory.
It never invokes GameServer.main, starts a server, or connects to external services.

Initial client decoding is exercised with a captured item and preserved opaque
bytes. Provenance remains out of band; it is not a new network header. These
checks do not establish every later client-side use of foreign embedded payloads,
every item/component subtype, or a real cross-server login. Preserve the legacy
shared-dictionary contract; no transcoding/protocol redesign is claimed.

Before deployment, validate real engine-role SQL/grants and a connected-client
profile/create/connect/disconnect/reconnect scenario, including mixed historical
payloads, respawn, co-op/split slots, shutdown during writes and recovery. Vehicle
storage, historical journal conversion and automatic imports remain separate work.
