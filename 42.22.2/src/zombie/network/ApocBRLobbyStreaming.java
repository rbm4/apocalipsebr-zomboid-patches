package zombie.network;

import zombie.gameStates.IngameState;
import zombie.popman.LoadedAreas;
import zombie.popman.ZombiePopulationManager;

/** Server-thread-only, frame-local streaming requests for Lua startup cleanup. */
public final class ApocBRLobbyStreaming {
    private ApocBRLobbyStreaming() {}
    private static boolean populationActive;
    private static int populationX, populationY;
    private static long populationRenewed;
    private static final long POPULATION_LEASE_NS = 15000000000L;

    /** Called by LoadedAreas.set(), on the server thread; never from native workers. */
    public static void appendPopulationArea(LoadedAreas areas) {
        if (GameServer.server && populationActive && System.nanoTime() - populationRenewed < POPULATION_LEASE_NS) {
            if (areas.count >= LoadedAreas.MAX_AREAS) {
                throw new IllegalStateException("No population area slot available for lobby cleanup");
            }
            areas.add(populationX, populationY, 8, 8);
        }
    }

    /** Activate virtual population in the loaded cell even without a connected player. */
    public static boolean activatePopulation(int x, int y) {
        if (keepCellLoaded(x, y) != 1) {
            throw new IllegalStateException("Lobby population activation requires a fully loaded server cell");
        }
        if (!LoadedAreas.apocbrLobbyActivationSupported()) {
            throw new IllegalStateException("Updated LoadedAreas patch required for lobby cleanup");
        }
        populationX = x / 64 * 8;
        populationY = y / 64 * 8;
        populationRenewed = System.nanoTime();
        populationActive = true;
        ZombiePopulationManager.instance.updateLoadedAreas();
        // Normal simulation skips this consumer on an empty paused server.
        // Drain native population work on its usual server thread, without
        // advancing the game clock or invoking the native worker ourselves.
        if (IngameState.instance != null && IngameState.instance.paused) {
            ZombiePopulationManager.instance.updateMain();
        }
        return ZombiePopulationManager.instance.readyToPause();
    }

    public static void releasePopulation() {
        if (!GameServer.server || !populationActive) return;
        populationActive = false;
        ZombiePopulationManager.instance.updateLoadedAreas();
    }

    /** Tile coordinates; -1 invalid, 0 loading, 1 fully integrated. No retained lease. */
    public static int keepCellLoaded(int x, int y) {
        if (!GameServer.server || x < 0 || y < 0 || x > 1000000 || y > 1000000) {
            return -1;
        }
        ServerMap map = ServerMap.instance;
        int cx = x / 64 - map.getMinX();
        int cy = y / 64 - map.getMinY();
        if (map.isInvalidCell(cx, cy)) {
            return -1;
        }
        map.loadOrKeepRelevent(cx, cy);
        ServerMap.ServerCell cell = map.getCell(cx, cy);
        return cell != null && cell.isLoaded ? 1 : 0;
    }
}
