# Client main-thread telemetry scaffold

Current schema 8 adds [worker building geometry and bulk commit measurements](2026-11-13-async-building-reconciliation.md).
Schema 7 added [network update and packet-stage telemetry](2026-11-13-client-network-telemetry.md).
Existing behavior: [deferred textures and asynchronous translucent ordering](2026-11-12-client-deferred-textures-and-async-translucency.md).
See the [client network async assessment](../reports/2026-11-12-client-network-async-assessment.md) for the scope of NETWORK timings.
Full cost review: [measured rendering/simulation costs and worker boundaries](../reports/2026-11-13-client-worker-boundaries-and-full-cost-review.md).
Rendering/building lifecycle: [source-backed practical async plan](../reports/2026-11-13-rendering-and-building-async-lifecycle.md).

Created 2026-11-12 for **42.22.2-client**. Compiled against the exact original game
JAR; deployment tested in disposable installations, not in a live multiplayer session. See
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
Schema 2 adds replacements for IngameState, IsoWorld, MovingObjectUpdateScheduler,
SpriteRenderer and FileSystemImpl alongside GameWindow. Schema 3 additionally
instruments TextureID, TextureIDAssetManager, ImageData and RenderThread.
No simulation or texture-processing algorithm
is changed. The decompiled FileSystemImpl admission counter and IsoWorld reused
boolean/coordinate local slots require minimal Java type corrections to compile.

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

The root `instalador.bat` and `patch-client.ps1` route to the version's
`patchApocalipseBr.ps1`, whose default is **compile and deploy**.
The PowerShell wrapper discovers the Steam game directory or accepts `-PZDir`,
forwards `-ArtifactDir` with `-BuildOnly`, and accepts `-NoPause` for automated use.
`-DryRun` validates launchers and compiles without game changes. `-Revert`
restores backed-up loose classes (or removes newly introduced ones) and points
JSON back to the original JAR. Example:

```powershell
.\patch-client.ps1 -NoPause -DryRun -PZDir 'Z:\SteamLibrary\steamapps\common\ProjectZomboid'
```

Normal deployment compiles into a unique system temporary directory and cleans
it afterward. The client includes the same Set-UnsignedGameJar helper as the
server target: it prepares/reuses `projectzomboid-apocbr.jar` from the read-only
original, stripping only top-level signature metadata, with a hash receipt.
Both existing native launcher JSON files are validated before writes; classpath
starts with `.` (or `java/.`), then the derived JAR. Other classpath entries,
mainClass, VM arguments and platform settings survive. This client path does not
install PostgreSQL dependencies or modify the dedicated-server batch launcher.

Loose compiled class families go under the game JAR directory's `zombie/` tree.
Original loose overrides are backed up under `apocbr-client-telemetry-backup/`;
`apocbr-client-telemetry.install.json` tracks classes for reversion. Launcher
backups use `.apocbr-client.bak`; revert edits current JSON rather than restoring
an old descriptor over independent settings. The disposable derived JAR remains
after revert. Deploy/revert with the game stopped. Publication is per file, not
a transaction across the entire installation. Runtime arguments are preserved,
so capture still requires the enable property below.

The separate Build-ClientTelemetry script (`-BuildOnly` from the installer)
compiles **all src Java files** with the repository JDK 25 and
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

## Schema 4 interpretation

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
- **writerJvm.collectors:** individual collector names, countDelta and
  collectionTimeMsDelta. First/unsupported deltas are -1. Concurrent-cycle and
  pause collectors remain separate; their collection times must not be summed
  and described as application pause time. These are writer-time MXBean samples,
  not per-frame pause events.
- **frameCpu:** current-thread CPU duration inside each captured frame when JVM
  thread CPU timing is already available/enabled; the probe does not enable it
  globally. No samples means unavailable. Worst frames include cpuMs (null when
  unavailable). Wall time minus CPU time suggests off-CPU time, but does not
  distinguish GPU/backpressure, locks, GC or OS descheduling. CPU on background
  workers is not counted. CPU and wall endpoints are sampled consecutively.
