"""42.21 production registry boundaries, square mutation hooks and ID reconciliation."""
from pathlib import Path
import subprocess
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
          public static final Bits bitsRunInMeta=new Bits();public boolean isRunInMeta(){return false;}public Component CreateComponent(){return new zombie.entity.meta.MetaTagComponent();}
          static class Bits{boolean intersects(Object o){return false;}}} """,
        "zombie/entity/Component.java": "package zombie.entity;public class Component{public ComponentType getComponentType(){return ComponentType.Other;}public boolean isQualifiesForMetaStorage(){return false;}}",
        "zombie/entity/meta/MetaTagComponent.java": "package zombie.entity.meta;public class MetaTagComponent extends zombie.entity.Component{public long getStoredID(){return 0;}public void setStoredID(long id){}}",
        "zombie/entity/MetaEntity.java": "package zombie.entity;public class MetaEntity extends GameEntity{public static MetaEntity alloc(GameEntity o){return new MetaEntity();}public static void release(MetaEntity m){}public long getEntityNetID(){return 123;}}",
        "zombie/entity/Engine.java": "package zombie.entity;public class Engine{public final java.util.Set<GameEntity> objects=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());public void addEntity(GameEntity o){objects.add(o);o.addedToEngine=true;}public void removeEntity(GameEntity o){objects.remove(o);o.addedToEngine=false;}}",
        "zombie/entity/GameEntity.java": """package zombie.entity;public class GameEntity{
          public boolean addedToEntityManager,addedToEngine,scheduledForEngineRemoval,removingFromEngine;
          public boolean hasComponents(){return true;}public int componentSize(){return 1;}public boolean hasComponent(ComponentType type){return false;}
          public long getEntityNetID(){return 123;}public String getGameEntityType(){return "fixture";}public String getEntityFullTypeDebug(){return "fixture";}
          public void sendRequestSyncGameEntity(){}public Object getComponentBits(){return null;}public Component getComponentForIndex(int i){return new Component();}
          public Component removeComponent(ComponentType c){return null;}public void removeComponent(Component c){}public void releaseComponent(ComponentType c){}public void addComponent(Component c){}public void connectComponents(){}
        }""",
        "zombie/iso/IsoGridSquare.java": """package zombie.iso;import zombie.util.list.PZArrayList;
          public class IsoGridSquare{final int x,y,z;public final PZArrayList<IsoObject> objects=new PZArrayList<>(IsoObject.class,2);
          public boolean requiresHotSave;public IsoGridSquare(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
          public PZArrayList<IsoObject> getObjects(){return objects;}public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}}
        """,
    }
    sources["zombie/iso/IsoObject.java"] = """package zombie.iso;import zombie.entity.GameEntityManager;import zombie.network.GameServer;
      public class IsoObject extends zombie.entity.GameEntity{public IsoGridSquare square;private long isoEntityNetId=-1;private int lastObjectIndex=-1;
      private boolean apocbrEntityIdDirty;public boolean floor,failIndex,failID;public IsoGridSquare getSquare(){return square;}
      public IsoGridSquare getChunk(){return square;}public boolean isFloor(){return floor;}public void sync(){}
    """ + method(obj, "public void setSquare(") + method(obj, "public int getObjectIndex()").replace("return this.square", "if(failIndex)throw new IllegalStateException(\"index\");return this.square") + method(obj, "public long getEntityNetID()").replace("public long getEntityNetID() {", "public long getEntityNetID() {if(failID)throw new IllegalStateException(\"id\");") + method(obj, "public final void apocbrInvalidateEntityNetID()") + method(obj, "private long apocbrGetServerEntityNetID()") + "}"
    square = sources["zombie/iso/IsoGridSquare.java"].replace("final int x,y,z;", "int x,y,z,cachedScreenValue;")
    square_source = (V / "src/zombie/iso/IsoGridSquare.java").read_text()
    sources["zombie/iso/IsoGridSquare.java"] = square[:square.rfind("}")] + "\n".join(method(square_source, signature) for signature in ("public void setX(", "public void setY(", "public void setZ(")) + "}"
    sources["zombie/iso/IsoGridSquare.java"] = sources["zombie/iso/IsoGridSquare.java"].replace("package zombie.iso;", "package zombie.iso;import zombie.network.GameServer;")
    # Compile the actual lifecycle and registry methods, with only engine/storage dependencies stubbed.
    signatures = ["public static GameEntity GetEntity(", "private static void reconcileIsoEntityIDs()",
                  "static void removeIsoRegistryKey(", "static void putIsoRegistryKey(", "static void RegisterEntity(",
                  "static void UnregisterEntity(GameEntity gameEntity)", "static void UnregisterEntity(GameEntity gameEntity,",
                  "public static void checkEntityIDChange("]
    sources["zombie/entity/GameEntityManager.java"] = """package zombie.entity;import java.util.*;import zombie.iso.IsoObject;import zombie.debug.DebugType;import zombie.network.GameClient;import zombie.entity.meta.MetaTagComponent;
      public class GameEntityManager{static final Map<Long,GameEntity> idToEntityMap=new HashMap<>();static Engine engine=new Engine();static boolean wasClient;
      static final ArrayDeque<MetaEntity> delayedReleaseMetaEntities=new ArrayDeque<>();
    """ + "\n".join(method(manager, s) for s in signatures) + "}"
    sources["zombie/entity/RegistryTest.java"] = HARNESS
    # Stable-index getter behavior still uses the exact vanilla formula and client body.
    original = method(original_obj, "public long getEntityNetID()")
    production = method(obj, "public long getEntityNetID()").replace("if (GameServer.server) return this.apocbrGetServerEntityNetID();", "")
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
        paths.extend(V / "src/zombie" / n for n in ("entity/ServerIsoEntityRegistry.java", "util/list/PZArrayList.java"))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in paths))
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(work / "classes"), "zombie.entity.RegistryTest"], check=True)


HARNESS = r"""package zombie.entity;import java.util.*;import zombie.iso.*;import zombie.network.GameServer;import zombie.ApocBRServerTelemetryLite;import zombie.debug.DebugType;
public class RegistryTest{
 static int assertions;static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);assertions++;}
 static void reset(){ServerIsoEntityRegistry.reset();GameEntityManager.idToEntityMap.clear();GameEntityManager.engine=new Engine();DebugType.errors=0;GameServer.server=true;}
 static IsoObject object(IsoGridSquare sq){IsoObject o=new IsoObject();o.setSquare(sq);sq.objects.add(o);return o;}
 static void register(IsoObject o){GameEntityManager.RegisterEntity(o);check(o.addedToEntityManager,"registered");}
 static long expected(IsoObject o){int index=o.getObjectIndex();return index<0?-1:((long)(o.floor?0:index)<<40)+((long)o.square.getZ()<<32)+((long)o.square.getY()<<16)+o.square.getX();}
 static void validate(List<IsoObject> live){
   for(IsoObject o:live){long id=expected(o);check(GameEntityManager.GetEntity(id)==o,"registry current identity");check(o.getEntityNetID()==id,"cached ID matches expected");}
   for(var e:GameEntityManager.idToEntityMap.entrySet())if(e.getValue() instanceof IsoObject o)check(expected(o)==e.getKey(),"no stale key");
 }
 public static void main(String[] args){
   reset();IsoGridSquare sq=new IsoGridSquare(100,200,0);IsoObject floor=object(sq);floor.floor=true;IsoObject first=object(sq),second=object(sq);register(first);register(second);
   // Shift a surviving registered entity, then query its NEW ID before calling its getter.
   GameEntityManager.UnregisterEntity(first);sq.objects.remove(first);check(!first.addedToEngine,"unregister lifecycle");check(GameEntityManager.GetEntity(expected(second))==second,"shifted lookup before getter");validate(List.of(second));
   // Insertion reconciles existing entities before registering the newly inserted one.
   IsoObject inserted=new IsoObject();inserted.setSquare(sq);sq.objects.add(1,inserted);register(inserted);validate(List.of(inserted,second));
   // Cyclic permutation through set() must not recurse through occupied old keys.
   sq.objects.set(1,second);sq.objects.set(2,inserted);check(second.getEntityNetID()==expected(second),"direct getter repairs permutation");validate(List.of(inserted,second));check(DebugType.errors==0,"no false collision errors");
   // Retained raw array changes have no List notifications; validate before hit AND miss.
   IsoObject[] raw=sq.objects.getElements();raw[1]=inserted;raw[2]=second;validate(List.of(inserted,second));
   long old=second.getEntityNetID();raw[2]=new IsoObject();check(GameEntityManager.GetEntity(old)==null,"raw detach clears stale key");
   raw[2]=second;check(GameEntityManager.GetEntity(expected(second))==second,"raw reattach restores registered object");
   raw[1]=second;raw[2]=inserted;check(second.getEntityNetID()==expected(second),"raw cyclic change via direct getter");validate(List.of(inserted,second));
   // Equal list index, different square: coordinates still require reconciliation.
   IsoGridSquare next=new IsoGridSquare(101,200,0);object(next);object(next);sq.objects.remove(second);second.setSquare(next);next.objects.add(second);validate(List.of(inserted,second));
   next.setX(102);next.setY(201);next.setZ(2);validate(List.of(inserted,second));
   // Native public-field square reassignment is caught when the old list is edited.
   IsoGridSquare direct=new IsoGridSquare(103,201,2);object(direct);object(direct);next.objects.remove(second);second.square=direct;direct.objects.add(second);validate(List.of(inserted,second));
   direct.objects.add(0,new IsoObject());validate(List.of(inserted,second));
   // Floor identity retains the original forced zero ordinal.
   IsoObject ground=object(next);ground.floor=true;register(ground);validate(List.of(inserted,second,ground));
   // Dirty queues release detached registered objects through the real removal path.
   long removedID=second.getEntityNetID();direct.objects.remove(second);check(GameEntityManager.GetEntity(removedID)==null,"detached map cleared");
   GameEntityManager.UnregisterEntity(second,true);check(!second.addedToEntityManager&&!second.addedToEngine,"detached unregister cleanup");
   // A new non-IsoObject entity reusing the old key must survive stale-key cleanup.
   GameEntity replacement=new GameEntity();GameEntityManager.idToEntityMap.put(removedID,replacement);check(GameEntityManager.GetEntity(removedID)==replacement,"unrelated reused key preserved");
   // No-work lookup does not scan unexposed registered squares every time.
   reset();sq=new IsoGridSquare(500,500,1);IsoObject stable=object(sq);register(stable);long id=stable.getEntityNetID();GameEntityManager.GetEntity(id);
   long visits=ApocBRServerTelemetryLite.counters.getOrDefault("entities.registry.checked",0L);for(int i=0;i<1000;i++)check(GameEntityManager.GetEntity(id)==stable,"stable lookup");
   check(visits==ApocBRServerTelemetryLite.counters.getOrDefault("entities.registry.checked",0L),"stable views incur zero entry checks");
   // Batch many unrelated/nonregistered edits into one registered-member validation.
   for(int i=0;i<100;i++)object(sq);check(GameEntityManager.GetEntity(id)==stable,"bulk append");
   check(ApocBRServerTelemetryLite.counters.get("entities.registry.checked")==visits+1,"bulk edits coalesced");
   // Failed ID evaluation can be retried without discarding dirty work.
   IsoObject shifted=object(sq);register(shifted);sq.objects.remove(1);shifted.failIndex=true;
   try{GameEntityManager.GetEntity(expected(stable));throw new AssertionError("exception expected");}catch(IllegalStateException expected){}
   shifted.failIndex=false;check(GameEntityManager.GetEntity(expected(shifted))==shifted,"retry after exception");
   shifted.failID=true;sq.objects.add(0,new IsoObject());
   try{GameEntityManager.GetEntity(expected(stable));throw new AssertionError("ID exception expected");}catch(IllegalStateException expected){}
   shifted.failID=false;validate(List.of(stable,shifted));
   // Genuine cross-family collision keeps the unrelated incumbent.
   reset();sq=new IsoGridSquare(700,700,0);IsoObject a=object(sq);register(a);IsoObject padding=object(sq);sq.objects.set(0,padding);sq.objects.set(1,a);
   long destination=expected(a);GameEntity incumbent=new GameEntity();GameEntityManager.idToEntityMap.put(destination,incumbent);
   check(GameEntityManager.GetEntity(destination)==incumbent,"unrelated incumbent not overwritten");check(DebugType.errors==1,"real collision reported");
   // Clear/unregister releases tracking; detached values must not resurrect on lookup.
   reset();sq=new IsoGridSquare(800,800,0);a=object(sq);register(a);id=a.getEntityNetID();GameEntityManager.UnregisterEntity(a);sq.objects.clear();check(GameEntityManager.GetEntity(id)==null,"unregister no resurrection");
   sq.objects.add(new IsoObject());sq.objects.add(a);register(a);check(a.addedToEngine,"same instance re-add attaches engine");validate(List.of(a));check(DebugType.errors==0,"re-add no phantom registry entry");
   sq.objects.add(a);validate(List.of(a));sq.objects.remove(sq.objects.size()-1);validate(List.of(a));
   // Java mods with their own ID contract retain that contract and are not tracked.
   IsoObject custom=new IsoObject(){@Override public long getEntityNetID(){return 987654321L;}};custom.setSquare(sq);sq.objects.add(custom);register(custom);sq.objects.add(0,new IsoObject());
   check(GameEntityManager.GetEntity(987654321L)==custom,"custom ID retained");GameEntityManager.UnregisterEntity(custom);
   // Client path retains vanilla lazy behavior and never activates the server tracker.
   reset();GameServer.server=false;zombie.network.GameClient.client=true;sq=new IsoGridSquare(900,900,0);a=object(sq);register(a);id=a.getEntityNetID();sq.objects.add(0,new IsoObject());
   check(GameEntityManager.GetEntity(id)==a,"client map remains vanilla lazy");check(a.getEntityNetID()==expected(a),"client getter still reconciles");
   zombie.network.GameClient.client=false;GameServer.server=true;
   // Randomized valid lifecycle: use source registration/removal plus arbitrary list permutations.
   reset();sq=new IsoGridSquare(1200,1200,0);var live=new ArrayList<IsoObject>();Random r=new Random(9093);
   for(int step=0;step<3000;step++){
     if(live.isEmpty()||live.size()<40&&r.nextInt(3)==0){IsoObject o=new IsoObject();o.setSquare(sq);sq.objects.add(r.nextInt(sq.objects.size()+1),o);register(o);live.add(o);}
     else if(r.nextBoolean()){IsoObject o=live.remove(r.nextInt(live.size()));GameEntityManager.UnregisterEntity(o);sq.objects.remove(o);check(!o.addedToEngine&&!o.addedToEntityManager,"random removal flags");}
     else if(sq.objects.size()>1){int x=r.nextInt(sq.objects.size()),y=r.nextInt(sq.objects.size());IsoObject xObj=sq.objects.get(x),yObj=sq.objects.get(y);
       if(r.nextBoolean()){sq.objects.set(x,yObj);sq.objects.set(y,xObj);}else{IsoObject[] array=sq.objects.getElements();array[x]=yObj;array[y]=xObj;}}
     validate(live);check(DebugType.errors==0,"random valid changes have no registry collisions");
   }
   ServerIsoEntityRegistry.reset();GameEntityManager.idToEntityMap.clear();check(GameEntityManager.GetEntity(1)==null,"reset releases views");
   System.out.println("Iso entity registry: "+assertions+" assertions");
 }
}
"""


if __name__ == "__main__":
    main()
