"""Production pattern registry, objective navigation, independent progression and native ownership."""
from pathlib import Path
import subprocess
import sys
import tempfile
sys.dont_write_bytecode = True
from test_server_humans import FILES, ROOT

HARNESS = r"""
package zombie.apocalipse.humans;
import java.util.*;
import zombie.apocalipse.humans.walking.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.*;
import zombie.apocalipse.humans.movement.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.profiles.*;
import zombie.iso.*;
public class WalkingTest {
 static int checks;static long clock;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void invalid(Runnable r){try{r.run();throw new AssertionError("invalid accepted");}catch(IllegalArgumentException expected){checks++;}}
 static final class World implements WalkingPattern.World {
  int queries;boolean loaded=true;
  public boolean available(){return loaded;}
  public boolean walkable(Tile tile){queries++;return tile.x()>=100&&tile.x()<=180&&tile.y()>=200&&tile.y()<=204;}
  public boolean sameBuilding(Tile a,Tile b){return b.x()<=103;}
  public boolean connected(Tile a,Tile b){return Math.abs(a.x()-b.x())+Math.abs(a.y()-b.y())==1;}
 }
 static WalkingPattern.Context context(World world){return new WalkingPattern.Context(world,new Tile(100,200),new Tile(100,200),0,1);}
 static void patterns(){
  var registry=WalkingPatternRegistry.builtins();var world=new World();var context=context(world);
  var indoor=registry.create(WalkingPatternSpec.indoor());
  check(indoor.scope(context).walkable(new Tile(103,200))&&!indoor.scope(context).walkable(new Tile(104,200)),"building restriction belongs to indoor pattern");
  var area=new WalkingPatternSpec("background","area-wander",8,List.of(),new Area(100,200,106,204));
  var outdoors=registry.create(area);
  check(outdoors.scope(context).walkable(new Tile(106,200))&&!outdoors.scope(context).walkable(new Tile(107,200)),"area wandering crosses building boundary but respects assigned rectangle");
  var plan=outdoors.plan(context);check(!plan.isEmpty()&&plan.stream().allMatch(t->outdoors.scope(context).walkable(t)),"random route stays within selected pattern domain");
  var spec=new WalkingPatternSpec("background","waypoint-circuit",16,List.of(new Point(102,200,0),new Point(104,200,0)));
  var one=registry.create(spec);var two=registry.create(spec);
  check(one.plan(context).getLast().equals(new Tile(102,200)),"circuit selects first destination");
  one.completed(false);check(one.plan(context).getLast().equals(new Tile(102,200)),"failed or cancelled leg does not advance circuit");
  one.completed(true);check(one.plan(context).getLast().equals(new Tile(104,200)),"successful leg advances circuit");
  check(two.plan(context).getLast().equals(new Tile(102,200)),"pattern cursor is fresh per actor");
  one.completed(true);check(one.plan(context).getLast().equals(new Tile(102,200)),"circuit wraps in declared order");
  world.queries=0;var route=DestinationRoutePlanner.plan(world,new Tile(100,200),new Tile(150,200));
  check(route.size()==51,"bounded objective A* reaches a distant same-floor destination");
  check(world.queries<1500,"objective search work stays bounded");
  invalid(()->registry.create(new WalkingPatternSpec("background","unknown-code",16,List.of())));
  invalid(()->registry.create(new WalkingPatternSpec("background","waypoint-circuit",16,List.of())));
  invalid(()->registry.create(new WalkingPatternSpec("background","indoor-wander",16,List.of(new Point(101,200,0)))));
  invalid(()->registry.create(new WalkingPatternSpec("background","waypoint-circuit",16,List.of(new Point(101,200,0),new Point(101,200,1)))));
  invalid(()->registry.create(new WalkingPatternSpec("background","waypoint-circuit",16,List.of(new Point(110,200,0)),new Area(100,200,105,204))));
  invalid(()->new Area(20,10,10,20));
 }
 static ServerHuman actor(String id,WalkingPatternSpec spec,boolean building){
  var profile=new HumanProfile(id,1,"modules",Map.of(),"","none",false,false,List.of(new HumanModuleBinding("walk",1000,100,Map.of("pauseMs",0L))),8,100,true,List.of(spec));
  ServerHumanProfiles.registry().register(profile);
  var cell=new IsoCell();cell.strict=true;var home=new BuildingDef();var neighbor=new BuildingDef();
  for(int x=100;x<=110;x++)for(int y=200;y<=204;y++){
   var square=new IsoGridSquare(x,y,0);if(building)square.building=x<=103?home:neighbor;cell.tiles.put(x+","+y+",0",square);
  }
  IsoWorld.instance.currentCell=cell;
  return new ServerHuman(new ServerHumanDefinition(id,id,id,100.5f,200.5f,0,IsoDirections.E,false,"Tourist",id),(short)1150,cell);
 }
 static void complete(ServerHuman human){
  HumanActuator.MotionStatus result=HumanActuator.MotionStatus.RUNNING;
  for(int i=0;i<200&&result==HumanActuator.MotionStatus.RUNNING;i++){clock+=100_000_000L;result=human.walking.update(clock);}
  check(result==HumanActuator.MotionStatus.SUCCEEDED,"navigation request completes through physical movement");
 }
 static void integration(){
  var human=actor("outside-walker",new WalkingPatternSpec("background","area-wander",8,List.of(),new Area(100,200,106,204)),false);
  check(human.perception.observe(0).movementAvailable(),"movement sense depends on loaded physical space, not lobby building or viewers");
  check(human.territory.contains(106.5f,200.5f,0)&&!human.territory.contains(107.5f,200.5f,0),"combat territory follows assigned movement domain outdoors");
  check(human.walking.start(new MovementRequest.To(new Point(106,200,0)),clock),"objective can request a destination outside any building");complete(human);
  check(Math.abs(human.player.x-106.5f)<.001,"objective arrives at actual destination");
  check(!human.walking.start(new MovementRequest.To(new Point(107,200,0)),clock),"objective cannot bypass assigned boundary");
  check(!human.walking.start(new MovementRequest.To(new Point(105,200,1)),clock),"floor changes rejected by navigation");human.dispose();
  var indoor=actor("inside-walker",WalkingPatternSpec.indoor(),true);
  check(!indoor.walking.start(new MovementRequest.To(new Point(104,200,0)),clock),"indoor profile retains home building scope");indoor.dispose();
  var circuit=actor("circuit-walker",new WalkingPatternSpec("background","waypoint-circuit",16,List.of(new Point(102,200,0),new Point(104,200,0))),true);
  var action=new HumanActionExecution(new HumanAction.Move(new MovementRequest.Pattern("background")));
  for(int i=0;i<100&&!action.terminal();i++){clock+=100_000_000L;action.update(clock,circuit.actuator);}
  check(action.status()==HumanActionExecution.Status.SUCCEEDED&&Math.abs(circuit.player.x-102.5f)<.001,"generic action/actuator delegates to configured circuit");
  check(circuit.walking.start(new MovementRequest.Pattern("background"),clock),"next circuit leg starts");
  clock+=100_000_000L;circuit.walking.update(clock);circuit.actuator.stopMoving();
  check(circuit.walking.start(new MovementRequest.Pattern("background"),clock),"cancelled circuit leg can restart");complete(circuit);
  check(Math.abs(circuit.player.x-104.5f)<.001,"cancellation retained destination cursor");
  check(circuit.walking.start(new MovementRequest.Pattern("background"),clock),"circuit wrap starts");complete(circuit);
  check(Math.abs(circuit.player.x-102.5f)<.001,"circuit resumes after crossing a building boundary");circuit.dispose();
 }
 public static void main(String[]args){patterns();integration();System.out.println("Passed "+checks+" pattern ownership, objective navigation and movement-sense assertions.");}
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-walking-tests-") as directory:
        work = Path(directory)
        sources = []
        fixture = dict(FILES)
        fixture["zombie/apocalipse/humans/WalkingTest.java"] = HARNESS
        for name, content in fixture.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources.extend(sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans").rglob("*.java")))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources))
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.apocalipse.humans.WalkingTest"], check=True)


if __name__ == "__main__":
    main()
