# Texture preprocessing workers and deeper client probes

Current schema 5 extension: [deferred textures, asynchronous translucent ordering and building lookups](2026-11-12-client-deferred-textures-and-async-translucency.md).

Measured follow-up: [schema 4 capture analysis](../reports/2026-11-12-schema-4-capture-analysis.md).

Implemented in source 2026-11-12 for 42.22.2-client, following the
[schema-3 capture](../reports/2026-11-12-schema-3-capture-analysis.md).
Exact-JAR compilation and isolated/native-buffer tests pass. Not deployed or
validated in a live game. Schema 4 originally extended the
[main telemetry guide](2026-11-12-client-main-thread-telemetry.md).

## Worker image preprocessing

TextureIDAssetManager snapshots raw-image flags, effective size limit and mipmap
debug-color policy into ApocBRTextureLoadTask. The dedicated preprocessing pool
decodes through the original FileTask_LoadImageData, then preprocesses an
exclusively owned image before FutureTask publication. Workers do not invoke
asset callbacks, read Core settings, manipulate TextureID state, run Lua or
create OpenGL textures. The task retains a receiver like the original callback;
only handleResult invokes it after claiming the result on the completion owner.

ApocBRTexturePreprocessor follows vanilla limitMaxSize: preserve transparent
color, generate/select mips, allocate/copy the selected buffer, construct the
smaller ImageData, set alphaPaddingDone and dispose the source. It reuses the
production pixel algorithms and thread-local scratch. Debug-color policy is
frozen per task and cached once per mip pass, avoiding per-pixel ThreadLocal
lookups. Main/render callers retain current policy. GPU upload stays on the
render-context queue; asset adoption/notification stay on their original owner.

| Startup property | Default | Meaning |
| --- | --- | --- |
| apocbr.clientTextures.workerPreprocess | true | false restores original raw-image loading/scaling for comparison. Independent of telemetry enablement. |
| apocbr.clientTextures.preprocessMiB | 256 | Extra-memory concurrency target, clamped 1-16384 MiB; oversized jobs take all permits and may exceed it. |

Only raw client image tasks with size-limit flags use this path. Packed textures
and animated APNG preprocessing stay vanilla. Already-small images do no extra
work. Dedicated-server asset loading does not select the new task. At most two
resizes run concurrently. Admission estimates every extra mip buffer
(including narrow textures) plus the final copy, rounded to MiB. Busy/oversized
jobs wait on workers; oversized jobs process alone instead of returning to main-thread scaling.

Workers do not wait for main-thread RPC. The budget controls ordinary admitted
extra simultaneous work, not total process memory or oversized jobs: original decode buffers,
completed results and existing game allocations are outside it. The existing
16-task in-progress mechanism and bounded dedicated executor control submission.

## Cancellation, settings and ownership

ApocBROwnedResult publishes once; exactly one main claimant or cancelling
disposer takes the result. Cancellation before decode skips it; cancellation
during processing revokes adoption while the worker finishes and disposes its
private result. Future.cancel(false) can fail after completion, so all three
FileSystemImpl.cancelAsync lookup paths also revoke the new task token. Other
task kinds retain original behavior. Failed futures notify asset failure once.

Main completion rechecks flags, limit and debug policy. Changed settings dispose
the unadopted result and start a fresh decode: increasing a limit cannot recover
discarded pixels. Unload/reload cancellation prevents stale completed adoption.
Failure before adoption disposes an unowned image. An adopted image remains
TextureID-owned even if subsequent notification throws. A receiver owns its
claimed result, including its error paths.

Owned disposal frees unpublished APNG frame buffers on cancellation and publishes
disposal state/prunes entries under the allocator monitor. ImageData.dispose now
skips unallocated mip slots so partially generated images can be cleaned up.
Future/gate publication establishes the image handoff; no image reaches the
telemetry writer. Worker processing never concurrently accesses a published image.

## Original schema 4 additions

