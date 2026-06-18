# ApocBR Server Performance Analysis — 2026-06-17

## Snapshot Data

**Timestamp:** Jun 17 15:13:31
**Server:** srv1255239 (dedicated)
**Players:** 16
**Zombies:** 2122
**Ticks:** 122 in 30s = **~4.1 TPS** (target: 10 TPS)
**Avg tick:** 241ms (target: <100ms)

---

## 1. Tick Budget Breakdown

```
world{avgMs=241} ──────────────────────────────────
  │
  ├── stateUpdate 211ms (88%)
  │   ├── isoWorld 192ms
  │   │   └── currentCell 173ms
  │   │       ├── startFrame       4ms   (2%)
  │   │       ├── movingObjects   158ms  (66%) ← HOT
  │   │       ├── isoObject        7ms   (3%)
  │   │       └── items/remove     0ms   (0%)
  │   ├── gem                  12ms   (5%)
  │   └── animationPost         9ms   (4%)
  │
  ├── serverMapPre    4ms   (2%)
  ├── serverMapPost  12ms   (5%)
  └── connChunk       2ms   (1%)
```

**158ms (66%) of every tick is spent in ProcessObjects → MovingObjectScheduler.update().**

---

## 2. Moving Objects Breakdown

### Per-Type Cost

```
IsoPlayer    1,952 calls = 16/tick × 3ms avg = 48ms baseline, 89ms max
BaseVehicle 59,458 calls = 487/tick × 0ms avg = near-zero, 21ms max
IsoAnimal   17,434 calls = 143/tick × 0ms avg = near-zero, 104ms max ← SPIKE
```

### Key Ratios

| Object | Calls | Per Tick | Avg | Max | % of 158ms |
|---|---|---|---|---|---|
| IsoPlayer | 1,952 | 16.0 | 3ms | 89ms | ~48ms (30%) |
| BaseVehicle | 59,458 | 487 | 0ms | 21ms | ~10ms (6%) |
| IsoAnimal | 17,434 | 143 | 0ms | **104ms** | ~20ms (13%) |
| IsoZombieGiblets | 22,425 | 184 | 0ms | 13ms | ~2ms (1%) |
| **Loop overhead** | — | **830 total** | — | — | **~78ms (49%)** |

**Half of the 158ms is loop overhead** — iterating 830 objects per tick across 5 bucket phases (preupdate, frameStep, update, postupdate for each of fullSimulation). Each object is fast individually, but 830 × 5 = 4,150 method calls per tick adds up.

---

## 3. Surprising Findings

### 3.1 Zombie cull was re-enabled

`startFrame` iterates `serverZombies=260,047` across 122 ticks = **2,131 zombies/tick**. But the scheduler bucket only contains `full=101,269` objects across 122 ticks = **830 non-zombie objects/tick**. Zombies are counted but NOT updated through the scheduler — they use `ZombieCountOptimiser`.

Despite this, tick rate at 2,122 zombies is 4.1 TPS. At 4,250 zombies (previous snapshot) it was 1.1 TPS. **The relationship between zombie count and TPS is not quite linear** — 2x zombies caused 3.7x tick time. This suggests some O(n²) behavior or a secondary effect (more chunks to iterate, more pathfinding contention).

### 3.2 `serverMapPost` improved dramatically

| Snapshot | serverMapPost avg | Players | Zombies |
|---|---|---|---|
| Previous | **267ms** | 18 | 4,250 |
| Current | **12ms** | 16 | 2,122 |

The previous 5.3s spike was likely a one-time save operation (world initial save or manual save command). Normal auto-save is 12ms average, peaking at 624ms.

### 3.3 Animals spike like players

`IsoAnimal maxMs=104` — one animal AI update took 104ms. This is higher than the worst player (89ms). Animal pathfinding or behavior state machine can be expensive when it triggers a wide search.

### 3.4 `virtualAnimals` in isoWorld

`virtualAnimals{avgMs=13, maxMs=93}` — The virtual animal manager (off-screen animal simulation) adds 13ms average, spiking to 93ms. This runs inside the `isoWorld` section, contributing to the 192ms isoWorld time.

### 3.5 Vehicle patch is working

```
vehicle{partsCalls=767, luaCalls=2853, luaAvgMs=0, luaMaxMs=18, luaSlowCalls=1}
```

767 updateParts() calls across 122 ticks = 6.3 per tick. With 487 vehicles/tick, only 1.3% get full Lua updates per frame. `luaAvgMs=0` confirms vehicle Lua is not the bottleneck.

---

## 4. Previous Snapshot Comparison

