# Schema 3 probes and image worker-offload assessment

2026-11-12. Requested follow-up to the
[schema 2 capture](2026-11-12-schema-2-capture-analysis.md). The maintained
[telemetry guide](../features/2026-11-12-client-main-thread-telemetry.md) defines
all new fields/phases and their nesting. This change measures existing work; it
does not enable off-thread scaling, alter pixel algorithms or change load order.

## Implemented probes

- IngameState separates IsoWorld.render, model-outline setup/finish, legacy
  postrender, synchronous OnPostRender Lua and Core.EndFrame inside RENDER_SCENE.
- IsoWorld separates culling, weather mask, body/item atlases, cell render,
  weather and skybox. Update probes cover hutch/fog/helicopter, the whole sound
  emitter loop, sound/group/collision initialization, OnceEvery, regions, halo,
  recorder and designation-zone work previously outside measured children.
- RenderThread's synchronous Runnable invocation separates full invocation from
  waitUntilFinished. Main-thread render-context RPC can now be distinguished from
  sprite queue backpressure without timing render workers from the main arrays.
- TextureID separates setImageData, limitMaxSize, mip selection/generation,
  buffer allocation, buffer copy, source disposal and upload enqueue. The
  TextureIDAssetManager success notification has a separate phase.
- ImageData records whole mipmap generation and complete per-level premultiply
  passes on the main owner, not per-pixel operations. Off-owner calls are ignored.
- Bounded texture summaries retain dimensions, flags, effective size limit,
  scaling count, logical pixel totals and the largest size-limit operation per
  window and retained slow frame. No asset object or image filename is exported.

These resolve the next boundaries inside the 741-ms scene stall, the ~410-ms
world-update remainder and the large image-result bursts. RENDER_CELL remains
broad; its children can be expanded once this capture identifies their importance.
Source paths exist for debug/native work outside named children, so new child
totals need not exhaust every parent. CPU time remains frame-level and coarse.

## Could image scaling run on a file worker?

**Yes, this is a plausible narrow offload boundary.** Current exact-build flow:

```text
main: TextureIDAssetManager.startLoading creates FileTask_LoadImageData
worker: waitFileTask -> decode PNG -> ImageData result
main: FileSystemImpl.updateAsyncTransactions -> handleResult callback
      -> TextureID.setImageData -> limitMaxSize -> queue GPU upload
render context: createTexture
```

The expensive candidate is CPU preprocessing after decode, before result
publication. It should not require moving world state, Lua or OpenGL to workers.
ImageData's mipmap and premultiply scratch objects are ThreadLocal. Its buffers
and mip arrays are instance state. DirectBufferAllocator serializes allocation
and registration; this alone does not prove every buffer lifetime/disposal path
safe, since WrappedBuffer.dispose itself is not synchronized.

An implementation worth testing would:

1. Capture an immutable preprocessing request on the asset owner when creating
   the load task: flags, effective maximum size, relevant pixel/debug policy and
   asset load identity. Workers must not read mutable Core settings or TextureID
   lifecycle fields to decide the transformation.
2. Decode and resize exclusively owned, unpublished ImageData on the existing
   file worker. Reuse the exact vanilla mip generation, premultiplication,
   transparent-color policy, copy and disposal order. This is not a new filtering
   algorithm. Publish an owned result through the existing future boundary.
3. Apply the result and notify asset state on the main thread; leave GPU creation
   on the render context. For a still-matching size limit, limitMaxSize should
   become a cheap no-op at handoff.
4. Revalidate cancellation/load identity and settings. A previously downscaled
   result cannot recover higher-resolution pixels if the setting changed upward;
   that case needs an explicit re-decode/reload policy rather than accepting the
   smaller result indefinitely. Reloaded/unloaded assets must not receive stale
   completion effects.
5. Give every output exactly one disposal owner. FileTask_LoadImageData.done is
   empty today; moving an owned derived buffer into a future requires handling
   cancellation while running, cancellation after preprocessing, rejected
   publication and callback failure without leaking or double-freeing buffers.
6. Bound decoded/mipmap/result memory and concurrency. waitFileTask checks a
   50-MiB direct-buffer threshold before decoding; several workers can pass that
   check before allocating. Mipmap generation expands transient buffers and
   retaining completed results can worsen pressure. The first capture's heap
   samples already indicate little headroom. Do not add another unbounded queue
   or one thread per texture.

This assessment is grounded in FileTask_LoadImageData, TextureIDAssetManager,
TextureID, ImageData, AssetTask_RunFileTask, FileSystemImpl, DirectBufferAllocator
and WrappedBuffer in the 42.22.2 baseline. TextureID.limitMaxSize also queues
render-context work only afterward; moving the CPU half need not move GL calls.

The schema-2 file identifies the callback type, not the expensive operation
inside it. Schema 3 will show whether limitMaxSize/mipmap work dominates or the
asset success notification/upload enqueue has a different cost. If scaling does
dominate, this can remove that main-thread CPU work, while the total texture
processing still consumes CPU/memory elsewhere. Batches of remaining completion
work can continue to cause spikes; better average FPS or elimination of the
unrelated scene/world stalls is not established.

## Baseline and validation

Exact vanilla references for TextureID, TextureIDAssetManager, ImageData and
RenderThread were copied from 42.22.2/decompiled. The required baseline helper
created separate local commits f166fa7, fd7700b, fb2ad4b and f40956e; no push.
Previously patched client classes were extended in place. Decompiled references
and the original game JAR remain unchanged.

- Exact-JAR release-25 compilation: eleven Java sources / 40 generated classes.
- Production-helper fixture covers schema 3 texture counters/dimensions,
  per-frame largest scaling context, window reset and off-owner rejection in
  addition to existing ownership/task/collector tests.
- Texture differential fixture executes the actual vanilla/patched limitMaxSize
  bodies against identical stub image/buffer dependencies. It compares ordered
  calls, output dimensions/bytes, identity, transparency flags, source disposal
  and failure propagation for no params, no size-limit flags, already-small and
  oversized images, including mip-generation/disposal exceptions, with telemetry
  enabled and disabled. This tests instrumentation transparency, not the actual
  pixel-filter algorithm or a worker offload (neither algorithm was changed).
- Existing production-body file-completion differential test remains passing.
- Disposable deployment tests cover the expanded recursive class families and
  restoration; public API comparisons and vanilla hashes are checked.

Live rendering/native initialization, gameplay overhead and a fresh schema-3
capture remain runtime validation. No deployment to the user's live game and no
new performance optimization occurred during this implementation.
