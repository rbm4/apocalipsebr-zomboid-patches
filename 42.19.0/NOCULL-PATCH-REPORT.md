# patch-zombie-nocull.ps1 — Technical Report

## What the patch does

`patch-zombie-nocull.ps1` removes the **server-side zombie culling** system that was added in **Build 42.19** of Project Zomboid.

---

## Background: The Cull System (Build 42.19)

### `MovingObjectUpdateScheduler.postupdate()`

The decompiled source (`zombie/MovingObjectUpdateScheduler.java`) shows that **Build 42.19** added the following block at the start of `postupdate()`:

```java
public void postupdate() {
    if (GameServer.server) {
        ZombieCountOptimiser.deleteZombies();  // <-- ADDED IN 42.19
    }
    // ... bucket updates ...
}
```

In **Build 42.18**, this call did not exist server-side. `ZombieCountOptimiser` was only invoked client-side.

---

### `ZombieCountOptimiser` — The Cull Pipeline

The cull runs in two phases, both triggered from `MovingObjectUpdateScheduler`:

#### Phase 1 — `startFrame()` (still active after the patch)

```java
// Called once per frame on the server:
ZombieCountOptimiser.startCount();

// Called for every zombie in the cell's object list:
ZombieCountOptimiser.incrementZombie(isoZombie);
```

**`startCount()`** computes the *excess* zombie count above a configurable threshold:

```java
public static void startCount() {
    zombieCountForDelete = (int)(
        1.0F * Math.max(0, IsoWorld.instance.getCell().getZombieList().size()
                         - SandboxOptions.instance.zombieConfig.zombiesCountBeforeDeletion.getValue())
    );
}
```

- The sandbox option `ZombieConfig.ZombiesCountBeforeDelete` defaults to **300** (range: 10–500).
- If the live zombie count exceeds this threshold, `zombieCountForDelete` = `liveCount - threshold`.

**`incrementZombie()`** marks individual zombies as deletion candidates:

```java
public static void incrementZombie(IsoZombie zombie) {
    if (zombieCountForDelete > 0
        && Rand.Next(Rand.AdjustForFramerate(10)) == 0   // ~10% chance per frame
        && !zombie.isReanimatedPlayer()
        && zombie.getTarget() == null                    // not chasing anyone
        && isOutside(zombie)                             // not inside a room
        && canBeDeletedUnnoticed(zombie)) {              // all players are far away
        zombiesForDelete.add(zombie);
        GameStatistic.getInstance().zombiesCulled.increase();
    }
}
```

`canBeDeletedUnnoticed()` checks that every connected player's distance to the zombie is greater than `(relevantRange - 2) * 10 / 2` tiles — i.e., the zombie is outside the visible range of all players.

#### Phase 2 — `postupdate()` (the patched call)

```java
public static void deleteZombies() {
    if (!zombiesForDelete.isEmpty()) {
        for (IsoZombie zombieForDelete : zombiesForDelete) {
            NetworkZombiePacker.getInstance().deleteZombie(zombieForDelete);
            zombieForDelete.removeFromWorld();
            zombieForDelete.removeFromSquare();
        }
        zombiesForDelete.clear();
    }
}
```

This is where marked zombies are actually **removed from the world**. The call to `NetworkZombiePacker.deleteZombie()` also broadcasts the removal to all clients.

---

## What the patch changes

The patch replaces the 9-byte bytecode sequence in `MovingObjectUpdateScheduler.class` that implements the `if (GameServer.server) { ZombieCountOptimiser.deleteZombies(); }` block:

| Offset | Bytes | Instruction |
|--------|-------|-------------|
| +0 | `B2 00 2F` | `getstatic` `GameServer.server` (push boolean field) |
| +3 | `99 00 06` | `ifeq +9` (if not server, skip) |
| +6 | `B8 00 D7` | `invokestatic` `ZombieCountOptimiser.deleteZombies()` |

All 9 bytes are replaced with **NOPs** (`00 x 9`):

```
B2 00 2F 99 00 06 B8 00 D7
->
00 00 00 00 00 00 00 00 00
```

The class file size is **unchanged**. The rest of `postupdate()` (bucket updates for `fullSimulation`, `halfSimulation`, etc.) is unaffected.

---

## Effect on the server

| Behaviour | Before patch (42.19) | After patch |
|-----------|---------------------|-------------|
| Zombie deletion call | Every frame (`postupdate`) | Never |
| Zombie population cap | ~300 (default `ZombiesCountBeforeDelete`) | Uncapped by this mechanism |
| `zombiesForDelete` list accumulation | Cleared every frame via `deleteZombies()` | Never cleared — **grows if count > threshold** |
| `startCount()` / `incrementZombie()` | Still runs every frame | Still runs every frame (unchanged) |

> **Note on `zombiesForDelete` accumulation:** Because `zombiesForDelete.clear()` is only called inside `deleteZombies()`, and that call is patched out, the static list will accumulate entries whenever the zombie count is above the threshold. If the server stays well below the default threshold of 300, `zombieCountForDelete` remains 0 and `incrementZombie()` adds nothing to the list — no accumulation occurs. Servers that consistently run above 300 zombies should monitor memory usage.

---

## Why this patch exists

In 42.19 the cull was disproportionately aggressive on populated servers with many connected players. Each player connection extends the area that is "noticed" by the relevance-range check, but `startCount()` calculates a single global excess value. On servers running 20+ concurrent players with 5000+ active zombies, the cull was observed reducing zombie populations to ~400 within minutes of server start.

Build 42.18 did not have this server-side cull, so reverting it restores the pre-42.19 population dynamics.

---

## Files affected

- **Jar entry:** `zombie/MovingObjectUpdateScheduler.class`  
- **Method:** `postupdate()`  
- **Bytecode offset:** variable (located at runtime by pattern scan)  
- **Patch type:** same-size binary in-place (9 bytes → 9 NOPs)
