package zombie.apocalipse.humans;

import java.util.List;
import java.util.Objects;
import zombie.apocalipse.humans.brain.HumanActuator;
import zombie.apocalipse.humans.movement.MovementGrid;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.humans.movement.MovementModifiers;
import zombie.apocalipse.humans.movement.MovementModifiers.*;
import zombie.iso.IsoGridSquare;
import zombie.pathfind.PolygonalMap2;
import zombie.apocalipse.humans.traversal.*;

/** Per-actor movement engine. Executes supplied routes without choosing a destination. */
final class ServerHumanMovement implements MovementGrid {
    static final float WALK_SPEED = 2.0F;
    // One second (two tiles) covers the 500 ms heartbeat plus jitter on straight paths.
    static final double PREDICTION_SECONDS = 1.0;
    static final int MAX_WAYPOINTS = 256;
    enum Gait { IDLE, WALK, RUN, SPRINT }
    /** Gait determines native animation; speed is physical tiles per second. */
    record Snapshot(Gait gait, float speed, int predictionUnits, Animation animation) {
        boolean moving() { return gait != Gait.IDLE; }
    }
    record Presentation(Gait gait, float speed, Animation animation) { }

    record Failure(String reason, Tile from, Tile to, float x, float y) { }
    private Failure lastFailure;
    Failure lastFailure(){return lastFailure;}
    Tile nextTile(){return waypoint<route.size()?route.get(waypoint):new Tile(human.player.getXi(),human.player.getYi());}
    private Float facingX,facingY;
    private boolean occupiedGaps;
    private float travelDirection;
    void tactical(Float x,Float y,boolean elevated){facingX=x;facingY=y;occupiedGaps=elevated;faceTarget();if(lastResolution!=Long.MIN_VALUE)refresh(lastResolution);}
    boolean guarded(){return facingX!=null&&facingY!=null&&!traversal.active();}
    private void faceTarget(){if(facingX!=null&&facingY!=null&&!traversal.active()){
        double dx=facingX-human.player.getX(),dy=facingY-human.player.getY();
        if(dx*dx+dy*dy>.000001)human.player.setDirectionAngle((float)Math.toDegrees(Math.atan2(dy,dx)));
    }}
    float travelDirection(){return guarded()?travelDirection:human.player.getDirectionAngleRadians();}
    private final ServerHuman human;
    private final HumanTraversal traversal;
    private MovementGrid policy;
    private boolean moving;
    private List<Tile> route = List.of();
    private int waypoint;
    private long lastStep;
    private long rhythmOrigin;
    private long lastResolution = Long.MIN_VALUE;
    private Pace requestedPace = Pace.WALK;
    private Conditions conditions = Conditions.NORMAL;
    private Conditions inputs = Conditions.NORMAL;
    private Settings settings = Settings.DEFAULT;
    private Resolved resolved = MovementModifiers.resolve(Pace.WALK, Conditions.NORMAL, Settings.DEFAULT, 0, 0);

    ServerHumanMovement(ServerHuman human, TraversalWorld world) {
        this.human = human;
        traversal = new HumanTraversal(human.player, human.definition.z(), world,
            (from, to) -> policy != null && (policy == this || policy.walkable(from) && policy.walkable(to) && policy.connected(from, to)),
            entering -> {
                for (var viewer : human.viewers) if (human.isRelevant(viewer)) {
                    ServerHumanTransport.idle(human, viewer);
                    sendTraversal(viewer, entering);
                }
            }, () -> {
                for (var viewer : human.viewers) if (human.isRelevant(viewer)) ServerHumanTransport.idle(human, viewer);
            });
    }
    private void sendTraversal(zombie.core.raknet.UdpConnection viewer, boolean entering) { traversal.send(viewer, entering); }
    void announceTraversal(zombie.core.raknet.UdpConnection viewer) { traversal.announce(viewer); }
    boolean traversing() { return traversal.active(); }
    void close() { try { stop(); } finally { traversal.close(); } }

