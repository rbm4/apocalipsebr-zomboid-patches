# NPC speech engine (42.21.0 server)

`HumanSpeechEngine` is a reusable per-NPC reaction and speech policy. Its inputs
are detached facts; its output is an immutable `Decision`. It does not send
packets, run SQL, choose a combat target, move an actor or perform surrender.
`ServerHumanSpeech` composes the policy with the existing action executor and
native delivery. The selected combat/module controller retains behavioral
authority. No new wire packet or client patch is introduced.

## API and ownership

| Owner | Responsibility |
| --- | --- |
| `speech/SpeechEvent` | Stable event vocabulary for combat and future reactions. |
| `speech/SpeechContext` | Account identity, safe display username, normalized health, damage, temperament, attack kind, aggressor count and fight initiator facts. Damage currently uses the logical health-fraction unit. |
| `speech/HumanSpeechEngine` | First/repeated classification, reaction recommendation, eligibility, probability, cooldowns and weighted selection. Fresh instance per NPC activation. |
| `speech/HumanSpeechCatalog` | Validated immutable rules/content, published atomically after startup hydration. |
| `speech/HumanSpeechMemory` | Bounded shared semantic memory, keyed by logical NPC/account; detached absolute snapshots for the worker. |
| `ServerHumanSpeech` | One pending presentation execution, native identity validation, cancellation and conversion into `Sequence`, `Wait`, `Say`. |
| `ServerHumanTransport` / `ChatServer` | Native `PlaySound` vocal delivery, world noise and explicit-recipient `ChatType.shout` delivery. |
| `savefile/ApocBRNpcSpeech` | Worker-only JDBC hydration and existing journal integration. |

The primary methods return `Decision`:

```java
engine.onAttackedByPlayer(context, monotonicNanos, utcMilliseconds);
engine.onAttackPlayer(context, monotonicNanos);
engine.onZombieKill(context, monotonicNanos);
engine.onDamageTaken(context, monotonicNanos);
engine.onEvent(SpeechEvent.RELOAD, context, monotonicNanos);
```

`Decision` exposes `event`, `reaction`, `status`, `messageId`, resolved `text`,
`hey`, `priority` and `speaks()`. READY means an eligible plan was selected,
not that a viewer received it. The delivery adapter records `delivered()` only
after text transport succeeds. A failed chance/cooldown still returns the
reaction so policy callers do not depend on whether speech was sent.

Supported extension events include first/repeated hits, new/multiple aggressors,
chase start/continuation, lost/reacquired/escaped target, landed/missed player
attacks, spotted/attacked zombies, reload, empty weapon, jam, low health,
bleeding, retreat, surrender, ally attacked/down and combat ended. These are
callable extension hooks; unpopulated pools return NO_CONTENT. Only the four
primary combat sources are wired in production in this change. First/repeated
hits reuse the attacked-player pool unless an exact pool is authored.

The reaction policy supplies DEFEND/PLEAD, pain, retreat and surrender results.
It recognizes the fight initiator and a 35% losing-health threshold; fresh
temperaments have a 50% first-hit plea branch. These are policy hooks, not new
movement/AI implementations. The current native adapter supplies STEADY and
existing retaliatory combat continues to own movement/attacks. Profile-authored
temperaments, multiple-target selection and profile maximum HP are separate
capabilities; this engine neither introduces nor substitutes for them.

## Content and selection

There are 20 original variants in each of ATTACK_PLAYER, ATTACKED_BY_PLAYER,
ZOMBIE_KILL and DAMAGE_TAKEN, in both EN and PTBR (160 localized rows). PTBR is
the native adapter default through existing `HumanLanguage`; EN is supported.
Unknown native locale settings use PTBR. Standalone mode uses shipped content;
persistence mode must successfully hydrate the database catalog before actors
activate, with no fallback actor creation while the database is unavailable.

Lines support bounded weight, health interval, attack-kind, temperament and
minimum-aggressor gates. `{username}` is the only template substitution. Rich
text delimiters/control characters are removed from substituted usernames; the
rendered result remains bounded to the existing 256-character action limit.
No line should assert a gunshot, wound location or bleeding without a matching
fact/gate. Generic defaults avoid those claims; severe pain lines require low HP.

Selection prefers eligible message IDs absent from the last 40 delivered lines
for that logical NPC. When exhausted, it reuses the oldest eligible ID. Content
is immutable and independent of per-NPC cooldown/execution state.

| Event family | Chance | Cooldown | Priority |
| --- | ---: | ---: | ---: |
| Attack player | 20% | 12 s | 40 |
| Attacked by player | 60% | 6 s | 90 |
| Zombie kill | 34% | 15 s | 30 |
| Damage taken | 68% | 6 s | 95 |

