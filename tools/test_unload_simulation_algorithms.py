"""Differential fixtures for 42.21 removal, zone topology, expiry and no-work gates."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.21.0"


def method(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


STUBS = {
    "zombie/network/GameServer.java": "package zombie.network; public class GameServer {public static boolean server=true;}",
    "zombie/core/Core.java": "package zombie.core; public class Core {public static boolean debug=true;}",
    "zombie/GameTime.java": "package zombie; public class GameTime {public static double hours; public static final GameTime instance=new GameTime(); public static GameTime getInstance(){return instance;} public double getWorldAgeHours(){return hours;}}",
    "zombie/ApocBRServerTelemetryLite.java": """package zombie; public class ApocBRServerTelemetryLite {
      public static final java.util.Map<String,Long> counts=new java.util.HashMap<>(), phases=new java.util.HashMap<>();
      public static void count(String n,long v){counts.merge(n,v,Long::sum);}
      public static void recordPhase(String n,long v){phases.merge(n,v,Long::sum);}
    }""",
    "zombie/entity/IBooleanInformer.java": "package zombie.entity; interface IBooleanInformer {boolean value();}",
    "zombie/entity/IBucketInformer.java": "package zombie.entity; interface IBucketInformer {boolean value();}",
    "zombie/entity/GameEntity.java": """package zombie.entity; public class GameEntity {
      boolean scheduledForEngineRemoval,removingFromEngine,addedToEngine,scheduledDelayedAddToEngine;
      final int id; GameEntity(int id){this.id=id;} void setComponentOperationHandler(ComponentOperationHandler h){}
    }""",
    "zombie/entity/Engine.java": """package zombie.entity; public class Engine {
      final java.util.ArrayList<String> log=new java.util.ArrayList<>(); java.util.function.Consumer<GameEntity> removed;
      void onEntityAdded(GameEntity e){log.add("add"+e.id);}
      void onEntityRemoved(GameEntity e){log.add("remove"+e.id);if(removed!=null)removed.accept(e);}
    }""",
    "zombie/entity/EntityBucketManager.java": """package zombie.entity; public class EntityBucketManager {
      EntityBucketManager(zombie.entity.util.ImmutableArray<GameEntity> e){}
      IBucketInformer getBucketsUpdatingInformer(){return ()->false;} void updateBucketMembership(GameEntity e){}
    }""",
    "zombie/entity/ComponentOperationHandler.java": """package zombie.entity; public class ComponentOperationHandler {
      interface OperationListener {void componentsChanged(GameEntity e);}
      ComponentOperationHandler(IBooleanInformer d,IBucketInformer b,OperationListener l){}
      boolean hasOperationsToProcess(){return false;} void processOperations(){}
    }""",
    "zombie/entity/util/ObjectSet.java": "package zombie.entity.util; public class ObjectSet<T> extends java.util.HashSet<T>{}",
    "zombie/entity/util/SingleThreadPool.java": """package zombie.entity.util; public abstract class SingleThreadPool<T extends SingleThreadPool.Poolable>{
      public interface Poolable {void reset();} protected abstract T newObject(); public T obtain(){return newObject();} public void free(T o){o.reset();}}
    """,
    "zombie/entity/util/Array.java": """package zombie.entity.util; public class Array<T> implements Iterable<T>{
      private final java.util.ArrayList<T> values=new java.util.ArrayList<>(); public int size; final boolean ordered;
      public Array(boolean ordered,int capacity){this.ordered=ordered;} public void add(T v){values.add(v);size++;}
      public T get(int i){return values.get(i);} public T first(){return get(0);} public T peek(){return get(size-1);}
      public void clear(){values.clear();size=0;} public T removeIndex(int i){T v=get(i);T tail=values.remove(--size);if(i<size)values.set(i,tail);return v;}
      public boolean removeValue(T v,boolean identity){for(int i=0;i<size;i++)if(get(i)==v){removeIndex(i);return true;}return false;}
      public java.util.Iterator<T> iterator(){return values.iterator();}}
    """,
    "zombie/entity/util/ImmutableArray.java": """package zombie.entity.util; public class ImmutableArray<T> implements Iterable<T>{
      final Array<T> array;public ImmutableArray(Array<T> a){array=a;}public int size(){return array.size;}public T first(){return array.first();}
      public T get(int i){return array.get(i);} public java.util.Iterator<T> iterator(){return array.iterator();}}
    """,
}

ENGINE_TEST = """package zombie.entity;
import java.util.*;import zombie.ApocBRServerTelemetryLite;
public class RemovalTest {
 static int assertions;static void check(boolean ok){assertions++;if(!ok)throw new AssertionError();}
 static void same(EngineEntityManager a,VanillaEngineEntityManager b,Engine ea,Engine eb){
  check(a.getEntities().size()==b.getEntities().size());for(int i=0;i<a.getEntities().size();i++)check(a.getEntities().get(i).id==b.getEntities().get(i).id);
  check(ea.log.equals(eb.log));
 }
 public static int run(){
  Engine ea=new Engine(),eb=new Engine();boolean[] delayed={false};
  EngineEntityManager a=new EngineEntityManager(ea,()->delayed[0]);VanillaEngineEntityManager b=new VanillaEngineEntityManager(eb,()->delayed[0]);
  GameEntity[] x=new GameEntity[200],y=new GameEntity[200];boolean[] live=new boolean[200];
  for(int i=0;i<x.length;i++){x[i]=new GameEntity(i);y[i]=new GameEntity(i);}
  Random random=new Random(6729);
  for(int k=0;k<10000;k++){
   int i=random.nextInt(x.length);delayed[0]=random.nextBoolean();
   if(live[i]){a.removeEntity(x[i]);b.removeEntity(y[i]);live[i]=false;}else{a.addEntity(x[i]);b.addEntity(y[i]);live[i]=true;}
   a.updateOperations();b.updateOperations();same(a,b,ea,eb);
   check(x[i].addedToEngine==y[i].addedToEngine&&x[i].removingFromEngine==y[i].removingFromEngine);
  }
  delayed[0]=false;a.removeAllEntities();b.removeAllEntities();same(a,b,ea,eb);
  for(int i=0;i<100;i++){a.addEntity(x[i]);b.addEntity(y[i]);}
  // A removal listener removes the just-swapped tail, then re-enters that identity.
  ea.removed=e->{if(e.id==0){a.removeEntity(x[99]);a.addEntity(x[99]);}};
  eb.removed=e->{if(e.id==0){b.removeEntity(y[99]);b.addEntity(y[99]);}};
  a.removeEntity(x[0]);b.removeEntity(y[0]);same(a,b,ea,eb);
  check(ApocBRServerTelemetryLite.counts.get("entities.removal.indexed")>0);
  System.out.println("Removal differential: "+assertions);return assertions;
 }
 public static void main(String[] args){int total=run()+zombie.iso.areas.TopologyTest.run()+zombie.iso.objects.NoWorkTest.run()+zombie.ExpiryTest.run();
  zombie.vehicles.ServerVehicleUpdateTelemetry.beginBatch();zombie.vehicles.ServerVehicleUpdateTelemetry.record(0,11);
  zombie.vehicles.ServerVehicleUpdateTelemetry.beginBatch();zombie.vehicles.ServerVehicleUpdateTelemetry.record(0,13);
  zombie.vehicles.ServerVehicleUpdateTelemetry.endBatch();check(!ApocBRServerTelemetryLite.phases.containsKey("simulation.vehicles.update.checks"));
  zombie.vehicles.ServerVehicleUpdateTelemetry.endBatch();check(ApocBRServerTelemetryLite.phases.get("simulation.vehicles.update.checks")==24);
  zombie.vehicles.ServerVehicleUpdateTelemetry.record(0,7);check(ApocBRServerTelemetryLite.phases.get("simulation.vehicles.update.checks")==31);
  System.out.println("PASS: "+(total+3)+" assertions");}
}
"""

TOPOLOGY_TEST = """package zombie.iso.areas; import java.util.*; import zombie.ApocBRServerTelemetryLite;
public class TopologyTest {
 static int assertions; static void check(boolean ok){assertions++;if(!ok)throw new AssertionError("topology");}
 public static int run(){
  Random random=new Random(681);var zones=DesignationZoneAnimal.designationAnimalZoneList;
  ServerAnimalZoneTopology index=new ServerAnimalZoneTopology();
  for(int round=0;round<100;round++){
   zones.clear();for(int i=0;i<20;i++){var z=new DesignationZoneAnimal();z.x=random.nextInt(20)-10;z.y=random.nextInt(20)-10;z.w=1+random.nextInt(5);z.h=1+random.nextInt(5);z.z=i%2;zones.add(z);}
   for(int step=0;step<10;step++){
    var root=zones.get(random.nextInt(zones.size()));
    var seed=new ArrayList<DesignationZoneAnimal>();seed.add(zones.get(0));seed.add(zones.get(0));
    var expected=DesignationZoneAnimal.vanillaConnected(new ArrayList<>(seed),root,zones.get(1));
    DesignationZoneAnimal.collectConnected(seed,root,zones.get(1),new HashSet<>(seed));check(seed.equals(expected));
    check(index.get(null,root).equals(DesignationZoneAnimal.vanillaConnected(null,root,null)));
    check(index.get(new ArrayList<>(),root).equals(DesignationZoneAnimal.vanillaConnected(null,root,null)));
    var output=index.get(null,root);output.clear();check(!index.get(null,root).isEmpty());
    for(int q=0;q<100;q++){int x=random.nextInt(40)-20,y=random.nextInt(40)-20,z=random.nextInt(2);check(index.find(x,y,z)==DesignationZoneAnimal.getZone(x,y,z));}
    root.x+=random.nextInt(3)-1;Collections.swap(zones,0,random.nextInt(zones.size()));
   }
  }
  zones.clear();var huge=new DesignationZoneAnimal();huge.x=-100000;huge.y=-100000;huge.w=200000;huge.h=200000;zones.add(huge);
  index.get(null,null);check(index.find(0,0,0)==huge);index.clear();zones.clear();index.get(null,null);check(index.find(0,0,0)==null);
  check(ApocBRServerTelemetryLite.counts.get("zones.topology.cacheHits")>0);
  System.out.println("Topology differential: "+assertions);return assertions;
 }
}
"""

NO_WORK_TEST = """package zombie.iso.objects; import java.util.*; import zombie.GameTime;
public class NoWorkTest {
 static int assertions;static void check(boolean ok){assertions++;if(!ok)throw new AssertionError("no-work gate");}
 public static int run(){Random random=new Random(3239);
  for(int i=0;i<50000;i++){
   IsoThumpable a=new IsoThumpable(),b=new IsoThumpable();
   a.lifeLeft=b.lifeLeft=new float[]{-1,0,1,10,Float.NaN}[random.nextInt(5)];
   a.lightSourceOn=b.lightSourceOn=random.nextBoolean();a.lastUpdateHours=b.lastUpdateHours=random.nextInt(60)-10;
   a.updateAccumulator=b.updateAccumulator=random.nextBoolean()?0:1;a.objectIndex=b.objectIndex=random.nextBoolean()?0:-1;
   GameTime.hours=(random.nextInt(120)-20)/60.0;
   if(a.apocbrNeedsServerUpdate())a.vanillaUpdate();b.vanillaUpdate();
   check(Float.floatToIntBits(a.lifeLeft)==Float.floatToIntBits(b.lifeLeft));check(a.lastUpdateHours==b.lastUpdateHours);
   check(a.updateAccumulator==b.updateAccumulator&&a.lightSourceOn==b.lightSourceOn);check(a.writes==b.writes);
  }
  System.out.println("No-work differential: "+assertions);return assertions;
 }
}
"""

def main():
    vanilla_zone = (V / "decompiled/zombie/iso/areas/DesignationZoneAnimal.java").read_text()
    production_zone = (V / "src/zombie/iso/areas/DesignationZoneAnimal.java").read_text()
    zone = "package zombie.iso.areas; import java.util.*; import zombie.network.GameServer; public class DesignationZoneAnimal {\n"
    zone += "public int x,y,z,w,h;public static final ArrayList<DesignationZoneAnimal> designationAnimalZoneList=new ArrayList<>();"
    zone += method(vanilla_zone, "public static DesignationZoneAnimal getZone(int x, int y, int z)")
    zone += method(vanilla_zone, "public static ArrayList<DesignationZoneAnimal> getAllDZones(").replace("getAllDZones", "vanillaConnected")
    zone += method(production_zone, "static void collectConnected(")
    zone += method(production_zone, "private static void admitConnected(") + "}"
    STUBS["zombie/iso/areas/DesignationZoneAnimal.java"] = zone
    vanilla_thump = (V / "decompiled/zombie/iso/objects/IsoThumpable.java").read_text()
    production_thump = (V / "src/zombie/iso/objects/IsoThumpable.java").read_text()
    update = method(vanilla_thump, "public void update()")
    visual = method(update, "if (!GameServer.server)")
    update = update.replace(visual, "").replace("public void update()", "public void vanillaUpdate()")
    STUBS["zombie/iso/objects/IsoThumpable.java"] = """package zombie.iso.objects; import zombie.GameTime; public class IsoThumpable {
      float lifeLeft,lastUpdateHours,updateAccumulator;boolean lightSourceOn;int objectIndex,writes;
      float getLifeLeft(){return lifeLeft;}boolean isLightSourceOn(){return lightSourceOn;}int getObjectIndex(){return objectIndex;}
      float getLifeDelta(){return 0.2f;}void setLifeLeft(float v){lifeLeft=v;writes++;}void toggleLightSource(boolean v){lightSourceOn=v;writes++;}
    """ + method(production_thump, "public boolean apocbrNeedsServerUpdate()") + update + "}"
    # Execute the production expiry branch and exact vanilla loop against duplicate identities.
    production_sound = (V / "src/zombie/WorldSoundManager.java").read_text()
    update_sound = method(production_sound, "public void update()")
    branch = method(update_sound, "if (GameServer.server)")
    original_sound = method((V / "decompiled/zombie/WorldSoundManager.java").read_text(), "public void update()")
    original_loop = original_sound[original_sound.index("        int s = this.soundList.size();"):original_sound.rfind("}")].replace("WorldSoundManager.WorldSound", "WorldSound")
    STUBS["zombie/ExpiryTest.java"] = """package zombie; import java.util.*;import zombie.network.GameServer;
    public class ExpiryTest {
      static class WorldSound {int life,id;WorldSound(int id,int life){this.id=id;this.life=life;}}
      final java.util.List<WorldSound> soundList=new zombie.util.list.MutationTrackedArrayList<>();
      final ArrayList<Integer> released=new ArrayList<>();
      WorldSound release(WorldSound sound){if(sound!=null)released.add(sound.id);return null;}
      void optimized(){""" + branch + "}\nvoid vanilla(){" + original_loop + "}" + """
      public static int run(){int assertions=0;Random random=new Random(9002);
        for(int r=0;r<1000;r++){ExpiryTest a=new ExpiryTest(),b=new ExpiryTest();WorldSound[] x=new WorldSound[100],y=new WorldSound[100];
          for(int i=0;i<100;i++){int life=random.nextInt(4)-1;x[i]=new WorldSound(i,life);y[i]=new WorldSound(i,life);}
          for(int i=0;i<200;i++){int id=random.nextInt(100);a.soundList.add(x[id]);b.soundList.add(y[id]);}
          a.soundList.add(null);b.soundList.add(null);
          a.optimized();b.vanilla();if(!a.released.equals(b.released)||a.soundList.size()!=b.soundList.size())throw new AssertionError("expiry order");assertions++;
          for(int i=0;i<a.soundList.size();i++){if(a.soundList.get(i).id!=b.soundList.get(i).id)throw new AssertionError("survivors");assertions++;}
          for(int i=0;i<100;i++){if(x[i].life!=y[i].life)throw new AssertionError("duplicate life decrement");assertions++;}
        }System.out.println("Expiry differential: "+assertions);return assertions;}
    }"""
    STUBS["zombie/entity/RemovalTest.java"] = ENGINE_TEST
    STUBS["zombie/iso/areas/TopologyTest.java"] = TOPOLOGY_TEST
    STUBS["zombie/iso/objects/NoWorkTest.java"] = NO_WORK_TEST
    STUBS["zombie/entity/VanillaEngineEntityManager.java"] = (V / "decompiled/zombie/entity/EngineEntityManager.java").read_text().replace("EngineEntityManager", "VanillaEngineEntityManager")
    # Instrumentation must leave the vehicle update's actual statements unchanged.
    import re
    vehicle = method((V / "src/zombie/vehicles/BaseVehicle.java").read_text(), "public void update()")
    vehicle = vehicle.replace("long vehicleStageStarted = GameServer.server ? System.nanoTime() : 0L;", "")
    vehicle = vehicle.replace("int vehicleStage = 0;", "").replace("try {", "", 1)
    vehicle = re.sub(r"if \(GameServer.server\) \{\s*long now = System.nanoTime\(\);\s*ServerVehicleUpdateTelemetry.record\(vehicleStage, now - vehicleStageStarted\);\s*vehicleStage = [0-7]; vehicleStageStarted = now;\s*\}", "", vehicle)
    vehicle = re.sub(r"\} finally \{\s*if \(GameServer.server\) ServerVehicleUpdateTelemetry.record\(vehicleStage, System.nanoTime\(\) - vehicleStageStarted\);\s*\}", "", vehicle)
    original_vehicle = method((V / "decompiled/zombie/vehicles/BaseVehicle.java").read_text(), "public void update()")
    assert re.sub(r"\s+", "", vehicle) == re.sub(r"\s+", "", original_vehicle), "Vehicle telemetry altered gameplay statements"
    assert method(production_thump, "public void update()") == method(vanilla_thump, "public void update()"), "Thumpable update body changed"
    with tempfile.TemporaryDirectory(prefix="apocbr-unload-tests-") as directory:
        work = Path(directory)
        sources = []
        for name, content in STUBS.items():
            p = work / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            sources.append(p)
        sources += [V / "src/zombie" / name for name in (
            "entity/EngineEntityManager.java", "iso/areas/ServerAnimalZoneTopology.java",
            "vehicles/ServerVehicleUpdateTelemetry.java", "util/list/MutationTrackedArrayList.java")]
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in sources), encoding="utf-8")
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(work / "classes"), "zombie.entity.RemovalTest"], check=True)


if __name__ == "__main__":
    main()
