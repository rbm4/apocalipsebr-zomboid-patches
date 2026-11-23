package cloud.apocalipse.pz.agent.features.npc;

/** Native lifecycle entry points for the server's issued furniture bindings. */
public final class NpcFurnitureHooks {
    private NpcFurnitureHooks() {}

    public static void invalidate(Object object) {
        var furniture = ServerHumanFactory.furnitureOnOwner();
        if (furniture != null) furniture.invalidate(object);
    }

    public static void removedFromWorld(Object object, Object square) {
        var furniture = ServerHumanFactory.furnitureOnOwner();
        if (furniture != null) furniture.removedFromWorld(object, square);
    }

    public static Object beginCellUnload(int x, int y) {
        var furniture = ServerHumanFactory.furnitureOnOwner();
        return furniture == null ? null : furniture.beginCellUnload(x, y);
    }

    public static void endCellUnload(Object scope) {
        if (scope != null) ((ServerHumanFurnitureWorld.Unload) scope).close();
    }
}
