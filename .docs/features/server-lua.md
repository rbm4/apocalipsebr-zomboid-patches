# Server Lua APIs and bounded service workers

Date: 2026-11-21. Target: original 42.22.2 JAR and the root Byte Buddy agent.
Status: implementation and targeted execution checks are present; full server/mod/database acceptance remains pending.

## Selected scope

This session ports the server-side Lua exposures requested from
`42.22.2/src/zombie/Lua/LuaManager.java`, plus their generator, streaming,
event, radio, market/API, account-worker and successful-login dependencies.
The matching `42.22.2/decompiled` owners establish original signatures and
native behavior. No version-directory implementation or original archive is modified.

PostgreSQL player reconstruction and its async profile/player-connect
continuations are owned by another session, as confirmed by the user.
They are not silently replaced with worker execution of SQLite/native actor loading.
The successful-login observer runs after native player acceptance in either
storage mode. Its optional relational character UUID enrichment is null until
reconstruction exposes an authoritative agent-owned binding.

## Lua compatibility

The server receives these same Lua global names:

| Lua global | Agent package below `cloud.apocalipse.pz.agent.features.lua` |
| --- | --- |
| `ApocBRLobbyStreaming` | `streaming` |
| `ApocBRManagedGenerators` | `streaming` |
| `ApocBREventControl` | `events` |
| `ApocBRRadioBridge` | `radio` |
| `ApocBrApiBridge`, its `Endpoint` enum and endpoint constants | `api` |
| `ApocBRPlayerLogin` | `login` |
| `IsoPowerBank`, `PowerBankBackup` | `power` |

Classes live outside the signed native `zombie.*` packages. Mods using these
Lua globals retain their names. Hardcoded Java class-name strings must use the
agent package. Client Lua receives none of these server exposures.

The legacy backup patch added instance methods to `IsoGenerator`. The agent
keeps its JVM structure unchanged and binds those five method names into the
native generator Lua method table after vanilla exposure. Existing server Lua
colon calls continue to work:

```lua
generator:applyISABackupState(fuel, condition, supply)
local owner = generator:getISABackupOwner()
generator:resetISABackupClock()
generator:setISABackupOwner(coordinates) -- or nil
local allowed = generator:isISABackupActivationAllowed(supply)
```

The bindings delegate to `PowerBankBackup` static adapters, which are also
available to Java integrations and Lua callers that prefer explicit functions.
`claim` / `release`, `enable`, `isControlled`, `stop` and the backup update hook
remain available. Ownership getters return copies. Existing native Lua methods
are never overwritten; an unexpected method-table conflict fails initialization.
Server mod files have not been modified.

`IsoPowerBank` remains an agent-owned subclass of native `IsoGenerator`; its own
simulation methods remain instance methods. Its native object name, class
identifier 22, sprite detection, save representation and generator packet
layout remain unchanged. It uses narrow reflective adapters for the original
private refrigeration/electricity/hour mechanics rather than requiring patched
protected methods.

## Installed execution paths

`AgentModules.all()` registers `server-lua` and conditional `async-accounts`.
Each declares its installation contracts through `hookChecks()`. Enabled modules
preflight classpath bytecode and every declared helper invocation without
defining game classes before `AgentRuntime` installs the pipeline. Missing
signatures or changed invocation anchors abort startup. Native-linked cleanup callbacks
are registered after installation/startup; they must not preload native targets
before the transformer exists.