    @Override
    public boolean available() { return walkable(new Tile(human.player.getXi(), human.player.getYi())); }

    /** Physical traversal only; callers can supply any bounded cardinal route. */
    boolean start(List<Tile> route, long now) { return start(route, this, now); }

    /** A route policy can restrict physical traversal, never bypass its checks. */
    boolean start(List<Tile> requested, MovementGrid policy, long now) {
        return start(requested, policy, Pace.WALK, now);
    }

    boolean start(List<Tile> requested, MovementGrid policy, Pace pace, long now) {
        stop();
        Objects.requireNonNull(requested);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(pace);
        if (requested.isEmpty() || requested.size() > MAX_WAYPOINTS) return false;
        List<Tile> copy = List.copyOf(requested);
        Tile previous = new Tile(human.player.getXi(), human.player.getYi());
        for (int i = 0; i < copy.size(); i++) {
            Tile tile = copy.get(i);
            long distance = Math.abs((long)tile.x() - previous.x()) + Math.abs((long)tile.y() - previous.y());
            if (distance != 1 && !(i == 0 && distance == 0)) return false;
            previous = tile;
        }
        if (!available() || !policy.available()) return false;
        lastFailure=null;
        this.route = copy;
        // Preserve ordinary centering/prediction. In an occupied origin, do not first
        // recenter toward an overlapping body before attempting to separate.
        var origin=square(human.player.getXi(),human.player.getYi());
        waypoint=copy.size()>1&&origin!=null&&!origin.isFree(true)
            &&copy.getFirst().equals(new Tile(human.player.getXi(),human.player.getYi()))?1:0;
        this.policy = policy;
        lastStep = now;
        rhythmOrigin = now;
        lastResolution = now;
        requestedPace = pace;
        refresh(now);
        return true;
    }

    /** Immutable simulation-thread inputs; health/endurance/threat producers retain ownership. */
    void conditions(Conditions value) { inputs = conditions = Objects.requireNonNull(value); }
    Conditions inputs(){return inputs;}
    void derivedConditions(Conditions value){conditions=Objects.requireNonNull(value);}
    void settings(Settings value) { settings = Objects.requireNonNull(value); }
    void requestPace(Pace value) { requestedPace = Objects.requireNonNull(value); }
    Resolved resolved() { return resolved; }

    /** Also called while idle/interacting so expired effects and condition presentation cannot stick. */
    void refresh(long now) {
        lastResolution = Math.max(lastResolution, now);
        traversal.clock(lastResolution);
        resolved = MovementModifiers.resolve(requestedPace, conditions, settings, lastResolution, rhythmOrigin);
        if(guarded()&&resolved.speed()>0){
            float ratio=Math.min(1,1.3F/resolved.speed());var animation=resolved.animation();
            resolved=new Resolved(Pace.WALK,resolved.speed()*ratio,
                new Animation(animation.walkInjury(),animation.walkSpeed()*ratio,animation.runSpeed()*ratio,animation.sneaking()),resolved.reason());
        }
        if (resolved.speed() == 0 && moving) {
            moving = false;
            human.postX = human.player.getX(); human.postY = human.player.getY();
        }
        applyPresentation();
    }

    private Gait gait() {
        if (!moving) return Gait.IDLE;
        return switch (resolved.pace()) { case RUN -> Gait.RUN; case SPRINT -> Gait.SPRINT; default -> Gait.WALK; };
    }

    Presentation presentation() { return new Presentation(gait(), moving ? resolved.speed() : 0, resolved.animation()); }

    private void applyPresentation() {
        Gait gait = gait();
        Animation animation = resolved.animation();
        human.player.setRunning(gait == Gait.RUN);
        human.player.setSprinting(gait == Gait.SPRINT);
        human.player.setSneaking(animation.sneaking());
        human.player.setVariable("WalkInjury", animation.walkInjury());
        human.player.setVariable("WalkSpeed", gait == Gait.RUN || gait == Gait.SPRINT ? animation.runSpeed() : animation.walkSpeed());
        human.player.setVariable("StrafeSpeed", animation.walkSpeed());
        human.player.getNetworkCharacterAI().walkSpeed = animation.walkSpeed();
        human.player.getNetworkCharacterAI().runSpeed = animation.runSpeed();
    }

