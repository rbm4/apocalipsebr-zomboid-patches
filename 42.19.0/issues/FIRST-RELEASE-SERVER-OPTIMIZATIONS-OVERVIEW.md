# ApocBR Build 42.19 Dedicated Server Optimizations — First Release Overview

## Purpose

This document describes the first stable release of the ApocBR Build 42.19 dedicated-server patches after the rollback back toward vanilla ownership.

The goal of this release is not to redesign the engine. The goal is to keep the Project Zomboid dedicated server close to vanilla execution ownership while smoothing the largest burst costs, adding production telemetry, and preserving a small set of proven safety fixes.

In practical terms, this release is meant to:

- reduce frame hitches caused by mass cell load/unload churn;
- preserve world-mutation ownership on the main thread;
- reduce avoidable simulation work for animals, vehicles, and zombie network ownership;
- expose enough telemetry to understand where the server is spending time in production;
- keep deployment and rollback simple through loose `.class` overrides.

---

## Release scope

This patch set targets **Project Zomboid Build 42.19** and is intended primarily for the **dedicated server**.

The deploy scripts compile the patched Java sources and copy loose `.class` files over the normal game install so they override the classes inside `projectzomboid.jar`.

Deployment artifacts are produced by:

- `patchApocalipseBr.ps1`
- `patchApocalipseBr.sh`

These scripts:

1. locate a suitable JDK;
2. compile the patched sources in one `javac` invocation;
3. back up original classes;
4. deploy the patched classes as loose overrides;
5. support revert/rollback by removing the overrides.

This release does **not** depend on repacking the JAR.

---

## Design principles for this release

This first release intentionally keeps the server close to vanilla in the places where race conditions became too expensive:

- no off-thread world mutation for cell load or unload;
- no async publish/commit pipeline for `ServerCell` lifecycle;
- no custom worker-owned cell states exposed to live gameplay objects;
- no off-thread Lua execution ownership changes as part of the baseline behavior.

The philosophy is:

- keep smoothing and throttles;
- keep instrumentation;
- keep safety guards that prevent real crashes;
- avoid behavior that changes *who* owns world mutation or *when* the world becomes visible.

---

## What ships in this first release

## 1. Main-thread deferred unload queue for `ServerMap`

The most important gameplay-side optimization in this release is the deferred unload queue in `ServerMap`.

### Behavior

- Cells do **not** unload immediately when they become irrelevant.
- A cell must remain irrelevant for **60 seconds** before it is eligible to drain.
- Unload work remains on the **main thread**.
- Unload is processed incrementally rather than tearing down an entire cell in one large burst.
- The queue is adaptive and can increase drain effort when the backlog or oldest age rises.

### Why it exists

Vanilla-style immediate unloads create large hitch opportunities when many players move, disconnect, or drive vehicles through cell borders. The queue trades memory and delayed retirement for smoother frame pacing.

### What to expect in production

- More cells may remain resident for longer.
- `pending` unload depth may rise during heavy exploration.
- `revalidated` cells are a **good sign**: they are unloads that were avoided because the cell became relevant again before teardown.
- The queue should eventually enter drain periods after exploration pressure eases.

### Important operator interpretation

A non-zero `serverMapUnload.pending` is not automatically a problem. The important question is whether:

- `oldestMs` keeps growing forever, or stabilizes;
- `unloaded` keeps making progress;
- `serverMapPostMaxMs` stays controlled.

---

## 2. Main-thread load finalization with frame budget

This release keeps cell load finalization on the main thread, but smooths how much finalize work is consumed per frame.

### Behavior

- Load finalization remains main-thread-owned.
- The system works with a target frame budget instead of greedily finalizing every prepared load at once.
- The current normal budget is **50 ms**.
- When the previous frame was already heavy, the finalize budget is reduced dynamically:
  - elevated: **30 ms**
  - high: **20 ms**
  - critical: **10 ms**

### Why it exists

This keeps the server from compounding a bad frame with too much additional load-commit work.

### What to expect in production

- Large movement bursts still create load work.
- First-time map generation and first exploration of new terrain can still spike.
- The system reduces how much finalize work is admitted into an already-bad frame, but does not eliminate load cost entirely.

### Current reality

This release is usually better at smoothing unload than at eliminating initial load spikes. Teleports, fast travel, and first-time chunk generation can still produce visible max values in `serverMapPre`, `connectionChunk`, and `serverMapLoadFinalize`.

---

## 3. Adaptive unload drain levels

The queue can step up its drain effort based on backlog and age.

### Current thresholds

- unload grace: **60 seconds**
- warning age: **120 seconds**
- stress age: **180 seconds**
- warning pending threshold: **64** cells
- stress pending threshold: **256** cells

### Current drain model

- normal slices per tick: **1**
- warning slices per tick: **4**
- stress slices per tick: **8**
- normal cells per tick: **1**
- warning cells per tick: **1**
- stress cells per tick: **2**

### Operational meaning

