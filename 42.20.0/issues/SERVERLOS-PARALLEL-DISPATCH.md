Change: parallelize server-side LOS (line-of-sight) calculation across worker threads, decoupled
from the vanilla local co-op splitscreen slot limit, with telemetry.

Build: 42.20.0 (dedicated server)
Component: zombie.network.ServerLOS / zombie.iso.IsoGridSquare / zombie.iso.LosUtil /
zombie.ApocBRServerTelemetry

Status: Implemented, not yet compiled/tested against the live PZ jar. Compile via
`patchApocalipseBr.ps1` and run on a populated dedicated server before shipping.

## Summary

Server-side LOS (which grid squares a player can currently see, used for zombie
awareness/spawning, room "explored" state, and general fog-of-war-style visibility) used to be
computed entirely on one dedicated thread, one player at a time, and was hard-capped at 4
concurrent "slots" - a limit inherited from the client's local co-op splitscreen mode (up to 4
local players sharing one screen), which has nothing to do with dedicated server capacity.

This change:

1. Replaces the single-threaded LOS worker with a dispatcher that hands work off to the game's
   existing `PZForkJoinPool`, so multiple players' LOS can be computed concurrently.
2. Removes the hardcoded `4` from the two data structures that actually gate LOS concurrency
   (`IsoGridSquare.lighting[]`, `LosUtil.cachedresults[]`/`cachecleared[]`), resizing them (on the
   server side only) to match the fork-join pool's real parallelism instead of the vanilla
   co-op limit.
3. Fixes a `checkRoomSeen`/`CalcVisibility` correctness issue that only mattered once LOS became
   concurrent (shared static scratch buffers, a hardcoded reliance on `IsoPlayer.players[0]`).
4. Adds lock-free telemetry so LOS health can be observed on a live server without adding CPU
   overhead on the hot path.

## Root cause / why this mattered

`ServerLOS`'s original design (`42.20.0/decompiled/zombie/network/ServerLOS.java`) ran a single
dedicated `Thread` (`LOSThread`) that, each pass, synchronously computed LOS for every player
currently in `WaitingInLOS` status, one at a time, before going back to sleep. On a populated
dedicated server (~70 players), each player waits for every other pending player's LOS calc to
finish before their own turn arrives, even though the calc itself (a full 96x96xZ visibility
raycast per player) is pure CPU-bound work with no cross-player dependency.

`IsoGridSquare.lighting` (`42.20.0/decompiled/zombie/iso/IsoGridSquare.java:221`) and
`LosUtil.cachedresults`/`cachecleared` (`42.20.0/decompiled/zombie/iso/LosUtil.java:13-14`) are
both fixed-size arrays of `4`. This size was never a technical requirement for the server LOS
path specifically - it is the number of local players a splitscreen client can have. The
single-LOS-thread design never needed more than 1 slot in practice (it always wrote through
`lighting[0]`/`cachedresults[0]`, ignoring the other 3 - see
`42.20.0/decompiled/zombie/network/ServerLOS.java:228-230`,
`IsoPlayer.players[0] = data.player;`).

## Changes

### `zombie.network.ServerLOS`

- `LOSThread` renamed to `LOSDispatcher`. It no longer computes LOS itself - each pass it scans
  `playersMain` for `WaitingInLOS` players, claims a free slot index from a
  `ConcurrentLinkedQueue<Integer> freeSlots` (bounded to `LOS_SLOT_COUNT`), and dispatches the
  actual work via `CompletableFuture.runAsync(() -> calcLOS(data, slot), PZForkJoinPool.commonPool())`.
