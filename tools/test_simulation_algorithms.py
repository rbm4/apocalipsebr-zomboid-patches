"""Production simulation indexes/dispatchers exercised against isolated engine fixtures.

Includes differential references and randomized mutations. No deployment or native
libraries. Game-JAR compilation is a separate patchApocalipseBr.ps1 -DryRun check.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "zombie/entity/ServerIsoEntityRegistry.java": "package zombie.entity;public class ServerIsoEntityRegistry{public static void listChanged(Object list){}public static void listExposed(Object list){}}",
    "zombie/UsedFromLua.java": "package zombie; public @interface UsedFromLua {}",
    "zombie/ApocBRServerTelemetryLite.java": """package zombie; public class ApocBRServerTelemetryLite {
 public static final java.util.Map<String,Long> counts=new java.util.HashMap<>();
 public static void count(String n,long v){counts.merge(n,v,Long::sum);} public static boolean isLuaEnabled(){return true;}
 public static void recordLuaEvent(String n,int c,long t){} public static Scope phase(String n){return new Scope();}
 public static class Scope implements AutoCloseable {public void close(){}}
} """,
    "zombie/GameTime.java": "package zombie; public class GameTime { public static final GameTime instance=new GameTime(); public float delta; public float getTimeDelta(){return delta;} }",
    "zombie/GameProfiler.java": "package zombie; public class GameProfiler {public static GameProfiler getInstance(){return new GameProfiler();} public ProfileArea profile(String n){return new ProfileArea();} public static class ProfileArea implements AutoCloseable {public void close(){}}}",
    "zombie/core/Core.java": "package zombie.core; public class Core { public static boolean debug; }",
    "zombie/core/logger/ExceptionLogger.java": "package zombie.core.logger; public class ExceptionLogger { public static void logException(Exception e){} }",
    "zombie/core/math/PZMath.java": "package zombie.core.math; public class PZMath {public static int fastfloor(float v){int i=(int)v;return v<i?i-1:i;} }",
    "zombie/debug/DebugType.java": "package zombie.debug; public class DebugType { public static final DebugType Entity=new DebugType(), Lua=new DebugType(); public void println(String s){} public void warn(String s,Object...a){} }",
    "zombie/debug/DebugOptions.java": "package zombie.debug; public class DebugOptions {public static final DebugOptions instance=new DebugOptions(); public Checks checks=new Checks(); public static class Checks {public Flag slowLuaEvents=new Flag();} public static class Flag {public boolean value; public boolean getValue(){return value;}} }",
    "zombie/entity/util/Array.java": """package zombie.entity.util; public class Array<T> {
 public int size; private final boolean ordered; private final java.util.ArrayList<T> list=new java.util.ArrayList<>();
 public Array(boolean ordered,int capacity){this.ordered=ordered;} public void add(T v){list.add(v);size++;}
 public T get(int i){return list.get(i);} public T peek(){return list.get(size-1);} public void clear(){list.clear();size=0;}
 public boolean contains(T v,boolean identity){for(T t:list)if(identity?t==v:java.util.Objects.equals(t,v))return true;return false;}
 public boolean removeValue(T v,boolean identity){for(int i=0;i<size;i++)if(identity?list.get(i)==v:java.util.Objects.equals(list.get(i),v)){removeIndex(i);return true;}return false;}
 public T removeIndex(int i){T old=list.get(i);if(!ordered && i<size-1)list.set(i,list.get(size-1));list.remove(ordered?i:size-1);size--;return old;}
 public void sort(java.util.Comparator<? super T> c){list.sort(c);}
} """,
    "zombie/entity/util/ImmutableArray.java": "package zombie.entity.util; public class ImmutableArray<T> {private final Array<T> a;public ImmutableArray(Array<T>a){this.a=a;}public int size(){return a.size;}public T get(int i){return a.get(i);}}",
    "zombie/entity/util/BitSet.java": "package zombie.entity.util; public class BitSet extends java.util.BitSet {}",
    "zombie/entity/util/ObjectSet.java": "package zombie.entity.util; public class ObjectSet<T> extends java.util.HashSet<T> {}",
    "zombie/entity/ComponentType.java": "package zombie.entity; public enum ComponentType { DryingCraftLogic }",
    "zombie/entity/GameEntityType.java": "package zombie.entity; public enum GameEntityType { MetaEntity; public byte getByteId(){return 0;} public static GameEntityType FromID(byte b){return MetaEntity;} }",
    "zombie/entity/GameEntityManager.java": "package zombie.entity; public class GameEntityManager {public static boolean debugMode;} ",
    "zombie/entity/Family.java": "package zombie.entity; public class Family { public boolean matches(GameEntity e){return true;} }",
    "zombie/entity/IBucketListener.java": "package zombie.entity; public interface IBucketListener {void onBucketEntityAdded(EntityBucket b,GameEntity e);void onBucketEntityRemoved(EntityBucket b,GameEntity e);}",
    "zombie/entity/GameEntity.java": """package zombie.entity; public class GameEntity {
 public boolean removingFromEngine, scheduledForEngineRemoval, addedToEngine=true, drying, accepted=true;
 ServerUsingPlayerIndex.Member usingPlayerMemberships; private zombie.characters.IsoPlayer usingPlayer;
 private final zombie.entity.util.BitSet bits=new zombie.entity.util.BitSet(); public zombie.entity.util.BitSet getBucketBits(){return bits;}
 public boolean hasComponent(ComponentType t){return drying;} public boolean hasRenderers(){return false;} public long getEntityNetID(){return 0;} public boolean isMeta(){return false;}
 public boolean isEntityValid(){return true;} public boolean isValidEngineEntity(){return addedToEngine&&!scheduledForEngineRemoval&&!removingFromEngine;}
 public zombie.iso.IsoGridSquare getSquare(){return null;} public float getX(){return 0;} public float getY(){return 0;} public float getZ(){return 0;}
 public GameEntityType getGameEntityType(){return GameEntityType.MetaEntity;} public boolean isOutside(){return false;}
 public boolean isUsingPlayer(zombie.characters.IsoPlayer p){return false;} public zombie.characters.IsoPlayer getUsingPlayer(){return usingPlayer;} public void setUsingPlayer(zombie.characters.IsoPlayer p){usingPlayer=p;ServerUsingPlayerIndex.changed(this);}
 public void reset(){drying=false;} public void saveEntity(java.nio.ByteBuffer b){} public void loadEntity(java.nio.ByteBuffer b,int v){}
} """,
    "zombie/iso/IsoGridSquare.java": "package zombie.iso; public class IsoGridSquare {public boolean isOutside(){return false;}public boolean isCanSee(int i){return true;}}",
    "zombie/iso/IsoObject.java": "package zombie.iso; public class IsoObject extends zombie.entity.GameEntity {}",
    "zombie/inventory/InventoryItem.java": "package zombie.inventory; public class InventoryItem extends zombie.entity.GameEntity {}",
    "zombie/vehicles/VehiclePart.java": "package zombie.vehicles; public class VehiclePart extends zombie.entity.GameEntity {}",
    "zombie/util/lambda/Invokers.java": "package zombie.util.lambda; public class Invokers {public static class Params2 {public static class Boolean {public interface ICallback<A,B> {boolean accept(A a,B b);}}}}",
    "zombie/characters/IsoPlayer.java": "package zombie.characters; public class IsoPlayer {public static int numPlayers;public static IsoPlayer[] players=new IsoPlayer[0];public boolean dead,ghost,invisible,visible;public Object vehicle;public float x,y,dist;public float getX(){return x;}public float getY(){return y;}public boolean isDead(){return dead;}public boolean isGhostMode(){return ghost;}public boolean isInvisible(){return invisible;}public Object getVehicle(){return vehicle;}public float DistToSquared(Object o){return dist;}}",
    "zombie/characters/animals/IsoAnimal.java": """package zombie.characters.animals; public class IsoAnimal {
 public short id; public float x,y; public final AI ai=new AI(); public float getX(){return x;}public float getY(){return y;}public short getOnlineID(){return id;}public AI getNetworkCharacterAI(){return ai;}
 public static class AI {public boolean urgent;public boolean isAnimalNeedExtraUpdate(zombie.core.raknet.UdpConnection c,boolean s){return urgent;} public void setAnimalPacket(zombie.core.raknet.UdpConnection c){}}
} """,
    "zombie/core/utils/UpdateLimit.java": "package zombie.core.utils; public class UpdateLimit {public static long now;public long next;public long period; public UpdateLimit(long p){period=p;} public boolean Check(){return now>=next;} public void Reset(){next=now+period;} public void Reset(long p){period=p;Reset();}}",
    "zombie/iso/Vector3.java": "package zombie.iso; public class Vector3 {public float x,y,z;public Vector3(float x,float y,float z){this.x=x;this.y=y;this.z=z;}}",
    "zombie/iso/IsoUtils.java": "package zombie.iso; public class IsoUtils {public static float DistanceManhatten(float a,float b,float c,float d){return Math.abs(a-c)+Math.abs(b-d);}}",
    "zombie/network/IConnection.java": "package zombie.network; public interface IConnection {long getConnectedGUID(); Object getPacket(PacketTypes.PacketType t);}",
    "zombie/network/PacketTypes.java": "package zombie.network; public class PacketTypes {public enum PacketType {AnimalUpdateReliable,AnimalUpdateUnreliable}}",
    "zombie/network/GameServer.java": "package zombie.network; public class GameServer {public static boolean server=true;public static final java.util.ArrayList<zombie.characters.IsoPlayer> Players=new java.util.ArrayList<>();public static final Engine udpEngine=new Engine();public static class Engine {public final java.util.ArrayList<zombie.core.raknet.UdpConnection> connections=new java.util.ArrayList<>();}}",
    "zombie/network/ServerLOS.java": "package zombie.network; public class ServerLOS {public static final ServerLOS instance=new ServerLOS();public int checks;public boolean isCouldSee(zombie.characters.IsoPlayer p,zombie.iso.IsoGridSquare s){checks++;return p!=null&&p.visible;}}",
    "zombie/core/raknet/UdpConnection.java": """package zombie.core.raknet; public class UdpConnection implements zombie.network.IConnection {
 public final zombie.iso.Vector3[] connectArea=new zombie.iso.Vector3[4],releventPos=new zombie.iso.Vector3[4];public final zombie.characters.IsoPlayer[] players=new zombie.characters.IsoPlayer[4];public int range=7; public boolean connected=true;public long guid;
 public final java.util.Map<Short,zombie.core.utils.UpdateLimit> timerUpdateAnimal=new java.util.HashMap<>();
 public final zombie.network.packets.character.AnimalUpdateReliablePacket reliable=new zombie.network.packets.character.AnimalUpdateReliablePacket();
 public final zombie.network.packets.character.AnimalUpdateUnreliablePacket unreliable=new zombie.network.packets.character.AnimalUpdateUnreliablePacket();
 public Object getPacket(zombie.network.PacketTypes.PacketType t){return t==zombie.network.PacketTypes.PacketType.AnimalUpdateReliable?reliable:unreliable;}
 public long getConnectedGUID(){return guid;}public boolean isFullyConnected(){return connected;}public int getRelevantRange(){return range;}public zombie.characters.IsoPlayer getPlayerAt(int i){return players[i];}
 public boolean RelevantTo(float x,float y,float radius){for(int n=0;n<4;n++){if(connectArea[n]!=null){int width=(int)connectArea[n].z;int minX=zombie.core.math.PZMath.fastfloor(connectArea[n].x-width/2)*8,minY=zombie.core.math.PZMath.fastfloor(connectArea[n].y-width/2)*8;int maxX=minX+width*8,maxY=minY+width*8;if(x>=minX&&x<maxX&&y>=minY&&y<maxY)return true;}if(releventPos[n]!=null&&Math.abs(releventPos[n].x-x)<=radius&&Math.abs(releventPos[n].y-y)<=radius)return true;}return false;}
} """,
    "zombie/popman/animal/AnimalInstanceManager.java": "package zombie.popman.animal; public class AnimalInstanceManager {public static final AnimalInstanceManager instance=new AnimalInstanceManager();public static AnimalInstanceManager getInstance(){return instance;}public final java.util.Map<Short,zombie.characters.animals.IsoAnimal> animals=new java.util.HashMap<>();public zombie.characters.animals.IsoAnimal get(short id){return animals.get(id);}}",
    "zombie/network/packets/character/AnimalUpdatePacket.java": """package zombie.network.packets.character; public class AnimalUpdatePacket {
 private final java.util.HashSet<Short> requested=new java.util.HashSet<>(),updated=new java.util.HashSet<>(),pending=new java.util.HashSet<>(),deleted=new java.util.HashSet<>();
 public final java.util.ArrayList<java.util.Set<Short>> sends=new java.util.ArrayList<>();public final java.util.ArrayList<java.util.Set<Short>> requestedSends=new java.util.ArrayList<>(),deletedSends=new java.util.ArrayList<>();
 public java.util.HashSet<Short> getRequested(){return requested;}public java.util.HashSet<Short> getUpdated(){return updated;}public java.util.HashSet<Short> getPending(){return pending;}public java.util.HashSet<Short> getDeleted(){return deleted;}
 public void sendToClient(zombie.network.PacketTypes.PacketType t,zombie.core.raknet.UdpConnection c){if(updated.size()+requested.size()>150)throw new AssertionError("packet cap");sends.add(new java.util.HashSet<>(updated));requestedSends.add(new java.util.HashSet<>(requested));deletedSends.add(new java.util.HashSet<>(deleted));}public void sendToServer(zombie.network.PacketTypes.PacketType t){}
} """,
    "se/krka/kahlua/vm/LuaClosure.java": 'package se.krka.kahlua.vm; public class LuaClosure {public Runnable call;public Prototype prototype=new Prototype();public static class Prototype {public String file="fixture";} public LuaClosure(Runnable r){call=r;} public static int equalityCalls; public boolean equals(Object o){equalityCalls++;return this==o;} public int hashCode(){return System.identityHashCode(this);}}',
    "se/krka/kahlua/integration/LuaCaller.java": "package se.krka.kahlua.integration; public class LuaCaller {public void protectedCallVoid(Object t,se.krka.kahlua.vm.LuaClosure c,Object[] p){c.call.run();}}",
    "se/krka/kahlua/vm/KahluaTable.java": "package se.krka.kahlua.vm; public class KahluaTable {public void rawset(Object k,Object v){}}",
    "se/krka/kahlua/vm/Platform.java": "package se.krka.kahlua.vm; public interface Platform {KahluaTable newTable();}",
    "se/krka/kahlua/vm/JavaFunction.java": "package se.krka.kahlua.vm; public interface JavaFunction {int call(LuaCallFrame f,int n);}",
    "se/krka/kahlua/vm/LuaCallFrame.java": "package se.krka.kahlua.vm; public class LuaCallFrame {public Object get(int i){return null;}}",
    "se/krka/kahlua/luaj/compiler/LuaCompiler.java": "package se.krka.kahlua.luaj.compiler; public class LuaCompiler {public static boolean rewriteEvents;}",
    "zombie/Lua/LuaManager.java": "package zombie.Lua; public class LuaManager {public static Object thread;}",
}
for packet in ("AnimalUpdateReliablePacket", "AnimalUpdateUnreliablePacket"):
    STUBS[f"zombie/network/packets/character/{packet}.java"] = f"package zombie.network.packets.character; public class {packet} extends AnimalUpdatePacket {{}}"

ENTITY_HARNESS = r"""
package zombie.entity;
import java.util.*; import zombie.entity.util.ImmutableArray; import zombie.network.GameServer;
public class SimulationIndexTest {
 static int assertions; static void check(boolean b,String s){assertions++;if(!b)throw new AssertionError(s);}
 static class Bucket extends EntityBucket.CustomBucket {Bucket(){super(0,e->e.accepted);}}
 static MetaEntity meta(long id)throws Exception {MetaEntity m=MetaEntity.alloc();identity(m,id);return m;}
 static void identity(MetaEntity m,long id)throws Exception {var b=java.nio.ByteBuffer.allocate(40);b.putLong(id).put((byte)0).putFloat(0).putFloat(0).putFloat(0).put((byte)0).flip();m.loadMetaEntity(b,249);}
 static void time(long end,int ticks)throws Exception {for(var v:Map.of("totalSimulationTicks",end,"simulationTicksThisFrame",(long)ticks).entrySet()){var f=EntitySimulation.class.getDeclaredField(v.getKey());f.setAccessible(true);if(v.getKey().equals("simulationTicksThisFrame"))f.setInt(null,ticks);else f.setLong(null,v.getValue());}}
 static List<GameEntity> list(ImmutableArray<GameEntity> a){var r=new ArrayList<GameEntity>();for(int i=0;i<a.size();i++)r.add(a.get(i));return r;}
 static void verify(Bucket b,long end,int ticks)throws Exception {
  time(end,ticks);var expected=new ArrayList<GameEntity>();for(GameEntity e:list(b.getEntities()))if(!e.isMeta()||e.drying||!MetaSimulationThrottle.shouldSkip(e))expected.add(e);
  check(list(b.getSimulationEntities()).equals(expected),"ordered indexed candidates vs exhaustive reference "+end+"/"+ticks);
  var full=new ArrayList<String>();for(GameEntity e:list(b.getEntities()))if(e.isValidEngineEntity()&&!MetaSimulationThrottle.shouldSkip(e))full.add(System.identityHashCode(e)+":"+EntitySimulation.getEffectiveSimulationTicksThisFrame());
  var due=new ArrayList<String>();for(GameEntity e:list(b.getSimulationEntities()))if(e.isValidEngineEntity()&&!MetaSimulationThrottle.shouldSkip(e))due.add(System.identityHashCode(e)+":"+EntitySimulation.getEffectiveSimulationTicksThisFrame());
  check(due.equals(full),"same updates/order/owed ticks");
 }
 public static void main(String[]args)throws Exception {
  var b=new Bucket();var random=new Random(6821);for(int i=0;i<300;i++){GameEntity e=i%9==0?new GameEntity():meta(random.nextInt(10000)-5000);e.drying=i%23==0;b.updateMembership(e);}
  for(int i=0;i<400;i++){verify(b,50+i,1+i%25);var all=list(b.getEntities());GameEntity e=all.get(random.nextInt(all.size()));switch(i%4){case 0:e.accepted=false;b.updateMembership(e);e.accepted=true;b.updateMembership(e);break;case 1:e.drying=!e.drying;b.updateMembership(e);break;case 2:if(e instanceof MetaEntity m)identity(m,random.nextInt(10000)-5000);break;case 3:e.scheduledForEngineRemoval=!e.scheduledForEngineRemoval;break;}}
  MetaEntity pooled=meta(-42);b.updateMembership(pooled);verify(b,1000,2);pooled.accepted=false;b.updateMembership(pooled);MetaEntity.release(pooled);MetaEntity reentered=MetaEntity.alloc();check(reentered==pooled,"actual pool reuse");identity(reentered,839);reentered.accepted=true;b.updateMembership(reentered);verify(b,1001,1);
  GameServer.server=false;check(b.getSimulationEntities()==b.getEntities(),"client/SP unchanged view");GameServer.server=true;
  var big=new Bucket();for(int i=0;i<30000;i++)big.updateMembership(meta(i));time(100,1);check(big.getSimulationEntities().size()==3000,"30,000 meta entities -> 3,000 due candidates");
  long sorted=zombie.ApocBRServerTelemetryLite.counts.get("entities.simulationIndexSorted");big.getSimulationEntities();check(zombie.ApocBRServerTelemetryLite.counts.get("entities.simulationIndexSorted")==sorted,"stable group order reused without per-frame sorting");
  check(zombie.ApocBRServerTelemetryLite.counts.get("entities.simulationSkippedByIndex")>=27000,"measured skipped traversal");
  for(GameEntity e:list(big.getEntities())){e.accepted=false;big.updateMembership(e);}for(GameEntity e:list(b.getEntities())){e.accepted=false;b.updateMembership(e);}
  var f=ServerEntitySimulationIndex.class.getDeclaredField("owners");f.setAccessible(true);check(((Map<?,?>)f.get(null)).isEmpty(),"removed lifetimes release global ownership references");
  System.out.println("Passed "+assertions+" entity index assertions.");
 }
} """

DISPATCH_HARNESS = r"""
package zombie.Lua;
import java.util.*;import se.krka.kahlua.vm.*;import se.krka.kahlua.integration.LuaCaller;
public class DispatchTest {
 static int assertions; static void check(boolean b,String s){assertions++;if(!b)throw new AssertionError(s);}
 static List<Integer> run(boolean production,int scenario){Event e=new Event("OnTick",0);var trace=new ArrayList<Integer>();var closures=new ArrayList<LuaClosure>();int[] calls=new int[5];
  for(int k=0;k<5;k++){final int id=k;closures.add(new LuaClosure(()->{trace.add(id);if(++calls[id]!=1)return;switch(scenario){case 1:if(id==0)e.callbacks.remove(closures.get(0));break;case 2:if(id==0)e.callbacks.remove(closures.get(2));break;case 3:if(id==0)e.callbacks.add(closures.get(4));break;case 4:if(id==0)e.callbacks.subList(0,1).set(0,closures.get(4));break;case 5:if(id==0)e.callbacks.subList(1,3).clear();break;case 6:if(id==0)e.callbacks.clear();break;case 7:if(id==0)e.callbacks.listIterator().next();break;case 8:if(id==0)e.callbacks.removeIf(c->c==closures.get(2));break;case 9:if(id==0)e.callbacks.replaceAll(c->c==closures.get(0)?closures.get(4):c);break;case 10:if(id==0)e.callbacks.subList(0,3).replaceAll(c->c==closures.get(0)?closures.get(4):c);break;case 11:if(id==0){var it=e.callbacks.listIterator();it.next();it.set(closures.get(4));}break;}}));}
  e.callbacks.addAll(closures.subList(0,4));if(scenario==0)e.callbacks.add(closures.get(0));
  if(production)e.trigger(null,new LuaCaller(),new Object[0]);else for(int n=0;n<e.callbacks.size();n++){LuaClosure c=e.callbacks.get(n);try{c.call.run();}catch(Exception ignored){}if(!e.callbacks.contains(c))n--;}
  trace.add(-1);for(LuaClosure c:e.callbacks)trace.add(closures.indexOf(c));return trace;
 }
 public static void main(String[]args){for(boolean slow:new boolean[]{false,true}){zombie.debug.DebugOptions.instance.checks.slowLuaEvents.value=slow;for(int scenario=0;scenario<12;scenario++)check(run(true,scenario).equals(run(false,scenario)),"differential event mutation "+scenario+" slow="+slow);}
  var a=new zombie.util.list.MutationTrackedArrayList<Integer>();a.addAll(List.of(1,2,3,4));long v=a.mutationVersion();a.subList(1,4).subList(0,1).set(0,9);check(a.mutationVersion()!=v,"nested backed sublist replacement tracked");check(a.equals(List.of(1,9,3,4)),"backed semantics");
  var stable=new Event("OnTick",0);for(int i=0;i<3000;i++)stable.callbacks.add(new LuaClosure(()->{}));LuaClosure.equalityCalls=0;stable.trigger(null,new LuaCaller(),new Object[0]);check(LuaClosure.equalityCalls==0,"stable dispatch performs zero callback membership searches");
  System.out.println("Passed "+assertions+" Lua mutation assertions.");}
} """

COVERAGE_HARNESS = r"""
package zombie.popman.animal;
import java.util.*;import zombie.core.raknet.UdpConnection;import zombie.characters.animals.IsoAnimal;import zombie.iso.Vector3;
public class AnimalCoverageTest {
 static int assertions; static void check(boolean b,String s){assertions++;if(!b)throw new AssertionError(s);}
 static IsoAnimal animal(short id,float x,float y){var a=new IsoAnimal();a.id=id;a.x=x;a.y=y;AnimalInstanceManager.instance.animals.put(id,a);return a;}
 public static void main(String[]args)throws Exception {
  var grid=new ServerAnimalUpdateCoverage();var random=new Random(8527);var connections=new ArrayList<UdpConnection>();var changed=new HashSet<Short>();
  for(int i=0;i<25;i++){var c=new UdpConnection();c.guid=i;c.range=random.nextInt(30)-2;for(int s=0;s<4;s++){if(random.nextBoolean())c.releventPos[s]=new Vector3(random.nextInt(4000)-2000,random.nextInt(4000)-2000,0);if(random.nextBoolean())c.connectArea[s]=new Vector3(random.nextInt(500)-250,random.nextInt(500)-250,random.nextInt(25));}connections.add(c);}
  for(short id=0;id<1000;id++){animal(id,random.nextInt(5000)-2500,random.nextInt(5000)-2500);changed.add(id);}
  for(int pass=0;pass<70;pass++){grid.prepare(connections,changed);for(var c:connections){var expected=new ArrayList<Short>();for(short id:changed){var a=AnimalInstanceManager.instance.get(id);if(c.connected&&c.RelevantTo(a.x,a.y,(c.range-2)*10))expected.add(id);}var filtered=new ArrayList<Short>();for(short id:grid.candidates(c)){var a=AnimalInstanceManager.instance.get(id);if(c.RelevantTo(a.x,a.y,(c.range-2)*10))filtered.add(id);}check(filtered.equals(expected),"exact relevance and original order after conservative filtering");check(new HashSet<>(grid.candidates(c)).size()==grid.candidates(c).size(),"split-screen overlaps deduplicated");}
   var c=connections.get(random.nextInt(connections.size()));c.connected=random.nextBoolean();c.range=random.nextInt(35)-3;c.releventPos[random.nextInt(4)]=new Vector3(random.nextInt(4000)-2000,random.nextInt(4000)-2000,0);var a=AnimalInstanceManager.instance.get((short)random.nextInt(1000));a.x+=100;a.y-=200;}
  connections.clear();var c=new UdpConnection();c.releventPos[0]=new Vector3(-64,64,0);c.range=2;connections.add(c);animal((short)1200,-64,64);changed.clear();changed.add((short)1200);grid.prepare(connections,changed);check(grid.candidates(c).contains((short)1200),"inclusive zero radius at negative cell boundary");
  c.releventPos[0]=new Vector3(0,0,0);c.connectArea[0]=new Vector3(0,0,1000000);animal((short)1200,30000,30000);grid.prepare(connections,changed);check(grid.candidates(c).contains((short)1200),"huge coverage bounded fallback");
  c.connectArea[0]=null;for(float x:new float[]{Math.nextDown(-137438953472f),-137438953472f,Math.nextUp(-137438953472f),Float.MAX_VALUE,-Float.MAX_VALUE,Float.NaN,Float.POSITIVE_INFINITY}){c.releventPos[0]=new Vector3(x,0,0);animal((short)1200,x,0);grid.prepare(connections,changed);if(c.RelevantTo(x,0,(c.range-2)*10))check(grid.candidates(c).contains((short)1200),"extreme-coordinate conservative coverage");}
  var sparse=new ServerAnimalUpdateCoverage();var distant=new ArrayList<UdpConnection>();var many=new HashSet<Short>();for(int i=0;i<100;i++){var d=new UdpConnection();d.releventPos[0]=new Vector3(i*10000,0,0);distant.add(d);}for(short id=0;id<30000;id++){animal(id,id*30,0);many.add(id);}long before=zombie.ApocBRServerTelemetryLite.counts.getOrDefault("animals.sync.coverageCandidatesVisited",0L);sparse.prepare(distant,many);long visits=zombie.ApocBRServerTelemetryLite.counts.get("animals.sync.coverageCandidatesVisited")-before;check(visits<10000,"sparse 30,000 animals / 100 connections avoids full 3,000,000 pair scan");
  // Exercise the actual manager's overflow, timer, deletion, request and urgent paths.
  AnimalInstanceManager.instance.animals.clear();zombie.network.GameServer.udpEngine.connections.clear();c=new UdpConnection();c.guid=500;c.releventPos[0]=new Vector3(0,0,0);zombie.network.GameServer.udpEngine.connections.add(c);
  var manager=AnimalSynchronizationManager.getInstance();var ids=new HashSet<Short>();for(short id=0;id<400;id++){animal(id,0,0);ids.add(id);}
  zombie.core.utils.UpdateLimit.now=0;manager.setSendToClients(ids);manager.update();check(c.reliable.sends.isEmpty(),"new normal timers wait original interval");
  zombie.core.utils.UpdateLimit.now=2000;manager.setSendToClients(ids);manager.update();check(c.reliable.sends.size()==3,"400 due updates split into bounded batches");var sent=new HashSet<Short>();for(var batch:c.reliable.sends)sent.addAll(batch);check(sent.equals(ids),"overflow batches lose no IDs");
  zombie.core.utils.UpdateLimit.now=2001;AnimalInstanceManager.instance.get((short)3).ai.urgent=true;manager.setSendToClients(ids);manager.update();check(c.unreliable.sends.size()==1&&c.unreliable.sends.get(0).equals(Set.of((short)3)),"urgent update bypasses timer");
  manager.setRequested(c,new HashSet<>(Set.of((short)7)));manager.delete((short)8);manager.update();check(c.unreliable.requestedSends.get(c.unreliable.requestedSends.size()-1).contains((short)7),"requests retained");check(c.unreliable.deletedSends.get(c.unreliable.deletedSends.size()-1).contains((short)8),"deletions retained");
  connections.clear();grid.prepare(connections,List.of());var f=ServerAnimalUpdateCoverage.class.getDeclaredField("buckets");f.setAccessible(true);check(((Map<?,?>)f.get(grid)).isEmpty(),"disconnect releases spatial buckets");
  System.out.println("Passed "+assertions+" animal routing and packet assertions.");
 }
} """

LIST_HARNESS = r"""
package zombie.util.list;
import java.util.*;import zombie.iso.IsoObject;
public class SquareListTest {
 static int assertions;static void check(boolean b,String s){assertions++;if(!b)throw new AssertionError(s);}
 static class EqualObject extends IsoObject {public boolean equals(Object o){return o instanceof IsoObject;}public int hashCode(){return 0;}}
 static void verify(PZArrayList<IsoObject> a,List<IsoObject> reference,List<IsoObject> objects){check(a.size()==reference.size(),"same size");for(IsoObject o:objects)check(a.indexOf(o)==reference.indexOf(o),"indexOf exact reference including duplicates and equality overrides");check(a.indexOf(null)==reference.indexOf(null),"null index");}
 public static void main(String[]args){var a=new PZArrayList<IsoObject>(IsoObject.class,100);var r=new ArrayList<IsoObject>();var objects=new ArrayList<IsoObject>();for(int i=0;i<35;i++)objects.add(new IsoObject());objects.add(new EqualObject());var random=new Random(8883);
  for(int i=0;i<25;i++){a.add(objects.get(i));r.add(objects.get(i));}verify(a,r,objects);
  for(int step=0;step<700;step++){IsoObject o=objects.get(random.nextInt(objects.size()));int index=r.isEmpty()?0:random.nextInt(r.size());switch(step%7){case 0:a.add(o);r.add(o);break;case 1:if(!r.isEmpty()){a.remove(index);r.remove(index);}break;case 2:if(!r.isEmpty()){a.set(index,o);r.set(index,o);}break;case 3:a.add(index,o);r.add(index,o);break;case 4:a.remove(o);r.remove(o);break;case 5:a.add(null);r.add(null);break;case 6:if(step<350){a.addAll(List.of(o));r.addAll(List.of(o));}else if(!r.isEmpty()){IsoObject[] raw=a.getElements();raw[index]=o;r.set(index,o);}break;}verify(a,r,objects);}
  check(zombie.ApocBRServerTelemetryLite.counts.get("isoObjects.squareIndexRebuilt")>50,"randomized mutations exercised cached mode");
  a.clear();r.clear();for(int i=0;i<25;i++){a.add(objects.get(i));r.add(objects.get(i));}verify(a,r,objects);a.ensureCapacity(1000);verify(a,r,objects);
  a.addAll(List.of(objects.get(0),objects.get(1)));r.addAll(List.of(objects.get(0),objects.get(1)));a.removeAll(Set.of(objects.get(3),objects.get(4)));r.removeAll(Set.of(objects.get(3),objects.get(4)));verify(a,r,objects);
  zombie.network.GameServer.server=false;verify(a,r,objects);System.out.println("Passed "+assertions+" mutable square list assertions.");
 }
} """


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def corpse_harness():
    relative = "zombie/iso/objects/IsoDeadBody.java"
    vanilla = (ROOT / "42.21.0/decompiled" / relative).read_text()
    patched = (ROOT / "42.21.0/src" / relative).read_text()
    return """package zombie.iso.objects;
