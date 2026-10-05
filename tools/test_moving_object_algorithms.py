"""Exercise the production scheduler/index/bucket against small engine fixtures.

Run with Python and the repository JDK 25. No game initialization, deployment,
or native libraries are needed. These tests cover collection lifecycle behavior;
the normal patch dry run separately checks real engine binary compatibility.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "zombie/ServerPlayerSpatialQueries.java": "package zombie; public class ServerPlayerSpatialQueries { public void vehicleMoved(zombie.vehicles.BaseVehicle v){} }",
    "zombie/GameTime.java": """
package zombie;
public class GameTime {
    private static final GameTime instance = new GameTime();
    public float perObjectMultiplier = 1;
    public static GameTime getInstance() { return instance; }
}
""",
    "zombie/GameWindow.java": "package zombie; public class GameWindow { public static float averageFPS=60; }",
    "zombie/network/GameServer.java": "package zombie.network; public class GameServer { public static boolean server=true, guiCommandline=false; }",
    "zombie/ApocBRServerTelemetryLite.java": "package zombie; public class ApocBRServerTelemetryLite { public static void recordPhase(String n,long t){} public static final java.util.Map<String,Long> counts=new java.util.HashMap<>(); public static void count(String n,long v){counts.merge(n,v,Long::sum);} public static Scope phase(String n){return new Scope();} public static class Scope implements AutoCloseable {public void close(){}} }",
    "zombie/VirtualZombieManager.java": "package zombie; import zombie.characters.IsoZombie; public class VirtualZombieManager { public static final VirtualZombieManager instance=new VirtualZombieManager(); public boolean isReused(IsoZombie z){return false;} }",
    "zombie/popman/NetworkZombiePacker.java": "package zombie.popman; public class NetworkZombiePacker { private static final NetworkZombiePacker i=new NetworkZombiePacker(); public static NetworkZombiePacker getInstance(){return i;} public void awaitWorkers(){} }",
    "zombie/popman/ZombieCountOptimiser.java": "package zombie.popman; public class ZombieCountOptimiser { public static void prepareZombiesForDeletion(){} public static void deleteZombies(){} }",
    "zombie/core/math/PZMath.java": "package zombie.core.math; public class PZMath { public static float min(float a,float b){return Math.min(a,b);} public static int min(int a,int b){return Math.min(a,b);} public static float max(float a,float b){return Math.max(a,b);} public static int abs(int v){return Math.abs(v);} }",
    "zombie/debug/DebugLog.java": "package zombie.debug; public class DebugLog { public static void log(Object t,String s){} }",
    "zombie/debug/DebugType.java": "package zombie.debug; public enum DebugType { Zombie }",
    "zombie/util/Type.java": "package zombie.util; public class Type { public static <T> T tryCastTo(Object o,Class<T> c){return c.isInstance(o)?c.cast(o):null;} }",
    "zombie/util/list/PZArrayUtil.java": """
package zombie.util.list;
import java.util.function.*;
public class PZArrayUtil {
    @SuppressWarnings("unchecked")
    public static <T> T[] newInstance(Class<T> c,int n,Supplier<T> f){
        T[] a=(T[])java.lang.reflect.Array.newInstance(c,n);
        for(int i=0;i<n;i++) a[i]=f.get(); return a;
    }
    public static <T> void forEach(T[] a,Consumer<T> f){for(T v:a)f.accept(v);}
    public static <T,U> void forEach(T[] a,U u,BiConsumer<T,U> f){for(T v:a)f.accept(v,u);}
}
""",
    "zombie/iso/IsoCell.java": """
package zombie.iso;
import java.util.*;
public class IsoCell {
    public final Set<IsoMovingObject> objects=new zombie.ServerMovingObjectSet(), removed=new HashSet<>();
    public java.util.Set<zombie.vehicles.BaseVehicle> getVehicles(){return java.util.Collections.emptySet();}
    public zombie.ServerPlayerSpatialQueries getPlayerSpatialQueries(){return new zombie.ServerPlayerSpatialQueries();}
    public Set<IsoMovingObject> getObjectList(){return objects;}
    public Set<IsoMovingObject> getRemoveList(){return removed;}
}
""",
    "zombie/iso/IsoWorld.java": """
