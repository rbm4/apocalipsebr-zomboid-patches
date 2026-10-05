"""Compile production helpers against small engine fixtures and check behavior/order."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "42.21.0/src"
PRODUCTION = [
    "zombie/util/list/MutationTrackedArrayList.java",
    "zombie/inventory/ServerInventoryUpdateIndex.java",
    "zombie/ServerPlayerSpatialQueries.java",
    "zombie/ServerSoundStressIndex.java",
    "zombie/popman/ServerZombiePacketPreparation.java",
    "zombie/network/NetworkVariables.java",
    "zombie/characters/NetworkZombieVariables.java",
]
STUBS = {
    "zombie/network/GameServer.java": "package zombie.network; public class GameServer {public static boolean server=true;}",
    "zombie/GameTime.java": "package zombie; public class GameTime {public static long now; private static final GameTime INSTANCE=new GameTime(); public static GameTime getInstance(){return INSTANCE;} public java.util.Calendar getCalender(){java.util.Calendar c=java.util.Calendar.getInstance();c.setTimeInMillis(now);return c;}}",
    "zombie/ApocBRServerTelemetryLite.java": """
package zombie;
public class ApocBRServerTelemetryLite {
 public static final java.util.Map<String,Long> counts=new java.util.HashMap<>();
 public static void count(String name,long value){counts.merge(name,value,Long::sum);}
 public static void recordPhase(String name,long value){}
 public static long count(String name){return counts.getOrDefault(name,0L);}
}
""",
    "zombie/interfaces/IUpdater.java": "package zombie.interfaces; public interface IUpdater {}",
    "zombie/inventory/InventoryItem.java": """
package zombie.inventory;
public class InventoryItem {public void update(){} }
""",
    "zombie/inventory/ItemContainer.java": """
package zombie.inventory;
public class ItemContainer {public java.util.ArrayList<InventoryItem> items=new ServerInventoryUpdateIndex.Items();}
""",
    "zombie/inventory/types/InventoryContainer.java": """
package zombie.inventory.types;
public class InventoryContainer extends zombie.inventory.InventoryItem {
 public zombie.inventory.ItemContainer inventory=new zombie.inventory.ItemContainer();
 public zombie.inventory.ItemContainer getInventory(){return inventory;}
}
""",
    "zombie/iso/IsoMovingObject.java": """
package zombie.iso;
public class IsoMovingObject {public float x,y; public float getX(){return x;} public float getY(){return y;}
 public float DistTo(IsoMovingObject other){return Math.abs(x-other.x)+Math.abs(y-other.y);}}
""",
    "zombie/characters/IsoGameCharacter.java": "package zombie.characters; public class IsoGameCharacter extends zombie.iso.IsoMovingObject {}",
    "zombie/vehicles/BaseVehicle.java": "package zombie.vehicles; public class BaseVehicle extends zombie.iso.IsoMovingObject {}",
    "zombie/iso/IsoCell.java": """
package zombie.iso;
public class IsoCell {
 public final java.util.ArrayList<zombie.characters.IsoZombie> zombies=new zombie.util.list.MutationTrackedArrayList<>();
 public final java.util.Set<zombie.vehicles.BaseVehicle> vehicles=new zombie.ServerPlayerSpatialQueries.Vehicles();
 public java.util.ArrayList<zombie.characters.IsoZombie> getZombieList(){return zombies;}
 public java.util.Set<zombie.vehicles.BaseVehicle> getVehicles(){return vehicles;}
}
""",
    "zombie/iso/IsoWorld.java": """
package zombie.iso; public class IsoWorld {public static final IsoWorld instance=new IsoWorld();
 public long frame; public long getFrameNo(){return frame;}}
""",
    "zombie/iso/IsoUtils.java": """
package zombie.iso; public class IsoUtils {public static float DistanceManhatten(float x,float y,float a,float b){return Math.abs(x-a)+Math.abs(y-b);}}
""",
    "zombie/WorldSoundManager.java": """
package zombie; public class WorldSoundManager {public static class WorldSound {
 public int x,y,radius; public boolean stresshumans; public float stressMod;}}