    boolean moving() { return moving; }

    /** Resolved gait/speed for native animation flags and prediction. */
    Snapshot snapshot() {
        return new Snapshot(gait(), moving ? resolved.speed() : 0, predictionDistanceUnits(), resolved.animation());
    }

    HumanActuator.MotionStatus update(long now) {
        if (route.isEmpty()) return fail("route_missing");
        double elapsed = Math.max(0, Math.min(0.15, (now - lastStep) / 1_000_000_000.0));
        lastStep = Math.max(lastStep, now);
        refresh(now);
        if (!available() || !policy.available()) return fail("scope_or_origin_unavailable");
        if (traversal.active()) {
            if (resolved.speed() == 0) return fail("traversal_immobile");
            var result = traversal.update(now);
            if (result == TraversalExecution.Result.FAILED) return fail("traversal_"+traversal.reason());
            // A completed climb lands at the target; the normal loop consumes that waypoint next tick.
            return HumanActuator.MotionStatus.RUNNING;
        }
        if (resolved.speed() == 0) return HumanActuator.MotionStatus.RUNNING;
        double remaining = resolved.speed() * elapsed;
        // Consume the full distance budget, including across tile centers. Previously
        // each reached waypoint cost an extra update and discarded leftover time.
        for (int steps = 0; steps <= MAX_WAYPOINTS; steps++) {
            if (waypoint >= route.size()) {
                stop();
                return HumanActuator.MotionStatus.SUCCEEDED;
            }
            Tile target = route.get(waypoint);
            float tx = target.x() + 0.5F, ty = target.y() + 0.5F;
            float x = human.player.getX(), y = human.player.getY();
            Tile source = new Tile((int)Math.floor(x), (int)Math.floor(y));
            boolean centering = false;
            if (!source.equals(target)) {
                TraversalEdge edge = traversal.inspect(source, target);
                if (!edge.usable() || !edgeAllowed(source, target)) return fail("edge_blocked");
                if (edge.kind() != TraversalEdge.Kind.WALK) {
                    float cx = source.x()+.5F, cy = source.y()+.5F;
                    if (Math.hypot(cx-x, cy-y) > .001) { tx = cx; ty = cy; centering = true; }
                    else {
                        moving = false; applyPresentation();
                        ServerHumanTransport.aim(human,false);
                        traversal.start(edge, now);
                        if (traversal.update(now) == TraversalExecution.Result.FAILED) return fail("traversal_"+traversal.reason());
                        return HumanActuator.MotionStatus.RUNNING;
                    }
                }
            }
            double dx = tx - x, dy = ty - y, distance = Math.hypot(dx, dy);
            if (distance < 0.001) { if (!centering) waypoint++; continue; }
            if (remaining <= 0) return HumanActuator.MotionStatus.RUNNING;
            double step = Math.min(distance, remaining);
            float nx = step == distance ? tx : (float)(x + dx / distance * step);
            float ny = step == distance ? ty : (float)(y + dy / distance * step);
            IsoGridSquare destination = square((int)Math.floor(nx), (int)Math.floor(ny));
            if (!allowed(target) || !allowed(new Tile((int)Math.floor(nx), (int)Math.floor(ny)))) return fail("scope_rejected");
            if(destination==null)return fail("square_unloaded");
            if(!destination.isFree(false))return fail("static_obstacle");
            if(!occupancyAllows(x,y,nx,ny))return fail("body_occupied");
            Tile current = new Tile((int)Math.floor(x), (int)Math.floor(y));
            Tile next = new Tile((int)Math.floor(nx), (int)Math.floor(ny));
            if(!current.equals(next)&&!edgeAllowed(current,next))return fail("edge_changed");
            if(blocked(x,y,nx,ny))return fail("collision_segment");
            human.player.setX(nx);
            human.player.setY(ny);
            human.player.realx = nx;
            human.player.realy = ny;
            travelDirection=(float)Math.atan2(dy,dx);
            if(guarded())faceTarget();else human.player.setDirectionAngle((float)Math.toDegrees(travelDirection));
            moving = true;
            applyPresentation();
            remaining -= step;
            if (step == distance && !centering) waypoint++;
        }
        return fail("step_budget");
    }

