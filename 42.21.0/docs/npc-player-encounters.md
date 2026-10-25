# Player encounters and affiliation memory (42.21.0)

Server only; deploy with manager migration V28 after V27. The first policy orders
players to unequip weapons from **both hands**. Refusal warns and denies
interaction; this feature does not attack players or constrain player movement.

## Owners

`PlayerEncounterModule` owns transient policy/execution, after survival/zombie
defense and before tasks/greeting/walking. `EncounterPolicy` is immutable data
pinned to a profile revision. `ServerHumanEncounters` supplies detached visible
player facts, account keys, localized content and semantic-memory access on the
simulation thread. Speech uses existing `HumanAction.Say` execution. No SQL,
native object, connection or player reference enters the policy.

`HumanRelationships` owns bounded account memory and spokesperson claims.
`ApocBRNpcRelationships` maps detached values to SQL on the existing NPC worker;
`ApocBRNpcStore` retains initialization, availability, epoch and journal ownership.
Affiliation is explicit policy, independent of actor/profile names. V28 enables
both seeded militia profiles with affiliation `militia`; the guide is unchanged.

## Policy and behavior

V28 adds `affiliation`, `profile_encounter`, `player_relationship` and
`relationship_event`. The manager aggregate editor preserves and edits the typed
`profile_encounter` row in immutable profile publications. Affiliation IDs must
already exist in `npc_engine.affiliation`. Publish the module and policy together,
without generic millisecond module parameters. Java and SQL both validate bounds
and priorities.

| Field | Default | Meaning |
| --- | --- | --- |
| `challenge_tiles` | 8, capped by seeded perception | Same-floor visible proximity to the NPC |
| `warning_ms` | 8000 | Deadline after successful warning delivery |
| `compliance_ms` | 1000 | Continuous observed time without held weapons |
| `cooldown_ms` | 60000 | Shared interval before another encounter |
| `permission_ms` | 300000 | Interaction permission after compliance |
| `refusal_ms` | 60000 | Interaction denial after refusal |

This is a proximity policy, not territorial ownership. Any `HandWeapon` other
than `Base.BareHands` in either hand counts, including melee weapons. Ordinary
held items are allowed. Lowering aim alone does not comply, and no inventory item
is forcibly removed. The native adapter bounds each query to 32 visible contacts,
resolves the exact live incarnation and checks floor, eligibility and visibility.
Native `EquipPacket.processServer` resolves both hands against server inventory;
`PlayerAimState` handles replicated aim. Detached directed-aim evidence is
provided, but this first policy does not use it as another escalation trigger.

An unarmed visitor receives a greeting and permission. Subsequent encounters use
a returning greeting from shared account memory. Known unarmed visitors with
valid permission are not greeted repeatedly. Armed visitors receive one warning.
Stable unequipping confirms compliance; reequipping resets verification without
extending the warning deadline. Remaining armed past the deadline records refusal.
Its denial period cannot be cleared by immediately unequipping or opening a menu.

One affiliation/account spokesperson claim expires after two monotonic seconds
and renews while selected. Other guards observe shared cooldowns. Preemption,
dialogue suspension, shutdown, failed speech/facing release the claim and discard
the warning countdown without recording a refusal. Lost visibility/range or
character identity ends the encounter as `LEFT`; incomplete sensing is never
evidence of disobedience. Corpse claims, movement and defense keep their owners.

Dialogue, task acceptance and trade recheck current permission and equipment.
Rearming closes existing dialogue/trade; stale choices/tokens cannot grant orders.
Trade request/commit paths also check the existing emergency hook. Pure emergency
checks remain available during autonomous suspension; the ordinary passive
`monitor` hook never runs encounter actions during dialogue.

Responses in this first policy are physical actions. It adds no negotiation graph,
forced client window, recruitment or player-combat backend. Conversation ranges,
wire format and V26/V27 NPC task commands remain unchanged.