This lets the server behave gently under ordinary movement, then push harder when the queue becomes stale or too deep.

---

## 4. Animal simulation tiering and virtual-animal telemetry

Animal work is one of the main places where server load can silently grow as the active world expands.

This release keeps the server-side animal simulation reductions that proved useful:

- baseline active/relevant animal simulation is reduced from full to **HALF**;
- inactive animals are reduced to **SIXTEENTH**;
- telemetry exposes the live bucket split in `movingAnimalBuckets`;
- virtual animal simulation is also exposed through `virtualAnimalSim`.

### What to expect

- Animal-heavy areas should be cheaper than vanilla.
- A snapshot showing animals in `half` and `sixteenth` buckets is expected and healthy.
- If animals are shown running mostly at `full`, that indicates a regression.

---

## 5. Moving-object and package/network throttles

This release keeps the simulation-level reductions and network-side throttles that behaved well in production testing.

This includes, at minimum:

- moving object scheduler adjustments that reduce expensive full-rate updates for non-critical objects;
- zombie network ownership/auth throttles that defer non-urgent work when appropriate;
- retained vehicle/animal relevance-based reductions inside the single-thread model.

### What to expect

- Better object-update scalability as player count rises.
- Less wasted work on low-value or distant simulation.
- More predictable frame pacing under mixed vehicle/animal/player populations.

---

## 6. Zombie no-cull baseline fix

This patch set removes the aggressive server-side zombie cull behavior tied to `ZombieCountOptimiser.deleteZombies()`.

### Why it matters

Vanilla behavior can over-delete zombies under multiplayer pressure, causing the online world to become too empty over time.

### What to expect

- Zombie populations should remain more stable than under the prior cull-heavy behavior.
- The tradeoff is that zombie-related cost must instead be controlled through scheduling, networking, visibility, and queue smoothing.

---

## 7. Player LOS / FOW instrumentation and safe baseline behavior

The patch set includes instrumentation for player line-of-sight / fog-of-war work and keeps the baseline server behavior stable.

Telemetry exposes:

- `playerLOS`
- `playerLOSObjects`
- `playerLOSParallel`
- `playerLOSSequential`
- `playerLOSComputeAvgMs/MaxMs`
- `playerLOSApplyAvgMs/MaxMs`

### Current expectation

For this release, most production snapshots should show LOS work staying modest relative to world and unload pressure. The metrics are primarily there to reveal when LOS becomes the next scalability wall.

---

## 8. Production telemetry

The telemetry work is one of the biggest deliverables of this release.

`ApocBRServerTelemetry` now exposes a broad set of server-facing sections including:

- frame/world totals;
- queue depth metrics;
- server-map pre/post/partition/cell-task timings;
- load finalize timings and budget state;
- unload queue depth, age, throughput, and teardown phase timings;
- zombie network/auth metrics;
- player LOS metrics;
- moving object bucket/type counts;
- animal simulation counters;
- packet queue throughput;
- parallel-world guard telemetry.

### Why this matters

Before these patches, many performance discussions were anecdotal. With telemetry, production decisions can be based on:

- which subsystem is actually hot;
- whether queue backlog is recovering or worsening;
- whether load or unload is the dominant burst source;
- whether zombie network load is rising faster than world simulation.

### Logging cadence

Telemetry is emitted on the server through `ApocBRServerTelemetry.maybeLog()` during the main loop window.

---

## 9. Proven safety fixes kept in the baseline

This release also preserves a set of crash-avoidance and corruption-avoidance fixes that are considered safe enough to keep even after reverting more aggressive async experimentation.

These include categories such as:

- stale-chunk/pathfind safety to avoid native crashes when chunk state changes underneath pathfind work;
- null/corruption guards in item/chunk save paths;
- selected null-safety guards in server-side engine/update code where the fix prevents a hard failure without changing scheduling ownership.

### Important note

This release tries to keep only the safety fixes that make sense in a mostly-vanilla ownership model. Guards that only existed to paper over async races are not the intended long-term baseline.

---

## Expected server-side behavior after applying this release

## 1. Better frame pacing under movement churn

When many players move, disconnect, or drive across the map, the server should:

- hitch less from unload teardown;
- defer irrelevant cells rather than retire them immediately;
- avoid unnecessary unload+reload loops for cells that quickly become relevant again.

### Positive signs in telemetry

- `revalidated` is non-zero during travel-heavy windows;
- `unloaded` continues increasing over time;
- `serverMapPostMaxMs` stays moderate even while unload progress is happening.

## 2. More memory usage than vanilla during exploration

Because of the 60-second unload grace and queue behavior, memory pressure is intentionally higher than immediate-unload vanilla behavior.

This is the tradeoff being taken in exchange for frame stability.

### Expected operator observation

- more cells remain loaded during exploration;
- unload queue depth may remain non-zero for long periods;
- once exploration slows, the queue should enter a recovery/drain period.

## 3. Load spikes are reduced, not eliminated

