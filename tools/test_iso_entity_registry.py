"""42.21 production registry boundaries, square mutation hooks and ID reconciliation."""
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.21.0"


def method(source, signature):
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def build_sources():
    manager = (V / "src/zombie/entity/GameEntityManager.java").read_text()
    obj = (V / "src/zombie/iso/IsoObject.java").read_text()
    original_obj = (V / "decompiled/zombie/iso/IsoObject.java").read_text()
    sources = {
        "zombie/UsedFromLua.java": "package zombie;public @interface UsedFromLua{}",
        "zombie/network/GameServer.java": "package zombie.network;public class GameServer{public static boolean server=true;}",
        "zombie/network/GameClient.java": "package zombie.network;public class GameClient{public static boolean client;}",
        "zombie/ApocBRServerTelemetryLite.java": "package zombie;public class ApocBRServerTelemetryLite{public static final java.util.Map<String,Long> counters=new java.util.HashMap<>();public static void count(String k,long v){counters.merge(k,v,Long::sum);}public static void recordPhase(String k,long v){} }",
        "zombie/debug/DebugType.java": "package zombie.debug;public class DebugType{public static final DebugType Entity=new DebugType();public static int errors;public void noise(String s){}public void println(String s){}public void error(String s,Object...args){errors++;}}",
        "zombie/util/lambda/Invokers.java": "package zombie.util.lambda;public class Invokers{public static class Params2{public static class Boolean{public interface ICallback<A,B>{boolean accept(A a,B b);}}}}",
        "zombie/entity/ComponentType.java": """package zombie.entity;public enum ComponentType{Script,MetaTag,FluidContainer,Other;
          public static final Bits bitsRunInMeta=new Bits();public boolean isRunInMeta(){return this==Other;}public Component CreateComponent(){return new zombie.entity.meta.MetaTagComponent();}
          static class Bits{boolean intersects(Object o){return Boolean.TRUE.equals(o);}}} """,
        "zombie/entity/Component.java": "package zombie.entity;public class Component{public ComponentType getComponentType(){return ComponentType.Other;}public boolean isQualifiesForMetaStorage(){return true;}}",
        "zombie/entity/meta/MetaTagComponent.java": "package zombie.entity.meta;public class MetaTagComponent extends zombie.entity.Component{long stored;public long getStoredID(){return stored;}public void setStoredID(long id){stored=id;}public zombie.entity.ComponentType getComponentType(){return zombie.entity.ComponentType.MetaTag;}}",
        "zombie/entity/MetaEntity.java": "package zombie.entity;public class MetaEntity extends GameEntity{public static MetaEntity lastAllocated;long id;public MetaEntity(){parts.clear();}public static MetaEntity alloc(GameEntity o){MetaEntity m=new MetaEntity();m.id=o.getEntityNetID();lastAllocated=m;return m;}public static void release(MetaEntity m){}public long getEntityNetID(){return id;}}",
        "zombie/entity/Engine.java": "package zombie.entity;public class Engine{public java.util.function.Consumer<GameEntity> onRemove;public int removes;public final java.util.Set<GameEntity> objects=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());public void addEntity(GameEntity o){objects.add(o);o.addedToEngine=true;}public void removeEntity(GameEntity o){removes++;objects.remove(o);o.addedToEngine=false;if(onRemove!=null)onRemove.accept(o);}}",
        "zombie/entity/GameEntity.java": """package zombie.entity;public class GameEntity{
          public boolean addedToEntityManager,addedToEngine,scheduledForEngineRemoval,removingFromEngine;
          public boolean components=true,scriptOnly,metaEligible;public final java.util.ArrayList<Component> parts=new java.util.ArrayList<>(java.util.List.of(new Component()));
          public boolean hasComponents(){return components&&!parts.isEmpty();}public int componentSize(){return parts.size();}public boolean hasComponent(ComponentType type){return scriptOnly&&type==ComponentType.Script||parts.stream().anyMatch(c->c.getComponentType()==type);}
          public long getEntityNetID(){return 123;}public String getGameEntityType(){return "fixture";}public String getEntityFullTypeDebug(){return "fixture";}
          public void sendRequestSyncGameEntity(){}public Object getComponentBits(){return metaEligible;}public Component getComponentForIndex(int i){return parts.get(i);}
          public Component removeComponent(ComponentType type){for(Component c:new java.util.ArrayList<>(parts))if(c.getComponentType()==type){removeComponent(c);return c;}return null;}
          public void removeComponent(Component c){parts.remove(c);if(parts.isEmpty()&&addedToEntityManager)GameEntityManager.UnregisterEntity(this);}
          public void releaseComponent(ComponentType c){removeComponent(c);}public void addComponent(Component c){parts.add(c);}public void connectComponents(){}
        }""",
        "zombie/iso/IsoGridSquare.java": """package zombie.iso;import zombie.util.list.PZArrayList;
          public class IsoGridSquare{final int x,y,z;public PZArrayList<IsoObject> objects=new PZArrayList<>(IsoObject.class,2);
          public boolean requiresHotSave;public IsoGridSquare(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
          public PZArrayList<IsoObject> getObjects(){return objects;}public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}}
        """,
    }
    sources["zombie/iso/IsoObject.java"] = """package zombie.iso;import zombie.entity.GameEntityManager;import zombie.network.GameServer;
      public class IsoObject extends zombie.entity.GameEntity{public IsoGridSquare square;private long isoEntityNetId=-1;private int lastObjectIndex=-1;
      public int indexCalls;public boolean floor,failIndex,failID;public IsoGridSquare getSquare(){return square;}
      public IsoGridSquare getChunk(){return square;}public boolean isFloor(){return floor;}public void sync(){}
    """ + method(obj, "public void setSquare(") + method(obj, "public int getObjectIndex()").replace("return this.square", "indexCalls++;if(failIndex)throw new IllegalStateException(\"index\");return this.square") + method(obj, "public long getEntityNetID()").replace("public long getEntityNetID() {", "public long getEntityNetID() {if(failID)throw new IllegalStateException(\"id\");") + method(obj, "private long apocbrVanillaEntityNetID()") + "}"
    fields = obj[obj.index("    private static final boolean apocbrIdTelemetry"):obj.index("    private long isoEntityNetId")]
    sources["zombie/iso/IsoObject.java"] = sources["zombie/iso/IsoObject.java"].replace("private long isoEntityNetId=-1;", fields + "private long isoEntityNetId=-1;").replace("import zombie.entity.GameEntityManager;", "import zombie.entity.GameEntityManager;import zombie.util.list.PZArrayList;")
    square = sources["zombie/iso/IsoGridSquare.java"].replace("final int x,y,z;", "int x,y,z,cachedScreenValue;")
    square_source = (V / "src/zombie/iso/IsoGridSquare.java").read_text()
    sources["zombie/iso/IsoGridSquare.java"] = square[:square.rfind("}")] + "\n".join(method(square_source, signature) for signature in ("public void setX(", "public void setY(", "public void setZ(")) + "}"
    sources["zombie/iso/IsoGridSquare.java"] = sources["zombie/iso/IsoGridSquare.java"].replace("package zombie.iso;", "package zombie.iso;import zombie.network.GameServer;")
    # Compile the actual lifecycle and registry methods, with only engine/storage dependencies stubbed.
    signatures = ["public static GameEntity GetEntity(", "static void RegisterEntity(",
                  "static void UnregisterEntity(GameEntity gameEntity)", "static void UnregisterEntity(GameEntity gameEntity,",
                  "public static void checkEntityIDChange("]
    import re
    vanilla_manager = (V / "decompiled/zombie/entity/GameEntityManager.java").read_text()
    for signature in signatures:
        assert re.sub(r"\s+", "", method(manager, signature)) == re.sub(r"\s+", "", method(vanilla_manager, signature)), signature
    for signature in ("public void setSquare(",):
        assert re.sub(r"\s+", "", method(obj, signature)) == re.sub(r"\s+", "", method(original_obj, signature)), signature
    sources["zombie/entity/GameEntityManager.java"] = """package zombie.entity;import java.util.*;import zombie.iso.IsoObject;import zombie.debug.DebugType;import zombie.network.GameClient;import zombie.entity.meta.MetaTagComponent;
      public class GameEntityManager{static final Map<Long,GameEntity> idToEntityMap=new HashMap<>();static Engine engine=new Engine();static boolean wasClient;
      static final ArrayDeque<MetaEntity> delayedReleaseMetaEntities=new ArrayDeque<>();
    """ + "\n".join(method(manager, s) for s in signatures) + "}"
    # Exercise the exact vanilla ObjectContainer resolution branch used by item packets.
    container = (V / "decompiled/zombie/network/fields/ContainerID.java").read_text()
    branch = method(container, "public void findObject()").split("} else if (this.containerType == ContainerID.ContainerType.ObjectContainer) {", 1)[1].split("} else if (this.containerType == ContainerID.ContainerType.Vehicle)", 1)[0]
    sources["zombie/entity/ContainerProbe.java"] = """package zombie.entity;import zombie.iso.*;import zombie.inventory.*;
      public class ContainerProbe {public int index,containerIndex;public IsoObject object;public ItemContainer container;
      public void resolve(IsoGridSquare sq){container=null;object=null;""" + branch + "}}"
    sources["zombie/inventory/ItemContainer.java"] = "package zombie.inventory;public class ItemContainer{public final java.util.ArrayList<Object> items=new java.util.ArrayList<>();}"
    sources["zombie/iso/IsoObject.java"] = sources["zombie/iso/IsoObject.java"].replace("public boolean floor,failIndex,failID;", "public boolean floor,failIndex,failID;public final zombie.inventory.ItemContainer container=new zombie.inventory.ItemContainer();public zombie.inventory.ItemContainer getContainerByIndex(int index){return index==0?container:null;}")
    # Compile a real audited square reader; its array traversal must not poison caching.
    square_reader = method(square_source, "public boolean haveFire()")
    sources["zombie/iso/IsoGridSquare.java"] = sources["zombie/iso/IsoGridSquare.java"].replace("import zombie.network.GameServer;", "import zombie.network.GameServer;import zombie.iso.objects.IsoFire;")
    sq_stub = sources["zombie/iso/IsoGridSquare.java"]
    sources["zombie/iso/IsoGridSquare.java"] = sq_stub[:sq_stub.rfind("}")] + square_reader + "}"
    sources["zombie/iso/objects/IsoFire.java"] = "package zombie.iso.objects;public class IsoFire extends zombie.iso.IsoObject{}"
    # New overrides differ from vanilla only in the audited array acquisition.
    for relative in ("LoadGridsquarePerformanceWorkaround.java", "LootRespawn.java", "iso/objects/IsoGenerator.java"):
        vanilla = (V / "decompiled/zombie" / relative).read_text(encoding="utf-8")
        production = (V / "src/zombie" / relative).read_text(encoding="utf-8")
        for expression in ("this.objects", "this.getObjects()", "square.getObjects()", "sq.getObjects()"):
            old = expression + ".getElements()"
            vanilla = vanilla.replace(old, "(GameServer.server ? " + expression + ".apocbrReadOnlyElements() : " + old + ")")
        assert re.sub(r"\s+", "", vanilla) == re.sub(r"\s+", "", production), relative
    sources["zombie/entity/RegistryTest.java"] = HARNESS
    # Stable-index getter behavior still uses the exact vanilla formula and client body.
    original = method(original_obj, "public long getEntityNetID()")
    production = method(obj, "private long apocbrVanillaEntityNetID()").replace("private long apocbrVanillaEntityNetID()", "public long getEntityNetID()")
    import re
    assert re.sub(r"\s+", "", original) == re.sub(r"\s+", "", production)
    return sources


