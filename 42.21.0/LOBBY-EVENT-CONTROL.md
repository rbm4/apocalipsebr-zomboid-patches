# PostgreSQL lobby event control — 42.21.0

Status: implemented, isolated tests and target-JAR compilation pass; live
PostgreSQL/engine/mod integration remains unvalidated. Server-only Java patch;
the extraction Lua mod must also be installed on server and clients. No changes
to the game JAR or client Java are required.

## Ownership and transport

`player_rel.lobby_event_control` is the durable integration boundary. The
reference DDL is [LOBBY-EVENT-CONTROL.sql](LOBBY-EVENT-CONTROL.sql). The **backend
owns the migration and its asynchronous synchronization work**. The game never
runs DDL, opens an HTTP endpoint, executes external Lua, or requires an online
player to relay a scheduled event.

The manager ships this contract as game-runtime migration
`V11__Create_Lobby_Event_Control.sql`. Both the engine queries and the reference
DDL target `player_rel.lobby_event_control`. Guide trade receipts use
`player_rel.apocbr_human_sale` from V10; the persistence journal's database receipts
remain in `public.apocbr_persistence_receipt` from V8. These are fully qualified
names, so changing the connection's search path cannot repair a schema mismatch.

The engine registers one row per configured server/world and updates its
heartbeat. The backend asynchronously discovers/synchronizes those rows,
publishes a next-event schedule into the desired columns, and consumes engine
receipts from the reported columns. Normal PostgreSQL permissions should give
the backend write access only to desired columns, and the game access to
registration/heartbeat/receipt fields plus SELECT. The existing game account
already has other persistence permissions; do not expose it to clients.

This version deliberately uses bounded polling, not a new LISTEN session: one
worker, one row, default 30 seconds, no command queue, and one pending receipt.
Reading durable state on every poll also handles missed synchronization,
reconnects and startup without a notification dependency. LISTEN can later
reduce notification latency while retaining these reconciliation reads.

## Enablement

Apply the reference DDL through a backend migration first. Enable the extraction
mod, existing PostgreSQL connection settings and optional startup guide, then
add JVM options before the Java main class:

```text
-Dapocbr.postgres.enabled=true
-Dapocbr.events.enabled=true
-Dapocbr.events.serverKey=extraction-test
-Dapocbr.events.displayZone=America/Fortaleza
-Dapocbr.guide.enabled=true
```

Credentials continue to come from the existing `APOCBR_POSTGRES_URL`/JVM
connector configuration; this feature introduces no credential transport.
`serverKey` defaults to `GameServer.serverName` and must contain 1–80 ASCII
letters/digits/underscores/hyphens. `world` is the engine's actual
`Core.gameSaveWorld`, not a client payload or the guide's location.
Use a unique serverKey/world pair for each concurrently running server process.

| Property | Default | Bounds/meaning |
| --- | --- | --- |
| `apocbr.events.enabled` | false | Server-only opt-in. PostgreSQL must also be enabled. |
| `apocbr.events.serverKey` | server name | Stable backend scope, matched exactly. |
| `apocbr.events.pollMillis` | 30000 | 1000–60000 ms between completed polls. |
| `apocbr.events.freshMillis` | 90000 | At least twice pollMillis, at most 300000. |
| `apocbr.events.displayZone` | America/Fortaleza | Valid Java zone ID; affects greeting display only. |

The worker uses existing pooled connections inside
`ApocBRPostgresConnector.runBackgroundDatabaseWork()`. Each prepared statement
has a two-second query timeout; acquisition/socket limits remain the connector's
limits. Connections are closed after each poll. No database call runs from Lua
getters, the guide, or the simulation/main loop. Errors log a code at most once
per 30 seconds. Missing schema/outage retries without preventing game startup;
activation waits for a valid fresh snapshot. Shutdown stops further worker
iterations; already-running JDBC remains subject to those timeouts.

## Backend release contract with v2 metadata

Primary key is `(server_key, world)`. Never delete/recreate an active control row
or change its scope. The engine creates an idle row when enabled and connected.
`engine_version='42.21.0/event-control-v2'` identifies this contract. Heartbeat
means the worker contacted the row; it does not assert a successful release.

**Backend-owned desired columns:**

- `desired_event_id`: UUID assigned by the backend to the event.
- `desired_revision`: monotonically increasing bigint per scope, 0 initially,
  maximum 9007199254740991 so Lua can represent it exactly.
- `desired_name`: nonblank single-line event name, at most 160 characters (the
  engine also enforces 160 UTF-16 code units).
- `desired_starts_at`: finite PostgreSQL `timestamptz`, after Unix epoch and before
  year 10000. Publish an explicit offset/UTC instant, never an unzoned local date.
