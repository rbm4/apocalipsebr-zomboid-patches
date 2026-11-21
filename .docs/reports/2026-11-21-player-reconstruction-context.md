# Dictionary prerequisites and PostgreSQL player reconstruction

Date: 2026-11-21. Status: dictionary setup is now implemented in the root agent;
this report records the investigation before payload/player implementation. The
subsequent [active player/payload guide](../features/postgresql-players.md) records
the registered hooks and current validation limits. The candidate boundaries and
open implementation statements below describe that earlier investigation.

## Scope and evidence

Implementation order: dictionary support first, then relational player reads and
reconstruction, then encoding/delivery of the initial client profile and
authoritative server connection. Dictionary support is a prerequisite acceptance
gate, not a dependency to postpone until after enabling player loads.

Selected player destination: `features.postgres.player`, with a separately
registered player module, configuration gate and hook checks. Shared PostgreSQL
connections and persistence infrastructure remain in `features.postgres`.
Dictionary contracts should be shared under `features.postgres.dictionary` rather
than owned by player reconstruction, because provenance and decoding extend
beyond players. This investigation does not authorize unrelated vehicle/world
feature ports; trace the decoder closure required by player payloads first.

The behavior reference is `42.22.2/src`; its vanilla baseline is
`42.22.2/decompiled`. Neither tree is an implementation destination. This review
read the current root source, including uncommitted Lua/NPC/login-history work;
those working-tree additions must not be overwritten or assumed to complete
player persistence.

The original archive at
`Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar` was inspected
with the repository JDK's `javap -p`. Its SHA-256 is
`3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287`, matching the
existing PostgreSQL audit. These signatures exist in the archive:

- `LoadPlayerProfilePacket.processServer(PacketTypes.PacketType, UdpConnection): void`
- `CreatePlayerPacket.processServer(PacketTypes.PacketType, UdpConnection): void`
- `GameServer.receivePlayerConnect(ByteBufferReader, IConnection, String): void`
- `ServerPlayerDB.serverLoadNetworkCharacter(int, String): IsoPlayer`
- `ServerPlayerDB.serverUpdateNetworkCharacter(ByteBuffer, UdpConnection): void`
- `ServerPlayerDB.serverUpdateNetworkCharacter(IsoPlayer, int, UdpConnection): void`
- `ServerPlayerDB.create(): void` (private), and `close(): void`

The legacy four-argument `receivePlayerConnect` overload, ready/finish helpers,
and PostgreSQL profile methods are added extension contracts, not vanilla APIs.
Their replacements must live in agent-owned state and helpers. Signature
inspection is not a transformation or gameplay test.

## First prerequisite: dictionary support through the client boundary

The reason is concrete: vanilla `InventoryItem.loadItem` reads a short registry
ID from the binary stream and passes it to
`InventoryItemFactory.CreateItem(short)`, which resolves it through
`WorldDictionary.getItemInfoFromID`. Script and string dictionary readers also
decode numeric IDs. The same ID is not evidence of the same meaning across
different source dictionary views.

This does not mean every relational item is loaded from a binary blob. Relational
reconstruction restores ordinary item fields and creates items by type, but also
restores opaque `value_node` values and `item.byteData`. The legacy reconstruction
copies source provenance from the opaque bytes onto the restored ByteBuffer.
Later native decoding can therefore still require the historical dictionary even
when the outer item was reconstructed entirely from relational rows.

Required dictionary sequence before enabling player reconstruction:

1. Establish the registered source server, dictionary namespace and immutable
   view contracts, including SQL schema/grants, publication and the current local
   encoding view. Preserve existing persisted identities and provenance labels.
2. Read historical views through bounded background JDBC and validate/cache
   complete views. Release the profile graph lease before borrowing for dictionary
   reads; pool size 1 must work. Missing or incompatible provenance must fail
   explicitly rather than silently use the current dictionary.
3. Carry provenance and payload world version through opaque byte arrays,
   buffers, copies and nested payloads, with bounded ownership and cleanup.
