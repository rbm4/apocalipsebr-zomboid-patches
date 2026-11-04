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

The companion mod requests one cell per callback until its five-second settling
interval and bounded cleanup finish, then stops renewing it. Native postupdate
saves/unloads cells after relevance expires unless players/other requests keep
them active. Failure/cancellation also stops renewal. It never evicts active
player chunks. The native loader still controls disk I/O, integration, collision,
vehicles, population and save/reuse. No ServerMap replacement changes were needed.

The mod setting LobbyCleanupZombies remains default false and requires LobbyEnabled.
The mod logs startup/progress/completion/failure independently of debug logging.
Absent bridge fails visibly rather than silently reverting to natural chunk loads.
Virtual zombie population, respawns and future arrivals are not erased; the
five-second settling allowance is not a native population-drained barrier. Normal
paused simulation remains paused. Server login/startup is not blocked on cleanup.

Source evidence: exact 42.22.1 decompiled ServerMap.loadOrKeepRelevent, preupdate,
postupdate, ServerCell.Load2/RecalcAll2/Unload; IngameState.OnTickEvenPaused and
paused population updateMain; corresponding patched ServerMap relevance lookup.

Changed sources:

- src/zombie/network/ApocBRLobbyStreaming.java (new companion bridge).
- src/zombie/Lua/LuaManager.java (server-only class exposure).
- tools/test_lobby_streaming.py at repository root (production bridge with engine doubles).

Validation passed on 2026-11-04:

```powershell
py tools/test_lobby_streaming.py
# From 42.22.1:
.\patchApocalipseBr.ps1 -BuildOnly -ArtifactDir Z:/git/Github/apocalipsebr-zomboid-patches/42.22.1/artifacts/lobby-startup-cleanup-20261104
```

Fixture: 12 server-authority, map-origin, invalid bounds, loading/integration and
explicit-renewal assertions. Exact-JAR build: 309 Java sources, 910 class files,
javac 25.0.4, successful, retained in the artifact directory above. It includes
other pre-existing working-tree changes; no deployment or game install mutation.
The companion mod's tests/lobby-cleanup.lua also passes with mocked streaming,
removal, timing and client command boundaries.

Still required in-game: empty paused startup, natural population materialization,
chunk load failures, save/reload after cleanup, native unload timing, player
influence during cleanup and multiplayer zombie-owner synchronization.

Deployment requires the updated server LuaManager class family and new bridge;
use the normal version patch script. This change has only been built, not deployed.