- `desired_state`: `idle`, `scheduled`, or `cancelled`. Idle requires null event
  details. Scheduled/cancelled require complete details and a positive revision.

Use optimistic concurrency, binding parameters:

```sql
UPDATE player_rel.lobby_event_control
SET desired_event_id = :event_uuid,
    desired_revision = desired_revision + 1,
    desired_name = :event_name,
    desired_starts_at = :activation_instant,
    desired_state = 'scheduled'
WHERE server_key = :server_key AND world = :world
  AND desired_revision = :expected_revision
RETURNING desired_revision;
```

Zero returned rows is a missing scope or concurrent writer: reread instead of
blindly overwriting. Cancellation similarly increments the revision and sets
`desired_state='cancelled'`; preserve the event details. Rescheduling/name edits
also require a revision increase. The reference trigger rejects rollback and
same-revision changes. Cancellation is effective once observed before release;
it cannot reverse world removal already accepted by the engine. Polling has
finite latency, so cancellation is not an atomic transaction with the game world.

**Engine-owned columns:** `engine_version`, `engine_heartbeat_at`,
`reported_event_id`, `reported_revision`, `reported_state`, `reported_at`.
Receipts are initially null. `reported_state='release_requested'` means server
Lua accepted persistent desired-state removal of the **entire perimeter**.
It does not mean all unloaded chunks were edited or all loaded edits finished.
Match both reported ID and revision to the desired values before considering a
schedule acknowledged. Do not clear receipts when publishing a newer revision;
they remain evidence of the previous acceptance. Receipt updates are scoped and
monotonic: an older receipt cannot overwrite a higher reported revision. If the
backend changes/cancels the schedule before its prior acceptance receipt arrives,
that prior receipt is still recorded truthfully with its original ID/revision;
it must not be treated as acknowledgement of the replacement schedule. Delivery
to PostgreSQL is asynchronous and retries after I/O failure.

Backend sync must preserve ownership: ingest engine columns into backend state,
write only desired columns, avoid echoing stale full-row copies, and advance its
cursor/checkpoint only after its own database transaction commits. No backend
job/endpoint has been implemented here; the supplied SQL/contract is its input.

## Main-thread activation and Lua API

The worker publishes immutable snapshots into one atomic slot. The main loop
installs them before updating server humans and rejects revision rollback or
same-revision content changes. This keeps a callback's snapshot stable even if
the worker discovers another schedule concurrently. Real system time controls
the deadline; monotonic time controls freshness/retry throttling. `PauseEmpty`,
game clock acceleration, guide spawning and player count do not gate activation.

The server-only Lua class `ApocBREventControl` exposes cached getters:

```lua
ApocBREventControl.getStatus() -- DISABLED / STARTING / READY / STALE
ApocBREventControl.getEventId()
ApocBREventControl.getRevision()
ApocBREventControl.getEventName()
ApocBREventControl.getStartsAtMillis() -- real Unix milliseconds
ApocBREventControl.getEventContext(eventId, revision) -- fresh matching specification, or nil
ApocBREventControl.getScheduleState()
ApocBREventControl.isDue(eventId, revision)
ApocBREventControl.isReleaseAuthorized(eventId, revision)
ApocBREventControl.acknowledgeRelease(eventId, revision)
```

Getters may retain the last cached fields while status is STALE. Check status
before presenting a current schedule; both due/release authorization fail closed
when the snapshot is not fresh. No getter performs a database operation.

When a fresh scheduled event is due, Java dispatches
`Events.OnApocBRLobbyEventDue(eventId, revision, context)` on the server main thread.
It retries at most once per second until the mod accepts. Registration happens
before `OnServerStarted`; the mod subscribes there and checks cached startup
state too. `Lobby.releaseForEvent()` revalidates the bridge's current due
identity/revision (or a durable prior release receipt), sets `removedAll` in the lobby's normal world ModData, and
queues every perimeter edge using the existing count/time budgets. It then
acknowledges desired-state acceptance. Client/RCON payloads cannot invoke this
new path through an added network handler; the old public token checks remain.

Startup also replays a durable prior release receipt even when the backend has
since cancelled/cleared/replaced its desired schedule. `isReleaseAuthorized()`
accepts a fresh due schedule OR that scope's fresh durable receipt, whereas
`isDue()` refers only to the current scheduled deadline. The same main-thread
callback restores the prior removal once before processing any newer due event.
This covers a crash between a PostgreSQL receipt and normal world save.
Removal is idempotent; there is no atomic exactly-once transaction spanning
PostgreSQL and game saves. With zero clients or unloaded terrain the intent is
accepted immediately, and affected owned fences remain absent when squares
load. The mod preserves unrelated map objects and continues its slow recovery
scans. An unavailable/stale schedule blocks new activation; an already-accepted
removal is not intentionally reversed by an outage, cancellation or restart.
If the process crashes before BOTH its normal world save and asynchronous DB
receipt are durable, and the backend removes/cancels the original intent before
recovery, that unrecorded acceptance cannot be recovered. This is an explicit
durability window; the backend must retain reported receipt fields and should
retain scheduled intent until observing the matching receipt.

