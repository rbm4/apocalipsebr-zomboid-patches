package zombie.apocalipse.humans;

import java.util.function.Consumer;
import zombie.apocalipse.humans.tasks.HumanCorpsePort.DragStatus;
import zombie.apocalipse.zombies.ServerCorpseDragAuthority;
import zombie.characters.IsoZombie;
import zombie.iso.objects.IsoDeadBody;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketTypes;
import zombie.network.ServerMap;
import zombie.popman.NetworkZombieManager;

/** One conversion session. No input simulation, fake client owner or server animation loop. */
final class ServerHumanCorpseDrag {
    static final long PICKUP_NANOS=1_500_000_000L,DROP_NANOS=1_200_000_000L;
    private final ServerHuman human;
    private final Consumer<IsoDeadBody> onDrop;
    private IsoZombie target;
    private int instance;private short online;
    private boolean released,dropping,failed;
    private long readyAt,dropAt;
    private boolean stateChanged=true;
    private String type="BwdDrag",dragType;
    private float bearing,bodyOffset=.3F;
    private boolean reverseBody;
    ServerHumanCorpseDrag(ServerHuman human,Consumer<IsoDeadBody> onDrop){this.human=human;this.onDrop=onDrop;}
    boolean start(IsoDeadBody body,long now){
        if(human.player.isGrappling()||human.player.isBeingGrappled()||!ServerCorpseDragAuthority.available())return false;
        double dx=human.player.getX()-body.getX(),dy=human.player.getY()-body.getY();
        if(Math.hypot(dx,dy)>1.6||body.getZi()!=human.player.getZi())return false;
        if(Math.hypot(dx,dy)>.001)human.player.setDirectionAngle((float)Math.toDegrees(Math.atan2(-dy,-dx)));
        body.getModData().rawset("lastPlayerGrabbed",(int)human.player.getOnlineID());
        // Keep the returned native replacement even if grapple acceptance fails, so rollback cannot orphan it.
        if(!(body.reanimate() instanceof IsoZombie zombie))return false;
        target=zombie;online=zombie.onlineId;instance=zombie.getID();
        target.setReanimatedForGrappleOnly(true);target.setFakeDead(false);target.setCrawler(false);target.setOnFloor(true);
        target.setDoRender(body.getDoRender());target.addFootstepParametersIfNeeded();
        // It is already a dead body's presentation: dropping must not count another kill or regenerate loot.
        target.setOnKillDone(true);
        target.addOnDiedListener((sender,corpse)->{if(sender==target&&sender.getID()==instance)onDrop.accept(corpse);},true);
        human.player.setDoGrapple(true);
        try{target.Grappled(human.player,human.player.getAttackingWeapon(),human.player.calculateGrappleEffectivenessFromTraits(),"BwdDrag");}
        finally{human.player.setDoGrapple(false);}
        if(human.player.getGrapplingTarget()!=target||!ServerCorpseDragAuthority.acquire(target)){release();return false;}
        NetworkZombieManager.getInstance().moveZombie(target,null,null);
        bearing=human.player.getBearingFromGrappledTarget();
        // Match native action-group bearing thresholds and paired drag XML offsets/yaw.
        boolean head=Math.abs(bearing)>=90;dragType=head?"BwdDragHead":"BwdDragLegs";
        boolean front=target.isFallOnFront();reverseBody=head?front:!front;
        bodyOffset=!head&&front?.7F:.3F;
        human.player.setDoContinueGrapple(true);human.movement.dragging(true);
        ServerHumanTransport.aim(human,false);readyAt=now+PICKUP_NANOS;
        log("pickup");tick(now);return true;
    }
    boolean live(){return !released&&target!=null&&target.onlineId==online&&target.getID()==instance
        &&ServerMap.instance.zombieMap.get(online)==target&&!target.isDead()
        &&target.isReanimatedForGrappleOnly()&&target.getGrappledBy()==human.player&&human.player.getGrapplingTarget()==target;}
    DragStatus status(long now){
        if(failed)return DragStatus.FAILED;
        if(released)return DragStatus.RELEASED;
        if(!live())return DragStatus.FAILED;
        if(dropping)return DragStatus.DROPPING;
        return now<readyAt?DragStatus.PICKING_UP:DragStatus.DRAGGING;
    }
    DragStatus drop(long now){
        if(!live())return released?DragStatus.RELEASED:DragStatus.FAILED;
        if(!dropping){dropping=true;dropAt=now+DROP_NANOS;human.player.setDoContinueGrapple(false);stateChanged=true;log("dropping");}
        tick(now);return status(now);
    }
    void tick(long now){
        if(released)return;
        if(!live()){failed=true;release();return;}
        if(!dropping&&now>=readyAt&&!type.equals(dragType)){
            type=dragType;target.setOnFloor(false);human.player.setSharedGrappleType(type);stateChanged=true;log("dragging");
        }
        if(now>=readyAt)target.setOnFloor(false);
        // Match native drag XML offsets; actor's checked route remains authoritative.
        double angle=human.player.getDirectionAngleRadians();
        float x=human.player.getX()+bodyOffset*(float)Math.cos(angle),y=human.player.getY()+bodyOffset*(float)Math.sin(angle);
        var from=new zombie.apocalipse.humans.movement.MovementGrid.Tile(human.player.getXi(),human.player.getYi());
        var to=new zombie.apocalipse.humans.movement.MovementGrid.Tile((int)Math.floor(x),(int)Math.floor(y));
        if(!from.equals(to)&&human.movement.traversalCost(from,to)!=zombie.apocalipse.humans.traversal.TraversalEdge.Kind.WALK.cost()){x=human.player.getX();y=human.player.getY();}
        if(now>=readyAt)place(x,y);
        if(dropping&&now>=dropAt){release();return;}
        // Native PlayerDraggingCorpse.setParams calls AcceptGrapple, resetting the
        // paired animation. State packets describe transitions, never a heartbeat.
        if(stateChanged){stateChanged=false;for(var viewer:human.viewers)if(human.isRelevant(viewer))announce(viewer);}
    }
    void announce(UdpConnection viewer){if(live())send(viewer,true);}
    private void send(UdpConnection viewer,boolean entering){
        try{new ServerHumanDragPacket(human,entering?target:null,type,entering,entering&&!dropping,bearing).sendToClient(PacketTypes.PacketType.State,viewer);}
        catch(RuntimeException failure){HumanLog.log(HumanLog.Level.WARN,()->"corpse drag presentation failed key="+human.definition.key()+"; reason="+failure);}
    }
    private void place(float x,float y){
        var square=human.player.getCell().getGridSquare((int)Math.floor(x),(int)Math.floor(y),human.player.getZi());
        if(square==null){failed=true;return;}
        target.setX(x);target.setY(y);target.setZ(human.player.getZ());target.setCurrent(square);target.setMovingSquareNow();
        target.setDirectionAngle((float)Math.toDegrees(human.player.getDirectionAngleRadians()+(reverseBody?Math.PI:0)));target.setTarget(null);
    }
    void release(){
        if(released)return;released=true;human.movement.dragging(false);
        if(target==null)return;
        try{
            if(target.getID()==instance&&target.onlineId==online&&ServerMap.instance.zombieMap.get(online)==target){
                place(human.player.getX(),human.player.getY());
                target.setHealth(0);
                if(human.player.getGrapplingTarget()==target)human.player.LetGoOfGrappled("Dropped");
                else if(!target.isDead())target.die();
            }
        }finally{
            // A removed/recycled target must not leave the NPC holding a stale relation.
            if(human.player.getGrapplingTarget()==target)human.player.resetGrappleStateToDefault("Aborted");
            ServerCorpseDragAuthority.release(target);log("released");
            for(var viewer:human.viewers)if(human.isRelevant(viewer))send(viewer,false);
        }
    }
    private void log(String phase){HumanLog.log(HumanLog.Level.DEBUG,()->"corpse drag key="+human.definition.key()+"; phase="+phase+"; zombie="+online+"; instance="+instance);}
}
