package cloud.apocalipse.pz.agent.features.npc;

import java.util.*;
import cloud.apocalipse.pz.agent.features.npc.zombies.*;
import zombie.characters.IsoZombie;
import zombie.core.raknet.UdpConnection;
import zombie.popman.NetworkZombieManager;
import zombie.network.packets.character.*;

/** Preserves native ownership lists and packet bookkeeping; adds only NPC authority/overlay. */
public final class NpcZombieHooks {
    private static List<IsoZombie> relays=List.of();
    public static boolean auth(Object object){
        if(!ServerHumanFactory.ready())return false;
        var zombie=(IsoZombie)object;
        if(ServerCorpseDragAuthority.controls(zombie)){
            if(zombie.getOwner()!=null)NetworkZombieManager.getInstance().moveZombie(zombie,null,null);
            return true;
        }
        return ServerZombieController.getInstance().retainOrAcquire(zombie);
    }
    public static boolean move(Object object,Object owner){
        return ServerHumanFactory.ready()&&owner!=null&&controlled((IsoZombie)object);
    }
    private static boolean controlled(IsoZombie z){return ServerZombieController.getInstance().controls(z)||ServerCorpseDragAuthority.controls(z);}
    public static void beginFrame(){
        relays=List.of();if(ServerHumanFactory.ready())ServerZombieController.getInstance().beginFrame();
    }
    public static void endFrame(){
        if(!ServerHumanFactory.ready())return;
        var result=new LinkedHashSet<IsoZombie>();
        ServerZombieController.getInstance().addRelays(result);ServerCorpseDragAuthority.addRelays(result);relays=List.copyOf(result);
    }
    public static void received(Object packer,Object zombie){
        if(ServerHumanFactory.ready())ServerZombieController.getInstance().received((IsoZombie)zombie,(ZombiePacket)NpcNativeAccess.get(packer,"packet"));
    }
    public static void prepare(Object packet,Object object){
        if(!ServerHumanFactory.ready())return;
        var zombie=(IsoZombie)object;
        ServerZombieController.getInstance().prepare(zombie,(ZombiePacket)packet);ServerCorpseDragAuthority.prepare(zombie,(ZombiePacket)packet);
    }
    public static int append(Object packer,Object viewer,Object outbound){
        if(!ServerHumanFactory.ready())return 0;
        var connection=(UdpConnection)viewer;var packet=(ZombieSynchronizationPacket)outbound;int count=0;
        if(connection==null||!connection.isFullyConnected())return 0;
        var ids=(Map<UdpConnection,List<Short>>)NpcNativeAccess.get(packer,"zombiesToSend");
        for(var z:relays){
            if(packet.sendQueue.size()>=300)break;
            if(!controlled(z)||z.onlineId==-1||z.getOwner()==connection||packet.sendQueue.contains(z)
                ||!connection.RelevantTo(z.getX(),z.getY(),(connection.getRelevantRange()-2)*10))continue;
            z.zombiePacket.set(z);prepare(z.zombiePacket,z);packet.sendQueue.add(z);
            ids.computeIfAbsent(connection,k->new ArrayList<>()).add(z.getOnlineID());z.zombiePacketUpdated=false;count++;
        }
        return count;
    }
    public static void hit(Object object){if(ServerHumanFactory.ready()&&object instanceof IsoZombie zombie)ServerZombieController.getInstance().hit(zombie);}
    static void reset(){relays=List.of();ServerZombieController.getInstance().reset();ServerCorpseDragAuthority.reset();}
}
