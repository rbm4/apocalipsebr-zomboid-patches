# Backend message inbox and zombie-kill delivery

Follow-up: [implemented producer and V36 migration](../features/2026-11-04-backend-messages.md).
The investigation below is preserved as the original proposal; lease-based
processing was not adopted, and backend business handling remains future work.

Report date: 2026-11-04. Target: 42.22.1 dedicated server. **Proposal only;
no runtime code, migrations, database changes or deployment are included.**

## Recommendation

Use PostgreSQL as a durable, typed **backend inbox**. Publish a small
`ZombieKillsRecorded.v1` event containing a batch of confirmed kills for one
character life. Let a backend worker apply the business effects. Reuse the inbox
transport later for commands such as `PurchaseRequested.v1`, with separate typed
handlers and validation rules.

This combines the useful parts of both proposed options: a reusable delivery
mechanism and explicit domain contracts. A kill is an already-observed fact;
a purchase is a request the backend may reject. Calling both arbitrary
"commands" makes their authority and retry semantics less clear.

For the lowest practical initial overhead, start with one backend worker polling
an indexed ready queue every 1–5 seconds, draining bounded batches. Batch kills
per character at a proposed 5-second interval or 100 kills, whichever comes first.
Those values are starting settings, not measured capacity or loss guarantees.
Do no work for players with zero pending kills. Add LISTEN/NOTIFY only if latency
or measured idle polling cost warrants the extra persistent listener connection.

Do not insert one row or open one transaction per zombie, export all character
stats again, or introduce a separate broker for this first use case.

## What exists today

### Kill export

The inspected handler is `ZKC_ServerHandler.lua` at
`C:/Users/ricar/Zomboid/Workshop/apocbr/Contents/mods/ApocalisebrPack/42.14/media/lua/server/ZKC_ServerHandler.lua`.
Its `onZombieDead` callback:

1. Requires the server environment and reads `zombie:getAttackedBy()`.
2. Requires an IsoPlayer whose online ID resolves to that same connected object.
3. Increments in-memory counters keyed by Steam ID, with an online-ID fallback.
4. Flushes aggregated JSON lines to `ZKC_PlayerData.txt`.

The configured threshold is 100 kills and a 60-second target, but timer checks use
`EveryOneMinute` and `EveryHours` game events. They are not a precise wall-clock
delivery deadline. A threshold flush collects **all online players**, including
zero-kill entries, and can enumerate every player's skills. Coordinates are sampled
at flush, not at each kill. The proposed kill message can remove that work.

The exact target vanilla
[IsoZombie.onKilled](../../decompiled/zombie/characters/IsoZombie.java) fires
`OnZombieDead`; the event itself has no server-only guard. Retain the Lua server
guard and current connected-player attribution checks. These checks are a useful
trusted entry point, not proof that every combat/network attribution path is
immune to cheating. Native zombie online IDs are not permanent event identities.

### Entity bridge

The existing [BeyondTen persistence guide](../features/2026-10-29-beyondten-character-entities.md)
describes the facade at
`C:/Users/ricar/Zomboid/Workshop/apocbr/Contents/mods/BeyondTen/42/media/lua/server/ApocBR/Entities.lua`.
The inspected [ApocBRModEntities.java](../../src/zombie/savefile/ApocBRModEntities.java)
and [ApocBRPersistence.java](../../src/zombie/savefile/ApocBRPersistence.java) establish:

| Existing behavior | Consequence for a message inbox |
| --- | --- |
| Detached scalar requests, worker-owned JDBC, Lua-thread completions | Reuse this boundary; never do synchronous JDBC in the death callback. |
| Fixed `mod_data` schema; UUID/string/number/integer/boolean fields | A table mapping is feasible, but native JSONB and timestamp mappings are absent. Strings are limited to 4096 characters. |
| Up to 16 models, 32 fields/model, 1–4 ID fields | A shared transport avoids consuming a model per command type. |
| Revision-based save/patch/delete | This is mutable entity persistence, not yet an append-only delivery API. |
| Generic save inserts with `ON CONFLICT DO NOTHING`; reports `CONFLICT` | Retrying a committed append does not currently return an idempotent success. |
| Schema/type/PK validation on each request | Each tiny save does more SQL/catalog work than its INSERT alone. Batch before submission. |
| 256 outstanding bridge requests; 32 completions per poll | Admission must be handled and pending work bounded. |
| Mod requests share the persistence replay worker | Per-kill calls compete with player/world persistence; no dedicated kill thread is needed. |
| Mod request lane is in memory, separate from the durable journal | Accepted is not durable. A process crash can lose accepted work. |
| Scoped operations verify the active life/session and lock its player slot | Good for live mastery state; late historical kill delivery needs different semantics. |

The facade currently ships inside BeyondTen. A general platform dependency should
eventually own it so other mods do not depend on an unrelated mastery mod loading
first. Preserve one facade/poll owner; competing consumers can steal completions.

