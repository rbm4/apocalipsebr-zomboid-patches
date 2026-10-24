package zombie.apocalipse.zombies;

import java.util.*;
import zombie.characters.IsoZombie;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.network.packets.character.ZombiePacket;

/** Simulation-thread authority for animated dead bodies, separate from combat pursuit. */
public final class ServerCorpseDragAuthority {
    private record Lease(short id,int instance,Object grappler) { }
    private static final Map<IsoZombie,Lease> bodies=new IdentityHashMap<>();
    private static long nextRelay=Long.MIN_VALUE;
    private ServerCorpseDragAuthority() { }
    public static boolean available(){return bodies.size()<128;}
    public static boolean acquire(IsoZombie body){
        if(!GameServer.server||!available()||!body.isReanimatedForGrappleOnly()||!body.isBeingGrappled()||body.onlineId==-1||ServerMap.instance.zombieMap.get(body.onlineId)!=body)return false;
        bodies.put(body,new Lease(body.onlineId,body.getID(),body.getGrappledBy()));return true;
    }
    public static boolean controls(IsoZombie body){
        var lease=bodies.get(body);
        return GameServer.server&&lease!=null&&lease.id()==body.onlineId&&lease.instance()==body.getID()
            &&body.isReanimatedForGrappleOnly()&&body.getGrappledBy()==lease.grappler()
            &&ServerMap.instance.zombieMap.get(body.onlineId)==body;
    }
    public static void release(IsoZombie body){bodies.remove(body);}
    public static void addRelays(Collection<IsoZombie> output){
        long now=System.nanoTime();if(now<nextRelay)return;nextRelay=now+100_000_000L;
        for(var body:bodies.keySet())if(controls(body)&&!body.isDead())output.add(body);
    }
    public static void prepare(IsoZombie body,ZombiePacket packet){
        if(!controls(body))return;
        packet.x=packet.realX=body.getX();packet.y=packet.realY=body.getY();packet.z=packet.realZ=(byte)body.getZi();
        packet.predictionType=0;packet.target=-1;packet.update=(byte)(packet.update&~6);
    }
    public static void reset(){
        for(var body:new ArrayList<>(bodies.keySet())){
            if(!controls(body))continue;
            body.setHealth(0);var grappler=body.getGrappledBy();
            if(grappler!=null)grappler.LetGoOfGrappled("Aborted");else body.die();
        }
        bodies.clear();nextRelay=Long.MIN_VALUE;
    }
}