This feature is release-only. New scheduled events do not automatically rebuild
the lobby. A repeated-event/rearm lifecycle needs a separate explicit design.
Manual `ReleaseExits`/`OpenDoors` retains its existing six-exit behavior; scheduled
activation removes the entire perimeter, even if six exits were already opened.

## Guide messages

The startup `guide` actor says `Olá!` as its wave begins. After the 2.5-second
wave finishes, a separate speech message announces the next operation's name
and real-time countdown. When an operation is active, the same follow-up also
reports its estimated remaining time. Cooldowns, roaming and visitor tracking
remain unchanged. Losing the visitor or opening a trade cancels the follow-up.
Speech failures settle once and stop the wave rather than repeating each tick.

Apply the manager's game-runtime **V13** migration before installing this patch.
The existing worker now reads five additive `guide_*` columns alongside release
intent. The manager publishes the earliest future scheduled operation and a
confirmed `RELEASE_REQUESTED` operation within its expected duration window,
scoped to the same server/world. Estimated end is scheduled start plus duration;
it neither establishes an actual game end nor stops gameplay. The snapshot is
published independently of release revisions and receipts, so the current active
operation can remain visible after desired intent advances to the next one.

Countdowns use wall-clock time, round positive fractional seconds up, and show
days/hours/minutes/seconds. Expired active estimates and elapsed future dates are
omitted. An empty agenda announces that no next operation is scheduled; a due
unacknowledged release announces that it is pending. An engine-stale snapshot,
missing manager publication, future publication timestamp or manager snapshot
older than 60 seconds announces temporary unavailability. With event control
disabled the guide only greets. Manager sync delays must remain below that
60-second announcement freshness bound. No speech call queries JDBC or activates
an event; the release hook retains its existing identity/revision checks.

Strings use the NPC-only `HumanLanguage.text()` with
`IGUI_ApocExtractionMapUtils_Event*` keys cached from V18's `npc_engine.label_text`.
PTBR is the default independently of the global Translator/client language;
standalone mode uses shipped EN/PTBR defaults. `displayZone` remains
accepted for compatibility but relative countdowns do not depend on a timezone.
No client Java patch is needed.

## Validation and limits

The countdown update passed `python tools/test_human_brain.py` (72 assertions),
`python tools/test_lobby_event_control.py` (217 assertions) and
`python tools/test_server_humans.py` (78 assertions). The 42.21.0
`patchApocalipseBr.ps1 -DryRun` compiled successfully against the installed game
JAR without deployment. The extraction mod's `python tests/guide-translations.py`
validates JSON, UTF-8, exact greeting, placeholders and estimate wording. These
checks cover cached countdowns, simultaneous active/next announcements, expired
estimates, stale manager publications, wave/speech ordering, cancellation and
existing release/restart behavior. Live multiplayer speech timing remains to be
validated after the coordinated backend migration, manager and game update.

`python tools/test_lobby_event_control.py` compiles/executes the production class
with fake JDBC and clocks: fixed SQL/scope binding/timeouts/lease cleanup,
worker/main handoff, no SQL from getters/greeting, real deadline, signal retries,
identity/revision validation, cancellation/rescheduling/rollback, stale outage,
overdue recovery, receipt predicates, restart replay and guide formatting.
Human brain/lifecycle/roaming/trading fixtures remain applicable. Lua perimeter
tests cover all-edge removal, retries, empty-server/unloaded activation and
restart/no reconstruction. Baseline disassembly/map tests remain applicable.

Compile/deploy verification uses `42.21.0/patchApocalipseBr.ps1 -DryRun` against
the installed real game JAR. No backend migration or live DB writes were run.
Reference SQL must still be applied/tested by the backend migration owner.
Live validation must cover DB permissions/schema, heartbeat and async receipt,
empty-server activation with PauseEmpty, cancellations near deadline, guide
speech/localization, unloaded chunks, normal save/restart and process crashes.

Executed successfully for this change: `tools/test_lobby_event_control.py`
(177 production assertions), `tools/test_human_brain.py`,
`tools/test_server_humans.py`, `tools/test_human_roaming.py`, and
`tools/test_human_trading.py`; all five Lua lobby/disassembly/login/map
harnesses; and the 42.21.0 patch script with `-DryRun`. Translation JSON was
parsed and edited sources checked for UTF-8 without BOM. These checks do not
substitute for applying the backend migration or a live dedicated-server test.


## Operation metadata transport (v2)

