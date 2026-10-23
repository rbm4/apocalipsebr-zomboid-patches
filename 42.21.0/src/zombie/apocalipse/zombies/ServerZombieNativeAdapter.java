package zombie.apocalipse.zombies;

import java.util.List;
import zombie.apocalipse.humans.HumanLog;
import zombie.apocalipse.zombies.ZombieVictimPort.Victim;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.NetworkZombieVariables;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.network.GameServer;
import zombie.network.NetworkVariables.ZombieState;
import zombie.network.ServerMap;
import zombie.network.packets.character.ZombiePacket;
import zombie.pathfind.PolygonalMap2;

/** Native geometry and presentation. Never changes damage, speedMod, walk type or reaction flags. */
final class ServerZombieNativeAdapter {
    final IsoZombie zombie;
    final short onlineId;
    final int instanceId;
    private final float paceScale;
    private boolean running;
    private final ServerZombiePath navigation;
    private List<ServerZombiePath.Point> route = List.of();
    private int waypoint;
    private boolean moving, attacking;
    private boolean routeDirty;
    private boolean nativeTraversal;
    private float goalX, goalY, velocityX, velocityY, goalRadius, predictionSeconds=.2F;
    private static final float MAX_PREDICTION_DISTANCE = .6F;

    ServerZombieNativeAdapter(IsoZombie zombie, float speed) {
        this.zombie=zombie; onlineId=zombie.onlineId; instanceId=zombie.getID();
        navigation=new ServerZombiePath(zombie);
        var motion=ServerZombiePace.nativeMotion(zombie);
        paceScale=speed/motion.speed();running=motion.running();
    }
    float x(){return zombie.getX();}
    float y(){return zombie.getY();}
    int z(){return zombie.getZi();}
    boolean needsNativeTraversal(){return nativeTraversal || navigation.needsNativeTraversal()
        || zombie.getCurrentSquare()!=null && zombie.getCurrentSquare().HasStairs();}
    boolean valid(){return zombie.onlineId==onlineId && zombie.getID()==instanceId && !zombie.isDead()
        && ServerMap.instance.zombieMap.get(onlineId)==zombie && zombie.getCurrentSquare()!=null;}
    boolean interrupted(){
        if(zombie.isCrawling() || zombie.isReanimatedForGrappleOnly() || zombie.isUseless() || !zombie.isCanWalk()
            || zombie.isKnockedDown() || zombie.isOnFloor() || zombie.isRagdoll() || zombie.isStaggerBack()
            || zombie.isFakeDead() || zombie.isBeingGrappled() || zombie.isGrappling())return true;
        // Client-simulated zombies may have no local Java AI state on the server.
        // Absence alone is not a reaction; explicit physical protections above win.
        var current=zombie.getCurrentState();
        if(current==null)return false;
        String state=current.getClass().getSimpleName();
        return !(state.equals("ZombieIdleState") || state.equals("GenericDefaultState")
            || state.equals("ZombieTurnAlerted")
            || state.equals("WalkTowardNetworkState") || state.equals("WalkTowardState")
            || state.equals("LungeNetworkState") || state.equals("LungeState") || state.equals("PathFindState"));
    }
    double distance(Victim v){return Math.hypot(x()-v.x(),y()-v.y());}
    boolean immediate(Victim v){return distance(v)<=4.5 && visible(v);}
    boolean playerTakesPriority(Victim v){
        if(zombie.getWrappedGrappleable().getGrappledBy()!=null)return true;
        double distance=distance(v);
        // Initial testing: acquire before the zombie reaches the NPC's attack range.
        if(immediate(v))return false;
        for(var connection:GameServer.udpEngine.connections){
            if(!connection.isFullyConnected() || GameServer.isDelayedDisconnect(connection))continue;
            for(var player:connection.players)if(player!=null && !player.isDead() && !player.isGhostMode()
                && player.getZi()==z() && Math.hypot(player.getX()-x(),player.getY()-y())<distance
                && visible(new Victim(player.getOnlineID(),player,player.getX(),player.getY(),player.getZi())))return true;
        }
        return false;
    }
    void target(Victim v){zombie.setTarget(v.body());zombie.timeSinceSeenFlesh=0;}
    boolean visible(Victim v){
        if(z()!=v.z() || square((int)Math.floor(v.x()),(int)Math.floor(v.y()))==null)return false;
        var line=LosUtil.lineClear(zombie.getCell(),(int)Math.floor(x()),(int)Math.floor(y()),z(),
            (int)Math.floor(v.x()),(int)Math.floor(v.y()),v.z(),false);
        return line!=LosUtil.TestResults.Blocked && line!=LosUtil.TestResults.ClearThroughClosedDoor;
    }
    boolean contact(Victim v){
        if(zombie.isNoTeeth() || z()!=v.z() || distance(v)>.72 || !visible(v))return false;
        var from=square((int)Math.floor(x()),(int)Math.floor(y()));
        var to=square((int)Math.floor(v.x()),(int)Math.floor(v.y()));
        return from!=null && to!=null && !from.isSomethingTo(to) && !to.isSomethingTo(from)
            && !blocked(x(),y(),v.x(),v.y());
    }
    private IsoGridSquare square(int x,int y){return zombie.getCell().getGridSquare(x,y,z());}
    private boolean blocked(float x,float y,float tx,float ty){
        return PolygonalMap2.instance.lineClearCollide(x,y,tx,ty,z(),null,false,true);
    }
    boolean route(Victim v){
        if(!blocked(x(),y(),v.x(),v.y())){navigation.cancel();route=List.of();waypoint=0;return true;}
        if(routeDirty || !route.isEmpty() && waypoint>=route.size()){
            navigation.cancel();route=List.of();waypoint=0;routeDirty=false;
        }
        navigation.request(v.x(),v.y());
        return true; // Pending is working, not a failed route or a direct-line fallback.
    }
    void close(){navigation.cancel();route=List.of();}
    boolean move(Victim v,float seconds){
        if(!navigation.pending() && navigation.points()!=route){
            route=navigation.points();waypoint=0;
            // The asynchronous route starts behind our present position. Skip only
            // its root; never round fractional polygon waypoints to tile centres.
            if(route.size()>1)waypoint=1;
        }
        if(route.isEmpty() && (navigation.pending() || blocked(x(),y(),v.x(),v.y())))return false;
        var motion=ServerZombiePace.nativeMotion(zombie);
        float pace=motion.speed()*paceScale;
        running=motion.running();
        float remaining=pace*Math.clamp(seconds,0,.15F);
        boolean moved=false;
        // Spend one travel budget across short polygon segments. Dropping the
        // remainder at each corner makes speed depend on path node spacing.
        for(int work=0;remaining>.00001F && work<32;work++){
            while(waypoint<route.size() && Math.hypot(route.get(waypoint).x()-x(),route.get(waypoint).y()-y())<.001)waypoint++;
            boolean following=waypoint<route.size();
            float tx=following?route.get(waypoint).x():v.x(),ty=following?route.get(waypoint).y():v.y();
            double dx=tx-x(),dy=ty-y(),length=Math.hypot(dx,dy);
            float distance=(float)Math.max(0,length-(following?0:.65F));
            float step=Math.min(.1F,Math.min(distance,remaining));
            if(length<.001 || step<=.00001F)break;
            float nx=x()+(float)(dx/length)*step,ny=y()+(float)(dy/length)*step;
            var to=square((int)Math.floor(nx),(int)Math.floor(ny));var from=zombie.getCurrentSquare();
            if(to!=null && to.HasStairs()){nativeTraversal=true;break;}
            if(to==null || !to.TreatAsSolidFloor() || blocked(x(),y(),nx,ny)
                || from==null || from.isSomethingTo(to) || to.isSomethingTo(from)){routeDirty=true;break;}
            if(!moved){zombie.setLastX(x());zombie.setLastY(y());}
            zombie.setX(nx);zombie.setY(ny);zombie.setNextX(nx);zombie.setNextY(ny);zombie.setCurrentSquare(to);
            zombie.setMovingSquareNow();
            zombie.realx=nx;zombie.realy=ny;zombie.realz=(byte)z();
            moved=true;remaining-=step;
        }
        while(waypoint<route.size() && Math.hypot(route.get(waypoint).x()-x(),route.get(waypoint).y()-y())<.001)waypoint++;
        boolean following=waypoint<route.size();
        goalX=following?route.get(waypoint).x():v.x();goalY=following?route.get(waypoint).y():v.y();
        goalRadius=following?0:.65F;
        double dx=goalX-x(),dy=goalY-y(),length=Math.hypot(dx,dy);
        velocityX=length>.001?(float)(dx/length)*pace:0;velocityY=length>.001?(float)(dy/length)*pace:0;
        if(moved && length>.001)zombie.setDirectionAngle((float)Math.toDegrees(Math.atan2(dy,dx)));
        // Relay once per auth frame; cover two frames without unbounded prediction
        // when the server stalls. Async backpressure still retains the newest state.
        predictionSeconds=Math.clamp(seconds*2,.1F,.3F);
        if(moved)IsoWorld.instance.currentCell.getPlayerSpatialQueries().invalidateZombies();
        return moved;
    }
    void present(boolean moving,boolean attacking){
        this.moving=moving;this.attacking=attacking;
        if(attacking && zombie.target!=null)zombie.faceThisObject(zombie.target);
    }
    void packet(ZombiePacket packet){
        packet.x=x();packet.y=y();packet.z=(byte)z();
        packet.realX=x();packet.realY=y();packet.realZ=(byte)z();
        packet.predictionType=0;
        if(moving && !attacking){
            float pace=(float)Math.hypot(velocityX,velocityY);
            float distance=(float)Math.min(Math.max(0,Math.hypot(goalX-x(),goalY-y())-goalRadius),
                Math.min(pace*predictionSeconds,MAX_PREDICTION_DISTANCE));
            // Stop at the current waypoint: never extrapolate around a corner.
            // Sample every segment as well as the full collision line to respect
            // missing floors, stairs and unloaded squares.
            float px=x(),py=y();
            for(float travel=.05F;pace>0 && distance>0;travel+=.05F){
                float advance=Math.min(travel,distance);
                float nx=x()+velocityX/pace*advance,ny=y()+velocityY/pace*advance;
                var from=square((int)Math.floor(px),(int)Math.floor(py));
                var to=square((int)Math.floor(nx),(int)Math.floor(ny));
                if(from==null || to==null || !to.TreatAsSolidFloor() || to.HasStairs()
                    || from.isSomethingTo(to) || to.isSomethingTo(from) || blocked(x(),y(),nx,ny))break;
                px=nx;py=ny;
                if(advance>=distance)break;
            }
            if(Math.hypot(px-x(),py-y())>.001){packet.x=px;packet.y=py;packet.predictionType=1;}
        }
        packet.realState=attacking?ZombieState.Attack: moving?ZombieState.WalkToward:ZombieState.Idle;
        int runningMask=1<<NetworkZombieVariables.Flag.IsRunning.ordinal();
        packet.booleanVariables=(short)(running && moving ? packet.booleanVariables|runningMask : packet.booleanVariables&~runningMask);
        packet.update=(byte)((packet.update | 1 | 4) & ~16); // No stale client path goal.
        packet.pfb.reset();
    }
    void diagnostic(String phase,String reason){HumanLog.log(HumanLog.Level.DEBUG,()->"zombie control id="+onlineId
        +"; instance="+instanceId+"; phase="+phase+"; reason="+reason+"; position="+x()+","+y()+","+z());}
}
