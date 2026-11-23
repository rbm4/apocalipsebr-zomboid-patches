# Bot engine: vanilla dependency map before migration

Date: 2026-11-21. Scope: audit and migration design only. No NPC implementation,
instrumentation, deployment, game installation or database was changed for this
report. The compatibility target is the **original game JAR only**, with
agent-owned adapters/state and no added game-class members.

## Assessment

The engine has a substantial reusable custom core, so it is a reasonable next
feature to migrate. However, its integration is considerably larger than a main
loop callback starting asynchronous work. The current design deliberately keeps
native world access and authoritative actions on the server thread. Its worker
performs detached decisions and copied-topology route planning. Preserving that
division is a prerequisite, rather than an optimization to defer.

The audit found 191 bot-related source files, a conservative custom dependency
closure of 234 files, and 603 distinct native member references across 135 native
declaring types. These are **API dependencies, not 603 hooks**. Most ordinary
native APIs already exist. The necessary changes cluster around lifecycle,
identity/visibility, perception validity, packet processing, combat, zombie
ownership, path requests, chat and durable storage. Several dependencies are
behavioral and do not appear as compilation errors.

Compiling the custom closure against the original archive reports ten errors.
Five are missing contracts used directly by bot sources; the other five arise
in shared persistence/login/telemetry dependencies. A successful compile after
fixing these would still leave substantial unwired behavior. In particular,
vanilla membership handling can silently prevent zombie perception, and some
packet methods that the patched source overrides are inherited in vanilla.

The recommended port is a registered NPC feature with explicit native adapters
and all required hooks designed together. Reuse detached policy/decision code;
rebuild its integration around original method owners. Do not copy the legacy
replacement classes, migrate unrelated engine optimizations automatically, or
describe a helper-only move as a completed feature.

## Evidence and scope

The source baseline is `42.22.2/src`; its corresponding `decompiled` files explain
vanilla behavior. Actual target declarations were checked against:

```text
Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar
SHA-256: 3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287
```

The [capture manifest](2026-11-21-npc-dependencies/manifest.json) records source
and artifact hashes, repository HEAD and compiler version. See the
[evidence guide](2026-11-21-npc-dependencies/README.md) for reproduction and limits.
The capture uses compiler symbol attribution, lexical inbound/dependency scans,
native source deltas and original classfile metadata; it never initializes game
classes. It inventories 48 original boundary class shapes.

| Inventory | Result | Meaning |
| --- | ---: | --- |
| Human engine sources | 177 | Entire custom humans tree |
| Related zombie sources | 7 | Native authority/path/corpse integration |
| Other seed sources | 7 | Walk-only marker, five NPC persistence files, human sales |
| Total seed sources | 191 | Starting scope, including persistence |
| Additional custom dependencies | 43 | Conservative lexical closure, not an approved port queue |
| Direct lexical native inbound owners | 17 | Does not include every indirect integration |
| Distinct native members used by seeds | 603 | Calls, constructors, reads and writes; separate from type-only references |
| Native declaring types / source files | 135 / 125 | Includes inherited member owners |
| Original-JAR compilation | 10 errors | Expected incompatibility evidence, not a passing build |

The [native API catalog](2026-11-21-npc-dependencies/native-api-catalog.md) lists
every attributed seed member by declaring owner, with links to vanilla source.
The [CSV](2026-11-21-npc-dependencies/native-api.csv) supplies source locations,
access levels and shared-closure references. Of the 191 seeds, 126 have no direct
attributed native references. That supports the value of the custom separation,
but does not prove those files are pure: collaborators may access native state.

## Thread ownership and lifecycle

The authoritative entry is
[ServerHumanFactory](../../42.22.2/src/zombie/apocalipse/humans/ServerHumanFactory.java).
Its `update()` checks server/world readiness, runs medical work and the factory
tick on the server thread, then starts the optional planner worker. The worker
uses `planner::tick`, **not the authoritative factory tick**. Native movement,
combat, inventory, Lua, network emission, physiology, death handling, world
capture and checkpoint capture remain server-owned.

The default asynchronous decision cadence is 100 ms. The current bounded design
includes 128 planner slots, at most 64 jobs per pass, four topology captures per
server tick, and bounded copied topology. Selection results expire after 500 ms;
route results after two seconds. Actor incarnation, ticket and generation checks
reject stale results. Synchronous mode uses the same job/result protocol inline.
These contracts must survive migration, including cancellation and queue limits.

