"""Exact-version async authority split: production planner/bridge/module/navigation/worker.

Factory entry/worker binding/guard and native equip are extracted from production;
world, Lua locks and native equipment are explicit doubles. No game deployment.
"""
from pathlib import Path
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True
from test_npc_movement_replication import STUBS, block

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "42.22.1/src/zombie/apocalipse/humans"

HARNESS = r'''package zombie.apocalipse.humans;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import zombie.apocalipse.humans.brain.*;import zombie.apocalipse.humans.brain.HumanSenses.*;
import zombie.apocalipse.humans.movement.*;import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.humans.planning.NpcDecisionPlanner;
import zombie.apocalipse.humans.walking.*;
public class AsyncAuthorityTest {
static int checks;static long now=1_000_000_000L;
static final HumanEngagement.Settings SETTINGS=HumanEngagement.Settings.DEFAULT;
static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
static void waitFor(java.util.function.BooleanSupplier condition)throws Exception{
long deadline=System.nanoTime()+5_000_000_000L;while(!condition.getAsBoolean()){
if(System.nanoTime()>deadline)throw new AssertionError("watchdog: async progress");Thread.sleep(1);}}
static void consume(NpcDecisionPlanner planner)throws Exception{var t=new Thread(()->planner.tick(now));t.start();t.join(5000);check(!t.isAlive(),"bounded detached job terminates");}
static Contact contact(short id,float distance){return new Contact(new Identity(Kind.ZOMBIE,id,id,id),distance,0,0,distance*distance,now,now,true);}
static HumanObservation observe(Contact... contacts){return observeAt(now,contacts);}
static HumanObservation observeAt(long at,Contact... contacts){var frame=new Frame(true,at,List.of(contacts),Coverage.COMPLETE,Coverage.COMPLETE,false,new Work(0,0,0,0,0));
return new HumanObservation(at,List.of(),true,zombie.apocalipse.humans.state.HumanState.INITIAL,frame,
new HumanTerritory(true,true,new HashSet<>(List.of(contacts).stream().map(Contact::identity).toList())));}
static NpcDecisionPlanner.Grid grid(){return new NpcDecisionPlanner.Grid(Set.of(new Tile(0,0),new Tile(1,0)),
List.of(new NpcDecisionPlanner.Edge(new Tile(0,0),new Tile(1,0),1)));}
static void valuesAndCapacity()throws Exception{
var planner=new NpcDecisionPlanner(true);planner.bindOwner();var slots=new ArrayList<NpcDecisionPlanner.Slot>();
for(int i=0;i<128;i++)slots.add(planner.open());
try{planner.open();throw new AssertionError("capacity escaped");}catch(IllegalStateException expected){checks++;}
long[] tickets=new long[128];for(int i=0;i<128;i++)tickets[i]=slots.get(i).submit(new NpcDecisionPlanner.Select(observe(contact((short)1,1)),null,SETTINGS),now);
consume(planner);int ready=0;for(int i=0;i<128;i++)if(slots.get(i).take(tickets[i])!=null)ready++;
check(ready==64,"worker respects global per-tick job budget");consume(planner);
for(int i=0;i<128;i++)if(slots.get(i).take(tickets[i])!=null)ready++;
check(ready==128,"round robin eventually services all reserved result slots");
var slot=slots.getFirst();long old=slot.submit(new NpcDecisionPlanner.Route(grid(),new Tile(0,0),new Tile(1,0),false),now);
slot.cancel();consume(planner);check(slot.take(old)==null,"cancel fences old request/result");
old=slot.submit(new NpcDecisionPlanner.Select(observe(contact((short)2,2)),null,SETTINGS),now);
long latest=slot.submit(new NpcDecisionPlanner.Select(observe(contact((short)3,1)),null,SETTINGS),now);
consume(planner);check(slot.take(old)==null,"coalesced ticket cannot claim newer result");
var choice=(NpcDecisionPlanner.Selected)slot.take(latest).answer();check(choice.selection().target().identity().onlineId()==3,"latest replaceable decision wins");
check(slot.take(latest)==null,"consumed receipt cannot be applied twice");
slot.close();var replacement=planner.open();long id=replacement.submit(new NpcDecisionPlanner.Select(observe(contact((short)4,1)),null,SETTINGS),now);
check(slot.take(latest)==null,"old incarnation slot cannot resolve replacement result");consume(planner);check(replacement.take(id)!=null,"replacement incarnation progresses");
var denied=new AtomicReference<Throwable>();var foreign=new Thread(()->{try{replacement.cancel();}catch(Throwable failure){denied.set(failure);}});
foreign.start();foreign.join();check(denied.get() instanceof IllegalStateException,"foreign producer cannot mutate server-owned slots");
var mutable=new HashSet<Tile>(Set.of(new Tile(0,0),new Tile(1,0)));var edges=new ArrayList<NpcDecisionPlanner.Edge>();edges.add(new NpcDecisionPlanner.Edge(new Tile(0,0),new Tile(1,0),1));
var detached=new NpcDecisionPlanner.Grid(mutable,edges);mutable.clear();edges.clear();
check(detached.walkable(new Tile(0,0))&&detached.traversalCost(new Tile(0,0),new Tile(1,0))==1,"nested navigation values detached");
check(detached.traversalCost(new Tile(1,0),new Tile(0,0))==0,"directed/unknown edges fail closed");
planner.fail(new IllegalStateException("fixture"));check(replacement.submit(new NpcDecisionPlanner.Select(observe(),null,SETTINGS),now)==-1,"failure stops admissions");
check(planner.failure()!=null,"failure has explicit publication");for(var s:slots)s.close();replacement.close();
}
static void bridgeAndExecution()throws Exception{
for(boolean async:new boolean[]{false,true}){
var planner=new NpcDecisionPlanner(async);planner.bindOwner();planner.beginServerTick();var bridge=new ServerHumanPlanning(planner);
var target=contact((short)5,1);var selection=bridge.preview(observe(target),null,SETTINGS);
if(async){check(selection.target()==null,"async admission is not optimistic selection success");consume(planner);selection=bridge.preview(observe(target),null,SETTINGS);}
check(selection.target()!=null,"guard selection supplied through actual bridge");
var partial=new Frame(true,now,List.of(),Coverage.COMPLETE,Coverage.PARTIAL,false,new Work(0,0,0,0,0));
var partialObservation=new HumanObservation(now,List.of(),true,zombie.apocalipse.humans.state.HumanState.INITIAL,partial,new HumanTerritory(true,true,Set.of(target.identity())));
check(bridge.preview(partialObservation,target,SETTINGS).choice()==HumanEngagement.Choice.WATCH,"partial capture preserves bounded held-target grace without attacking");
check(bridge.preview(observe(),null,SETTINGS).target()==null,"current target disappearance vetoes stale selection");
bridge.cancel();check(bridge.preview(observe(),null,SETTINGS).target()==null,"preemption clears cached decision");
bridge.cancel();var module=new EngageZombiesModule(SETTINGS);module.bindPlanning(bridge);
var actuator=new FixtureActuator();for(int i=0;i<6;i++){
var observation=observeAt(now+i*100_000_000L,target);if(async)consume(planner);
if(module.eligible(observation))module.update(observation,actuator);
}
check(actuator.begins==1&&actuator.updates>1,"async result does not cause engagement preemption/restart churn");
module.suspend(now,actuator);check(actuator.cancels==1,"exclusive control cancels execution immediately on authority");
check(!module.eligible(observe(target)),"suspended module cannot revive stale plan");module.stop(now,actuator);bridge.close();
var human=new ServerHuman();human.planning=new ServerHumanPlanning(planner);
var destination=new MovementRequest.To(new WalkingPatternSpec.Point(3,0,0));
check(human.walking.start(destination,now),"navigation request accepted as pending execution");
planner.beginServerTick();check(human.walking.update(now)==HumanActuator.MotionStatus.RUNNING,"pending plan is RUNNING");
if(async)consume(planner);check(human.walking.update(now+100_000_000L)==HumanActuator.MotionStatus.RUNNING,"route starts after acknowledgement");
human.walking.update(now+200_000_000L);check(human.player.x>.5f,"physical movement executes on authority after plan");
human.walking.stop();check(!human.movement.moving(),"cancel stops actual movement without waiting for worker");
check(human.walking.start(destination,now),"replacement route admitted");planner.beginServerTick();human.walking.update(now);
human.walking.stop();if(async)consume(planner);
check(!human.movement.moving(),"cancelled result cannot restart movement");
human.walking.start(destination,now);check(human.walking.update(now+2_000_000_000L)==HumanActuator.MotionStatus.FAILED,"expired execution fails before native route start");
planner.beginServerTick();for(int i=0;i<4;i++)check(planner.admitCapture(),"bounded capture admission");
check(!planner.admitCapture(),"global capture budget exhausted without blocking");human.planning.close();
var returning=new ServerHuman();returning.planning=new ServerHumanPlanning(planner);
var point=new WalkingPatternSpec.Point(3,1,0);var spec=new WalkingPatternSpec("background","waypoint-circuit",16,List.of(point),new WalkingPatternSpec.Area(3,0,6,3));
returning.profile.patterns=List.of(spec);var pattern=ServerHumanProfiles.walkingPatterns().create(spec);
var patternField=ServerHumanWalking.class.getDeclaredField("patterns");patternField.setAccessible(true);
((Map<String,WalkingPattern>)patternField.get(returning.walking)).put("background",pattern);
check(!returning.walking.start(new MovementRequest.To(point),now),"ordinary objective cannot bypass assigned area from an outside start");
check(!returning.walking.start(new MovementRequest.To(new WalkingPatternSpec.Point(0,0,0),"background",true,true,List.of()),now),"re-entry still rejects destinations outside assigned area");
check(returning.walking.start(new MovementRequest.To(point,"background",true,true,List.of(new WalkingPatternSpec.Point(4,1,0))),now),"explicit shared re-entry accepts outside origin and detached companions");
planner.beginServerTick();returning.walking.update(now);if(async)consume(planner);returning.walking.update(now+100_000_000L);
for(int i=2;i<=35;i++)returning.walking.update(now+i*100_000_000L);
check(returning.player.getXi()==3&&returning.player.getYi()==1,"real walking/movement follows acknowledged re-entry route into the area");
var cursor=pattern.getClass().getDeclaredField("cursor");cursor.setAccessible(true);
check(cursor.getInt(pattern)==0,"shared re-entry does not advance an actor-local circuit cursor");
var local=new WalkingPatternSpec.Point(1,1,0);
check(returning.walking.start(new MovementRequest.Nearby(local,local,4),now),"local care request is allowed outside authored patrol area");
planner.beginServerTick();returning.walking.update(now);if(async)consume(planner);returning.walking.update(now+100_000_000L);
for(int i=2;i<=35;i++)returning.walking.update(now+i*100_000_000L);
check(returning.player.getXi()==1&&returning.player.getYi()==1,"server movement executes local recovery path through detached planning");
check(cursor.getInt(pattern)==0,"local recovery does not consume personal or shared patrol progression");
var vicinity=new VicinityGrid(returning.movement,new Tile(1,1),new Tile(2,2),4);
check(!vicinity.walkable(new Tile(20,20))&&vicinity.walkable(new Tile(2,2)),"local navigation cannot leave its bounded vicinity");
check(!new VicinityGrid(returning.movement,new Tile(1,1),new Tile(100,100),4).available(),"distant anchors cannot turn local movement into an unbounded world search");
returning.planning.close();planner.stop();
var capturePlanner=new NpcDecisionPlanner(async);capturePlanner.bindOwner();capturePlanner.beginServerTick();
var captured=new ServerHumanPlanning(capturePlanner);Thread authority=Thread.currentThread();int[] calls={0,0};
MovementGrid live=new MovementGrid(){public boolean walkable(Tile tile){check(Thread.currentThread()==authority,"topology lookup stays on authority");calls[0]++;return tile.x()>=0&&tile.x()<5&&tile.y()==0;}
public boolean connected(Tile from,Tile to){return traversalCost(from,to)>0;}
public int traversalCost(Tile from,Tile to){check(Thread.currentThread()==authority,"edge lookup stays on authority");calls[1]++;return 1;}};
check(captured.route(live,new Tile(0,0),new Tile(3,0),false,now),"detached topology admitted");
int nativeCalls=calls[0]+calls[1];if(async)consume(capturePlanner);var route=captured.routeResult(now);
check(route!=null&&route.getLast().equals(new Tile(3,0)),"worker navigates copied graph");
check(nativeCalls==calls[0]+calls[1]&&calls[0]<=256&&calls[1]<=1024,"worker performs no hidden live reads; capture budget is bounded");
capturePlanner.beginServerTick();captured.route(live,new Tile(0,0),new Tile(3,0),false,now);if(async)consume(capturePlanner);
check(captured.routeResult(now+2_000_000_001L).isEmpty(),"stale topology result cannot authorize a route");captured.close();capturePlanner.stop();
}}
static void locksAndFactory()throws Exception{
ServerHumanFactory.update();var factory=ServerHumanFactory.INSTANCE;
check(factory.worker!=null==ServerHumanFactory.ASYNC,"startup mode selects planner worker only");
if(ServerHumanFactory.ASYNC){
var slot=factory.planner.open();long id=slot.submit(new NpcDecisionPlanner.Select(observe(contact((short)6,1)),null,SETTINGS),now);
long ticks=factory.worker.ticks();int world=factory.authorityTicks;
// Force the lock ordering seen in the dump. The real planner worker must progress
// while the test authority holds both monitors; no worker native/world result is awaited in production.
synchronized(FixtureLua.EVENT_MAP){synchronized(factory){long before=ticks;waitFor(()->factory.worker.ticks()>before);
check(slot.take(id)!=null,"planner progresses while authority holds Lua and factory monitors");
check(factory.authorityTicks==world,"worker never advances native factory tick");
factory.assertOwner();var actor=new ServerHuman();var inventory=new NativeEquipFixture(actor);
check(inventory.equip(1),"actual production equip body succeeds on authority");
check(FixtureLua.events==1,"equip event delivered exactly once on Lua owner");
var problem=new AtomicReference<Throwable>();var foreign=new Thread(()->{try{inventory.equip(1);}catch(Throwable failure){problem.set(failure);}});
foreign.start();foreign.join(1000);check(!foreign.isAlive()&&problem.get() instanceof IllegalStateException,"off-owner equip fails before waiting for Lua lock");
check(FixtureLua.events==1,"rejected off-owner effect emits no event");}}
id=slot.submit(new NpcDecisionPlanner.Select(observe(),null,SETTINGS),now);ticks=factory.worker.ticks();
synchronized(FixtureLua.QUEUE){synchronized(factory){long before=ticks;waitFor(()->factory.worker.ticks()>before);
check(slot.take(id)!=null,"queued-event monitor cannot block detached worker");}}
var rejected=new AtomicReference<Throwable>();var t=new Thread(()->{try{factory.assertOwner();}catch(Throwable failure){rejected.set(failure);}});
t.start();t.join();check(rejected.get() instanceof IllegalStateException,"factory no longer authorizes worker/foreign thread");
var state=new zombie.apocalipse.humans.state.HumanStateStore(zombie.apocalipse.humans.state.HumanState.INITIAL,factory::assertOwner);
rejected.set(null);t=new Thread(()->{try{state.dispatch(new zombie.apocalipse.humans.state.HumanMutation.Kill());}catch(Throwable failure){rejected.set(failure);}});
t.start();t.join();check(rejected.get() instanceof IllegalStateException&&!state.snapshot().dead(),"off-owner health reduction rejected without mutation");
slot.close();factory.planner.stop();factory.worker.shutdown();factory.worker.shutdown();
long stopped=factory.worker.ticks();check(stopped>0&&!factory.worker.failed(),"worker stops independently without Lua/native dependency");
}
var failurePlanner=new NpcDecisionPlanner(true);failurePlanner.bindOwner();
var failureWorker=new ServerHumanSimulationWorker(1_000_000L,at->{throw new IllegalStateException("controlled failure");},failurePlanner::fail);
synchronized(factory){synchronized(FixtureLua.EVENT_MAP){failureWorker.start();waitFor(()->failurePlanner.failure()!=null);
check(failureWorker.failed(),"failure publishes without entering factory/Lua monitors");}}
failurePlanner.stop();failureWorker.shutdown();
}
public static void main(String[] args)throws Exception{valuesAndCapacity();bridgeAndExecution();locksAndFactory();System.out.println("Async authority: "+checks+" checks passed; async="+ServerHumanFactory.ASYNC);}
}
class FixtureActuator implements HumanActuator {
int begins,updates,cancels;public HumanCombat.Port combat(){return (request,now)->{begins++;return new HumanCombat.Execution(){public HumanCombat.Result update(long at){updates++;return new HumanCombat.Result(HumanCombat.Status.RUNNING,HumanCombat.Reason.NONE);}public void cancel(HumanCombat.Reason reason){cancels++;}};};}
}
class FixtureLua {
static final Object EVENT_MAP=new Object(),QUEUE=new Object();static int events;
static void equip(){synchronized(EVENT_MAP){ServerHumanFactory.INSTANCE.assertOwner();events++;}}
}
'''