42.22.1 is a compiled carry-forward, not a fresh runtime certification. Its persisted
build labels intentionally remain 42.22.0; this proposal does not require renaming
them. See the [port report](2026-11-04-port-execution-and-artifact.md).

### Backend effects and transaction boundary

The local backend inspected is `Z:/git/Github/apocalipsebr-manager/manager`.
`CharacterService.updateCharacterStats` currently adds `killsSinceLastUpdate` to
website character kills and awards currency, alongside location/status/skill
updates. It chooses a website character by user/name/current season/server, rather
than the game-runtime life UUID. Do not route the new event through this legacy
all-stats DTO unchanged: absent fields can overwrite state and delayed events can
be assigned to the wrong life or season.

Backend instructions and datasource configuration establish separate app and
game-runtime transaction managers. A single ordinary `@Transactional` annotation
does not make queue acknowledgement and website rewards atomic across them.
The relational snapshot also has `character_state.zombie_kills`; its existence
alone does not establish an authoritative, monotonic reward ledger or a lossless
replacement for death events.

## Proposed contract and storage

Expose the mapping style through a small server API such as
`BackendMessages.publish(type, playerContext, payload, callback)`. Internally use
an **append** operation with immutable IDs and detached values. Ordinary entities
retain save/patch behavior; publishing should not permit changing message meaning.

Use one inbox table, for example `mod_data.backend_message`, with:

| Fields | Purpose / owner |
| --- | --- |
| `message_id uuid` primary key | Generated once before first submission; reused on every retry. |
| `message_type text`, `schema_version bigint` | Allowlisted handler and explicit payload contract. |
| `server_key`, `world_id`, `producer_epoch`, `character_id` | Trusted source and life identity captured at observation. Epoch changes on producer restart. |
| `payload` | Small typed data; initial scalar-compatible text, or JSONB behind a deliberate bridge extension. |
| `created_at`, `status`, `available_at`, `attempts`, `last_error` | Database defaults/backend-owned delivery state. |
| Optional `lease_until`, `claim_token` | Needed when claiming in one transaction and processing outside it. |

This is a conceptual schema, not executable DDL. An interim existing-entity mapping
would also need `revision bigint` and the exact mapped primary key. Backend-only
columns can be omitted from the mapping and supplied by defaults. TIMESTAMPTZ and
JSONB require new mapping support if Lua is to map them directly. Do not map queue
status as a mutable gameplay field.

Kill payload: `count > 0`, optionally first/last observation time and producer batch
sequence. Envelope fields identify the actor/source. Do not include XP, full
skills, health or current coordinates merely because the old exporter did.
If rewards depend on kill location, weapon, region or time, capture those facts
at death and batch by the relevant dimensions; querying the latest character row
later cannot reconstruct kill-time context. Aggregated counts intentionally
cannot support later per-zombie auditing.

Persist a stable mapping from game-runtime character UUID to website reward target
and season. Resolve it server-side; never accept website IDs, prices, balances or
character UUIDs from client payloads. Define handling for unknown identities and
old lives. A valid delayed event remains a fact after disconnect/death; it must
not be reassigned to the newly active character.

Consequently, do not use mastery `loadOrCreateForCharacter` or its active-life
collection semantics as the long-term message transport. Capture trusted identity
while the player is bound, then retry the **same detached event**. Admission and
historical replay must have distinct validation rules.

## Delivery and exactly-once business effects

1. The server increments a counter for the actual character life. At flush it
   freezes a batch and assigns a UUID. New kills enter a separate active batch.
2. Publish the frozen batch asynchronously. Rejected admission retains it;
   admission alone never clears it. Only a committed acknowledgement releases it.
3. Append retries reuse the UUID. Existing ID plus identical immutable contents
   means success; existing ID with different contents is an error. This needs an
   append/reconciliation extension, not blind retries of ordinary `save`.
4. A worker selects a bounded ready batch and claims rows using
   `FOR UPDATE SKIP LOCKED`. For the separate app datasource, commit a lease and
   claim token, then process without holding runtime locks across app work.
5. In **one app transaction**, insert a unique processed-message receipt and apply
   the kill/reward updates. Only the receipt insertion winner may apply effects.
   Concurrent different messages for one wallet also require atomic increments
   or appropriate row locking to avoid lost updates.
6. After that app transaction commits, mark the runtime row completed using the
   claim token. On crash before acknowledgement, lease expiry enables replay;
   the app receipt prevents paying again. Stale workers cannot acknowledge a new
   owner's claim. Terminal rejection is recorded; transient failure backs off.

If business effects eventually share the same PostgreSQL transaction as the inbox,
steps 4–6 can instead apply effects and completion atomically, avoiding leases for
short database-only work. This is not the current cross-datasource assumption.
External game delivery/RCON is another boundary and needs its own idempotency.

