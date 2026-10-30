"""42.22.0 CraftLogicSystem compiled whole with real clock/throttle and doubles.

Bucket selections, recipes/resources, native entities and world services are
explicit test inputs. This does not validate the real bucket index or game JAR.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / "42.22.0"

FILES = {
    "zombie/network/GameClient.java": "package zombie.network;public class GameClient {public static boolean client;}",
    "zombie/network/GameServer.java": "package zombie.network;public class GameServer {public static boolean server=true;}",
    "zombie/GameTime.java": "package zombie;public class GameTime {public static final GameTime instance=new GameTime();public float delta;public float getTimeDelta(){return delta;}}",
    "zombie/entity/ComponentType.java": "package zombie.entity;public enum ComponentType {Resources,CraftLogic,DryingCraftLogic}",
    "zombie/entity/EngineSystem.java": "package zombie.entity;public class EngineSystem {public EngineSystem(boolean f,boolean s,int p){}public void addedToEngine(Engine e){}public void updateSimulation(){}}",
    "zombie/entity/Family.java": "package zombie.entity;public class Family {public static Family all(ComponentType t){return new Family();}public Family one(ComponentType... t){return this;}public Family get(){return this;}}",
    "zombie/entity/Engine.java": "package zombie.entity;public class Engine {public EntityBucket bucket=new EntityBucket();public EntityBucket getBucket(Family f){return bucket;}}",
    "zombie/entity/EntityBucket.java": """package zombie.entity;import zombie.entity.util.ImmutableArray;
      public class EntityBucket {public ImmutableArray<GameEntity> all=new ImmutableArray<>(),selected=new ImmutableArray<>();
       public ImmutableArray<GameEntity> getEntities(){return all;}public ImmutableArray<GameEntity> getSimulationEntities(){return selected;}}""",
    "zombie/entity/util/ImmutableArray.java": "package zombie.entity.util;public class ImmutableArray<T> extends java.util.ArrayList<T>{}",
    "zombie/entity/GameEntity.java": """package zombie.entity;
      import zombie.entity.components.crafting.CraftLogic;import zombie.entity.components.resources.Resources;
      public class GameEntity {public long id;public boolean drying,valid=true;public int removed;
       public CraftLogic logic=new CraftLogic(this);public Resources resources=new Resources();
       public boolean isEntityValid(){return valid;}public boolean isValidEngineEntity(){return valid;}
       public boolean hasComponent(ComponentType t){return t==ComponentType.DryingCraftLogic&&drying;}
       public int getXi(){return 0;}public int getYi(){return 0;}public int getZi(){return 0;}
       public void removeFromWorld(){removed++;}public boolean isMeta(){return this instanceof MetaEntity;}
       public long getEntityNetID(){return id;}public String getEntityDisplayName(){return "fixture";}
       @SuppressWarnings("unchecked") public <T> T getComponent(ComponentType t){return (T)resources;}
       @SuppressWarnings("unchecked") public <T> T getComponentAny(ComponentType... t){return (T)logic;}}
    """,
    "zombie/entity/MetaEntity.java": "package zombie.entity;public class MetaEntity extends GameEntity {}",
    "zombie/iso/IsoObject.java": "package zombie.iso;public class IsoObject extends zombie.entity.GameEntity {}",
    "zombie/iso/IsoGridSquare.java": "package zombie.iso;public class IsoGridSquare {public final java.util.List<Object> objects=new java.util.ArrayList<>();public java.util.List<Object> getObjects(){return objects;}}",
    "zombie/network/ServerMap.java": "package zombie.network;public class ServerMap {public static final ServerMap instance=new ServerMap();public zombie.iso.IsoGridSquare square;public zombie.iso.IsoGridSquare getGridSquare(int x,int y,int z){return square;}}",
    "zombie/iso/IsoWorld.java": "package zombie.iso;public class IsoWorld {public static final IsoWorld instance=new IsoWorld();public Cell currentCell=new Cell();public static class Cell {public IsoGridSquare getGridSquare(int x,int y,int z){return zombie.network.ServerMap.instance.square;}}}",
    "zombie/debug/DebugType.java": "package zombie.debug;public class DebugType {public static final Log CraftLogic=new Log();public static class Log {public void debugln(String s,Object... args){}}}",
    "zombie/characters/IsoPlayer.java": "package zombie.characters;public class IsoPlayer {public final History history=new History();public History getPlayerCraftHistory(){return history;}public static class History {public int count;public void addCraftHistoryCraftedEvent(String s){count++;}}}",
    "zombie/scripting/entity/components/crafting/CraftRecipe.java": "package zombie.scripting.entity.components.crafting;public class CraftRecipe {public double time=100;public double getTime(){return time;}public String getName(){return " + '"fixture"' + ";}}",
    "zombie/entity/components/resources/ResourceGroup.java": "package zombie.entity.components.resources;public class ResourceGroup {public boolean dirty;public final java.util.ArrayList<Object> items=new java.util.ArrayList<>(java.util.List.of(new Object()));public boolean isDirty(){return dirty;}public java.util.ArrayList<Object> getResources(){return items;}}",
    "zombie/entity/components/resources/Resources.java": "package zombie.entity.components.resources;public class Resources {public boolean valid=true;public final ResourceGroup input=new ResourceGroup(),output=new ResourceGroup();public boolean isValid(){return valid;}public ResourceGroup getResourceGroup(String s){return s.equals(" + '"in"' + ")?input:output;}}",
    "zombie/entity/components/crafting/StartMode.java": "package zombie.entity.components.crafting;public enum StartMode {Manual,Automatic,Passive}",
    "zombie/entity/components/crafting/recipe/CraftRecipeData.java": """package zombie.entity.components.crafting.recipe;
      import java.util.List;import zombie.scripting.entity.components.crafting.CraftRecipe;
      public class CraftRecipeData {public double elapsed;public boolean outputs=true,consume=true;
       public int updates,starts,failed,created;public CraftRecipe recipe=new CraftRecipe();
       public boolean canCreateOutputs(List<Object> r){return outputs;}public boolean createOutputs(List<Object> r){return outputs;}
       public boolean consumeInputs(List<Object> r){return consume;}public boolean isFinished(){return elapsed>=recipe.time;}
       public double getElapsedTime(){return elapsed;}public void setElapsedTime(double e){elapsed=e;}
       public CraftRecipe getRecipe(){return recipe;}public void luaCallOnUpdate(){updates++;}public void luaCallOnStart(){starts++;}
       public void luaCallOnFailed(){failed++;}public void luaCallOnCreate(){created++;}}
    """,
    "zombie/entity/components/crafting/CraftLogic.java": """package zombie.entity.components.crafting;
      import zombie.entity.GameEntity;import zombie.characters.IsoPlayer;
      import zombie.entity.components.crafting.recipe.CraftRecipeData;import zombie.scripting.entity.components.crafting.CraftRecipe;
      public class CraftLogic {public final GameEntity entity;public boolean valid=true,running=true,start,stop,automatic;
       public IsoPlayer player;public int updates,starts,stops,returned,syncs,finalised;public boolean cancelled;
       public final java.util.ArrayList<CraftRecipeData> progress=new java.util.ArrayList<>();
       public CraftRecipeData pending=new CraftRecipeData();public CraftLogic(GameEntity e){entity=e;progress.add(new CraftRecipeData());}
       public boolean isValid(){return valid;}public String getInputsGroupName(){return "in";}public String getOutputsGroupName(){return "out";}
       public boolean isRunning(){return running;}public java.util.ArrayList<CraftRecipeData> getAllInProgressCraftData(){return progress;}
       public void onUpdate(CraftRecipeData d){updates++;}public boolean isStopRequested(){return stop;}
       public void setStopRequested(boolean v){stop=v;}public void setRequestingPlayer(IsoPlayer p){player=p;}
       public boolean isStartRequested(){return start;}public void setStartRequested(boolean v){start=v;}
       public IsoPlayer getRequestingPlayer(){return player;}public StartMode getStartMode(){return StartMode.Manual;}
       public boolean isDoAutomaticCraftCheck(){return automatic;}public void setDoAutomaticCraftCheck(boolean v){automatic=v;}
       public boolean canStart(StartMode m,IsoPlayer p){return true;}public CraftRecipe getPossibleRecipe(){return pending.recipe;}
       public void setRecipe(CraftRecipe r){}public CraftRecipeData getPendingCraftData(){return pending;}
       public void onStart(){starts++;running=true;progress.add(pending);}public void returnConsumedItemsToResourcesOrSquare(CraftRecipeData d){returned++;}
       public GameEntity getGameEntity(){return entity;}public void onStop(CraftRecipeData d,boolean c){stops++;cancelled=c;}
       public void finaliseRecipe(CraftRecipeData d){progress.remove(d);finalised++;}public void sendCraftLogicSync(){syncs++;}}
    """,
}

HARNESS = """package zombie.entity;
import zombie.entity.components.crafting.*;import zombie.entity.components.crafting.recipe.CraftRecipeData;
import zombie.network.*;import zombie.GameTime;
public class CraftTest {
 static int checks;static Engine engine;static CraftLogicSystem system;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void fresh(float delta){EntitySimulation.reset();GameTime.instance.delta=delta;EntitySimulation.update();
  GameClient.client=false;GameServer.server=true;ServerMap.instance.square=null;engine=new Engine();system=new CraftLogicSystem(1);system.addedToEngine(engine);}
 static <T extends GameEntity> T select(T e){engine.bucket.selected.add(e);engine.bucket.all.add(e);return e;}
 static void selection(){fresh(.5f);GameEntity loaded=select(new GameEntity());GameEntity excluded=new GameEntity();engine.bucket.all.add(excluded);
  MetaEntity notDue=select(new MetaEntity());notDue.id=1;MetaEntity due=select(new MetaEntity());due.id=5;
  system.updateSimulation();check(Math.abs(loaded.logic.progress.get(0).elapsed-12)<.0001,"loaded five-tick elapsed");
  check(loaded.logic.updates==1&&loaded.logic.progress.get(0).updates==1,"one callback pair per batch");
  check(excluded.logic.updates==0,"uses selected bucket rather than all members");check(notDue.logic.updates==0,"not-due meta throttled");
  check(Math.abs(due.logic.progress.get(0).elapsed-24)<.0001,"due meta receives ten owed ticks");
  fresh(0);MetaEntity zero=select(new MetaEntity());system.updateSimulation();check(zero.logic.updates==0,"zero owed meta does no work");
 }
 static void guards(){fresh(.1f);MetaEntity dry=select(new MetaEntity());dry.id=1;dry.drying=true;ServerMap.instance.square=new zombie.iso.IsoGridSquare();
  system.updateSimulation();check(dry.removed==1&&dry.logic.updates==0,"detached drying cleanup before not-due throttle");
  fresh(.1f);GameEntity bad=select(new GameEntity());bad.valid=false;system.updateSimulation();check(bad.logic.updates==0,"invalid entity");
  bad.valid=true;bad.resources.valid=false;system.updateSimulation();check(bad.logic.updates==0,"invalid resources");
  bad.resources.valid=true;bad.resources.input.items.clear();system.updateSimulation();check(bad.logic.updates==0,"empty input resources");
  bad.resources.input.items.add(new Object());GameClient.client=true;system.updateSimulation();check(bad.logic.updates==0,"client exclusion");
 }
 static void transitions(){fresh(.5f);GameEntity e=select(new GameEntity());CraftRecipeData data=e.logic.progress.get(0);data.recipe.time=5;
  system.updateSimulation();check(data.elapsed==5,"clamps progress to duration");check(data.created==0,"completion deferred to native next call");
  system.updateSimulation();check(data.created==1&&e.logic.finalised==1&&e.logic.syncs==1,"native completion effects once");
  system.updateSimulation();check(data.created==1,"completed recipe not repeated");
  fresh(.1f);e=select(new GameEntity());data=e.logic.progress.get(0);data.outputs=false;e.resources.output.dirty=true;
  system.updateSimulation();check(data.failed==1&&e.logic.returned==1&&e.logic.cancelled,"dirty output cancellation");
  check(data.updates==0&&data.elapsed==0,"cancel before progress/callback");
  fresh(.1f);e=select(new GameEntity());data=e.logic.progress.get(0);e.logic.stop=true;
  system.updateSimulation();check(data.failed==1&&e.logic.returned==1,"requested stop returns inputs");check(!e.logic.stop&&e.logic.player==null,"stop flags cleared");
  fresh(.1f);e=select(new GameEntity());e.logic.running=false;e.logic.progress.clear();e.logic.start=true;e.logic.player=new zombie.characters.IsoPlayer();
  var player=e.logic.player;system.updateSimulation();check(e.logic.starts==1&&e.logic.pending.starts==1,"manual start callbacks");
  check(player.history.count==1&&!e.logic.start&&e.logic.player==null,"history and request cleanup");
 }
 public static void main(String[] args){selection();guards();transitions();System.out.println("PASS: "+checks+" target crafting assertions");}
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-crafting-") as directory:
        work = Path(directory)
        sources = []
        for name, content in {**FILES, "zombie/entity/CraftTest.java": HARNESS}.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources += [VERSION / "src/zombie/entity" / name for name in
                    ("EntitySimulation.java", "MetaSimulationThrottle.java", "components/crafting/CraftLogicSystem.java")]
        args = work / "javac.args"
        args.write_text("\n".join('"'+str(p).replace("\\", "/")+'"' for p in sources), encoding="utf-8")
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@"+str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-ea", "-cp", str(work / "classes"), "zombie.entity.CraftTest"], check=True)


if __name__ == "__main__":
    main()