| Original owner / boundary | Production behavior |
| --- | --- |
| `LuaManager.Exposer.exposeAll()`, immediately before its collected-type iteration and at normal exit | Adds server classes only after all original registrations, preserving native network enum IDs. Uses native `setExposed`, then installs the Lua-only generator compatibility methods after vanilla exposure. |
| `LuaManager.init()` normal exit | Initializes server-owned generator/backup/streaming state and registers `OnPowerBankUpdate`, `OnPowerBankBackupUpdate`, `OnApocBRLobbyEventDue`. Original initialization runs first. |
| `GameServer.startServer()` entry | Starts configured event polling, API dispatch, radio delivery and login-audit journal before native `OnServerStarted`. Native socket/startup work continues. |
| `GameServer.main`, immediately before native `LoginQueue.update()` | Drains API callbacks, installs event snapshots, dispatches due events and reconciles managed generators even on empty/paused servers. A separate registered hook drains account/admin continuations. |
| `LoadedAreas.set()`, before `compareWithPrev()` | Adds the temporary lobby population area only to the non-server-cell view. Native change detection includes the added area. |
| `IsoObject.factoryFromFileInput(IsoCell, ByteBuffer)` exit | Reconstructs the server solar subtype for native generator payloads with the solar sprite. Keeps the input at the native sprite boundary; ordinary generators and other factory results continue unchanged. |
| `IsoGenerator.update()` entry | Runs backup scheduling; tagged managed generators without an admitted/materialized source skip simulation. Ordinary generators retain vanilla execution. |
| `IsoGenerator.update()`, hourly depletion interval | Managed/owned backup sources bypass native fuel/wear/fire accounting. Native sound, toxicity, hour advancement, synchronization and deferred electricity work remain in their original order. |
| `IsoGenerator.addToWorld/removeFromWorld` | Tracks integrated managed generators and unload/removal. |
| `IsoGenerator.load/save` | Captures backup ownership from native deserialization and projects authoritative ownership during serialization, including chunk-worker load/save. Ownership is stored separately from incoming mod data. |
| Generator `remove`, setters and `syncIsoObjectReceive`; `GameServer.RemoveItemFromMap` | Protects managed objects and owned backup activation, rejects client authority over protected state, consumes the original packet payload and corrects the sender. Disconnecting an owned backup releases ownership. |
| `GameServer.receivePlayerConnect` normal exit | Observes accepted player slots, tracks same-connection respawns and queues one successful-login audit per accepted player object. Native player loading/acceptance stays with its existing owner. |
| `LoginPacket`, `GoogleAuthPacket`, `GoogleAuthKeyPacket.processServer` | With account workers enabled, snapshots packet values, runs bounded account JDBC work and resumes connection changes on the server thread. Two-factor proof is tied to its connection, user and expiry. Disabled hooks leave the native packet implementations in place. |
| `RequestUserLogPacket.processServer/setData` | Executes the user-log query on the bounded admin worker, rechecks permission when applying the result and supplies detached rows to native response serialization without issuing another query on the server thread. |
| Account class and its original `LogonResult` constructor | Uses worker-owned role copies and captured options, world, Steam mode, admin name and username validation. Date formatting/ASCII encoding receive independent worker instances. Server-thread reads retain their native behavior. |
| `ServerMap.QueuedQuit` entry/exit and `GameServer.main` exceptional/normal exit | Stops services/workers, then clears world-owned state after vanilla final saves. Backup ownership survives final serialization. Shared-pool shutdown also stops registered consumers before closing the pool. JVM hooks provide terminal cleanup. |

All native members retain their original structure. Helpers, advice and the
bounded invocation visitors are agent-owned; there are no complete-class native replacements.

## Ownership, bounds and failure behavior

Lua, connection changes, generator mutations, source reconciliation and
population integration belong to the server thread. Native deserialization and
serialization may capture/project a detached backup-ownership copy on their
existing chunk worker. The ownership map uses weak keys and synchronized
publication; Lua receives copies, not its private table.

API dispatch has one virtual worker, 32 outstanding requests, admission and wire
pacing, queue-age expiry and a result/callback budget of eight per loop. Workers
receive URI/method/body values, never Lua closures. Endpoints remain allowlisted
balance/bind/debit/credit operations. Item custody uses the bounded native item
codec. Invalid parameters are rejected before HTTP; errors never expose backend
bodies. HTTP deadlines, response-size limits and cooldown handling are preserved.
Shutdown interrupts and joins the worker and discards callbacks.

Account/admin workers each use two daemon threads and bounded queues/admission
of 32 connections. Login phases are coalesced; conflicting phases fail closed.
Each continuation validates the original connection identity, world, Steam
identity and expiry. Login results retain their capacity until consumed or
cancelled. Each drain processes at most two callbacks / two milliseconds per
worker group. JDBC budgets share the existing agent account implementation.
SQL failures swallowed by vanilla are still detected by the enclosing workflow.
Worker shutdown cancels retained results and joins both worker groups.

Event polling publishes immutable validated snapshots; only the server loop
installs them and invokes Lua. Stale/regressing/conflicting revisions do not
release a lobby event. Acknowledgements retain the exact event/revision identity.
Radio uses the legacy bounded detached event delivery core, but this port wires
only the requested radio Lua observations. Other historical backend chat,
weather, population and NPC observations are not automatically registered.

Login audits use `SuccessfulLoginJournal`, a separate `login-history-agent`
journal generation. The SQL routine receives a stable event UUID, so retries
preserve audit identity. The worker commits before acknowledging the journal;
readiness is published only after the database decision. Pending work survives
graceful shutdown and reopening. This does not consume, discard or mark drained
an unported legacy mixed persistence journal. Login-history capture failures
leave gameplay acceptance intact and origin unknown, with a sanitized diagnostic.

## Configuration

All JVM properties and participating environment variables are declared/read
through `AgentConfiguration`. Configuration is selected at startup, not a hot-switch API.

