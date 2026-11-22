# Server freeze: NPC factory versus Lua event monitor

Date: 2026-11-05. Target: **42.22.1 dedicated server**, asynchronous NPC mode.
Status: **confirmed live JVM deadlock; source fix implemented, runtime verification pending**.
The [async authority implementation](../features/2026-11-05-npc-async-authority.md)
removes native execution/factory access from the worker and passes target fixtures
and compilation. No deployment, configuration change or process termination.

Original correction design: [async ownership split draft](../reports/2026-11-05-npc-async-deadlock-permanent-fix-draft.md).
Consult the implementation guide for the actual async job scope and limits.

Related: [worker ownership limits](../../../42.21.0/docs/features/2026-10-26-npc-async-worker.md),
[squad assistance](../features/2026-11-04-npc-squad-combat-assistance.md),
[movement publication](../features/2026-11-05-npc-movement-replication.md).

## Evidence

Java server PID **25316** started 2026-11-05 at 00:57:55 local time. Its usable
log was `C:/Users/ricar/Zomboid/server-console.txt`; the corresponding
`Logs/2026-11-05_00-57_DebugLog-server.txt` was empty when inspected. Archived
00:28 server/00:30 client logs belong to a previous session.

Retained local evidence (ignored artifacts):

- [Server console snapshot](../../artifacts/npc-server-25316-console-2026-11-05.txt).
- [Live thread dump](../../artifacts/npc-server-25316-threads-2026-11-05.txt),
  captured with JDK25 `jcmd 25316 Thread.print -l`. The JVM explicitly reports
  `Found one Java-level deadlock` and `Found 1 deadlock`.

At frame **486**, monotonic log time `st:95,411,676`, Vector starts combat
execution 1 against zombie 26634, template `SQUAD_ASSIST`, and reports
`phase=ENGAGING`. No later world frame appears in the inspected console.
Persistence metrics continue on frame 486. A later shutdown attempt logs
`IllegalStateException: NPC simulation did not stop before world teardown`;
the dump also shows shutdown waiting for the NPC worker.

## Confirmed circular wait

| Thread | Holds | Waits for | Captured call path |
| --- | --- | --- | --- |
| `main` | Lua EventMap, HashMap `0x0000040055200840` | Factory `0x0000040045d50f88` | `IngameState.onTick` → `LuaEventManager.triggerEvent:279` → Lua callback → `ServerHumanDialogue.lambda$bind$0:231` |
| `ApocBR-NPC-Simulation` | The same factory | The same Lua EventMap | `ServerHumanFactory.tick:247` → squad assistance → weapon execution → `ServerHumanInventory.equip:54` → `IsoGameCharacter.setPrimaryHandItem:3765` → `LuaEventManager.triggerEvent:297` |

[ServerHumanDialogue.bind](../../src/zombie/apocalipse/humans/ServerHumanDialogue.java)
acquires the factory for Lua API calls. Exact target
[LuaEventManager](../../decompiled/zombie/Lua/LuaEventManager.java) holds
`synchronized(EventMap)` while invoking main-thread callbacks; the two-argument
overload acquires it before deciding whether an off-thread event should be queued.

[ServerHumanInventory.equip](../../src/zombie/apocalipse/humans/ServerHumanInventory.java)
calls [setPrimaryHandItem](../../src/zombie/characters/IsoGameCharacter.java)
while the worker holds the factory. That setter triggers `OnEquipPrimary`.
It cannot reach its off-thread queue because the main thread holds the event
monitor while waiting for the worker's factory monitor.

The shared `bind()` lambda does not identify which API name or Lua script called
it. The dump does prove the monitor owners and circular wait.

## Movement relationship and recovery

Installed Factory, Movement and Transport class hashes match retained
`build_20261105_004655`, so the movement changes were present before this process
started. Neither blocked stack serializes movement packets. This is evidence of
the existing live-world worker lock-order problem, not a malformed movement
payload. It does not rule out cadence changing when the race occurs or establish
anything about client gait parity.

For a controlled restart, existing **server** startup flag
`-Dapocbr.npc.async=false` returns NPC execution to the world thread and removes
this particular two-thread cycle. It cannot unlock the current deadlock.
`ProjectZomboid64.json` configures the client, not this dedicated server. No
startup flag or process was changed during this investigation.

A durable correction must remove opposite factory/Lua lock ordering across the
worker/native-capability and Lua API boundaries. Audit other Lua-triggering
native calls too. Preserve authenticated dialogue/session ownership, incarnation
checks, bounded work and exactly-once effects. Simply deleting factory locking
exposes shared session state; suppressing events or extending shutdown timeout
does not repair the ownership model. Add a deterministic two-thread regression
for this cycle, compile against the target JAR and validate both startup modes,
equip events, dialogue and shutdown in game.

The original investigation performed only diagnostic attachment, evidence capture
and documentation. Subsequent implementation/tests/build are recorded in the
linked authority guide; the captured JVM incident remains historical evidence.