Apply manager runtime migration **V14** before installing this patch or the
updated manager. Reference SQL: [LOBBY-EVENT-METADATA.sql](LOBBY-EVENT-METADATA.sql).
The original V11/V13 migrations and reference DDL remain unchanged. V14 adds
`desired_metadata_json`, non-null text defaulting to `{}`, constrained to a JSON
object of at most 32768 characters. Its exact text participates in the revision
guard. Publish metadata and schedule fields atomically with a strictly increasing
revision; even formatting-only changes require a new revision. The manager
supports v1/v2 engine rows during rollout, but v1 engines ignore metadata.

The manager publishes this JSON envelope from the existing operation editor:

```json
{"schemaVersion":1,"gameType":"extraction","ruleset":"default","durationMinutes":60,"parameters":{"objectives":[]}}
```

`parameters` is the parsed object entered in the website JSON field. No scripts
or commands are generated or evaluated. Duration remains informational. The
engine validates the bounded JSON object on its JDBC worker and caches the
original string; it does not interpret objective structure. Metadata is part of
immutable schedule equality, so same-revision mutations fail closed.

The first two callback arguments retain their existing meanings. The third is
a new Lua table created on the main thread for each dispatch:

```lua
Events.OnApocBRLobbyEventDue.Add(function(eventId, revision, context)
    if not context or context.isReplay or not context.metadataJson then return end
    -- context.metadataJson is the JSON envelope from the matching operation.
    -- Validate supported schema/types and persist accepted definitions in the mod.
end)
```

Fields: `eventId` (string), `revision` (number), `isReplay` (boolean), and, when
the desired identity/revision match, `name`, `startsAtMillis`, `state` and
`metadataJson` (string). Existing two-argument Lua listeners continue to work.
`getEventContext(eventId, revision)` returns a fresh table for the exact current
desired identity/revision, including before its deadline, or nil if stale,
unavailable or mismatched. It performs no SQL or JSON parsing. Check schedule
state/deadline for activation; reading context does not authorize activation.
Changing a returned table cannot change the cached specification.

Receipt restoration sets `isReplay=true`, including when the receipt matches an
already-due desired schedule. A receipt whose original specification is no
longer current carries only identity/revision/replay fields: it NEVER receives a
replacement event's JSON. Receipts still contain no durable metadata. Objective
consumers must save accepted definitions/progress and handle retries/restarts;
this transport does not add an objective lifecycle or reconstruct lost progress.
The extraction perimeter listener can continue ignoring the third argument.

Polling now defaults to **30000 ms**, freshness to **90000 ms**. Existing JVM
overrides still apply and freshness must be at least twice the poll interval.
Remove an old explicit 2000-ms override to use the new default; an explicit
10000-ms freshness value is incompatible with 30000-ms polling. An already
cached schedule still activates on its real deadline through the server loop.
New schedules/cancellations are observed on the next poll (up to approximately
30 seconds plus I/O, in addition to manager sync latency). Receipts also flush
on this cadence. Guide data retains its separate 60-second manager-age limit;
keep manager publication comfortably below that limit (default 10 seconds).

The event fixture uses the real game JAR for its JSON parser (default installed
path; override with `PZ_GAME_JAR`). Engine/Lua/JDBC boundaries remain mocked.
Verification: production event fixture covers metadata, identity/revision,
Lua-table mutation isolation, receipt replay, outage freshness and polling
constants. Real game-JAR compilation and coordinated manager/migration tests are
required alongside it. No live deployment is implied by these checks.


Executed for the metadata enhancement: `python tools/test_lobby_event_control.py`
passed 251 production assertions; `test_human_brain.py` passed 72 and
`test_server_humans.py` passed 78. `42.21.0/patchApocalipseBr.ps1 -DryRun`
compiled all 169 patched sources against the installed 42.21.0 game JAR without
deployment. The manager's 37 targeted tests passed, including the disposable
PostgreSQL 17.6 V14/adapter checks. Live website-to-dedicated-server Lua dispatch
still requires validation after coordinated migration and deployment.


### Structured NPC announcement content

`guideAnnouncementLines()` returns immutable ordered phrases from one cached
schedule snapshot and one clock reading: active operation first, then next
operation when both exist. Empty/single fallback cases retain their existing
meaning. It performs no SQL or speech. `guideAnnouncement()` and
`guideGreeting()` remain compatibility text adapters. The guide's injected
`OperationAnnouncementProvider` supplies those phrases to generic `Say`, `Wait`
and `Sequence` actions, with a four-second pause instead of concatenating both
messages into one transmission. See the mandatory
[NPC engine architecture contract](NPC-ENGINE-ARCHITECTURE.md) for ownership,
composition, cancellation, bounds and validation requirements for all NPC work.
