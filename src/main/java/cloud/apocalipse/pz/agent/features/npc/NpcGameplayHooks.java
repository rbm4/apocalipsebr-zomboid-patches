package cloud.apocalipse.pz.agent.features.npc;

import cloud.apocalipse.pz.agent.features.npc.pathfind.WalkOnlyMover;
import zombie.network.GameServer;
import zombie.network.fields.IDShort;
import zombie.network.fields.character.*;
import zombie.core.raknet.UdpConnection;
import zombie.pathfind.*;
import zombie.inventory.InventoryItem;
import zombie.core.TradingState;

/** Narrow original-game boundaries. Sender parsing/authorization remains native. */
public final class NpcGameplayHooks {
    public static short freeSlot(){
        for(short n=0;n<Math.min(GameServer.udpEngine.getMaxConnections(),GameServer.SlotToConnection.length);n++)
            if(!ServerHumanPresentationIds.reservesConnectionSlot(n)&&GameServer.SlotToConnection[n]==null)return n;
        return -1;
    }
    public static boolean dataRequest(Object object,Object connection){
        return ServerHumanFactory.ready()&&ServerHumanFactory.handleDataRequest(((IDShort)object).getID(),(UdpConnection)connection);
    }
    public static void resolveTrade(Object packet,Object destination){
        if(ServerHumanFactory.ready()&&tradePacket(packet))ServerHumanTrading.resolveTarget((OnlinePlayer)destination);
    }
    private static boolean tradePacket(Object packet){
        return switch(packet.getClass().getName()){
            case "zombie.network.packets.RequestTradingPacket","zombie.network.packets.AcceptTradingPacket",
                 "zombie.network.packets.RejectTradingPacket","zombie.network.packets.TradingUIAddItemPacket",
                 "zombie.network.packets.TradingUIRemoveItemPacket","zombie.network.packets.TradingUIUpdateStatePacket"->true;
            default->false;
        };
    }
    public static boolean trade(Object packet,Object transport){
        if(!ServerHumanFactory.ready()||!tradePacket(packet))return false;
        var connection=(UdpConnection)transport;
        var sender=((ConnectionPlayer)NpcNativeAccess.get(packet,"sender")).getPlayer();
        var target=((OnlinePlayer)NpcNativeAccess.get(packet,"destination")).getPlayer();
        return switch(packet.getClass().getSimpleName()){
            case "RequestTradingPacket"->ServerHumanTrading.request(sender,target,connection,true);
            case "AcceptTradingPacket","RejectTradingPacket"->ServerHumanTrading.request(sender,target,connection,false);
            case "TradingUIAddItemPacket"->ServerHumanTrading.addItem(sender,target,connection,(InventoryItem)NpcNativeAccess.get(packet,"item"));
            case "TradingUIRemoveItemPacket"->ServerHumanTrading.removeItem(sender,target,connection,(int)NpcNativeAccess.get(packet,"itemId"));
            case "TradingUIUpdateStatePacket"->ServerHumanTrading.state(sender,target,connection,(TradingState)NpcNativeAccess.get(packet,"state"));
            default->false;
        };
    }
    public static boolean walkOnly(Object mover,Object request){
        if(!(NpcNativeAccess.get(request,"mover") instanceof WalkOnlyMover))return false;
        PMMover target=(PMMover)mover;
        target.type=MoverType.Animal;
        target.crawling=false;target.canCrawl=false;target.ignoreCrawlCost=false;target.canThump=false;
        target.canClimbFences=false;target.canClimbTallFences=false;
        target.minLevel=(int)NpcNativeAccess.get(request,"minLevel");target.maxLevel=(int)NpcNativeAccess.get(request,"maxLevel");
        target.allowedChunkLevels=(java.util.ArrayList)NpcNativeAccess.get(request,"allowedChunkLevels");
        target.allowedLevelTransitions=(java.util.ArrayList)NpcNativeAccess.get(request,"allowedLevelTransitions");
        return true;
    }
}
