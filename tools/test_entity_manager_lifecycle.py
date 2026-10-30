"""42.22.0 manager methods plus real EngineEntityManager/clock/throttle.

GameEntityManager methods are extracted unchanged; engine/world collaborators
are explicit doubles. This is not exact-JAR or native-runtime validation.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_unload_simulation_algorithms import STUBS, method

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / "42.22.0"


def fixtures():
    files = dict(STUBS)
    source = (VERSION / "src/zombie/entity/GameEntityManager.java").read_text(encoding="utf-8")
    signatures = (
        "public static void Update()", "public static GameEntity GetEntity(long gameEntityNetID)",
        "static void RegisterEntity(GameEntity gameEntity)", "static void UnregisterEntity(GameEntity gameEntity)",
        "static void UnregisterEntity(GameEntity gameEntity, boolean offloadToMeta)",
    )
    files["zombie/entity/GameEntityManager.java"] = """package zombie.entity;
      import java.util.*;import zombie.network.GameClient;import zombie.debug.DebugType;
      import zombie.iso.IsoObject;import zombie.entity.components.ComponentType;
      import zombie.entity.components.meta.MetaTagComponent;
      public class GameEntityManager {
       static Engine engine;static boolean wasClient;static Debugger debugger;
       static final Map<Long,GameEntity> idToEntityMap=new HashMap<>();
       static final ArrayDeque<MetaEntity> delayedReleaseMetaEntities=new ArrayDeque<>();
       static class Debugger {void beginUpdate(){}void endUpdate(){}}
    """ + "\n".join(method(source, signature) for signature in signatures) + "}"
    predicate = method((VERSION / "src/zombie/entity/GameEntity.java").read_text(encoding="utf-8"),
                       "public final boolean isAddedOrScheduledForEngine()")
    files["zombie/entity/GameEntity.java"] = """package zombie.entity;
      import java.util.*;import zombie.entity.components.ComponentType;
      public class GameEntity {
       boolean scheduledForEngineRemoval,removingFromEngine,addedToEngine,scheduledDelayedAddToEngine,addedToEntityManager;
       long id;Runnable getter;final ArrayList<Component> components=new ArrayList<>();
       GameEntity(long id){this.id=id;}void setComponentOperationHandler(ComponentOperationHandler h){}
       long getEntityNetID(){if(getter!=null){Runnable f=getter;getter=null;f.run();}return id;}
       boolean hasComponents(){return !components.isEmpty();}int componentSize(){return components.size();}
       boolean hasComponent(ComponentType type){return components.stream().anyMatch(c->c.type==type);}
       Component removeComponent(ComponentType type){for(int i=0;i<components.size();i++)if(components.get(i).type==type)return components.remove(i);return null;}
       void removeComponent(Component c){components.remove(c);}void releaseComponent(ComponentType t){removeComponent(t);}
       void addComponent(Component c){components.add(c);}Component getComponentForIndex(int i){return components.get(i);}
       ArrayList<Component> getComponentBits(){return components;}String getGameEntityType(){return getClass().getSimpleName();}
       String getEntityFullTypeDebug(){return "fixture";}void sendRequestSyncGameEntity(){}void connectComponents(){}
       boolean isMeta(){return this instanceof MetaEntity;}
    """ + predicate + "}"
    files["zombie/entity/Component.java"] = """package zombie.entity;
      import zombie.entity.components.ComponentType;
      public class Component {public ComponentType type;public boolean meta;
       public Component(ComponentType t){type=t;}public ComponentType getComponentType(){return type;}
       public boolean isQualifiesForMetaStorage(){return meta;}}
    """
    files["zombie/entity/components/ComponentType.java"] = """package zombie.entity.components;
      import zombie.entity.Component;import zombie.entity.components.meta.MetaTagComponent;
      public enum ComponentType {Script,MetaTag,FluidContainer;
       public boolean isRunInMeta(){return this==FluidContainer;}
       public Component CreateComponent(){return this==MetaTag?new MetaTagComponent():new Component(this);}
       public static final Bits bitsRunInMeta=new Bits();
       public static class Bits {public boolean intersects(java.util.List<Component> cs){return cs.stream().anyMatch(c->c.type.isRunInMeta());}}
      }"""
    files["zombie/entity/components/meta/MetaTagComponent.java"] = """package zombie.entity.components.meta;
      import zombie.entity.Component;import zombie.entity.components.ComponentType;
      public class MetaTagComponent extends Component {long stored;
       public MetaTagComponent(){super(ComponentType.MetaTag);}public long getStoredID(){return stored;}public void setStoredID(long id){stored=id;}}
    """
    files["zombie/entity/MetaEntity.java"] = """package zombie.entity;
      public class MetaEntity extends GameEntity {static int released;static long next=10000;
       MetaEntity(long id){super(id);}static MetaEntity alloc(GameEntity from){return new MetaEntity(next++);}
       static void release(MetaEntity e){if(e.isAddedOrScheduledForEngine()||e.removingFromEngine||e.scheduledForEngineRemoval)throw new AssertionError("premature meta release");released++;}}
    """
    files["zombie/entity/Engine.java"] = """package zombie.entity;
      public class Engine {boolean processing;int frames,passes;int observedTicks;
       final EngineEntityManager manager=new EngineEntityManager(this,()->processing);
       final java.util.List<String> events=new java.util.ArrayList<>();Runnable simulate=()->{};
       void addEntity(GameEntity e){manager.addEntity(e);}void removeEntity(GameEntity e){manager.removeEntity(e);}
       void onEntityAdded(GameEntity e){events.add("add:"+e.id);}void onEntityRemoved(GameEntity e){events.add("remove:"+e.id);}
       void update(){frames++;manager.updateOperations();}
       void updateSimulation(){passes++;observedTicks=EntitySimulation.getEffectiveSimulationTicksThisFrame();processing=true;
        try{simulate.run();manager.updateOperations();}finally{processing=false;}}
      }"""
    files["zombie/iso/IsoObject.java"] = """package zombie.iso;
      public class IsoObject extends zombie.entity.GameEntity {public final Chunk chunk=new Chunk();
       public IsoObject(long id){super(id);}public void sync(){}public Chunk getChunk(){return chunk;}
       public static class Chunk {public boolean requiresHotSave;}}
    """
    # IsoObject is in another package; only its constructor must cross that boundary.
    files["zombie/entity/GameEntity.java"] = files["zombie/entity/GameEntity.java"].replace("GameEntity(long id)", "public GameEntity(long id)")
    files["zombie/network/GameClient.java"] = "package zombie.network;public class GameClient {public static boolean client;}"
    files["zombie/debug/DebugType.java"] = """package zombie.debug;
      public class DebugType {public static final Log Entity=new Log();public static class Log {
       public void noise(String s){}public void println(String s){} }}"""
    files["zombie/GameTime.java"] = "package zombie;public class GameTime {public static final GameTime instance=new GameTime();public float delta;public float getTimeDelta(){return delta;}}"
    files["zombie/ApocBRServerTelemetryLite.java"] = """package zombie;
      public class ApocBRServerTelemetryLite {
       public static final java.util.Map<String,Long> counts=new java.util.HashMap<>();
       public static int scopes;public static void count(String n,long v){counts.merge(n,v,Long::sum);}
       public static void recordPhase(String n,long v){}public static Scope phase(String n){scopes++;return new Scope();}
       public static class Scope implements AutoCloseable {public void close(){scopes--;}}}
    """
    files["zombie/entity/LifecycleTest.java"] = HARNESS
    return files


HARNESS = """package zombie.entity;
import zombie.GameTime;import zombie.network.GameClient;import zombie.iso.IsoObject;
import zombie.entity.components.ComponentType;import zombie.entity.components.meta.MetaTagComponent;
public class LifecycleTest {
 static int checks;static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static void fresh(){GameEntityManager.engine=new Engine();GameEntityManager.idToEntityMap.clear();
  GameEntityManager.delayedReleaseMetaEntities.clear();GameEntityManager.wasClient=false;GameClient.client=false;
  MetaEntity.released=0;EntitySimulation.reset();GameTime.instance.delta=0;}
 static GameEntity member(long id){GameEntity e=new GameEntity(id);e.addComponent(new Component(ComponentType.FluidContainer));return e;}
 static void clock(){fresh();Engine engine=GameEntityManager.engine;
  for(int ticks:new int[]{0,1,5,11}){int before=engine.passes;GameTime.instance.delta=ticks/10.0f;
   GameEntityManager.Update();check(engine.passes-before==(ticks==0?0:1),"one pass per elapsed batch");
   check(EntitySimulation.getSimulationTicksThisFrame()==ticks,"elapsed ticks retained");
   if(ticks>0)check(engine.observedTicks==ticks,"effective ticks passed to systems");}
  check(EntitySimulation.getTotalSimulationTicks()==17,"total elapsed ticks");
  GameClient.client=true;int before=engine.passes;GameTime.instance.delta=.5f;GameEntityManager.Update();
  check(engine.passes==before,"client suppresses simulation");check(zombie.ApocBRServerTelemetryLite.scopes==0,"scopes closed");
  GameClient.client=false;int owed=0;
  for(int i=0;i<30;i++){GameTime.instance.delta=.1f;GameEntityManager.Update();
   MetaEntity meta=new MetaEntity(3);if(!MetaSimulationThrottle.shouldSkip(meta))owed+=EntitySimulation.getEffectiveSimulationTicksThisFrame();}
  check(owed==30,"meta phased ticks aggregate without loss across full intervals");
 }
 static void lookup(){fresh();GameEntity stale=member(10),replacement=member(10);
  GameEntityManager.idToEntityMap.put(10L,stale);
  stale.getter=()->{stale.id=11;GameEntityManager.idToEntityMap.put(10L,replacement);};
  check(GameEntityManager.GetEntity(10)==replacement,"reread map after ID getter mutation");
  check(GameEntityManager.GetEntity(99)==null,"missing lookup");
  GameEntityManager.idToEntityMap.put(12L,stale);stale.id=12;
  stale.getter=()->{stale.id=13;GameEntityManager.idToEntityMap.remove(12L);};
  check(GameEntityManager.GetEntity(12)==null,"getter retired requested key");
 }
 static void lifecycle(){fresh();Engine engine=GameEntityManager.engine;GameEntity pending=member(1);
  engine.processing=true;engine.addEntity(pending);check(pending.scheduledDelayedAddToEngine,"queued addition");
  GameEntityManager.UnregisterEntity(pending);engine.processing=false;engine.manager.updateOperations();
  check(!pending.isAddedOrScheduledForEngine(),"unregistered pending add canceled");check(engine.events.isEmpty(),"no phantom callbacks");
  GameEntity e=member(2);GameEntityManager.RegisterEntity(e);check(e.addedToEntityManager&&e.addedToEngine,"registered actual member");
  GameEntityManager.UnregisterEntity(e);check(!e.addedToEntityManager&&!e.addedToEngine,"unregistered actual member");
  GameEntityManager.UnregisterEntity(e);check(engine.events.size()==2,"absent duplicate has no callbacks");
  MetaEntity m=new MetaEntity(3);m.addComponent(new Component(ComponentType.FluidContainer));GameEntityManager.RegisterEntity(m);
  GameEntityManager.UnregisterEntity(m);check(MetaEntity.released==1,"immediate meta release");
  MetaEntity delayed=new MetaEntity(4);delayed.addComponent(new Component(ComponentType.FluidContainer));GameEntityManager.RegisterEntity(delayed);
  engine.processing=true;GameEntityManager.UnregisterEntity(delayed);
  check(MetaEntity.released==1&&GameEntityManager.delayedReleaseMetaEntities.size()==1,"deferred meta release");
  engine.processing=false;GameTime.instance.delta=0;GameEntityManager.Update();
  check(MetaEntity.released==2&&!delayed.addedToEngine,"queued removal before release");
 }
 static void metaLoad(){fresh();MetaEntity stale=new MetaEntity(20),actual=new MetaEntity(20);
  Component state=new Component(ComponentType.FluidContainer);actual.addComponent(state);GameEntityManager.RegisterEntity(actual);
  GameEntityManager.idToEntityMap.put(20L,stale);
  stale.getter=()->{stale.id=21;GameEntityManager.idToEntityMap.put(20L,actual);};
  IsoObject object=new IsoObject(30);GameEntity loaded=object;
  MetaTagComponent tag=new MetaTagComponent();tag.setStoredID(20);loaded.addComponent(tag);
  GameEntityManager.RegisterEntity(object);
  check(loaded.components.contains(state),"meta registration uses refreshed lookup");
  check(loaded.addedToEngine&&loaded.addedToEntityManager,"loaded object registered");
  check(!actual.addedToEngine&&MetaEntity.released==1,"source meta retired");check(object.chunk.requiresHotSave,"loaded chunk marked dirty");
 }
 public static void main(String[] args){clock();lookup();lifecycle();metaLoad();System.out.println("PASS: "+checks+" target manager lifecycle assertions");}
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-manager-lifecycle-") as directory:
        work = Path(directory)
        sources = []
        for name, content in fixtures().items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources += [VERSION / "src/zombie/entity" / name for name in
                    ("EngineEntityManager.java", "EntitySimulation.java", "MetaSimulationThrottle.java")]
        args = work / "javac.args"
        args.write_text("\n".join('"'+str(p).replace("\\", "/")+'"' for p in sources), encoding="utf-8")
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@"+str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-ea", "-cp", str(work / "classes"), "zombie.entity.LifecycleTest"], check=True)


if __name__ == "__main__":
    main()
