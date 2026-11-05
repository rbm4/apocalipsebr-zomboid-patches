# NPC death, corpse loot and respawn (42.21.0, V30)

Every NPC has a detached `HumanDeathPolicy`, selected by database references on
`npc_engine.instance`. V30 assigns existing NPCs the enabled `npc-default`
respawn profile: **600,000 real-time milliseconds (10 minutes)**. The guide keeps
god mode. Standalone definitions use the same timer default. Persisted home X/Y/Z
are reused for respawn; the last death position belongs to the corpse.

## Owners and lifecycle

| Owner | Responsibility |
| --- | --- |
| `HumanLifecycle`, `HumanDeathState` | Identity/incarnation, death UUID/deadline, fresh physical state and construction rollback |
| `HumanDeathPolicy` | Immutable respawn configuration and bounded native-free rarity/item rolls |
| `ServerHumanFactory` | Active routing, bounded deadline queue and fresh activation/checkpoints |
| `ServerHumanDeaths` | Native corpse/container creation, real loot items and reputation composition |
| `HumanReputationPort` | Typed kill consequence extension for future faction standing rules |
| `ApocBRNpcMortality`, `ApocBRNpcStore` | Worker-only hydration and detached writes through existing journal/epoch fences |

Death enters DEAD once, sets zero health and captures UTC epoch milliseconds,
deadline and UUID. It removes active ID/interaction routing, stops activity, combat,
tasks, traversal and corpse dragging, closes sessions, publishes the native corpse
and removes the actor from viewers. Duplicate death does not roll loot or emit
consequences twice. DEAD records have no ordinary AI, sensing, movement,
targeting or remote-player heartbeat traffic. Their deadlines remain in a queue
with at most eight due attempts per 100ms tick. Corpses are passive world objects.

`runtime_state` stores `died_at_ms`, `respawn_at_ms` and `death_id` in the same
fenced checkpoint as lifecycle/health/position. Restart requeues DEAD records only
when desired activation and respawn are enabled. Downtime counts toward the timer.
An overdue NPC waits for its home square to load and active capacity; no chunks
are pinned or forced to stream. Startup never recreates or rerolls old corpses.

Respawn creates a fresh actor, native ID, behavior runtime and finite combat
inventory under the same logical key, with healthy vitals and cleared injuries.
Physical revision and incarnation increase. Targets, tasks and sessions reset;
account/affiliation memory and dialogue flags remain. The old corpse is unaffected.
Construction failure restores DEAD, old health and the original deadline. Manual
removal creates a tombstone that invalidates queued respawns. Ordinary `activate()`
still refuses DEAD; only the lifecycle scheduler can reset physical state.

There are 64 active slots and 256 logical records. The native ID pool expands from
64 lifetime IDs to 1024–32766, with no process-local recycling. Native VoiceManagerData
indexes by positive short; 32767 is excluded to avoid its short-loop overflow.
The finite 31,743-incarnation budget requires restart if exhausted. This preserves
delayed-packet identity protection without changing the stock client protocol.

## Native corpses, loot and reputation

The exact-version `IsoDeadBody(IsoGameCharacter)` / `GameServer.sendCorpse` path
preserves appearance, worn/attached items, native looting/hauling and world saves.
Normal player logout/account persistence is not called. Metadata includes logical
key and death UUID. Corpse reanimation is disabled; NPC respawn is independent.
The temporary test crowbar/pistol/clip/ammunition supplies are removed before
transfer so they cannot bypass loot policy. Other native inventory/outfit contents
remain. Rolled rewards become real corpse-container items before publication.

Each roll selects COMMON/RARE/EPIC/LEGENDARY at 6000/3000/900/100 out of 10,000,
then one entry proportional to weights within that tier, then an inclusive quantity.
Item probability per roll is its tier chance multiplied by its share of entry
weights. Empty tiers yield nothing, without fallback. Limits: 0–16 rolls, 128 entries
and quantities 1–20. The seeded default table has **no item entries**; administrators
author rewards in SQL rather than receiving invented rare/legendary content.