4. Instrument the actual native item/script/string and reachable object decoding
   boundaries. Enter the correct historical lookup scope, propagate it into
   nested payloads and restore it on all exits. Preparing a view or attaching
   metadata alone does not establish decoding support.
5. Validate encoding and client decoding as a separate boundary. Native
   `InventoryItem.save` copies `byteData` into the outgoing stream unchanged;
   `player.save` therefore does not automatically transcode embedded historical
   bytes into the destination dictionary. Determine how the initial client's
   dictionary synchronization and embedded payload handling make those bytes
   decodable. An agent-side identity map alone does not cross a network packet.
   The user confirmed the patched 42.22.2 cross-server flow works through shared
   dictionary knowledge. Preserve that implementation contract and trace its
   remaining client/decoder integration during the port; this observation is not
   grounds to introduce a new transcoding/protocol design.
6. Prove mixed source views, missing views, nested payloads, failures, scope
   cleanup and the destination-client round trip before opening player admission.

Evidence owners are the matching vanilla and legacy InventoryItem,
InventoryItemFactory, WorldDictionary, ScriptsDictionary and StringDictionary;
legacy `ApocBRPayloadProvenance`, `ApocBRWorldDictionaryDatabase`,
`ApocBRWorldDictionarySnapshot`, `ApocBRWorldDictionaryMirror` and
`world/ApocBRWorldDictionaryDecode`; and the reader/reconstruction classes below.
The root `features.postgres.dictionary` now implements setup/publication, counted
foreign-view loading/cache, notification/reconnect handling and shutdown with
original-JAR hooks and focused tests. Native payload decoding and client/profile
integration still need their port. The user reports the legacy cross-server flow
works; the agent has not yet established that complete round trip.

## Then: two distinct player reconstruction paths

### Client profile response

Legacy `network/packets/connection/LoadPlayerProfilePacket.java`:

1. Check admission, request budget and slot range (0 through 3).
2. Capture world, username, slot and identity mode. Steam identity is used only
   for Steam co-op; other loads use username. Do not equate Steam-enabled with
   co-op identity mode.
3. Submit bounded JDBC work through `ApocBRAsyncLogin`; co-op conversion precedes
   `ServerPlayerDB.readPostgresProfile` where applicable.
4. The worker checks pending-write admission, reads the relational graph, then
   prepares its historical dictionary views after returning the graph lease.
5. The simulation-thread continuation rechecks connection/slot admission and
   calls `buildPostgresClientProfile`.
6. Reconstruct a temporary `IsoPlayer`, then use native `player.save(ByteBuffer)`
   to produce the client payload. Grow from 32 KiB up to the configured source
   limit; return current world version, position, death flag and saved split
   username (falling back to the request username).
7. Verify the resulting payload, including preserved embedded byte arrays, is
   decodable under the logging-in client's synchronized dictionary contract as
   described in the prerequisite above.
8. Send a fresh profile reply. No profile is a normal absent-profile response;
   failed/unavailable reconstruction denies the request rather than pretending
   there is no saved character.

The payload is regenerated from relational state; it is not simply a
`public.networkplayers.data` blob. Temporary reconstruction must not publish a
live character, descriptor identity or equipped-item simulation registrations.
Constructor/finalization side effects and cleanup on failure still need focused
verification against vanilla.

### Authoritative server connection

Legacy `network/GameServer.java` and `savefile/ServerPlayerDB.java`:

1. Copy the incoming connection bytes before deferring work. Do not retain a
   pooled reader/packet handler as mutable request state.
2. Validate slot and ensure it is vacant. Consume a committed newly created
   player handoff when present; otherwise asynchronously read the profile again.
3. Before continuing, verify the connection generation/identity/world and slot
   are still current, including the split-slot username check.
4. `finishPostgresPlayer` rechecks pending-write admission, reconstructs the
   character, registers its existing persisted character/dataset/item identities
   for live persistence, publishes its descriptor and restores live equipment
   registration.
5. Continue vanilla connection acceptance: relevance/maps, player IDs, role,
   connection ownership, synchronization and other native acceptance work.
6. Send initial world state and metadata only after successful acceptance.

The two reads may observe different committed heads. The legacy implementation
does not reuse the temporary client-profile player for a returning character.
Newly created characters have a separate durable handoff.

