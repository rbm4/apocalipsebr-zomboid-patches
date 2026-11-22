# Async NPC planning with server-owned native execution

Implemented in source 2026-11-05 for **42.22.1 dedicated servers**.
Status: production-code fixtures and exact-JAR compilation pass; **not deployed,
live multiplayer validation pending**.

This replaces the live-world NPC worker implicated in the
[confirmed factory/Lua deadlock](../issues/2026-11-05-npc-lua-factory-deadlock.md).
See the [design draft](../reports/2026-11-05-npc-async-deadlock-permanent-fix-draft.md)
and inherited [ownership inventory](../../../42.21.0/docs/reports/2026-10-26-npc-async-snapshot-map.md).
The implementation is deliberately explicit about the async work moved below;
the larger migration inventory is not a claim that every policy is off-thread.

## Implemented boundary

`ServerHumanFactory.update()` always advances the authoritative factory tick on
the initialized server thread. All existing actions, combat phases, native world
capture, physiology/state reductions, inventory/equip, movement/traversal,
dialogue/trade, speech delivery, death, persistence capture and packets stay
behind that owner. Factory guards no longer authorize the NPC worker.

With startup `-Dapocbr.npc.async=true` (default), the existing single worker runs
only `NpcDecisionPlanner.tick()`. Its failure callback publishes planner failure
without entering the factory. It receives sealed value-only requests and has no
factory, actor, native adapter, Lua, callback, connection or persistence port.
There is no direct or synchronous worker-to-world RPC.

The startup log is now
`simulation=async-planning; authority=server; targetHz=10`.
`-Dapocbr.npc.async=false` processes the same detached jobs/result protocol inline
on the server. The existing `SimulationStatus` public record remains compatible;
its async tick counters now measure planning passes, not native-world passes.

| Async work implemented | Server validation/execution |
| --- | --- |
| Guard-zombie engagement selection using immutable observation, held-contact value and settings | Refresh current contact/lifetime, territory and visibility before exposing a selection. Server module owns its target lease, retry timers and combat execution. |
| Destination/partial-leg A* for `MovementRequest.To` on a copied directed grid | Capture topology on server; reject stale/start-tile-changed results, then start the normal physical engine with its original live scope. Recheck collision/traversal during movement. |

Module arbitration, emergency safety checks, squad assistance coordination,
survival retreat scoring, background pattern planning and native execution phases
remain on the server. Keeping these on their owner is safe for the identified
worker lock cycle; moving more pure policy off-thread is an extension, not a
condition for this worker to remain detached. No optimistic async inventory or
combat port was introduced: successful equip still means actual native equip.

## Components and ownership

- [Factory](../../src/zombie/apocalipse/humans/ServerHumanFactory.java) owns server
  gating, medical/group capture, native ticking, planning startup/failure handling,
  pending mutation admission and lifecycle. Server and worker clocks target
  100 ms independently; only the server clock advances native actions.
- [NpcDecisionPlanner](../../src/zombie/apocalipse/humans/planning/NpcDecisionPlanner.java)
  owns fixed-capacity request/result cells and pure job evaluation. Requests are
  sealed `Select` or `Route` values. Navigation collections are deeply copied;
  observations use the existing immutable record/list/set/state contracts.
- [ServerHumanPlanning](../../src/zombie/apocalipse/humans/ServerHumanPlanning.java)
  is a per-incarnation server-side bridge. It owns two slots, pending tickets,
  cached selection freshness and live topology capture. No live scope escapes
  into a request. `ServerHuman` closes its slots in a `finally` on activity stop,
  including cleanup failure.
- [EngageZombiesModule](../../src/zombie/apocalipse/humans/brain/EngageZombiesModule.java)
  receives the planner through the generic `HumanEngagement.Planning` port.
  Standalone modules without a binding retain their local pure selector. The
  production bridge supports the built-in guard doctrine; arbitrary Java Policy
  callbacks are not passed to the worker.
- [Walking](../../src/zombie/apocalipse/humans/ServerHumanWalking.java) represents
  accepted objective navigation as a pending execution. `update()` returns
  RUNNING during capture/planning; enqueue success is not route success. The
  existing physical movement capability starts only after a valid route result.
- [Inventory](../../src/zombie/apocalipse/humans/ServerHumanInventory.java) now
  explicitly checks the actor's construction/server owner before native
  initialization, equip, ammo, reload and corpse-release effects. An off-owner
  equip fails before entering native `OnEquipPrimary`.

## Bounds, freshness and cancellation

- **128 slots**, two for each of the maximum 64 active actors. Each slot has one
  replaceable pending request and one result; no unbounded queue or effect
  receipts. At most one request per slot can be claimed by the single consumer.
- **64 jobs per worker pass**, round-robin over the slot table. An atomic
  consumption guard rejects overlapping consumers. Jobs use bounded existing
  selectors/A*; no missed-tick catch-up burst.