    /** Native prediction distance is quantized in eighths of a tile. */
    int predictionDistanceUnits() {
        if (traversal.active() || !moving || waypoint >= route.size()) return 0;
        Tile target = route.get(waypoint);
        float x = human.player.getX(), y = human.player.getY();
        double dx = target.x() + 0.5 - x, dy = target.y() + 0.5 - y;
        double distance = Math.hypot(dx, dy);
        double angle = travelDirection();
        // Never extrapolate the old heading around an upcoming corner.
        if (distance < 0.001 || (dx * Math.cos(angle) + dy * Math.sin(angle)) / distance < 0.999) return 0;
        if (!allowed(target)) return 0;
        Tile current = new Tile((int)Math.floor(x), (int)Math.floor(y));
        if (!current.equals(target) && !walkingConnected(current, target)) return 0;
        double headingX = dx / distance, headingY = dy / distance;
        // Tile centers on a straight run are not stopping points. Look ahead only
        // through connected, collinear route edges; never predict through a turn.
        for (int i = waypoint + 1; i < route.size() && distance < resolved.speed() * PREDICTION_SECONDS + 0.15; i++) {
            Tile next = route.get(i);
            int edgeX = next.x() - target.x(), edgeY = next.y() - target.y();
            if (edgeX * headingX + edgeY * headingY < 0.999 || !walkingConnected(target, next)) break;
            distance += 1.0;
            target = next;
        }
        // Leave headroom for the native client's final frame of extrapolation.
        int units = Math.min(Byte.MAX_VALUE, (int)Math.floor(Math.min(distance - 0.15, resolved.speed() * PREDICTION_SECONDS) * 8));
        if (units <= 0) return 0;
        double projected = units / 8.0;
        float nx = (float)(x + headingX * projected), ny = (float)(y + headingY * projected);
        IsoGridSquare destination = square((int)Math.floor(nx), (int)Math.floor(ny));
        if (destination == null || !destination.isFree(true)
            || !allowed(new Tile((int)Math.floor(nx), (int)Math.floor(ny)))
            || blocked(x, y, nx, ny)) return 0;
        return units;
    }

    void stop() {
        traversal.cancel();
        route = List.of();
        policy = null;
        waypoint = 0;
        moving = false;
        requestedPace = Pace.WALK;
        refresh(lastResolution);
        // Greeting recovery restores this last stopping point, not the spawn tile.
        human.postX = human.player.getX();
        human.postY = human.player.getY();
    }

