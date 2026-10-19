"""Exercise the production 42.21.0 human factory/lifecycle/transport with engine stubs.

No native game initialization or deployment. The real-JAR dry run separately checks
engine signatures; this fixture checks lifecycle and outbound packet field contracts.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FILES = {
    "zombie/iso/IsoDirections.java": """package zombie.iso;
public enum IsoDirections { N,NW,W,SW,S,SE,E,NE }
""",
    "zombie/iso/IsoCell.java": """package zombie.iso;
import java.util.*; import zombie.characters.IsoPlayer;
public class IsoCell {
 public final List<IsoPlayer> add=new ArrayList<>(),objects=new ArrayList<>();
 public List<IsoPlayer> getAddList(){return add;}
 public List<IsoPlayer> getObjectList(){return objects;}
}
""",
    "zombie/iso/IsoWorld.java": """package zombie.iso;
public class IsoWorld {
 public static IsoWorld instance=new IsoWorld(); public IsoCell currentCell=new IsoCell();
}
""",
    "zombie/characters/SurvivorDesc.java": """package zombie.characters;
public class SurvivorDesc {
 public Object instance; public boolean female; public String name,outfit;
 public SurvivorDesc(boolean ignored){}
 public void setFemale(boolean v){female=v;} public void setForename(String v){name=v;}
 public void setSurname(String v){} public void setInstance(Object v){instance=v;}
 public void dressInNamedOutfit(String v){outfit=v;}
 public Visual getHumanVisual(){return new Visual();}
 public static class Visual {
  public void setSkinTextureIndex(int v){} public void setHairModel(String v){}
  public void setBeardModel(String v){}
 }
}
""",
    "zombie/characters/IsoPlayer.java": """package zombie.characters;
import zombie.iso.*;
public class IsoPlayer {
 public boolean remote,god,invisible,ghost,detached; public int playerIndex;
 public short id; public float realx,realy,x,y,z,idleSpeed; public byte realz;
 public String username,displayName; public IsoDirections facing;
 private final IsoCell cell; private final SurvivorDesc descriptor;
 private final Emitter emitter=new Emitter();
 public IsoPlayer(IsoCell c,SurvivorDesc d,int x,int y,int z){
  cell=c;descriptor=d;d.instance=this;c.add.add(this);c.objects.add(this);
 }
 public void removeFromSquare(){detached=true;} public IsoCell getCell(){return cell;}
 public void setOnlineID(short v){id=v;} public short getOnlineID(){return id;}
 public void setUsername(String v){username=v;} public void setDisplayName(String v){displayName=v;}
 public void setGodMod(boolean v,boolean forced){god=v;}
 public void setInvisible(boolean v,boolean forced){invisible=v;}
 public void setGhostMode(boolean v,boolean forced){ghost=v;}
 public void setX(float v){x=v;} public void setY(float v){y=v;} public void setZ(float v){z=v;}
 public void setForwardIsoDirection(IsoDirections v){facing=v;}
 public float getDirectionAngleRadians(){return facing.ordinal()*.5f;}
 public void setVariable(String k,float v){idleSpeed=v;}
 public SurvivorDesc getDescriptor(){return descriptor;} public Emitter getEmitter(){return emitter;}
 public static class Emitter {public boolean stopped; public void stopAll(){stopped=true;}}
}
""",
    "zombie/core/raknet/UdpConnection.java": """package zombie.core.raknet;
public class UdpConnection {
 public boolean connected=true,relevant=true; public int announced,removed;
 public boolean isFullyConnected(){return connected;}
 public boolean isRelevantTo(float x,float y){return relevant;}
}
""",
    "zombie/debug/DebugLog.java": """package zombie.debug;
public class DebugLog {public static void log(String value){}}
""",
    "zombie/network/GameServer.java": """package zombie.network;
import java.util.*;import zombie.characters.IsoPlayer;import zombie.core.raknet.UdpConnection;
public class GameServer {
 public static boolean server=true;
 public static final Map<Short,IsoPlayer> IDToPlayerMap=new HashMap<>();
 public static final Map<Short,Long> IDToAddressMap=new HashMap<>();
 public static Engine udpEngine=new Engine();
 public static void sendPlayerConnected(IsoPlayer p,UdpConnection c){c.announced++;}
 public static class Engine {public final List<UdpConnection> connections=new ArrayList<>();}
}
""",
    "zombie/network/PacketTypes.java": """package zombie.network;
public class PacketTypes {public enum PacketType {PlayerUpdateReliable,PlayerTimeout}}
""",
    "zombie/network/packets/INetworkPacket.java": """package zombie.network.packets;
import zombie.network.PacketTypes;import zombie.core.raknet.UdpConnection;
public interface INetworkPacket {
 static void send(UdpConnection c,PacketTypes.PacketType t,Object... args){
  if(t!=PacketTypes.PacketType.PlayerTimeout)throw new AssertionError();c.removed++;
 }
}
""",
    "zombie/network/packets/character/PlayerPacketReliable.java": """package zombie.network.packets.character;