`workerTextures` is sampled/reset by the writer with thread-safe numeric adders:
enabled, PREPROCESSED, INPUT_PIXELS, OUTPUT_PIXELS, WORK_NANOS, FALLBACK, FAILED,
CANCELLED_RESULTS and SETTINGS_RELOADS. Pixel totals are logical dimensions for
successful resizes; FAILED counts preprocessing exceptions, not ordinary decode
failures. CANCELLED_RESULTS counts discarded results, not cancellation requests.
These are writer intervals, not per-frame attribution. The first sample can
include work before in-game entry, and jobs can span windows. Successful offload
should reduce main TEXTURE_MIP_SELECT/scaled while PREPROCESSED increases.
FALLBACK explains remaining main scaling. WORK_NANOS is work paid elsewhere,
not itself measured saved frame time. Main texture context describes the already
processed input dimensions after a successful handoff.

WORLD_REGIONS children: REGION_SWAP, REGION_RESET_REFERENCES,
REGION_PROCESS_BUILDINGS, REGION_WORKER_UPDATE and REGION_LOGGER. Swap includes
the reset/building work. No region algorithm moved threads.

FBO renderer children: CELL_FBO_RENDER, CELL_GRID_STACKS, CELL_CHECKS,
CELL_PREPARE_CHUNKS, CELL_PREPARE_CHUNK, CELL_OCCLUSION, CELL_LIGHTING,
CELL_DRAW_TILES, CELL_RENDER_CHUNK, CELL_REBAKE_LEVEL, CELL_CORPSES, CELL_ITEMS,
CELL_MOVING_OBJECTS, CELL_TRANSLUCENT_FLOORS, CELL_TRANSLUCENT_OBJECTS and CELL_FOG.
Calls provide workload counts: chunk render attempts, level preparations and
dirty-and-canRender rebake branch entries. These are CPU attempts, not exact GPU
uploads, unique chunks, visible sprites or draw calls. Timers wrap whole
passes/chunks, not pixels/sprites. Nested totals overlap. CELL_FBO_RENDER calls
identify that backend; legacy rendering retains WORLD_RENDER_CELL outer timing.

Both Event.trigger branches now time OnTick callbacks individually, preserving
protected calls, exceptions and list-mutation behavior. LUA_ONTICK_CALLBACK is
inclusive. `slowLua` includes thresholdMs, count and up to 32 largest callbacks
per window (unsorted): frame, ms, file, function, line and closureId. File uses
prototype.file or filename; line is the first recorded instruction line or -1.
Closure identity hashes are process-local. Source/function strings are limited
to 512 characters. No closure or VM object reaches the writer. Slow count includes
records omitted by the cap. Reentrant timings overlap. Wall time includes waits.
`-Dapocbr.clientTelemetry.slowLuaMs=5` is the default (minimum 1 ms).

## Validation and next capture

- Exact original-JAR release-25 build: 17 sources / 54 classes.
- Native-buffer test compares real ImageData pixels/dimensions/alpha policy with
  the original resize body, normal/debug-color policies, including 1024x1024.
  It covers admission fallback, publication/adoption, failed decode, cancellation
  races, all three production cancellation routes after future completion, and
  1,000 ownership races. Tracked native image bytes return to zero in tested paths.
- Completion-policy fixture covers changed flags/limits/debug policy, re-decode,
  failure notification and exceptions before/after adoption.
- Event.trigger differential fixture covers ordering, callback self-removal,
  removing another callback, addition, exceptions and empty events, in both debug
  branches and with telemetry enabled/disabled.
- Helper fixtures check schema 4, bounded/escaped Lua identities and worker fields.
  Region/FBO vanilla call-site order is checked; runtime rendering equivalence
  and enabled/disabled instrumentation overhead still need gameplay validation.
- Disposable deployment tests cover expanded recursive class families, unsigned
  runtime, settings preservation and repeat revert. Public APIs/baselines checked.

The build script now checks javac exit status instead of treating successful
stderr deprecation notes as failure in Windows PowerShell. Baseline commits for
IsoRegions, FBORenderCell and Event: 24cb20d, 1629332 and 35a74d3. No push.

Reinstall through instalador.bat with the client closed. Keep telemetry enabled
and use a fresh file. Compare worker preprocessing enabled/disabled under the
same route/load conditions, and validate cancellation/reload and transient memory
with actual mods before claiming gains. No live game files/process were changed
by this implementation and testing.
