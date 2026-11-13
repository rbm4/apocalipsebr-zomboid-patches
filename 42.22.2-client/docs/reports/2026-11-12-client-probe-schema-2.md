# Schema 2 probe implementation and validation

2026-11-12. Implements deeper measurements requested after the
[sprite-heavy capture](2026-11-12-sprite-heavy-client-capture.md). Definitions,
nesting, enablement and limits are in the
[feature guide](../features/2026-11-12-client-main-thread-telemetry.md).

## Source and runtime boundaries

Added timing-only replacements for the exact 42.22.2 IngameState, IsoWorld,
MovingObjectUpdateScheduler, SpriteRenderer and FileSystemImpl. All five vanilla
references were copied byte-for-byte from 42.22.2/decompiled into the client
baseline. The repository baseline-copy helper created one local baseline commit
per source: 5f78eca, fe96ae5, 6be52a9, 5f582ca and d9cf267. Nothing was pushed.
GameWindow and the existing client telemetry helper were extended in place.

New boundaries separate scene/text/UI/offscreen work from frame-queue handoff,
monitor acquisition and ready-slot wait. Gameplay splits world/cell/physics,
moving-object update and postupdate, contact resolution, climate, entities,
animals, radio, UpdateStuff, Lua events, model update and managers. Background
worker calls are intentionally ignored by owner-thread telemetry.

File completions retain result/cleanup timings by a bounded set of task-class
names; slow frames identify the largest callback. Queue admission lock/insertion
and executor submission are separate phases. No file paths, task objects or live
collections reach the writer. GC collector names/deltas remain separate; main
thread CPU samples and primary render resolution/zoom provide more context.

Vanilla call order, callback exception propagation and lock ownership are retained.
Timing finalizers do not introduce extra done calls or release a vanilla lock
on paths where vanilla would not. FileSystemImpl's decompiled boolean priority
counter was restored to int. IsoWorld's four invalid boolean casts became normal
boolean constants; later reused coordinate slots became independent int x/y
locals. These are compilation repairs in src only, not source behavior changes.

## Verification

- Exact original JAR compilation: seven sources, 31 classes, release 25.
- Production-helper fixture: schema 2 JSON, separate collectors, per-task timing,
  64 named task-type cap plus overflow, reset windows, queue samples, CPU metric
  availability, slow-frame callback context, render context and prior ownership/
  disabled behavior checks.
- File-completion differential fixture: extracts the actual original and patched
  updateAsyncTransactions bodies, stubbing external tasks/executor/logger only.
  Compares call order, queue state, lock state and propagated exceptions for
  completed/cancelled/pending jobs and throwing handleResult/done, with telemetry
  both enabled and disabled. It does not reimplement the production queue loop.
- Deployment round-trip fixture: root/java layouts, dry run, recursive class
  installation, original JAR preservation, repeat deployment and revert restore.
- Public API comparison against the original JAR for all six vanilla replacement
  classes; vanilla reference hashes and documentation links checked.

No live client deployment or new capture was performed. Rendering/native startup,
actual task/mod callbacks, enabled-vs-disabled overhead and correlated collector
pause behavior still require gameplay validation. Existing schema-1 capture
findings are historical and are not evidence that schema 2 fixes performance.
