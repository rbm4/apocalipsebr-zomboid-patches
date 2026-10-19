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
 public final Map<String,IsoGridSquare> tiles=new HashMap<>();public boolean strict;
 public List<IsoPlayer> getAddList(){return add;}
 public List<IsoPlayer> getObjectList(){return objects;}
 public IsoGridSquare getGridSquare(int x,int y,int z){String k=x+","+y+","+z;
  return strict?tiles.get(k):tiles.computeIfAbsent(k,key->new IsoGridSquare(x,y,z));}
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
 public boolean remote,god,invisible,ghost,detached,dead; public int playerIndex;
 public int instanceId; public float angle;
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
 public String getUsername(){return username;}
 public void setGodMod(boolean v,boolean forced){god=v;}
 public void setInvisible(boolean v,boolean forced){invisible=v;}
 public void setGhostMode(boolean v,boolean forced){ghost=v;}
 public void setX(float v){x=v;} public void setY(float v){y=v;} public void setZ(float v){z=v;}
 public void setForwardIsoDirection(IsoDirections v){facing=v;angle=v.ordinal()*.5f;}
 public float getDirectionAngleRadians(){return angle;}
 public void setDirectionAngle(float degrees){angle=(float)Math.toRadians(degrees);}
 public float getX(){return x;} public float getY(){return y;} public float getZ(){return z;}
 public int getXi(){return (int)x;} public int getYi(){return (int)y;} public int getZi(){return (int)z;}
 public boolean isDead(){return dead;} public boolean isInvisible(){return invisible;}
 public int getID(){return instanceId;} public Object getCurrentSquare(){return detached?null:cell;}
 public void setVariable(String k,float v){idleSpeed=v;}
 public SurvivorDesc getDescriptor(){return descriptor;} public Emitter getEmitter(){return emitter;}
 public static class Emitter {public boolean stopped; public void stopAll(){stopped=true;}}
}
""",
    "zombie/core/raknet/UdpConnection.java": """package zombie.core.raknet;
public class UdpConnection {
 public boolean connected=true,relevant=true; public int announced,removed;
 public final zombie.characters.IsoPlayer[] players=new zombie.characters.IsoPlayer[4];
 public zombie.characters.IsoPlayer getPlayerAt(int slot){return players[slot];}
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
 public static final List<IsoPlayer> Players=new ArrayList<>();
 public static final Map<IsoPlayer,UdpConnection> owners=new IdentityHashMap<>();
 public static UdpConnection getConnectionFromPlayer(IsoPlayer p){return owners.get(p);}
 public static boolean isDelayedDisconnect(IsoPlayer p){return false;}
 public static Engine udpEngine=new Engine();
 public static void sendPlayerConnected(IsoPlayer p,UdpConnection c){c.announced++;}
 public static class Engine {public final List<UdpConnection> connections=new ArrayList<>();}
}
""",
    "zombie/network/PacketTypes.java": """package zombie.network;
public class PacketTypes {public enum PacketType {PlayerUpdateReliable,PlayerTimeout,State}}
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
  check(defaults.username().equals(defaults.displayName()),"guide username is its actual configured name");
  System.setProperty("apocbr.guide.name","Ricardão");
  check(ServerHumanDefinition.configuredGuide().username().equals("Ricardão"),"custom guide name also controls username");
  System.clearProperty("apocbr.guide.name");
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
  rejects(()->f.spawn(new ServerHumanDefinition("other","__GUIDE","Other",1,2,0,IsoDirections.N,false,"Tourist")),"duplicate name comparison ignores case");
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
  check(actor.getUsername().equals("__guide"),"wire username has no numeric suffix");
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
  a.relevant=false;f.tick(3*ServerHumanFactory.UPDATE_NANOS);check(a.removed==0,"leaving relevance preserves client identity and speech cache");
  a.relevant=true;f.tick(4*ServerHumanFactory.UPDATE_NANOS);check(a.announced==1,"returning viewer reuses the same client actor");
  GameServer.udpEngine.connections.remove(b);f.tick(5*ServerHumanFactory.UPDATE_NANOS);
  check(b.removed==0,"disconnected transport not used");
  UdpConnection replacement=new UdpConnection();GameServer.udpEngine.connections.add(replacement);
  f.tick(6*ServerHumanFactory.UPDATE_NANOS);check(replacement.announced==1,"reconnected client gets appearance");
  check(ServerHumanFactory.handleDataRequest(id,a)&&a.announced==2,"reload requests reannounce known actor");
  int before=outside.announced;check(ServerHumanFactory.handleDataRequest(id,outside)&&outside.announced==before,"out-of-range request does not disclose actor");
  check(!ServerHumanFactory.handleDataRequest((short)42,a),"ordinary IDs fall through to vanilla");
  check(f.remove("guide")&&a.removed==1&&replacement.removed==1,"explicit removal notifies viewers");
  check(actor.getDescriptor().instance==null&&actor.getEmitter().stopped,"removal releases descriptor and sounds");
  check(!f.remove("guide")&&!ServerHumanFactory.handleDataRequest(id,a),"removed actor no longer resolves");
  rejects(()->f.spawn(new ServerHumanDefinition("replacement","__guide","Guide",1,2,0,IsoDirections.N,false,"Tourist")),"removed name stays reserved to protect native speech cache");
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