## Reader and reconstruction contracts

`ApocBRPlayerRelationalReader` selects the slot's active character/head/snapshot
from `player_rel` in one SQL statement, giving all graph rows one MVCC view.
It maps graph UUID references to ordinals, preserves persisted character/item
identity seeds, decodes typed JSON and byte arrays, and validates complete status,
schema/codec versions, object/item/container counts and edge counts. Missing
profiles and invalid profiles are different outcomes. Graph transactions commit
or roll back and close before dictionary preparation, including with pool size 1.

The detached profile has immutable map/list wrappers; nested values and byte
arrays still need ownership review before describing the whole graph as deeply
immutable. The reader's schema SQL depends on capture/schema definitions, so a
read port needs explicit format constants/contracts without importing the entire
capture/writer subsystem as an accidental dependency.

`ApocBRPlayerRelationalReconstruction` restores descriptor and character fields,
stats, traits, XP/perks, sequences, health, visuals, containers/items, components,
structured values and equipment. It validates graph ownership and rejects
unhandled nonempty tables. Coordinates are world coordinates, not values to
translate again using source offsets. It establishes native owner references,
restores the XP checker baseline without replaying level-gain hooks, rebuilds
visual/health state and applies death/fire state. Saved vehicle return state is
also handled. Preserve table-specific policies: for example missing saved traits
are logged/skipped, whereas missing perks fail.

`ApocBRPlayerRelationalAccess` provides version-pinned reflective access. It also
depends on detached snapshot types; package migration must separate that dependency
deliberately and validate reflected members on the original archive. Successful
compilation alone does not validate reflective field/method names.

Legacy read limits include 128 MiB estimated graph bytes, 500,000 rows, 8 MiB
source payload and a two-second graph query timeout, with configurable ceilings.
Port only needed settings through `AgentConfiguration`; do not transplant direct
system-property reads or confuse these budgets with the login workflow deadline.

## Cross-cutting dependencies

- **Async lifecycle:** `ApocBRAsyncLogin` uses two JDBC workers, bounded queue and
  pending admission (32), one phase per connection, same-phase retransmission
  coalescing, an eight-second request expiry and stale connection/world/Steam
  identity checks. Workers retain result capacity until consumption/discard.
  Its tick pump limits continuations to two or a 2 ms elapsed cutoff; this does
  not preempt an individual expensive reconstruction. Shutdown cancels pending
  requests and releases workers. Its legacy account-role snapshot dependencies
  need agent-owned equivalents where required, not patched game types.
- **Dictionary provenance:** opaque structured values preserve source server,
  namespace, view and payload world version. The reader prepares dictionary views;
  reconstruction transfers metadata to byte arrays/buffers. Later native decode
  requires scoped historical dictionary lookup as well. Legacy integration spans
  InventoryItem, StringDictionary, ScriptsDictionary, WorldDictionary and object
  decoders. Determine the exact reachable decoder closure for supported player
  payloads; preparation or metadata attachment alone is insufficient. Local
  re-encoding also requires a valid destination dictionary publication contract.
- **Admission/replay:** legacy `playerReadable` checks global admission and pending
  journal/live-mutation work for the slot. Recheck before publishing reconstructed
  state. The root journal currently supports account aliases and rejects unknown
  legacy operation descriptors; player replay/coordinator behavior remains open.
- **Live persistence:** `registerPostgresPlayer` binds persisted character/dataset
  UUIDs and native-item-ID aliases before starting tracking. NetworkPlayerManager
  wraps native updates with begin/observe/end tick sampling; ServerPlayerDB's
  player save path takes a final sample. Snapshot writes, ordering, quarantine,
  replay and shutdown drain are part of safe authority, not proven by a reader.
- **New character creation:** CreatePlayerPacket copies its pooled request,
  waits for prior saves, captures the newly created character, waits for durable
  creation, then sends the response. The handoff retains that exact committed
  server instance outside simulation until connect. Read failure must never
  accidentally enter new-character creation or replace an existing slot.