- **context.screenWidth/screenHeight/playerZoom:** last captured primary-player
  render context. Not a visible-sprite count, draw-call count or GPU workload.
- **fileSystem:** resultCallbacks counts attempted main-thread handleResult calls,
  including callbacks that throw; submitted counts successful executor.submit
  calls. tasks maps class names to separate handleResult/done timing summaries.
  The map retains at most 64 named types plus an <other> overflow bucket for the
  session; taskTypeOverflow counts measurements routed to it during the window.
  No task objects or file paths are retained. Types already seen can have zero
  calls in later windows. inProgressLast/pendingLast/addedLast are queue sizes at
  the last admission point (added read under the original lock), and backlogMax
  is the maximum sum at those points, not a continuously observed high-water mark.
  Worst frames also include largestFileCallback (type/ms, or null), covering
  either handleResult or done. The phase totals disambiguate result vs cleanup.
- **droppedWindows/writeErrors:** cumulative process counters. Sequence gaps can
  indicate dropped windows or failed writes. Error logging is rate-limited.

Schema 2 adds these inclusive child boundaries. They also appear in worstFrames:

| Parent | Child phases |
| --- | --- |
| RENDER_PREPARE | RENDER_INLINE_LIGHTING, RENDER_LIGHTING_PREUPDATE, RENDER_PICKER, RENDER_STATES |
| RENDER_STATES | RENDER_SCENE (per-player renderframe), RENDER_COMPOSITE (offscreen buffer), RENDER_TEXT, RENDER_UI |
| RENDER_UI, normally | RENDER_HANDOFF wraps SpriteRenderer.pushFrameDown; RENDER_HANDOFF_MONITOR measures acquiring its states monitor; RENDER_READY_SLOT_WAIT measures the subsequent ready-slot wait. HANDOFF includes both waits and queue publication. |
| STATE_UPDATE | UPDATE_CHUNK_POSITION, UPDATE_WORLD, UPDATE_ENTITIES, UPDATE_ANIMALS, UPDATE_RADIO, UPDATE_STUFF, UPDATE_LUA_TICK, UPDATE_LUA_EVEN_PAUSED, UPDATE_MODELS, UPDATE_MANAGERS |
| UPDATE_WORLD | WORLD_PHYSICS, WORLD_CLIMATE, WORLD_CELL, WORLD_CONTACTS, WORLD_REMAINDER (synchronous updateThread), WORLD_WORKER_WAIT (join when world threading enabled) |
| WORLD_CELL | MOVING_UPDATE wraps the scheduler update; MOVING_POSTUPDATE normally occurs after the cell update under UPDATE_WORLD, or remains unmeasured on an animation worker. |
| FILE_TRANSACTIONS | FILE_HANDLE_RESULT, FILE_DONE, FILE_ADMISSION_LOCK, FILE_ADMISSION, FILE_SUBMIT |

Phase arrays run only on the capturing owner thread. Schema 4 reports worker
preprocessing separately through numeric workerTextures counters; simulation
scheduling options are unchanged. Nested phase counts can differ from
frame counts and must not be added to their parents. RENDER_SCENE still includes
multiple rendering systems. UPDATE_CHUNK_POSITION measures ProcessChunkPos, not
all chunk ingestion. WORLD_CELL still includes unseparated cell systems.
UPDATE_STUFF wraps the existing UpdateStuff method; Lua timings are event-level,
not individual mod callbacks. Frame handoff waits measure main-thread latency,
not GPU execution duration. Sprite/draw counts and per-chunk dirty/rebake counters
remain future probes. Use profiler/JFR for off-CPU causes and remaining large
phase remainders.

Previous captures are schema 1 and schema 2. Old files retain their schema, and
append output can contain multiple versions. Use a fresh output path for the next
comparison. Rebuild/reinstall through instalador.bat with the client stopped;
the installer now discovers seventeen Java sources (54 generated classes).

