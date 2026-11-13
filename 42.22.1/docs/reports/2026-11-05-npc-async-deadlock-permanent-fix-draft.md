# Permanent async NPC deadlock fix: proposed ownership boundary

Date: 2026-11-05. Target: **42.22.1 dedicated server**.
Status: **original design proposal; implementation follow-up linked below**.

Implementation follow-up, 2026-11-05: [server authority and detached planning](../features/2026-11-05-npc-async-authority.md)
is now applied in source. That guide, not this original proposal, defines the
shipped source boundary: guard selection and objective A* run through the worker;
remaining arbitration/retreat/background policy stays on server with native
execution. Full migration of every policy in this draft is not claimed.

Evidence: [confirmed factory/Lua deadlock](../issues/2026-11-05-npc-lua-factory-deadlock.md).
This specializes the existing
[snapshot migration contract](../../../42.21.0/docs/reports/2026-10-26-npc-async-snapshot-map.md)
for the observed failure. Follow the existing
[NPC architecture](../../../42.21.0/docs/features/2026-10-20-npc-engine-architecture.md)
and [worker lifecycle](../../../42.21.0/docs/features/2026-10-26-npc-async-worker.md).

## Recommended decision

Keep asynchronous **decision and planning** work. Give the dedicated-server
thread sole ownership of native NPC execution, Lua-facing session state and
replication. Replace the live-world worker's call to `ServerHumanFactory.tick()`
with a worker-private decision runtime consuming immutable observations and
producing bounded intents. The worker must never acquire the factory monitor,
invoke Lua, resolve a live actor or call a native actuator.

This is a real ownership change, not a one-line lock edit. It preserves async AI
without claiming that independent physical movement can continue safely while
the native world thread is stalled. Existing gameplay/execution contracts stay
on their native owner instead of being made optimistically asynchronous.

### Scope distinction

| Option | What it establishes | Limits |
| --- | --- | --- |
| Shorten Lua dispatcher locks | Removing callbacks from both `EventMap` and `QueuedEvents` monitors breaks the two known Lua-monitor/factory cycles. | Requires exact-build Lua dispatcher compatibility work, argument/reentrancy/reset tests and a dedicated-server drain. Other worker native reads/mutations remain. |
| **Recommended owner split** | The worker neither owns the factory nor enters Lua/native execution. Main-thread callbacks cannot wait for a worker-held factory monitor. | Requires detached observations/ports and intent/result contracts across the NPC runtime. Native effects depend on server-thread progress. |

Do not present the dispatcher-only option as proof that the whole current
live-world worker is safe. It can be a separate focused repair, but the owner
split is the permanent NPC design proposed here. No global Lua monkey patch is
required to remove this NPC worker from the confirmed cycle.

## Source facts driving the design

- [Factory.update/startWorker/tick](../../src/zombie/apocalipse/humans/ServerHumanFactory.java)
  currently starts a worker on `this::tick`, while `tick()` holds the factory
  monitor through behavior, native execution and transport. Main-thread
  `serverMedicalTick()` and many authenticated lookup/session entry points also
  acquire that monitor.
- [Dialogue.bind](../../src/zombie/apocalipse/humans/ServerHumanDialogue.java)
  synchronizes Lua API calls on the factory. This currently protects shared
  mutable sessions; deleting it alone creates races.
- [Inventory.equip](../../src/zombie/apocalipse/humans/ServerHumanInventory.java)
  calls native `setPrimaryHandItem()`, which triggers `OnEquipPrimary`.
  [HumanWeaponCombat](../../src/zombie/apocalipse/humans/brain/HumanWeaponCombat.java)
  expects a synchronous equip result. Returning true merely because an equip
  command was queued would advance combat before equipment actually changed.
- Exact target [LuaEventManager](../../decompiled/zombie/Lua/LuaEventManager.java)
  invokes immediate callbacks under `EventMap` and queued callbacks under
  `QueuedEvents`. Both monitors can participate in the same factory inversion.
  Its off-thread path obtains registry/queue locks and checks handlers before
  queuing; it is not a lock-free worker/Lua boundary.
