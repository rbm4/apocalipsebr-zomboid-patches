# Client main-thread telemetry scaffold

Created 2026-11-12 for **42.22.2-client**. Compiled against the exact original game
JAR; not deployed or tested in a live multiplayer session. See
[validation and provenance](../reports/2026-11-12-client-telemetry-validation.md).

## Main-thread entry point in multiplayer

The thread is named `MainThread`, not `Render Thread`. Its bootstrap is
[GameWindow.InitGameThread](../../src/zombie/GameWindow.java), which supplies
start/step/exit callbacks to [MainThread.init](../../decompiled/zombie/MainThread.java).
The recurring call chain is:

```text
MainThread.mainLoop()
  synchronized (m_contextLock)
    flushInvokeQueue()                 # outside frameStep measurements
    mainThreadLoop.run()
      GameWindow.mainThreadStep()      # FPS-cap/accumulator gate
        GameWindow.frameStep()         # actual frame: telemetry boundary
          logic()
            GameClient.instance.update()  # multiplayer incoming update processing
            UIManager.update()
            states.update()
              IngameState.update()
                IngameState.updateInternal()
                  IsoWorld.instance.update()
                  ...entities, managers, Lua OnTick, model update...
            fileSystem.updateAsyncTransactions()
          IsoWorld.instance.FinishAnimation()
          IsoObjectAnimations.update()
          renderInternal()             # game-thread render preparation
          onRender()
          Core.DoFrameReady()
          LightingThread.instance.update()
          LuaEventManager.RunQueuedEvents()
```

[IngameState](../../decompiled/zombie/gameStates/IngameState.java) owns the gameplay
update; GameWindow.logic also includes work outside it. This is why timing just
IngameState would miss network dispatch, UI, render preparation and other work.
MainThread also performs invoke-queue work before the frame callback.

The separate RenderThread consumes prepared sprite states and performs rendering.
`RENDER_PREPARE` is **not GPU time or render-thread execution time**. Wall timing
can include waits/descheduling/GC. A slow phase identifies where the main thread
spent time; it does not prove which CPU/GPU/lock caused it.

`FRAME_READY` wraps the method with that name; in this exact build Core.DoFrameReady
updates the keyboard. Do not interpret its name as a GPU presentation fence.

## Capture and ownership

[ApocBRClientTelemetryLite](../../src/zombie/ApocBRClientTelemetryLite.java) adapts
the server scaffold's NDJSON + bounded asynchronous writer model, with independent
client properties/schema and no server database, ServerMap or Lua dependencies.
The only vanilla replacement is GameWindow. No simulation algorithm is changed.

Enablement requires the startup property AND a frame starting with all of:

- not GameServer.server;
- GameClient.client and GameClient.ingame;
- GameClient.instance.connected;
- states.current is an IngameState.

This excludes menus/loading/single-player/server frames. It also permits a client
connected to a hosted/co-op server: this scaffold does not identify whether the
remote endpoint is a standalone dedicated process. Frames that disconnect during
logic are counted if they began connected; the next ineligible frame flushes the
partial window and resets cadence. A new connection starts fresh cadence.

The first eligible caller becomes owner. Off-owner measurements are ignored.
Only the game thread reads zombie/moving-object list sizes, in O(1) per measured
frame; counts represent loaded lists, not rendered/visible/attacking zombies.
The writer receives only numeric arrays, strings and detached records. It never
reads game collections, invokes Lua or calls rendering APIs.

Normal capture adds nanoTime calls and fixed primitive accumulator updates.
It does not allocate one object per phase or log one line per frame. Allocation
occurs for interval snapshots and retained slow frames. At most 16 worst frames
are retained per window and the writer queue defaults to eight windows. A full
queue drops a complete window without waiting. Snapshot copying is included in
future cadence but outside frameWork. Writer JSON formatting/filesystem work is
off-thread; its overhead still needs an enabled/disabled runtime comparison.

Normal game-thread exit flushes the last window and waits at most one second for
the daemon writer. A crash/kill or blocked storage can lose pending records. No
per-frame I/O, world locks, worker-to-main RPC or unbounded work queues are added.

## Build and enable

From the repository root:

