# Experimental NPC simulation worker

The dedicated-server NPC runtime defaults to an independent 10 Hz worker.
Set the JVM startup option `-Dapocbr.npc.async=false` to retain synchronous
simulation inside `GameServer`'s loop. `-Dapocbr.npc.async=true` explicitly selects
the default. Selection is fixed when the factory class initializes; restart to
change modes. This is a live-world concurrency experiment, not the detached
snapshot architecture in [the access map](npc-async-snapshot-map.md).

## Startup and scheduling

`GameServer` keeps its existing `ServerHumanFactory.update()` call. The first
ready updates initialize the factory, install its Lua API and finish accepting
configuration on the server thread. The first simulation pass also runs there.
After configuration succeeds, the factory starts one daemon thread named
`ApocBR-NPC-Simulation`. Failed or incomplete configuration does not start it.
An empty configured roster can still start the worker, allowing later activation.

Subsequent NPC passes run on that worker; server updates keep installing/updating
the Lua API and the server zombie victim adapter. The worker calls the existing
factory tick: physiology, profile intake, perception, behavior, route planning,
movement/combat/traversal, speech, corpse tasks, viewer management, packet delivery
and checkpoints keep their existing data access. The initial change does not
create world snapshots or marshal native effects back to the world thread.

The worker parks between deadlines. Normal deadlines are 100 ms after the actual
previous execution start. If execution takes at least that long, it records an
overrun and waits another 100 ms after completion. It never queues ticks, overlaps
its own executions or replays missed periods. Thus 10 Hz is a target, not a
guarantee. Existing factory 100 ms gating and 500 ms routine transport heartbeat
remain. Sleeping gives CPU capacity back to the OS; it reserves no capacity and
does not prove a server throughput improvement.

## Ownership and retained concurrency

Factory entry points accept the initialized server thread and that factory's
worker only. Factory tick/lifecycle/maps are serialized with the factory monitor.
Factory-managed `HumanStateStore` instances use this explicit access guard and
serialize reducer dispatch/restore, including fresh stores created for respawn.
Standalone stores still reject writers other than their construction thread.

This limited locking prevents obvious races inside factory maps and state
reductions. Returned native actors and their behavior/adapters are not locked for
the entire lifetime of a server callback. Existing packet, damage, dialogue,
trade, zombie, inventory, geometry and Lua event paths can still run concurrently
with worker accesses. Worker-originated calls can still invoke Lua events and
native game methods. Keeping Lua API installation on the server thread does not
make those downstream calls thread-safe.

The server world loop does not wait for each worker tick. NPC factory lookups and
mutations from callbacks can wait on the factory monitor while a tick executes.
Therefore this experiment does not guarantee that all NPC cost is removed from
the main thread. Native shared caches/pools, object reuse and silent lost effects
remain risks beyond exceptions. See [the inventory](npc-async-snapshot-map.md)
for the planned long-term ownership boundaries.

## Failure, diagnostics and shutdown

An exception escaping the worker tick stops that worker, logs a full stack trace
and completed-tick statistics, rejects pending mutations and disables new queued
mutations. Main-thread updates do not restart it or fall back to synchronous
simulation. Existing controllers may handle their own errors as before; those
handled errors do not reach the worker boundary. A failed worker does not roll
back a partially executed tick or remove its native actors. Restart to recover.

`ServerHumanFactory.simulationStatus()` provides a lock-free diagnostic record:
selected mode, whether a worker was created, failure state, completed worker
ticks, last completed duration in nanoseconds and number of overrunning ticks.
The counters do not include the initial server-thread pass, per-family timings
or queue time. `started` means a worker was created, including one later stopped.
There are no new `ApocBRServerTelemetryLite` phase fields in this change.

`ServerMap.QueuedQuit()` stops/joins the worker before saving or tearing down
world/pathfinding/network services. The join occurs outside the factory monitor.
Shutdown rejects pending mutations and captures final NPC checkpoints while
persistence is still alive. A worker that does not stop within ten seconds causes
an explicit shutdown failure instead of silently continuing world teardown.
Shutdown is idempotent and prevents later worker starts/server updates. This hook
also runs in synchronous mode, where it has no worker to join.

## Validation

`python tools/test_npc_simulation_worker.py` executes the production worker and
factory against engine stubs in separate default/explicit-async/sync JVMs. It
checks independent progress without server updates, access guards, queue/result
handling, no overlap/catch-up, overrun accounting, failure stop/no restart,
shutdown/idempotence and shutdown hook ordering.

Use the existing lifecycle, state, profile, death/respawn and interaction fixtures
for affected contracts, then compile with `42.21.0/patchApocalipseBr.ps1 -DryRun`
against the exact target game JAR. Stub tests and compilation do not validate
native concurrent geometry, Lua, packets, streaming or persistence under load.
Runtime checks should exercise those paths, server ticks below 10 Hz, NPC death
and respawn, client reconnect, and shutdown during a long NPC tick. No runtime
speedup or full thread safety is established by this change.

Verification for this implementation:

- `test_npc_simulation_worker.py`: passed default async, explicit async and sync
  runs (31, 31 and 26 assertions), including concurrent authorized state writers.
  Injected failures intentionally print stack traces to verify failure reporting.
- `test_server_humans.py`, `test_human_state.py`, `test_human_profiles.py`,
  `test_human_death_respawn.py`, `test_human_dialogue.py`, `test_human_trading.py`,
  `test_npc_runtime.py`, `test_human_retaliation.py`, `test_human_speech.py`: passed.
  Each is invoked as `python tools/<name>`; fixtures exercise production code
  against engine doubles, not a live concurrent server.
- `./42.21.0/patchApocalipseBr.ps1 -DryRun`: passed against
  `Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar` using JDK
  25; 296 server Java sources compiled and 874 class files produced. No deployment.
- Documentation relative links and `git diff --check`: passed. Live multiplayer,
  concurrent streaming/geometry/Lua and slow-tick/load verification remain open.
