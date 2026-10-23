package zombie.apocalipse.zombies;

import java.util.List;
import zombie.apocalipse.humans.HumanLog;
import zombie.apocalipse.humans.movement.DestinationRoutePlanner;
import zombie.apocalipse.humans.movement.MovementGrid;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.zombies.ZombieVictimPort.Victim;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.network.GameServer;
import zombie.network.NetworkVariables.ZombieState;
import zombie.network.ServerMap;
import zombie.network.packets.character.ZombiePacket;
import zombie.pathfind.PolygonalMap2;

/** Native geometry and presentation. Never changes damage, speedMod, walk type or reaction flags. */
final class ServerZombieNativeAdapter implements MovementGrid {
    final IsoZombie zombie;
    final short onlineId;
    final int instanceId;
    private final float speed, originalSpeedMod;
    private List<Tile> route = List.of();
    private int waypoint;
    private boolean moving, attacking;

    ServerZombieNativeAdapter(IsoZombie zombie, float speed) {
        this.zombie=zombie; onlineId=zombie.onlineId; instanceId=zombie.getID();
        this.speed=speed; originalSpeedMod=Math.max(.01F,zombie.getSpeedMod());
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
            || state.equals("WalkTowardNetworkState") || state.equals("WalkTowardState")
            || state.equals("LungeNetworkState") || state.equals("LungeState") || state.equals("PathFindState"));
    }
    double distance(Victim v){return Math.hypot(x()-v.x(),y()-v.y());}
    boolean playerTakesPriority(Victim v){
        if(zombie.getWrappedGrappleable().getGrappledBy()!=null)return true;
        if(zombie.target instanceof IsoPlayer player && player!=v.body()
            && GameServer.getConnectionFromPlayer(player)!=null && !player.isDead() && !player.isGhostMode())return true;
        double distance=distance(v);
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
    public boolean walkable(Tile t){
        var square=square(t.x(),t.y());
        return square!=null && square.TreatAsSolidFloor() && square.isFree(false) && !square.HasStairs();
    }
    public boolean connected(Tile a,Tile b){
        var from=square(a.x(),a.y());var to=square(b.x(),b.y());
        return from!=null && to!=null && !from.isSomethingTo(to) && !to.isSomethingTo(from)
            && !blocked(a.x()+.5F,a.y()+.5F,b.x()+.5F,b.y()+.5F);
    }
    private boolean blocked(float x,float y,float tx,float ty){
        return PolygonalMap2.instance.lineClearCollide(x,y,tx,ty,z(),null,false,true);
    }
    boolean route(Victim v){
        if(!blocked(x(),y(),v.x(),v.y())){route=List.of();waypoint=0;return true;}
        route=DestinationRoutePlanner.plan(this,new Tile((int)Math.floor(x()),(int)Math.floor(y())),
            new Tile((int)Math.floor(v.x()),(int)Math.floor(v.y())));
        waypoint=0;
        return !route.isEmpty();
    }
    boolean move(Victim v,float seconds){
        float tx=v.x(),ty=v.y();
        if(!route.isEmpty()){
            while(waypoint<route.size() && Math.hypot(route.get(waypoint).x()+.5F-x(),route.get(waypoint).y()+.5F-y())<.08)waypoint++;
            if(waypoint<route.size()){tx=route.get(waypoint).x()+.5F;ty=route.get(waypoint).y()+.5F;}
        }
        double dx=tx-x(),dy=ty-y(),length=Math.hypot(dx,dy);
        float step=(float)Math.min(length, speed*zombie.getSpeedMod()/originalSpeedMod*seconds);
        if(length<.001 || step<=0)return false;
        float nx=x()+(float)(dx/length)*step,ny=y()+(float)(dy/length)*step;
        var to=square((int)Math.floor(nx),(int)Math.floor(ny));var from=zombie.getCurrentSquare();
        if(to==null || !to.TreatAsSolidFloor() || !to.isFree(false) || to.HasStairs() || blocked(x(),y(),nx,ny)
            || from==null || from.isSomethingTo(to) || to.isSomethingTo(from))return false;
        // Preserve world membership: the zombie stays a normal native moving object.
        zombie.setLastX(x());zombie.setLastY(y());
        zombie.setX(nx);zombie.setY(ny);zombie.setNextX(nx);zombie.setNextY(ny);zombie.setCurrentSquare(to);
        zombie.setMovingSquareNow();
        zombie.realx=nx;zombie.realy=ny;zombie.realz=(byte)z();
        zombie.setDirectionAngle((float)Math.toDegrees(Math.atan2(dy,dx)));
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
        packet.predictionType=0; // Authoritative sampled position; never predict through an obstacle.
        packet.realState=attacking?ZombieState.Attack: moving?ZombieState.WalkToward:ZombieState.Idle;
        packet.update=(byte)((packet.update | 1 | 4) & ~16); // No stale client path goal.
        packet.pfb.reset();
    }
    void diagnostic(String phase,String reason){HumanLog.log(HumanLog.Level.DEBUG,()->"zombie control id="+onlineId
        +"; instance="+instanceId+"; phase="+phase+"; reason="+reason+"; position="+x()+","+y()+","+z());}
}