- Target source searches find `RunQueuedEvents()` callers in `GameWindow` and
  `GameLoadingState`, but none in the dedicated-server `GameServer` or current
  server patches. A dispatcher-only queue solution must add and verify a server
  drain rather than assuming worker notifications already reach callbacks.

## Thread ownership after migration

| Server thread only | Decision worker only | Shared by safe publication |
| --- | --- | --- |
| Logical/native actor registry and activation; live world queries and bounded capture | Worker-local behavior/target-selection progress | Deeply immutable configuration/catalog revisions |
| Health reductions, incoming damage, authoritative claims and control epochs | Bounded route/tactical planning over detached navigation pages | Immutable latest observation frame |
| Dialogue/trade sessions, Lua tables/events and all Lua API calls | Private contact/policy memory derived from observations | Bounded command/result/event records with lifetime identifiers |
| `HumanActivityController`, `HumanActionExecution`, `HumanWeaponCombat` and active native combat sessions | Desired action/intention selection, not native execution phases | Atomic publication/mailboxes; no locks held while invoking callbacks |
| Inventory/equip/reload, hit commits, movement/traversal/drag, physiology projection, death/corpse effects | Decision deadlines and cancellation reconciliation | Acknowledgement sequence/counters; no live object references |
| Relevance/viewer caches, packet construction/sending, checkpoints from detached facts | No factory/native/session/transport adapters | Existing independent persistence workers retain their own ownership |

Keep server-facing synchronous APIs synchronous. Server Lua can inspect/modify
its own session state and receive truthful immediate results. No Lua callback
waits for a worker future. Worker policy sees interaction ownership through the
next observation/control epoch. Existing state stores must stop allowing worker
mutation once authority moves; migrate their guards with the ownership change.

## Proposed bridge and tick phases

Names below are proposed interfaces, not existing deployed classes.

```text
ServerHumanFactory.serverTick()
  authoritative invalidations (death, disconnect, dialogue/trade, admin stop)
  -> drain a bounded amount of prior worker intents
  -> validate and advance server-owned executions/native effects
  -> publish results + immutable observations

NpcDecisionRuntime.tick()              [one async worker, target 10 Hz]
  read latest observation + acknowledged events/results
  -> reconcile private policy state
  -> select targets / plan / choose desired actions under budgets
  -> offer bounded intents; never wait for the server
```

Do not queue missed decision ticks. A newer frame can replace older observations;
accepted one-shot results and unconsumed events require separate acknowledged
delivery and cannot disappear when a latest-frame reference is replaced.

Server execution retains the movement engine's elapsed cap, collision checks,
traversal arbitration and cancellation. Run native execution on a server-side
due clock rather than once for every queued plan. Keep actual motion publication
at the implemented simulation cadence when the server can sustain it; no burst
of old motion packets after a stall. The
[movement replication change](../features/2026-11-05-npc-movement-replication.md)
continues to reuse one current snapshot per broadcast.

The current `GameServer` factory update hook is a candidate integration point.
Trace its ordering relative to native `OnTick`, damage/session intake and world
updates before implementation. A frame must describe a consistent completed
authority phase; do not publish a mixture of pre- and post-command actor state.

## Concrete intent and result contracts

Every envelope carries world epoch, logical actor key, activation/incarnation,
control epoch, command/execution ID, source frame sequence and monotonic deadline.
Operations add relevant target lifetime, profile/navigation revisions and
dependencies. Do not require an unrelated health revision to remain identical
for a useful navigation proposal.

| Worker output | Server action and result |
| --- | --- |
| `EngageIntent(targetHandle, mode, tacticalPlan)` | Revalidate live target, hostility/PvP, visibility/age, activity ownership and range; create/continue a server-owned combat execution. Publish accepted/rejected/progress/terminal observations. |
| `MoveIntent(routeProposal, pace, scopeRevision)` | Revalidate bounded route/scope/navigation assumptions; start or replace authorized movement. Existing server executor owns per-step collision, position and stop. |
| `ActionIntent(immutableAction)` | Validate allowlisted immutable action data; create a fresh server-owned execution. Preserve ordered Sequence/Wait/Say semantics and interaction exclusivity. |
| `CancelIntent(executionId, reason)` | Cancel only the matching incarnation/control epoch/execution. Server-originated preemption does not wait for this command. |