Lethal player damage captures a detached account/affiliation identity; death can
also resolve a currently authenticated native killer. Zombie/environmental deaths
do not invent a player killer. Current affiliation comes from the encounter policy;
unaffiliated NPCs do not invent a faction. `KILLED_NPC` persists through the existing
relationship worker/journal, revoking permission/starting denial without increasing
refusal counts. `HumanReputationPort.npcKilled(Kill)` receives death UUID, NPC key,
incarnation, killer account/affiliation and time. Its default is a logging stub,
with no numeric standing formula. Install a future service through simulation-thread
`ServerHumanFactory.reputationPort(port)` and deduplicate its effects by death UUID.
Extension exceptions do not stop corpse creation. Native effects are not replayed
on restart; relationship event UUIDs deduplicate journal replay.

PostgreSQL journaling and native map/corpse saving are separate durability domains.
Graceful shutdown/checkpointing saves both. A crash before journal staging or world
saving may lose recent changes; this is not an atomic economic reward guarantee.
The loader does not compensate for a missing map corpse by generating new loot.

## Configuration, rollout and checks

Apply manager game-runtime V30 as migration owner, deploy the server patch and
restart. Previously dead non-retired records receive a fresh 10-minute deadline.
Protection flags/profile history are unchanged. New tables have explicit engine
role `pz_server_01` grants, with no new sequences or datasource.

The NPC editor assigns authored respawn-profile/loot-table IDs. Table contents are
maintained in SQL. Per-instance mortality configuration loads at startup; restart
after changing it. `respawn_profile.enabled=false` disables the timer; `delay_ms`
allows 1 second–30 days. Home coordinates already exist on `instance`.

```sql
-- Example only; choose the actual desired item definitions before applying.
INSERT INTO npc_engine.loot_table(loot_table_id,display_name,rolls)
VALUES ('militia-example','Militia example',2);
INSERT INTO npc_engine.loot_entry
 (loot_table_id,rarity,item_type,weight,minimum_quantity,maximum_quantity)
VALUES ('militia-example','COMMON','Base.Bandage',1,1,2),
       ('militia-example','RARE','Base.Bullets9mm',1,3,6),
       ('militia-example','EPIC','Base.Pistol',1,1,1),
       ('militia-example','LEGENDARY','Base.Katana',1,1,1);
UPDATE npc_engine.instance SET loot_table_id='militia-example'
WHERE server_key='your-server' AND world='your-world' AND npc_key='your-npc';
INSERT INTO npc_engine.respawn_profile
 (respawn_profile_id,display_name,enabled,delay_ms)
VALUES ('slow-respawn','One hour',true,3600000);
```

Fixtures: `test_human_death_respawn.py`, `test_npc_runtime.py`, lifecycle/profile,
retaliation/corpse/survival tests, disposable PostgreSQL
`test_npc_persistence.py --manager-test`, editor tests and actual game-JAR
`patchApocalipseBr.ps1 -DryRun`. Coverage includes exact rarity thresholds, weighted
quantity, inventory isolation, duplicate death, typed reputation, 70 successive
respawns, construction rollback, disabled timers, overdue restart, migration
upgrade/grants, state fencing and editor assignment round trips. Live multiplayer
acceptance still needs corpse visibility/looting/hauling, world save/reload,
loaded/unloaded homes and a complete kill/respawn cycle. No live deployment is part
of these fixtures.

2026-10-26 verification: mortality/loot/corpse/respawn fixture 32 assertions,
runtime/restart 35, lifecycle 92, profiles 56, retaliation 58, corpses 131,
survival 107, dialogue 66 (plus EN/PTBR) and trading 538 passed. Disposable
PostgreSQL/JDBC passed 93 assertions; the manager passed 47 targeted tests and
seven editor tests. Actual game-JAR dry compilation passed with 286 sources and
849 class files. Repository whitespace checks passed; no live deployment occurred.
