"""Exercise the production 42.21.0 human factory/lifecycle/transport with engine stubs.

No native game initialization or deployment. The real-JAR dry run separately checks
engine signatures; this fixture checks lifecycle and outbound packet field contracts.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FILES = {
    "zombie/savefile/ApocBRNpcStore.java": """package zombie.savefile;
import java.util.*;import zombie.apocalipse.humans.*;import zombie.apocalipse.humans.profiles.*;
public final class ApocBRNpcStore {
 public record State(ServerHumanDefinition definition,HumanLifecycle.State lifecycle,int activations,int profileRevision,float x,float y,int z,long revision,boolean retired,zombie.apocalipse.humans.state.HumanState physiology){
 public State(ServerHumanDefinition d,HumanLifecycle.State l,int a,int p,float x,float y,int z,long r,boolean t){this(d,l,a,p,x,y,z,r,t,zombie.apocalipse.humans.state.HumanState.INITIAL);}}

 public record Restored(State state,boolean activate){}
 public record Loaded(List<HumanProfile> profiles,List<Restored> instances){}
 public static boolean enabled(){return false;}
 public record DialogueIdentity(String npc,String dialogue,String kind,String account){}
 public static ApocBRNpcStore current(){return null;}
 public zombie.apocalipse.humans.encounters.HumanRelationships relationships(){return new zombie.apocalipse.humans.encounters.HumanRelationships();}
 public Set<String> dialogueFlags(DialogueIdentity id){return Set.of();}
 public boolean remember(DialogueIdentity id,String flag){return false;}
 public static ApocBRNpcStore start(String s,String w,ServerHumanDefinition d){throw new AssertionError("Unexpected database initialization");}
 public Loaded takeLoaded(){return null;}public List<HumanProfile> takeProfiles(){return null;}public boolean healthy(){return true;}public void offer(State s){}
}
""",

    "zombie/iso/IsoDirections.java": """package zombie.iso;
public enum IsoDirections { N,NW,W,SW,S,SE,E,NE }
""",
    "zombie/iso/IsoCell.java": """package zombie.iso;
import java.util.*; import zombie.characters.IsoPlayer;
public class IsoCell {
 public final List<IsoPlayer> add=new ArrayList<>();public final zombie.ServerMovingObjectSet objects=new zombie.ServerMovingObjectSet();
 public final Map<String,IsoGridSquare> tiles=new HashMap<>();public boolean strict;
 public List<IsoPlayer> getAddList(){return add;}
 public java.util.Set<IsoMovingObject> getObjectList(){return objects;}
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
public class IsoPlayer extends IsoGameCharacter {
 public float useChargeDelta;public boolean aiming;
 public final zombie.inventory.types.HandWeapon bareHands=new zombie.inventory.types.HandWeapon(-1,"Base.BareHands");
 public boolean isGodMod(){return god;}public byte getExtraInfoFlags(){return (byte)(god?1:0);}
 public final Safety safety=new Safety();public Safety getSafety(){return safety;}public boolean factionPvp;
 public boolean isFactionPvp(){return factionPvp;}public static class Safety{public boolean enabled=true;public boolean isEnabled(){return enabled;}public void setEnabled(boolean value){enabled=value;}}