import java.util.*;import zombie.characters.IsoPlayer;import zombie.core.raknet.UdpConnection;
import zombie.network.PacketTypes;
public class PlayerPacketReliable {
 public static final List<PlayerPacketReliable> sent=new ArrayList<>();
 public static final List<UdpConnection> recipients=new ArrayList<>();
 public final ID id=new ID(); public final Prediction prediction=new Prediction();
 public final Vehicle hitVehicleId=new Vehicle(); public final Variables variables=new Variables();
 public short booleanVariables;public boolean disconnected;
 public void sendToClient(PacketTypes.PacketType t,UdpConnection c){
  if(t!=PacketTypes.PacketType.PlayerUpdateReliable)throw new AssertionError();sent.add(this);recipients.add(c);
 }
 public static class ID {public IsoPlayer player;public byte index;
  public void set(IsoPlayer p){player=p;} public void setPlayerIndex(byte v){index=v;}}
 public static class Prediction {
  public byte type,z,distance;public float x,y,direction,moveDirection,speed;
  public final Position position=new Position();
 }
 public static class Position {public float x,y,z;public void set(float a,float b,float c){x=a;y=b;z=c;}}
 public static class Vehicle {public int id=0;public void set(Object v){id=-1;}}
 public static class Variables {public IsoPlayer player;public void set(IsoPlayer p){player=p;}}
}
""",
    "zombie/apocalipse/humans/FactoryTest.java": """package zombie.apocalipse.humans;
