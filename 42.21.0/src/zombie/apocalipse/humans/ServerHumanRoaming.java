package zombie.apocalipse.humans;

import zombie.apocalipse.humans.movement.IndoorRoutePlanner;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.iso.BuildingDef;
import zombie.iso.IsoGridSquare;

/** Roaming pattern: selects an indoor route and confines it to the home building. */
final class ServerHumanRoaming implements IndoorRoutePlanner.Grid {
    private final ServerHuman human;
    private BuildingDef building;
    private long sequence;

    ServerHumanRoaming(ServerHuman human) { this.human = human; }

    @Override
    public boolean available() {
        IsoGridSquare home = human.movement.square((int)human.definition.x(), (int)human.definition.y());
        if (home == null) return false;
        if (building == null) building = home.getBuildingDef();
        return building != null && home.getBuildingDef() == building
            && walkable(new Tile(human.player.getXi(), human.player.getYi()));
    }

    boolean start(long now) {
        human.movement.stop();
        if (!available()) return false;
        var route = IndoorRoutePlanner.plan(this, new Tile(human.player.getXi(), human.player.getYi()),
            ((long)human.player.getOnlineID() << 32) ^ ++sequence);
        return human.movement.start(route, this, now);
    }

    @Override
    public boolean walkable(Tile tile) {
        IsoGridSquare square = human.movement.square(tile.x(), tile.y());
        return building != null && square != null && square.getBuildingDef() == building
            && human.movement.walkable(tile);
    }

    @Override
    public boolean connected(Tile from, Tile to) {
        return walkable(from) && walkable(to) && human.movement.connected(from, to);
    }
}