    boolean occupancyAllows(float x,float y,float nx,float ny) {
        var destination=square((int)Math.floor(nx),(int)Math.floor(ny));
        if(destination==null)return false;
        if(occupiedGaps||guarded())return (destination.isFree(true)||!destination.getMovingObjects().isEmpty())&&bodyClear(x,y,nx,ny);
        if(destination.isFree(true))return true;
        // Permit separation from existing overlap, never entering another occupied tile.
        if((int)Math.floor(x)!=destination.x||(int)Math.floor(y)!=destination.y)return false;
        var bodies=destination.getMovingObjects();
        if(bodies.isEmpty()||bodies.size()>64)return false;
        for(var body:bodies){
            if(body==human.player)continue;
            if(!(body instanceof zombie.characters.IsoGameCharacter character))return false;
            double before=Math.hypot(x-character.getX(),y-character.getY());
            double after=Math.hypot(nx-character.getX(),ny-character.getY());
            double dx=nx-x,dy=ny-y;
            double outward=(x-character.getX())*dx+(y-character.getY())*dy;
            if(before<.65){if((before>.00001&&outward<=0)||after<=before+.00001)return false;}
            else {
                double length=dx*dx+dy*dy;
                double t=length==0?0:Math.clamp(-outward/length,0,1);
                if(Math.hypot(x+t*dx-character.getX(),y+t*dy-character.getY())<.65)return false;
            }
        }
        return true;
    }
    /** Continuous local body clearance for elevated-exposure routes; static geometry remains mandatory elsewhere. */
    boolean bodyClear(float x,float y,float nx,float ny){
        int inspected=0;
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<zombie.iso.IsoMovingObject,Boolean>());
        int left=(int)Math.floor(Math.min(x,nx))-1,right=(int)Math.floor(Math.max(x,nx))+1;
        int top=(int)Math.floor(Math.min(y,ny))-1,bottom=(int)Math.floor(Math.max(y,ny))+1;
        if(right-left>3||bottom-top>3)return false;
        for(int tx=left;tx<=right;tx++)for(int ty=top;ty<=bottom;ty++){
            var square=square(tx,ty);if(square==null)continue;
            for(var body:square.getMovingObjects()){
                if(!seen.add(body)||body==human.player)continue;
                if(++inspected>64)return false;
                if(!(body instanceof zombie.characters.IsoGameCharacter c))return false;
                if(c.isDead()||c.getHealth()<=0)continue;
                double dx=nx-x,dy=ny-y,before=Math.hypot(x-c.getX(),y-c.getY()),after=Math.hypot(nx-c.getX(),ny-c.getY());
                double outward=(x-c.getX())*dx+(y-c.getY())*dy;
                if(before<.65){if((before>.00001&&outward<=0)||after<=before+.00001)return false;}
                else {double length=dx*dx+dy*dy,t=length==0?0:Math.clamp(-outward/length,0,1);
                    if(Math.hypot(x+t*dx-c.getX(),y+t*dy-c.getY())<.65)return false;}
            }
        }
        return true;
    }
    private HumanActuator.MotionStatus fail(String reason) {
        Tile from=new Tile(human.player.getXi(),human.player.getYi());
        Tile to=waypoint<route.size()?route.get(waypoint):from;
        lastFailure=new Failure(reason,from,to,human.player.getX(),human.player.getY());
        var failure=lastFailure;
        var tile=square(to.x(),to.y());int occupants=tile==null?-1:tile.getMovingObjects().size();
        HumanLog.log(HumanLog.Level.DEBUG,()->"movement failed key="+human.definition.key()+"; reason="+failure.reason()
            +"; from="+failure.from()+"; to="+failure.to()+"; x="+failure.x()+"; y="+failure.y()
            +"; waypoint="+waypoint+"; routeSize="+route.size()+"; occupants="+occupants);
        stop();
        return HumanActuator.MotionStatus.FAILED;
    }

    @Override
    public boolean walkable(Tile tile) {
        return traversal.walkable(tile);
    }

    @Override
    public boolean connected(Tile from, Tile to) {
        return traversal.inspect(from, to).usable();
    }

    @Override public int traversalCost(Tile from, Tile to) { return traversal.inspect(from, to).kind().cost(); }
    private boolean walkingConnected(Tile from, Tile to) {
        return traversal.inspect(from, to).kind() == TraversalEdge.Kind.WALK && edgeAllowed(from, to);
    }

    private boolean allowed(Tile tile) {
        return walkable(tile) && (policy == this || policy.walkable(tile));
    }

    private boolean edgeAllowed(Tile from, Tile to) {
        return connected(from, to) && (policy == this || policy.connected(from, to));
    }

    private boolean blocked(float x, float y, float nx, float ny) {
        return PolygonalMap2.instance.lineClearCollide(x, y, nx, ny, human.definition.z(), null, false, true);
    }

    IsoGridSquare square(int x, int y) {
        return human.player.getCell().getGridSquare(x, y, human.definition.z());
    }
}