import java.util.concurrent.atomic.AtomicReference;
import zombie.characters.*;import zombie.core.raknet.UdpConnection;import zombie.iso.*;
import zombie.network.GameServer;import zombie.network.packets.character.PlayerPacketReliable;
public class FactoryTest {
 static int checks;
 static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
 static void rejects(Runnable action,String message){
  boolean rejected=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException e){rejected=true;}
  check(rejected,message);
 }
 static ServerHumanDefinition def(String key,float x){
  return new ServerHumanDefinition(key,"__"+key,"Guide",x,200.75f,0,IsoDirections.E,false,"Tourist");
 }
 static PlayerPacketReliable last(){return PlayerPacketReliable.sent.getLast();}
 public static void main(String[] args)throws Exception {
  System.clearProperty("apocbr.guide.enabled");
  check(ServerHumanDefinition.configuredGuide()==null,"disabled by default");
  System.setProperty("apocbr.guide.enabled","true");
  ServerHumanDefinition defaults=ServerHumanDefinition.configuredGuide();
  check(defaults.x()==14294&&defaults.y()==11862&&defaults.z()==0,"requested default guide coordinates");
  check(defaults.displayName().equals("Apocalipse Guide"),"default guide name");
  System.setProperty("apocbr.guide.x","100.5");System.setProperty("apocbr.guide.y","200.75");
  check(ServerHumanDefinition.configuredGuide().x()==100.5f,"configured position");
  System.clearProperty("apocbr.guide.enabled");
  rejects(()->def("bad",Float.NaN),"NaN rejected");rejects(()->def("bad",Float.POSITIVE_INFINITY),"infinity rejected");
  rejects(()->def("bad",-1),"negative position rejected");rejects(()->def("",1),"empty key rejected");
  ServerHumanFactory f=ServerHumanFactory.getInstance();
  rejects(()->f.spawn(def("early",1)),"no construction before simulation initialization");
  f.tick(0);
  GameServer.IDToAddressMap.put((short)ServerHumanFactory.FIRST_ID,1L);
  short id=f.spawn(def("guide",100.5f));
  check(id==ServerHumanFactory.FIRST_ID+1,"allocated/reserved IDs skipped");
  check(GameServer.IDToPlayerMap.isEmpty(),"presentation actor not a logged-in player");
  check(IsoWorld.instance.currentCell.add.isEmpty()&&IsoWorld.instance.currentCell.objects.isEmpty(),"constructor additions detached");
  rejects(()->f.spawn(def("guide",100)),"duplicate key rejected");
  rejects(()->f.spawn(new ServerHumanDefinition("other","__guide","Other",1,2,0,IsoDirections.N,false,"Tourist")),"duplicate username rejected");
  AtomicReference<Throwable> failure=new AtomicReference<>();
  Thread worker=new Thread(()->{try{f.remove("guide");}catch(Throwable e){failure.set(e);}});worker.start();worker.join();
  check(failure.get() instanceof IllegalStateException,"worker cannot mutate engine state");
  UdpConnection a=new UdpConnection(),b=new UdpConnection(),outside=new UdpConnection(),loading=new UdpConnection();
  outside.relevant=false;loading.connected=false;
  GameServer.udpEngine.connections.add(a);GameServer.udpEngine.connections.add(b);
  GameServer.udpEngine.connections.add(outside);GameServer.udpEngine.connections.add(loading);
  f.tick(ServerHumanFactory.UPDATE_NANOS);
  check(a.announced==1&&b.announced==1&&outside.announced==0&&loading.announced==0,"announce only relevant fully connected clients");
  check(PlayerPacketReliable.sent.size()==2,"one heartbeat per viewer");
  PlayerPacketReliable packet=last();IsoPlayer actor=packet.id.player;
  check(actor.remote&&actor.playerIndex==-1&&actor.god&&!actor.invisible&&!actor.ghost,"visible remote protected actor");
  check(actor.detached&&actor.getDescriptor().instance==actor,"descriptor retained but square detached");
  check(packet.id.index==-1&&actor.id==id,"online identity uses remote sentinel");
  check(packet.prediction.x==100.5f&&packet.prediction.y==200.75f&&packet.prediction.z==0,"exact configured packet position");
  check(packet.prediction.direction==actor.getDirectionAngleRadians(),"configured facing");
  check(packet.prediction.type==0&&packet.prediction.speed==0&&packet.prediction.distance==0,"static prediction has no extrapolation");
  check(packet.prediction.position.x==packet.prediction.x&&packet.prediction.position.y==packet.prediction.y,"prediction world position initialized");
  check(packet.booleanVariables==0&&!packet.disconnected&&packet.hitVehicleId.id==-1,"idle flags, connected, no collision vehicle");
  check(packet.variables.player==actor&&actor.idleSpeed==1,"vanilla idle variables use actor");
  f.tick(ServerHumanFactory.UPDATE_NANOS+1);check(PlayerPacketReliable.sent.size()==2,"cadence bounded");
  f.tick(2*ServerHumanFactory.UPDATE_NANOS);check(a.announced==1&&PlayerPacketReliable.sent.size()==4,"heartbeat continues without repeating appearance");
  a.relevant=false;f.tick(3*ServerHumanFactory.UPDATE_NANOS);check(a.removed==1,"leaving relevance removes client actor");
  a.relevant=true;f.tick(4*ServerHumanFactory.UPDATE_NANOS);check(a.announced==2,"returning viewer gets appearance again");
  GameServer.udpEngine.connections.remove(b);f.tick(5*ServerHumanFactory.UPDATE_NANOS);
  check(b.removed==0,"disconnected transport not used");
  UdpConnection replacement=new UdpConnection();GameServer.udpEngine.connections.add(replacement);
  f.tick(6*ServerHumanFactory.UPDATE_NANOS);check(replacement.announced==1,"reconnected client gets appearance");
  check(ServerHumanFactory.handleDataRequest(id,a)&&a.announced==3,"reload requests reannounce known actor");
  int before=outside.announced;check(ServerHumanFactory.handleDataRequest(id,outside)&&outside.announced==before,"out-of-range request does not disclose actor");
  check(!ServerHumanFactory.handleDataRequest((short)42,a),"ordinary IDs fall through to vanilla");
  check(f.remove("guide")&&a.removed==2&&replacement.removed==1,"explicit removal notifies viewers");
  check(actor.getDescriptor().instance==null&&actor.getEmitter().stopped,"removal releases descriptor and sounds");
  check(!f.remove("guide")&&!ServerHumanFactory.handleDataRequest(id,a),"removed actor no longer resolves");
  short next=f.spawn(def("next",1));check(next>id,"removed online IDs not recycled");f.remove("next");
  int count=0;while(true){try{String key="n"+count;f.spawn(def(key,1));f.remove(key);count++;}catch(IllegalStateException expected){break;}}
  check(count>0&&count<64,"ID budget exhausted safely without touching live-player slots");
  GameServer.server=false;rejects(()->f.remove("missing"),"client cannot operate factory");
  check(!ServerHumanFactory.handleDataRequest(id,a),"client data request bypasses server factory");
  System.out.println("Passed "+checks+" server-human lifecycle and packet-field assertions.");
 }
}
""",
}


def main():
    # Preserve the entire vanilla request handler after the one intentional fallback.
    relative = Path("zombie/network/packets/character/PlayerDataRequestPacket.java")
    vanilla = (ROOT / "42.21.0/decompiled" / relative).read_text()
    patched = (ROOT / "42.21.0/src" / relative).read_text()
    hook = "        if (zombie.apocalipse.humans.ServerHumanFactory.handleDataRequest(this.getID(), connection)) return;\n"
    assert patched.count(hook) == 1 and patched.replace(hook, "") == vanilla
    with tempfile.TemporaryDirectory(prefix="apocbr-human-tests-") as directory:
        work = Path(directory)
        sources = []
        for relative, content in FILES.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(path)
        sources.extend(sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans").glob("*.java")))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources))
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.apocalipse.humans.FactoryTest"], check=True)


if __name__ == "__main__":
    main()