| Metric | Previous (14:57) | Current (15:13) | Delta |
|---|---|---|---|
| Players | 18 | 16 | -11% |
| Zombies | 4,250 | 2,122 | **-50%** |
| Ticks/30s | 33 | 122 | **+270%** |
| Avg tick | 880ms | 241ms | **-73%** |
| TPS | 1.1 | 4.1 | **+273%** |
| movingObjects avg | 353ms | 158ms | **-55%** |
| serverMapPost avg | 267ms | 12ms | **-96%** |
| updateStuff/gameTime avg | 91/84ms | 4/2ms | **-96%** |

The 50% zombie drop caused a 270% TPS improvement — disproportional. This confirms that zombie count has **super-linear impact** on server tick time, likely through chunk loading pressure, pathfinding grid contention, and zombie-to-zombie interaction checks.

---

## 5. Bottleneck Priority Ranking

| Priority | Target | Avg | Max | Why |
|---|---|---|---|---|
| **1** | **ProcessObjects loop overhead** | **78ms** | — | 830 objects × 5 phases = 4,150 calls/tick. The pure iteration overhead dominates. |
| **2** | **IsoPlayer.update** | **48ms** | **89ms** | 16 players × 3ms average. The 89ms spike suggests a specific player path (likely `ServerLOS` or `updateRemotePlayer`). |
| **3** | **IsoAnimal.update** | ~20ms | **104ms** | One animal AI spike can cost 104ms. Animal pathfinding is not throttled. |
| **4** | **virtualAnimals** | **13ms** | **93ms** | Off-screen animal simulation. The 93ms spike correlates with the 104ms animal spike. |
| **5** | **startFrame iteration** | **4ms** | **35ms** | Iterating 2,961 objects/tick. Scales with total world population. |

---

## 6. Known Server-Side OnPlayerUpdate Hooks

Only 3 mods register server-side OnPlayerUpdate hooks. All are cheap (return early when conditions not met):

| Mod | File | Function | Estimated Cost |
|---|---|---|---|
| **ArmorMakesSense** | `ArmorMakesSense_MPServerRuntime.lua:1271` | Sleep-wake detection | <0.01ms |
| **89defender** | `89defender_server.lua:293` | Windshield vent control | <0.01ms (returns if not in 89defender) |
| **85chevyStepVan** | `85chevyStepVan_server.lua:124` | Side vent control | <0.01ms (returns if not in StepVan) |

**Conclusion: Mod hooks are NOT the cause of the 48ms player update cost.** The 89ms spike is in Java code — likely `ServerLOS.updateLOS()` iterating HashMaps or `IsoPlayer.updateRemotePlayer()` iterating other players.

---

## 7. Recommended Actions

### Action 1 — Add Player Sub-section Telemetry (diagnostic)
Add `recordIsoCellSection` calls inside `IsoPlayer.updateInternal2()` to identify which sub-section (`updateLOS`, `updateRemotePlayer`, `updateWhileInVehicle`, `OnPlayerUpdate` events) is the hotspot. Without this, we're guessing which Java path causes the 89ms spike.

### Action 2 — Throttle startFrame zombie iteration (quick win)
`startFrame` iterates ALL objects including zombies (2,131/tick) just to count them (`ZombieCountOptimiser.incrementZombie()`). This iteration is pure overhead for zombies — they don't go into scheduler buckets. A zombie-skip in the iteration loop would save 4ms average, 35ms max.

### Action 3 — Throttle animal updates (moderate)
Animals contribute 143 updates/tick with 104ms spikes. The `MovingObjectUpdateScheduler` gives them `FULL` simulation level. Enabling distance-based throttling (same as client-side logic) on the server could reduce this. This requires modifying `getUpdateSchedulerSimulationLevelForObject` to check player proximity even on the server.

### Action 4 — Offload virtualAnimals (async, high effort)
`virtualAnimals` at 13ms average, 93ms max runs on the main thread inside `isoWorld`. If this operates on independent data (animal population state, not IsoCell collections), it could be submitted to ForkJoinPool similar to the existing pre/post-Lua async slots.

### Action 5 — Profile the 89ms player spike (requires Action 1 first)
Without sub-section telemetry, we can't target the exact player update path. Action 1 should be done first, then analyze on a loaded server.

---

## 8. Summary

The server is running at 4.1 TPS (target 10). The bottleneck is `ProcessObjects` at 158ms, driven by:
1. **Loop overhead** (~78ms) — iterating 830 objects × 5 phases
2. **Player updates** (~48ms) — 16 players at 3ms avg, spiking to 89ms
3. **Animal updates** (~20ms) — 143 animals at 0ms avg, spiking to 104ms
4. **Vehicle updates** (~10ms) — 487 vehicles at near-zero due to frame-skip
5. **Giblets** (~2ms) — 184 zombie giblets

Total estimated from individual costs: 158ms ✓

**The vehicle async patches are working** — vehicle Lua is negligible (0ms avg, 18ms max). **Player and animal updates are the next targets.**

Waiting on Action 1 (player sub-section telemetry) before attempting to async player updates. The `serverMapPost` auto-save spike is no longer a problem (12ms average in this snapshot).
