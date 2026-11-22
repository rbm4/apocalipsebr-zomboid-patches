"""Review40 native handshake/identity fields + actual NPC adapter, engine doubles.

Applies the candidate only to temporary sources. No live DB, real journal, Lua UI,
full NPC composition, network transport, or full-game-JAR compilation is executed.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_player_damage_port import fixture_files

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"
DIFF = V / "docs/reports/2026-10-28-merge-40-request-trading.diff"
FILES = fixture_files()
del FILES["DamageTest.java"]
FILES["zombie/UsedFromLua.java"] = "package zombie;public @interface UsedFromLua {}"


def stub(path, body):
    package, name = path.rsplit("/", 1)
    FILES[path + ".java"] = "package " + package.replace("/", ".") + ";\n" + body


stub("zombie/characters/IsoPlayer", r'''public class IsoPlayer {
 public short id;public int index,instance;public float x,y,z;public boolean dead,local=true;public zombie.inventory.ItemContainer inventory=new zombie.inventory.ItemContainer();
 public short getOnlineID(){return id;}public int getID(){return instance;}public String getUsername(){return "user"+id;}public long getSteamID(){return id;}public int getIndex(){return index;}public boolean isLocalPlayer(){return local;}public float getX(){return x;}public float getY(){return y;}public float getZ(){return z;}public boolean isDead(){return dead;}public zombie.inventory.ItemContainer getInventory(){return inventory;}
}''')
stub("zombie/network/GameServer", r'''public class GameServer {
 public static boolean server=true;public static java.util.Map<Short,zombie.characters.IsoPlayer>IDToPlayerMap=new java.util.HashMap<>();public static java.util.Map<zombie.characters.IsoPlayer,zombie.core.raknet.UdpConnection>owners=new java.util.IdentityHashMap<>();public static zombie.core.raknet.UdpConnection getConnectionFromPlayer(zombie.characters.IsoPlayer p){return owners.get(p);}
}''')
stub("zombie/core/raknet/UdpConnection", r'''public class UdpConnection implements zombie.network.IConnection {
 public zombie.characters.IsoPlayer[] players=new zombie.characters.IsoPlayer[4];public boolean connected=true,claims=true;public zombie.characters.IsoPlayer getPlayerAt(int i){return players[i];}public boolean hasPlayer(short id){for(var p:players)if(p!=null&&p.id==id)return claims;return false;}public boolean isFullyConnected(){return connected;}public String getUserName(){return "fixture";}public byte getPlayerIndex(zombie.characters.IsoPlayer p){for(byte i=0;i<4;i++)if(players[i]==p)return i;return -1;}
}''')
stub("zombie/core/TradingManager", "public class TradingManager {public static int additions;public static TradingManager getInstance(){return new TradingManager();}public void addNewTrading(Object a,Object b){additions++;}}")
stub("zombie/core/Core", "public class Core {public static String gameSaveWorld=\"fixture\";}")
stub("zombie/core/znet/SteamUtils", "public class SteamUtils {public static boolean isSteamModeEnabled(){return false;}}")
stub("zombie/debug/DebugLog", "public class DebugLog {public static void log(String s){}}")
stub("zombie/Lua/LuaEventManager", "public class LuaEventManager {public static String event;public static Object[] args;public static void triggerEvent(String name,Object...values){event=name;args=values;}}")
stub("zombie/network/PacketTypes", "public class PacketTypes {public enum PacketType {RequestTrading,AcceptTrading,RejectTrading,TradingUIUpdateState,RemoveInventoryItemFromContainer}}")
stub("zombie/network/packets/INetworkPacket", r'''public interface INetworkPacket {
 void setData(Object...v);void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c);void write(zombie.core.network.ByteBufferWriter b);boolean isConsistent(zombie.network.IConnection c);
 default void processClient(zombie.core.raknet.UdpConnection c){}default void processServer(zombie.network.PacketTypes.PacketType t,zombie.core.raknet.UdpConnection c){}
 public record Sent(zombie.network.PacketTypes.PacketType type,zombie.core.raknet.UdpConnection connection,byte[] bytes,Object[] values){}public static java.util.List<Sent>SENT=new java.util.ArrayList<>();
 default void sendToClient(zombie.network.PacketTypes.PacketType type,zombie.characters.IsoPlayer target){var c=zombie.network.GameServer.getConnectionFromPlayer(target);if(c==null)return;var b=java.nio.ByteBuffer.allocate(32);write(new zombie.core.network.ByteBufferWriter(b));SENT.add(new Sent(type,c,java.util.Arrays.copyOf(b.array(),b.position()),null));}
 static void send(zombie.network.IConnection target,zombie.network.PacketTypes.PacketType type,Object...v){INetworkPacket p=switch(type){case RequestTrading->new RequestTradingPacket();case AcceptTrading->new AcceptTradingPacket();case RejectTrading->new RejectTradingPacket();default->null;};byte[] bytes=new byte[0];if(p!=null){p.setData(v);var b=java.nio.ByteBuffer.allocate(32);p.write(new zombie.core.network.ByteBufferWriter(b));bytes=java.util.Arrays.copyOf(b.array(),b.position());}SENT.add(new Sent(type,(zombie.core.raknet.UdpConnection)target,bytes,v));}
}''')
stub("zombie/inventory/InventoryItem", "public class InventoryItem {public int id;public String type;public ItemContainer container;public InventoryItem(int id,String type){this.id=id;this.type=type;}public int getID(){return id;}public String getFullType(){return type;}public ItemContainer getContainer(){return container;}}")
stub("zombie/inventory/ItemContainer", "public class ItemContainer {public java.util.Map<Integer,InventoryItem>items=new java.util.HashMap<>();public InventoryItem getItemWithID(int id){return items.get(id);}public void add(InventoryItem i){items.put(i.id,i);i.container=this;}public void Remove(InventoryItem i){items.remove(i.id);i.container=null;}}")
stub("zombie/savefile/ApocBRPayloadProvenance", "public class ApocBRPayloadProvenance {public static int configuredServerId(){return 1;}}")
stub("zombie/savefile/ServerPlayerDB", "public class ServerPlayerDB {public static boolean available=true;public static int saves;public static boolean isAvailable(){return available;}public static ServerPlayerDB getInstance(){return new ServerPlayerDB();}public void serverUpdateNetworkCharacter(Object p,int i,Object c){saves++;}}")
stub("zombie/savefile/ApocBRHumanSales", r'''public class ApocBRHumanSales {
 public static boolean available=true,fail;public static java.util.List<Sale>sales=new java.util.ArrayList<>();public record Sale(java.util.UUID id,int server,String world,String user,String steam,int index,String npc,long time,java.util.List<Integer> money,java.util.List<Integer> bundles){}
 public static boolean available(){return available;}public static java.util.UUID enqueue(Sale s){if(fail)throw new IllegalStateException("fixture");sales.add(s);return s.id();}
}''')
stub("zombie/apocalipse/humans/brain/HumanObservation", "public class HumanObservation {public record Identity(short onlineId,int instanceId){}}")
stub("zombie/apocalipse/humans/ServerHuman", r'''final class ServerHuman {
 final zombie.characters.IsoPlayer player;final Profile profile=new Profile();final Activity activity=new Activity();final Actuator actuator=new Actuator();final Definition definition=new Definition();ServerHuman(zombie.characters.IsoPlayer p){player=p;}
 static class Profile {boolean enabled=true;boolean trading(){return enabled;}}
 static class Activity {int begins,finishes;boolean busy,urgent;boolean emergency(Object o){return urgent;}boolean beginTrade(Object i,long n,Object a){if(busy)return false;begins++;return true;}void finishTrade(long n,Object a){finishes++;}void recordingSale(long n,Object a){}}
 static class Actuator {boolean visible=true;boolean face(Object i){return visible;}Object observe(long n){return null;}void say(String s){}}
 static class Definition {String key(){return "fixture-npc";}}
}''')
stub("zombie/apocalipse/humans/ServerHumanFactory", "public class ServerHumanFactory {static java.util.Map<Short,ServerHuman>humans=new java.util.HashMap<>();static ServerHuman tradingHuman(short id){return humans.get(id);}}")
stub("zombie/apocalipse/humans/ServerHumanEncounters", "public class ServerHumanEncounters {static boolean authorized=true;static boolean authorized(Object h,Object v){return authorized;}}")

NATIVE = [
    "zombie/network/fields/character/Player.java",
    "zombie/network/fields/character/ConnectionPlayer.java",
    "zombie/network/fields/character/OnlinePlayer.java",
    "zombie/network/packets/PlayerRequestPacket.java",
    "zombie/characters/Requests.java", "zombie/characters/TradingRequests.java",
    "zombie/core/TradingState.java",
]


def main():
    with tempfile.TemporaryDirectory(prefix="pz-request-trading-") as directory:
        work = Path(directory)
        paths = [line[6:] for line in DIFF.read_text().splitlines() if line.startswith("+++ b/")]
        for name in paths:
            relative = name.removeprefix("42.22.0/src/")
            source = V / "decompiled" / relative
            if not source.exists():
                source = ROOT / "42.21.0/src" / relative
            destination = work / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(source.read_text(encoding="utf-8-sig"), encoding="utf-8", newline="\n")
        subprocess.run(["git", "apply", str(DIFF)], cwd=work, check=True)
        java = [str(work / name) for name in paths]
        files = dict(FILES)
        for relative in NATIVE:
            files[relative] = (V / "decompiled" / relative).read_text(encoding="utf-8-sig")
        for relative in ["zombie/apocalipse/humans/trading/HumanTradeOffer.java", "zombie/apocalipse/humans/ServerHumanPresentationIds.java"]:
            files[relative] = (V / "src" / relative).read_text(encoding="utf-8-sig")
        files["zombie/apocalipse/humans/HandshakeTest.java"] = TEST
        for relative, content in files.items():
            destination = work / "fixture" / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(content, encoding="utf-8")
            java.append(str(destination))
        subprocess.run(["javac", "-d", str(work / "classes"), *java], check=True)
        subprocess.run(["java", "-cp", str(work / "classes"), "zombie.apocalipse.humans.HandshakeTest"], check=True)


TEST = r'''package zombie.apocalipse.humans;
import java.nio.*;import java.util.*;import zombie.characters.*;import zombie.core.*;import zombie.core.raknet.*;import zombie.core.network.*;import zombie.network.*;import zombie.network.fields.character.*;import zombie.network.packets.*;import zombie.inventory.*;import zombie.savefile.*;
public class HandshakeTest {
 static int checks;static IsoPlayer visitor,peer;static ServerHuman human;static UdpConnection connection,other;
 static void check(boolean b){if(!b)throw new AssertionError("check "+checks);checks++;}
 static IsoPlayer player(int id){var p=new IsoPlayer();p.id=(short)id;p.instance=id;return p;}
 static void setup(){GameServer.server=true;GameClient.client=false;for(var old:ServerHumanFactory.humans.values())ServerHumanTrading.close(old);ServerHumanFactory.humans.clear();GameServer.IDToPlayerMap.clear();GameServer.owners.clear();connection=new UdpConnection();other=new UdpConnection();visitor=player(50);peer=player(60);visitor.x=1;connection.players[2]=visitor;other.players[1]=peer;GameServer.IDToPlayerMap.put(visitor.id,visitor);GameServer.IDToPlayerMap.put(peer.id,peer);GameServer.owners.put(visitor,connection);GameServer.owners.put(peer,other);human=new ServerHuman(player(768));ServerHumanFactory.humans.put(human.player.id,human);TradingRequests.getInstance().remove(visitor);TradingRequests.getInstance().remove(peer);TradingManager.additions=0;ApocBRHumanSales.available=true;ApocBRHumanSales.fail=false;ApocBRHumanSales.sales.clear();ServerPlayerDB.available=true;ServerPlayerDB.saves=0;ServerHumanEncounters.authorized=true;INetworkPacket.SENT.clear();}
 static ByteBuffer wire(int slot,int destination){return ByteBuffer.allocate(4).putShort((short)slot).putShort((short)destination).flip();}
 static <T extends INetworkPacket>T parse(T p,int slot,int destination,UdpConnection c){var b=wire(slot,destination);p.parse(new ByteBufferReader(b),c);check(b.position()==4);return p;}
 static <T extends INetworkPacket>T dispatch(T p,int slot,int destination,UdpConnection c,PacketTypes.PacketType t){parse(p,slot,destination,c);if(p.isConsistent(c))p.processServer(t,c);return p;}
 static void open(){dispatch(new RequestTradingPacket(),2,768,connection,PacketTypes.PacketType.RequestTrading);}
 static INetworkPacket.Sent last(){return INetworkPacket.SENT.getLast();}
 public static void main(String[]args){
  setup();var p=parse(new RequestTradingPacket(),2,768,connection);check(p.isConsistent(connection));check(!GameServer.IDToPlayerMap.containsKey((short)768));p.processServer(PacketTypes.PacketType.RequestTrading,connection);check(human.activity.begins==1);check(TradingManager.additions==0);check(!TradingRequests.getInstance().response(visitor,human.player));check(last().type()==PacketTypes.PacketType.AcceptTrading&&last().connection()==connection);check(Arrays.equals(last().bytes(),new byte[]{3,0,0,50}));
  var money=new InventoryItem(11,"Base.Money");visitor.inventory.add(money);check(ServerHumanTrading.addItem(visitor,human.player,connection,money));check(ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasSealed));open();check(human.activity.begins==1&&last().type()==PacketTypes.PacketType.AcceptTrading);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasFinalized);check(ApocBRHumanSales.sales.size()==1&&visitor.inventory.items.isEmpty()&&ServerPlayerDB.saves==1);check(ApocBRHumanSales.sales.getFirst().index()==2);check(human.activity.finishes==1);
  // Packet reuse clears the old destination before a different ID is parsed.
  setup();p=parse(new RequestTradingPacket(),2,768,connection);check(p.isConsistent(connection));parse(p,2,769,connection);check(!p.isConsistent(connection));check(human.activity.begins==0);
  for(int slot:new int[]{-1,0,1,3,4,50,768,1024}){setup();p=dispatch(new RequestTradingPacket(),slot,768,connection,PacketTypes.PacketType.RequestTrading);check(!p.isConsistent(connection));check(human.activity.begins==0&&INetworkPacket.SENT.isEmpty());}
  setup();p=parse(new RequestTradingPacket(),2,768,null);check(!p.isConsistent(null));
  // Session intake requires real ownership, not only a resolvable sender slot.
  for(int mode=0;mode<5;mode++){setup();p=parse(new RequestTradingPacket(),2,768,connection);switch(mode){case 0->connection.claims=false;case 1->connection.connected=false;case 2->GameServer.owners.put(visitor,other);case 3->GameServer.IDToPlayerMap.put(visitor.id,player(50));case 4->{p.processServer(PacketTypes.PacketType.RequestTrading,other);check(human.activity.begins==0);continue;}}p.processServer(PacketTypes.PacketType.RequestTrading,connection);check(human.activity.begins==0&&INetworkPacket.SENT.isEmpty());}
  // Native requester is sender-scoped; another global player's ID is not a slot.
  setup();p=dispatch(new RequestTradingPacket(),60,768,connection,PacketTypes.PacketType.RequestTrading);check(!p.isConsistent(connection));check(human.activity.begins==0);
  // Range/profile/encounter/emergency/persistence gates retain rejection replies.
  for(int mode=0;mode<8;mode++){setup();switch(mode){case 0->human.profile.enabled=false;case 1->ServerHumanEncounters.authorized=false;case 2->human.activity.urgent=true;case 3->visitor.x=5;case 4->human.actuator.visible=false;case 5->ApocBRHumanSales.available=false;case 6->ServerPlayerDB.available=false;case 7->human.activity.busy=true;}open();check(human.activity.begins==0);check(last().type()==PacketTypes.PacketType.RejectTrading);check(last().bytes().length==4);}
  // Busy NPC rejects another owner without replacing the original session.
  setup();open();peer.x=1;dispatch(new RequestTradingPacket(),1,768,other,PacketTypes.PacketType.RequestTrading);check(last().type()==PacketTypes.PacketType.RejectTrading);check(human.activity.begins==1&&human.activity.finishes==0);
  // Both old non-Ask actions remain cancellation, never native NPC trade creation.
  for(boolean accept:new boolean[]{true,false}){setup();open();dispatch(accept?new AcceptTradingPacket():new RejectTradingPacket(),2,768,connection,accept?PacketTypes.PacketType.AcceptTrading:PacketTypes.PacketType.RejectTrading);check(human.activity.finishes==1);check(TradingManager.additions==0);check(last().type()==PacketTypes.PacketType.TradingUIUpdateState);}
  setup();open();dispatch(new RejectTradingPacket(),1,768,other,PacketTypes.PacketType.RejectTrading);check(human.activity.finishes==0&&TradingManager.additions==0);
  setup();open();ServerHumanTrading.update(human,Long.MAX_VALUE);check(human.activity.finishes==1&&last().type()==PacketTypes.PacketType.TradingUIUpdateState);
  // Queue failure is before inventory consumption in the unchanged sales path.
  setup();open();money=new InventoryItem(12,"Base.Money");visitor.inventory.add(money);ServerHumanTrading.addItem(visitor,human.player,connection,money);ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasSealed);ApocBRHumanSales.fail=true;ServerHumanTrading.state(visitor,human.player,connection,TradingState.DealWasFinalized);check(visitor.inventory.items.size()==1&&ApocBRHumanSales.sales.isEmpty()&&ServerPlayerDB.saves==0);
  // Human handshake uses actual Requests pending-response tracking and native events.
  setup();dispatch(new AcceptTradingPacket(),1,50,other,PacketTypes.PacketType.AcceptTrading);check(TradingManager.additions==0&&INetworkPacket.SENT.isEmpty());dispatch(new RequestTradingPacket(),2,60,connection,PacketTypes.PacketType.RequestTrading);check(last().type()==PacketTypes.PacketType.RequestTrading&&last().connection()==other);dispatch(new AcceptTradingPacket(),1,50,other,PacketTypes.PacketType.AcceptTrading);check(TradingManager.additions==1&&last().connection()==connection);dispatch(new AcceptTradingPacket(),1,50,other,PacketTypes.PacketType.AcceptTrading);check(TradingManager.additions==1);
  setup();dispatch(new RequestTradingPacket(),2,60,connection,PacketTypes.PacketType.RequestTrading);dispatch(new RejectTradingPacket(),1,50,other,PacketTypes.PacketType.RejectTrading);check(TradingManager.additions==0&&last().type()==PacketTypes.PacketType.RejectTrading);check(!TradingRequests.getInstance().response(visitor,peer));
  // No detached NPC may be treated as a real requesting connection identity.
  setup();connection.players[0]=human.player;dispatch(new RequestTradingPacket(),0,60,connection,PacketTypes.PacketType.RequestTrading);check(TradingManager.additions==0&&INetworkPacket.SENT.isEmpty());
  // Native client decoding of the actual NPC reply, with reserved ID and event order.
  setup();open();var accepted=last().bytes();GameServer.server=false;GameClient.client=true;GameClient.IDToPlayerMap.clear();GameClient.IDToPlayerMap.put(human.player.id,human.player);GameClient.IDToPlayerMap.put(visitor.id,visitor);var reply=new AcceptTradingPacket();reply.parse(new ByteBufferReader(ByteBuffer.wrap(accepted)),null);check(reply.isConsistent(null));reply.processClient(null);check(zombie.Lua.LuaEventManager.event.equals("AcceptedTrade"));check(zombie.Lua.LuaEventManager.args[0]==human.player&&zombie.Lua.LuaEventManager.args[1]==visitor&&zombie.Lua.LuaEventManager.args[2].equals(true));var reject=new RejectTradingPacket();reject.parse(new ByteBufferReader(ByteBuffer.wrap(accepted)),null);reject.processClient(null);check(zombie.Lua.LuaEventManager.args[2].equals(false));
  System.out.println("Trade handshake candidate: "+checks+" assertions passed (native identity/requests, NPC adapter, engine doubles)");
 }
}'''


if __name__ == "__main__":
    main()