| Setting | Default / requirement |
| --- | --- |
| `apocbr.lua.enabled` | `true`; false installs none of the Lua module's hooks/exposures. |
| `apocbr.postgres.async.enabled` | `true` with enabled PostgreSQL account hooks; independent of the Lua master flag. False retains synchronous native packet/account execution. |
| `apocbr.bridge.api.enabled` | `true`; usable only with a valid `apocbr.bridge.serverKey` credential. Invalid/missing API configuration disables this service with diagnostics. |
| `apocbr.bridge.api.requestIntervalMs` | 1500; range 1500..60000. |
| `apocbr.events.enabled` | `false`; requires enabled PostgreSQL and the existing event-control schema. |
| `apocbr.events.serverKey` | Native server name; matches the backend event-control scope. |
| `apocbr.events.pollMillis`, `freshMillis` | 30000 / 90000; validated bounds and freshness at least twice polling. |
| `apocbr.events.displayZone` | `America/Fortaleza`. |
| `apocbr.bridge.enabled`, `captureOnly` | `false` / `false`; radio observation/delivery switch and diagnostic capture-only mode. |
| `apocbr.bridge.serverKey` | Existing shared API credential / legacy radio event scope setting. |
| `apocbr.bridge.endpoint`, `apiKey` | Radio delivery requires endpoint/credential; `APOCBR_BRIDGE_ENDPOINT` / `APOCBR_BRIDGE_API_KEY` take precedence. |
| `apocbr.bridge.connectTimeoutMs`, `requestTimeoutMs`, `requestIntervalMs`, `logDelivery` | 5000 / 10000 / 1500 / true; original HTTP configuration validation retained. |
| `apocbr.postgres.loginHistory.enabled` | `true` when PostgreSQL is enabled. |
| `apocbr.postgres.serverId` | Required registered positive server ID for successful audit capture. |
| `apocbr.postgres.loginHistory.journalDirectory` | Optional separate audit directory; default `<saveDir>/Multiplayer/<serverName>/login-history-agent`. |

The API/radio legacy use of `bridge.serverKey` is preserved rather than silently
reinterpreting its existing two roles. Review the actual configured service
values before enabling both. No new schema or live credentials are installed.
The engine role needs access to the existing event-control table and execution
of `player_rel.record_successful_login`, including all objects accessed by that
routine. Follow the PostgreSQL guide's separate migration/engine-role grant
policy. Actual routine/table permissions still need isolated database acceptance.

## Validation and remaining acceptance

The original archive used on Windows / Java 25 has SHA-256:

`3C4A7C5F149D784758C6FB5F8FA85F3A21D572E38BC081400E89035F8120D287`.

Seven focused tests cover all declared original-bytecode hook invocations and
unchanged native members, API endpoint validation/queue bounds/cooldown/cleanup,
event freshness/revisions and durable audit identity/reopening/prepared bindings.
Earlier root `mvn test` and packaging passed during implementation. Concurrent
sessions subsequently changed shared build inputs; the latest Lua sources were
compiled/tested in isolated output against the last packaged agent, without
building or rewriting those other sessions' work. That isolated artifact is
validation evidence, not a final merged deployment artifact. A fresh root
`mvn package` is still required after the shared tree stabilizes.

`tools/Test-LuaAgent.ps1` compiles only the smoke fixtures into a unique run,
copies the supplied agent artifact and launches two bounded child JVMs. The
original JAR precedes the agent and those fixtures; root `target/test-classes`
and loose native overrides are absent. Service environment overrides are
removed. The test never invokes `GameServer.main`, opens a real database/socket,
or loads a production world. Lua exposure uses game resources and an isolated
cache. Each run retains raw logs, artifact/original hashes and fixture hashes.

```powershell
# Run against a freshly packaged artifact when shared Maven work is idle:
.\tools\Test-LuaAgent.ps1 -AgentJar .\target\apocbr-agent.jar
```

The original-first premain checks execute real Lua exposure and initialization,
client exclusion and unchanged native enum IDs, power-bank reconstruction and
packet authority, backup claim/activation/sync/release and legacy Lua colon-call
compatibility, and ordinary/suspended generator update paths. A minimal
transformed lifecycle body verifies startup continuation, server-loop delivery
of an actual Kahlua API callback and registered due-event callback, plus cleanup.
The account check executes actual transformed admin/login packet and account
classes with fake JDBC/transport: detached role ownership, stale result discard,
admin response caching, failed-read suppression, denied authentication,
continuation ownership, active-worker cancellation and JDBC lease cleanup.

Remaining live acceptance is explicit: solar energy catch-up and backup behavior
with the real server mod; source prewarm/reconciliation, population activation,
chunk unload/reload and final saves; event polling/receipts/role grants against
an isolated schema; audit replay across crashes/commit-response loss with the
real routine; successful login/two-factor/account-write/backend market flows;
market item custody/credit/debit idempotency and radio delivery. No deployment,
production database change or complete live feature acceptance is claimed.