### Schema 3: scene, world remainder and texture processing

| Boundary | New phases |
| --- | --- |
| RENDER_SCENE | SCENE_WORLD_RENDER, SCENE_OUTLINE_START, SCENE_OUTLINE_END, SCENE_LEGACY_POST, SCENE_LUA_POST_RENDER, SCENE_END_FRAME |
| SCENE_WORLD_RENDER | WORLD_RENDER_CULL (one call each for zombies/animals), WORLD_RENDER_MASK, WORLD_RENDER_BODY_ATLAS, WORLD_RENDER_ITEM_ATLAS, WORLD_RENDER_CELL, WORLD_RENDER_WEATHER, WORLD_RENDER_SKYBOX |
| UPDATE_WORLD remainder | WORLD_HUTCH, WORLD_FOG, WORLD_HELICOPTER, WORLD_EMITTERS (whole emitter loop), WORLD_SOUND_INIT, WORLD_GROUP_PREUPDATE, WORLD_ONCE_EVERY, WORLD_COLLISION_INIT, WORLD_REGIONS, WORLD_HALO, WORLD_RECORDER, WORLD_DESIGNATION |
| Main-thread synchronous render-context RPC | RENDER_CONTEXT_INVOKE (whole Runnable overload) and nested RENDER_CONTEXT_WAIT (waitUntilFinished). These differ from the sprite ready-slot wait. |
| Texture image application | TEXTURE_SET_IMAGE; nested TEXTURE_LIMIT_SIZE and TEXTURE_QUEUE_UPLOAD; TEXTURE_ASSET_SUCCESS wraps asset notification after setImageData returns. |
| TEXTURE_LIMIT_SIZE scaling branch | TEXTURE_MIP_SELECT, TEXTURE_BUFFER_ALLOC, TEXTURE_BUFFER_COPY, TEXTURE_SOURCE_DISPOSE |
| Image CPU preprocessing | IMAGE_GENERATE_MIPMAPS; IMAGE_PREMULTIPLY wraps each complete mip-level premultiplication, never individual pixels. These can nest. |

All new timings still require the capturing main-thread owner. Decode workers
and render-thread GPU uploads are not timed by these phase arrays. QUEUE_UPLOAD
measures the enqueue operation, not subsequent GPU texture creation. Schema 3
itself did not move work between threads. Schema 4 adds the
[worker preprocessing path](2026-11-12-texture-workers-and-client-probes.md).
Whole-loop phases avoid per-emitter/pixel instrumentation.

The new `textures` object reports limitCalls (measured limitMaxSize attempts),
scaled (successful source replacement/disposal), inputPixels/outputPixels totals
and largestLimit. Pixel totals are logical dimensions, not hardware-padded memory
bytes. Non-scaling calls contribute unchanged dimensions; an exceptional attempt
can retain its initial output dimensions. They are workload context, not memory
accounting. largestLimit contains elapsed ms, input/output width/height, maxSize,
flags and scaled. maxSize=-1 means vanilla did not consult the size-limit setting
on that path. Each retained worst frame has its own largestTextureLimit or null.
Only detached numeric data are retained, with no texture object/path collection.

See the [schema 3 implementation and worker-offload assessment](../reports/2026-11-12-client-probe-schema-3-texture-offload.md).

The [schema 3 capture analysis](../reports/2026-11-12-schema-3-capture-analysis.md)
confirms CPU mip preprocessing as the image-result cost, identifies IsoRegions
spikes, and narrows persistent rendering cost to cell rendering.

The [first sprite-heavy multiplayer capture](../reports/2026-11-12-sprite-heavy-client-capture.md)
records measured costs and the next instrumentation boundaries, including render
handoff waits and main-thread asset-result handling.

The [schema 2 capture analysis](../reports/2026-11-12-schema-2-capture-analysis.md)
narrows measured cost to scene rendering and image-data result callbacks, rules
down the measured ready-slot wait, and identifies a remaining world-update gap.
