package zombie.apocalipse.humans;

import java.util.*;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.apocalipse.humans.seating.HumanSeating.Phase;
import zombie.apocalipse.humans.seating.SeatingGeometry;

/** Bounded, generation-qualified vanilla presentation; delivery is not a client animation acknowledgement. */
final class ServerHumanSeatingTransport {
    private static final class Receipt {long epoch,due,retry;int attempts;boolean relevant,entered,idle;}
    private final ServerHuman human;private final ServerHumanSeating seating;
    private final Map<UdpConnection,Receipt> receipts=new IdentityHashMap<>();
    private long clock;
    void clock(long now){clock=Math.max(clock,now);}
    ServerHumanSeatingTransport(ServerHuman human,ServerHumanSeating seating){this.human=human;this.seating=seating;}
    private Receipt receipt(UdpConnection viewer){
        if(!receipts.containsKey(viewer)&&receipts.size()>=256)return null;
        var r=receipts.computeIfAbsent(viewer,v->new Receipt());
        if(r.epoch!=seating.epoch()){r.epoch=seating.epoch();r.entered=r.idle=false;r.attempts=0;}
        return r;
    }
    private void variable(UdpConnection viewer,String key,Object value){INetworkPacket.send(viewer,PacketTypes.PacketType.VariableSync,human.player,key,value);}
    void enter(){
        for(var viewer:human.viewers)if(human.isRelevant(viewer))sendEnter(viewer,true);
    }
    private void sendEnter(UdpConnection viewer,boolean first){
        var r=receipt(viewer);var plan=seating.plan();if(r==null||plan==null||seating.object()==null)return;
        if(first){ServerHumanTransport.seatingPosition(human,viewer,plan.entry());variable(viewer,"SitOnFurnitureStarted",false);}
        variable(viewer,"SitOnFurnitureDirection",plan.side());
        new ServerHumanSeatingPacket(human,seating.object(),plan,false,true).sendToClient(PacketTypes.PacketType.State,viewer);
        r.relevant=r.entered=true;r.idle=false;r.attempts++;r.due=clock+SeatingGeometry.TRANSITION_NANOS;r.retry=clock+2_500_000_000L;
    }
    void settled(){for(var viewer:human.viewers){var r=receipts.get(viewer);if(r!=null&&human.isRelevant(viewer)&&r.entered)idle(viewer,r);}}
    private void idle(UdpConnection viewer,Receipt r){
        variable(viewer,"sittingonfurniture",true);variable(viewer,"SitOnFurnitureStarted",true);variable(viewer,"SitOnFurnitureAnim","Idle");r.idle=true;
        if(seating.plan()!=null)ServerHumanTransport.seatingPosition(human,viewer,seating.plan().seat());
    }
    void announce(UdpConnection viewer){human.requireAuthority();var r=receipt(viewer);if(r!=null)r.relevant=false;}
    boolean movementAllowed(UdpConnection viewer){var r=receipts.get(viewer);return !seating.transitioning()&&(r==null||!r.entered||r.idle);}
    void stand(){
        var plan=seating.plan();if(plan==null)return;
        for(var viewer:human.viewers)if(human.isRelevant(viewer)){
            var r=receipts.get(viewer);if(r==null||!r.entered)continue;
            variable(viewer,"SitOnFurnitureDirection",plan.side());variable(viewer,"getUpQuick",false);
            new ServerHumanSeatingPacket(human,seating.object(),plan,false,false).sendToClient(PacketTypes.PacketType.State,viewer);
            new ServerHumanSeatingPacket(human,seating.object(),plan,true,true).sendToClient(PacketTypes.PacketType.State,viewer);
        }
    }
    void end(boolean abort,boolean reaction){
        var plan=seating.plan();if(plan==null)return;
        for(var viewer:human.viewers)if(human.isRelevant(viewer)){
            var r=receipts.get(viewer);
            if(r!=null&&r.entered){
                if(abort)new ServerHumanSeatingPacket(human,null,plan,false,false).sendToClient(PacketTypes.PacketType.State,viewer);
                if(abort&&!reaction)new ServerHumanSeatingPacket(human,null,plan,true,true).sendToClient(PacketTypes.PacketType.State,viewer);
                else if(!abort)new ServerHumanSeatingPacket(human,seating.object(),plan,true,false).sendToClient(PacketTypes.PacketType.State,viewer);
            }
            variable(viewer,"sittingonfurniture",false);variable(viewer,"SitOnFurnitureStarted",false);variable(viewer,"SitOnFurnitureAnim","");
            if(!reaction)variable(viewer,"forceGetUp",abort);
            ServerHumanTransport.seatingPosition(human,viewer,new zombie.apocalipse.humans.furniture.FurnitureAffordances.Position(human.player.getX(),human.player.getY(),human.player.getZi()));
        }
        receipts.clear();
    }
    void tick(long now){
        human.requireAuthority();clock=Math.max(clock,now);
        receipts.keySet().removeIf(v->!human.viewers.contains(v));
        if(!seating.ownsPose())return;
        boolean stable=seating.phase()==Phase.SEATED||seating.phase()==Phase.WAIT_EXIT;
        for(var viewer:human.viewers){
            var r=receipt(viewer);if(r==null)continue;
            if(!human.isRelevant(viewer)){r.relevant=false;continue;}
            if(!stable)continue;
            if(!r.relevant||!r.entered){sendEnter(viewer,!r.entered);continue;}
            if(!r.idle&&clock>=r.due)idle(viewer,r);
            if(r.attempts<3&&clock>=r.retry)sendEnter(viewer,false);
        }
    }
    void close(){receipts.clear();}
}