def main():
    files = dict(STUBS)
    files["zombie/apocalipse/humans/ServerHumanTransport.java"] = """package zombie.apocalipse.humans;
class ServerHumanTransport {static void idle(ServerHuman h,zombie.core.raknet.UdpConnection c){}static void movement(ServerHuman h){}
static void aim(ServerHuman h,boolean v){}static void equipment(ServerHuman h){h.requireAuthority();}}"""
    files["zombie/apocalipse/humans/brain/HumanActuator.java"] = """package zombie.apocalipse.humans.brain;
public interface HumanActuator {enum MotionStatus {RUNNING,SUCCEEDED,FAILED}
default HumanCombat.Port combat(){return HumanCombat.UNSUPPORTED;}default void stopMoving(){}default void tactical(TacticalIntent intent){}}"""
    files["zombie/apocalipse/humans/brain/HumanCombatTactics.java"] = "package zombie.apocalipse.humans.brain;public class HumanCombatTactics {public static final double IMMEDIATE_RANGE=1.5;}"
    files["zombie/apocalipse/humans/groups/SquadAssistPort.java"] = "package zombie.apocalipse.humans.groups;public interface SquadAssistPort {}"
    files["zombie/apocalipse/humans/groups/SquadAssistModule.java"] = """package zombie.apocalipse.humans.groups;import zombie.apocalipse.humans.brain.*;
public class SquadAssistModule {public SquadAssistModule(SquadAssistPort p){}public boolean eligible(HumanObservation o){return false;}public void update(HumanObservation o,HumanActuator a){}public void cancel(HumanActuator a){}}"""
    files["zombie/apocalipse/humans/profiles/HumanProfile.java"] = "package zombie.apocalipse.humans.profiles;public class HumanProfile {public static void identifier(String id){}}"
    files["zombie/apocalipse/humans/ServerHumanProfiles.java"] = """package zombie.apocalipse.humans;import zombie.apocalipse.humans.walking.*;
class ServerHumanProfiles {static WalkingPatternRegistry walkingPatterns(){return WalkingPatternRegistry.builtins();}}"""
    human = files["zombie/apocalipse/humans/ServerHuman.java"]
    human = human.replace("class ServerHuman {", """class ServerHuman {
final Thread authority=Thread.currentThread();ServerHumanPlanning planning;
boolean motionAllowed(){return true;}
final ServerHumanWalking walking=new ServerHumanWalking(this);
final Profile profile=new Profile();static class Profile {java.util.List<zombie.apocalipse.humans.walking.WalkingPatternSpec> patterns=java.util.List.of();java.util.List<zombie.apocalipse.humans.walking.WalkingPatternSpec> walkingPatterns(){return patterns;}}
""")
    # Field initialization order must match construction dependencies.
    human = human.replace("final ServerHumanWalking walking=new ServerHumanWalking(this);", "ServerHumanWalking walking;")
    human = human.replace("boolean isRelevant", "ServerHuman(){walking=new ServerHumanWalking(this);}\n" + block((SRC/"ServerHuman.java").read_text(encoding="utf-8"), "void requireAuthority()") + "\nboolean isRelevant")
    human = human.replace('record Definition(){', 'record Definition(){float x(){return .5f;}float y(){return .5f;}')
    files["zombie/apocalipse/humans/ServerHuman.java"] = human
    files["zombie/characters/IsoPlayer.java"] = files["zombie/characters/IsoPlayer.java"].replace("public class IsoPlayer", "public class IsoPlayer").replace(
        'public String getActionStateName()', 'public short getOnlineID(){return 768;}public String getActionStateName()')
    files["zombie/iso/IsoGridSquare.java"] = files["zombie/iso/IsoGridSquare.java"].replace(
        'public List<IsoMovingObject> getMovingObjects()', 'public Object getBuildingDef(){return null;}public List<IsoMovingObject> getMovingObjects()')
    factory = (SRC / "ServerHumanFactory.java").read_text(encoding="utf-8")
    assert "planner::tick, planner::fail" in factory and "this::tick" not in factory
    assert "INSTANCE.tick(System.nanoTime());" in block(factory, "public static void update()")
    assert "worker.isWorkerThread()" not in block(factory, "private void requireOwner()")
    assert "requireOwner();" in block(factory, "synchronized void tick(long now)")
    assert "if(planner.failure()!=null){planningFailed" in factory
    assert "INSTANCE.planner.stop();" in block(factory, "public static void shutdown()")
    shutdown = block(factory, "public static void shutdown()")
    assert shutdown.index("running.shutdown()") < shutdown.index("synchronized(INSTANCE)")
    assert shutdown.index("Thread.currentThread()!=INSTANCE.owner") < shutdown.index("human.stopActivities()")
    server = (SRC.parents[1] / "network/GameServer.java").read_text(encoding="utf-8")
    assert "ServerHumanFactory.shutdown();\n            System.exit(0);" in server
    files["zombie/apocalipse/humans/ServerHumanFactory.java"] = """package zombie.apocalipse.humans;
import zombie.network.GameServer;import zombie.iso.IsoWorld;import zombie.debug.DebugLog;
class ServerHumanFactory {
static final boolean ASYNC=Boolean.parseBoolean(System.getProperty("apocbr.npc.async","true"));
static final long BRAIN_UPDATE_NANOS=100_000_000L;static final ServerHumanFactory INSTANCE=new ServerHumanFactory();
volatile boolean stopping;Thread owner=Thread.currentThread();ServerHumanSimulationWorker worker;
final zombie.apocalipse.humans.planning.NpcDecisionPlanner planner=new zombie.apocalipse.humans.planning.NpcDecisionPlanner(ASYNC);
boolean configured=true,configurationRejected;Object zombieVictims=new Object();int authorityTicks;
ServerHumanFactory(){planner.bindOwner();}void assertOwner(){requireOwner();}void tick(long now){requireOwner();authorityTicks++;}void serverMedicalTick(long now){}
""" + "\n".join(block(factory, s) for s in ("public static void update()", "private synchronized void startWorker()", "private void requireOwner()")) + "}"
    files["zombie/network/GameServer.java"] = "package zombie.network;public class GameServer {public static boolean server=true;public static Object udpEngine=new Object();}"
    files["zombie/iso/IsoWorld.java"] = "package zombie.iso;public class IsoWorld {public static final IsoWorld instance=new IsoWorld();public Object currentCell=new Object();}"
    files["zombie/debug/DebugLog.java"] = "package zombie.debug;public class DebugLog {public static void log(String message){}}"
    files["zombie/apocalipse/humans/ServerHumanDialogue.java"] = "package zombie.apocalipse.humans;class ServerHumanDialogue {static void install(){}}"
    files["zombie/apocalipse/zombies/ServerZombieController.java"] = "package zombie.apocalipse.zombies;public class ServerZombieController {public static ServerZombieController getInstance(){return new ServerZombieController();}public void install(Object port){}}"
    equip = block((SRC / "ServerHumanInventory.java").read_text(encoding="utf-8"), "public boolean equip(int id)")
    files["zombie/apocalipse/humans/NativeEquipFixture.java"] = """package zombie.apocalipse.humans;
class NativeEquipFixture {final NativeHuman human;final Weapon weapon=new Weapon();NativeEquipFixture(ServerHuman h){human=new NativeHuman(h);}
static class Weapon {int getCondition(){return 100;}boolean isTwoHandWeapon(){return false;}}
Weapon item(int id){return weapon;}
static class NativeHuman extends ServerHuman {final ServerHuman actor;final NativePlayer player=new NativePlayer();NativeHuman(ServerHuman h){actor=h;}void requireAuthority(){actor.requireAuthority();}}
static class NativePlayer {Object primary;Object getPrimaryHandItem(){return primary;}void setPrimaryHandItem(Object w){FixtureLua.equip();primary=w;}void setSecondaryHandItem(Object w){}void setUseHandWeapon(Object w){}}
""" + equip + "}"
    files["zombie/apocalipse/humans/AsyncAuthorityTest.java"] = HARNESS
    production = [SRC / name for name in ("planning/NpcDecisionPlanner.java", "ServerHumanPlanning.java", "ServerHumanWalking.java", "ServerHumanMovement.java", "ServerHumanSimulationWorker.java",
        "state/HumanState.java", "state/HumanStateStore.java", "state/HumanMutation.java", "state/HumanStateReducer.java", "brain/HumanObservation.java", "brain/HumanSenses.java", "brain/HumanTerritory.java", "brain/HumanEngagement.java", "brain/HumanCombat.java",
        "brain/HumanBehavior.java", "brain/HumanModule.java", "brain/EngageZombiesModule.java", "brain/TacticalIntent.java", "traversal/TraversalEdge.java")]
    production += list((SRC / "movement").glob("*.java")) + list((SRC / "walking").glob("*.java"))
    production += [p for p in (SRC / "furniture/FurnitureAffordances.java", SRC / "furniture/FurnitureDemand.java") if p.exists()]
    with tempfile.TemporaryDirectory(prefix="npc_async_authority_") as folder:
        tmp = Path(folder)
        sources = []
        for relative, content in files.items():
            path = tmp / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        args = tmp / "sources.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in sources + production), encoding="utf-8")
        output = tmp / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        for options in ([], ["-Dapocbr.npc.async=false"], ["-Dapocbr.npc.async=true"]):
            subprocess.run([str(ROOT / "jdk/bin/java.exe"), *options, "-cp", str(output), "zombie.apocalipse.humans.AsyncAuthorityTest"], check=True, timeout=30)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="42.22.1", help="Exact repository target (default: 42.22.1)")
    SRC = ROOT / parser.parse_args().version / "src/zombie/apocalipse/humans"
    main()