PostgreSQL documents SKIP LOCKED as suitable for queue-like consumers, while
warning that it gives an inconsistent view unsuitable for ordinary queries.
It does not promise FIFO processing. Do not use highest-seen message ID as the
only checkpoint; out-of-order commits/claims can skip work.
See [SELECT locking](https://www.postgresql.org/docs/current/sql-select.html).

**The guarantee is repeatable delivery with idempotent business effects, not an
end-to-end exactly-once zombie-death transaction.** The death callback and database
commit are separate operations. A duplicate callback creating a new UUID also
needs producer-side death deduplication if runtime testing shows that possibility.

## Durability and resource tradeoffs

| Option | Assessment |
| --- | --- |
| One persistent row per zombie | Detailed audit, highest write/retention cost; unnecessary for count-based rewards. |
| Batched immutable kill events in a reusable inbox | Recommended balance of bounded work, replay safety and future commands. |
| One cumulative counter per producer/life plus a backend cursor | Potentially least storage for counts alone, but loses event detail and requires reset/epoch/ownership semantics; purchases still need a separate inbox. |
| NOTIFY without a table | No durable backlog for an offline worker; unsuitable as the delivery record. |

With 1,000 kills in a five-second burst from one character, a 100-kill threshold
would produce roughly ten batch rows rather than 1,000. This illustrates reduced
row/transaction demand; it is not a benchmark. Current generic saves still incur
validation per request. A future bounded multi-message append transaction can
validate once and insert several batches, while keeping player persistence fair.

Index the ready subset by availability/ID, drain small batches, back off on empty
polls, and retain no full-table scanning loop. Keep payloads and error text bounded.
Retire completed rows in bounded cleanup batches and monitor table/index bloat.
Retain app receipts or permanent reward-ledger keys for at least every possible
producer replay interval; deleting both queue history and deduplication records
while old messages can replay permits duplicate rewards.

For lower latency later, add one statement trigger that emits a small wake-up
signal and one backend listener. Notifications are delivered after commit and
duplicate identical signals within a transaction can coalesce. They should mean
"drain the inbox", never carry the durable business record.
See [NOTIFY](https://www.postgresql.org/docs/current/sql-notify.html).
On startup/reconnect: commit LISTEN, scan the inbox, then listen with periodic
fallback scans. Registrations end with the session; LISTEN has a documented setup
race. See [LISTEN](https://www.postgresql.org/docs/current/sql-listen.html).

Without local durable storage, a hard crash or database outage can lose uncommitted
batches. There is no strictly lossless, bounded-memory, file-free producer during
an arbitrarily long PostgreSQL outage. Choose explicitly between accepting that
window, extending the existing internal journal with durable message records,
or stopping/restricting operations when persistence is unavailable. A bridge-owned
journal removes mod-written export files but still uses local disk. A five-second
flush interval is not a five-second maximum loss window.

For kill statistics, a documented bounded best-effort buffer may be acceptable.
For purchases, do not confirm success or remove value until the backend has made
the authoritative durable decision. Keep an immutable request ID through retries.

## Future buy/sell commands and authority

Share envelope, admission, retries, receipts and handler registration. Keep domain
policies separate. `PurchaseRequested.v1` should name an offer/version and quantity;
the backend derives buyer identity, price, stock and funds, then commits a unique
order/ledger decision. Unknown types/versions are terminal failures, not arbitrary
method names or SQL to execute. Independent commands may run concurrently;
conflicting wallet/stock operations require backend serialization/constraints.

Delivering an item back to the game requires an acknowledged idempotent fulfillment
protocol. Selling an in-game item additionally needs reservation/removal and a
compensation/reconciliation policy. The shared inbox alone cannot atomically
exchange a live inventory item and website money.

Existing broad engine grants in `public`/`player_rel` do not establish backend-only
queue ownership. Prefer a dedicated schema or table-specific producer INSERT/read
access and backend status ownership. The current bridge hardcodes `mod_data`, so
a dedicated schema needs an allowlisted Java change. Audit inherited/default grants
and table ownership before claiming least privilege. Backend migrations must grant
the actual engine role `pz_server_01` access, including sequences where used, and
be verified with distinct migration/engine roles in an isolated database.

## Suggested rollout and validation

First define the accepted kill-loss policy and life-to-reward-target mapping.
Then add backend migrations, append/reconciliation support and an idempotent
kill handler. Keep the existing entity facade style; use a publish lifecycle for
messages. Replace the exporter only after verifying count parity. Shadow mode
may compare totals, but only one path may award rewards. Drain or explicitly
checkpoint the old file at cutover to prevent overlap.

Verify duplicate submissions, uncertain commits, worker crashes before/after app
commit, concurrent wallets, expired leases, late events after death/reconnect,
season boundaries, database outages, full admission buffers, paused servers and
shutdown. Measure replay-worker delay, queue age/depth, app transaction time,
deduplication hits, failures and dropped pending kills. No Java compilation or
runtime test was run for this documentation-only investigation.

Local backend evidence: `CharacterService.updateCharacterStats`,
`infrastructure/config/GameRuntimeDataSourceConfig.java`,
`infrastructure/config/AppDataSourceConfig.java`, and backend `AGENTS.md`.
Runtime database contents, PostgreSQL server version, grants and live throughput
were not inspected. PostgreSQL references above describe documented semantics,
not a verification of the deployed server configuration.
