Bug: server main thread hangs forever when a short-typed ObjectID space (Player/Zombie/DeadBody/Vehicle) runs out of free ids

Build: 42.20.0 (dedicated server)
Component: zombie.network.id.ObjectIDManager / zombie.network.id.ObjectIDType

## Summary

`ObjectIDManager.addObject()` allocates a new id for `Player`, `Zombie`, `DeadBody` and `Vehicle`
objects by truncating an ever-incrementing counter to a Java `short`
(`ObjectIDType.java:10-16`, `ObjectID.ObjectIDShort`), giving each of these types a hard cap of
65536 distinct concurrently-registered ids. The allocation loop has no bound and no exhaustion
check:

```java
id = (short)type.allocateID();

while (type.idToObjectMap.get(id) != null) {
    id = (short)type.allocateID();
}
```

If every one of the 65536 possible values for a type is currently occupied, this loop probes
forever with no way out. On a long-running server we hit this for `DeadBody`: the main thread
hung permanently inside `ObjectIDManager.addObject()`, called from `IsoDeadBody`'s constructor
while a randomized building story (`RandomizedWorldBase.createRandomDeadBody`, in our case
triggered by a bandit-raid room, `RDSBanditRaid`) tried to spawn a corpse during chunk load.

CPU usage stayed effectively pegged on the main thread indefinitely; the server stopped
processing ticks, accepting connections, or responding to RCON. Only a hard restart recovered
it.

## Root cause

1. `DeadBody` uses `ObjectID.ObjectIDShort` (`ObjectIDType.java:15`), a 16-bit wire field, so at
   most 65536 distinct `DeadBody` ids can exist at once in `ObjectIDType.idToObjectMap`.
2. Corpse cleanup is driven almost entirely by `IsoDeadBody.updateBodies()`
   (`IsoDeadBody.java:1582-1585`), which is a full no-op whenever the sandbox option
   `hoursForCorpseRemoval` is `0` ("corpses never disappear"):
   ```java
   float hoursForCorpseRemoval = (float)SandboxOptions.instance.hoursForCorpseRemoval.getValue();
   if (!(hoursForCorpseRemoval <= 0.0F)) {
       ...
   }
   ```
   With that option set to `0`, the only way a `DeadBody` ever gets deregistered from
   `ObjectIDManager` is `IsoDeadBody.removeFromWorld()` firing on chunk unload
   (`IsoChunk.java:3237-3260`). Any corpse sitting in a chunk that stays resident (busy
   base, spawn area, frequently revisited building, or simply a very long uptime with
   continuous zombie/PvP/bandit-raid deaths) never gets deregistered.
3. Once the number of concurrently-loaded, never-decaying corpses approaches 65536, the
   probability that `allocateID()`'s truncated value collides with an already-occupied slot
   approaches 100%, and the retry loop degrades into an effectively infinite spin the moment
   the space is fully saturated.

## Steps to reproduce

1. Start a dedicated server with sandbox option `Corpses > Time before corpses disappear`
   (`hoursForCorpseRemoval`) set to `0` ("Never").
2. Let the server run for an extended period with regular zombie kills and/or randomized
   building stories that create corpses (bandit raids, survivor stories, etc.), without ever
   restarting or otherwise clearing the world.
3. Keep enough of the map permanently loaded (players spread across a large base, or players
   simply staying online for a long time) that corpses in those chunks are never unloaded and
   therefore never deregistered from `ObjectIDManager`.
4. Once the number of live, registered `DeadBody` instances approaches 65536, any further
   corpse creation (organic death, randomized building story, etc.) has an increasing chance of
   landing in `ObjectIDManager.addObject()`'s retry loop with no free slot left, hanging the
   main thread forever.

We were able to correlate this with a thread dump showing the main thread stuck inside
`ObjectIDManager.addObject()`, called from `IsoDeadBody`'s constructor via
`RandomizedWorldBase.createRandomDeadBody()` during chunk load.

## Our fix (server-side patch, no network/save format changes)

We patched `ObjectIDManager` and `ObjectIDType` with two independent, minimal changes:

1. **O(1) id allocation via an explicit free-list instead of blind probing.**
   `ObjectIDType` now carries a `Deque<Long> freeIds`. `ObjectIDManager.remove()` pushes a
   released id onto that queue instead of just forgetting it. `addObject()`'s allocator
   (`nextFreeId()`) then works as follows:
   - While the type has never issued every value in its id space at least once
     (`lastId < 65536`), a fresh `(short)allocateID()` is guaranteed collision-free by
     construction, so no lookup is needed at all.
   - Once the space has wrapped, every allocation pops from `freeIds` in O(1) instead of
     probing `idToObjectMap.get(id)` in a loop.
   This removes the unbounded-probing behavior entirely; the number of `idToObjectMap` lookups
   per allocation goes from "grows without bound as occupancy approaches 100%" to "zero or one,
   always."
