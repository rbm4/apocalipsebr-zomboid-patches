"""42.22.2 native furniture adapter/catalog/capture with explicit engine doubles.

Compiles actual production adapters and scheduler. IsoObject's lifetime getter,
setSquare and removeFromSquare are extracted verbatim from the target patch.
Factory composition is source-checked; the full target-JAR build checks linkage.
No live chunks, metadata files, pathfinding or packet presentation are run.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "42.22.2/src/zombie/apocalipse/humans"


def method(text, signature):
    start = text.index(signature)
    end = text.index("{", start) + 1
    depth = 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


FIXTURE = {
    "zombie/iso/IsoCell.java": r'''package zombie.iso;import java.util.*;
public class IsoCell {public final Map<String,IsoGridSquare> tiles=new HashMap<>();public int reads;
 public IsoGridSquare getGridSquare(int x,int y,int z){reads++;return tiles.get(x+","+y+","+z);}
 public IsoGridSquare put(int x,int y,int z,Object room){var s=new IsoGridSquare(x,y,z,room);tiles.put(x+","+y+","+z,s);return s;}}
''',
    "zombie/iso/IsoGridSquare.java": r'''package zombie.iso;import java.util.*;
public class IsoGridSquare {public int x,y,z;public Object room;public List<IsoObject> objects=new ArrayList<>();public List<IsoObject> special=new ArrayList<>();
 public IsoGridSquare(int x,int y,int z,Object room){this.x=x;this.y=y;this.z=z;this.room=room;}
 public Object getRoom(){return room;}public List<IsoObject> getObjects(){return objects;}public List<IsoObject> getSpecialObjects(){return special;}}
''',
    "zombie/iso/sprite/IsoSprite.java": r'''package zombie.iso.sprite;
public class IsoSprite {public String name,tilesetName="chairs";public int tileSheetIndex;
 public IsoSprite(String name){this.name=name;}public String getName(){return name;}}
''',
    "zombie/characters/IsoPlayer.java": r'''package zombie.characters;import zombie.iso.*;
public class IsoPlayer {public IsoCell cell;public int x=10,y=10,z;public boolean dead;public IsoObject seated;public int occupancyReads;
 public IsoPlayer(IsoCell cell){this.cell=cell;}public IsoCell getCell(){return cell;}public boolean isDead(){return dead;}
 public int getXi(){return x;}public int getYi(){return y;}public int getZi(){return z;}
 public IsoObject getSitOnFurnitureObject(){occupancyReads++;return seated;}}
''',
    "zombie/seating/SeatingManager.java": r'''package zombie.seating;import java.util.*;import zombie.iso.sprite.IsoSprite;
public class SeatingManager {
 public static final SeatingManager INSTANCE=new SeatingManager();public int calls,mergedCount=1;public String facing="S";
 public final List<String> mods=new ArrayList<>();public final Map<String,Tile> tiles=new HashMap<>();
 public static class Vector {public float x,y,z;public Vector(float x,float y,float z){this.x=x;this.y=y;this.z=z;}}
 public static class Tile {public List<String> ids=new ArrayList<>();public List<Vector> translations=new ArrayList<>();public Map<String,String> properties=new HashMap<>();}
 public static SeatingManager getInstance(){return INSTANCE;}
 public int getTilePositionCount(String tileset,int col,int row){calls++;return mergedCount;}
 public int getTilePositionCount(String mod,String tileset,int col,int row){calls++;return tiles.containsKey(mod)?tiles.get(mod).ids.size():0;}
 public String getFacingDirection(IsoSprite sprite){calls++;return facing;}
 public ArrayList<String> getModIDs(){calls++;return new ArrayList<>(mods);}
 public String getTilePositionID(String mod,String tileset,int col,int row,int index){calls++;return tiles.get(mod).ids.get(index);}
 public Vector getTilePositionTranslate(String mod,String tileset,int col,int row,int index){calls++;return tiles.get(mod).translations.get(index);}
 public String getTilePositionProperty(String mod,String tileset,int col,int row,int index,String key){calls++;return tiles.get(mod).properties.get(key);}
}
''',
    "zombie/apocalipse/humans/brain/HumanActuator.java": "package zombie.apocalipse.humans.brain;public interface HumanActuator {}",
    "zombie/apocalipse/humans/profiles/HumanProfile.java": r'''package zombie.apocalipse.humans.profiles;import java.util.*;import zombie.apocalipse.humans.walking.*;
public record HumanProfile(List<WalkingPatternSpec> walkingPatterns){public static void identifier(String id){if(id==null||id.isBlank())throw new IllegalArgumentException();}}
''',
    "zombie/apocalipse/humans/HumanLog.java": r'''package zombie.apocalipse.humans;import java.util.function.Supplier;
public final class HumanLog {public enum Level{ERROR}public static int errors;public static void log(Level level,Supplier<String> text){errors++;text.get();}}
''',
    "zombie/apocalipse/humans/ServerHuman.java": r'''package zombie.apocalipse.humans;import java.util.*;import zombie.characters.*;import zombie.iso.*;import zombie.apocalipse.humans.brain.*;import zombie.apocalipse.humans.profiles.*;import zombie.apocalipse.humans.state.*;import zombie.apocalipse.humans.walking.*;
final class ServerHuman {
 private final Thread owner=Thread.currentThread();final IsoPlayer player;final Control activity;final HumanProfile profile;final Definition definition;
 final HumanActuator actuator=new HumanActuator(){};final Store state=new Store();
 record Definition(String key,int z){}static class Store{HumanState value=HumanState.INITIAL;HumanState snapshot(){return value;}}
 static class Control {final HumanBehavior behavior;int stops;Control(HumanBehavior behavior){this.behavior=behavior;}HumanBehavior behavior(){return behavior;}void stop(long now,HumanActuator actuator){stops++;}}
 ServerHuman(IsoCell cell,String key,HumanBehavior behavior,WalkingPatternSpec.Area area){player=new IsoPlayer(cell);activity=new Control(behavior);definition=new Definition(key,0);profile=new HumanProfile(List.of(new WalkingPatternSpec("background","indoor-wander",12,List.of(),area)));}
 void requireAuthority(){if(Thread.currentThread()!=owner)throw new IllegalStateException();}
}
''',
}

HARNESS = r'''
package zombie.apocalipse.humans;
import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
import zombie.iso.*;import zombie.iso.sprite.*;import zombie.characters.*;import zombie.seating.*;
import zombie.apocalipse.humans.brain.*;import zombie.apocalipse.humans.furniture.*;import zombie.apocalipse.humans.walking.*;
import static zombie.apocalipse.humans.furniture.FurnitureAffordances.*;
public class NativeFurnitureTest {
 static int checks;static final long SEC=1_000_000_000L;
 static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static FurnitureScanBudget budget(){var b=new FurnitureScanBudget();b.reset();return b;}
 static IsoObject object(IsoGridSquare square,String name){var o=new IsoObject();o.setSquare(square);o.sprite=new IsoSprite(name);square.objects.add(o);return o;}
 static class Catalog implements ServerHumanFurnitureWorld.Catalog {
  int reads,clears;boolean fail;String failName;
  public ServerHumanFurnitureWorld.Descriptor describe(IsoSprite sprite){reads++;if(fail||sprite!=null&&sprite.name.equals(failName))throw new IllegalStateException("injected catalog failure");return sprite==null||sprite.name.startsWith("floor")?null:new ServerHumanFurnitureWorld.Descriptor("S",new Seat(.1f,.2f,.3f,Set.of("Front","Left","Right")));}
  public void clear(){clears++;}
 }
 static class Behavior implements HumanBehavior {
  final FurnitureDemand demand;Behavior(boolean enabled){demand=enabled?new FurnitureDemand(Set.of(Kind.SIT),1,1000):FurnitureDemand.NONE;}
  public FurnitureDemand furnitureDemand(){return demand;}public void update(HumanObservation o,HumanActuator a){}
  public void suspend(long t,HumanActuator a){}public void resume(HumanObservation.Identity id,long t,HumanActuator a){}public void stop(long t,HumanActuator a){}public boolean available(){return true;}
 }
 static IsoCell cell(Object room){var cell=new IsoCell();for(int x=9;x<=11;x++)for(int y=9;y<=11;y++)cell.put(x,y,0,room);return cell;}
 static void metadata(){
  var manager=SeatingManager.INSTANCE;manager.mods.clear();manager.tiles.clear();manager.mods.addAll(List.of("first","second","game"));manager.mergedCount=1;manager.facing="S";
  var a=new SeatingManager.Tile();a.ids.add("S");a.translations.add(new SeatingManager.Vector(.1f,.3f,.2f));a.properties.put("BlockLeft","true");manager.tiles.put("first",a);
  var b=new SeatingManager.Tile();b.ids.add("S");b.translations.add(new SeatingManager.Vector(.7f,.8f,.9f));b.properties.put("BlockRight","true");manager.tiles.put("second",b);
  var catalog=new ServerHumanSeatingCatalog();var sprite=new IsoSprite("chair");var descriptor=catalog.describe(sprite);
  check(descriptor.facing().equals("S")&&descriptor.seat().offsetX()==.1f&&descriptor.seat().offsetY()==.2f&&descriptor.seat().height()==.3f,"native coordinate mapping and first source precedence");
  check(descriptor.seat().sides().equals(Set.of("Front","Right")),"native side restrictions, not guessed approaches");
  int reads=manager.calls;check(catalog.describe(sprite)==descriptor&&manager.calls==reads,"sprite metadata cached");
  sprite.name="chair-changed";catalog.describe(sprite);check(manager.calls>reads,"sprite name revision invalidates cache key");
  reads=manager.calls;check(catalog.describe(null)==null&&manager.calls==reads,"missing sprite requires no metadata queries");
  manager.mergedCount=0;check(catalog.describe(new IsoSprite("no-data"))==null,"sprite without native seat positions is not a seat");
  manager.mergedCount=9;check(catalog.describe(new IsoSprite("excess"))==null,"position count bounded");
  manager.mergedCount=1;manager.facing="default";check(catalog.describe(new IsoSprite("invalid-direction"))==null,"unresolved native direction unavailable");manager.facing="S";
  a.translations.set(0,new SeatingManager.Vector(Float.NaN,0,0));check(catalog.describe(new IsoSprite("invalid-offset"))==null,"invalid native offset rejected");a.translations.set(0,new SeatingManager.Vector(.1f,.3f,.2f));
  var first=new IsoSprite("cache-first");catalog.clear();catalog.describe(first);for(int i=0;i<512;i++)catalog.describe(new IsoSprite("cache-"+i));
  reads=manager.calls;catalog.describe(first);check(manager.calls>reads,"bounded metadata cache evicts old sprite");
 }
 static void nativeWorld() throws Exception {
  check(java.lang.reflect.Modifier.isTransient(IsoObject.class.getDeclaredField("apocbrLifetimeRevision").getModifiers()),"native lifetime revision never enters Java serialization");
  Object room=new Object();var cell=cell(room);var chair=object(cell.getGridSquare(10,10,0),"chair");object(cell.getGridSquare(11,10,0),"floor");
  var otherRoom=cell.getGridSquare(9,10,0);otherRoom.room=new Object();object(otherRoom,"other-room-chair");object(cell.put(10,10,1,room),"upstairs");
  var catalog=new Catalog();var world=new ServerHumanFurnitureWorld(catalog);world.beginPass(cell,List.of(),List.of());
  var view=world.view(cell,0,null);view.begin(10,10,0);var scanner=new FurnitureScanner(new FurnitureDemand(Set.of(Kind.SIT),1,1000));
  var frame=scanner.update(10,10,0,0,view,budget());check(frame.candidates().size()==1&&frame.coverage()==Coverage.COMPLETE,"only loaded current-room same-floor furniture");
  var candidate=frame.candidates().getFirst();var token=candidate.token();
  check(candidate.approaches().isEmpty()&&candidate.seat().sides().size()==3,"discovered native seat does not invent reachable geometry");
  check(FurnitureSelector.choose(frame,Kind.SIT,new Position(10,10,0),0,Set.of())==null,"unresolved geometry cannot authorize approach");
  check(world.resolve(token)==chair,"fresh server-only binding resolves");
  check(world.resolve(new Token(token.generation(),token.x(),token.y(),token.z(),"N"))==null&&world.resolve(token)==chair,"invalid slot cannot authorize or erase a valid binding");
  var second=world.view(cell,0,null);second.begin(10,10,0);second.objectCount(10,10,0);
  check(second.inspect(10,10,0,0,Set.of(Kind.SIT),0).token().equals(token),"observers share object generation");
  var player=new IsoPlayer(cell);player.seated=chair;world.beginPass(cell,List.of(player),List.of());
  view.objectCount(10,10,0);check(view.inspect(10,10,0,0,Set.of(Kind.SIT),0).occupied(),"real-player occupancy captured");
  world.beginPass(cell,List.of(),List.of(player));check(view.inspect(10,10,0,0,Set.of(Kind.SIT),0).occupied(),"detached NPC occupancy without square membership");
  var excess=new ArrayList<IsoPlayer>();for(int i=0;i<129;i++)excess.add(new IsoPlayer(cell));world.beginPass(cell,excess,List.of());
  check(view.inspect(10,10,0,0,Set.of(Kind.SIT),0).occupied(),"incomplete occupancy never advertises free seat");
  world.beginPass(cell,List.of(),List.of());check(!view.inspect(10,10,0,0,Set.of(Kind.SIT),0).occupied(),"standing player frees observation next pass");
  // Verbatim production removeFromSquare/setSquare revise the same pooled native object.
  chair.removeFromSquare();chair.setSquare(cell.getGridSquare(10,10,0));chair.square.objects.add(chair);
  check(world.resolve(token)==null,"remove/re-add same object same location invalidates token");
  view.objectCount(10,10,0);var renewed=view.inspect(10,10,0,0,Set.of(Kind.SIT),SEC).token();check(renewed.generation()>token.generation(),"native incarnation gets fresh generation");
  chair.sprite.name="chair-repainted";check(world.resolve(renewed)==null,"same sprite instance name change invalidates native stamp");
  view.objectCount(10,10,0);renewed=view.inspect(10,10,0,0,Set.of(Kind.SIT),SEC).token();
  var pad=new IsoObject();pad.setSquare(chair.square);pad.sprite=new IsoSprite("floor-pad");chair.square.objects.add(0,pad);
  check(world.resolve(renewed)==null,"raw object-list ordinal shift invalidates captured membership");
  view.objectCount(10,10,0);renewed=view.inspect(10,10,0,1,Set.of(Kind.SIT),SEC).token();chair.square.objects.remove(pad);
  check(world.resolve(renewed)==null,"returning to previous index cannot revive shifted receipt");
  view.objectCount(10,10,0);renewed=view.inspect(10,10,0,0,Set.of(Kind.SIT),SEC).token();
  var oldSquare=chair.square;cell.put(10,10,0,room);check(world.resolve(renewed)==null,"chunk square replacement invalidates binding");
  cell.tiles.put("10,10,0",oldSquare);view.begin(10,10,0);view.objectCount(10,10,0);renewed=view.inspect(10,10,0,0,Set.of(Kind.SIT),SEC).token();
  var differentCell=cell(new Object());world.beginPass(differentCell,List.of(),List.of());check(world.resolve(renewed)==null&&view.objectCount(10,10,0)==-1,"world replacement clears bindings and old observer availability");
  var wrong=new AtomicBoolean();var thread=new Thread(()->{try{world.clear();}catch(IllegalStateException expected){wrong.set(true);}});thread.start();thread.join();check(wrong.get(),"native adapter rejects unrelated thread");
 }
 static void composition(){
  Object room=new Object();var cell=cell(room);object(cell.getGridSquare(10,10,0),"chair");var catalog=new Catalog();var world=new ServerHumanFurnitureWorld(catalog);var service=new ServerHumanFurniture(world);
  var ordinary=new ServerHuman(cell,"ordinary",new Behavior(false),null);check(service.register(ordinary)==null,"no-demand profile allocates no scanner");
  int reads=cell.reads;service.capture(0,cell,List.of());check(cell.reads==reads&&catalog.reads==0&&ordinary.player.occupancyReads==0,"no-demand zero world/catalog/occupancy capture");
  var first=new ServerHuman(cell,"first",new Behavior(true),null);var observer=service.register(first);service.capture(0,cell,List.of());
  check(observer.snapshot().candidates().size()==1,"module demand drives real native capture");
  var saved=observer.snapshot();cell.getGridSquare(10,10,0).room=new Object();service.capture(SEC/10,cell,List.of());
  check(observer.snapshot()!=saved&&observer.snapshot().sampledAt()==SEC/10,"room identity change invalidates cadence cache");
  var bounded=new ServerHuman(cell,"bounded",new Behavior(true),new WalkingPatternSpec.Area(9,9,9,9));var bound=service.register(bounded);service.capture(2*SEC,cell,List.of());
  check(bound.snapshot().candidates().isEmpty(),"explicit profile area constrains discovery");
  var old=observer.snapshot();service.remove(first);check(observer.snapshot()==Frame.EMPTY&&old.sampled(),"removal clears observer while detached prior frame stays immutable");
  service.remove(bounded);service.remove(ordinary);service.capture(3*SEC,cell,List.of());
  var replacement=new ServerHuman(cell,"new-incarnation",new Behavior(true),null);var fresh=service.register(replacement);service.capture(4*SEC,cell,List.of());
  check(fresh.snapshot().candidates().getFirst().token().generation()>old.candidates().getFirst().token().generation(),"all-observer cleanup never recycles generation");
  catalog.fail=true; // force native metadata read on the next due sample
  service.capture(6*SEC,cell,List.of());check(fresh.snapshot()==Frame.EMPTY&&replacement.activity.stops==0&&HumanLog.errors==1,"capture failure disables furniture while preserving other activity");
  int before=cell.reads;service.capture(8*SEC,cell,List.of());check(replacement.activity.stops==0&&HumanLog.errors==1&&cell.reads==before,"failed observer cannot repeat native capture or actuate/log");
  service.remove(replacement);
  catalog.fail=false;var again=new ServerHuman(cell,"new-incarnation",new Behavior(true),null);var recovered=service.register(again);service.capture(9*SEC,cell,List.of());
  check(recovered.snapshot().candidates().size()==1&&again.activity.stops==0,"fresh activation restores sensing after previous observer failure");service.remove(again);
 }
 static void isolatedFailure(){
  var cell=cell(new Object());object(cell.getGridSquare(10,10,0),"bad");cell.getGridSquare(11,10,0).room=new Object();object(cell.getGridSquare(11,10,0),"good");
  var catalog=new Catalog();catalog.failName="bad";var service=new ServerHumanFurniture(new ServerHumanFurnitureWorld(catalog));
  var a=new ServerHuman(cell,"failure",new Behavior(true),null);var b=new ServerHuman(cell,"healthy",new Behavior(true),null);b.player.x=11;
  var failed=service.register(a);var healthy=service.register(b);int before=HumanLog.errors;service.capture(0,cell,List.of());
  check(failed.snapshot()==Frame.EMPTY&&healthy.snapshot().candidates().size()==1&&a.activity.stops==0&&b.activity.stops==0&&HumanLog.errors==before+1,"one observer failure does not disable another room or autonomous activity");
  service.remove(a);service.remove(b);
 }
 static void fairness(){
  var scheduler=new FurnitureCaptureScheduler();int[] visits=new int[64],work=new int[64];
  var participants=new ArrayList<FurnitureCaptureScheduler.Participant>();
  for(int i=0;i<64;i++){final int index=i;FurnitureCaptureScheduler.Participant p=(now,b)->{visits[index]++;for(int n=0;n<64;n++){if(!b.square())break;if(!b.object())break;work[index]++;}};participants.add(p);scheduler.add(p);}
  for(int i=0;i<16;i++)scheduler.capture(i*SEC/10);
  for(int i=0;i<64;i++)check(work[i]==64&&visits[i]==16,"64 actors receive work within sixteen passes; denied actors still invalidate frames");
  scheduler.remove(participants.get(0));scheduler.capture(2*SEC);check(visits[0]==16,"removed participant not captured again");
  var uncertain=new FurnitureScanner(new FurnitureDemand(Set.of(Kind.SIT),1,1000));var world=new FurnitureScanner.World(){
   public int objectCount(int x,int y,int z){return x==10&&y==10?200:0;}public long revision(int x,int y,int z){return UNTRACKED_REVISION;}
   public Candidate inspect(int x,int y,int z,int index,Set<Kind> kinds,long now){return null;}};
  uncertain.update(10,10,0,0,world,budget());var frame=uncertain.update(10,10,0,SEC/10,world,budget());
  check(frame.coverage()==Coverage.PARTIAL,"unversioned native list resumed across passes cannot claim completeness");
 }
 public static void main(String[] args)throws Exception{metadata();nativeWorld();composition();isolatedFailure();fairness();System.out.println("PASS: "+checks+" production native furniture/catalog/scheduling assertions");}
}
'''


def main():
    native = (ROOT / "42.22.2/src/zombie/iso/IsoObject.java").read_text(encoding="utf-8")
    for signature in ("public void reset()", "public void addToWorld()", "public void removeFromWorld()"):
        body = method(native, signature).split("{", 1)[1].lstrip()
        assert body.startswith("this.apocbrLifetimeRevision++;"), f"Missing lifetime hook: {signature}"
    fixture = dict(FIXTURE)
    field = next(line for line in native.splitlines() if "private transient long apocbrLifetimeRevision;" in line)
    fixture["zombie/iso/IsoObject.java"] = """package zombie.iso;import zombie.iso.sprite.IsoSprite;