- `calcLOS(PlayerData, int slotIndex)` is now a plain instance method (moved out of the thread
  class) parameterized by slot index instead of hardcoding `0`/`IsoPlayer.players[0]`. It no
  longer touches `IsoPlayer.players[]` at all - `IsoGridSquare.checkRoomSeen(IsoPlayer)` (new
  overload, see below) takes the player directly. Returns `boolean skip` (whether the player
  hadn't moved grid cell since last calc) for telemetry.
- `LOS_SLOT_COUNT` changed from `4` to `Math.max(4, PZForkJoinPool.commonPool().getParallelism())`
  - must stay in sync with `IsoGridSquare.SERVER_LOS_SLOT_COUNT` and `LosUtil.SLOT_COUNT` (same
    formula, same underlying pool singleton, so deterministically equal - not a shared constant
    to avoid a new cross-package dependency, but verified equivalent).
- `PlayerData.status` marked `volatile` - now read/written across the main thread, the dispatcher
  thread, and pool worker threads (previously only ever touched by the main thread and one
  dedicated LOS thread).
- **Suspend/resume safety fix**: `suspend()` (called by `ServerMap.postupdate()` before unloading
  cells - see `42.20.0/decompiled/zombie/network/ServerMap.java:557`) polls `ServerLOS.suspended`
  and assumes `true` means "no thread is currently reading grid squares", so it's safe to
  mutate/unload them. With `dispatch()` being fire-and-forget onto the pool, "no new work to
  dispatch" no longer implies "no in-flight work" - so `suspended` is now only ever set `true`
  once `freeSlots.size() == LOS_SLOT_COUNT` (every dispatched task has returned its slot), not
  merely when the dispatcher stops finding new players to dispatch. The dispatcher still always
  blocks on `notifier.wait()` while `mapLoading` is true (same as before), woken by either a task
  completing (`freeSlots.add()` + `notify()`) or `resume()` - so this fix does not introduce a
  busy-spin.

### `zombie.iso.IsoGridSquare`

- `tempo`/`tempo2` (`CalcVisibility()`'s scratch `Vector2` buffers) changed from shared `static
  final Vector2` fields to `ThreadLocal<Vector2>` (`tempoTL`/`tempo2TL`). They were written then
  consumed within a single call with nothing persisting across calls, which was safe when only
  one thread ever called `CalcVisibility()`. Concurrent LOS workers would otherwise clobber each
  other's in-flight values.
- `checkRoomSeen(int playerIndex)` kept as a thin wrapper; added `checkRoomSeen(IsoPlayer player)`
  overload that takes the player directly instead of resolving it through the shared
  `IsoPlayer.players[]` slot array, which `ServerLOS` no longer populates for its own worker
  threads.
- `lighting` field: `new IsoGridSquare.ILighting[4]` → `new
  IsoGridSquare.ILighting[GameServer.server ? SERVER_LOS_SLOT_COUNT : 4]`. Only server-side
  squares get the larger array; client squares (local co-op, up to 4 players) keep the original
  size to avoid allocating unused `Lighting`/`JNILighting` objects per square.
  `SERVER_LOS_SLOT_COUNT = Math.max(4, PZForkJoinPool.commonPool().getParallelism())`.
- The per-square `lighting[]` allocation loop and the `lighting[]`/`lightInfo[]` reset loop
  (inside the square reset/reuse path) now iterate `this.lighting.length` instead of a hardcoded
  `4`, so every slot actually gets initialized/reset regardless of array size. `lightInfo[]`
  (client rendering cache, unrelated to server LOS, stays fixed at size 4) is bounds-checked
  inside that same loop to avoid an `ArrayIndexOutOfBoundsException` when `lighting.length > 4`.
- Server `lighting[]` allocation (`ResetSt()`-adjacent init) previously only ever constructed a
  `ServerLOS.ServerLighting` for slot `0`; now constructs one for every slot, since every slot is
  now potentially in use.

Audited and deliberately left untouched (confirmed unrelated to LOS, would only add risk):
`lightInfo[]`, `playerCutawayFlags[]`, `playerIsDissolvedFlags[]`/`targetPlayerIsDissolvedFlags[]`
etc. (all still fixed at `4`, client rendering only), and the `isSeen`/`setIsSeen` byte-bitmask
packing (`vis |= 1 << i`, `IsoGridSquare.java` ~3041-3047/3425-3430) which is explicitly guarded
by `!GameClient.client && !GameServer.server` and therefore never executes when `GameServer.server`
is true.

### `zombie.iso.LosUtil`

- `cachedresults`/`cachecleared` resized from `new LosUtil.PerPlayerData[4]`/`new boolean[4]` to
  `SLOT_COUNT = Math.max(4, PZForkJoinPool.commonPool().getParallelism())`. The static
  initializer loop that populates them was updated to match. Cheap on the client since
  `PerPlayerData`'s actual `byte[][][]` payload is allocated lazily (`checkSize()`), so the extra
  slots only cost a handful of unused wrapper objects there.

### `zombie.ApocBRServerTelemetry`

- New `"los"` section in the periodic JSON telemetry log: `slots` (resolved
  `ServerLOS.LOS_SLOT_COUNT`), `busyMax` (peak concurrently-occupied slots this interval),
  `calcs`/`skipped`/`starved` counts, `avgMs`/`maxMs` per real (non-skipped) calc.
- Because these counters are written concurrently from multiple `PZForkJoinPool` worker threads
  (unlike every other section in this class, which is written from a single thread and uses
  `synchronized`), they use `LongAdder`/`AtomicInteger`/`AtomicLong` instead, to avoid contending
  a shared lock on the LOS hot path.
- `starved` is the key signal to watch post-deploy: it counts `WaitingInLOS` players that found
  no free slot on a given dispatcher pass. Sustained non-zero values mean `LOS_SLOT_COUNT` (i.e.
  the fork-join pool's real parallelism / core count) is the current bottleneck, as opposed to an
  artificial cap.

New/changed call sites in `ServerLOS`:
- `start()` → `ApocBRServerTelemetry.recordServerLosSlotCount(LOS_SLOT_COUNT)` (once).
- `LOSDispatcher.runInner()` → `recordServerLosDispatch()` on successful slot claim,
  `recordServerLosStarved()` when no free slot was available.
- `LOSDispatcher.dispatch()` → wraps `calcLOS()` with `System.nanoTime()` and calls
  `recordServerLosCalc(skipped, nanos)` in the existing `finally` block.

## Why the slot count is tied to `PZForkJoinPool.commonPool().getParallelism()` and not player count

LOS calc is pure CPU-bound computation (nested-loop visibility raycasting over up to 96x96xZ
squares per player), not I/O-bound. `PZForkJoinPool.commonPool()`
(`42.20.0/decompiled/zombie/core/PZForkJoinPool.java`) already sizes itself to
`Runtime.getRuntime().availableProcessors() - 1`, which is the real hardware ceiling for how many
of these calcs can execute truly in parallel at any instant, regardless of how many player slots
exist. Sizing `LOS_SLOT_COUNT` above that value would let more players be marked "in flight"
simultaneously without any additional real throughput - the excess would just queue behind the
pool's own worker threads. Tying it to `getParallelism()` (with a floor of 4, so a low-core-count
box never regresses below the vanilla behavior) gives the actual achievable ceiling instead of an
arbitrary number.

Virtual threads (JDK 21+ `Thread.ofVirtual()`) were considered and rejected for this specific
workload: they help when threads spend time blocked (I/O, locks), not for CPU-bound work like
this, where oversubscribing beyond core count only adds context-switch overhead for no throughput
gain. Nothing in this change uses virtual threads; `PZForkJoinPool.commonPool()` is a classic
`java.util.concurrent.ForkJoinPool` with platform (OS) worker threads, one per unit of
`getParallelism()`.

## Known limitations / follow-ups

- Not yet compiled against the real PZ jar in this environment (no game install available in the
  dev sandbox). Must be verified via `patchApocalipseBr.ps1` + a real server run before shipping.
- `ServerLighting.lightInfo` remains a `private static final ColorInfo` shared across all
  `ServerLighting` instances (pre-existing, not touched by this change). Confirmed unused by the
  server LOS calc path (`calcLOS`/`CalcVisibility`/`checkRoomSeen`/`isCouldSee`); flagged here in
  case a future change starts calling `ILighting.lightInfo()` from a server code path, which would
  need its own fix at that point.
- `isCouldSee(IsoPlayer, IsoGridSquare)` reads `PlayerData.px/py/pz/visible` without checking
  `status` at all (pre-existing "best effort" read, not gated by `ReadyInLOS`). Not made worse by
  this change (each player's `PlayerData` is still only ever written by one worker at a time), but
  also not fixed - out of scope for this work item.
- Recommended follow-up: watch `"los".starved` and `"los".busyMax` for at least one full
  population cycle (peak concurrent players) after deploy to confirm the new ceiling is not
  itself becoming a bottleneck, and to get a real-world baseline for `avgMs`/`maxMs` per calc.