package zombie.iso;
public class IsoWorld {
    public static final IsoWorld instance=new IsoWorld();
    public IsoCell cell=new IsoCell();
    public java.util.concurrent.CompletableFuture<Void> animation;
    public IsoCell getCell(){return cell;}
    public void FinishAnimation(){if(animation!=null){animation.join();animation=null;}}
}
""",
    "zombie/iso/IsoMovingObject.java": """
package zombie.iso;
import zombie.UpdateSchedulerSimulationLevel;
public class IsoMovingObject {
    public int id, updates, posts;
    public float x,y;
    public float getX(){return x;} public float getY(){return y;}
    public Runnable onUpdate, onPost;
    public static int equalsCalls;
    public UpdateSchedulerSimulationLevel minimum=UpdateSchedulerSimulationLevel.FULL;
    public int getID(){return id;}
    public Object getCurrentSquare(){return this;}
    public void setCurrentSquareFromPosition(){}
    public UpdateSchedulerSimulationLevel getMinimumSimulationLevel(){return minimum;}
    public void setCurrentSimulationLevel(UpdateSchedulerSimulationLevel l){}
    public void preupdate(){} public void frameStep(){}
    public void update(){updates++; if(onUpdate!=null)onUpdate.run();}
    public void postupdate(){posts++; if(onPost!=null)onPost.run();}
    public boolean getDoRender(){return true;} public boolean isSceneCulled(){return false;}
    public float DistTo(Object o){return 0;} public int getZi(){return 0;}
    public float getAlpha(int i){return 1;} public float getTargetAlpha(int i){return 1;}
    @Override public boolean equals(Object o){equalsCalls++; return this==o;}
    @Override public int hashCode(){return System.identityHashCode(this);}
}
""",
    "zombie/iso/objects/IsoDeadBody.java": "package zombie.iso.objects; public class IsoDeadBody extends zombie.iso.IsoMovingObject {}",
    "zombie/characters/IsoGameCharacter.java": "package zombie.characters; public class IsoGameCharacter extends zombie.iso.IsoMovingObject {}",
    "zombie/characters/IsoZombie.java": "package zombie.characters; public class IsoZombie extends IsoGameCharacter { public void updateForServerGui(){} }",
    "zombie/characters/IsoPlayer.java": "package zombie.characters; public class IsoPlayer extends IsoGameCharacter { public static int numPlayers=0; public static IsoPlayer[] players=new IsoPlayer[0]; }",
    "zombie/characters/animals/IsoAnimal.java": """
package zombie.characters.animals;
public class IsoAnimal extends zombie.characters.IsoPlayer {
    public Object heldBy,luredBy,atkTarget,fightingOpponent,thumpTarget,alertedChr;
    public boolean alerted,walkToCharLuring,onHook;
    public int sounds;
    public Object getVehicle(){return null;}
    public boolean isOnHook(){return onHook;}
    public void updateVocalProperties(){}
    public void updateLoopingSounds(){sounds++;}
}
""",
    "zombie/vehicles/BaseVehicle.java": """
package zombie.vehicles;
import java.util.*;
import zombie.characters.IsoGameCharacter;
public class BaseVehicle extends zombie.iso.IsoMovingObject {
    public boolean active;
    public enum engineStateTypes { Idle, Running }
    public static class Mode { public boolean isEnable(){return false;} }
    public final Mode lightbarLightsMode=new Mode(), lightbarSirenMode=new Mode();
    public Object getDriver(){return active?this:null;}
    public boolean isMechanicUIOpen(){return false;} public boolean needPartsUpdate(){return false;}
    public engineStateTypes getEngineState(){return engineStateTypes.Idle;}
    public boolean isAlarmActive(){return false;} public boolean isSirenActive(){return false;}
    public Object getVehicleTowedBy(){return null;} public Object getVehicleTowing(){return null;}
    public boolean isAtRest(){return true;} public List<Object> getAnimals(){return List.of();}
    public int getMaxPassengers(){return 0;} public IsoGameCharacter getCharacter(int n){return null;}
}
""",
}

HARNESS = r"""
package zombie;
import java.lang.reflect.*;
import java.util.*;
import zombie.iso.*;
import zombie.characters.*;
import zombie.characters.animals.IsoAnimal;
import zombie.vehicles.BaseVehicle;
import zombie.network.GameServer;