## Persistence and failures

Keys are `(server, world, affiliation, identity kind, account)`: Steam ID when
available, otherwise exact authenticated username, following dialogue identity.
Memory survives player death and reconnect; native IDs only validate live work.
State stores revision, contact attempts, compliance/refusal counts, permission
and denial expiries, and next encounter time. Expiries use UTC epoch milliseconds.
Active targets, claims, sessions, actions and monotonic deadlines are never saved.

Events are `GREETED`, `COMPLIED`, `REFUSED`, `LEFT`, with UUID, source NPC and
timestamp. `GREETED` means an accepted contact attempt, not a transaction spanning
speech and persistence. Cached mutation and detached enqueue happen together.
The worker submits event insertion and its absolute state projection in one
shared-journal transaction. UUID uniqueness deduplicates history; revisions
prevent stale projections. Both statements acquire the scope/epoch fence, so an
old process cannot append history or change a superseding owner's relationship.

Acceptance is asynchronous. A crash before shared-journal disk staging can lose
recent outcomes; this provides no economic reward/settlement guarantee. Limits
are 100,000 loaded relationship keys, 4,096 pending events and 256 speaker claims.
Full queues refuse mutations without partially granting permission. Database
availability follows the existing NPC pause, lease-closure and recovery rules.
No JDBC runs on sensing, dialogue or task threads. SQL history has no automatic
pruning: administrators define retention separately from current state.

## Rollout and checks

Apply V28 with the manager's game-runtime Flyway datasource, deploy the server
patch and restart/reactivate militia actors. V28 clones heads above drafts,
retaining old history, movement/cleanup areas, cheats, survival, tasks, trade and
custom dialogue bindings. Active policies do not hot-swap on profile refresh.
Standalone profiles are unchanged; explicitly authored encounter profiles can
use bounded in-memory relationships without SQL persistence.

Relevant fixtures: `test_human_encounters.py`, `test_human_dialogue.py`,
`test_human_tasks.py`, `test_human_trading.py`, `test_human_profiles.py`,
`test_npc_runtime.py`, `test_human_brain.py`, `test_human_sequences.py`,
`test_human_survival.py`, `test_human_corpses.py`, `test_postgres_resilience.py`.
Run them from repository root with Python. The source-list updates preserve the
isolated fixtures' explicit dependencies on recently added NPC capabilities.

```powershell
$env:APOCBR_TEST_POSTGRES_BIN='Z:/postgresql/bin'
python tools/test_npc_persistence.py --manager-test
powershell -File 42.21.0/patchApocalipseBr.ps1 -DryRun
```

The PostgreSQL fixture creates a disposable localhost cluster with separate
migration and engine roles. It checks V28 hydration/grants, publication,
replay/epoch fences, account/scope isolation and worker shutdown. Manager tests
cover a V27 upgrade with an existing draft and atomic policy editing. The manager
also runs `node --test src/test/js/npc-editor.test.cjs`.

Live acceptance requires multiple players/guards: first/return greetings,
secondary-hand weapons, quick unequip/rearm, deadlines, defense/corpse
interruptions, walls/floors, disconnect, new characters, stale menus, trade
cancellation, restart and recovery. Validate speech audibility and native
equipment/aim replication on clients. No live deployment is part of these checks.

Verification on 2026-10-25: encounter (31), dialogue (66 plus PTBR/EN), task (29),
trade (538), profile (56), module runtime (33), brain (72), sequence (31),
survival (107) and corpse (131) assertion fixtures passed. Shared persistence
resilience scenarios passed, including replay, quarantine, shutdown, offline and
startup paths. Disposable PostgreSQL/JDBC passed 82 assertions; the manager's
targeted suite passed 44 tests and the editor passed six tests. Final target-JAR
dry compilation passed: 272 sources and 826 class files. Both repository diff
whitespace checks passed. These are isolated checks, not live-server results.
