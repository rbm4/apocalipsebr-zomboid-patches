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
| CreatePlayerPacket.processServer, immediately after native database process() | Detach the pooled request, coalesce retransmissions, wait for older writes, run original creation, capture the new life durably and delay its response until replay commits. Retain that exact server instance outside simulation until connect; cancellation removes it. |
| ServerPlayerDB.create, snapshot constructors, process, serverUpdateNetworkCharacterInt | Skip SQLite initialization; preserve native snapshot capture and queue processing. Snapshot origin/world/identity are captured before queue handoff. Journal detached native mirror writes to public.networkplayers with transactional receipts. |
| NetworkPlayerManager.update; ServerPlayerDB.serverUpdateNetworkCharacter(IsoPlayer,...) | Preserve native simulation/networking, observe live players with the legacy sampling budgets, capture graph reconciliation and final state, and journal scalar/graph mutations in order. A final capture that cannot be retained fails explicitly. |
| ServerPlayerDB.saveFinishWait / close; GameServer.main; ServerMap.QueuedQuit | Drain captured work, coordinate native teardown and concurrent JVM shutdown hooks, stop account continuations and player replay, then release dictionaries and the shared pool. |

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
lost commit acknowledgements retry the same operation UUID. Failed replay keeps
records and blocks reads. Unknown legacy player journal descriptors still fail
without acknowledgement; this port does not silently reinterpret or discard them.

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
It exercises relational capture/reconstruction, source-ID remapping, nested opaque
provenance, pooled item reuse, profile absence/presence/corruption, native reply
parsing/player loading, durable creation handoff, retransmission coalescing and
final saves. Request/continuation tests cover copied buffers and stale connections.

The connection fixture deliberately substitutes a small native acceptance tail,
world-state sender, metadata sink and quit tail; it proves production deferral/resumption
and ordering, not live chat, RakNet, map download or full vanilla acceptance.
The original unmodified acceptance bytes are separately transformed/validated.
Journal tests cover restart, opaque metadata retention and lost commit replies.

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