- **Four topology captures per authoritative tick**. Each captures at most 256
  cell queries and 1024 directed edge queries, following traversable edges.
  Missing cells/edges are unavailable. The original A* also remains bounded.
- Selection inputs respect existing contact-memory bounds (128 contacts per
  frame, 128 visitors/territory members). Cached selection expires after 500 ms;
  current target identity/visibility/territory are revalidated. Partial-frame
  held-target grace remains WATCH, without attack authority.
- Objective navigation expires after two seconds and requires the original
  starting tile. Capture congestion remains pending under that deadline. A
  conservative finite topology can reject a route that a fresh live search
  would find; it never authorizes travel through uncaptured geometry.

Tickets advance on cancellation/replacement. An old result cannot claim a newer
ticket. Actor removal closes its private cells; respawn allocates new slot
objects, so reuse of a table position cannot bind an earlier incarnation's work.
Existing suspend/stop/preemption cancels target-planning state and objective
movement cancellation invalidates pending route results immediately on server.

All worker outputs are advisory, replaceable plans. Native one-shot effects stay
within existing server-owned executions and deduplication/commit rules; they are
not represented by coalescing mailboxes. Taking a result removes that receipt.
No server execution waits for a worker future. Main-thread mutation submissions
and asynchronous trusted mutation inbox reductions now execute only when the
server can run them; an independent planning tick cannot change health.

## Failure and shutdown

A worker exception stops planning and publishes failure. On the next server
update the factory rejects pending mutations, closes exclusive sessions, blocks
autonomous activities, cancels speech and publishes stops, with diagnostics.
No restart or silent synchronous fallback. Native medical capture retains its
existing server phase. A stalled world cannot apply these changes until it runs.

Shutdown stops planner admission, signals/joins the worker outside the factory
monitor, then performs actor cleanup/checkpoints on the authority thread.
[GameServer](../../src/zombie/network/GameServer.java) now calls factory shutdown
after its main loop and before the final `System.exit(0)`, so normal shutdown
captures NPC state before the JVM hook tears down native/persistence services.

An off-owner JVM shutdown hook may signal/join the detached worker and reject
pending admissions, but does not take over NPC native cleanup or capture a live
checkpoint. Forced process termination still cannot promise a final authoritative
save; normal server shutdown must run on the server owner. The existing
`ServerMap.QueuedQuit()` hook remains in place and repeated shutdown is safe for
already-stopped actor/planner state.

## Why the recorded deadlock is removed

The former worker held the factory while entering `OnEquipPrimary`; the main
Lua callback held EventMap while waiting for that factory. The worker now holds
neither factory nor Lua monitors and cannot call native equip. Server-thread
equip and dialogue callbacks are on the same authority thread. No new mailbox
operation executes user/native callbacks under an internal monitor.

No `LuaEventManager` or `Event` replacement was added. The native dispatcher's
broader lock/queued-event behavior for other producers is unchanged. NPC native
events are now emitted on the owner rather than depending on its off-thread
queue. This removes the demonstrated NPC worker cycle; it is not a guarantee
about arbitrary mods or unrelated engine locks.

## Validation

| Check | Result |
| --- | --- |
| `py -3 tools/test_npc_async_authority.py` | 191 checks default async, 181 sync, 191 explicit async. |
| `py -3 tools/test_npc_movement_replication.py` | 146 checks. |
| `py -3 tools/test_human_groups.py` | 141 assertions, target 42.22.1 production classes. |
| `py -3 tools/test_human_squad_assist.py` | 143 assertions, target 42.22.1 production policies/adapters. |
| `42.22.1/patchApocalipseBr.ps1 -BuildOnly` | 333 sources / 973 classes, javac 25.0.4. Final output: `42.22.1/artifacts/build_20261105_081006/`. |

The authority fixture compiles complete production planner, bridge, worker,
engagement module, walking/movement engine, pure navigation and state models.
Factory update/start-worker/owner guards and native equip are extracted production
methods with controlled collaborators; it does not execute the full native
factory/game loop. It forces Lua/queue and factory monitors to be held while the
real planning worker advances, checks off-owner equip/state rejection, native
equip notification on the owner, bounded work/admission, actor-slot replacement,
coalescing, stale/current-target validation, held-target grace, pending navigation,
cancellation/expiry and failure/shutdown. World, event dispatch and native
equipment are doubles. Test waits are watchdogs, not performance measurements.

The build uses the installed 42.22.1 baseline JAR recorded by the earlier build
receipt. Documentation links and `git diff --check` pass. No installation or
live database was changed by this work.

Before deployment acceptance, reproduce the Vector squad-engagement/dialogue
polling scenario in both startup modes, verify real Lua equip callbacks,
movement/start/stop, combat timing, partial navigation, trade/healing interruption,
death/respawn, slow server ticks and normal/forced shutdown. Inspect thread dumps,
frame/result age and capture/worker/server CPU under the same NPC workload.
Passing isolated fixtures and compilation does not establish runtime performance
or animation-speed calibration.