The first attack from an unfamiliar account bypasses chance and requests the
guaranteed opening at priority 100, subject to content/actor validity and
cooldowns. A global six-second speech gap prevents overlapping event chatter;
higher-priority reactions may bypass the global gap but never their own family
cooldown. An eligible event consumes its family opportunity even when its
chance fails, so polling cannot reroll every frame. An unrelated attacker does
not bypass the shared attacked-player family limit.

## Production triggers and delivery

Authenticated/deduplicated incoming player attacks classify account familiarity
after dialogue/trade close. The opening executes `Say("Ei!"/"Hey!")`,
`Wait(350ms)`, `Say(named accusation)`. The first line emits stock `ShoutHey`
with the native vocal flag, and a radius/volume 30 world stimulus. Every line is
delivered through the actual shout channel, using its configured range, relevant
viewers, same-floor checks and one packet per connection including split screen.
Shout text and audible voice are distinct outputs; missing/disabled chat causes
the speech sequence to fail once without a retry storm.

Committed player attacks call the attack hook, including misses. Positive
logical loss from player damage, zombie strikes and imported native health loss
calls damage taken. A positive-health zombie becoming dead during a committed
NPC hit calls zombie kill once through the authoritative combat path. It does
not announce unrelated zombie deaths or infer a kill from a miss.

Only one pending reactive plan is retained. Higher priority may replace it;
there is no unbounded speech queue. The plan expires after three seconds.
Death/downing, a changed/disconnected addressed player, dialogue/trade ownership,
persistence blocking, disposal or explicit cancellation drops pending children.
Already-sent lines/noise cannot be recalled. Speech owns no movement/animation
lane and does not delay weapon phases. Ordinary autonomous greetings and trade
speech keep their existing owners; this foundation does not reroute all speech.

`ServerHumanCombatAudio` additionally sends native body/flesh impact sound for
successful NPC hits, independent of speech probabilities. It does not change
damage/ammo or add another local hit application. Swing presentation and existing
ranged discharge delivery retain their native/current paths.

## Persistence and startup

Apply manager game-runtime migration `V31__Npc_Speech_Engine.sql` after V30.
It creates `speech_rule`, `speech_pool_line` and `speech_memory`, with explicit
grants to `pz_server_01`. The pre-existing V16 `speech_set`/`speech_line` schema
remains the separate trade-content contract; this change does not alter it.

The NPC worker reads all rules, enabled pool rows and scoped semantic state in
the initial repeatable-read transaction. It publishes content/memory before the
`Loaded` startup snapshot. Speech is startup-loaded: changing database pools
requires restart; normal profile refresh does not query speech content.

Memory keys contain `(server, world, npc_key, account kind, account identity)`.
Steam identity is preferred; username is the non-Steam fallback. Pair memory
stores attack count/latest UTC attack time; speaker memory stores delivered IDs.
After 24 hours without an attack, that pair is classified as first-hit again.
Each accepted attack refreshes this interval, including silent/throttled attacks.
A backward wall-clock change does not invent a first hit. Expiry resets semantic
familiarity; rows are retained rather than deleted. This preserves replay fences.

Limits: 100,000 memory keys, 4,096 coalesced pending keys, 40 recent IDs per
speaker, 4,096 enabled localized lines, one pending execution per actor. Capacity
pressure returns MEMORY_FULL for new attack-memory mutations; existing combat
validation/retaliation remains independent. Mutations are synchronized, detached
and coalesced; the worker stages them through the existing shared journal.
Epoch and mutation-revision fences reject duplicate/old replay. Failed staging
retains pending snapshots, and acknowledging an old snapshot cannot discard a
newer simulation mutation. No JDBC, disk read or per-action threads run in combat.

Memory becomes crash durable after journal staging, not immediately on event
acceptance. The worker normally stages within its one-second loop; shutdown
flushes already captured memory. Active targets, native identities, timers,
cooldowns, pending lines and reaction executions are never persisted.

## Verification

- `python tools/test_human_speech.py`: production policy, first/repeat/expiry,
  independent accounts/NPCs, repeat avoidance, chance/cooldown, reaction hooks,
  bounds, native vocal/impact arguments and timed shout delivery.
- `python tools/test_npc_persistence.py` with
  `APOCBR_TEST_POSTGRES_BIN=Z:\postgresql\bin`: disposable PostgreSQL, distinct
  migration/engine roles, catalog parity, startup hydration, worker writes,
  scope isolation and duplicate/stale-epoch replay.
- Existing retaliation, server-human, dialogue and sequence fixtures.
- `42.21.0/patchApocalipseBr.ps1 -DryRun`: exact installed game-JAR compile.

Dedicated-server acceptance still requires victim and observer clients: audible
Hey/impact, voice selection, named shout/chat display, zombie attraction, no
duplicate damage, interrupted sequences and reconnect/restart familiarity.
No live migration, deployment or server restart is performed by these fixtures.