def main():
    sources = build_sources()
    with tempfile.TemporaryDirectory(prefix="apocbr-registry-tests-") as directory:
        work = Path(directory)
        paths = []
        for name, content in sources.items():
            p = work / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            paths.append(p)
        paths.extend(V / "src/zombie" / n for n in ("util/list/PZArrayList.java",))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in paths))
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(work / "classes"), "zombie.entity.RegistryTest", *sys.argv[1:]], check=True)


HARNESS = r"""package zombie.entity;import java.util.*;import zombie.iso.*;import zombie.network.GameServer;import zombie.debug.DebugType;
public class RegistryTest{
 static int assertions;static void check(boolean b,String m){if(!b)throw new AssertionError(m);assertions++;}
 static IsoObject object(IsoGridSquare sq){IsoObject o=new IsoObject();o.setSquare(sq);sq.objects.add(o);return o;}
 static long expected(IsoObject o){int n=o.getObjectIndex();return n<0?-1:((long)(o.floor?0:n)<<40)+((long)o.square.getZ()<<32)+((long)o.square.getY()<<16)+o.square.getX();}
 static void trackedArrayChecks(){
  GameServer.server=true;zombie.network.GameClient.client=false;GameEntityManager.idToEntityMap.clear();
  IsoGridSquare sq=new IsoGridSquare(3000,3000,0);IsoObject a=object(sq),b=object(sq);
  a.getEntityNetID();b.getEntityNetID();a.indexCalls=0;b.indexCalls=0;long version=sq.objects.apocbrMutationVersion();
  IsoObject[] array=sq.objects.apocbrReadOnlyElements();
  for(int n=0;n<1000;n++){check(!sq.haveFire(),"audited production reader result");a.getEntityNetID();b.getEntityNetID();}
  check(!sq.objects.apocbrElementsExposed()&&sq.objects.apocbrMutationVersion()==version,"read-only open array stays clean");
  check(a.indexCalls==0&&b.indexCalls==0,"read-only array access permits cached IDs");
  array[0]=b;array[1]=a;sq.objects.apocbrElementsChanged();check(sq.objects.apocbrMutationVersion()!=version,"managed array write marks dirty");
  check(a.getEntityNetID()==expected(a)&&b.getEntityNetID()==expected(b),"managed raw reorder refreshes both objects");
  a.indexCalls=0;b.indexCalls=0;a.getEntityNetID();b.getEntityNetID();check(a.indexCalls==0&&b.indexCalls==0,"cache resumes after managed raw edit");
  version=sq.objects.apocbrMutationVersion();sq.objects.get(0);check(sq.objects.apocbrMutationVersion()==version,"element reads stay clean");
  sq.objects.set(0,a);check(sq.objects.apocbrMutationVersion()!=version,"replacement marks dirty");
  sq.objects.getElements();check(sq.objects.apocbrElementsExposed(),"unknown public array access retains fallback");
  check(zombie.ApocBRServerTelemetryLite.counters.getOrDefault("isoObjects.arrayExposure.auditedReads",0L)>=1000,"audited-reader revision is observable");
  check(zombie.ApocBRServerTelemetryLite.counters.getOrDefault("isoObjects.arrayExposure.firstCaller.zombie.entity.RegistryTest.trackedArrayChecks",0L)==1,"first exposure attributed to real caller");
  long first=zombie.ApocBRServerTelemetryLite.counters.getOrDefault("isoObjects.arrayExposure.firstExposures",0L);sq.objects.getElements();
  check(zombie.ApocBRServerTelemetryLite.counters.getOrDefault("isoObjects.arrayExposure.firstExposures",0L)==first,"repeat exposure does not collect stack again");
  a.getEntityNetID();check(zombie.ApocBRServerTelemetryLite.counters.getOrDefault("isoObjects.arrayExposure.fallbackCaller.zombie.entity.RegistryTest.trackedArrayChecks",0L)>0,"fallback retains first-caller attribution");
  for(int n=0;n<200;n++){new zombie.util.list.PZArrayList<IsoObject>(IsoObject.class,1).getElements();}
  check(zombie.ApocBRServerTelemetryLite.counters.getOrDefault("isoObjects.arrayExposure.sampledLists",0L)==128,"stack collection bounded per process");
  check(zombie.ApocBRServerTelemetryLite.counters.getOrDefault("isoObjects.arrayExposure.unsampledLists",0L)>0,"overflow reported explicitly");
 }
 static void cacheChecks(){
  GameServer.server=true;zombie.network.GameClient.client=false;GameEntityManager.idToEntityMap.clear();
  IsoGridSquare sq=new IsoGridSquare(1000,1000,0);IsoObject pad=object(sq),a=object(sq),b=object(sq);
  a.getEntityNetID();b.getEntityNetID();a.indexCalls=0;b.indexCalls=0;
  for(int n=0;n<1000;n++){a.getEntityNetID();b.getEntityNetID();}
  check(a.indexCalls==0&&b.indexCalls==0,"clean getters avoid all index reads");
  sq.objects.remove(pad);long aid=a.getEntityNetID();check(a.indexCalls>0,"dirty first object rechecks");
  check(b.getEntityNetID()==expected(b)&&b.indexCalls>0,"second dirty object independently rechecks");
  int reads=a.indexCalls;a.getEntityNetID();check(a.indexCalls==reads,"cache warms after dirty check");
  sq.setX(1001);a.getEntityNetID();check(a.indexCalls>reads,"coordinate changes use vanilla fallback");
  IsoObject[] raw=sq.objects.getElements();raw[0]=b;raw[1]=a;reads=a.indexCalls;
  a.getEntityNetID();a.getEntityNetID();check(a.indexCalls>reads,"retained arrays always use vanilla fallback");
  IsoGridSquare replacement=new IsoGridSquare(1001,1000,0);replacement.objects.add(a);a.square=replacement;reads=a.indexCalls;
  a.getEntityNetID();check(a.indexCalls>reads,"direct square reassignment misses cache");
  replacement.objects=new zombie.util.list.PZArrayList<>(IsoObject.class,4);replacement.objects.add(a);reads=a.indexCalls;
  a.getEntityNetID();check(a.indexCalls>reads,"list replacement misses cache");
  replacement.objects.clear();check(a.getEntityNetID()==-1,"removed object has no ID");replacement.objects.add(a);check(a.getEntityNetID()!=-1,"re-add initializes cache");
  IsoObject custom=new IsoObject(){@Override public int getObjectIndex(){return super.getObjectIndex();}};custom.setSquare(replacement);replacement.objects.add(custom);custom.getEntityNetID();reads=custom.indexCalls;custom.getEntityNetID();check(custom.indexCalls>reads,"custom index getter bypasses cache");
  GameServer.server=false;reads=a.indexCalls;a.getEntityNetID();a.getEntityNetID();check(a.indexCalls>reads,"client always retains vanilla checks");
  GameServer.server=true;
  // Differential replay against the unmodified body forced by raw exposure.
  IsoGridSquare tracked=new IsoGridSquare(2000,2000,0),reference=new IsoGridSquare(2000,2000,0);
  IsoObject t=object(tracked),r=object(reference);Random random=new Random(42);
  long oldT=-1,oldR=-1;
  for(int n=0;n<2000;n++){
   switch(random.nextInt(7)){
    case 0 -> {tracked.objects.add(0,new IsoObject());reference.objects.add(0,new IsoObject());}
    case 1 -> {if(tracked.objects.size()>1){int ix=tracked.objects.get(0)==t?1:0;tracked.objects.remove(ix);reference.objects.remove(ix);}}
    case 2 -> {tracked.setX(2000+n);reference.setX(2000+n);}
    case 3 -> {t.floor=!t.floor;r.floor=t.floor;}
    case 4 -> {tracked.objects.remove(t);reference.objects.remove(r);}
    case 5 -> {if(!tracked.objects.contains(t)){tracked.objects.add(t);reference.objects.add(r);}}
    default -> {}
   }
   reference.objects.getElements();GameEntityManager.idToEntityMap.clear();if(oldT!=-1)GameEntityManager.idToEntityMap.put(oldT,t);
   long tid=t.getEntityNetID();Set<Long> keys=new HashSet<>(GameEntityManager.idToEntityMap.keySet());
   GameEntityManager.idToEntityMap.clear();if(oldR!=-1)GameEntityManager.idToEntityMap.put(oldR,r);
   long rid=r.getEntityNetID();check(tid==rid,"tracked getter matches vanilla body result");check(keys.equals(GameEntityManager.idToEntityMap.keySet()),"lazy registry keys match vanilla body");oldT=tid;oldR=rid;
  }
 }
 public static void main(String[] args){
  for(boolean client:new boolean[]{false,true}){
   GameServer.server=!client;zombie.network.GameClient.client=client;
   GameEntityManager.idToEntityMap.clear();GameEntityManager.engine=new Engine();DebugType.errors=0;
   IsoGridSquare sq=new IsoGridSquare(100,200,-1);IsoObject pad=object(sq),chest=object(sq);
   GameEntityManager.RegisterEntity(chest);long old=chest.getEntityNetID();check(GameEntityManager.GetEntity(old)==chest,"registered ID");
   ContainerProbe packet=new ContainerProbe();packet.index=chest.getObjectIndex();packet.containerIndex=0;
   Object item=new Object();chest.container.items.add(item);
   for(int n=0;n<1000;n++){
    packet.resolve(sq);check(packet.object==chest&&packet.container==chest.container,"vanilla container resolution");
    check(packet.container.items.remove(item),"take item");
    check(chest.getEntityNetID()==old,"inventory edits preserve owner ID");packet.container.items.add(item);
   }
   sq.objects.getElements();sq.objects.remove(pad);long next=expected(chest);
   check(GameEntityManager.GetEntity(old)==chest,"lookup preserves vanilla lazy map before getter");
   check(GameEntityManager.GetEntity(next)==null,"lookup does not eagerly reconcile");
   check(chest.getEntityNetID()==next,"getter repairs changed ordinal");
   check(GameEntityManager.GetEntity(next)==chest&&GameEntityManager.GetEntity(old)==null,"old/new mapping repaired");
   packet.index=chest.getObjectIndex();packet.resolve(sq);check(packet.container==chest.container,"shifted container resolves");
   GameEntityManager.UnregisterEntity(chest);check(GameEntityManager.GetEntity(next)==null,"unregister removes ID");
   check(!chest.addedToEntityManager&&!chest.addedToEngine,"vanilla removal flags");
   check(DebugType.errors==0,"no registry errors");
  }
  trackedArrayChecks();cacheChecks();System.out.println("Lazy ID cache and vanilla container fixture: "+assertions+" assertions; production methods match exact-version vanilla");
 }
}
"""

if __name__ == "__main__":
    main()