public class IsoObject {public IsoGridSquare square;public IsoSprite sprite;
public IsoGridSquare getSquare(){return square;}public IsoSprite getSprite(){return sprite;}
""" + field + "\n" + "\n".join(method(native, signature) for signature in (
        "public final long getApocbrLifetimeRevision()", "public void setSquare(IsoGridSquare square)", "public void removeFromSquare()")) + "\n}"
    fixture["zombie/apocalipse/humans/NativeFurnitureTest.java"] = HARNESS
    # Production composition call sites are not replaced by a model in this fixture.
    factory = (SOURCE / "ServerHumanFactory.java").read_text(encoding="utf-8")
    assert factory.index("furniture.capture(now,IsoWorld.instance.currentCell,GameServer.Players)") < factory.index("human.activity.update(now")
    assert "human.attachFurniture(furniture)" in factory
    assert "human.furnitureSnapshot()" in (SOURCE / "ServerHumanPerception.java").read_text(encoding="utf-8")
    assert "furnitureOwner.remove(this)" in (SOURCE / "ServerHuman.java").read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory(prefix="apocbr-native-furniture-") as directory:
        work = Path(directory)
        sources = []
        for name, content in fixture.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources.extend(sorted((SOURCE / "furniture").glob("*.java")))
        sources.extend(sorted((SOURCE / "interactions").glob("*.java")))
        for name in ("ServerHumanFurniture", "ServerHumanFurnitureWorld", "ServerHumanSeatingCatalog", "brain/HumanBehavior", "brain/HumanObservation", "brain/HumanSenses", "brain/HumanTerritory", "state/HumanState", "walking/WalkingPatternSpec"):
            sources.append(SOURCE / (name + ".java"))
        sources.append(ROOT / "42.22.2/src/zombie/util/list/MutationTrackedArrayList.java")
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources), encoding="utf-8")
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-encoding", "UTF-8", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.apocalipse.humans.NativeFurnitureTest"], check=True)


if __name__ == "__main__":
    main()
