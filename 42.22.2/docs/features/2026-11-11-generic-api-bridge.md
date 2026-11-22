# Server Lua API bridge (42.22.2)

## Configuration and ownership

`zombie.apocbr.api.ApocBrApiBridge` is an independent server-only Lua API.
It does not refactor, start or require the radio observation bridge.

- `apocbr.bridge.api.enabled` defaults to `true`; set it to `false` to disable.
- `apocbr.bridge.serverKey` supplies the **API credential**, verbatim, in
  `X-API-Key`. It is not normalized, derived from the server name or read from
  the radio bridge's API-key environment variable. Missing/invalid credentials
  disable this bridge with a fixed diagnostic.
- `apocbr.bridge.api.requestIntervalMs` defaults to 1500 and accepts 1500–60000.
  This is a global per-process limit, not a separate allowance for each Lua mod.
- `apocbr.bridge.enabled` has no effect on this API. If the observation bridge
  also runs, it continues to interpret `apocbr.bridge.serverKey` as its server
  scope; this new bridge does not change that existing contract.

`GameServer` initializes the API before `OnServerStarted`, and drains callbacks
from its simulation loop. `ServerMap.QueuedQuit` closes it before world teardown.
Initialization, request submission, callback state and shutdown belong to the
same server thread. Calls from a client or unrelated Java thread are rejected.
LuaManager exposes the facade and its nested `Endpoint` enum only on the server.
Lifecycle, queue and HTTP implementation classes are not exposed to Lua.

## Lua interface

```lua
-- Server-side Lua only. JSON parameters must be constructed from validated
-- server-owned player identity, not an unchecked client command payload.
local params = '{"runtimeUuid":"12345678-1234-4234-8234-123456789abc",'
    .. '"sourceServerId":2,"steamId":"76561198000000001"}'

local requestId = ApocBrApiBridge.request(
    ApocBrApiBridge.CHARACTER_BALANCE,
    params,
    function(response)
        if response.ok then
            print("Balance: " .. tostring(response.balance))
        else
            print("Balance request failed: " .. tostring(response.error))
        end
    end
)

if not requestId then
    print("Request rejected: " .. tostring(ApocBrApiBridge.getLastRejection()))
end
```

`CHARACTER_BALANCE` is a public facade alias for the nested enum constant
`ApocBrApiBridge.Endpoint.CHARACTER_BALANCE` in Java. The alias avoids relying on
Kahlua's nested-class table naming. The enum is explicitly registered with the
exposer, too. `isEnabled()` reports availability on the owning server thread.

`request(endpoint, parametersJson, callback)` requires a Lua closure and returns
an opaque UUID string on admission, or nil on immediate rejection. Rejected
calls do not invoke callbacks. Read `getLastRejection()` immediately after nil:
`UNAVAILABLE`, `INVALID_ARGUMENTS`, `INVALID_PARAMETERS`, `THROTTLED`,
`BACKEND_COOLDOWN`, `QUEUE_FULL`, or `STOPPED`. The last rejection is shared by
server Lua callers, not a per-player history.

Accepted requests receive one callback while the server remains running.
The callback is deferred to the server loop, never invoked by the HTTP worker.
Its table has `requestId`, boolean `ok`, numeric `status` (0 without an HTTP
response), and either numeric `balance` or string `error`. There are no URL,
credential, raw body or HTTP header fields. A protected call isolates Lua errors
from other completions. Shutdown cancels pending work and releases callbacks
without invoking Lua during teardown; it is not a flush or hot restart.

## Endpoint contract

### Login registration update (2026-11-11)

`CHARACTER_LOGIN` issues an authenticated empty-body POST to
`/api/v1/game/characters/{runtimeUuid}/bind`, with the same validated
`sourceServerId`/`steamId` query parameters. It returns the same decoded numeric
`balance` field. `CHARACTER_BALANCE` remains GET `/balance`. Both aliases and the
nested enum are available to server Lua. Method and route remain Java-owned;
the detached worker queue carries the method alongside the URI.

The backend ensures an exact-life/server binding through its shared login/reward
service, then returns account balance. It does not award credits or overwrite
conflicts. Replays/reconnects reuse the binding; a new life UUID gets a new binding.
The wallet uses POST for the first read of each native player session and GET
for later display refreshes. Bounded Lua registration retries are separate from
the transport, which still has no application-level automatic retry.

Deploy the backend POST first, then the full updated 42.22.2 bridge class families
and wallet mod. Lua reports an old bridge as `API_BRIDGE_OUTDATED`. There is no
SQL migration or new vanilla baseline. Credential/radio bridge behavior is unchanged.

The read-only enum entry is `CHARACTER_BALANCE`:

```text
GET https://plataforma.apocalipse.cloud/api/v1/game/characters/{runtimeUuid}/balance
    ?sourceServerId={sourceServerId}&steamId={steamId}
X-API-Key: <apocbr.bridge.serverKey>
```

Exactly three JSON fields are accepted: a canonical UUID string `runtimeUuid`,
a positive integral JSON number `sourceServerId` up to 9007199254740991, and a
17-digit **string** `steamId`. Strings cannot add path segments, parameters or
headers. Lua cannot supply another host, path, method, credential or timeout.
The request JSON is capped at 1024 characters and is never sent as a GET body.

