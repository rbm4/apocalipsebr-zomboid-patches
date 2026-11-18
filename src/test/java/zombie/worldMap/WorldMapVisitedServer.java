package zombie.worldMap;

/** Structural double for the target server-loop simulation boundary; never packaged. */
public final class WorldMapVisitedServer {
    public static int updates;
    private static final WorldMapVisitedServer INSTANCE = new WorldMapVisitedServer();

    public static WorldMapVisitedServer getInstance() {
        return INSTANCE;
    }

    public void update() {
        updates++;
    }
}