FILES.update({
    "zombie/iso/BuildingDef.java": """package zombie.iso;public class BuildingDef {}
""",
    "zombie/iso/IsoGridSquare.java": """package zombie.iso;
import java.util.*;
public class IsoGridSquare {
 public int x,y,z;public BuildingDef building;public boolean free=true,floor=true,stairs,occupied;
 public Set<String> blockedEdges=new HashSet<>();
 public IsoGridSquare(int a,int b,int c){x=a;y=b;z=c;}
 public BuildingDef getBuildingDef(){return building;}
 public boolean isFree(boolean characters){return free&&(!characters||!occupied);}
 public boolean TreatAsSolidFloor(){return floor;}public boolean HasStairs(){return stairs;}
 public boolean testCollideAdjacentAdvanced(int dx,int dy,int dz,boolean doors){return blockedEdges.contains(dx+","+dy);}
}
""",
    "zombie/pathfind/PolygonalMap2.java": """package zombie.pathfind;
public class PolygonalMap2 {
 public static final PolygonalMap2 instance=new PolygonalMap2();public boolean blocked;
 public boolean lineClearCollide(float x,float y,float a,float b,int z,Object ignored,boolean doors,boolean walls){return blocked;}
}
""",
    "zombie/characters/NetworkPlayerVariables.java": """package zombie.characters;
public class NetworkPlayerVariables {public enum Flags {
 isSneaking,isOnFire,isAsleep,isRunning,isSprinting,isCharging,isChargingLT,isDoShove,hasDeferredMovement}}
""",
    "zombie/iso/LosUtil.java": """package zombie.iso;
public class LosUtil {
 public enum TestResults {Clear,Blocked,ClearThroughClosedDoor}
 public static TestResults result=TestResults.Clear;
 public static TestResults lineClear(IsoCell c,int x,int y,int z,int a,int b,int d,boolean doors){return result;}
}
""",
    "zombie/ai/State.java": """package zombie.ai;
public class State { public enum Stage {Enter,Execute,Exit} }
""",
    "zombie/ai/states/PlayerEmoteState.java": """package zombie.ai.states;
public class PlayerEmoteState {private static final PlayerEmoteState INSTANCE=new PlayerEmoteState();
 public static PlayerEmoteState instance(){return INSTANCE;}}
""",
    "zombie/network/packets/actions/StatePacket.java": """package zombie.network.packets.actions;
import java.util.*;import zombie.ai.State;import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;import zombie.network.PacketTypes;
public class StatePacket {
 protected final ID characterId=new ID();protected final StateID state=new StateID();
 protected boolean isSubState;protected final Params params=new Params();
 protected final Events events=new Events();protected State.Stage stage;
 public float x,y,z;public static final List<StatePacket> sent=new ArrayList<>();
 public void set(float a,float b,float c){x=a;y=b;z=c;}
 public void sendToClient(PacketTypes.PacketType t,UdpConnection c){if(t!=PacketTypes.PacketType.State)throw new AssertionError();sent.add(this);}
 public boolean sub(){return isSubState;}public State.Stage stage(){return stage;}
 public Map<String,Object> parameters(){return params.values;}
 public Map<String,String> eventValues(){return events.values;}
 public IsoPlayer player(){return characterId.player;}
 public static class ID {IsoPlayer player;public void set(IsoPlayer p){player=p;}}
 public static class StateID {public void set(Object state){}}
 public static class Params {Map<String,Object> values=new HashMap<>();public void rawset(String k,Object v){values.put(k,v);}}
 public static class Events {Map<String,String> values=new HashMap<>();public Map<String,String> get(){return values;}}
}
""",
    "zombie/network/chat/ChatType.java": """package zombie.network.chat;public enum ChatType {say}
""",
    "zombie/chat/ChatUtility.java": """package zombie.chat;import zombie.network.chat.ChatType;
public class ChatUtility {public static boolean enabled=true;public static boolean chatStreamEnabled(ChatType t){return enabled;}}
""",
    "zombie/chat/ChatMessage.java": """package zombie.chat;
public class ChatMessage {public String author,text;public void setAuthor(String value){author=value;}}
""",
    "zombie/chat/ChatBase.java": """package zombie.chat;import java.util.*;
public class ChatBase {
 public static final List<ChatMessage> sent=new ArrayList<>();public static final List<Short> recipients=new ArrayList<>();
 public ChatMessage createMessage(String text){ChatMessage m=new ChatMessage();m.text=text;return m;}
 public void sendMessageToPlayer(short id,ChatMessage m){sent.add(m);recipients.add(id);}
}
""",
})