| Boundary | Legacy behavior / vanilla port requirement |
| --- | --- |
| Recurring `GameServer.main` simulation branch | Factory update follows `LoginQueue.update` and other handoffs, before later social/world-map work. Instrument an exact recurring call site or equivalent established tick boundary. Advice at `main` entry runs once and cannot supply this behavior. Avoid packet-only iterations and duplicate ticks. |
| `ServerMap.QueuedQuit` entry | Stop actor activity, cancel decisions and capture checkpoints before pathfinding/world teardown. Preserve native quit flow afterward. |
| `GameServer.main` finally | Idempotent fallback shutdown on failure/exit, including exceptional paths. |
| Planner/store shutdown | Bounded worker stop; the planner joins outside the factory monitor. Off-owner shutdown must not access native bodies or manufacture a final checkpoint. A JVM shutdown callback cannot replace the server-thread boundary. |
| World readiness/reset | Do not start before the current cell/transport exists; invalidate actors, leases, jobs and native identity state when the world changes. |

The historical [async authority design](../../42.22.1/docs/features/2026-11-05-npc-async-authority.md)
helps explain this design; the current 42.22.2 implementation is the behavior
audited here. Older live-world worker designs are not the migration target.

## Required native integration contracts

The following groups distinguish required behavior from ordinary API reuse.
Instrumentation choices are candidates for a later implementation, not claims
that these hooks already exist in the root agent.

### Identity, actor construction and visibility

| Boundary / owner | Required behavior and migration direction |
| --- | --- |
| `GameServer.getFreeSlot()` | Preserve reservation of connection slots 192–255, corresponding to presentation IDs 768–1023 with four split-screen IDs per slot. Otherwise a real player can collide with a bot's wire identity. Apply the restriction to original slot selection without adding game fields. |
| `ServerHumanPresentationIds` | Preserve process-level nonreuse, the 256-ID budget and 64 active actor limit; names, identities and incarnations must remain distinct. |
| `PlayerDataRequestPacket.processServer` | Recognize a known NPC before the vanilla player-map lookup; announce only when the requesting connection is relevant. Consume the recognized request, retain vanilla handling for real players. |
| `IsoPlayer` construction and world membership | The custom body is deliberately detached from normal player/moving-object collections. Keep it out of `GameServer.Players`, `IDToPlayerMap` and `IDToAddressMap`; use a feature-owned registry. Do not globally broaden player lookup to make a packet work. |
| Existing actor APIs | Position, animation, health, inventory, doors, windows, fences, vehicles and corpses mostly use available original APIs. Keep calls on their owning thread, revalidate loaded squares and preserve native callbacks. |

See [ServerHuman](../../42.22.2/src/zombie/apocalipse/humans/ServerHuman.java),
[presentation IDs](../../42.22.2/src/zombie/apocalipse/humans/ServerHumanPresentationIds.java),
and the patched [GameServer](../../42.22.2/src/zombie/network/GameServer.java).
Detached bodies must not accidentally pin chunks through real-player streaming
or acquire normal player database/network lifecycle ownership.

### Perception, pooled objects and furniture

The most important non-compilation blocker is
[ServerHumanPerception](../../42.22.2/src/zombie/apocalipse/humans/ServerHumanPerception.java).
It expects `IsoCell.getObjectList()` to be a `ServerMovingObjectSet`. In vanilla
the collection lacks that custom membership contract. Coverage becomes
unavailable, and the zombie eligibility predicate requires a membership record.
This can suppress zombie perception even after all missing methods compile.

Port the **membership/identity-generation contract**, including removal, reuse,
re-addition and unload, through an agent-owned adapter. Possible designs include
observing mutations through the existing collection field or complete lifecycle
tracking. Neither requires adding a member to `IsoCell`. Replace concrete custom
collection checks with a deliberate perception port. Do not assume hooking only
`IsoCell.addMovingObject` sees every mutation: vanilla has direct set changes in
server, zombie creation, removal, world cleanup and other paths, including
iterator removal. The [mutation-site inventory](2026-11-21-npc-dependencies/vanilla-object-list-sites.csv)
is a starting checklist, not alias-complete proof.

