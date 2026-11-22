# Engine observation bridge: 42.21.0 server

## Scope and activation

`src/zombie/network/ApocBRBackendBridge.java` owns the bounded observation queue
and Java 25 virtual delivery worker. `ApocBRBackendBridgeHooks` captures
engine values on their owning thread. `ApocBRRadioBridge` is a narrow server-Lua
API for the radio mod. No game objects or Lua tables enter the worker queue.

Capture is wired into the engine and disabled by default. To enable it, supply
JVM properties before server startup (use the manager catalog's normalized key):

```text
-Dapocbr.bridge.enabled=true
-Dapocbr.bridge.serverKey=pve
-Dapocbr.bridge.endpoint=https://your-manager.example/api/v1/radio/events
-Dapocbr.bridge.snapshotIntervalMs=300000
```

Set `APOCBR_BRIDGE_API_KEY` to an active integration token provisioned through the
manager's `/admin/integrations`. `APOCBR_BRIDGE_ENDPOINT` can also supply the full
event URL. Environment values take precedence over `apocbr.bridge.apiKey` and
`apocbr.bridge.endpoint` JVM properties. No real credential is stored in this repo.

The engine neither configures nor sends `seasonId`. The manager assigns its
current active season when ingesting the event. The snapshot interval
defaults to five minutes and is bounded to 1,000-300,000 ms. Invalid required configuration leaves capture
disabled and logs a fixed message without configuration values or credentials.
Initialization runs immediately before `OnServerStarted`; clean ServerMap
shutdown closes the bridge without waiting on the simulation thread.

Enabled startup now creates `ApocBRBackendHttpDelivery` and starts the bridge's
virtual worker. `-Dapocbr.bridge.captureOnly=true` explicitly retains the earlier
queue-only behavior: no HTTP adapter, URL or credential is required, and the
bounded queue eventually fills unless consumed separately.
Capture/delivery remain disabled unless `apocbr.bridge.enabled=true` is supplied.

The manager reference is `manager/docs/radio-orchestration.md` and the actual
`RadioCommands.WorldEvent` record inspected on October 13, 2026. It currently
exposes authenticated `POST /api/v1/radio/events` through `RadioIngestionController`.

## HTTP ingestion behavior

The reusable JDK HttpClient posts one event per request with `X-API-Key`, UTF-8
JSON and no redirect following. The endpoint must use HTTPS; literal loopback
HTTP/localhost is supported for local deployment and tests. URLs containing
credentials, query parameters or fragments are rejected. No request bodies,
credentials, endpoint values or backend error bodies are logged.

The wire request contains the WorldEvent fields below. `publicPayloadJson` is a
JSON **string containing an object**, matching the manager DTO, rather than a
nested JSON object. This uses the existing `org.json` classes shipped in the game
JAR; no extra runtime library is installed. A payload over the manager's 4,000
character limit is rejected locally, counted and skipped without truncating facts.

Only HTTP 200 with a valid UUID `id` acknowledges an event. The adapter retains a
cursor and serialized bodies within the current batch. On partial success it
retries the first unacknowledged event with the identical body/ID/timestamps and
then continues; acknowledged events are not sent again. A lost response may
replay an accepted event, relying on the manager's source-identity deduplication.

| Result | Policy |
| --- | --- |
| 200 with valid UUID | Acknowledge and advance |
| I/O error, timeout, malformed/oversized acknowledgment | Retry current event |
| 408, 409, 425 or 5xx | Retry in a fresh HTTP request; honor Retry-After when supplied |
| 429 | Wait at least 60 seconds, or the longer Retry-After seconds/date, bounded to one hour |
| 401, 403, 404, 405 or redirect | Mark bridge FAILED, retain outstanding data, stop capture/delivery; correct configuration and restart |
| Other 4xx (including validation/conflicting identity 400) | Count permanent event rejection and advance, so one bad event does not block the queue |

Requests are paced at 1,500 ms by default (about 40/minute), because the manager
shares a 50/minute per-IP/per-key quota across all API-v1 integrations. The
`apocbr.bridge.requestIntervalMs` override accepts 1,250-60,000 ms. This limiter is
per bridge, not a distributed reservation: multiple game servers or integrations
sharing an IP/key can still receive 429. A batch is an internal queue optimization,
not a backend bulk request. High event rates therefore build a backlog and can
hit the existing queue limit; near-real-time delivery depends on available quota.

`apocbr.bridge.connectTimeoutMs` defaults to 5,000 (range 1-30,000), and
`apocbr.bridge.requestTimeoutMs` to 10,000 (range 1-60,000). The whole exchange,
including the response body, has a deadline. Responses are bounded to 8 KiB.
Interrupting shutdown cancels the current HTTP future and shuts down the client
from the worker, without joining it on the simulation thread.

`current().status()` reports acknowledged completed-batch totals separately from
`rejectedDelivery`, plus safe `lastDeliveryError` and retry/failure counters.
`ApocBRBackendBridgeHooks.httpStatus()` reports per-event requests/acknowledgments/
rejections (including partial-batch progress), last HTTP status and safe error
code. Error-code diagnostics are throttled to one per minute; terminal failures
always report. Delivery progress logs are enabled by default: batch collection
and retry/resume, event packing (type and UTF-8 byte count), request attempts,
HTTP status and elapsed milliseconds, acknowledged events, rejections, failures,
and completed batches. These run on the delivery worker, with no per-tick logs.
All lines use `[ApocBR][Bridge] delivery:` and contain metadata only, never
payloads, chat, names, tokens, endpoint URLs or response bodies. Set
`-Dapocbr.bridge.logDelivery=false` to silence progress while keeping startup
and throttled error diagnostics. A disabled server bridge now logs that state
at initialization rather than returning silently. An HTTP 200 response alone
does not count as ingestion: the following `acknowledged` line confirms that
the backend returned a valid observation UUID.

## Current state and meaningful changes

The capture helper polls cheap time/weather/heli getters at most once per real
second. It does not perform HTTP or log on simulation ticks.

| Data | Capture/publication policy |
| --- | --- |
| World clock | Initial state, every five minutes, plus calendar date or day-part changes. Day parts: night before 06:00 and from 22:00; morning 06:00-12:00; afternoon 12:00-18:00; evening 18:00-22:00. Always `WORLD_CLOCK_SNAPSHOT`, not news. |
| Weather | Initial state and five-minute freshness refreshes are `WEATHER_SNAPSHOT`. Meaningful changes publish `WEATHER_CHANGED` within one polling interval, including previous weather values. |
| Population | First telemetry sample, then at most once per two real minutes (rounded up to the next available telemetry emission). Ordinary samples are `POPULATION_SNAPSHOT`; a significant difference from the preceding published sample is `POPULATION_CHANGED`, including previous counts. |
| Helicopter | Initial baseline is silent; transitions only. |
| Deaths, skill milestones, presence, chat, radio | Distinct events retain their existing immediate capture and FIFO behavior. Rankings remain external. |

Weather comparison excludes capture/game timestamps, uptime and light-time drift.
Rain/snow use hysteresis: start at intensity >=0.05, stop below 0.01. Other
meaningful changes: temperature >=2 C, rain/snow/fog intensity delta >=0.2,
wind-speed delta >=10 km/h, shortest wind-direction delta >=45 degrees while
current wind is >=10 km/h, storm/blizzard transitions, or climate-season change.
Comparison uses the last successfully queued weather sample, so numerical drift
can accumulate to the threshold. The heartbeat resets that comparison baseline.
Population news requires an absolute change >=max(3 players, 25% of prior player
count), or >=max(100 loaded zombies, 25% of prior loaded zombie count). Missing
counts are not treated as zero. Thresholds advance only after queue acceptance.

The manager routes the three snapshot types from `pz-engine` to one latest-state
row per server/active season/source/type. New snapshots update current values and
freshness without inserting story observations. A compact receipt stores source
identity plus a content fingerprint for strict idempotency. Replays return the
same receipt UUID and do not refresh freshness; older samples cannot overwrite
newer state. Change types both refresh latest state and retain distinct story
observations. State-change freshness defaults to 15 minutes for weather and six
minutes for population. Fresh state supplements announcer context; it is not a
new story or a substitute for WORLD_DRIVEN evidence. Related supplied deaths and
milestones can be combined editorially in a bulletin while retaining their IDs.

The engine queue remains bounded FIFO, including snapshots. In-flight retries
retain the original immutable event ID/body; this change does not rewrite or
coalesce uncertain deliveries. Deploy the manager with radio Flyway V4 as well as
the engine patch to obtain separate current-state storage. Older manager builds
will still store snapshots as ordinary observations.

## Backend mapping and character names

| Bridge field | Manager WorldEvent argument |
| --- | --- |
| `serverKey` | Server scope, fixed for the instance; season is assigned by the backend |
| `source` | `pz-engine` |
| `sourceEventId` | Per-instance UUID plus ordered sequence; unchanged on retries |
| `type` | Enum name as the observation type |
| `occurredAt` | Capture-time UTC Unix milliseconds |
| `gameTime` | Source-formatted game calendar string, separate from real time |
| `publicPayload` | Allowlisted scalar fields; adapter serializes to `publicPayloadJson` |
| `expiresAt` | Optional UTC expiry, retained unchanged on retries |

`schemaVersion` is adapter metadata, currently **3** for the season-free envelope.
It is not an extra WorldEvent argument. Backend receipt
time belongs to the backend. Do not change captured content/timestamps during
retry; the manager rejects conflicting replay. Corrections use new observations.

Per the chosen integration contract, `characterName` is the server-side player
name (`IsoPlayer.getUsername()`), the name used by chat/user logs and manager
player-name matching. It is not a Steam ID, transient online ID, database row ID,
or generated incarnation UUID. `displayName` is additional presentation context.
The backend resolves character history from its mirrored Steam/character data,
server, its active season and occurrence time. Name reuse after death is resolved there;
importers must not permanently merge every life with the same name into one radio
subject. This does not change the backend's incarnation-aware subject requirement.
Listener-request names are separately labeled supplied attribution.

## Wired observations and source paths

All source paths below are relative to `src/zombie/` in 42.21.0. Existing patched
classes were extended in place. Only three new vanilla replacements were needed:
`network/chat/ChatServer`, `network/packets/WorldMessagePacket`, and `Lua/LuaManager`.
Their exact decompiled baselines were copied and committed separately before edits
using `tools/Copy-PZPatchBaseline.ps1`; no behavior changes were committed/pushed.

| Event | Capture point and payload |
| --- | --- |
| `PLAYER_CHAT` | `ChatServer.processMessageFromPlayerPacket`, after stream checks, word filtering and routing, restricted to general chat. `WorldMessagePacket.processServer`, after accepted global routing. The packet author must resolve to a player belonging to the sender connection. Payload: character name/display name, channel (`general`/`world`), game origin, text. |
| `PLAYER_CONNECTED` / `PLAYER_DISCONNECTED` | `GameServer.receivePlayerConnect`, after player identity and fully-connected state; `disconnectPlayer`, before removal. Capture requires the actual player object to be in `IDToPlayerMap`. These are character-presence events, not raw socket/login attempts. |
| `PLAYER_DEATH` | `IsoPlayer.onKilled`, reached through the existing `IsoGameCharacter.Kill` once-only guard. Payload: victim and optional known player killer names. Animals are excluded. Missing attacker does not imply infection or another exact death cause. |
| `CHARACTER_LEVEL_UP` | `IsoGameCharacter.LevelPerk` compares previous and resulting level. Successful `XP.load` compares the existing old-level map against the fully loaded state. Only online player increases in 1-10 are published; initial profile loads, reductions, unchanged values and capped-at-10 calls are skipped. Payload: character name, stable perk ID, previous/new level, observation basis (`server_level_transition` or `received_xp_state`). Received XP state is an observation, not independent proof of legitimate gameplay. |
| `POPULATION_SNAPSHOT` | `ApocBRServerTelemetryLite.emit` forwards the already-captured player count and loaded zombie count. Missing cell means the zombie count is absent, not zero. No second zombie traversal, private telemetry dump, or world-total claim is added. |
| `WORLD_CLOCK_SNAPSHOT` | Main-thread `GameServer` simulation tick calls the interval-limited helper. Payload: `worldAgeHours` and real bridge uptime milliseconds; envelope contains normalized game date/time and real UTC time. |
| `WEATHER_SNAPSHOT` | Same interval; uses `ClimateManager` and `ErosionSeason` getters for temperature Celsius, rain/snow/fog intensity, wind km/h and degrees, thunderstorm/blizzard state, climate season, dawn/dusk hours and daylight duration. Climate season is distinct from manager `seasonId`. |
| `HELICOPTER_STATE` | Existing `GameServer.sendHelicopter` calls plus one-second reconciliation. The first sample silently establishes a baseline; only subsequent transitions publish events. Deduplicates unchanged active state; excludes coordinates, target identity and future schedule. |
| `RADIO_CONTENT_STATE` | Radio server scheduler reports announced/started/completed/stopped for a song/talk once per sequence/phase. Contains station/content IDs, kind, sequence and caption. Heartbeats and play-to-transition changes do not repeat started events. |
| `RADIO_LISTENER_REQUEST` | Radio mod, only after whole request validation and atomic queue acceptance. Contains station, canonical song IDs, supplied name/message, song count and queue depth. Failed requests publish nothing. |
| `RADIO_LINE_AIRED` | Radio mod, after successful `SendTransmission`, for announcer, dedication, catalog-talk and request-notice lines. Contains station, line kind, text and `server_transmission` observation basis. |

Weather/time snapshots expire after three configured intervals. Population expires
after six minutes (three two-minute sampling intervals). Radio observations expire after five minutes. Chat, deaths,
presence, level-ups, weather/population change events and helicopter state have
no automatic expiry. These are capture defaults; expiry is not
recomputed on retry or automatically purged by this queue.

The world-age getter documents a consistent offset around its 07:00 boundary;
it is not precise player survival duration. Game calendar month/day indexes are
normalized for the source string. Deprecated `GameTime.getHoursSurvived` is unused.
Uptime starts when bridge capture is installed, not when the OS process starts.

Radio reporting requires the updated ApocalipseBRRadio server module. LuaManager
exposes only `ApocBRRadioBridge` on the server, not the engine owner/configuration
API. The mod uses optional protected calls, so unpatched servers and observation
failures retain normal playback. Scheduler completion and transmitted text are
not confirmed client audio playback. These observations must not automatically
advance the manager's aired continuity without trusted playback confirmation.

The vanilla sources for weather/time/season/helicopter getters were inspected
under `decompiled/zombie/`. They do not need new replacement classes: existing
GameServer callbacks and getters provide their capture points. The new LuaManager
replacement needed minimal decompiler repairs: split a reused File/List local in
LoadDirBase and cast logging nulls to Object, matching the JAR's original logging
overload verified with javap. No vanilla files under `decompiled/` were edited.

### Lua startup regression: requested versus resolved cache keys

The full LuaManager override also exposed a decompiler defect in
`RunLuaInternal`: the original JAR saves the normalized requested filename before
`ZomboidFileSystem.getString` resolves the physical filename. It uses that saved
key for `loaded` and `loadedReturn`, matching the lookup at method entry. The
decompiled baseline lost the saved local and wrote the resolved filename instead.
On Windows, differing path forms/separators can make every subsequent `require`
miss the cache, re-execute files, reset globals and break circular dependencies.

The local October 13 startup log showed vanilla WorldGen biome scripts indexing
missing feature tables, then termination with `Need at least one of 'biome' or
'prefab' in WorldGenOverride.lua/worlgen.static_modules`. These are consistent
with that loader regression; the failure precedes bridge startup/HTTP delivery.
The patched method now retains `requestedFilename` for both caches while retaining
the resolved IO path for reading and `filenameToClosure`, matching JAR bytecode.
The vanilla decompiled baseline remains untouched.

`tools/test_lua_manager_cache.py` verifies the original JAR's saved-local/cache
instructions and executes the production loading methods with a path-resolving
filesystem fixture. It failed before the repair and passes afterward, covering
return-value caching, dependency/global preservation, nil/error returns, circular
require protection and loading-guard cleanup. This is isolated loader validation,
not a claim that the live modded server has completed startup. Rebuild/reapply the
patch and retry startup to check for any remaining independent mod/map failures.

## Queue, failures and delivery contract

- Default capacity: 1,024 queued observations plus one batch of up to 100 more.
  Payload strings have length bounds. Publication uses non-waiting `offer`; a
  short lifecycle lock orders sequence assignment and insertion. No network,
  serialization or capacity waits occur while holding that lock.
- Overflow rejects the new event, returns false and increments `droppedFull`.
  NEW/RUNNING accept; STOPPED/FAILED reject. Status/capture-error counters are
  operational diagnostics, not public radio evidence.
- One delivery call is in flight. An idle worker blocks on the queue, then batches
  for up to 200 ms or until full. It does not send empty requests or scan the world.
- Delivery success means every event in the batch was acknowledged or explicitly
  counted as permanently rejected. Failures/uncertain acknowledgment retain the batch with
  exponential backoff (1-30 s by default). Later batches cannot overtake it.
  The HTTP adapter resumes from its saved cursor, preserving partial progress.
- Capture runtime exceptions are isolated from original game operations. Queue
  overflow, radio capture failure or disabled capture does not cancel gameplay.
  Exception messages, prompts, private coordinates and credentials are not logged.
- `close` is terminal cancellation, not a flush. It interrupts delivery and retains
  outstanding data in memory until instance release. `awaitTermination` is for a
  non-game-thread owner. No disk spool, crash replay, restart or exactly-once
  capture guarantee exists.
- Snapshots share the FIFO/overflow policy with discrete events. Coalescing,
  expiry purging, jitter, graceful flush and durable storage remain later work.
  A closed/failed instance cannot restart; replace it through a future controlled
  server lifecycle rather than mutating its identity while events are pending.

Private whisper/faction/safehouse/admin chat, Steam IDs, inventory, moderation data
and precise player positions are not published. Public chat/name/listener text
remains untrusted prompt data. Meaningful weather changes are distinct
`WEATHER_CHANGED` news events; unchanged weather snapshots only refresh backend state. Loaded zombie count changes can reflect
streaming/player distribution; they do not prove a horde arrived or a region is safe.

Electrical grid state, water supply, and rankings are excluded as requested.
The external ranking system remains the ranking source; only skill level-up
milestones are added here. Full medical death causes, survival-day milestones and
other achievements are not inferred from unavailable facts.

## Validation

Commands run against the production sources:

```powershell
python tools/test_backend_bridge.py
python tools/test_backend_bridge_hooks.py
python tools/test_backend_observation_cadence.py
python tools/test_backend_http_delivery.py
python tools/test_lua_manager_cache.py
python tools/test_player_packet_algorithms.py
python tools/test_simulation_algorithms.py
python tools/test_packet_ownership_guard.py
.\42.21.0\patchApocalipseBr.ps1 -PZDir "Z:\SteamLibrary\steamapps\common\ProjectZomboid" -DryRun
```

The bridge fixture covers bounds, FIFO, immutable retries, concurrent producers,
virtual delivery, blocked requests and shutdown. Hook fixtures cover client and
offline exclusion, sender ownership, name-based payloads, perk increases,
telemetry forwarding, snapshot cadence/calendar formatting, helicopter change
deduplication, radio validation, error isolation and source call-site contracts.
The radio repository's `npm test` includes a behavioral bridge fixture for
accepted/rejected requests, once-per-phase events, actual transmitted lines,
missing/throwing bridge behavior and single-player exclusion.

HTTP fixtures use a loopback stub with dummy credentials. They cover actual POST
mapping/escaping, API-key headers, pacing, partial-batch replay, valid UUID
acknowledgments, 400 isolation, 401/403/route failures, 409/429 policies, malformed
and oversized responses, whole-body timeouts, worker shutdown and startup wiring.

All listed fixtures passed. Full 42.21.0 dry-run compilation passed with 97 source
files, including the new class families. No deployment or live backend requests
were performed. Native Kahlua exposure, multiplayer connect/reconnect/death/respawn,
XP updates, climate/heli transitions, queue saturation and real shutdown still
need an in-game smoke test after deployment. Production credentials, reverse-proxy
TLS, shared quota and actual manager ingestion remain live integration checks.