import zombie.network.GameServer; import zombie.network.ServerLOS; import zombie.characters.IsoPlayer; import zombie.iso.IsoGridSquare;
public class CorpseNearbyTest {
 IsoGridSquare square; IsoGridSquare getSquare(){return square;}
""" + method(vanilla, "private boolean isPlayerNearby()").replace("isPlayerNearby()", "vanilla()", 1) + "\n" + method(patched, "private boolean isPlayerNearby()").replace("isPlayerNearby()", "optimized()", 1) + "\n" + method(patched, "private boolean isPlayerNearby(IsoPlayer player, boolean isCanSee)") + r"""
 public static void main(String[]args){var body=new CorpseNearbyTest();int assertions=0;
  for(boolean square:new boolean[]{false,true})for(float distance:new float[]{0,3.999f,4,16,16.001f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY})for(int flags=0;flags<32;flags++){
   var player=new IsoPlayer();player.dist=distance;player.dead=(flags&1)!=0;player.ghost=(flags&2)!=0;player.invisible=(flags&4)!=0;player.vehicle=(flags&8)!=0?new Object():null;player.visible=(flags&16)!=0;
   GameServer.Players.clear();GameServer.Players.add(player);body.square=square?new IsoGridSquare():null;ServerLOS.instance.checks=0;boolean expected=body.vanilla();int oldChecks=ServerLOS.instance.checks;ServerLOS.instance.checks=0;boolean actual=body.optimized();
   if(expected!=actual||ServerLOS.instance.checks>oldChecks)throw new AssertionError("corpse wakeup predicate changed "+distance+"/"+flags);assertions++;
  }
  GameServer.Players.clear();GameServer.Players.add(null);body.square=new IsoGridSquare();if(body.vanilla()!=body.optimized())throw new AssertionError("null player");assertions++;
  GameServer.Players.clear();var far=new IsoPlayer();far.dist=10000;far.visible=true;GameServer.Players.add(far);ServerLOS.instance.checks=0;body.optimized();if(ServerLOS.instance.checks!=0)throw new AssertionError("distant corpse check still queried LOS");assertions++;
  System.out.println("Passed "+assertions+" production corpse perception assertions.");
 }
} """


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-simulation-tests-") as directory:
        work = Path(directory)
        sources = []
        files = dict(STUBS)
        files.update({"zombie/entity/SimulationIndexTest.java": ENTITY_HARNESS,
                      "zombie/Lua/DispatchTest.java": DISPATCH_HARNESS,
                      "zombie/popman/animal/AnimalCoverageTest.java": COVERAGE_HARNESS,
                      "zombie/util/list/SquareListTest.java": LIST_HARNESS,
                      "zombie/iso/objects/CorpseNearbyTest.java": corpse_harness()})
        for relative, content in files.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        src = ROOT / "42.21.0/src/zombie"
        sources.extend(src / name for name in ["entity/EntityBucket.java", "entity/ServerUsingPlayerIndex.java", "entity/MetaEntity.java",
            "entity/EntitySimulation.java", "entity/MetaSimulationThrottle.java", "entity/ServerEntitySimulationIndex.java",
            "Lua/Event.java", "util/list/MutationTrackedArrayList.java", "util/list/PZArrayList.java",
            "popman/animal/ServerAnimalUpdateCoverage.java", "popman/animal/AnimalSynchronizationManager.java"])
        output = work / "classes"
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in sources), encoding="utf-8")
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        for harness in ("zombie.entity.SimulationIndexTest", "zombie.Lua.DispatchTest",
                        "zombie.popman.animal.AnimalCoverageTest", "zombie.util.list.SquareListTest",
                        "zombie.iso.objects.CorpseNearbyTest"):
            subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), harness], check=True)


if __name__ == "__main__":
    main()