# Exercise the production chat bridge method rather than a duplicate implementation.
chat_source = (ROOT / "42.21.0/src/zombie/network/chat/ChatServer.java").read_text(encoding="utf-8")
chat_start = chat_source.index("    public boolean sendHumanSay(")
chat_end = chat_source.index("\n    }", chat_start) + len("\n    }")
FILES["zombie/network/chat/ChatServer.java"] = """package zombie.network.chat;
import java.util.*;import zombie.chat.*;
public class ChatServer {
 public static final Set<Short> players=new HashSet<>();public static boolean inited=true;
 public static final Map<ChatType,ChatBase> defaultChats=new HashMap<>();
 private static final ChatServer INSTANCE=new ChatServer();
 static {defaultChats.put(ChatType.say,new ChatBase());}
 public static ChatServer getInstance(){return INSTANCE;}
""" + chat_source[chat_start:chat_end] + "\n}\n"

# Extend the same fixture with actual observation, actuation and packet construction.
FILES["zombie/apocalipse/humans/FactoryTest.java"] = FILES["zombie/apocalipse/humans/FactoryTest.java"].replace(
    "  GameServer.server=false;rejects",
    """
  ServerHuman human=new ServerHuman(def("greeter",100.5f),(short)1100,IsoWorld.instance.currentCell);
  UdpConnection near=new UdpConnection();GameServer.udpEngine.connections.add(near);
  IsoPlayer visitor=new IsoPlayer(IsoWorld.instance.currentCell,new SurvivorDesc(false),0,0,0);
  visitor.id=7;visitor.instanceId=700;visitor.x=100.5f;visitor.y=202.75f;
  near.players[0]=visitor;near.players[1]=visitor;
  GameServer.Players.add(visitor);GameServer.IDToPlayerMap.put(visitor.id,visitor);GameServer.owners.put(visitor,near);
  var observation=human.actuator.observe(10);
  check(observation.visitors().size()==1,"connected nearby same-floor visitor observed");
  var identity=observation.visitors().getFirst().identity();
  check(human.actuator.face(identity)&&Math.abs(human.player.angle-Math.PI/2)<.0001,"facing uses engine degrees correctly");
  check(!human.actuator.face(new zombie.apocalipse.humans.brain.HumanObservation.Identity(visitor.id,999)),"reused slot rejected by actuator");
  visitor.x=102.5f;visitor.y=200.75f;
  check(human.actuator.face(identity)&&Math.abs(human.player.angle)<.0001,"same visitor identity resolves its new eastward position");
  visitor.x=100.5f;visitor.y=202.75f;
  visitor.invisible=true;check(human.actuator.observe(11).visitors().isEmpty(),"invisible player ignored");visitor.invisible=false;
  visitor.dead=true;check(human.actuator.observe(11).visitors().isEmpty(),"dead player ignored");visitor.dead=false;
  visitor.z=1;check(human.actuator.observe(11).visitors().isEmpty(),"other floor ignored");visitor.z=0;
  visitor.x=200;check(human.actuator.observe(11).visitors().isEmpty(),"distant player ignored");visitor.x=100.5f;
  LosUtil.result=LosUtil.TestResults.Blocked;check(human.actuator.observe(11).visitors().isEmpty(),"wall blocks perception");
  LosUtil.result=LosUtil.TestResults.ClearThroughClosedDoor;check(human.actuator.observe(11).visitors().isEmpty(),"closed door blocks perception");
  LosUtil.result=LosUtil.TestResults.Clear;
  human.viewers.add(near);
  zombie.network.packets.actions.StatePacket.sent.clear();
  check(human.actuator.startEmote("wavehi"),"native salutation submitted");
  var wave=zombie.network.packets.actions.StatePacket.sent.getLast();
  check(wave.player()==human.player&&wave.sub()&&wave.stage()==zombie.ai.State.Stage.Enter,"emote identifies remote human and enters substate");
  check(wave.parameters().get("emote").equals("wavehi")&&wave.parameters().get("playing").equals(true),"vanilla named state parameters");
  check(wave.eventValues().containsKey("EventEmote"),"native action transition event included");
  int waves=zombie.network.packets.actions.StatePacket.sent.size();ServerHumanTransport.announce(human,near);
  check(zombie.network.packets.actions.StatePacket.sent.size()==waves+1,"reload resynchronizes active salutation");
  human.actuator.stopEmote();var stop=zombie.network.packets.actions.StatePacket.sent.getLast();
  check(human.emote==null&&stop.stage()==zombie.ai.State.Stage.Exit&&stop.parameters().get("playing").equals(false),"emote exit clears playback");
  ServerHumanTransport.announce(human,near);check(!zombie.network.packets.actions.StatePacket.sent.getLast().parameters().get("playing").equals(true),"reload also clears stale playback");
  zombie.network.chat.ChatServer.players.add(visitor.id);
  zombie.chat.ChatBase.sent.clear();check(human.actuator.say("hi"),"say routed through production chat bridge");
  check(zombie.chat.ChatBase.sent.size()==1,"split-screen connection receives one greeting");
  var spoken=zombie.chat.ChatBase.sent.getLast();check(spoken.author.equals(human.player.getUsername())&&spoken.text.equals("hi"),"speech author matches client actor identity");
  zombie.chat.ChatUtility.enabled=false;check(!human.actuator.say("hi")&&zombie.chat.ChatBase.sent.size()==1,"disabled say chat respected");zombie.chat.ChatUtility.enabled=true;
  zombie.network.chat.ChatServer.players.clear();check(!human.actuator.say("hi"),"uninitialized chat recipient rejected");
  zombie.network.chat.ChatServer.players.add(visitor.id);visitor.x=200;
  check(!human.actuator.say("hi"),"speech not broadcast outside local range");visitor.x=100.5f;
  human.player.setX(0);human.player.setY(0);human.player.setZ(2);
  check(human.actuator.returnHome()&&human.player.x==100.5f&&human.player.y==200.75f&&human.player.z==0&&human.player.facing==IsoDirections.E,"home position and original facing restored");
  human.dispose();
  var constructor=ServerHumanFactory.class.getDeclaredConstructor();constructor.setAccessible(true);
  ServerHumanFactory movingFactory=constructor.newInstance();movingFactory.tick(0);
  movingFactory.spawn(def("moving",100.5f));
  long brainStep=ServerHumanFactory.BRAIN_UPDATE_NANOS;
  movingFactory.tick(brainStep);
  int movingPackets=PlayerPacketReliable.sent.size();
  visitor.x=102.5f;visitor.y=200.75f;movingFactory.tick(2*brainStep);
  check(PlayerPacketReliable.sent.size()>movingPackets&&Math.abs(last().prediction.direction)<.0001,"moving visitor heading published before 500ms heartbeat");
  movingPackets=PlayerPacketReliable.sent.size();movingFactory.tick(3*brainStep);
  check(PlayerPacketReliable.sent.size()==movingPackets,"unchanged heading does not increase idle packet cadence");
  for(long t=4*brainStep;t<=15*brainStep;t+=brainStep)movingFactory.tick(t);
  long emoteStarts=zombie.network.packets.actions.StatePacket.sent.stream().filter(s->s.stage()==zombie.ai.State.Stage.Enter).count();
  check(emoteStarts>0,"factory-driven salutation reached");
  visitor.x=98.5f;movingPackets=PlayerPacketReliable.sent.size();movingFactory.tick(16*brainStep);
  check(PlayerPacketReliable.sent.size()>movingPackets&&Math.abs(last().prediction.direction-Math.PI)<.0001,"heading follows visitor moving west during salutation");
  check(zombie.network.packets.actions.StatePacket.sent.stream().filter(s->s.stage()==zombie.ai.State.Stage.Enter).count()==emoteStarts,"turning while waving does not replay emote");
  movingFactory.remove("moving");
  var named=new ServerHumanDefinition("named","Ricardão","Ricardão",1,2,0,IsoDirections.N,false,"Tourist");
  movingFactory.spawn(named);movingFactory.remove("named");
  rejects(()->movingFactory.spawn(new ServerHumanDefinition("accent","RICARDA\\u0303O","Other",1,2,0,IsoDirections.N,false,"Tourist")),"equivalent Unicode name stays unique after removal");
  visitor.username="Real Player";
  rejects(()->movingFactory.spawn(new ServerHumanDefinition("collision","real player","Other",1,2,0,IsoDirections.N,false,"Tourist")),"online human name conflict rejected");
  GameServer.server=false;rejects"""
)


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
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources.extend(sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans").rglob("*.java")))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources))
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.apocalipse.humans.FactoryTest"], check=True)


if __name__ == "__main__":
    main()