Keep `HumanWeaponCombat` and `ServerHumanInventory.equip()` together on the
server initially. Their existing boolean result continues to mean actual equip
success, and `OnEquipPrimary` runs on the Lua owner thread. The worker selects
an engagement; it does not independently enter WINDUP, spend ammo or commit hits.
Only move an execution phase to the worker later if its port is explicitly
changed to PENDING/acknowledged results with equivalent cancellation semantics.

No `IsoPlayer`, `IsoZombie`, square, item, connection, Lua closure/table, live
`TraversalEdge.obstacle`, mutable collection view or lambda closing over one can
cross the bridge. Server capture publishes values/lifetime handles. Server
execution resolves handles again and checks object reuse. Copying an outer list
does not detach its elements. Pin immutable catalog/config revisions per batch.

## Cancellation, boundedness and failures

- Server control epochs advance immediately for death/despawn, dialogue/trade
  reservation, persistence outage and administrative stop. Stop/cancel executes
  locally even if the worker stalls. Reject older output and never revive an
  expired actor incarnation.
- Use fixed-capacity mailboxes and global/per-actor capture, planning and drain
  budgets. Choose capacities from instrumented maximum in-flight executions,
  not arbitrary unlimited lists. Latest replaceable movement/target intent may
  coalesce; equip/hit/loot/transaction results must not coalesce or silently drop.
- Reserve terminal-result capacity before accepting a one-shot execution.
  Without capacity, reject new work **before** a native effect. Accepted results
  remain until acknowledged. Backpressure pauses new autonomous admissions,
  not the world thread or cancellation. Reconcile uncertain state explicitly;
  never retry a potentially committed effect as a new command.
- Deduplicate one-shot effects by world/incarnation/execution/command identity.
  A repeated accepted intent returns its prior receipt instead of spending
  ammo or applying damage again. Bound receipt retention by the acknowledged
  in-flight protocol; reject older IDs beyond that window rather than reapply.
- Worker failure stops decision production with diagnostics. The server cancels
  unsafe autonomous executions, retains authoritative sessions/state and marks
  affected actors blocked. No silent restart, synchronous fallback or optimistic
  success. Handling an error does not conceal an already committed effect.
- Shutdown rejects admissions, invalidates authority and stops executions on
  their owner; signal/join the pure worker outside all factory/Lua/queue locks.
  The worker must never depend on a final server callback to exit. Teardown
  follows the established server shutdown phases; a shutdown-hook thread must
  not take over world/Lua mutation just because the main thread is stalled.

## Why this eliminates the observed cycle

In the capture, the worker held factory F and waited for Lua event lock L, while
the main thread held L and waited for F. After the split:

1. The worker can hold neither F nor L and invokes neither factory nor Lua.
2. Main-thread Lua APIs and native equip execute on the same server owner.
   If a retained factory monitor is acquired reentrantly by that thread, no
   other decision worker can hold it.
3. Bridge synchronization only publishes bounded data and invokes no user/Lua
   callbacks or native effects while holding a mailbox lock.

The cross-thread F → L edge and the worker-owned F required by this cycle are
absent. This is a scoped lock-cycle proof, not a claim about every vanilla/mod
lock or a measured performance improvement. Main-thread Lua can still be slow;
that is execution latency rather than this circular wait.

## Delivery plan and reviewable changes

1. Add bridge DTOs, bounded mailboxes, metrics and strict owner guards, with
   no worker/native behavior change. Add a deterministic reproduction fixture.
2. Split factory composition into server authority and worker decision runtime.
   Keep actions/combat/movement/native services on the server; replace worker
   ports with detached implementations. Convert module/controller call sites
   that currently return live `HumanCombat.Execution` or actuate directly.
   Audit callback, task, damage, group and persistence paths for hidden aliases.