[ServerHumanFurnitureWorld](../../42.22.2/src/zombie/apocalipse/humans/ServerHumanFurnitureWorld.java)
also depends on the patched `IsoObject.getApocbrLifetimeRevision()`. Its token
must become stale when the object changes square, resets, enters/leaves the
world or is removed from a square. Reusing the same Java object, square, sprite
and index must not make an old token valid again. Use identity-based lifetime
generations with lifecycle hooks and bounded/weak ownership. Preserve the legacy
increments in `setSquare`, `reset`, `addToWorld`, `removeFromWorld` and
`removeFromSquare`, including their conditional semantics.

The optional `MutationTrackedArrayList` furniture revision has an untracked
fallback. It is different from the hard perception membership dependency. The
whole moving-object scheduler, animal index and square-cache optimization set
need not be migrated merely to supply these two correctness contracts.

### Chat and mod-facing APIs

| Boundary | Required behavior |
| --- | --- |
| `ChatServer` | Original lacks `humanShoutRange`, `sendHumanShout` and `sendHumanSay`. Replace these extension APIs with agent-owned chat transport using native message/routing contracts. Preserve range, speaker identity and connection eligibility rather than broadcasting arbitrary packets. |
| `LuaManager` environment / `LuaEventManager` | Install the trusted server `ApocBRHumanAPI` version 5 facade and custom dialogue events when the environment changes. Existing public Lua APIs appear sufficient; no wholesale LuaManager replacement is required solely for this. |
| Seating facade | Preserve `ApocBRSeating` version 1 (`sitAt`, `stand`, `status`), ownership checks and asset capability detection. |
| External Lua/UI | Historical dialogue documentation names the ExtractionMapUtils mod as client UI/command glue. Its Lua implementation is outside this captured tree; availability and command validation remain external acceptance requirements. |
| Operation announcements | The registered operations provider depends on event-control announcement data. Preserve that provider through an adapter if the existing lobby-guide content is in scope; the generic NPC engine need not absorb the entire events subsystem. |

The facade is a trusted server API, not proof that arbitrary client requests are
authorized. Seating also depends on media/animation/action-group assets and
override fingerprints. An original JAR alone does not prove client asset or
animation compatibility. Preserve unsupported-capability behavior.

### Trading: parsing, authorization and durable completion

Six native packet families enter
[ServerHumanTrading](../../42.22.2/src/zombie/apocalipse/humans/ServerHumanTrading.java):
`RequestTradingPacket`, `AcceptTradingPacket`, `RejectTradingPacket`,
`TradingUIAddItemPacket`, `TradingUIRemoveItemPacket` and
`TradingUIUpdateStatePacket`.

The first three **inherit their vanilla parse method from `PlayerRequestPacket`**.
Advice matching a declared child `parse` would miss them. Hook the declaring
parent with exact packet filtering, or an exact verified call site. Resolve only
the NPC target after native sender parsing, before consistency checks. Preserve
sender authentication; never replace the attacker/sender with a bot registry
lookup. At processing, consume handled/rejected NPC sessions and otherwise
continue native player-to-player behavior.

Preserve connection ownership/fully-connected checks, three-tile range,
exclusive session/activity leases, profile/emergency restrictions and the
180-second timeout. Bots must not be inserted into `TradingManager` or the
account database to simulate real players.

Sales require the durable journal receipt **before** inventory consumption,
stable operation UUIDs, native removal notification, the real player's
`ServerPlayerDB.serverUpdateNetworkCharacter` save and finalization. Existing
MoneyBundle/Money conversion and nonmoney destruction behavior must be explicit.
`player_rel.apocbr_human_sale` and external backend credit processing are part of
this contract; a helper returning success does not complete it. Legacy serialized
sale records and replay idempotence must remain readable during migration.

### Incoming and outgoing combat

