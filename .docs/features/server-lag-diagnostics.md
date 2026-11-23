# Server lag diagnostics and asynchronous Workshop checks

The root agent targets the original 42.22.2 game archive. The manager's recurring
RCON command and direct Lua calls to `checkModsNeedUpdate(connection)` enter the
same method in `LuaManager.GlobalObject`.

## Workshop checks

With Steam enabled on the server, the hook queues the request and returns. The
server's Steam pump starts one native query at a time and shares its result with
requests that arrived while it was pending. Completion callbacks only record the
result count or failure. After the native dispatch returns, the server reads at
most eight Workshop results per pump, yielding after an approximately 1 ms
budget between items. A single native call can take longer than that budget.
The installed-item enumeration and query creation also use the Steam owner
thread. No Lua callback or Steam callback list runs on a worker.

Successful detection retains the update result, chat response where applicable,
and asynchronous HTTP restart notification. Creation, callback and read failures,
timeouts after two minutes, and shutdown remove the callback and release any
live query handle. If the Steam thread holds the lock during JVM shutdown, the
hook leaves cleanup to that thread so shutdown can proceed. Set
`-Dapocbr.workshop.asyncCheck.enabled=false` to restore the vanilla check path.
Restart delivery retains its own configuration.

## Reading a slow tick

Start with `tick.slowest` in the raw schema-3 snapshot. It describes the same
completed simulation tick as `tick.maxMs` when the peak exceeds 100 ms.
`tick.slowest.ts` is the epoch timestamp at the end of that tick. Compare
`tick.slowest.phaseMs` with its `totalMs` and inspect the same tick's
`luaMs`, `luaCalls` and `callbacks`.

- `phaseMs` contains inclusive wall times in that iteration. Nested phases
  overlap. `network.incoming` covers the iteration prefix through queue
  handling and packet-drop bookkeeping, before limiter sleep. Window phase
  totals can also include packet-only iterations. Method timers cover the
  entire matched vanilla method.
- Look at `rcon.update`, `workshop.steamPump` and `workshop.check` for a
  coincident mod check. The first covers RCON command handling, the second
  covers the Steam pump, and the third covers the agent's query start and result
  processing after the pump. Steam timing can also include other callbacks.
- Callback timing wraps the original Kahlua `KahluaThread.call(int)` boundary.
  `Event.trigger` supplies the event or hook name and direct invocations are
  labeled `(direct)`. Rows identify the function, first source line, Lua file,
  configured mod ID if the game's file map resolves it, and Workshop item ID
  if present in the path. `luaMs` uses elapsed time of outer invocations.
  Callback rows can overlap when one invocation triggers a different event.
- The slowest tick lists up to eight callback identities ranked by cumulative
  time in that tick. `lua.callbacks` lists the top twelve across the reporting
  window. Both lists aggregate invocations of at least 1 ms. Their `calls`,
  `totalMs` and `maxMs` cover those invocations. `lua.callbackCallsOmitted`
  counts calls dropped when the window reaches 256 callback identities.
  `-Dapocbr.telemetry.lua.enabled=false` disables callback attribution.
- The same window includes heap gauges, collector count/time deltas, loaded and
  pending server cells, and current-cell moving object counts. Collector totals
  cover the reporting window and cannot establish that a given tick paused for
  GC. JSON encoding and HTTP delivery run on the telemetry worker.

The port restores timings at original method boundaries across the server
loop, map and cell work, simulation, RCON, backup and networking. Some legacy
measurements are still open, including per-packet drain counts, cell
boundary/recalculation substeps, and counters or phases inside unported
optimization paths. Existing agent optimization metrics continue to be emitted.

## Moving-object stalls

`simulation.movingObjects.update` measures the server scheduler update,
including the virtual `preupdate`, `frameStep` and `update` calls in each
bucket. `simulation.movingObjects.startFrame` measures the earlier bucket
setup in the cell, and `postupdate` is a separate pass. Vanilla 42.22.2
takes the dedicated-server zombie tracking branch when filling buckets, so
the snapshot zombie gauge alone does not identify the scheduler workload.

Enable `-Dapocbr.telemetry.movingObjects.enabled=true` at startup to add
callback wall time by stage and kind. The names have the form
`simulation.movingObjects.callbacks.update.vehicle`. Inspect the
slowest tick's phase rows to identify the expensive stage and kind, and
look at the window's call count and maximum call to distinguish a single
stall from repeated work. Scheduler `cpu.update` and `cpu.postupdate`
sample the owner thread's CPU clock when available. A large wall-to-CPU
gap can include blocking, scheduler descheduling or a JVM pause. It does
not identify the wait by itself.

The hook uses the original virtual calls and only records when the
diagnostic server-loop thread is active. Inline postupdate is covered.
Postupdate on an optional animation thread is outside this timing.
See the [original-JAR integration scenario](../validation/server-integration.md#moving-object-scheduler-scenario).

## Validation

The Maven tests verify the registered hook calls against the original 42.22.2
JAR. The Kahlua fixture exercises original event dispatch, direct callback
execution, errors and source attribution. The Workshop fixture exercises
deferral, coalescing, bounded continuation, failed requests, notification
delivery and shutdown with a simulated Steam API.

On Windows with Java 25, `mvn package` ran 131 tests with zero failures and one
historical patched-runtime test skipped. Both fixtures also passed with the
shaded agent. The shaded archive contains no game or fixture classes.

Live Steam behavior and the cause of the production 1.2 s ticks still require
measurements from a running server with its actual mod set.