""",
    "zombie/network/packets/character/ZombiePacket.java": """
package zombie.network.packets.character;
public class ZombiePacket {public short id; public int bookkeeping,fields; public float health;
 public void prepareFrame(zombie.characters.IsoZombie z){id=z.onlineId;bookkeeping++;}
 public void setPrepared(zombie.characters.IsoZombie z){id=z.onlineId;health=z.health;fields++;}}
""",
}
# Generate fixture accessors for all flags; tests compare the real vanilla encoder.
flags = [
    ("IsFakeDead", "isFakeDead", "setFakeDead"),
    ("IsLunger", None, None), ("IsRunning", None, None),
    ("IsCrawling", "isCrawling", "setCrawler"),
    ("IsSitAgainstWall", "isSitAgainstWall", "setSitAgainstWall"),
    ("IsReanimatedPlayer", "isReanimatedPlayer", "setReanimatedPlayer"),
    ("IsOnFire", "isOnFire", None), ("IsUseless", "isUseless", "setUseless"),
    ("IsOnFloor", "isOnFloor", "setOnFloor"),
    ("IsReanimatedForGrappleOnly", "isReanimatedForGrappleOnly", "setReanimatedForGrappleOnly"),
    ("IsCanWalk", "isCanWalk", "setCanWalk"), ("IsSkeleton", "isSkeleton", "setSkeleton"),
    ("IsFallOnFront", "isFallOnFront", "setFallOnFront"),
    ("IsAnimationRecording", "isAnimationRecorderActive", None),
]
zombie = """package zombie.characters; public class IsoZombie extends zombie.iso.IsoMovingObject {
 public short onlineId; public float health; public boolean lunger,running;
 public int bits;
 public final zombie.network.packets.character.ZombiePacket zombiePacket=new zombie.network.packets.character.ZombiePacket();
 public void SetOnFire(){bits|=64;} public void StopBurning(){bits&=~64;}
 public void setAnimRecorderActive(boolean value,boolean force){put(13,value);}
 private void put(int bit,boolean value){if(value)bits|=1<<bit;else bits&=~(1<<bit);}
"""
for bit, (_, getter, setter) in enumerate(flags):
    if getter:
        zombie += f"public boolean {getter}(){{return (bits & {1 << bit})!=0;}}\n"
    if setter:
        zombie += f"public void {setter}(boolean value){{put({bit},value);}}\n"
STUBS["zombie/characters/IsoZombie.java"] = zombie + "}\n"

HARNESS = r"""
import java.util.*;
import zombie.*;
import zombie.characters.*;
import zombie.inventory.*;
import zombie.inventory.types.*;
import zombie.interfaces.*;
import zombie.iso.*;
import zombie.vehicles.*;
import zombie.popman.*;
import zombie.util.list.*;
public class PlayerPacketAlgorithmsTest {
 static int assertions;
 static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
 static List<String> log=new ArrayList<>();
 static class Updating extends InventoryItem implements IUpdater {
  final String name; Runnable action=()->{};
  Updating(String name){this.name=name;}
  public void update(){log.add(name);action.run();}
 }
 static void inventory(){
  ItemContainer root=new ItemContainer();
  for(int i=0;i<10000;i++)root.items.add(new InventoryItem());
  Updating first=new Updating("first"),child=new Updating("child"),last=new Updating("last");
  InventoryContainer bag=new InventoryContainer(); bag.inventory.items.add(child);
  root.items.add(first);root.items.add(bag);root.items.add(last);
  ServerInventoryUpdateIndex.update(root);
  check(log.equals(List.of("first","child","last")),"depth-first order");
  long discovered=ApocBRServerTelemetryLite.count("players.inventory.discoveryEntries");
  for(int i=0;i<100;i++)ServerInventoryUpdateIndex.update(root);
  check(discovered==ApocBRServerTelemetryLite.count("players.inventory.discoveryEntries"),"stable loot must not be discovered again");
  bag.inventory.items.listIterator().add(new Updating("inserted"));log.clear();ServerInventoryUpdateIndex.update(root);
  check(log.equals(List.of("first","inserted","child","last")),"nested mutation invalidates only child routes");
  root.items.subList(10000,10001).set(0,new Updating("replacement"));log.clear();ServerInventoryUpdateIndex.update(root);
  check(log.getFirst().equals("replacement"),"same-size sublist replacement");
  ItemContainer mutation=new ItemContainer(); Updating remove=new Updating("remove"),skip=new Updating("skip"),successor=new Updating("successor");
  mutation.items.addAll(List.of(remove,skip,successor));remove.action=()->mutation.items.remove(0);log.clear();ServerInventoryUpdateIndex.update(mutation);
  check(log.equals(List.of("remove","successor")),"match vanilla index increment when callback removes current item");
  ItemContainer append=new ItemContainer();Updating add=new Updating("add"),appended=new Updating("appended");
  add.action=()->append.items.add(appended);append.items.add(add);log.clear();ServerInventoryUpdateIndex.update(append);
  check(log.equals(List.of("add","appended")),"visit callback additions like vanilla");
  ItemContainer swapped=new ItemContainer(); Updating swap=new Updating("swap");swapped.items.add(swap);swap.action=()->swapped.items=new ArrayList<>(List.of(swap,new Updating("new")));
  log.clear();ServerInventoryUpdateIndex.update(swapped);check(log.equals(List.of("swap","new")),"public-field replacement preserves live traversal");
  swapped.items=new ArrayList<>(List.of(new Updating("plain")));log.clear();ServerInventoryUpdateIndex.update(swapped);
  check(log.equals(List.of("plain")),"external plain-list fallback");
  MutationTrackedArrayList<Integer> list=new MutationTrackedArrayList<>(); list.addAll(List.of(1,2,3));long version=list.mutationVersion();
  ListIterator<Integer> it=list.listIterator();it.next();it.set(9);check(version!=list.mutationVersion(),"list iterator set revision");
  version=list.mutationVersion();list.subList(0,3).subList(1,2).clear();check(version!=list.mutationVersion(),"nested sublist removal revision");
  version=list.mutationVersion();list.subList(0,2).replaceAll(x->x+1);check(version!=list.mutationVersion(),"sublist bulk replace revision");
 }
 static BaseVehicle vehicle(float x,float y){BaseVehicle v=new BaseVehicle();v.x=x;v.y=y;return v;}
 static IsoZombie zombie(float x,float y){IsoZombie z=new IsoZombie();z.x=x;z.y=y;return z;}
 static void spatial(){
  IsoCell cell=new IsoCell();ServerPlayerSpatialQueries query=new ServerPlayerSpatialQueries();IsoGameCharacter player=new IsoGameCharacter();
  BaseVehicle diagonal=vehicle(3,3);cell.vehicles.add(diagonal);check(!query.nearVehicle(cell,player),"Manhattan metric must not become Euclidean");
  BaseVehicle near=vehicle(-1,-1);cell.vehicles.add(near);check(query.nearVehicle(cell,player),"negative cells and insertion");
  near.x=100;query.vehicleMoved(near);check(!query.nearVehicle(cell,player),"vehicle relocation within frame");
  near.x=0;query.vehicleMoved(near);check(query.nearVehicle(cell,player),"vehicle relocation back");
  cell.vehicles.remove(near);check(!query.nearVehicle(cell,player),"vehicle removal invalidates membership");
  cell.vehicles.add(near);Iterator<BaseVehicle> iterator=cell.vehicles.iterator();while(iterator.hasNext()){if(iterator.next()==near)iterator.remove();}
  check(!query.nearVehicle(cell,player),"iterator vehicle removal");
  IsoZombie a=zombie(1,1),b=zombie(-1,-1),far=zombie(1000,0);cell.zombies.addAll(List.of(b,far,a));ArrayList<IsoZombie> found=new ArrayList<>();
  query.queryZombies(cell,0,0,20,found);check(found.equals(List.of(b,a)),"zombie list order preserved after spatial query");
  long visits=ApocBRServerTelemetryLite.count("players.los.gridEntriesChecked");query.queryZombies(cell,0,0,20,found);
  check(visits==ApocBRServerTelemetryLite.count("players.los.gridEntriesChecked"),"shared frame grid");
  cell.zombies.set(0,far);query.queryZombies(cell,0,0,20,found);check(found.equals(List.of(a)),"same-cardinality replacement invalidates grid");
  a.x=100;IsoWorld.instance.frame++;query.queryZombies(cell,0,0,20,found);check(found.isEmpty(),"next frame refresh sees moving zombies");
  a.x=0;query.invalidateZombies();query.queryZombies(cell,0,0,20,found);check(found.equals(List.of(a)),"incoming position invalidation");
  query.queryZombies(cell,Float.MAX_VALUE,Float.MAX_VALUE,20,found);check(found.isEmpty(),"extreme finite coordinates terminate");
  query.queryZombies(cell,0,0,Float.MAX_VALUE,found);check(found.equals(cell.zombies),"huge radius uses full snapshot fallback");
  player.x=Float.MAX_VALUE;player.y=Float.MAX_VALUE;check(!query.nearVehicle(cell,player),"extreme vehicle query terminates");
 }
 static float vanillaStress(List<WorldSoundManager.WorldSound> sounds,int x,int y){
  float result=0;for(var sound:sounds)if(sound.stresshumans&&sound.radius!=0){float delta=1.0F-IsoUtils.DistanceManhatten(x,y,sound.x,sound.y)/sound.radius;
   if(!(delta<=0)){if(delta>1)delta=1;result+=delta*sound.stressMod;}}return result;
 }
 static void sound(){
  Random random=new Random(513);MutationTrackedArrayList<WorldSoundManager.WorldSound> sounds=new MutationTrackedArrayList<>();
  for(int i=0;i<1000;i++){var s=new WorldSoundManager.WorldSound();s.x=random.nextInt(1000)-500;s.y=random.nextInt(1000)-500;s.radius=random.nextInt(40);s.stresshumans=i%4==0;s.stressMod=random.nextFloat();sounds.add(s);}
  ServerSoundStressIndex query=new ServerSoundStressIndex();
  for(int i=0;i<500;i++){int x=random.nextInt(1000)-500,y=random.nextInt(1000)-500;
   check(Float.floatToIntBits(query.query(sounds,x,y))==Float.floatToIntBits(vanillaStress(sounds,x,y)),"ordered stress sum equivalence");}
  sounds.subList(0,50).clear();check(query.query(sounds,0,0)==vanillaStress(sounds,0,0),"sound removal invalidates");
  var negative=new WorldSoundManager.WorldSound();negative.radius=-1;negative.stresshumans=true;negative.stressMod=2;sounds.add(negative);
  check(query.query(sounds,10000,0)==vanillaStress(sounds,10000,0),"negative radius semantics");
  long builds=ApocBRServerTelemetryLite.count("players.soundStress.indexRebuilt");
  for(int i=0;i<1000;i++){
   long revision=sounds.mutationVersion();
   var sound=new WorldSoundManager.WorldSound();sound.x=i-500;sound.y=i%7;
   sound.radius=i%3==0?20:5;sound.stresshumans=i%3==0;sound.stressMod=0.25f;
   sounds.add(sound);query.appended(sounds,sound,revision);
   check(Float.floatToIntBits(query.query(sounds,0,0))==Float.floatToIntBits(vanillaStress(sounds,0,0)),"incremental append sum/order");
  }
  check(ApocBRServerTelemetryLite.count("players.soundStress.indexRebuilt")==builds,"normal appends avoid full rediscovery");
  sounds.subList(0,10).clear();query.query(sounds,0,0);
  check(ApocBRServerTelemetryLite.count("players.soundStress.indexRebuilt")==builds+1,"external removal uses full fallback");
  sounds.get(0).stresshumans=!sounds.get(0).stresshumans;IsoWorld.instance.frame++;
  check(query.query(sounds,0,0)==vanillaStress(sounds,0,0),"new frame reconciles public eligibility edits");
  query.invalidate();check(query.query(sounds,0,0)==vanillaStress(sounds,0,0),"explicit invalidation reconciles fields");
 }
 static void packet(){
  ServerZombiePacketPreparation cache=new ServerZombiePacketPreparation();IsoZombie z=zombie(0,0);z.onlineId=10;z.health=5;
  cache.beginFrame();cache.bookkeep(z);check(z.zombiePacket.bookkeeping==1&&z.zombiePacket.fields==0,"bookkeeping independent of sending");
  cache.prepare(z);cache.prepare(z);check(z.zombiePacket.fields==1&&z.zombiePacket.bookkeeping==1,"prepare once across recipients");
  z.health=4;cache.received(z);cache.prepare(z);check(z.zombiePacket.health==4&&z.zombiePacket.fields==2,"incoming invalidates fields");
  cache.beginFrame();cache.bookkeep(z);cache.prepare(z);check(z.zombiePacket.fields==3&&z.zombiePacket.bookkeeping==2,"new frame refresh");
  IsoZombie late=zombie(0,0);late.onlineId=11;cache.prepare(late);check(late.zombiePacket.fields==1&&late.zombiePacket.bookkeeping==1,"late ownership/request inclusion");
  z.onlineId=12;cache.prepare(z);check(z.zombiePacket.id==12&&z.zombiePacket.bookkeeping==3,"pooled ID change invalidates cached packet");
 }
 static void enumsAndFlags(){
  String[] values={null,"","unknown","1","sprint2","SPRINT2","slow3","Ãƒâ€¦Ã‚Â¿print1","idle","ATTACK","attack-network","Ãƒâ€žÃ‚Â°DLE","falling"};
  Locale previous=Locale.getDefault();
  try {for(Locale locale:List.of(Locale.ROOT,Locale.forLanguageTag("tr"))){Locale.setDefault(locale);for(String value:values){
   check(zombie.network.NetworkVariables.WalkType.fromString(value).ordinal()==vanilla.zombie.network.NetworkVariables.WalkType.fromString(value).ordinal(),"walk enum equivalence");
   check(zombie.network.NetworkVariables.ZombieState.fromString(value).ordinal()==vanilla.zombie.network.NetworkVariables.ZombieState.fromString(value).ordinal(),"state enum equivalence");
  }}}finally{Locale.setDefault(previous);}
  IsoZombie z=zombie(0,0);for(int bits=0;bits<(1<<14);bits++){z.bits=bits;z.lunger=(bits&2)!=0;z.running=(bits&4)!=0;
   short reference=zombie.characters.VanillaNetworkZombieVariables.getBooleanVariables(z).asShort();
   check(zombie.characters.NetworkZombieVariables.getBooleanVariablesShort(z)==reference,"primitive flags match vanilla");
   check(zombie.characters.NetworkZombieVariables.getBooleanVariables(z).asShort()==reference,"public flag API preserved");}
 }
 static void pathAndExpiry(){
  Random random=new Random(921);
  for(int n=0;n<2000;n++){
   PathThresholdFixture f=new PathThresholdFixture();f.chr.x=random.nextFloat()*100;f.chr.y=random.nextFloat()*100;
   f.targetX=random.nextFloat()*100;f.targetY=random.nextFloat()*100;
   int count=random.nextInt(70);for(int i=0;i<count;i++)f.path.nodes.add(new PathThresholdFixture.Node(random.nextFloat()*100,random.nextFloat()*100));
   f.pathIndex=random.nextInt(count+2)-1;
   for(float threshold:new float[]{0,5,100,Float.POSITIVE_INFINITY,Float.NaN})check(f.isPathLengthGreaterThan(threshold)==(f.getPathLength()>threshold),"threshold equals vanilla distance decision");
  }
  MechanicsExpiryFixture expiry=new MechanicsExpiryFixture();
  expiry.mechanicsItem.put(1L,0L);expiry.invalidate();GameTime.now=86400000L;expiry.run();check(expiry.mechanicsItem.containsKey(1L),"strict expiry boundary");
  GameTime.now++;expiry.run();check(expiry.mechanicsItem.isEmpty(),"expires first update after boundary");
  HashMap<Long,Long> reference=new HashMap<>();
  for(int i=0;i<1000;i++){
   if(i%7==0){long timestamp=random.nextInt(200000000);reference.put((long)i,timestamp);expiry.mechanicsItem.put((long)i,timestamp);expiry.invalidate();}
   GameTime.now=random.nextInt(400000000);reference.entrySet().removeIf(e->GameTime.now>e.getValue()+86400000L);
   expiry.run();check(expiry.mechanicsItem.equals(reference),"expiry equivalence under additions and clock rollback");
  }
 }
 public static void main(String[] args){inventory();spatial();sound();packet();enumsAndFlags();pathAndExpiry();System.out.println("PASS: "+assertions+" assertions");}
}
"""


def extract_method(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def algorithm_fixtures():
    path = (SRC / "zombie/pathfind/PathFindBehavior2.java").read_text()
    threshold = extract_method(path, "    public boolean isPathLengthGreaterThan(")
    vanilla_path = (ROOT / "42.21.0/decompiled/zombie/pathfind/PathFindBehavior2.java").read_text()
    reference = extract_method(vanilla_path, "    public float getPathLength()")
    expiry = extract_method((SRC / "zombie/characters/IsoPlayer.java").read_text(), "    private void updateMechanicsItems()")
    return {
        "PathThresholdFixture.java": "class PathThresholdFixture {static record Node(float x,float y){} static class Path {java.util.ArrayList<Node> nodes=new java.util.ArrayList<>();} public Path path=new Path(); public zombie.characters.IsoGameCharacter chr=new zombie.characters.IsoGameCharacter(); public int pathIndex;public float targetX,targetY;" + threshold + reference + "}",
        "MechanicsExpiryFixture.java": "import java.util.*; import zombie.GameTime; class MechanicsExpiryFixture {public HashMap<Long,Long> mechanicsItem=new HashMap<>(); private long apocbrMechanicsLastTime=Long.MIN_VALUE,apocbrMechanicsNextExpiry=Long.MIN_VALUE;public void invalidate(){apocbrMechanicsNextExpiry=Long.MIN_VALUE;}public void run(){updateMechanicsItems();}" + expiry + "}",
    }


def main():
    vanilla_packet = (ROOT / "42.21.0/decompiled/zombie/network/packets/character/ZombiePacket.java").read_text()
    patched_packet = (SRC / "zombie/network/packets/character/ZombiePacket.java").read_text()
    assert vanilla_packet.split("    public void set(IsoZombie chr)")[0] == patched_packet.split("    public void set(IsoZombie chr)")[0], "packet wire/parse/copy contract changed"
    with tempfile.TemporaryDirectory(prefix="apocbr-player-packet-tests-") as directory:
        work = Path(directory)
        files = []
        for name, text in (STUBS | algorithm_fixtures()).items():
            target = work / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
            files.append(target)
        vanilla = ROOT / "42.21.0/decompiled"
        enum_target = work / "vanilla/zombie/network/NetworkVariables.java"
        enum_target.parent.mkdir(parents=True)
        enum_target.write_text((vanilla / "zombie/network/NetworkVariables.java").read_text().replace("package zombie.network;", "package vanilla.zombie.network;"), encoding="utf-8")
        flag_target = work / "zombie/characters/VanillaNetworkZombieVariables.java"
        flag_target.write_text((vanilla / "zombie/characters/NetworkZombieVariables.java").read_text().replace("NetworkZombieVariables", "VanillaNetworkZombieVariables"), encoding="utf-8")
        harness = work / "PlayerPacketAlgorithmsTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        files.extend([enum_target, flag_target, harness])
        files.extend(SRC / name for name in PRODUCTION)
        files.extend(vanilla / "zombie/util/flags" / name for name in ["ShortFlags.java", "ShortFlag.java", "OrdinalShortFlag.java"])
        output = work / "classes"
        output.mkdir()
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in files), encoding="utf-8")
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "PlayerPacketAlgorithmsTest"], check=True)


if __name__ == "__main__":
    main()
