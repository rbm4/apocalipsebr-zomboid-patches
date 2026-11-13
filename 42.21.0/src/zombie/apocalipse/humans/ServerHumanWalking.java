package zombie.apocalipse.humans;

import java.util.*;
import zombie.apocalipse.humans.brain.HumanActuator.MotionStatus;
import zombie.apocalipse.humans.movement.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.humans.walking.*;

/** Navigation adapter and per-request ownership; pattern selection is supplied by the profile or objective. */
final class ServerHumanWalking implements WalkingPattern.World {
    private final ServerHuman human;
    private final Map<String, WalkingPattern> patterns = new HashMap<>();
    private WalkingPattern active;
    private boolean returning;
    private long sequence;
    ServerHumanWalking(ServerHuman human) {
        this.human = human;
        for (var spec : human.profile.walkingPatterns()) patterns.put(spec.slot(), ServerHumanProfiles.walkingPatterns().create(spec));
    }
    private WalkingPattern.Context context() {
        return new WalkingPattern.Context(this, new Tile(human.player.getXi(), human.player.getYi()),
            new Tile((int)human.definition.x(), (int)human.definition.y()), human.player.getZi(),
            ((long)human.player.getOnlineID()<<32) ^ sequence);
    }
    MovementGrid scope(String slot) {
        var pattern = patterns.get(slot); return pattern == null ? null : pattern.scope(context());
    }
    boolean permitted(Tile tile) {
        var grid=scope("background");return grid==null?walkable(tile):grid.walkable(tile);
    }
    boolean start(MovementRequest request, long now) {
        stop();
        if (!available()) return false;
        sequence++;
        var context = context();
        MovementGrid scope; List<Tile> route;
        if (request instanceof MovementRequest.Pattern pattern) {
            var selected=patterns.get(pattern.slot());if(selected==null)return false;
            scope=selected.scope(context);
            if(pattern.slot().equals("background")&&!scope.walkable(context.current())){
                // A recovery leg does not complete/advance the authored walking pattern.
                returning=true;
                var assigned=scope;scope=recoveryGrid(context.current());
                var anchor=returnAnchor(context,assigned);
                route=AreaReturnPlanner.plan(scope,context.current(),assigned::walkable,anchor);
                var end=route.isEmpty()?null:route.getLast();int steps=route.size();
                HumanLog.log(HumanLog.Level.DEBUG,()->"area return key="+human.definition.key()+"; from="+context.current()
                    +"; anchor="+anchor+"; goal="+end+"; steps="+steps);
            }else{active=selected;route=selected.plan(context);}
        } else {
            var objective = (MovementRequest.To)request;
            var destination = objective.destination();
            if (destination.z() != context.z()) return false;
            // An objective may select its destination, but cannot bypass the profile's assigned movement area.
            var assigned=scope(objective.slot());
            if(assigned==null&&!objective.slot().equals("background"))return false;
            scope=assigned==null?this:assigned;
            route = DestinationRoutePlanner.plan(scope, context.current(), new Tile(destination.x(), destination.y()));
        }
        if (!human.movement.start(route, scope, now)) { finish(false); return false; }
        return true;
    }
    private Tile returnAnchor(WalkingPattern.Context context,MovementGrid assigned){
        var spec=human.profile.walkingPatterns().stream().filter(p->p.slot().equals("background")).findFirst().orElseThrow();
        if(spec.area()!=null){
            var area=spec.area();var closest=new Tile(Math.clamp(context.current().x(),area.x1(),area.x2()),Math.clamp(context.current().y(),area.y1(),area.y2()));
            if(assigned.walkable(closest))return closest;
        }
        return context.home();
    }
    private MovementGrid recoveryGrid(Tile start){
        return new MovementGrid(){
            public boolean available(){return human.movement.available();}
            public boolean walkable(Tile tile){var square=human.movement.square(tile.x(),tile.y());
                return human.movement.walkable(tile)&&square!=null&&(tile.equals(start)||human.movement.freeOfBlockingBodies(square));}
            public boolean connected(Tile from,Tile to){return traversalCost(from,to)>0;}
            public int traversalCost(Tile from,Tile to){return walkable(from)&&walkable(to)?human.movement.traversalCost(from,to):0;}
        };
    }
    MotionStatus update(long now) {
        var result = human.movement.update(now);
        if (result != MotionStatus.RUNNING) finish(result == MotionStatus.SUCCEEDED);
        return result;
    }
    private void finish(boolean success) {
        var previous=active;active=null;
        if(returning){returning=false;HumanLog.log(HumanLog.Level.DEBUG,()->"area return settled key="+human.definition.key()+"; success="+success
            +"; inside="+permitted(new Tile(human.player.getXi(),human.player.getYi())));}
        if(previous!=null)previous.completed(success);
    }
    void stop() { try { human.movement.stop(); } finally { finish(false); } }
    public boolean available() { return human.movement.available(); }
    public boolean walkable(Tile tile) { return human.movement.walkable(tile); }
    public boolean connected(Tile from, Tile to) { return human.movement.connected(from, to); }
    public int traversalCost(Tile from, Tile to) { return human.movement.traversalCost(from, to); }
    public boolean sameBuilding(Tile first, Tile second) {
        var a = human.movement.square(first.x(), first.y()); var b = human.movement.square(second.x(), second.y());
        return a != null && b != null && a.getBuildingDef() != null && a.getBuildingDef() == b.getBuildingDef();
    }
}