3. Move bounded decision/route work onto the worker through those ports. Preserve
   legacy synchronous startup mode using the same decision/authority protocol,
   rather than maintaining two different gameplay implementations.
4. Enable the migrated async path only after the full owner boundary is complete.
   An intermediate build must not label itself safe if even one worker port
   can enter Lua/native world code. Keep the current mitigation available during
   migration; do not ship a half-converted equip proxy or delete session locks.
5. Measure native capture/execution versus worker CPU and frame/result age before
   tuning budgets. Revalidate packet cadence and path/attack behavior under load.

Likely source families: `ServerHumanFactory`, `ServerHumanSimulationWorker`, new
bridge/runtime classes, behavior/engagement/navigation ports and modules, state
access guards, and server integration/diagnostics. `ServerHumanDialogue` and
trading retain authority instead of sharing sessions with the worker. Inventory
and native setters gain server-owner guards. No packet-format migration.

### Smaller dispatcher-only alternative

If implemented separately, copy the exact target `LuaEventManager` baseline
according to repository rules. Remove all callback execution from both registry
and queue locks across **every 0..8-argument overload**, including wrappers.
Queue append/claim operations must not execute callbacks. Drain claimed events
on the verified Lua owner, outside locks, with explicit reentrancy and ordering
rules, bounded work, and pool cleanup in `finally` on all paths. Preserve the
current callback add/remove-during-dispatch semantics; do not assume a frozen
callback array is behaviorally equivalent.

Audit registry/reset/register/reroute/getEvents/setEvents and unknown-event
registration, not just trigger methods. Native `checkEvent()` inspects a mutable
callback list and may register an unknown event; moving synchronization without
an ownership policy introduces races. Preserve public/nested class signatures.
Confirm cross-thread pool release against target `Pool`/`PoolReference` rather
than assuming it is illegal or automatically safe in every changed path.

Add a dedicated-server queued-event pump with verified owner and phase ordering.
Specify backlog/admission limits and explicit overflow behavior before enabling
new producers; do not silently discard already committed equip notifications.
This alternative requires its own implementation review and tests. It is not
part of the recommended owner split and is not applied by this draft.

## Acceptance tests

Use production code with controlled ports and latch/barrier scheduling:

1. Reproduce the original EventMap/factory cycle deterministically in an isolated
   subprocess with a hard timeout. Assert both ownership edges, not just a sleep
   or thread count. Repeat with queued-event/factory ordering.
2. Run the migrated runtime with a main-thread Lua callback active while the
   decision worker produces an engagement intent. Verify world and worker
   progress, actual server-thread equip, exactly one equip notification and no
   factory/Lua access from the worker. Include nested Lua API calls.
3. Test main-thread ownership of dialogue/trade reserve/view/select/complete,
   cancellation, immutable responses and all native execution ports. A captured
   native reference or off-owner adapter call must fail the fixture.
4. Exercise stale frame, death/respawn, native ID reuse, disconnect, profile/control
   revision changes, unload, delayed decisions and duplicate commands/results.
   Verify no stale effects and truthful accepted versus completed results.
5. Saturate command/result capacities. Assert bounded memory/work, admission
   rejection before effects, preserved accepted terminal results and cancellation
   progress while the worker is stalled. Include worker failure and shutdown
   during a callback without lock-held joins or server waits on worker results.
6. Compile all target sources against the real 42.22.1 JAR. Validate async and
   synchronous startup on a dedicated server with two observing stock clients:
   repeated squad engagement/equip, dialogue polling, healing/trading,
   fleeing/corners, death/respawn, delayed server ticks and shutdown. Capture
   thread dumps and telemetry; require no deadlocks and equivalent authority/
   one-shot behavior, not merely absence of a timeout.

The original draft changed documentation only and made no candidate validation
claim. Subsequent implementation, checks and open runtime validation are recorded
in the implementation guide linked above.
