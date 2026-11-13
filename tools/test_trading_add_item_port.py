"""Review41 full packet + applied NPC adapter with native fields and engine doubles.

Inventory encoding/journal/Lua are doubles. Native TradingManager pair/item methods
are extracted exactly; full manager persistence and the game runtime are excluded.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
import test_request_trading_port as trade

ROOT, V = trade.ROOT, trade.V
DIFF = V / "docs/reports/2026-10-28-merge-41-trading-add-item.diff"
REL = "zombie/network/packets/TradingUIAddItemPacket.java"


def method(source, signature):
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def main(extra_diff=None, harness=None, extra_relative=None, transform_files=None):
    with tempfile.TemporaryDirectory(prefix="pz-trade-add-item-") as directory:
        work = Path(directory)
        destination = work / "42.22.0/src" / REL
        destination.parent.mkdir(parents=True)
        destination.write_text((V / "decompiled" / REL).read_text(encoding="utf-8-sig"), encoding="utf-8", newline="\n")
        subprocess.run(["git", "apply", str(DIFF)], cwd=work, check=True)
        files = dict(trade.FILES)
        files["zombie/network/PacketTypes.java"] = files["zombie/network/PacketTypes.java"].replace("RequestTrading,", "TradingUIAddItem,TradingUIRemoveItem,RequestTrading,")
        files["zombie/debug/DebugType.java"] = files["zombie/debug/DebugType.java"].replace("DebugType Multiplayer=", "DebugType General=new DebugType();public static final DebugType Multiplayer=").replace("public void error", "public void printException(Exception e,LogSeverity l){}public void error")
        files["zombie/inventory/InventoryItem.java"] = files["zombie/inventory/InventoryItem.java"].rstrip()[:-1] + r'''
          public static int loadVersion,loads,saves;
          public static InventoryItem loadItem(java.nio.ByteBuffer b,int version){loads++;loadVersion=version;return new InventoryItem(b.getInt(),"Base.Mock");}
          public void saveWithSize(java.nio.ByteBuffer b,boolean unused)throws java.io.IOException{saves++;b.putInt(id);}
        }'''
        native_manager = (V / "decompiled/zombie/core/TradingManager.java").read_text(encoding="utf-8-sig")
        files["zombie/core/TradingManager.java"] = r'''package zombie.core;
        import java.util.*;import zombie.characters.IsoPlayer;import zombie.inventory.InventoryItem;
        public class TradingManager {
          public static int additions;private static final TradingManager instance=new TradingManager();
          private final List<Trading>tradings=new ArrayList<>();public static TradingManager getInstance(){return instance;}
          public static void reset(){instance.tradings.clear();}public static int pairs(){return instance.tradings.size();}
          public static int items(){return instance.tradings.stream().mapToInt(t->t.playerAItems.size()+t.playerBItems.size()).sum();}
          private static class Trading {IsoPlayer playerA,playerB;List<InventoryItem>playerAItems=new ArrayList<>(),playerBItems=new ArrayList<>();Trading(IsoPlayer a,IsoPlayer b){playerA=a;playerB=b;}void resetAgreements(){}}
        ''' + "\n".join(method(native_manager, signature) for signature in [
            "public void addNewTrading(", "public boolean isInvalid(", "public boolean addItem(", "public boolean removeItem(",
        ]) + "}"
        for relative in trade.NATIVE:
            files[relative] = (V / "decompiled" / relative).read_text(encoding="utf-8-sig")
        for relative in [
            "zombie/network/packets/RequestTradingPacket.java", "zombie/network/packets/AcceptTradingPacket.java",
            "zombie/network/packets/RejectTradingPacket.java", "zombie/apocalipse/humans/ServerHumanTrading.java",
            "zombie/apocalipse/humans/trading/HumanTradeOffer.java", "zombie/apocalipse/humans/ServerHumanPresentationIds.java",
        ]:
            files[relative] = (V / "src" / relative).read_text(encoding="utf-8-sig")
        prefix = trade.TEST.split(" public static void main(String[]args){")[0]
        files["zombie/apocalipse/humans/HandshakeTest.java"] = prefix + (TEST if harness is None else harness) + "\n}"
        java = [str(destination)]
        if extra_diff is not None:
            extra_rel = extra_relative or "zombie/network/packets/TradingUIRemoveItemPacket.java"
            extra = work / "42.22.0/src" / extra_rel
            extra.write_text((V / "decompiled" / extra_rel).read_text(encoding="utf-8-sig"), encoding="utf-8", newline="\n")
            subprocess.run(["git", "apply", str(extra_diff)], cwd=work, check=True)
            java.append(str(extra))
        if transform_files is not None:
            transform_files(files)
        for relative, content in files.items():
            path = work / "fixture" / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            java.append(str(path))
        subprocess.run(["javac", "-d", str(work / "classes"), *java], check=True)
        subprocess.run(["java", "-cp", str(work / "classes"), "zombie.apocalipse.humans.HandshakeTest"], check=True)


TEST = r'''
 static void reset(){setup();TradingManager.reset();}
 static InventoryItem item(int id){var i=new InventoryItem(id,"Base.Money");visitor.inventory.add(i);return i;}
 static TradingUIAddItemPacket add(TradingUIAddItemPacket packet,int slot,int target,int item,UdpConnection c){var b=ByteBuffer.allocate(8).putShort((short)slot).putShort((short)target).putInt(item).flip();packet.parse(new ByteBufferReader(b),c);check(b.position()==8);if(packet.isConsistent(c))packet.processServer(PacketTypes.PacketType.TradingUIAddItem,c);return packet;}
 public static void main(String[]args){
  reset();open();item(10);var packet=add(new TradingUIAddItemPacket(),2,768,10,connection);check(packet.isConsistent(connection));check(TradingManager.pairs()==0&&TradingManager.items()==0);check(last().type()==PacketTypes.PacketType.TradingUIUpdateState);check(visitor.inventory.items.size()==1&&ApocBRHumanSales.sales.isEmpty());ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasSealed);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasFinalized);check(ApocBRHumanSales.sales.size()==1&&visitor.inventory.items.isEmpty());check(ApocBRHumanSales.sales.getFirst().money().equals(List.of(10)));
  // Missing IDs clear a reused packet and fail native consistency before the adapter.
  reset();open();item(11);packet=add(new TradingUIAddItemPacket(),2,768,11,connection);add(packet,2,768,999,connection);check(!packet.isConsistent(connection));check(human.activity.finishes==0);check(visitor.inventory.items.size()==1);
  reset();item(12);packet=add(new TradingUIAddItemPacket(),2,768,12,connection);check(packet.isConsistent(connection));check(TradingManager.pairs()==0&&ApocBRHumanSales.sales.isEmpty());
  // All four valid sender slots resolve the exact local owner and its inventory.
  for(int slot=0;slot<4;slot++){reset();connection.players=new IsoPlayer[4];connection.players[slot]=visitor;dispatch(new RequestTradingPacket(),slot,768,connection,PacketTypes.PacketType.RequestTrading);item(13);packet=add(new TradingUIAddItemPacket(),slot,768,13,connection);check(packet.isConsistent(connection));check(human.activity.begins==1&&human.activity.finishes==0);}
  for(int bad:new int[]{-1,0,1,3,4,50,768,1024}){reset();open();item(14);packet=add(new TradingUIAddItemPacket(),bad,768,14,connection);check(!packet.isConsistent(connection));check(human.activity.finishes==0&&visitor.inventory.items.size()==1);}
  // A globally known foreign player's item cannot be selected using an ID as a slot.
  reset();open();var theirs=new InventoryItem(15,"Base.Money");peer.inventory.add(theirs);packet=add(new TradingUIAddItemPacket(),2,768,15,connection);check(!packet.isConsistent(connection));check(peer.inventory.items.size()==1&&ApocBRHumanSales.sales.isEmpty());
  reset();open();item(16);packet=add(new TradingUIAddItemPacket(),2,769,16,connection);check(!packet.isConsistent(connection));check(human.activity.finishes==0);
  reset();open();item(17);connection.claims=false;add(new TradingUIAddItemPacket(),2,768,17,connection);check(human.activity.finishes==0&&ApocBRHumanSales.sales.isEmpty());
  // An object in the inventory map with a foreign container is rejected by the real adapter.
  reset();open();var moved=item(18);moved.container=peer.inventory;add(new TradingUIAddItemPacket(),2,768,18,connection);check(human.activity.finishes==1);check(ApocBRHumanSales.sales.isEmpty());
  // Editing a sealed offer invalidates consent; this packet never consumes inventory.
  reset();open();item(19);item(20);add(new TradingUIAddItemPacket(),2,768,19,connection);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasSealed);add(new TradingUIAddItemPacket(),2,768,20,connection);check(visitor.inventory.items.size()==2);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasFinalized);check(visitor.inventory.items.size()==2&&ApocBRHumanSales.sales.isEmpty());
  // Ordinary peer trades retain the extracted native pair check and item insertion.
  reset();item(21);packet=add(new TradingUIAddItemPacket(),2,60,21,connection);check(packet.isConsistent(connection));check(TradingManager.items()==0&&INetworkPacket.SENT.isEmpty());dispatch(new RequestTradingPacket(),2,60,connection,PacketTypes.PacketType.RequestTrading);dispatch(new AcceptTradingPacket(),1,50,other,PacketTypes.PacketType.AcceptTrading);check(TradingManager.pairs()==1);add(packet,2,60,21,connection);check(TradingManager.items()==1&&last().type()==PacketTypes.PacketType.TradingUIAddItem);check(last().connection()==other&&visitor.inventory.items.size()==1);
  var stranger=player(70);GameServer.IDToPlayerMap.put(stranger.id,stranger);add(packet,2,70,21,connection);check(TradingManager.items()==1);
  // Retain native asymmetric encoding: client sends item ID, server sends item data.
  reset();var offered=item(22);GameServer.server=false;GameClient.client=true;visitor.index=2;packet=new TradingUIAddItemPacket();packet.setData(visitor,peer,22);var b=ByteBuffer.allocate(8);packet.write(new ByteBufferWriter(b));b.flip();check(b.getShort()==2&&b.getShort()==60&&b.getInt()==22);check(b.remaining()==0);
  GameServer.server=true;GameClient.client=false;packet.parse(new ByteBufferReader(ByteBuffer.allocate(8).putShort((short)2).putShort((short)60).putInt(22).flip()),connection);b=ByteBuffer.allocate(32);int saves=InventoryItem.saves;packet.write(new ByteBufferWriter(b));check(InventoryItem.saves==saves+1);check(b.position()==8);b.flip();check(b.getShort()==50&&b.getShort()==60);b.position(0);
  GameServer.server=false;GameClient.client=true;GameClient.IDToPlayerMap.put(visitor.id,visitor);GameClient.IDToPlayerMap.put(peer.id,peer);var received=new TradingUIAddItemPacket();received.parse(new ByteBufferReader(b),null);check(received.isConsistent(null));check(InventoryItem.loadVersion==251);received.processClient(null);check(zombie.Lua.LuaEventManager.event.equals("TradingUIAddItem"));check(zombie.Lua.LuaEventManager.args[0]==visitor&&zombie.Lua.LuaEventManager.args[1]==peer&&((InventoryItem)zombie.Lua.LuaEventManager.args[2]).id==22);
  System.out.println("Trading add-item candidate: "+checks+" assertions passed (native fields/pair methods, actual NPC offer adapter, engine doubles)");
 }
'''


if __name__ == "__main__":
    main()
