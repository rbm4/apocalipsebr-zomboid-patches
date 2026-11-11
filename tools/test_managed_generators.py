"""Production manager and extracted native setters/sync receiver, with engine doubles."""
from pathlib import Path
import subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
native=(ROOT/'42.22.2/src/zombie/iso/objects/IsoGenerator.java').read_text()
def method(sig):
 start=native.index('    '+sig); brace=native.index('{',start); end=brace+1; depth=1
 while depth:
  depth+=(native[end]=='{')-(native[end]=='}');end+=1
 return native[start:end]
methods='\n'.join(method(s) for s in ['public void setActivated(boolean activated)','public void setConnected(boolean connected)','public void setFuel(float fuel)','public void setCondition(int condition)','public void syncIsoObjectReceive(ByteBufferReader bb, UdpConnection source)','public void remove()'])
update=method('public void update()')
files={
'zombie/debug/DebugLog.java':'package zombie.debug; public class DebugLog {public static java.util.List<String> lines=new java.util.ArrayList<>();public static void log(String s){lines.add(s);}}',
'zombie/network/GameServer.java':'package zombie.network; public class GameServer {public static boolean server=true;public static void PlayWorldSoundServer(Object... a){} }',
'zombie/network/GameClient.java':'package zombie.network; public class GameClient {public static boolean client=false;}',
'zombie/GameTime.java':'package zombie; public class GameTime {public static double hours=1000;public static GameTime getInstance(){return new GameTime();}public double getWorldAgeHours(){return hours;}}',
 'zombie/WorldSoundManager.java':'package zombie; public class WorldSoundManager {public static WorldSoundManager instance=new WorldSoundManager();public static int sounds;public void addSoundRepeating(Object... a){sounds++;}public void addSound(Object... a){}}',
'zombie/SandboxOptions.java':'package zombie; public class SandboxOptions {public static SandboxOptions instance=new SandboxOptions();public Option generatorFuelConsumption=new Option();public static class Option{public double getValue(){return 1;}}}',
'zombie/core/math/PZMath.java':'package zombie.core.math; public class PZMath {public static int fastfloor(float f){return (int)f;}}',
'zombie/core/random/Rand.java':'package zombie.core.random; public class Rand {public static int calls;public static int Next(int n){calls++;return 1;}}',
'zombie/iso/objects/IsoFireManager.java':'package zombie.iso.objects; public class IsoFireManager {public static void StartFire(Object... a){throw new AssertionError("managed fire");}}',
'zombie/scripting/objects/Item.java':'package zombie.scripting.objects; public class Item {public int getSoundRadius(){return 20;}public int getSoundVolume(){return 20;}public int getConditionLowerChance(){return 30;}}',
'zombie/scripting/ScriptManager.java':'package zombie.scripting; public class ScriptManager {public static ScriptManager instance=new ScriptManager();public zombie.scripting.objects.Item getItem(String type){return new zombie.scripting.objects.Item();}}',
'zombie/Lua/LuaEventManager.java':'package zombie.Lua; public class LuaEventManager {public static void triggerEvent(String s,Object... a){}}',
'zombie/core/logger/ExceptionLogger.java':'package zombie.core.logger; public class ExceptionLogger {public static void logException(Throwable t){}}',
'zombie/core/raknet/UdpConnection.java':'package zombie.core.raknet; public class UdpConnection {}',
'zombie/core/network/ByteBufferReader.java':'package zombie.core.network; public class ByteBufferReader {public int reads;public float getFloat(){reads++;return 0;}public int getInt(){reads++;return 0;}public boolean getBoolean(){reads++;return false;}}',
'zombie/iso/SpriteDetails/IsoFlagType.java':'package zombie.iso.SpriteDetails; public enum IsoFlagType {exterior}',
'zombie/iso/ModData.java':'package zombie.iso; public class ModData {java.util.Map<Object,Object> m=new java.util.HashMap<>();public Object rawget(Object k){return m.get(k);}public void rawset(Object k,Object v){m.put(k,v);}}',
'zombie/inventory/InventoryItem.java':'package zombie.inventory; public class InventoryItem {public int condition;private zombie.iso.ModData d=new zombie.iso.ModData();public void setCondition(int c){condition=c;}public zombie.iso.ModData getModData(){return d;}}',
'zombie/inventory/InventoryItemFactory.java':'package zombie.inventory; public class InventoryItemFactory {public static InventoryItem CreateItem(String id){return new InventoryItem();}}',
'zombie/iso/IsoChunk.java':'package zombie.iso; public class IsoChunk {public boolean loaded=true;}',
'zombie/iso/IsoWorld.java':'package zombie.iso; public class IsoWorld {public static IsoWorld instance=new IsoWorld();public boolean valid=true;public Object getMetaGrid(){return this;}public boolean isValidSquare(int x,int y,int z){return valid && x<10000 && y<10000 && z>=-32 && z<=31;}public Object getCell(){return this;}}',
'zombie/iso/IsoObject.java':'''package zombie.iso; public class IsoObject {protected IsoGridSquare square;private ModData d=new ModData();public int saves,syncs;public float getX(){return square.x;}public float getY(){return square.y;}public float getZ(){return square.z;}public IsoGridSquare getSquare(){return square;}public ModData getModData(){return d;}public int getObjectIndex(){return square==null?-1:square.objects.indexOf(this);}public void flagForHotSave(){saves++;}public void sync(){syncs++;}}''',
'zombie/iso/IsoGridSquare.java':'''package zombie.iso; public class IsoGridSquare {public boolean floor=true,solid,solidTrans;public boolean TreatAsSolidFloor(){return floor;}public boolean isSolid(){return solid;}public boolean isSolidTrans(){return solidTrans;}public final int x,y,z;public java.util.List<IsoObject> objects=new java.util.ArrayList<>();public IsoGridSquare(int x,int y,int z){this.x=x;this.y=y;this.z=z;}public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}public java.util.List<IsoObject> getObjects(){return objects;}public static class Props{public boolean has(Object p){return true;}}public static class Building{public void setToxic(boolean b){}}public Object getRoom(){return null;}public Props getProperties(){return new Props();}public Building getBuilding(){return null;}public void transmitRemoveItemFromSquare(IsoObject o){if(zombie.network.ApocBRManagedGenerators.denyRemoval(o))return;objects.remove(o);zombie.network.ApocBRManagedGenerators.onRemoved((zombie.iso.objects.IsoGenerator)o);}}''',
'zombie/network/ServerMap.java':'''package zombie.network; public class ServerMap {public static ServerMap instance=new ServerMap();public java.util.Map<String,zombie.iso.IsoGridSquare> squares=new java.util.HashMap<>();public zombie.iso.IsoGridSquare getGridSquare(int x,int y,int z){return squares.get(x+":"+y+":"+z);}public java.util.Set<String> loadedChunks=new java.util.HashSet<>();public zombie.iso.IsoChunk getChunk(int x,int y){return loadedChunks.contains(x+":"+y)?new zombie.iso.IsoChunk():null;}public void put(zombie.iso.IsoGridSquare s){squares.put(s.x+":"+s.y+":"+s.z,s);loadedChunks.add(s.x/8+":"+s.y/8);}}''',
'zombie/network/ApocBRLobbyStreaming.java':'package zombie.network; public class ApocBRLobbyStreaming {public static int requests;public static boolean invalid,forceLoading;public static int keepCellLoaded(int x,int y){requests++;return invalid?-1:forceLoading?0:ServerMap.instance.getChunk(x/8,y/8)!=null?1:0;}}',
'zombie/iso/objects/IsoGenerator.java':'''package zombie.iso.objects;
import zombie.iso.*;import zombie.network.*;import zombie.inventory.*;import zombie.GameTime;import zombie.scripting.objects.Item;import zombie.scripting.ScriptManager;import zombie.WorldSoundManager;import zombie.core.math.PZMath;import zombie.core.random.Rand;import zombie.SandboxOptions;import zombie.Lua.LuaEventManager;import zombie.core.logger.ExceptionLogger;import zombie.iso.SpriteDetails.IsoFlagType;import zombie.core.network.ByteBufferReader;import zombie.core.raknet.UdpConnection;
public class IsoGenerator extends IsoObject {
public float fuel;private float totalPowerUsing=0.1F;public int condition;public boolean activated,connected;private int lastHour;private boolean updateSurrounding=true;public int coverage,vanillaUpdates;public static int created;private Emitter emitter;private static class Emitter{public boolean isPlaying(String s){return true;}public void tick(){}}public String getGeneratorItemType(){return "Base.Generator";}public String getSoundPrefix(){return "Generator";}public Object getCell(){return null;}public void explode(Object s){throw new AssertionError("managed explosion");}
public IsoGenerator(InventoryItem item,Object cell,IsoGridSquare sq){created++;square=sq;sq.objects.add(this);condition=item.condition;fuel=((Double)item.getModData().rawget("fuel")).floatValue();ApocBRManagedGenerators.onAdded(this);}
public void setSurroundingElectricity(){coverage++;}public boolean isActivated(){return activated;}private void playGeneratorSound(String s){}private void stopAllSounds(){}private void updateFridgeFreezerItems(){}
'''+methods+'\n'+update+'\n}',
'Test.java':'''import zombie.network.*;import zombie.iso.*;import zombie.iso.objects.*;import zombie.inventory.*;import zombie.core.network.*;
public class Test {
static void check(boolean b){if(!b)throw new AssertionError();}
static IsoGenerator generator(IsoGridSquare s){InventoryItem item=new InventoryItem();item.condition=100;item.getModData().rawset("fuel",10.0);return new IsoGenerator(item,null,s);}
public static void main(String[] args)throws Exception {
ApocBRManagedGenerators.initialize();check(!ApocBRManagedGenerators.registerGenerator("bad",-1,2,0));check(ApocBRManagedGenerators.registerGenerator("lobby",10,20,0));check(ApocBRManagedGenerators.registerGenerator("lobby",10,20,0));check(!ApocBRManagedGenerators.registerGenerator("duplicate",10,20,0));check(!ApocBRManagedGenerators.registerGenerator("lobby",11,20,0));
ApocBRManagedGenerators.update();ApocBRManagedGenerators.update();check(ApocBRLobbyStreaming.requests>0);IsoGridSquare square=new IsoGridSquare(10,20,0);ServerMap.instance.put(square);ApocBRManagedGenerators.update();check(square.objects.size()==1);IsoGenerator g=(IsoGenerator)square.objects.get(0);check(ApocBRManagedGenerators.getStatus("lobby").equals("READY")&&g.activated&&g.connected&&g.fuel==10&&g.condition==100&&ApocBRManagedGenerators.isManagedGenerator(g));
int created=IsoGenerator.created,saves=g.saves;for(int i=0;i<100;i++){ApocBRManagedGenerators.update();g.update();}check(IsoGenerator.created==created&&g.saves==saves&&zombie.WorldSoundManager.sounds==100);
zombie.GameTime.hours=2000;g.update();check(g.fuel==10&&g.condition==100&&g.activated&&zombie.core.random.Rand.calls==0);
g.setActivated(false);g.setConnected(false);g.setFuel(0);g.setCondition(0);g.remove();check(g.activated&&g.connected&&g.fuel==10&&g.condition==100&&square.objects.contains(g));ByteBufferReader reader=new ByteBufferReader();g.syncIsoObjectReceive(reader,null);check(reader.reads==4&&g.activated&&g.connected&&g.fuel==10&&g.condition==100);
g.getModData().rawset("ApocBRManagedGeneratorId",null);check(ApocBRManagedGenerators.isManagedGenerator(g));g.update();check(g.getModData().rawget("ApocBRManagedGeneratorId").equals("lobby"));
final boolean[] denied={false};Thread t=new Thread(()->{try{ApocBRManagedGenerators.registerGenerator("worker",1,1,0);}catch(IllegalStateException e){denied[0]=true;}});t.start();t.join();check(denied[0]);
ApocBRManagedGenerators.onRemoved(g);square.objects.remove(g);check(!ApocBRManagedGenerators.isManagedGenerator(g));IsoGenerator saved=generator(square);saved.getModData().rawset("ApocBRManagedGeneratorId","lobby");ApocBRManagedGenerators.onAdded(saved);created=IsoGenerator.created;ApocBRManagedGenerators.update();check(IsoGenerator.created==created&&ApocBRManagedGenerators.isManagedGenerator(saved));check(ApocBRManagedGenerators.unregisterGenerator("lobby")&&square.objects.isEmpty());
IsoGridSquare other=new IsoGridSquare(30,40,0);ServerMap.instance.put(other);IsoGenerator ordinary=generator(other);check(ApocBRManagedGenerators.registerGenerator("occupied",30,40,0));ApocBRManagedGenerators.update();ApocBRManagedGenerators.update();check(other.objects.size()==1&&ApocBRManagedGenerators.getStatus("occupied").equals("CREATION_DENIED_EXISTING_GENERATOR"));ordinary.setFuel(1);ordinary.setActivated(true);zombie.GameTime.hours=3000;ordinary.update();check(ordinary.fuel==0&&!ordinary.activated&&zombie.core.random.Rand.calls>0);
IsoGridSquare ss=new IsoGridSquare(50,60,0);ServerMap.instance.put(ss);IsoGenerator stale=generator(ss);stale.getModData().rawset("ApocBRManagedGeneratorId","deleted");stale.activated=true;ApocBRManagedGenerators.onAdded(stale);ApocBRManagedGenerators.update();check(!stale.activated&&ss.objects.contains(stale));ApocBRManagedGenerators.finishStartup();ApocBRManagedGenerators.update();check(ss.objects.isEmpty());IsoWorld.instance.valid=false;check(!ApocBRManagedGenerators.registerGenerator("outside",1,1,0));IsoWorld.instance.valid=true;
int before=IsoGenerator.created;
IsoGridSquare noFloor=new IsoGridSquare(100,100,2);noFloor.floor=false;ServerMap.instance.put(noFloor);check(ApocBRManagedGenerators.registerGenerator("no-floor",100,100,2));
IsoGridSquare blocked=new IsoGridSquare(120,120,0);blocked.solid=true;ServerMap.instance.put(blocked);check(ApocBRManagedGenerators.registerGenerator("solid",120,120,0));
check(ApocBRManagedGenerators.registerGenerator("no-square",100,100,3));
for(int i=0;i<12;i++)ApocBRManagedGenerators.update();
check(ApocBRManagedGenerators.getStatus("no-floor").equals("CREATION_DENIED_NO_SOLID_FLOOR"));
check(ApocBRManagedGenerators.getStatus("solid").equals("CREATION_DENIED_SOLID_TILE"));
check(ApocBRManagedGenerators.getStatus("no-square").equals("CREATION_DENIED_SQUARE_MISSING_AFTER_PREWARM"));
check(IsoGenerator.created==before && !ApocBRManagedGenerators.registerGenerator("no-floor",100,100,2));
check(zombie.debug.DebugLog.lines.stream().anyMatch(line->line.contains("id=no-floor tile=100,100,2")));
ApocBRManagedGenerators.initialize();
for(int i=0;i<10;i++)check(ApocBRManagedGenerators.registerGenerator("waiting-"+i,3000+16*i,4000,0));
for(int i=0;i<10;i++){int requests=ApocBRLobbyStreaming.requests;ApocBRManagedGenerators.update();check(ApocBRLobbyStreaming.requests-requests<=4);}
int requests=ApocBRLobbyStreaming.requests;ApocBRManagedGenerators.update();check(ApocBRLobbyStreaming.requests-requests==4);
ApocBRLobbyStreaming.invalid=true;ApocBRManagedGenerators.update();check(ApocBRManagedGenerators.getStatus("waiting-0").equals("CREATION_DENIED_INVALID_MAP_CELL"));ApocBRLobbyStreaming.invalid=false;
ApocBRManagedGenerators.initialize();check(ApocBRManagedGenerators.registerGenerator("timeout",6000,6000,0));ApocBRManagedGenerators.update();
java.lang.reflect.Field definitions=ApocBRManagedGenerators.class.getDeclaredField("sources");definitions.setAccessible(true);
Object pending=((java.util.Map<?,?>)definitions.get(null)).get("timeout");java.lang.reflect.Field deadline=pending.getClass().getDeclaredField("deadline");deadline.setAccessible(true);deadline.setLong(pending,System.nanoTime()-1);
requests=ApocBRLobbyStreaming.requests;ApocBRManagedGenerators.update();check(ApocBRManagedGenerators.getStatus("timeout").equals("CREATION_DENIED_PREWARM_TIMEOUT")&&requests==ApocBRLobbyStreaming.requests);
ApocBRManagedGenerators.initialize();IsoGridSquare warmSquare=new IsoGridSquare(7000,7000,0);ServerMap.instance.put(warmSquare);check(ApocBRManagedGenerators.registerGenerator("must-warm",7000,7000,0));ApocBRLobbyStreaming.forceLoading=true;ApocBRManagedGenerators.update();ApocBRManagedGenerators.update();check(warmSquare.objects.isEmpty()&&ApocBRManagedGenerators.getStatus("must-warm").equals("PREWARM_LOADING"));ApocBRLobbyStreaming.forceLoading=false;ApocBRManagedGenerators.update();check(warmSquare.objects.size()==1&&ApocBRManagedGenerators.getStatus("must-warm").equals("READY"));
ApocBRManagedGenerators.initialize();IsoGridSquare oldSquare=new IsoGridSquare(8000,8000,0);ServerMap.instance.put(oldSquare);IsoGenerator old=generator(oldSquare);old.getModData().rawset("ApocBRManagedGeneratorId","saved-pending");old.activated=true;old.connected=true;ApocBRManagedGenerators.onAdded(old);check(ApocBRManagedGenerators.registerGenerator("saved-pending",8000,8000,0));ApocBRManagedGenerators.finishStartup();ApocBRLobbyStreaming.forceLoading=true;ApocBRManagedGenerators.update();ApocBRManagedGenerators.update();check(!old.activated&&oldSquare.objects.contains(old)&&!ApocBRManagedGenerators.isManagedGenerator(old));ApocBRLobbyStreaming.forceLoading=false;created=IsoGenerator.created;ApocBRManagedGenerators.update();check(old.activated&&IsoGenerator.created==created&&ApocBRManagedGenerators.isManagedGenerator(old));
GameServer.server=false;check(!ApocBRManagedGenerators.isManagedGenerator(saved));System.out.println("managed generator lifecycle/native mutation checks passed");
}}
'''
}
with tempfile.TemporaryDirectory(prefix='apocbr_managed_generators_') as temp:
 folder=Path(temp);sources=[]
 for name,text in files.items():
  p=folder/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);sources.append(str(p))
 sources.append(str(ROOT/'42.22.2/src/zombie/network/ApocBRManagedGenerators.java'))
 subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'-d',temp,*sources],check=True)
 subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',temp,'Test'],check=True)