| Boundary | Required behavior / hazard |
| --- | --- |
| `PlayerHitPlayerPacket.parse` | Resolve only a correctly typed NPC victim after native wielder parsing; retain raw-payload fingerprinting before sanitization. Keep packet-instance state in agent-owned storage and reset it on parse/pool reuse. |
| `PlayerHitPacket` envelope | Filter invalid, duplicate or unauthorized NPC targets before common attack calculations, rebuild closest-target state, then exclude rejected relays. The child alone cannot supply the envelope contract. |
| Native processing owner | Vanilla `PlayerHitPlayerPacket` inherits `HitCharacter`'s default `processServer`; the patched child adds an override. Child-only Advice will not intercept that original execution. Target the actual declaring/default boundary with exact filtering or intercept the envelope invocation. |
| Validation/anti-cheat | Preserve vanilla packet consistency and anti-cheat ordering. Validate connection-owned live real attacker, equipped weapon, bounded hit count, finite range/floor, PvP/safety/faction/non-PvP zones, line of attack and victim life. Reject replay/duplicate application. |
| Native damage bridge | Apply native damage once, import before/after health loss into custom state, and sanitize fall/target effects. Do not perform a second weapon roll or damage application. |
| `Hit.roll`, `Hit.process` and `BodyPart.ReduceHealth` | Preserve server-rolled body-part decisions, force/direction and the NPC-specific bypass of delayed disconnect logic for an attacker with no connection. Apply the NPC health multiplier at the appropriate pre-clamp point; preserve real-player/animal behavior. |
| `PlayerHitSquarePacket` | Preserve missed/no-character diagnostics where applicable. This is not authorization to synthesize NPC damage or retaliation. |
| NPC-originated hit/state packets | Keep native packet format, native effects/maintenance/XP callbacks and the committed-hit contract; do not reroll already-authored damage. Revalidate target identity/life at application. |

See [player attacks](../../42.22.2/src/zombie/apocalipse/humans/ServerHumanPlayerAttacks.java),
[player damage](../../42.22.2/src/zombie/apocalipse/humans/ServerHumanPlayerDamage.java),
[committed hit](../../42.22.2/src/zombie/apocalipse/humans/ServerHumanCommittedHit.java),
and the patched [hit envelope](../../42.22.2/src/zombie/network/packets/hit/PlayerHitPacket.java).
Preserve the distinction between an attacker's god/invisibility flags and victim
immunity/retaliation policy. Do not weaken global packet validation to accommodate
detached NPCs.

### Zombie authority, corpse dragging and relay

The custom controller is not effective merely because it can call native zombie
methods. Its ownership transitions depend on patched native entry points:

| Owner | Required integration |
| --- | --- |
| `NetworkZombieManager.updateAuth(IsoZombie)` | Acquire/retain server NPC or corpse-drag control before ordinary ownership selection; keep those zombies server-owned. Preserve ordinary zombie owner/cooldown behavior. |
| `NetworkZombieManager.moveZombie` | Prevent conflicting non-null reassignment while controlled. Acquisition must still perform native ownership-list bookkeeping; release must avoid immediate re-acquisition through reentrant hooks. |
| `IsoGameCharacter.hitConsequences` | Notify the controller of a zombie hit at the intended point relative to native damage and hit reaction. |
| `NetworkZombiePacker` frame/auth phase | Begin controller frames and pump native path results before per-zombie ownership evaluation. |
| Packer accepted receive path | Observe incoming state only after native owner validation; a null owner does not authorize arbitrary inbound packets. |
| Packer send path | Include controlled/corpse-dragged zombies even without a client owner; preserve viewer eligibility and overlay authored target/position/state/drag flags. |
| Packer reset/world teardown | Clear control and drag state, pending paths and frame data; reject stale pooled identities. |

Sources: [ServerZombieController](../../42.22.2/src/zombie/apocalipse/zombies/ServerZombieController.java)
and [NetworkZombiePacker](../../42.22.2/src/zombie/popman/NetworkZombiePacker.java).
Death, stairs, reactions, identity reuse and release/reclaim ordering need focused
coverage. Preserving native owner lists matters as much as the feature's map.

The legacy packer also contains independent transport/index/cadence/batch
optimizations. `ServerZombiePacketPreparation` is reached from native patches
and is therefore outside the seed's lexical custom closure. It uses added
`ZombiePacket.prepareFrame/setPrepared` and
`NetworkZombieAI.updatePacketBookkeeping/setPacketFields` APIs. Do not treat this
whole optimized pipeline as a mandatory prerequisite. A candidate vanilla port
keeps native packet construction/bookkeeping and applies the required NPC
overlay at a verified boundary. It must prove equivalent flags, eligibility and
ordering before claiming compatibility.

### Pathfinding

[ServerZombiePath](../../42.22.2/src/zombie/apocalipse/zombies/ServerZombiePath.java)
uses its own walk-only mover identity, native asynchronous requests/cancellation
and main-thread result pumping, with a PolygonalMap2 fallback. This is distinct
from the copied-grid planner worker. Preserve bounded paths, cancellation,
generation/identity checks, pooled result copying, death and floor/stair checks.

