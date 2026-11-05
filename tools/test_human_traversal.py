"""Production traversal, native geometry adapter, route choice and State-packet contracts.

Uses the shared human fixture; no native initialization or deployment.
"""
from pathlib import Path
import subprocess
import sys
import tempfile
sys.dont_write_bytecode = True
from test_server_humans import FILES, ROOT

HARNESS = r"""
package zombie.apocalipse.humans;
import java.util.*;
import zombie.apocalipse.humans.traversal.*;
import zombie.apocalipse.humans.movement.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.humans.brain.HumanActuator;
import zombie.iso.*;
import zombie.iso.objects.*;
import zombie.pathfind.PolygonalMap2;
import zombie.network.packets.actions.StatePacket;
import zombie.core.raknet.UdpConnection;
public class TraversalTest {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static final Tile A=new Tile(100,200), B=new Tile(101,200), C=new Tile(101,201);
 static final long SECOND=1_000_000_000L;
 static TraversalEdge edge(Tile a,Tile b,int z,Object object){return new TraversalEdge(a,b,z,TraversalEdge.Kind.FENCE,object);}
 static final class Port implements TraversalExecution.Port {
  TraversalEdge edge;TraversalExecution.Landing landing=TraversalExecution.Landing.CLEAR;
  boolean prepare=true,throwBegin,endedLanded;int starts,lands,ends;
  Port(TraversalEdge e){edge=e;}
  public TraversalEdge inspect(TraversalEdge e){return edge;}
  public TraversalExecution.Landing landing(TraversalEdge e){return landing;}
  public boolean prepare(TraversalEdge e){return prepare;}
  public void begin(TraversalEdge e){starts++;if(throwBegin)throw new IllegalStateException("begin failure");}
  public void land(TraversalEdge e){lands++;}
  public void end(TraversalEdge e,boolean landed){ends++;endedLanded=landed;}
 }
 static void leases(){
  var leases=new CrossingReservations();var object=new Object();var e=edge(A,B,0,object);
  var first=leases.acquire("one",e,0);check(first!=null,"first lease acquired");
  check(leases.acquire("two",edge(B,A,0,object),0)==null,"opposite directions share crossing");
  check(leases.acquire("two",edge(C,B,0,object),0)==null,"different crossings cannot share landing");
  var upstairs=leases.acquire("up",edge(A,B,1,object),0);check(upstairs!=null,"floors have independent leases");
  var replacement=leases.acquire("new",e,10*SECOND);check(replacement!=null,"expired lease recovers");
  leases.release(first);check(leases.owns(replacement,10*SECOND),"stale cleanup cannot release replacement");
  leases.release(replacement);check(leases.acquire("two",e,10*SECOND)!=null,"explicit release works");
 }
 static void lifecycle(){
  var leases=new CrossingReservations();var e=edge(A,B,0,new Object());var p=new Port(e);
  var run=new TraversalExecution(p,leases,p);run.start(e,0);
  check(run.update(0)==TraversalExecution.Result.RUNNING&&p.starts==1,"starts native presentation once");
  run.update(SECOND);run.update(SECOND/2);check(p.starts==1&&p.lands==0,"clock reversal cannot advance or restart crossing");
  run.update(1_200_000_000L);check(p.lands==1&&run.committed(),"commit only after crossing phase");
  run.cancel();check(p.ends==1&&p.endedLanded,"cancel after commitment retains landing");
  run.cancel();check(p.ends==1,"cancellation idempotent");
  run.start(e,3*SECOND);run.update(3*SECOND);run.cancel();check(!p.endedLanded,"cancel before commitment retains source");
  run.start(e,4*SECOND);run.update(4*SECOND);
  check(run.update(6*SECOND)==TraversalExecution.Result.SUCCEEDED&&p.lands==2,"successful crossing commits exactly once");
  check(!run.active()&&p.ends==3,"completion clears ownership");
  run.start(e,7*SECOND);p.landing=TraversalExecution.Landing.OCCUPIED;
  check(run.update(7*SECOND)==TraversalExecution.Result.RUNNING,"occupied landing initially waits");
  check(run.update(9*SECOND)==TraversalExecution.Result.FAILED&&run.reason().equals("landing_occupied"),"occupied wait bounded");
  p.landing=TraversalExecution.Landing.CLEAR;run.start(e,10*SECOND);run.update(10*SECOND);
  p.landing=TraversalExecution.Landing.BLOCKED;
  check(run.update(11*SECOND)==TraversalExecution.Result.FAILED&&p.lands==2,"landing changed before commit cannot be crossed");
  p.landing=TraversalExecution.Landing.CLEAR;p.edge=edge(A,B,0,new Object());run.start(e,12*SECOND);
  check(run.update(12*SECOND)==TraversalExecution.Result.FAILED,"replacement obstacle requires revalidation");
  p.edge=e;p.prepare=false;run.start(e,13*SECOND);
  check(run.update(13*SECOND)==TraversalExecution.Result.FAILED,"prepare rejection releases lease");
  p.prepare=true;p.throwBegin=true;run.start(e,14*SECOND);
  try{run.update(14*SECOND);throw new AssertionError("exception expected");}catch(IllegalStateException expected){}
  check(!run.active()&&leases.acquire("other",e,14*SECOND)!=null,"exception cleans crossing lease");
  var waitLeases=new CrossingReservations();var holder=waitLeases.acquire("other",e,0);
  p.throwBegin=false;run=new TraversalExecution(p,waitLeases,p);run.start(e,0);
  check(run.update(0)==TraversalExecution.Result.RUNNING,"contended crossing waits");
  check(run.update(2*SECOND)==TraversalExecution.Result.FAILED&&run.reason().equals("crossing_busy"),"contention has bounded wait");
  waitLeases.release(holder);run.start(e,3*SECOND);run.update(3*SECOND);
  check(run.update(10*SECOND)==TraversalExecution.Result.FAILED&&run.reason().equals("timeout"),"watchdog prevents completing after a long simulation stall");
 }
 static IsoCell cell(){
  var cell=new IsoCell();cell.strict=true;var building=new BuildingDef();
  for(int x=100;x<=103;x++)for(int y=200;y<=202;y++){
   var square=new IsoGridSquare(x,y,0);square.building=building;cell.tiles.put(x+","+y+",0",square);
  }return cell;
 }
 static ServerHuman human(IsoCell cell,String name,short id,TraversalWorld world){
  var d=new ServerHumanDefinition(name,name,name,100.5f,200.5f,0,IsoDirections.E,false,"Tourist");
  return new ServerHuman(d,id,cell,new zombie.apocalipse.humans.state.HumanStateStore(zombie.apocalipse.humans.state.HumanState.INITIAL),world);
 }
 static void put(IsoCell cell,IsoObject object){
  object.square=cell.getGridSquare(100,200,0);
  cell.getGridSquare(100,200,0).edges.put("101,200",object);
  cell.getGridSquare(101,200,0).edges.put("100,200",object);
  cell.getGridSquare(100,200,0).blockedEdges.add("1,0");cell.getGridSquare(101,200,0).blockedEdges.add("-1,0");
 }
 static void integration(){
  var cell=cell();IsoWorld.instance.currentCell=cell;var world=new TraversalWorld();
  var human=human(cell,"climber",(short)1100,world);var viewer=new UdpConnection();human.viewers.add(viewer);
  var fence=new IsoObject();put(cell,fence);
  check(human.movement.connected(A,B)&&human.movement.traversalCost(A,B)==5,"low fence is a reachable action edge");
  check(!human.movement.connected(A,C),"diagonal traversal refused");
  fence.tall=true;check(!human.movement.connected(A,B),"tall fence refused");fence.tall=false;
  fence.sprite.name="fixtures_railings_01";check(!human.movement.connected(A,B),"railing coverage matches A-life refusal");fence.sprite.name="fence";
  var landing=cell.getGridSquare(101,200,0);
  landing.water=true;check(!human.movement.connected(A,B),"water landing refused");landing.water=false;
  landing.floor=false;check(!human.movement.connected(A,B),"unsupported landing refused");landing.floor=true;
  landing.stairs=true;check(!human.movement.connected(A,B),"stairs landing refused");landing.stairs=false;
  human.movement.start(List.of(B),0);human.movement.update(100_000_000L);
  check(human.movement.traversing()&&human.player.x==100.5f,"crossing begins at source center");
  check(human.movement.snapshot().predictionUnits()==0&&!human.movement.snapshot().moving(),"walking prediction suppressed during climb");
  var packet=StatePacket.sent.getLast();check(packet.stage()==zombie.ai.State.Stage.Enter&&!packet.sub(),"native climb is main-state enter");
  check(packet.eventValues().containsKey("EventClimbFence")&&packet.parameters().get("dir")==IsoDirections.E,"fence event and direction encoded");
  check(packet.parameters().get("solid_floor").equals(true)&&packet.parameters().get("run").equals(false),"safe walking-vault params encoded");
  human.movement.update(1_300_000_000L);check(human.player.x==101.5f,"server commits exact landing independently of animation callbacks");
  int sent=StatePacket.sent.size();human.movement.announceTraversal(new UdpConnection());
  check(StatePacket.sent.size()==sent,"late viewer does not replay committed crossing");
  human.actuator.stopMoving();check(human.postX==101.5f&&!human.movement.traversing(),"cancel after commit preserves greeting recovery point");
  check(StatePacket.sent.getLast().stage()==zombie.ai.State.Stage.Exit,"cancel sends native exit/cancels pending enter");
  human.player.x=100.5f;human.movement.start(List.of(B),2*SECOND);human.movement.update(2*SECOND);
  landing.occupied=true;check(human.movement.update(3*SECOND)==HumanActuator.MotionStatus.FAILED&&human.player.x==100.5f,"new occupant aborts before commitment");landing.occupied=false;
  var door=new IsoDoor();put(cell,door);
  check(human.movement.connected(A,B)&&door.toggles==0&&human.player.getCurrentSquare()==null,"planning door is read-only and restores detached square");
  door.locked=true;check(!human.movement.connected(A,B),"locked door excluded");door.locked=false;
  human.movement.start(List.of(B),4*SECOND);human.movement.update(4*SECOND);
  check(door.open&&door.toggles==1&&!human.movement.traversing(),"door opens once without climbing or false movement");
  check(human.player.getCurrentSquare()==null,"native door execution restores detached square");
  var window=new IsoWindow();put(cell,window);
  check(human.movement.connected(A,B)&&window.toggles==0,"unlocked window can be planned without opening");
  window.locked=true;check(!human.movement.connected(A,B),"locked closed window excluded");window.locked=false;
  window.smashed=true;check(!human.movement.connected(A,B),"remaining broken glass excluded");window.glassRemoved=true;
  check(human.movement.connected(A,B),"glass-cleared window allowed");window.smashed=false;
  human.movement.start(List.of(B),5*SECOND);human.movement.update(5*SECOND);
  packet=StatePacket.sent.getLast();
  check(window.open&&packet.eventValues().containsKey("EventClimbWindow"),"window is opened then native climb event sent");
  var params=(zombie.ai.states.ClimbThroughWindowPositioningParams)packet.parameters().get("params");
  check(params.startX==100&&params.endX==101&&params.climbingCharacter==human.player&&params.windowObject==window,"native positioning serializes source, actor and actual portal");
  window.square=null;window.objectIndex=-1;
  human.movement.stop();check(human.player.x==100.5f,"cancel before commitment stays source");
  params=(zombie.ai.states.ClimbThroughWindowPositioningParams)StatePacket.sent.getLast().parameters().get("params");
  check(params.windowObject==null,"removed portal is encoded safely on native exit");
  var frame=new IsoWindowFrame();put(cell,frame);check(human.movement.connected(A,B),"window frame supported");
  var thump=new IsoThumpable();thump.door=false;thump.window=true;put(cell,thump);
  check(human.movement.connected(A,B),"thumpable window supported");thump.barricaded=true;check(!human.movement.connected(A,B),"barricaded thumpable window refused");
  put(cell,fence);var other=human(cell,"other",(short)1101,world);other.player.x=101.5f;
  human.movement.start(List.of(B),6*SECOND);human.movement.update(6*SECOND);
  check(human.movement.traversing()&&human.player.x==100.5f,"detached human occupancy waits without native square membership");
  other.dispose();human.movement.update(6_100_000_000L);
  check(StatePacket.sent.getLast().eventValues().containsKey("EventClimbFence"),"removal frees detached actor landing");
  human.dispose();check(!human.movement.traversing(),"disposal cancels active traversal");
 }
 static void routes(){
  CombatRoutePlanner.Grid grid=new CombatRoutePlanner.Grid(){
   public boolean walkable(Tile t){return t.x()>=0&&t.x()<=2&&t.y()>=0&&t.y()<=1;}
   public boolean connected(Tile a,Tile b){return Math.abs(a.x()-b.x())+Math.abs(a.y()-b.y())==1;}
   public int traversalCost(Tile a,Tile b){return a.y()==0&&b.y()==0&&Math.min(a.x(),b.x())==0?6:1;}
   public boolean clearAttack(Tile t){return t.equals(new Tile(2,0));}
  };
  var route=CombatRoutePlanner.plan(grid,new Tile(0,0),2.5f,.5f,.1f);
  check(route.size()==5&&route.get(1).equals(new Tile(0,1)),"weighted routing prefers four walking steps over costly direct climb");
  var escape=CombatRoutePlanner.withdraw(grid,new Tile(0,0),-1.5f,.5f,2);
  check(!escape.contains(new Tile(1,0)),"short combat withdrawal does not start a long climb");
 }
 static void directions(){
  Tile source=new Tile(101,201);
  Tile[] destinations={new Tile(102,201),new Tile(100,201),new Tile(101,200),new Tile(101,202)};
  IsoDirections[] expected={IsoDirections.E,IsoDirections.W,IsoDirections.N,IsoDirections.S};
  for(boolean window:new boolean[]{false,true})for(int i=0;i<destinations.length;i++){
   var cell=cell();IsoWorld.instance.currentCell=cell;var world=new TraversalWorld();
   var human=human(cell,"direction"+i+window,(short)1110,world);human.player.x=101.5f;human.player.y=201.5f;
   human.viewers.add(new UdpConnection());Tile destination=destinations[i];
   IsoObject object=window?new IsoWindowFrame():new IsoObject();
   object.square=cell.getGridSquare(source.x(),source.y(),0);
   cell.getGridSquare(source.x(),source.y(),0).edges.put(destination.x()+","+destination.y(),object);
   check(human.movement.start(List.of(destination),0),"cardinal action route starts");
   human.movement.update(100_000_000L);var packet=StatePacket.sent.getLast();
   Object direction=window?((zombie.ai.states.ClimbThroughWindowPositioningParams)packet.parameters().get("params")).climbDir:packet.parameters().get("dir");
   check(direction==expected[i],"native traversal direction correct on either side of portal");
   long end=100_000_000L+(window?2_600_000_000L:2_000_000_000L);
   human.movement.update(end);
   check(human.player.x==destination.x()+.5f&&human.player.y==destination.y()+.5f,"exact intended landing reached");
   check(human.movement.update(end+100_000_000L)==HumanActuator.MotionStatus.SUCCEEDED,"route resumes and completes after traversal");
   human.dispose();
  }
 }
 public static void main(String[] args){leases();lifecycle();integration();directions();routes();System.out.println("Passed "+checks+" traversal, geometry, routing and packet assertions.");}
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-traversal-tests-") as directory:
        work = Path(directory)
        sources = []
        fixture = dict(FILES)
        fixture["zombie/apocalipse/humans/TraversalTest.java"] = HARNESS
        for relative, content in fixture.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources.extend(sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans").rglob("*.java")))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources))
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.apocalipse.humans.TraversalTest"], check=True)


if __name__ == "__main__":
    main()