The server should no longer aggressively pile finalize work into already-bad frames, but new area generation, fast travel, and heavy chunk intake can still spike.

### Expected operator observation

- `serverMapLoadFinalize.maxMs` can still spike during teleports, admin travel, or first-world generation;
- these spikes should usually be easier to isolate than in older all-at-once behavior.

## 4. Revalidations are good, not bad

A high `revalidated` count means the server avoided unnecessary unload work.

Operationally, this often means:

- fewer pointless chunk saves;
- fewer pointless detach/re-attach cycles;
- fewer follow-up load finalize bursts;
- less border thrash during player movement.

---

## How to read the most important telemetry fields

## Unload health

Primary fields:

- `serverMapUnload.pending`
- `serverMapUnload.queued`
- `serverMapUnload.revalidated`
- `serverMapUnload.unloaded`
- `serverMapUnload.oldestMs`
- `serverMapPostMaxMs`
- `serverMapUnloadDetail.squareTeardownMaxMs`

Healthy pattern:

- `pending` may rise during exploration;
- `revalidated` may be significant;
- `unloaded` continues progressing;
- `oldestMs` stabilizes or recovers instead of growing forever;
- teardown max values stay controlled.

Concerning pattern:

- `pending` grows over multiple windows;
- `unloaded` stays low;
- `oldestMs` climbs without recovery;
- `serverMapPostMaxMs` and teardown max values rise sharply.

## Load pressure

Primary fields:

- `serverMapPreMaxMs`
- `connectionChunkMaxMs`
- `serverMapLoadFinalize.maxMs`
- `serverMapLoadFinalize.budgetMs`
- `chunks.maxWaiting`

Healthy pattern:

- occasional spikes during burst intake;
- finalize completes without queueing indefinitely;
- chunk intake remains bounded.

## Zombie pressure

Primary fields:

- `state.zombies`
- `zombieNetwork.live`
- `zombieNetwork.authScanned`
- `zombieNetwork.authUrgent`
- `zombieNetwork.syncPackets`
- `zombieNetwork.syncZombies`

Healthy pattern:

- zombie network metrics grow with population, but `postMaxMs` and frame max values stay modest.

## Animal pressure

Primary fields:

- `movingAnimalBuckets`
- `virtualAnimalSim`
- `movingTypes` / `movingStartTypes`

Healthy pattern:

- most animals live in `half` or `sixteenth` buckets;
- little or no `full` animal simulation on the server baseline.

---

## Operational expectations for dedicated servers

Administrators applying this release should expect the following:

### What should improve

- smoother exploration and vehicle travel;
- fewer catastrophic unload hitches;
- fewer wasted unload/reload cycles at cell borders;
- much better visibility into real bottlenecks.

### What will still happen

- first-time area generation can still spike;
- heavy chunk intake can still create load pressure;
- queue depth can remain non-zero for long periods under active exploration;
- max values will still spike during real gameplay events.

### What is normal

- non-zero `pending` unloads;
- non-zero `revalidated` unloads;
- unload queues entering drain periods after exploration slows;
- moderate `serverMapPostMaxMs` while the server keeps good loop throughput.

### What deserves investigation

- queue age growing over many consecutive windows without recovery;
- unload throughput collapsing while `pending` rises;
- `movingAnimalBuckets.full` rising materially;
- repeated `serverMapLoadFinalize.maxMs` spikes tied to non-teleport normal play;
- zombie network metrics rising much faster than active zombie count.

---

## Known tradeoffs and limitations

1. **This release prefers smoothness over aggressive memory retirement.**
   Keeping cells for 60 seconds is intentional.

2. **Load spikes are improved but not solved.**
   Initial load and generation are still heavier than unload in many burst scenarios.

3. **Telemetry is the primary operating tool.**
   This release is designed to be steered by measurement, not assumptions.

4. **This is a stable baseline release, not the final architecture.**
   The patch set is intentionally conservative after the async world-mutation experiments proved too race-prone.

---

## Deployment notes

- Target: **Build 42.19**.
- Deploy using `patchApocalipseBr.ps1` on Windows or `patchApocalipseBr.sh` on Linux.
- Prefer a dry run before first deployment.
- Keep backups of original class overrides and use the script revert mode when rolling back.
- These patches are most meaningful on the **dedicated server**. Local-host testing is useful for functional validation, but dedicated behavior is the real target.

---

## Summary

This first release should be understood as a **vanilla-baseline server optimization pack** rather than an engine rewrite.

It keeps the server on a safer execution model while delivering four major benefits:

1. **main-thread load/unload smoothing**;
2. **reduced waste through deferred unload and revalidation**;
3. **simulation/network throttles for scaling**;
4. **strong production telemetry for diagnosis and iteration**.

If the deployment is healthy, the server should show:

- more stable frame pacing during movement churn;
- unload queues that eventually recover instead of hitches that freeze the frame;
- animal and zombie workloads that scale more gracefully than vanilla;
- telemetry that makes the next bottleneck obvious.