- **Pool ownership:** root `PostgresRuntime.borrowBackground` currently initializes
  through account support and requires accounts enabled. Independent player
  gating needs a deliberate shared-pool lifecycle contract. Stop/cancel readers
  and drain feature writes before the existing account shutdown closes the pool.

## Implementation stages and proposed original-JAR boundaries

Proceed in dependency order:

1. Dictionary schema/view/publication, provenance, executable decode scopes and
   the client encoding/decoding compatibility contract.
2. Detached PostgreSQL profile reader with graph validation and dictionary
   preparation, followed by simulation-thread relational reconstruction.
3. Temporary reconstruction and initial profile encoding/reply, preserving
   ordinary absence versus corruption/unavailability and proving client decode.
4. Authoritative connect reconstruction and native acceptance continuation,
   durable creation handoff, live identity tracking and final persistence.

Internal stages may be reviewed separately, but a helper-only stage is not an
enabled feature port. Dictionary completion must not be inferred from an empty
module, registration log or an unexercised provenance service.

These are implementation candidates, not installed or proven hooks:

| Boundary | Intended responsibility |
| --- | --- |
| `LoadPlayerProfilePacket.processServer` | Intercept enabled PostgreSQL profile requests; preserve disabled vanilla behavior; own detached request and fresh reply. |
| `ConnectPacket.parse(ByteBufferReader, IConnection)` | Defer world-state/metadata sends until async player acceptance succeeds. Vanilla sends these immediately after `receivePlayerConnect` returns. |
| `GameServer.receivePlayerConnect` plus its `serverLoadNetworkCharacter` call sites | Async admission and resume native acceptance with an agent-owned reconstructed-player context; narrowly substitute the native load call without copying the complete acceptance method. Validate direct callers too. |
| Validated `GameServer.main` loop anchor | Pump continuations in simulation-thread order before relevant packet processing. Reuse established ASM anchor validation patterns and compose with telemetry/NPC/Lua transformers. |
| `ServerPlayerDB.create`, load/save/process/close paths | Avoid SQLite initialization for enabled player authority and connect the selected storage/lifecycle contracts. Do not merely replace `conn` with PostgreSQL. |
| `CreatePlayerPacket`, `NetworkPlayerManager.update`, final-save paths | Durable creation handoff and subsequent live/final persistence. |
| Reachable dictionary/opaque decode boundaries | Bind provenance to actual native decoders and restore scopes on failure. |
| Shutdown boundaries | Cancel async requests, release temporary state, drain persistence and then close shared resources. |

In particular, advising `serverLoadNetworkCharacter` alone would either perform
JDBC on the simulation thread or leave the surrounding connection flow unable to
wait. Simply skipping `receivePlayerConnect` also lets vanilla ConnectPacket send
premature world state. Validate a narrow call-site/context approach before moving
support code; if it cannot preserve native ordering, stop and resolve that boundary
rather than recreating the complete GameServer method.

## Validation required during implementation

Use transformed entry-point tests for present/absent/invalid profiles, disabled
mode, continued native acceptance, fresh reply serialization, co-op and split-slot
identity, retransmissions, stale/disconnected requests, queue/deadline failure,
pending writes, dictionary mismatch and all cleanup paths. Verify temporary
players cannot enter simulation and live descriptor/equipment registration occurs
only at the intended boundary. Test shutdown while reads/results are pending.

Validate every matcher and call-site anchor against this exact original archive,
including transformer composition and unchanged game-class structure. Add the
module and hook checks to the real registry. Package/linkage checks must ensure
no signed `zombie` helpers or patched APIs are required. Isolated PostgreSQL
schema/engine-role acceptance and a real client/server profile/connect scenario
remain separate from unit and bytecode tests. Do not start the optional server
integration suite or use production services merely for this context review.

The initial investigation performed no compilation or runtime tests. The later
dictionary setup slice has its own build/hook/artifact evidence in the setup
guide; no real database connection, deployment or dedicated-server startup is claimed.
Remaining reconstruction uncertainties include
constructor/temporary-player cleanup, exact decoder closure, reflective-member
compatibility and the proposed async resumption anchors. This document is a source
map and port plan, not feature acceptance.
