# Lobby startup streaming bridge

Status: implemented 2026-11-04; compiled against installed game JAR; live server validation pending.

ExtractionMapUtils startup zombie cleanup needs to load the lobby without a
connected player. Vanilla Lua exposes neither ServerMap nor its relevance API.
`zombie.network.ApocBRLobbyStreaming.keepCellLoaded(int tileX, int tileY)` is
exposed through LuaManager only when GameServer.server is true.

Return -1 for invalid coordinates/non-server/outside-map, 0 while loading, 1
when ServerCell.isLoaded is true. Convert nonnegative tile coordinates to 64-tile
cells and subtract ServerMap.getMinX/Y before loadOrKeepRelevent. No blocking,
manual chunk insertion, direct unload, new threads or persisted loading leases.
The bridge runs only on the server thread through the mod's OnTickEvenPaused
callback. It is not an API for NPC/database/background workers.

The companion mod requests one cell per callback, activates population, performs
bounded cleanup and verification sweeps, then stops renewing it. Native postupdate
saves/unloads cells after relevance expires unless players/other requests keep
them active. Failure/cancellation also stops renewal. It never evicts active
player chunks. The native loader still controls disk I/O, integration, collision,
vehicles, population and save/reuse. No ServerMap replacement changes were needed.

The mod setting LobbyCleanupZombies remains default false and requires LobbyEnabled.
The mod logs startup/progress/completion/failure independently of debug logging.
Absent bridge fails visibly rather than silently reverting to natural chunk loads.
The original geometry-only implementation reported zero removals before zombies
appeared after login. The [population activation fix](../issues/2026-11-04-lobby-cleanup-zero-removals.md)
adds activatePopulation(tileX,tileY) and releasePopulation(). Patched LoadedAreas
appends one temporary 8x8-chunk population area to its non-serverCells list,
preserving real player/connection areas. A full area list fails explicitly rather
than evicting another area. Native server-cell areas remain unchanged.
Activation checks the cell is integrated, publishes loaded areas and, while paused,
calls ZombiePopulationManager.updateMain on the server thread. It returns native
readyToPause; Lua also requires two sweeps and five seconds without removals.
This consumes pending native work without advancing the game clock or directly
executing the native worker. Population requests have a 15-second renewal limit
and are explicitly released on completion/failure/cancellation. Respawns and future
arrivals after completion are not suppressed. Server login is not gated on cleanup.

Source evidence: exact 42.22.1 decompiled ServerMap.loadOrKeepRelevent, preupdate,
postupdate, ServerCell.Load2/RecalcAll2/Unload; IngameState.OnTickEvenPaused and
paused population updateMain; corresponding patched ServerMap relevance lookup.

Changed sources:

- src/zombie/network/ApocBRLobbyStreaming.java (new companion bridge).
- src/zombie/Lua/LuaManager.java (server-only class exposure).
- src/zombie/popman/LoadedAreas.java (exact 42.22.1 baseline plus server-only
  population-area hook and deployment compatibility marker).
- tools/test_lobby_streaming.py at repository root (production bridge with engine doubles).

Validation passed on 2026-11-04:

```powershell
py tools/test_lobby_streaming.py
# From 42.22.1:
.\patchApocalipseBr.ps1 -BuildOnly -ArtifactDir Z:/git/Github/apocalipsebr-zomboid-patches/42.22.1/artifacts/lobby-startup-cleanup-20261104
```

Initial fixture: 12 server-authority, map-origin, invalid bounds, loading/integration and
explicit-renewal assertions. Exact-JAR build: 309 Java sources, 910 class files,
javac 25.0.4, successful, retained in the artifact directory above. It includes
other pre-existing working-tree changes; no deployment or game install mutation.
The companion mod's tests/lobby-cleanup.lua also passes with mocked streaming,
removal, timing and client command boundaries.

Population fix validation on 2026-11-04: `py tools/test_lobby_streaming.py` compiles
the production bridge and LoadedAreas with engine doubles, passing 24 checks.
Coverage adds virtual activation with no players, paused-only consumer pumping,
native readiness, preservation of player areas/serverCells, area-capacity failure,
idempotent release, expiry and non-server behavior. Full `-BuildOnly` compilation
passes with 310 sources / 911 classes, retained at
`42.22.1/artifacts/lobby-population-fix-20261104`. No deployment performed.

Still required in-game: empty paused startup, natural population materialization,
chunk load failures, save/reload after cleanup, native unload timing, player
influence during cleanup and multiplayer zombie-owner synchronization.

Deployment requires updated LuaManager, ApocBRLobbyStreaming and LoadedAreas classes;
use the normal version patch script. This change has only been built, not deployed.