A successful 2xx response must be a JSON object with numeric `balance`, e.g.
`{"balance":15000}`. The value must be finite and within Lua's safe numeric
range. Invalid successful bodies become `INVALID_RESPONSE`. All non-2xx statuses
become `HTTP_<status>`; their bodies are discarded. Transport errors use fixed
`TIMEOUT`, `RESPONSE_TOO_LARGE`, or `TRANSPORT_ERROR` codes.

Future endpoints require a Java enum entry with Java-owned validation, routing
and response decoding. There is no arbitrary URL escape hatch. The transport
supports GET and empty-body POST; additional methods/bodies require a Java change.

## Resource limits and abuse boundary

### Wallet login integration (2026-11-11)

The independent `ApocalipseBR-Wallet` Lua mod in the ExtractionMapUtils repository
now queues balance requests on login and caches responses on server/client.
It uses the existing server-only `ApocBRModEntities.character(player)` response:
`characterId` and `session` remain unchanged; `sourceServerId` now comes from
`apocbr.postgres.serverId`, and `steamId` is Java's exact unsigned string from the
native player. `IDENTITY_PENDING` still contains no identity fields. No JDBC or
client-supplied identity is introduced by this getter. The new fields require
deploying the updated `ApocBRModEntities` class as well as the API bridge.

The 42.22.2 scoped-entity fixture additionally verifies the two fields and exact
17-digit Steam ID preservation. Run with `APOCBR_TEST_VERSION=42.22.2`:
`py tools/test_character_mod_entities.py` (375 assertions passed).
The integration build also passed with 370 sources / 1061 classes retained at
`42.22.2/artifacts/build_20261111_182253/` (ignored); it was not deployed.

### Limits

Admission and actual HTTP sends are both paced, at most 40 requests per minute
with default settings and no burst allowance. One reusable JDK HttpClient and
one virtual worker allow one request in flight. At most 32 accepted requests
remain outstanding, including results waiting for Lua. Requests expire after
30 seconds in the queue; expired requests complete with `QUEUE_TIMEOUT` without
network activity. At most eight callbacks run per server update.

Connect timeout is five seconds. The whole HTTP exchange, including the response
body, is bounded to ten seconds. Bodies are capped at 16 KiB. Redirects are not
followed. There are no application-level retries. HTTP 429 suspends admission
and sends for at least 60 seconds, honoring longer Retry-After seconds or an HTTP
date up to one hour. Queued requests may expire during that cooldown. Shutdown
interrupts the worker and cancels its HTTP future without joining on the tick.

These limits bound backend traffic even if a server mod submits repeatedly.
They are not player authentication, per-player fairness or a distributed quota.
Mods handling client commands must derive/authorize identity on the server;
client-supplied Steam IDs, runtime UUIDs and arbitrary callbacks are not trusted.
An abusive caller can consume the shared allowance. Other JVMs and the radio
bridge have separate limiters. No new client packet or client Lua API is added.
Endpoints and credentials stay in server Java; this is not a secrecy guarantee
against someone with access to server class files.

## Sources and validation

- [Lua facade and enum](../../src/zombie/apocbr/api/ApocBrApiBridge.java)
- [Server lifecycle and callbacks](../../src/zombie/apocbr/api/ApiBridgeLifecycle.java)
- [Detached queue, pacing and worker](../../src/zombie/apocbr/api/ApiDispatcher.java)
- [HTTP transport](../../src/zombie/apocbr/api/ApiHttpTransport.java)
- [Lua exposure](../../src/zombie/Lua/LuaManager.java)
- [Server loop](../../src/zombie/network/GameServer.java)
- [Shutdown](../../src/zombie/network/ServerMap.java)
- [Inherited radio bridge contract](../../../42.21.0/docs/features/2026-10-13-backend-bridge.md)

The existing target patches were extended in place. No new vanilla replacement
baseline was needed, and `decompiled/` remains untouched.

Run from the repository root:

```powershell
py tools/test_api_bridge.py
.\42.22.2\patchApocalipseBr.ps1 -BuildOnly
```

The fixture compiles the production 42.22.2 API sources, uses actual Kahlua for
Lua exposure and callback execution, and uses loopback HTTP with dummy keys.
Engine globals/logging are stubbed. It creates an unsigned game-JAR copy only
inside its temporary directory to support loose fixture classes. No installed
game JAR, live backend or credential is modified. Dedicated-server startup,
actual backend authorization, callback behavior under real multiplayer load,
and shutdown during world teardown still require an in-game smoke test.

Verified 2026-11-11: all 83 production-code assertions passed, including real
Kahlua facade/enum access and callback delivery, parameter validation, concurrent
spam admission, queue bounds/expiry, pacing, 429 cooldown, response limits,
whole-body timeout and nonblocking shutdown during an in-flight request.
The target `-BuildOnly` compilation passed with 370 sources / 1061 class files,
retained under `artifacts/build_20261111_181025/` (ignored). Documentation links,
index coverage, server-only exposure and lifecycle wiring checks passed. No
deployment or live backend request was performed.

Login-registration validation on 2026-11-11: all 89 assertions passed, including
`CHARACTER_LOGIN` exposure through real Kahlua, POST dispatch, empty request body,
dummy API-key header, and rejection of unsupported methods. The updated target
`-BuildOnly` compilation passed with 370 sources / 1061 class files, retained in
`artifacts/build_20261111_201218/` (ignored). Deploy the backend registration
endpoint first, then this Java patch and the wallet Lua update, and restart the
game server. No deployment or live request was performed during validation.