Vanilla `PMMover.set(PathFindRequest)` rejects unsupported custom movers. The
legacy branch recognizes `WalkOnlyMover`, selects animal/generic graph rules and
disables crawl/thump/fence capabilities without claiming the mover is a live
zombie. Port this narrow existing-method behavior; preserve the ordinary request
field copying and both existing overloads. Do not accidentally instrument only
`set(PMMover)`.

Existing collision and standability APIs are available. Old PolygonalMap2
thread-local scratch changes and vehicle graph batching are not automatically
required by the current detached-worker design. Establish thread ownership
before deciding any native path optimization belongs to this feature.

## Persistence and shared services

[ApocBRNpcStore](../../42.22.2/src/zombie/savefile/ApocBRNpcStore.java) has its own
database worker, detached snapshots and profile loading. Server-thread capture
and application remain separate. Persistence requires the shared durable journal;
unhealthy persistence pauses actor activity and rejects mutations rather than
silently pretending a checkpoint succeeded.

The store holds a scope advisory lock on a long-lived connection, uses
`npc_engine.scope` fencing/epochs and validates server/world ownership. Pool
budgeting must account for that retained session plus replay/query work. A
one-connection configuration can starve other services. Keep migration ownership
and DDL separate from normal engine queries.

| Store area | Native/custom dependency and acceptance requirement |
| --- | --- |
| Profiles, spawns and instances | `profile`, revisions/parameters/modules/movement/survival, `server_spawn`, `instance`, `runtime_state`, group membership and scope; preserve logical IDs, revisions and incarnations. |
| Dialogue | Dialogue revisions, nodes, choices, text/labels, actions/behavior parameters, profile links and instance flags; preserve the validated factory/behavior allowlist. |
| Mortality | Loot tables/entries, respawn profiles and checkpoint ordering; no duplicate death/loot/revival after replay. |
| Relationships/encounters | Player relationships, encounters and event receipts; stable player identity and idempotent replay. |
| Speech | Rules, pool lines and memory; retain scope/revision and cooldown persistence. |
| Sales | Shared journal/operation receipts, `player_rel` sale storage, real player inventory/save integration and external credit consumer. |

The 43-source shared closure includes full relational player reconstruction,
login, dictionary/provenance, backend/event and telemetry dependencies. That is
a **coupling inventory**, not a requirement to migrate every service unchanged.
Extract explicit shared ports and immutable data contracts. Existing journal
type names/serialized payloads and pending records need a deliberate compatibility
policy; never discard them or report them replayed without executing their work.

At audit time the root `PostgresModule` wires account behavior, not the complete
NPC gameplay/store/sales/reconstruction graph. Its existence does not make this
dependency ready. See the [PostgreSQL feature status](../features/postgresql.md).

The engine role `pz_server_01` requires schema/table/sequence access in
**`npc_engine` as well as `player_rel`**. Existing public/player_rel grants do not
cover a new schema. Preserve creator-role default privileges and explicit grants
for existing objects; ownership/DDL is a separate requirement. Later database
validation must use isolated migration and engine roles and actual engine
queries. No database changes were made for this audit.

## Missing APIs and structural compatibility

The [compiler diagnostics](2026-11-21-npc-dependencies/javac-diagnostics.txt)
identify these missing original-JAR contracts:

| Source | Missing legacy extension | Classification |
| --- | --- | --- |
| `ServerHumanTransport` | Three ChatServer human chat methods | Direct bot transport dependency |
| `ServerHumanFurnitureWorld` | `IsoObject.getApocbrLifetimeRevision()` | Direct object validity dependency |
| `ServerZombieNativeAdapter` | `IsoCell.getPlayerSpatialQueries()` | Direct cache invalidation coupling; replace with the selected vanilla/adapted perception contract |
| `ApocBRServerTelemetryLite` | `ServerMap.telemetryPendingCells()` | Shared closure telemetry dependency |
| `ApocBRAsyncLogin` | `ServerWorldDatabase.LoginRoles` (two diagnostics), `useLoginRoles` | Shared closure login dependency |
| `ApocBRPlayerMigrationVerification` | `ServerPlayerDB.encodePostgresClientProfile` | Shared player reconstruction dependency |