2. **A bounded, non-hanging fallback for genuine exhaustion.** If `freeIds` is empty and the id
   space has already wrapped (i.e. truly saturated), we force-remove one live object of that
   type from the world via its own `removeFromWorld()` override (which also releases its id
   back into `freeIds`), reclaim the freed id, and continue. If that ever fails, we log an error
   and drop the new object instead of hanging the main thread. This is a last-resort safety net;
   in practice it should rarely if ever trigger once the free-list is in place, since ids are
   reused continuously instead of only ever incrementing forward.

Neither change touches `ObjectID.save()`/`load()` or `getPacketSizeBytes()` - the wire format and
world-save format for `DeadBody` (and every other type) are byte-for-byte identical to vanilla.
`freeIds` is purely an in-memory runtime bookkeeping structure.

We are also recommending server operators avoid `hoursForCorpseRemoval = 0` on long-running
servers as a mitigation independent of this code fix, since it's the practical trigger that lets
the id space get anywhere near saturation in the first place.

## Why we didn't just change `DeadBody` to `ObjectID.ObjectIDInteger`

The obvious-looking fix is to give `DeadBody` (and the other short-typed entries) a 32-bit id
space instead of 16-bit, the same way `Item`/`Container` already use `ObjectIDInteger`
(`ObjectIDType.java:13-14`). We deliberately did not do this, for two compatibility reasons that
apply to any server-side-only patch (i.e. one that does not also require a modified client):

1. **`ObjectID.save()`/`load()` is a shared network wire format, not just internal server
   state.** `IsoDeadBody.saveChange()`/`loadChange()` for `IsoObjectChange.OBJECT_ID`
   (`IsoDeadBody.java:1049-1050`, `1075-1081`) is invoked through
   `IsoObject.sendObjectChange()` -> `GameServer.sendObjectChange()`
   (`IsoObject.java:4816-4818`), which broadcasts the exact same byte stream to every connected
   client. `ObjectIDShort.save()` writes 2 bytes; `ObjectIDInteger.save()` writes 4. A
   server-only change from `ObjectIDShort` to `ObjectIDInteger` for `DeadBody` would make the
   server write 4 bytes where an unmodified vanilla client's `ObjectIDShort.load()` still expects
   2, desynchronizing the packet stream on the very next `OBJECT_ID` change and corrupting
   everything read after it client-side (client crash/kick, not a cleanly recoverable error).
   Doing this safely would require shipping the same change to every connecting client, which is
   outside the scope of what a dedicated-server-only patch can guarantee.
2. **The same `ObjectID.save()`/`load()` pair is also used for on-disk world persistence** (the
   change-stream / hot-save mechanism that `IsoDeadBody.saveChange()`/`loadChange()`
   participates in). Any world save that already contains corpses serialized with the 2-byte
   format would misparse after a format change, since the reader would suddenly expect 4 bytes
   where 2 were actually written. This would require either a hard save-format version bump
   (breaking or migrating every existing save with surviving corpses) or a save-version-gated
   reader, which is a much larger, riskier change than the actual bug warrants.

Given those two constraints, we opted for the free-list allocator plus a bounded exhaustion
fallback described above: it fixes both the immediate hang and the underlying "probing gets
slower as it fills up" cost, entirely on the server side, without touching anything that has to
match between server and client or between old and new save data.

## Suggested official fix

If the devs want to fix this upstream with the ability to also patch the client, the cleanest
long-term fix is probably to widen `DeadBody`'s id type to `ObjectIDInteger` the same way
`Item`/`Container` already work, since 65536 concurrently-tracked corpses is a realistic ceiling
on long-running, heavily populated servers (especially with `hoursForCorpseRemoval = 0`), while
also adding a bound/guard to `ObjectIDManager.addObject()`'s allocation loop regardless, since an
unbounded retry loop with no exhaustion check is a latent hang for any of the short-typed
`ObjectIDType` values, not just `DeadBody`.

## Files referenced

- `zombie/network/id/ObjectIDType.java`
- `zombie/network/id/ObjectIDManager.java`
- `zombie/network/id/ObjectID.java`
- `zombie/iso/objects/IsoDeadBody.java`
- `zombie/iso/IsoChunk.java`
- `zombie/iso/IsoObject.java`
- `zombie/network/GameServer.java`
- `zombie/randomizedWorld/RandomizedWorldBase.java`