public class MovingObjectAlgorithmsTest {
    static int assertions;
    static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    static IsoMovingObject object(int id){IsoMovingObject o=new IsoMovingObject();o.id=id;IsoWorld.instance.cell.objects.add(o);return o;}
    static void resetWorld(){IsoWorld.instance.cell=new IsoCell();}
    static MovingObjectUpdateSchedulerUpdateBucket[] buckets() throws Exception {
        Field f=MovingObjectUpdateScheduler.class.getDeclaredField("simulationLevels");f.setAccessible(true);
        return (MovingObjectUpdateSchedulerUpdateBucket[])f.get(MovingObjectUpdateScheduler.instance);
    }
    static Object position(MovingObjectUpdateSchedulerUpdateBucket b,IsoMovingObject o) throws Exception {
        Field f=b.getClass().getDeclaredField("positions");f.setAccessible(true);
        return ((Map<?,?>)f.get(b)).get(o);
    }
    static void bucketTests(){
        var b=new MovingObjectUpdateSchedulerUpdateBucket(UpdateSchedulerSimulationLevel.FULL);
        var a=object(0);var next=object(1);var victim=object(2);
        b.add(a);b.add(next);b.add(victim);b.add(next);
        a.onUpdate=()->{b.removeObject(a);b.removeObject(victim);};
        b.update(0);
        check(a.updates==1 && next.updates==1 && victim.updates==0,"callback removal skips victim, not successor; no duplicates");
        b.postupdate(0);check(next.posts==1 && a.posts==0,"removed object cannot postupdate");
        b.compact();check(b.getBucket(0).equals(List.of(next)),"stable compaction");
        b.removeObject(next);check(b.getBucket(0).isEmpty(),"indices updated by compaction");
        b.compact();b.add(next);next.onPost=()->b.removeObject(next);
        b.postupdate(0);check(b.getBucket(0).isEmpty(),"postupdate self-removal");
        b.clear();var failing=object(3);failing.onUpdate=()->{throw new IllegalStateException();};b.add(failing);
        try{b.update(0);}catch(IllegalStateException expected){}
        check(GameTime.getInstance().perObjectMultiplier==1,"multiplier restored on update failure");
        b.clear();var sparse=new MovingObjectUpdateSchedulerUpdateBucket(UpdateSchedulerSimulationLevel.SIXTEENTH);
        var negative=object(-1);sparse.add(negative);sparse.update(-1);
        check(negative.updates==1,"negative IDs/frame rollover remain schedulable");
        sparse.clear();check(sparse.getBucket(-1).isEmpty(),"clear releases membership");
        GameServer.server=false;b.clear();b.add(a);b.add(next);b.removeObject(a);b.update(0);
        check(next.updates==2,"client clear/rebuild/removal behavior");GameServer.server=true;
    }
    static void schedulerTests() throws Exception {
        resetWorld();var s=MovingObjectUpdateScheduler.instance;var cell=IsoWorld.instance.cell;
        var regular=object(16);var car=new BaseVehicle();car.id=32;var animal=new IsoAnimal();animal.id=48;
        var zombie=new IsoZombie();cell.objects.addAll(List.of(regular,car,animal,zombie));
        s.startFrame();var full=buckets()[0];var slow=buckets()[4];Object original=position(full,regular);
        s.startFrame();check(position(full,regular)==original,"stable frame retains bucket entry");
        check(slow.getBucket(0).contains(car)&&slow.getBucket(0).contains(animal),"idle car/animal throttles retained");
        car.active=true;animal.alerted=true;s.startFrame();
        check(full.getBucket(0).contains(car)&&!slow.getBucket(0).contains(car),"car promoted without duplicate");
        check(buckets()[1].getBucket(0).contains(animal),"alert animal promoted to HALF");
        car.active=false;animal.alerted=false;s.startFrame();
        check(!full.getBucket(0).contains(car)&&slow.getBucket(0).contains(car),"car demotion");
        var replacement=object(64);cell.objects.remove(regular);cell.objects.add(replacement);s.startFrame();
        check(!full.getBucket(0).contains(regular)&&full.getBucket(0).contains(replacement),"direct removal with same world cardinality updates lifecycle");
        s.removeObject(replacement);s.removeObject(replacement);cell.objects.remove(replacement);
        check(!full.getBucket(0).contains(replacement),"scheduler removal is idempotent");
        resetWorld();s.startFrame();for(var b:buckets())for(int phase=0;phase<16;phase++)check(b.getBucket(phase).isEmpty(),"new cell clears retained references");
        GameServer.server=false;var client=object(1);IsoWorld.instance.cell.objects.add(client);s.startFrame();s.update();
        check(client.updates==1,"client scheduling fallback");GameServer.server=true;
    }
    static void indexTests(){
        resetWorld();var c=IsoWorld.instance.cell;var p=new IsoPlayer();var a=new IsoAnimal();var z=new IsoZombie();var car=new BaseVehicle();
        c.objects.addAll(List.of(a,car,z,object(6),p));
        check(ServerMovingObjectIndex.getPerceptionTargets(c).equals(List.of(z,p)),"filter retains lifetime order; animals are not human players");
        for(int frame=0;frame<4;frame++)ServerMovingObjectIndex.updateAnimalSounds(c,frame);
        check(a.sounds==1,"sound work distributed once per four frames");
        a.onHook=true;for(int frame=4;frame<8;frame++)ServerMovingObjectIndex.updateAnimalSounds(c,frame);
        check(a.sounds==1,"hooked animals do not run sound work");
        var newcomer=new IsoPlayer();c.objects.add(newcomer);
        check(ServerMovingObjectIndex.getPerceptionTargets(c).contains(newcomer),"insertion immediately registers perception target");
        var cached=ServerMovingObjectIndex.getPerceptionTargets(c);c.objects.remove(p);
        check(ServerMovingObjectIndex.getPerceptionTargets(c)==cached,"removal does not cause repeated world rebuilds");
        check(!cached.contains(p),"removal immediately tombstones perception membership");
        c.objects.remove(z);c.objects.add(new IsoZombie());
        check(!ServerMovingObjectIndex.getPerceptionTargets(c).contains(z),"same-cardinality direct replacement maintains index");
    }
    static void threadedFrameTest() throws Exception {
        resetWorld();var s=MovingObjectUpdateScheduler.instance;var o=object(7);
        IsoWorld.instance.cell.objects.add(o);s.startFrame();s.update();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        o.onPost=()->{
            entered.countDown();
            try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}
            s.removeObject(o); // Reentrant removal while the worker owns the scheduler.
            IsoWorld.instance.cell.objects.remove(o);
        };
        IsoWorld.instance.animation=java.util.concurrent.CompletableFuture.runAsync(s::postupdate);
        check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS),"animation worker entered postupdate");
        var nextFrame=java.util.concurrent.CompletableFuture.runAsync(s::startFrame);
        try{
            nextFrame.get(50,java.util.concurrent.TimeUnit.MILLISECONDS);
            throw new AssertionError("next frame must wait for preceding animation");
        }catch(java.util.concurrent.TimeoutException expected){
            check(true,"next frame waits before changing buckets");
        }finally{release.countDown();}
        nextFrame.get(5,java.util.concurrent.TimeUnit.SECONDS);
        check(position(buckets()[0],o)==null,"animation removal and frame transition complete without deadlock");
    }
    static void lifecycleTests() throws Exception {
        resetWorld();var c=IsoWorld.instance.cell;var s=MovingObjectUpdateScheduler.instance;
        var human=new IsoPlayer();var animal=new IsoAnimal();var zombie=new IsoZombie();
        c.objects.addAll(List.of(human,animal,zombie));
        long classified=ApocBRServerTelemetryLite.counts.getOrDefault("movingObjects.classified",0L);
        check(!c.objects.add(animal),"duplicate add does not create another lifetime");
        s.startFrame();s.startFrame();
        check(ApocBRServerTelemetryLite.counts.get("movingObjects.classified")==classified,"stable frames perform zero type classifications");
        var pooled=object(16);s.startFrame();
        c.objects.remove(pooled);c.objects.add(pooled);s.update();
        check(pooled.updates==0,"re-addition cannot resurrect the old update slot");
        s.postupdate();check(pooled.posts==0,"old lifetime cannot postupdate after re-addition");
        s.startFrame();s.update();check(pooled.updates==1,"new lifetime schedules normally next frame");
        c.objects.removeIf(o->o instanceof IsoAnimal);
        ServerMovingObjectIndex.updateAnimalSounds(c,0);check(animal.sounds==0,"removeIf updates type index");
        var iterator=c.objects.iterator();while(iterator.hasNext()){if(iterator.next()==zombie){iterator.remove();break;}}
        check(!ServerMovingObjectIndex.getPerceptionTargets(c).contains(zombie),"iterator.remove updates perception index");
        c.objects.addAll(List.of(animal,zombie));c.objects.retainAll(List.of(human));s.startFrame();
        check(ServerMovingObjectIndex.getPerceptionTargets(c).stream().filter(Objects::nonNull).toList().equals(List.of(human)),"retainAll updates all indexes");
        c.objects.add(zombie);c.objects.removeAll(List.of(human,zombie));s.startFrame();
        check(ServerMovingObjectIndex.getPerceptionTargets(c).isEmpty(),"removeAll updates all indexes");
        c.objects.addAll(List.of(animal,zombie));c.objects.clear();s.startFrame();
        check(ServerMovingObjectIndex.forCell(c).getScheduledMembers().isEmpty(),"clear releases scheduled/type entries");
    }
    static void spatialTests(){
        var c=new IsoCell();IsoWorld.instance.cell=c;
        var out=new ArrayList<IsoMovingObject>();
        var z=new IsoZombie();z.x=-16;z.y=-16;c.objects.add(z);
        var edge=new IsoZombie();edge.x=-6;edge.y=-16;c.objects.add(edge);
        var distant=new IsoZombie();distant.x=10000;c.objects.add(distant);
        var human=new IsoPlayer();human.x=10000;c.objects.add(human);
        long beforeHumanOnly=ApocBRServerTelemetryLite.counts.getOrDefault("animals.perceptionGridChecked",0L);
        ServerMovingObjectIndex.queryPerceptionTargets(c,0,-16,-16,false,out);
        check(out.equals(List.of(human)),"human-only query works before spatial initialization");
        check(ApocBRServerTelemetryLite.counts.getOrDefault("animals.perceptionGridChecked",0L)==beforeHumanOnly,"human-only query performs no zombie reconciliation");
        ServerMovingObjectIndex.queryPerceptionTargets(c,1,-16,-16,true,out);
        check(out.equals(List.of(z,edge,human)),"negative cells, inclusive radius, global humans, stable order");
        long checked=ApocBRServerTelemetryLite.counts.get("animals.perceptionGridChecked");
        ServerMovingObjectIndex.queryPerceptionTargets(c,1,-16,-16,true,out);
        check(ApocBRServerTelemetryLite.counts.get("animals.perceptionGridChecked")==checked,"one movement traversal per frame");
        z.x=500;edge.x=-5.999f;
        ServerMovingObjectIndex.queryPerceptionTargets(c,2,-16,-16,true,out);
        check(out.equals(List.of(human)),"bucket relocation and exact radius rejection");
        distant.x=-16;distant.y=-16;
        ServerMovingObjectIndex.queryPerceptionTargets(c,3,-16,-16,true,out);
        check(out.equals(List.of(distant,human)),"distant zombie enters query after movement");
        c.objects.remove(distant);
        ServerMovingObjectIndex.queryPerceptionTargets(c,3,-16,-16,true,out);
        check(out.equals(List.of(human)),"same-frame removal releases spatial slot");
        c.objects.add(distant);
        ServerMovingObjectIndex.queryPerceptionTargets(c,3,-16,-16,true,out);
        check(out.equals(List.of(human,distant)),"same-instance re-entry gets new lifetime order");
        ServerMovingObjectIndex.queryPerceptionTargets(c,3,-16,-16,false,out);
        check(out.equals(List.of(human)),"fleeZombies=false keeps human perception");
        c.objects.clear();
        ServerMovingObjectIndex.queryPerceptionTargets(c,4,0,0,true,out);
        check(out.isEmpty(),"clear releases all spatial entries");
        var random=new Random(713);
        var zombies=new ArrayList<IsoZombie>();
        for(int i=0;i<400;i++){var o=new IsoZombie();o.x=random.nextFloat()*200-100;o.y=random.nextFloat()*200-100;zombies.add(o);c.objects.add(o);}
        for(int frame=5;frame<45;frame++){
            for(var o:zombies){o.x+=random.nextFloat()*40-20;o.y+=random.nextFloat()*40-20;}
            for(int q=0;q<10;q++){
                float x=random.nextFloat()*200-100,y=random.nextFloat()*200-100;
                ServerMovingObjectIndex.queryPerceptionTargets(c,frame,x,y,true,out);
                var expected=new ArrayList<IsoMovingObject>();
                for(var o:zombies){float dx=o.x-x,dy=o.y-y;if(dx*dx+dy*dy<=100)expected.add(o);}
                check(out.equals(expected),"spatial query matches exhaustive reference after movement");
            }
        }
        c.objects.clear();
        for(int i=0;i<30000;i++){var o=new IsoZombie();o.x=i*32;c.objects.add(o);}
        long before=ApocBRServerTelemetryLite.counts.get("animals.perceptionGridChecked");
        long visits=ApocBRServerTelemetryLite.counts.get("animals.perceptionBucketCandidatesVisited");
        for(int i=0;i<100;i++)ServerMovingObjectIndex.queryPerceptionTargets(c,100,i*32,0,true,out);
        check(ApocBRServerTelemetryLite.counts.get("animals.perceptionGridChecked")-before==30000,"30k zombies refreshed once for 100 animal queries");
        check(ApocBRServerTelemetryLite.counts.get("animals.perceptionBucketCandidatesVisited")-visits==100,"sparse queries visit local buckets instead of 3 million targets");
    }
    static void removalScaleTest(){
        var b=new MovingObjectUpdateSchedulerUpdateBucket(UpdateSchedulerSimulationLevel.SIXTEENTH);
        var objects=new ArrayList<IsoMovingObject>();for(int i=0;i<30000;i++){var o=object(i);objects.add(o);b.add(o);}
        IsoMovingObject.equalsCalls=0;long started=System.nanoTime();
        for(int i=0;i<1000;i++)b.removeObject(objects.get(i*29));
        long elapsed=System.nanoTime()-started;
        check(IsoMovingObject.equalsCalls==0,"indexed server removal performs no linear equality searches");
        b.compact();int remaining=0;for(int phase=0;phase<16;phase++)remaining+=b.getBucket(phase).size();
        check(remaining==29000,"large removal batch retains exactly the survivors");
        System.out.printf("30,000 objects / 1,000 indexed removals: %.3f ms (fixture, not server benchmark)%n",elapsed/1e6);
    }
    public static void main(String[] args) throws Exception {
        bucketTests();schedulerTests();indexTests();threadedFrameTest();lifecycleTests();spatialTests();removalScaleTest();
        System.out.println("Passed "+assertions+" lifecycle and algorithm assertions.");
    }
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-moving-object-tests-") as directory:
        work = Path(directory)
        sources = []
        for relative, content in STUBS.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        harness = work / "zombie/MovingObjectAlgorithmsTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        sources.append(harness)
        src = ROOT / "42.21.0/src/zombie"
        sources.extend(src / name for name in [
            "MovingObjectUpdateScheduler.java",
            "MovingObjectUpdateSchedulerUpdateBucket.java",
            "ServerMovingObjectIndex.java",
            "ServerMovingObjectSet.java",
            "ServerAnimalPerceptionGrid.java",
            "vehicles/ServerVehicleUpdateTelemetry.java",
        ])
        sources.append(ROOT / "42.21.0/decompiled/zombie/UpdateSchedulerSimulationLevel.java")
        output = work / "classes"
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in sources), encoding="utf-8")
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.MovingObjectAlgorithmsTest"], check=True)


if __name__ == "__main__":
    main()