```powershell
.\42.22.2-client\Build-ClientTelemetry.ps1
py -3.12 .\42.22.2-client\tests\test_client_telemetry.py
```

The build script compiles **all src Java files** with the repository JDK 25 and
the exact pinned original game JAR. It retains classes, compiler.log and
receipt.json under a new ignored artifacts/build-* directory. It never installs
classes or changes launchers. Whole-class overrides need a fresh baseline review
if the JAR hash changes. Installation must also account for existing overrides
and the repository's unsigned-runtime/classpath deployment model; do not replace
the original game JAR.

After the compiled class families are installed through the client deployment
flow, add this JVM argument and restart the client:

```text
-Dapocbr.clientTelemetry.enabled=true
```

Optional properties:

| Property prefix: apocbr.clientTelemetry. | Default | Meaning |
| --- | --- | --- |
| enabled | false | Startup opt-in. |
| path | apocbr-client-telemetry.ndjson | Append-only output, relative to process working directory unless absolute. Parent directories are created by writer. |
| intervalMs | 5000 | Reporting interval, clamped to 1000-60000 ms. |
| slowFrameMs | 50 | Frame-work threshold, clamped to 1-60000 ms. |
| queueCapacity | 8 | Detached output windows, clamped to 1-256. |

No rotation is implemented in this scaffold. Use a fresh path for separate runs
or archive the output between runs; seq restarts with the JVM.

## Schema 1 interpretation

Each NDJSON line has kind=client-main-thread, targetBuild=42.22.2, seq, ts and
monotonic elapsed intervalMs. Wall-clock ts is for correlation, not duration.

- **frameWork:** elapsed wall time inside the instrumented frameStep capture.
  Excludes FPS-cap waiting and preceding MainThread invoke-queue processing.
- **frameCadence:** start-to-start interval between measured frames. Includes the
  preceding frame's work, outside-frame work, pacing and descheduling. It is not
  display-present FPS. The first frame after entry has no cadence sample.
- **phases:** count/totalMs/avgMs/maxMs plus fixed histograms. LOGIC includes
  NETWORK, STATE_UPDATE, UI_UPDATE and FILE_TRANSACTIONS. Timings are inclusive:
  **do not sum LOGIC and its children**. Uninstrumented logic is the remainder.
  Other frame phases are FINISH_ANIMATION, OBJECT_ANIMATIONS, RENDER_PREPARE,
  ON_RENDER, FRAME_READY, LIGHTING and QUEUED_LUA.
- **histogram:** 13 buckets with inclusive upper bounds in ms of
  1, 2, 4, 8, 16.666667, 25, 33.333334, 50, 100, 250, 500, 1000, infinity.
  p95UpperMs/p99UpperMs are histogram upper bounds, not exact percentiles. null
  means no samples or percentile in the >1000-ms overflow bucket; inspect count
  and histogram to distinguish these cases. Displayed bounds round to 3 decimals.
- **context:** loaded-zombie and moving-object counts on the last frame of the
  window. This is a context snapshot, not a window average.
- **slowFrameCount/worstFrames:** count of frames whose frameWork meets the
  threshold and up to 16 largest frameWork samples (unsorted). Each includes
  per-phase durations and same-frame population context. cadenceMs describes the
  interval leading to that frame, so its delay is not necessarily explained by
  that frame's phases. A cadence-only hitch need not appear in worstFrames.
- **writerJvm:** heap and GC deltas sampled when the writer processes a window,
  with a separate sampleTs. First GC deltas are -1 (no prior writer sample).
  Values cover the JVM, not this thread; GC collection time is not exact STW pause
  attribution. Queuing can shift the sample relative to captured frames.
- **droppedWindows/writeErrors:** cumulative process counters. Sequence gaps can
  indicate dropped windows or failed writes. Error logging is rate-limited.

STATE_UPDATE is intentionally coarse. This scaffold does not yet split individual
zombie AI, collision/separation, animation, Lua callbacks, chunk integration,
render-thread waits or GPU submissions. First capture comparable quiet/horde
sessions and inspect frameWork versus cadence and phase maxima/worstFrames. Add
the next measurement boundary in the dominant phase, rather than assuming hordes
mean the zombie update is the cause. Use profiler/JFR stacks for waits or
unattributed time; this scaffold alone cannot explain every stutter.
