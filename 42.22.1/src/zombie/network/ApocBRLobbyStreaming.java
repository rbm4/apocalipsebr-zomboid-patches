package zombie.network;

/** Server-thread-only, frame-local streaming requests for Lua startup cleanup. */
public final class ApocBRLobbyStreaming {
    private ApocBRLobbyStreaming() {}

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