These total ten compiler diagnostics; they are not ten exhaustive integration
boundaries. Reflection, concrete collection assumptions, missing call sites and
inherited method ownership require the separate behavioral analysis above.

The original archive has signing entries `PROJECTZ.SF`/`PROJECTZ.EC`. Keeping
unsigned helpers in existing signed game packages in the same loader risks
signer mismatch. Move implementations into agent-owned `cloud.apocalipse...`
packages and adjust adapters/subclasses; do not bundle native game classes.
The census found protected native dependencies in custom packet subclasses;
preserve legal subclass access when relocating them. Original class structure,
protected access and signature evidence must be checked against the actual JAR,
not a patched archive or loose class override.

## Implementation boundaries and acceptance plan

This ordering describes dependencies within a future complete port; it does not
authorize starting implementation or silently reducing the feature's scope.

1. Establish shared configuration, persistence/service contracts, signed-package
   relocation and the registered feature module. Declare all settings in
   `AgentConfiguration`; keep feature-owned state out of native class structure.
2. Wire recurring server-thread execution, shutdown/reset, IDs and data requests;
   demonstrate native execution reaches the production implementation.
3. Supply membership/lifetime adapters, world capabilities and the walk-only
   path boundary. Preserve detached bodies and stale-work rejection.
4. Integrate zombie/corpse authority, native frame ordering and packet overlays.
5. Integrate incoming/outgoing combat, chat, trade, Lua/dialogue and seating;
   preserve ordinary native behavior and authorization on every shared boundary.
6. Complete durable NPC store/sales/replay and external client/backend contracts;
   package and validate against the original archive before runtime acceptance.

| Verification level | Required evidence when implementing |
| --- | --- |
| Focused behavior | Worker ownership/cancellation/bounds; membership remove/reuse; furniture lifetime; identities/reservations; health/damage once; trade authorization/durable ordering; store failure/fencing and replay. |
| Actual transformed boundaries | Exercise production hooks for tick/quit, inherited parse/default processing, NPC data requests, PMMover, ownership and relay. Prove ordinary players/zombies continue native behavior and malformed NPC requests are rejected. |
| Exact original JAR | Transform declared owners and verify linkage without patched/loose classes; check packaged dependencies and signed-package behavior. Compilation alone is insufficient. |
| Isolated database | Real NPC/player_rel schema, separate creator/engine roles, scope locking, retained-session pool budget, checkpoint/restart, sales idempotence and pending legacy records. |
| Disposable server and compatible clients | NPC visibility/request/choreography; zombie acquire/release; real combat; dialogue/seating assets; trade/player save; world quit/reload and worker cleanup. Startup hook installation alone does not prove gameplay. |

Historical fixtures remain useful design evidence, but their target versions and
source extraction assumptions must be checked before reuse. No historical fixture
result is claimed as current agent acceptance. The current
[real-server integration guide](../validation/server-integration.md) describes the
opt-in harness; this report did not launch it.

## Coverage limits and remaining decisions

This report maps statically attributable APIs and the native behavioral changes
found by tracing the bot entry points and their indirect packet/authority paths.
It is not a whole-program proof. Lexical closure overincludes some shared
services and can miss reflective/indirect dependencies; compiler attribution is
partial where the ten errors occur. Native member deltas flag candidates, not
semantic equivalence. Asset, client mod, backend schema/credit consumer and
native-library availability were not executed or validated.

Final fingerprint recheck: the original JAR, all 13 captured artifacts and 473 of
474 captured source/context files were unchanged. `AGENTS.md` changed after
capture, so the strict `--verify` command correctly stops at that context drift.
The separate full hash comparison found no other drift, including in the legacy
sources and captured root implementation files. The dated manifest has been
preserved rather than rewritten to hide the change. All report/index links were
checked locally. No Maven/gameplay result is claimed for this documentation-only
audit; the compiler probe is intentionally recorded as failing.

Before implementing each boundary, select and test its precise instrumentation
shape. The significant design choices remaining are complete mutation tracking,
inherited/default packet interception, native zombie packet overlay placement,
chat construction/routing and shared persistence adapter ownership. None requires
defaulting back to adding game members or supporting the old patched runtime.

The engine's custom separation is useful, but **the safe port unit is the engine
plus these functional native contracts**. Unrelated legacy performance changes
can remain separate ports once those contracts are satisfied.
