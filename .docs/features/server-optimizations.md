# Server optimization ports

These modules run by default against the original Project Zomboid 42.22.2
JAR. Each is registered directly in `AgentModules`. Pass
`-D<property>=false` at JVM startup to disable a port and restore vanilla
execution at its hook sites.

| Port | JVM property | Vanilla entry point and scope |
| --- | --- | --- |
| Zombie culling quota | `apocbr.optimization.zombieCulling.enabled` | `ZombieCountOptimiser.prepareZombiesForDeletion()` skips candidate iteration at zero quota and leaves the loop as soon as the quota is filled. Random checks and selected-zombie order while quota remains follow vanilla. The scheduler still runs vanilla deletion at its existing point. |
| Lua stable dispatch | `apocbr.optimization.luaDispatch.enabled` | Both branches of `Event.trigger(KahluaTable, LuaCaller, Object[])` confirm the callback by identity at the current index. A moved, removed, or replaced callback falls back to the original membership scan. The public list, profiling, callback exception handling, and event timing use their vanilla or existing agent paths. |
| Remote-player near-vehicle skip | `apocbr.optimization.playerNearVehicle.enabled` | `IsoPlayer.updateInternal2()` skips the remote-branch query when the dedicated server updates a non-sneaking player. Sneaking and non-server callers still use the vanilla query, and the second call site in the local-player branch is unchanged. |
| Local zombie packet preparation | `apocbr.optimization.zombiePacket.enabled` | `NetworkZombieAI.set(ZombiePacket)`, its prediction helpers, and `ZombiePacket.set(IsoZombie)` use primitive flags, enum lookups without a fresh values array, and primitive forward-direction snapshots. The walk-toward prediction avoids square roots after crossing five units and still checks later deltas for NaN so the comparison agrees with vanilla. Both vanilla walk-type assignments, prediction bookkeeping, synchronous preparation, and serialization order are preserved. |
| Thermal clothing ownership | `apocbr.optimization.thermalClothing.enabled` | `Thermoregulator(BodyDamage)` creates its instance's clothing state, which `updateClothing()` uses for current visuals, cached visual identities, and covered-part scratch space. Equipment changes still trigger the vanilla rebuild logic. Thermal cadence and formulas follow vanilla. |
| Character stat lookup | `apocbr.optimization.characterStats.enabled` | `Stats.get(CharacterStat)` returns the stored float on a hit and uses the primitive default on a miss. The existing stats map, writes, clamping and serialized layout are preserved. |
| Region cell lookup | `apocbr.optimization.regionCellLookup.enabled` | `DataRoot.getCell(int)` reads a primitive index owned by that root. The original cell map still handles traversal and cleanup. Cell creation and whole-root reset update both indexes. |

The thermal module adds one private synthetic field when the JVM first defines
`Thermoregulator`. Its lifetime follows the regulator and avoids a global map
holding equipment references. The region cell lookup adds one private synthetic field to each `DataRoot`.
The remaining ports preserve game class shape.
The packet path helper reads the original behavior's private path, index, and
character fields through cached variable handles. Its shortcut is scoped to the
prediction comparison. The public path length method still computes full length.

## Telemetry

The schema-3 snapshot carries fixed counter names and nested phases. Counters
represent events in the reporting window. Phases report call count and total,
average, and maximum elapsed milliseconds. Absent values represent windows with
no recorded samples. The existing Lua event timing continues to include the
callback bodies.

| Counter | Recorded work |
| --- | --- |
| `zombies.culling.zeroQuotaConnections` | Connections where iteration was skipped because quota started at zero. |
| `zombies.culling.fulfilledQuotaConnections` | Connections whose iteration exited when it filled the quota. |
| `lua.dispatch.membershipScansSkipped` | Membership checks answered by same-slot identity in either profiling branch. |
| `players.nearVehicle.queriesSkipped` | Dedicated-server remote-branch queries skipped for non-sneaking players. |
| `zombies.packet.pathSquareRootsSkipped` | Prediction path segments whose square root was skipped after the running length crossed five. |
| `players.thermal.clothingRebuilt` | Dedicated-server clothing rebuilds entered. |

`simulation.players.nearVehicle` measures executed queries in the hooked
dedicated-server branch. `zombies.packet.prepare` measures the inclusive
dedicated-server `ZombiePacket.set` call. `simulation.players.thermalClothing`
measures the inclusive dedicated-server clothing update, including cache hits.

## Validation and rollout

The packaged fixture used Windows, Java 25, and original JAR SHA-256
`3C4A7C5F149D784758C6FB5F8FA85F3A21D572E38BC081400E89035F8120D287`.

```powershell
$env:JAVA_HOME = (Resolve-Path ./jdk).Path
mvn "-Djar.forceCreation=true" "-Dpz.game.jar=Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar" package
./tools/Test-OptimizationAgent.ps1
```

The script launches two isolated JVMs with the packaged production agent,
original game classes, and the existing test-only hook observer. It validates
the declared hooks with default settings and with its five original switches set to
`false`, including composition
with existing Lua event timing. The same fixtures execute the game methods in
both modes. The culling fixture reads zero versus two candidates for a
zero-quota connection, and one versus three after quota is filled. The thermal
fixture records one versus two rebuilds for unchanged clothing when another
regulator updates in between. Lua tests execute both dispatch branches and
mutate the live callback list. The remote-player fixture checks the skip,
continued execution, and propagation of the vanilla query's exception on
sneaking and client paths. The packet fixture checks the prediction threshold,
including NaN, and compares bytes for moving and stationary preparations.
It also checks optimization counter totals and snapshot resets.

The fixture leaves class-origin and hook evidence, hashes, and console logs
under `target/optimization-validation/run-*`. It does not launch
`GameServer.main`. Deployment, a connected-client session, and representative
live-server performance measurements remain pending. Toggle ports individually
when measuring production impact.

The [moving-object JVM profile](../validation/server-integration.md#character-stat-lookup-comparison) exercises the stat lookup with defaults, stored values, a native save/load round trip, and the live scheduler.