 private zombie.inventory.InventoryItem primary,secondary;
 private final zombie.network.fields.hit.AttackVars attackVars=new zombie.network.fields.hit.AttackVars();
 private final java.util.List<zombie.network.fields.hit.HitInfo> hitInfo=new java.util.ArrayList<>();
 public zombie.inventory.InventoryItem getPrimaryHandItem(){return primary;}public zombie.inventory.InventoryItem getSecondaryHandItem(){return secondary;}
 public void setPrimaryHandItem(zombie.inventory.InventoryItem item){primary=item;}public void setSecondaryHandItem(zombie.inventory.InventoryItem item){secondary=item;}
 public void setUseHandWeapon(zombie.inventory.types.HandWeapon weapon){}public void setIsAiming(boolean value){aiming=value;}public boolean isAiming(){return aiming;}
 public void setAttackTargetSquare(IsoGridSquare square){}
 public void setDoShove(boolean value){}public boolean critical;public int critChance=20;public void setCriticalHit(boolean value){critical=value;}public boolean isCriticalHit(){return critical;}public int calculateCritChance(IsoGameCharacter target){return critChance;}public float getHittingMod(){return 1;}public void setAimAtFloor(boolean value){}
 public void setAttackType(zombie.AttackType value){}public void setVariable(String key,String value){}
 public zombie.network.fields.hit.AttackVars getAttackVars(){return attackVars;}
 public java.util.List<zombie.network.fields.hit.HitInfo> getHitInfoList(){return hitInfo;}
 public int noises;public void addWorldSoundUnlessInvisible(int radius,int volume,boolean stress){noises++;}
 public boolean remote,god,invisible,ghost,detached,dead,running,sprinting,sneaking; public int playerIndex;
 private final java.util.Map<String,Float> variables=new java.util.HashMap<>();
 private final NetworkPlayerAI networkAi=new NetworkPlayerAI();
 public NetworkPlayerAI getNetworkCharacterAI(){return networkAi;}
 public void setRunning(boolean v){running=v;}public void setSprinting(boolean v){sprinting=v;}public void setSneaking(boolean v){sneaking=v;}
 public int instanceId; public float angle;
 private float health=1;private final Stats stats=new Stats();private final zombie.characters.BodyDamage.BodyDamage body=new zombie.characters.BodyDamage.BodyDamage();
 public float getHealth(){return health;}public void setHealth(float v){health=v;}
 public Stats getStats(){return stats;}public zombie.characters.BodyDamage.BodyDamage getBodyDamage(){return body;}

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
 public long getSteamID(){return 76561198000000000L;}
 public final zombie.inventory.ItemContainer inventory=new zombie.inventory.ItemContainer();
 public zombie.inventory.ItemContainer getInventory(){return inventory;}
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
 public int getID(){return instanceId;} public IsoGridSquare getCurrentSquare(){return detached?null:cell.getGridSquare(getXi(),getYi(),getZi());}
 public void setVariable(String k,float v){variables.put(k,v);if(k.equals("IdleSpeed"))idleSpeed=v;}
 public float getVariableFloat(String k,float fallback){return variables.getOrDefault(k,fallback);}
 public SurvivorDesc getDescriptor(){return descriptor;} public Emitter getEmitter(){return emitter;}
 public static class Emitter {public boolean stopped; public void stopAll(){stopped=true;}}
}
""",
    "zombie/core/raknet/UdpConnection.java": """package zombie.core.raknet;
public class UdpConnection {
 public boolean connected=true,relevant=true; public int announced,removed;
 public final zombie.characters.IsoPlayer[] players=new zombie.characters.IsoPlayer[4];
 public zombie.characters.IsoPlayer getPlayerAt(int slot){return players[slot];}
 public boolean hasPlayer(short id){for(var p:players)if(p!=null&&p.getOnlineID()==id)return true;return false;}
 public byte getPlayerIndex(zombie.characters.IsoPlayer p){for(byte i=0;i<4;i++)if(players[i]==p)return i;return -1;}
 public zombie.core.network.ByteBufferWriter startPacket(){return new zombie.core.network.ByteBufferWriter();}
 public boolean isFullyConnected(){return connected;}
 public boolean isRelevantTo(float x,float y){return relevant;}
}
""",
    "zombie/debug/DebugLog.java": """package zombie.debug;
public class DebugLog {public static final java.util.List<String> messages=new java.util.ArrayList<>();public static void log(String value){messages.add(value);}}
""",
    "zombie/network/GameServer.java": """package zombie.network;
import java.util.*;import zombie.characters.IsoPlayer;import zombie.core.raknet.UdpConnection;
public class GameServer {
 public static String serverName="fixture";
 public static boolean server=true;
 public static final Map<Short,IsoPlayer> IDToPlayerMap=new HashMap<>();
 public static final Map<Short,Long> IDToAddressMap=new HashMap<>();
 public static final List<IsoPlayer> Players=new ArrayList<>();
 public static final Map<IsoPlayer,UdpConnection> owners=new IdentityHashMap<>();
 public static UdpConnection getConnectionFromPlayer(IsoPlayer p){return owners.get(p);}
 public static boolean isDelayedDisconnect(IsoPlayer p){return false;}
 public static Engine udpEngine=new Engine();
 public static int corpsesSent;public static void sendCorpse(zombie.iso.objects.IsoDeadBody body){corpsesSent++;}
 public static void sendPlayerConnected(IsoPlayer p,UdpConnection c){c.announced++;}
 public static class Engine {public final List<UdpConnection> connections=new ArrayList<>();}
}
""",
    "zombie/network/PacketTypes.java": """package zombie.network;
public class PacketTypes {public enum PacketType {
 PlayerUpdateReliable,PlayerTimeout,PlayerInjuries,State,RequestTrading,TradingUIUpdateState,RemoveInventoryItemFromContainer,Equip,PlayerHitZombie,PlayerHitPlayer,PlayerHitSquare,RangedWeaponSound;
 public void doPacket(zombie.core.network.ByteBufferWriter b){} public void send(zombie.core.raknet.UdpConnection c){}
 }}
""",
    "zombie/network/packets/INetworkPacket.java": """package zombie.network.packets;
import zombie.network.PacketTypes;import zombie.core.raknet.UdpConnection;
public interface INetworkPacket {
 record Message(UdpConnection connection,PacketTypes.PacketType type,Object[] args){}
 java.util.List<Message> messages=new java.util.ArrayList<>();
 static void send(UdpConnection c,PacketTypes.PacketType t,Object... args){
  if(t==PacketTypes.PacketType.PlayerTimeout)c.removed++;
  else messages.add(new Message(c,t,args));
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
  var victims=new ServerHumanZombieVictims();var victim=victims.resolve(id);
  check(victim!=null&&victim.body()==actor,"visible protected NPC is eligible zombie prey");
  check(victims.strike(victim,new IsoZombie(),1).equals("would_hit_god_mode")&&actor.getHealth()==1,"persisted god mode suppresses incoming damage");
  check(victims.strike(new zombie.apocalipse.zombies.ZombieVictimPort.Victim(id,null,0,0,0),new IsoZombie(),1).equals("victim_lost"),"victim identity validated before damage");
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
  var base=ServerHumanProfiles.registry().require("stationary");
  for(String name:java.util.List.of("vulnerable","ghost","hidden")){
   var cheats=new zombie.apocalipse.humans.profiles.HumanCheats(false,name.equals("ghost"),name.equals("hidden"));
   ServerHumanProfiles.registry().register(new zombie.apocalipse.humans.profiles.HumanProfile(name,1,base.behaviorId(),base.parameters(),base.greetingKey(),base.announcementId(),base.dialogue(),base.trading(),base.modules(),base.perceptionRadius(),base.perceptionIntervalMs(),base.requireLos(),base.walkingPatterns(),cheats,base.survival()));
   short testId=f.spawn(new ServerHumanDefinition(name,name,name,1,2,0,IsoDirections.N,false,"Tourist",name));
   var testVictim=victims.resolve(testId);
   if(name.equals("vulnerable")){
    check(testVictim!=null,"vulnerable NPC resolves");
    check(victims.strike(testVictim,new IsoZombie(),2).startsWith("native_damage="),"vulnerable NPC uses native damage boundary");
    check(Math.abs(f.state(name).vitals().health()-.9f)<.0001,"native damage imported to logical state once");
    check(Math.abs(testVictim.body().getHealth()-.9f)<.0001,"native health projected after strike");
   }else check(testVictim==null,"persisted concealment excludes NPC from prey");
   f.remove(name);check(victims.resolve(testId)==null,"deactivated victim immediately disappears");
  }
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
 "zombie/characters/Faction.java":"package zombie.characters;public class Faction {public static boolean same;public static boolean isInSameFaction(IsoPlayer a,IsoPlayer b){return same;}}",
 "zombie/iso/areas/NonPvpZone.java":"package zombie.iso.areas;public class NonPvpZone {public static boolean blocked;public static boolean isInNonPvpZone(zombie.characters.IsoPlayer p){return blocked;}}",
 "zombie/network/ServerOptions.java":"package zombie.network;public class ServerOptions {public static final ServerOptions instance=new ServerOptions();public final Flag pvp=new Flag(true),safetySystem=new Flag(false);public static class Flag{public boolean value;Flag(boolean v){value=v;}public boolean getValue(){return value;}}}",
 "zombie/network/fields/hit/Player.java":"package zombie.network.fields.hit;public class Player {public short id;public zombie.characters.IsoPlayer player;public short getID(){return id;}public zombie.characters.IsoPlayer getPlayer(){return player;}public void set(zombie.characters.IsoPlayer p,boolean critical){player=p;id=p.getOnlineID();}}",
 "zombie/network/packets/hit/PlayerHitPlayerPacket.java":r"""package zombie.network.packets.hit;
 public class PlayerHitPlayerPacket implements HitCharacter {
  protected final PlayerHitZombiePacket.Fall fall=new PlayerHitZombiePacket.Fall();public zombie.characters.IsoPlayer target,actor;public zombie.inventory.types.HandWeapon weapon;
  public java.util.List<zombie.network.fields.hit.WeaponHit> hits;public static int localProcesses,sends;
  public void set(zombie.characters.IsoPlayer actor,zombie.inventory.types.HandWeapon weapon,boolean critical,java.util.List<?> tracers,java.util.List<zombie.network.fields.hit.WeaponHit> hits,zombie.characters.IsoPlayer target){this.actor=actor;this.target=target;this.weapon=weapon;this.hits=java.util.List.copyOf(hits);}
  public void attack(){}
  public void processClient(Object connection){localProcesses++;for(var hit:hits){target.setHealth(target.getHealth()-.1f);target.getBodyDamage().setOverallBodyHealth(target.getHealth()*100);}attack();}
  public void sendToClient(zombie.network.PacketTypes.PacketType type,zombie.core.raknet.UdpConnection connection){sends++;}
 }""",
    "zombie/iso/BuildingDef.java": """package zombie.iso;public class BuildingDef {}
""",
    "zombie/iso/IsoGridSquare.java": """package zombie.iso;
import java.util.*;
public class IsoGridSquare {
 public final java.util.ArrayList<zombie.iso.IsoMovingObject> statics=new java.util.ArrayList<>();
 public java.util.ArrayList<zombie.iso.IsoMovingObject> getStaticMovingObjects(){return statics;}
 public void removeCorpse(zombie.iso.objects.IsoDeadBody b,boolean remote){statics.remove(b);zombie.network.id.ObjectIDManager.remove(b.id);b.square=null;}
 public void addCorpse(zombie.iso.objects.IsoDeadBody b,boolean remote){if(!statics.contains(b))statics.add(b);b.square=this;zombie.network.id.ObjectIDManager.add(b.id,b);}
 public final java.util.ArrayList<IsoMovingObject> moving=new java.util.ArrayList<>();
 public java.util.ArrayList<IsoMovingObject> getMovingObjects(){return moving;}
 public int x,y,z;public BuildingDef building;public boolean free=true,floor=true,stairs,occupied;
 public Set<String> blockedEdges=new HashSet<>();
 public IsoGridSquare(int a,int b,int c){x=a;y=b;z=c;}
 public BuildingDef getBuildingDef(){return building;}
 public boolean isFree(boolean characters){return free&&(!characters||!occupied&&moving.isEmpty());}
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
    "zombie/characters/CharacterStat.java": """package zombie.characters;
public enum CharacterStat {ENDURANCE,HUNGER,THIRST,FATIGUE,PAIN,PANIC,SICKNESS,ZOMBIE_INFECTION}
""",
    "zombie/characters/Stats.java": """package zombie.characters;
public class Stats {private final java.util.Map<CharacterStat,Float> values=new java.util.HashMap<>();
 public float get(CharacterStat k){return values.getOrDefault(k,k==CharacterStat.ENDURANCE?1f:0f);}
 public boolean set(CharacterStat k,float v){values.put(k,v);return true;}}
""",
    "zombie/characters/BodyDamage/BodyDamage.java": """package zombie.characters.BodyDamage;
public class BodyDamage {private float health=100;public float getOverallBodyHealth(){return health;}public void setOverallBodyHealth(float v){health=v;}}
""",
    "zombie/characters/NetworkPlayerAI.java": """package zombie.characters;
public class NetworkPlayerAI {public float walkSpeed=1,runSpeed=1;private byte shot;public byte getShotID(){return shot;}public void setShotID(byte value){shot=value;}}
""",
    "zombie/characters/NetworkPlayerVariables.java": """package zombie.characters;
public class NetworkPlayerVariables {public enum Flags {
 isSneaking,isOnFire,isAsleep,isRunning,isSprinting,isCharging,isChargingLT,isDoShove,hasDeferredMovement,
 isOnFloor,isCheatMode,isDebugMode,isDoGrapple,isDoContinueGrapple,isAutoDrinkEnabled,isPerformingAction}}
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
  // Perception snapshots are refreshed on the configured 100 ms cycle; actuation validates immediately.
  visitor.invisible=true;check(!human.actuator.face(identity),"invisible visitor cannot be faced between perception samples");check(human.actuator.observe(100000010L).visitors().isEmpty(),"invisible player ignored");visitor.invisible=false;
  visitor.dead=true;check(human.actuator.observe(200000010L).visitors().isEmpty(),"dead player ignored");visitor.dead=false;
  visitor.z=1;check(human.actuator.observe(300000010L).visitors().isEmpty(),"other floor ignored");visitor.z=0;
  visitor.x=200;check(human.actuator.observe(400000010L).visitors().isEmpty(),"distant player ignored");visitor.x=100.5f;
  LosUtil.result=LosUtil.TestResults.Blocked;check(!human.actuator.face(identity),"new obstruction blocks actuation immediately");check(human.actuator.observe(500000010L).visitors().isEmpty(),"wall blocks perception");
  LosUtil.result=LosUtil.TestResults.ClearThroughClosedDoor;check(human.actuator.observe(600000010L).visitors().isEmpty(),"closed door blocks perception");
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



# Shared inventory/protocol ports used by the production trading adapter.
FILES.update({
 "zombie/core/Core.java": "package zombie.core; public class Core { public static String gameSaveWorld=\"test-world\"; }",
 "zombie/apocalipse/events/ApocBREventControl.java": "package zombie.apocalipse.events; public final class ApocBREventControl { public static java.util.List<String> guideAnnouncementLines(){return java.util.List.of();} }",
 "zombie/core/Translator.java": "package zombie.core; public final class Translator { public static String getText(String key){return key;} }",
 "zombie/core/TradingState.java": "package zombie.core; public enum TradingState { WindowWasClosed,DealWasSealed,DealWasUnsealed,DealWasFinalized }",
 "zombie/core/znet/SteamUtils.java": "package zombie.core.znet; public class SteamUtils { public static boolean isSteamModeEnabled(){return true;} }",
 "zombie/core/network/ByteBufferWriter.java": "package zombie.core.network; public class ByteBufferWriter {}",
 "zombie/inventory/InventoryItem.java": """package zombie.inventory;
 public class InventoryItem {
  public int condition=10,ammo,count=1;
  public int getCondition(){return condition;}public int getCurrentAmmoCount(){return ammo;}public void setCurrentAmmoCount(int value){ammo=value;}
  public int getCount(){return count;}public void setCount(int value){count=value;}
  public int id;public String type;public ItemContainer container;
  public InventoryItem(int id,String type){this.id=id;this.type=type;}
  public int getID(){return id;} public String getFullType(){return type;}
  public ItemContainer getContainer(){return container;}
 }""",
 "zombie/inventory/ItemContainer.java": """package zombie.inventory;
 public class ItemContainer {
  public final java.util.List<InventoryItem> items=new java.util.ArrayList<>();
  public InventoryItem getItemWithID(int id){return items.stream().filter(i->i.id==id).findFirst().orElse(null);}
  public void Remove(InventoryItem item){items.remove(item);item.container=null;}
  public void AddItem(InventoryItem item){items.add(item);item.container=this;}
 }""",
 "zombie/network/fields/character/PlayerID.java": """package zombie.network.fields.character;
 public class PlayerID {
  public short id; public byte index=-1;public zombie.characters.IsoPlayer player;
  public short getID(){return id;} public byte getPlayerIndex(){return index;}
  public zombie.characters.IsoPlayer getPlayer(){return player;}
  public void set(zombie.characters.IsoPlayer p){player=p;id=p.getOnlineID();}
 }""",
 "zombie/network/packets/RequestTradingPacket.java": """package zombie.network.packets;
 public class RequestTradingPacket {
  public record Reply(zombie.characters.IsoPlayer npc,zombie.characters.IsoPlayer visitor,boolean accepted){}
  public static final java.util.List<Reply> replies=new java.util.ArrayList<>();
  private Reply reply;
  public void accept(zombie.characters.IsoPlayer a,zombie.characters.IsoPlayer b){reply=new Reply(a,b,true);}
  public void reject(zombie.characters.IsoPlayer a,zombie.characters.IsoPlayer b){reply=new Reply(a,b,false);}
  public void write(zombie.core.network.ByteBufferWriter writer){replies.add(reply);}
 }""",
 "zombie/savefile/ServerPlayerDB.java": """package zombie.savefile;
 public class ServerPlayerDB {
  public static boolean available=true;public static int saves;public static final ServerPlayerDB instance=new ServerPlayerDB();
  public static boolean isAvailable(){return available;} public static ServerPlayerDB getInstance(){return instance;}
  public void serverUpdateNetworkCharacter(zombie.characters.IsoPlayer p,int index,zombie.core.raknet.UdpConnection c){saves++;}
 }""",
 "zombie/savefile/ApocBRPayloadProvenance.java": "package zombie.savefile; public class ApocBRPayloadProvenance { public static int configuredServerId(){return 1;} }",
 "zombie/savefile/ApocBRHumanSales.java": """package zombie.savefile;
 public class ApocBRHumanSales {
  public record Sale(java.util.UUID id,int serverId,String world,String username,String steamId,int playerIndex,String npcKey,long soldAtMillis,java.util.List<Integer> itemIds,java.util.List<Integer> bundleItemIds){
   public int quantity(){return itemIds.size()+99*bundleItemIds.size();}
  }
  public static boolean enabled=true,fail;public static final java.util.List<Sale> sales=new java.util.ArrayList<>();
  public static boolean available(){return enabled;}
  public static java.util.UUID enqueue(Sale sale){if(fail)throw new IllegalStateException();sales.add(sale);return sale.id();}
 }""",
})


# Lua VM/network boundaries used by the production dialogue bridge.
FILES.update({
 "se/krka/kahlua/vm/KahluaTable.java": """package se.krka.kahlua.vm;
 public class KahluaTable { public final java.util.Map<Object,Object> values=new java.util.LinkedHashMap<>();
 public void rawset(Object k,Object v){values.put(k,v);} public Object rawget(Object k){return values.get(k);} }""",
 "se/krka/kahlua/vm/JavaFunction.java": "package se.krka.kahlua.vm; public interface JavaFunction {int call(LuaCallFrame f,int count);}",
 "se/krka/kahlua/vm/LuaCallFrame.java": """package se.krka.kahlua.vm;
 public class LuaCallFrame { public Object[] args; public Object result;
 public LuaCallFrame(Object... args){this.args=args;} public Object get(int i){return args[i];}
 public int push(Object value){result=value;return 1;} }""",
 "zombie/Lua/LuaManager.java": """package zombie.Lua; import se.krka.kahlua.vm.*;
 public class LuaManager { public static KahluaTable env=new KahluaTable();
 public static Platform platform=new Platform(); public static class Platform {public KahluaTable newTable(){return new KahluaTable();}} }""",
 "zombie/Lua/LuaEventManager.java": """package zombie.Lua;
 public class LuaEventManager { public static final java.util.List<String> events=new java.util.ArrayList<>();
 public static void AddEvent(String name){} public static void triggerEvent(String name,Object... args){events.add(name);} }""",
})


FILES.update({
 "zombie/iso/IsoMovingObject.java": """package zombie.iso;
 public abstract class IsoMovingObject {
  public abstract float getX();public abstract float getY();public abstract float getZ();
  public int getXi(){return (int)Math.floor(getX());}public int getYi(){return (int)Math.floor(getY());}public int getZi(){return (int)Math.floor(getZ());}
  public abstract int getID();public abstract IsoGridSquare getCurrentSquare();
 }""",
 "zombie/characters/IsoGameCharacter.java": """package zombie.characters;
 public abstract class IsoGameCharacter extends zombie.iso.IsoMovingObject {public abstract boolean isDead();public abstract float getHealth();public boolean isOnFloor(){return false;}
 public float getHitForce(){return .5f;}public void setHitReaction(String value){}
 public float processHitDamage(zombie.inventory.types.HandWeapon weapon,IsoPlayer owner,float damage,boolean ignore,float range){return damage*.3f;}
 }""",
 "zombie/characters/IsoZombie.java": """package zombie.characters;
 public class IsoZombie extends IsoGameCharacter {
  public int nativeHits;public String reaction;public boolean floor;
  public boolean isOnFloor(){return floor;}public float getHitForce(){return .5f;}
  public void setHitReaction(String value){reaction=value;}
  public float processHitDamage(zombie.inventory.types.HandWeapon weapon,IsoPlayer owner,float damage,boolean ignore,float range){return damage*.3f*(owner.isCriticalHit()?weapon.getCriticalDamageMultiplier():1);}
  public void Hit(float damage){nativeHits++;health-=damage;if(health<=0)dead=true;}
  public float x,y,z,health=1;public short online;public int instanceId;public boolean dead,grapple;
  public zombie.iso.IsoGridSquare square;
  public float getX(){return x;}public float getY(){return y;}public float getZ(){return z;}
  public int getID(){return instanceId;}public short getOnlineID(){return online;}
  public float getHealth(){return health;}public boolean isDead(){return dead;}
  public boolean isReanimatedForGrappleOnly(){return grapple;}public zombie.iso.IsoGridSquare getCurrentSquare(){return square;}
 }""",
 "zombie/ServerMovingObjectSet.java": """package zombie;
 public class ServerMovingObjectSet extends java.util.HashSet<zombie.iso.IsoMovingObject> {
  private final java.util.IdentityHashMap<zombie.iso.IsoMovingObject,Object> tokens=new java.util.IdentityHashMap<>();
  public Object member(zombie.iso.IsoMovingObject body){return tokens.get(body);}
  public boolean add(zombie.iso.IsoMovingObject body){if(!super.add(body))return false;tokens.put(body,new Object());return true;}
  public boolean remove(Object body){tokens.remove(body);return super.remove(body);}
 }""",
})

FILES.update({
 "zombie/AttackType.java":"package zombie;public enum AttackType {SHOT,MELEE_TO_FLOOR,MELEE_SWING}",
 "zombie/core/random/Rand.java":"package zombie.core.random;public class Rand {public static int roll;public static int Next(int max){return roll;}public static float Next(float min,float max){return min;}}",
 "zombie/inventory/types/HandWeapon.java":r"""package zombie.inventory.types;
 public class HandWeapon extends zombie.inventory.InventoryItem {
  public boolean chambered,clip,jammed;public HandWeapon(int id,String type){super(id,type);}
  public boolean isRanged(){return type.equals("Base.Pistol");}public boolean isTwoHandWeapon(){return !isRanged();}
  public int getMaxAmmo(){return 15;}public int getProjectileCount(){return 1;}public int getMaxHitCount(){return 3;}public float getMaxRange(zombie.characters.IsoPlayer owner){return isRanged()?15:1.2f;}
  public boolean isJammed(){return jammed;}public void setJammed(boolean value){jammed=value;}
  public boolean isRoundChambered(){return chambered;}public void setRoundChambered(boolean value){chambered=value;}
  public void setContainsClip(boolean value){clip=value;}public void setSpentRoundChambered(boolean value){}
  public float getMinDamage(){return .6f;}public float getMaxDamage(){return 1;}
  public float getDamageMod(zombie.characters.IsoPlayer actor){return 1;}public float getCriticalChance(){return 20;}public float getCriticalDamageMultiplier(){return 2.5f;}public int getHitChance(){return 50;}public int getSoundRadius(){return 100;}public int getSoundVolume(){return 30;}public String getSwingSound(){return "test-shot";}
 }""",
 "zombie/inventory/InventoryItemFactory.java":r"""package zombie.inventory;
 public class InventoryItemFactory {private static int next=10000;public static boolean fail;
  public static InventoryItem CreateItem(String type){if(fail)return null;return type.equals("Base.Crowbar")||type.equals("Base.Pistol")?
   new zombie.inventory.types.HandWeapon(next++,type):new InventoryItem(next++,type);}
 }""",
 "zombie/ai/states/PlayerAimState.java":"package zombie.ai.states;public class PlayerAimState {private static final PlayerAimState INSTANCE=new PlayerAimState();public static PlayerAimState instance(){return INSTANCE;}}",
 "zombie/CombatManager.java":r"""package zombie;
 public class CombatManager {private static final CombatManager INSTANCE=new CombatManager();public static CombatManager getInstance(){return INSTANCE;}
  public void processWeaponEndurance(zombie.characters.IsoPlayer owner,zombie.inventory.types.HandWeapon weapon){
   owner.getStats().set(zombie.characters.CharacterStat.ENDURANCE,owner.getStats().get(zombie.characters.CharacterStat.ENDURANCE)-.01f);}
 }""",
 "zombie/network/fields/hit/AttackVars.java":r"""package zombie.network.fields.hit;
 public class AttackVars {
  public boolean aimAtFloor;public float useChargeDelta,targetDistance;public Moving targetOnGround=new Moving(),targetStanding=new Moving();
  public void clear(){}public void setWeapon(zombie.inventory.types.HandWeapon weapon){}
  public static class Moving{public void set(Object target){}}
 }""",
 "zombie/network/fields/hit/HitInfo.java":r"""package zombie.network.fields.hit;
 public class HitInfo {public HitInfo init(Object target,float dot,float distance,float x,float y,float z){return this;}}""",
 "zombie/network/fields/hit/WeaponHit.java":r"""package zombie.network.fields.hit;
 public class WeaponHit {public float damage;public boolean ignored;public float getDamage(){return damage;}public boolean isDamageIgnored(){return ignored;}public void set(float damage,float range,float force,float dx,float dy,boolean head,boolean legs,boolean remove,boolean ignore){this.damage=damage;this.ignored=ignore;}}""",
 "zombie/network/packets/hit/PlayerHitZombiePacket.java":r"""package zombie.network.packets.hit;
 import java.util.*;import zombie.network.fields.hit.*;import zombie.characters.*;import zombie.inventory.types.HandWeapon;
 public class PlayerHitZombiePacket implements HitCharacter {
  protected final Fall fall=new Fall();public IsoZombie target;public List<WeaponHit> hits;public IsoPlayer actor;public HandWeapon weapon;
  public static final List<PlayerHitZombiePacket> sent=new ArrayList<>();public boolean critical;public String victimReaction;public static int localProcesses,serverAttackCallbacks;
  public void set(IsoPlayer actor,HandWeapon weapon,boolean critical,List<?> tracers,List<WeaponHit> hits,IsoZombie target,boolean helmet){this.critical=critical;this.actor=actor;this.weapon=weapon;this.target=target;this.hits=List.copyOf(hits);this.victimReaction=target.reaction;}
  public void sendToClient(zombie.network.PacketTypes.PacketType type,zombie.core.raknet.UdpConnection connection){sent.add(this);}
  public void processClient(Object connection){localProcesses++;for(var hit:hits)target.Hit(hit.damage*(weapon.isRanged()?1:.15f));attack();}
  public void attack(){serverAttackCallbacks++;}
  public static class Fall {public void set(float x,float y,byte z,float angle){}}
 }""",
 "zombie/network/packets/hit/HitCharacter.java":r"""package zombie.network.packets.hit;
 public interface HitCharacter {void sendToClient(zombie.network.PacketTypes.PacketType type,zombie.core.raknet.UdpConnection connection);void processClient(Object connection);}""",
 "zombie/network/packets/hit/PlayerHitSquarePacket.java":r"""package zombie.network.packets.hit;
 public class PlayerHitSquarePacket implements HitCharacter {
  public void set(zombie.characters.IsoPlayer actor,zombie.inventory.types.HandWeapon weapon,boolean critical,int hits,java.util.List<?> tracers){}
  public void attack(){PlayerHitZombiePacket.serverAttackCallbacks++;}
  public void processClient(Object connection){PlayerHitZombiePacket.localProcesses++;attack();}
  public void sendToClient(zombie.network.PacketTypes.PacketType type,zombie.core.raknet.UdpConnection connection){
   zombie.network.packets.INetworkPacket.messages.add(new zombie.network.packets.INetworkPacket.Message(connection,type,new Object[0]));}
 }""",
})

# Native traversal dependencies. These model engine inputs; production traversal owns classification and execution.
FILES.update({
 "zombie/iso/SpriteDetails/IsoFlagType.java": "package zombie.iso.SpriteDetails;public enum IsoFlagType {water}",
 "zombie/iso/sprite/IsoSprite.java": "package zombie.iso.sprite;public class IsoSprite {public String name=\"fence\";public String getName(){return name;}}",
 "zombie/iso/IsoObject.java": """package zombie.iso;
 public class IsoObject {public boolean tall;public IsoGridSquare square;public int objectIndex;public IsoGridSquare getSquare(){return square;}public int getObjectIndex(){return objectIndex;}public zombie.iso.sprite.IsoSprite sprite=new zombie.iso.sprite.IsoSprite();
 public boolean isTallHoppable(){return tall;}public zombie.iso.sprite.IsoSprite getSprite(){return sprite;}}""",
 "zombie/iso/objects/IsoDoor.java": """package zombie.iso.objects;
 public class IsoDoor extends zombie.iso.IsoObject {
 public boolean open,locked,barricaded,obstructed,refuse;public int toggles;
 public boolean IsOpen(){return open;}public boolean isBarricaded(){return barricaded;}public boolean isObstructed(){return obstructed;}
 public boolean couldBeOpen(zombie.characters.IsoPlayer p){if(p.getCurrentSquare()==null)throw new AssertionError("missing source context");return !locked&&!barricaded&&!obstructed;}
 public void ToggleDoor(zombie.characters.IsoPlayer p){toggles++;if(!refuse)open=!open;}}""",
 "zombie/iso/objects/IsoThumpable.java": """package zombie.iso.objects;
 public class IsoThumpable extends IsoDoor {public boolean door=true,window;public boolean isDoor(){return door;}
 public boolean canClimbThrough(zombie.characters.IsoPlayer p){return window&&!barricaded;}}""",
 "zombie/iso/objects/IsoWindow.java": """package zombie.iso.objects;
 public class IsoWindow extends zombie.iso.IsoObject {
 public boolean open,locked,perma,barricaded,invincible,smashed,glassRemoved;public int toggles;
 public boolean IsOpen(){return open;}public boolean isInvincible(){return invincible;}public boolean isBarricaded(){return barricaded;}
 public boolean isSmashed(){return smashed;}public boolean isGlassRemoved(){return glassRemoved;}
 public boolean isLocked(){return locked;}public boolean isPermaLocked(){return perma;}
 public boolean canClimbThrough(zombie.characters.IsoPlayer p){return !barricaded&&!invincible&&(open||smashed);}
 public void ToggleWindow(zombie.characters.IsoPlayer p){toggles++;if(!locked&&!perma)open=!open;}}""",
 "zombie/iso/objects/IsoWindowFrame.java": """package zombie.iso.objects;
 public class IsoWindowFrame extends zombie.iso.IsoObject {public boolean blocked;public boolean canClimbThrough(zombie.characters.IsoPlayer p){return !blocked;}}""",
 "zombie/ai/states/ClimbThroughWindowPositioningParams.java": """package zombie.ai.states;
 public class ClimbThroughWindowPositioningParams {
 public boolean canClimb,scratch,isCounter,isSheetRope,isFloor;public zombie.iso.IsoDirections climbDir;
 public zombie.characters.IsoPlayer climbingCharacter;public zombie.iso.IsoObject windowObject;
 public int startX,startY,z,endX,endY,oppositeX,oppositeY;}""",
 "zombie/ai/states/ClimbThroughWindowState.java": """package zombie.ai.states;
 public class ClimbThroughWindowState {private static final ClimbThroughWindowState INSTANCE=new ClimbThroughWindowState();
 public static ClimbThroughWindowState instance(){return INSTANCE;}
 public static final Object PARAMS=new Object();}""",
 "zombie/ai/states/ClimbOverFenceState.java": "package zombie.ai.states;public class ClimbOverFenceState {private static final ClimbOverFenceState INSTANCE=new ClimbOverFenceState();public static ClimbOverFenceState instance(){return INSTANCE;}}",
 "zombie/ai/states/IdleState.java": "package zombie.ai.states;public class IdleState {private static final IdleState INSTANCE=new IdleState();public static IdleState instance(){return INSTANCE;}}",
})
FILES["zombie/characters/IsoPlayer.java"] = FILES["zombie/characters/IsoPlayer.java"].replace(
 "public IsoGridSquare getCurrentSquare(){return detached?null:cell.getGridSquare(getXi(),getYi(),getZi());}",
 "public IsoGridSquare current;public void setCurrent(IsoGridSquare s){current=s;} public IsoGridSquare getCurrentSquare(){return current!=null?current:detached?null:cell.getGridSquare(getXi(),getYi(),getZi());}"
 "private final zombie.ai.states.ClimbThroughWindowPositioningParams climb=new zombie.ai.states.ClimbThroughWindowPositioningParams();"
 "public zombie.ai.states.ClimbThroughWindowPositioningParams get(Object param){return climb;}")
FILES["zombie/pathfind/PolygonalMap2.java"] = FILES["zombie/pathfind/PolygonalMap2.java"].replace(
 "public boolean lineClearCollide", "public boolean standBlocked;public boolean canStandAt(float x,float y,int z,Object ignored,boolean doors,boolean walls){return !standBlocked;}public boolean lineClearCollide")
FILES["zombie/iso/IsoGridSquare.java"] = FILES["zombie/iso/IsoGridSquare.java"].replace(
 "public Set<String> blockedEdges", """public boolean water,hopBlocked;
 public boolean has(zombie.iso.SpriteDetails.IsoFlagType flag){return water;}
 public final Map<String,IsoObject> edges=new HashMap<>();
 public IsoObject edge(IsoGridSquare b){return edges.get(b.x+","+b.y);}
 public IsoObject getDoorTo(IsoGridSquare b){return edge(b) instanceof zombie.iso.objects.IsoDoor d&&(!(d instanceof zombie.iso.objects.IsoThumpable t)||t.door)?d:null;}
 public zombie.iso.objects.IsoWindow getWindowTo(IsoGridSquare b){return edge(b) instanceof zombie.iso.objects.IsoWindow w?w:null;}
 public zombie.iso.objects.IsoWindowFrame getWindowFrameTo(IsoGridSquare b){return edge(b) instanceof zombie.iso.objects.IsoWindowFrame w?w:null;}
 public zombie.iso.objects.IsoThumpable getWindowThumpableTo(IsoGridSquare b){return edge(b) instanceof zombie.iso.objects.IsoThumpable w&&w.window?w:null;}
 public IsoObject getHoppableTo(IsoGridSquare b){IsoObject e=edge(b);return e!=null&&e.getClass()==IsoObject.class?e:null;}
 public boolean isPlayerAbleToHopWallTo(IsoDirections d,IsoGridSquare b){return !hopBlocked;}
 public Set<String> blockedEdges""")

FILES["zombie/apocalipse/zombies/ZombieVictimPort.java"] = (ROOT / "42.21.0/src/zombie/apocalipse/zombies/ZombieVictimPort.java").read_text(encoding="utf-8")
FILES["zombie/apocalipse/zombies/ServerZombieController.java"] = """package zombie.apocalipse.zombies;
public class ServerZombieController {private static final ServerZombieController I=new ServerZombieController();
public static int engagements;
public static ServerZombieController getInstance(){return I;}public void install(ZombieVictimPort port){}
public boolean engage(zombie.characters.IsoZombie z,short id){engagements++;return true;}}"""
FILES["zombie/characters/IsoPlayer.java"] = FILES["zombie/characters/IsoPlayer.java"].replace(
    "public float useChargeDelta;", "public void setAttackedBy(zombie.characters.IsoGameCharacter z){}public boolean isDeathDragDown(){return false;}public float useChargeDelta;")
FILES["zombie/characters/BodyDamage/BodyDamage.java"] = FILES["zombie/characters/BodyDamage/BodyDamage.java"].replace(
    "private float health=100;", "private float health=100;public void DamageFromWeapon(zombie.inventory.types.HandWeapon weapon,int part){health-=10;}public void calculateOverallHealth(){}public boolean AddRandomDamageFromZombie(zombie.characters.IsoZombie z,String reaction,int part){health-=10;return true;}")

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


FILES.update({
 "zombie/network/id/ObjectID.java": "package zombie.network.id;public class ObjectID {}",
 "zombie/network/id/ObjectIDManager.java": """package zombie.network.id;
 public class ObjectIDManager {static final java.util.Map<ObjectID,Object> objects=new java.util.IdentityHashMap<>();
 public static Object get(ObjectID id){return objects.get(id);}public static void add(ObjectID id,Object b){objects.put(id,b);}public static void remove(ObjectID id){objects.remove(id);}}""",
 "zombie/iso/objects/IsoDeadBody.java": """package zombie.iso.objects;
 public class IsoDeadBody extends zombie.iso.IsoMovingObject {
 public final zombie.network.id.ObjectID id=new zombie.network.id.ObjectID();public float x,y,z,angle;public boolean animal,fake;
 public float getAngle(){return angle;}
 public zombie.iso.IsoGridSquare square;public Object inventory=new Object();
 public boolean isAnimal(){return animal;}public boolean isFakeDead(){return fake;}
 public zombie.network.id.ObjectID getObjectID(){return id;}
 public int getStaticMovingObjectIndex(){return square==null?-1:square.statics.indexOf(this);}
 public float getX(){return x;}public float getY(){return y;}public float getZ(){return z;}public int getID(){return 0;}
 public void setX(float v){x=v;}public void setY(float v){y=v;}public void setZ(float v){z=v;}
 public zombie.iso.IsoGridSquare getSquare(){return square;}public zombie.iso.IsoGridSquare getCurrentSquare(){return square;}
 public void setSquare(zombie.iso.IsoGridSquare s){square=s;}
 }""",
})


FILES["zombie/iso/objects/IsoZombieGiblets.java"] = """package zombie.iso.objects;
public class IsoZombieGiblets extends zombie.iso.IsoMovingObject {
 public float getX(){return 0;}public float getY(){return 0;}public float getZ(){return 0;}
 public int getID(){return -1;}public zombie.iso.IsoGridSquare getCurrentSquare(){return null;}
}"""


# Native dependencies for the production grapple adapter. Conversion/animation are
# explicit engine doubles; the session, claims, packet builder and authority are production code.
FILES["zombie/apocalipse/zombies/ServerCorpseDragAuthority.java"] = (ROOT / "42.21.0/src/zombie/apocalipse/zombies/ServerCorpseDragAuthority.java").read_text(encoding="utf-8")
FILES["zombie/iso/Vector2.java"] = "package zombie.iso;public class Vector2 {public float x,y;public Vector2(float x,float y){this.x=x;this.y=y;}}"
FILES["zombie/network/ServerMap.java"] = """package zombie.network;
public class ServerMap {public static final ServerMap instance=new ServerMap();public final java.util.Map<Short,zombie.characters.IsoZombie> zombieMap=new java.util.HashMap<>();}"""
FILES["zombie/popman/NetworkZombieManager.java"] = """package zombie.popman;
public class NetworkZombieManager {static final NetworkZombieManager INSTANCE=new NetworkZombieManager();public static NetworkZombieManager getInstance(){return INSTANCE;}
public void moveZombie(zombie.characters.IsoZombie body,Object connection,Object player){body.owner=connection;}}"""
FILES["zombie/network/packets/character/ZombiePacket.java"] = """package zombie.network.packets.character;
public class ZombiePacket {public float x,y,realX,realY;public byte z,realZ,predictionType,update;public short target;public zombie.network.NetworkVariables.ZombieState realState;}"""
FILES["zombie/network/NetworkVariables.java"] = "package zombie.network;public class NetworkVariables {public enum ZombieState {Idle,Grappled}}"
FILES["zombie/ai/states/PlayerDraggingCorpse.java"] = """package zombie.ai.states;
public class PlayerDraggingCorpse {static final PlayerDraggingCorpse INSTANCE=new PlayerDraggingCorpse();public static PlayerDraggingCorpse instance(){return INSTANCE;}}"""
FILES["zombie/characters/IsoGameCharacter.java"] = FILES["zombie/characters/IsoGameCharacter.java"].replace("{public abstract boolean", """{
 public IsoGameCharacter grapplingTarget,grappledBy;public boolean continued;public String sharedGrappleType;
 public IsoGameCharacter getGrapplingTarget(){return grapplingTarget;}public IsoGameCharacter getGrappledBy(){return grappledBy;}
 public boolean isGrappling(){return grapplingTarget!=null;}public boolean isBeingGrappled(){return grappledBy!=null;}
 public float grappleBearing;public float getBearingFromGrappledTarget(){return grappleBearing;}
 public void setDoContinueGrapple(boolean value){continued=value;}
 public boolean isDoContinueGrapple(){return continued;}
 public void setSharedGrappleType(String type){sharedGrappleType=type;if(grapplingTarget!=null)grapplingTarget.sharedGrappleType=type;}
 public void resetGrappleStateToDefault(String reason){grapplingTarget=null;continued=false;}
 public void LetGoOfGrappled(String result){var target=grapplingTarget;grapplingTarget=null;continued=false;if(target instanceof IsoZombie z){z.grappledBy=null;z.die();}}
 public abstract boolean""")
FILES["zombie/characters/IsoPlayer.java"] = FILES["zombie/characters/IsoPlayer.java"].replace("public class IsoPlayer extends IsoGameCharacter {", """public class IsoPlayer extends IsoGameCharacter {
 public boolean rejectCorpseGrab;public static short nextCorpseZombie=2000;
 public zombie.iso.Vector2 getForwardDirection(){return new zombie.iso.Vector2((float)Math.cos(angle),(float)Math.sin(angle));}
 public void pickUpCorpse(zombie.iso.objects.IsoDeadBody body,String type){
  if(rejectCorpseGrab)return;
  var helper=new IsoZombie();helper.cell=cell;helper.inventory=body.inventory;helper.grapple=true;helper.instanceId=++nextCorpseZombie;helper.online=helper.onlineId=nextCorpseZombie;
  helper.x=body.x;helper.y=body.y;helper.z=body.z;helper.square=body.square;
  body.square.removeCorpse(body,false);body.inventory=null;helper.square.moving.add(helper);cell.objects.add(helper);
  zombie.network.ServerMap.instance.zombieMap.put(helper.onlineId,helper);grapplingTarget=helper;helper.grappledBy=this;setSharedGrappleType(type);
 }
""")
FILES["zombie/characters/IsoZombie.java"] = FILES["zombie/characters/IsoZombie.java"].replace("public class IsoZombie extends IsoGameCharacter {", """public class IsoZombie extends IsoGameCharacter {
 public short onlineId;public Object owner,inventory;public zombie.iso.IsoCell cell;
 public interface DeathListener {void onDied(IsoGameCharacter sender,zombie.iso.objects.IsoDeadBody body);}
 private final java.util.List<DeathListener> deathListeners=new java.util.ArrayList<>();
 public void addOnDiedListener(DeathListener listener,boolean once){deathListeners.add(listener);}
 public void die(){if(dead)return;dead=true;
  var corpse=new zombie.iso.objects.IsoDeadBody();corpse.x=x;corpse.y=y;corpse.z=z;corpse.inventory=inventory;inventory=null;
  if(square!=null){square.moving.remove(this);square.addCorpse(corpse,true);}
  zombie.network.ServerMap.instance.zombieMap.remove(onlineId);if(cell!=null)cell.objects.remove(this);
  for(var listener:new java.util.ArrayList<>(deathListeners))listener.onDied(this,corpse);deathListeners.clear();
 }
 public void setX(float value){x=value;}public void setY(float value){y=value;}public void setZ(float value){z=value;}
 public void setCurrent(zombie.iso.IsoGridSquare value){if(square!=null)square.moving.remove(this);square=value;}
 public void setMovingSquareNow(){if(square!=null&&!square.moving.contains(this))square.moving.add(this);}
 public void setForwardDirection(zombie.iso.Vector2 direction){}public void setTarget(Object value){}
""")
FILES["zombie/iso/objects/IsoDeadBody.java"] = FILES["zombie/iso/objects/IsoDeadBody.java"].replace("public class IsoDeadBody extends zombie.iso.IsoMovingObject {", """public class IsoDeadBody extends zombie.iso.IsoMovingObject {
 private final se.krka.kahlua.vm.KahluaTable modData=new se.krka.kahlua.vm.KahluaTable();
 public se.krka.kahlua.vm.KahluaTable getModData(){return modData;}
""")


FILES["zombie/characters/IsoPlayer.java"] = FILES["zombie/characters/IsoPlayer.java"].replace(" public boolean rejectCorpseGrab;", """
 public void setDoGrapple(boolean value){}public float calculateGrappleEffectivenessFromTraits(){return 1;}
 public zombie.inventory.types.HandWeapon getAttackingWeapon(){return null;}
 public boolean rejectCorpseGrab;""")
FILES["zombie/characters/IsoZombie.java"] = FILES["zombie/characters/IsoZombie.java"].replace(" public short onlineId;", """
 public void setReanimatedForGrappleOnly(boolean value){grapple=value;}public void setFakeDead(boolean value){}public void setCrawler(boolean value){}
 public boolean isFallOnFront(){return false;}public void setDoRender(boolean value){}public void addFootstepParametersIfNeeded(){}public void setDirectionAngle(float value){}
 public void setOnFloor(boolean value){floor=value;}public void setOnKillDone(boolean value){}public void setHealth(float value){health=value;}
 public void Grappled(IsoPlayer actor,zombie.inventory.types.HandWeapon weapon,float effectiveness,String type){if(actor.rejectCorpseGrab)return;grappledBy=actor;actor.grapplingTarget=this;actor.setSharedGrappleType(type);}
 public short onlineId;""")
FILES["zombie/iso/objects/IsoDeadBody.java"] = FILES["zombie/iso/objects/IsoDeadBody.java"].replace(" private final se.krka", """
 public boolean getDoRender(){return true;}
 public zombie.characters.IsoGameCharacter reanimate(){
  var helper=new zombie.characters.IsoZombie();helper.inventory=inventory;helper.instanceId=++zombie.characters.IsoPlayer.nextCorpseZombie;helper.online=helper.onlineId=zombie.characters.IsoPlayer.nextCorpseZombie;
  helper.x=x;helper.y=y;helper.z=z;helper.square=square;var old=square;old.removeCorpse(this,false);inventory=null;old.moving.add(helper);
  zombie.network.ServerMap.instance.zombieMap.put(helper.onlineId,helper);return helper;
 }
 private final se.krka""")

if __name__ == "__main__":
    main()
