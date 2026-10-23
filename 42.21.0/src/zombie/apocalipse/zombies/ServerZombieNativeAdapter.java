package zombie.apocalipse.zombies;

import java.util.List;
import zombie.apocalipse.humans.HumanLog;
import zombie.apocalipse.zombies.ZombieVictimPort.Victim;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.NetworkZombieVariables;
import zombie.iso.Vector2;
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
    private final float speed, originalSpeedMod;
    private final boolean running;
    private final ServerZombiePath navigation;
    private List<ServerZombiePath.Point> route = List.of();
    private int waypoint;
    private boolean moving, attacking;
    private boolean routeDirty;
    private float goalX, goalY, velocityX, velocityY, goalRadius, predictionSeconds=.2F;
    private static final float MAX_PREDICTION_DISTANCE = .6F;

    ServerZombieNativeAdapter(IsoZombie zombie, float speed) {
        this.zombie=zombie; onlineId=zombie.onlineId; instanceId=zombie.getID();
        navigation=new ServerZombiePath(zombie);
        this.speed=speed; originalSpeedMod=Math.max(.01F,zombie.getSpeedMod());
        boolean previous=zombie.running;
        try{
            zombie.running=false;
            zombie.getZombieWalkTowardSpeed(.025F,1,new Vector2(1,0));
            running=zombie.running;
        }finally{zombie.running=previous;}
    }
    float x(){return zombie.getX();}
    float y(){return zombie.getY();}
    int z(){return zombie.getZi();}
    boolean valid(){return zombie.onlineId==onlineId && zombie.getID()==instanceId && !zombie.isDead()
        && ServerMap.instance.zombieMap.get(onlineId)==zombie && zombie.getCurrentSquare()!=null;}
    boolean interrupted(){
        if(zombie.isCrawling() || zombie.isReanimatedForGrappleOnly() || zombie.isUseless() || !zombie.isCanWalk()
            || zombie.isKnockedDown() || zombie.isOnFloor() || zombie.isRagdoll() || zombie.isStaggerBack()
            || zombie.isFakeDead() || zombie.isBeingGrappled() || zombie.isGrappling())return true;
        String state=zombie.getCurrentState()==null?"":zombie.getCurrentState().getClass().getSimpleName();
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
        float tx=v.x(),ty=v.y();
        if(!route.isEmpty()){
            while(waypoint<route.size() && Math.hypot(route.get(waypoint).x()-x(),route.get(waypoint).y()-y())<.03)waypoint++;
            if(waypoint<route.size()){tx=route.get(waypoint).x();ty=route.get(waypoint).y();}
        }else if(navigation.pending() || blocked(x(),y(),tx,ty)){
            return false;
        }
        double dx=tx-x(),dy=ty-y(),length=Math.hypot(dx,dy);
        float pace=speed*zombie.getSpeedMod()/originalSpeedMod;
        float step=(float)Math.min(length, pace*seconds);
        if(length<.001 || step<=0)return false;
        float nx=x()+(float)(dx/length)*step,ny=y()+(float)(dy/length)*step;
        var to=square((int)Math.floor(nx),(int)Math.floor(ny));var from=zombie.getCurrentSquare();
        if(to==null || !to.TreatAsSolidFloor() || to.HasStairs() || blocked(x(),y(),nx,ny)
            || from==null || from.isSomethingTo(to) || to.isSomethingTo(from)){routeDirty=true;return false;}
        // Preserve world membership: the zombie stays a normal native moving object.
        zombie.setLastX(x());zombie.setLastY(y());
        zombie.setX(nx);zombie.setY(ny);zombie.setNextX(nx);zombie.setNextY(ny);zombie.setCurrentSquare(to);
        zombie.setMovingSquareNow();
        zombie.realx=nx;zombie.realy=ny;zombie.realz=(byte)z();
        zombie.setDirectionAngle((float)Math.toDegrees(Math.atan2(dy,dx)));
        goalX=tx;goalY=ty;
        goalRadius=route.isEmpty() || waypoint>=route.size() ? .65F : 0;
        velocityX=(float)(dx/length)*pace;velocityY=(float)(dy/length)*pace;
        // Relay once per auth frame; cover two frames without unbounded prediction
        // when the server stalls. Async backpressure still retains the newest state.
        predictionSeconds=Math.clamp(seconds*2,.1F,.3F);
        IsoWorld.instance.currentCell.getPlayerSpatialQueries().invalidateZombies();
        return true;
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
