# Zombie Network Optimization Implementation Notes

## Scope

Implemented and validated the first pass of the 42.20.1 zombie server optimization work described in `zed-optimizations.md`.

The implemented changes target the server-side network cost around real zombies:

- `NetworkZombieManager.updateAuth()`
- `NetworkZombiePacker.getZombieData()`
- `NetworkZombiePacker.setExtraUpdate()`
- `ZombieGroupManager`
- telemetry around `IsoZombie.update()` and `ZombiePopulationManager.updateMain()`

No parallelization was added to `NetworkZombiePacker` or `NetworkZombieManager` in this pass. The chosen approach was to reduce the amount of work before considering thread-level parallelism.

## Implemented Changes

### NetworkZombieManager auth grid

Vanilla ownership arbitration scans every connection and every player for zombies whose ownership is missing or expired. This was replaced with a per-tick spatial auth grid:

- Build once per auth update from fully connected, non-delayed connections.
- Add alive players as auth candidates into all grid cells covered by their vanilla ownership radius.
- Query candidates by zombie grid cell instead of scanning every connection/player.
- Preserve the vanilla ownership decision after candidate lookup:
  - `IsoPlayer.getRelevantAndDistance(...)`
  - `distance > d * 1.618034F`
  - final `connection.RelevantTo(...)` guard

Important correction found during telemetry testing: ownership candidate radius must match `IsoPlayer.getRelevantAndDistance()`, which uses `relevantRange * 8`, not `relevantRange * 10`. The final `connection.RelevantTo(...)` guard still uses `(connection.getRelevantRange() - 2) * 10`, as vanilla did.

Temporary diagnostic fallback/repair paths were tested and then removed:

- Full-scan auth fallback
- Connection-area fallback
- Owner/list repair scan
- Rejection-distance telemetry

These were useful for proving that a no-zombie local test save was a sandbox/mod/population issue, not an auth regression, but they are not part of the final optimization shape.

### NetworkZombiePacker relay grid

Vanilla relay fan-out scans all `zombiesProcessing` for every connection. This was replaced with a per-tick grid of active processed zombies:

- Build `zombiesProcessingByCell` once after `zombiesReceived` is copied to `zombiesProcessing`.
- For each connection, collect nearby relay candidates from player relevance and `connection.connectArea[]`.
- Keep the vanilla final relay guard:
  - owner exists
  - owner is not the receiving connection
  - `connection.RelevantTo(...)`
  - valid `onlineId`

This keeps the behavior conservative while avoiding the global `connections * zombiesProcessing` scan.

### O(1) extra update mark

`NetworkZombiePacker.setExtraUpdate()` no longer loops over all connections. It now sets a single `extraUpdateAll` flag and each fully connected connection observes it during `send()`.

This avoids an O(connections) mutation every time ownership changes.

### ZombieGroupManager group grid

Group lookup and rally separation were changed from broad group scans to a simple spatial group grid:

- `rebuildGroupGrid()` indexes existing groups.
- `findNearestGroup()` queries nearby cells.
- Rally leader separation uses nearby groups only.

Telemetry currently showed `avgGroups:0.0` in the tested server snapshots, so this path has not been meaningfully stressed yet.

## Telemetry Added

### zombieNet.auth

Tracks auth grid behavior:

- `gridBuilds`
- `avgCells`
- `avgCandidates`
- `avgCellWrites`
- `avgBuildMs`
- `maxBuildMs`
- `queries`
- `avgQueryCandidates`
- `moves`

The healthy live-server snapshot with 9 players, 15 connections, and 243 real zombies showed:

- `avgQueryCandidates:1.01`
- `avgBuildMs:0.02`
- `moves:24`

This indicates the auth grid is reducing the old full-scan behavior to a small nearby-candidate set.

### zombieNet.relay

Tracks relay grid behavior:

- `gridBuilds`
- `avgActive`
- `avgCells`
- `avgBuildMs`
- `maxBuildMs`
- `queries`
- `avgCellsVisited`
- `avgCandidates`
- `initialSent`
- `sent`
- `packets`
- `extraAllMarks`
- `extraAllPackets`

The same live-server snapshot showed:

- `avgCandidates:3.89`
- `initialSent:744`
- `sent:3600`
- `packets:613`

This shows relay fan-out is active while candidate counts remain small.

### zombiePop

Added a telemetry-only override of `ZombiePopulationManager` to distinguish population/materialization issues from network/auth issues:

- `updates`
- `nativeRequested`
- `batches`
- `recordsRead`
- `skippedNewIndoor`
- `edgeForcedStanding`
- `standing`
- `moving`
- `avgMs`
- `maxMs`

This proved that one local self-hosted save with no visible zombies had `nativeRequested:0`, meaning native population was not asking Java to materialize zombies. A fresh no-mod test save produced expected values such as `nativeRequested:90`, `standing:90`.

### zombieUpdate

`IsoZombie.update()` is instrumented on the server path only:

- `serverCalls`
- `owned`
- `target`
- `remote`
- `avgMs`
- `maxMs`

All tested client-authoritative server snapshots showed `serverCalls:0`, which supports the conclusion that ordinary network-owned zombies are not being simulated through server `IsoZombie.update()` in these tests.

## Validation Summary

Representative healthy live-server telemetry:

- `players:9`
- `connections:15`
- `zombies:243`
- `zombieNet.auth.avgQueryCandidates:1.01`
- `zombieNet.auth.avgBuildMs:0.02`
- `zombieNet.relay.avgCandidates:3.89`
- `zombieNet.relay.initialSent:744`
- `zombieNet.relay.sent:3600`
- `zombieUpdate.serverCalls:0`

Conclusion: the zombie network optimization is behaving as intended. The main pressure in that snapshot was not zombie auth/relay/population, but LOS:

- `los.busyMax == los.slots`
- `los.starved:1`
- `los.avgMs:24.16`
- `los.maxMs:80.33`

## Current Recommendation

Keep the current zombie network optimization shape:

- Spatial auth grid with `relevantRange * 8`
- Relay grid for `zombiesProcessing`
- O(1) `extraUpdateAll`
- Group grid
- `zombiePop` telemetry for continued validation

Do not add `NetworkZombiePacker`/`NetworkZombieManager` parallelism yet. The telemetry suggests the algorithmic reduction is already effective, and the next hotter area is likely `ServerLOS` saturation and/or deferred unload pressure.

