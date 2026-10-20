"""Production indoor routing, motion integration and packet tests with engine stubs.

Shares the engine fixture with test_server_humans; no A-Life code is used.
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
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.movement.IndoorRoutePlanner;
import zombie.apocalipse.humans.movement.IndoorRoutePlanner.Tile;
import zombie.characters.*;
import zombie.core.raknet.UdpConnection;
import zombie.iso.*;
import zombie.network.*;
import zombie.network.packets.character.PlayerPacketReliable;
import zombie.pathfind.PolygonalMap2;
public class RoamingTest {
 static int checks;
 static final long STEP=100_000_000L;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static String key(int x,int y){return x+","+y+",0";}
 static ServerHumanDefinition definition(String key){return new ServerHumanDefinition(key,key,key,100.5f,200.5f,0,IsoDirections.S,false,"Tourist");}
 static IsoCell house(){
  IsoCell cell=new IsoCell();cell.strict=true;BuildingDef a=new BuildingDef(),b=new BuildingDef();
  for(int x=100;x<=109;x++)for(int y=200;y<=204;y++){
   IsoGridSquare square=new IsoGridSquare(x,y,0);square.building=x<=106?a:b;cell.tiles.put(key(x,y),square);
  }
  cell.tiles.get(key(104,202)).stairs=true;
  cell.tiles.get(key(105,203)).floor=false;
  cell.tiles.get(key(102,202)).free=false;
  cell.tiles.get(key(102,201)).blockedEdges.add("1,0");
  cell.tiles.get(key(103,201)).blockedEdges.add("-1,0");
  return cell;
 }
 public static void main(String[] args)throws Exception {
  int[] visits={0};IndoorRoutePlanner.Grid infinite=new IndoorRoutePlanner.Grid(){
   public boolean walkable(Tile tile){visits[0]++;return true;}
   public boolean connected(Tile a,Tile b){return true;}
  };
  List<Tile> bounded=IndoorRoutePlanner.plan(infinite,new Tile(0,0),42);
  check(!bounded.isEmpty()&&bounded.size()<=IndoorRoutePlanner.MAX_STEPS+1,"route length bounded");
  check(visits[0]<=IndoorRoutePlanner.MAX_VISITED*4+1,"search work bounded on unlimited grid");
  for(int i=1;i<bounded.size();i++)check(Math.abs(bounded.get(i).x()-bounded.get(i-1).x())+Math.abs(bounded.get(i).y()-bounded.get(i-1).y())==1,"no diagonal corner cutting");
  check(bounded.equals(IndoorRoutePlanner.plan(infinite,new Tile(0,0),42)),"same seed has reproducible route");
  IndoorRoutePlanner.Grid isolated=new IndoorRoutePlanner.Grid(){
   public boolean walkable(Tile t){return t.equals(new Tile(0,0));}
   public boolean connected(Tile a,Tile b){return false;}
  };
  check(IndoorRoutePlanner.plan(isolated,new Tile(0,0),1).isEmpty(),"no reachable destination produces no route");
  IsoCell cell=house();IsoWorld.instance.currentCell=cell;
  ServerHuman human=new ServerHuman(definition("walker"),(short)1100,cell);
  UdpConnection viewer=new UdpConnection();human.viewers.add(viewer);
  check(human.roaming.available(),"spawn building bound lazily from loaded square");
  check(!human.roaming.walkable(new Tile(107,200)),"neighbor building excluded");
  check(!human.roaming.walkable(new Tile(104,202)),"stairs excluded");
  check(!human.roaming.walkable(new Tile(105,203)),"floor holes excluded");
  check(!human.roaming.walkable(new Tile(102,202)),"solid furniture tile excluded");
  check(!human.roaming.walkable(new Tile(99,200)),"unloaded exterior excluded");
  check(!human.roaming.connected(new Tile(102,201),new Tile(103,201)),"closed boundary excluded");
  check(human.roaming.start(0),"reachable indoor route selected");
  HumanActuator.MotionStatus status=HumanActuator.MotionStatus.RUNNING;boolean walked=false,sawPrediction=false;
  BuildingDef building=cell.getGridSquare(100,200,0).building;
  for(long now=STEP;now<=400*STEP&&status==HumanActuator.MotionStatus.RUNNING;now+=STEP){
   float x=human.player.x,y=human.player.y;status=human.roaming.update(now);
   check(Math.hypot(human.player.x-x,human.player.y-y)<=ServerHumanRoaming.SPEED*.15+.0001,"per-tick distance obeys speed and elapsed cap");
   if(status==HumanActuator.MotionStatus.RUNNING)check(Math.abs(Math.abs(human.player.x-x)+Math.abs(human.player.y-y)-ServerHumanRoaming.SPEED*.1)<.0001,"waypoints consume leftover distance without a dead tick");
   check(cell.getGridSquare(human.player.getXi(),human.player.getYi(),0).building==building&&human.player.z==0,"all movement stays in original building and floor");
   if(human.moving){
    walked=true;ServerHumanTransport.idle(human,viewer);var packet=PlayerPacketReliable.sent.getLast();
    check(packet.prediction.x==human.player.x&&packet.prediction.y==human.player.y,"movement packets use live position");
    check((packet.booleanVariables&(1<<8))!=0,"native walking flag maintained");
    check(packet.prediction.distance>=0&&packet.prediction.distance<=7,"750ms prediction horizon bounded in eighth-tile units");
    if(packet.prediction.distance>0){
     sawPrediction=true;
     check(packet.prediction.type==1&&packet.prediction.speed==ServerHumanRoaming.SPEED,"native moving prediction carries speed");
     check(packet.prediction.moveDirection==packet.prediction.direction,"prediction follows current segment heading");
     PolygonalMap2.instance.blocked=true;
     check(human.roaming.predictionDistanceUnits()==0,"new collision disables extrapolation immediately");
     PolygonalMap2.instance.blocked=false;
    }else check(packet.prediction.type==0,"near-corner prediction falls back to static point");
   }
  }
  check(walked&&status==HumanActuator.MotionStatus.SUCCEEDED,"route eventually completes");
  check(sawPrediction,"straight movement actually enables native prediction");
  check(!human.moving,"route completion clears movement");
  // Exercise straight tile-center lookahead and clipping at a real corner.
  var routeField=ServerHumanRoaming.class.getDeclaredField("route");routeField.setAccessible(true);
  var waypointField=ServerHumanRoaming.class.getDeclaredField("waypoint");waypointField.setAccessible(true);
  routeField.set(human.roaming,List.of(new Tile(101,200),new Tile(102,200),new Tile(102,201)));
  waypointField.setInt(human.roaming,0);human.player.x=101.4f;human.player.y=200.5f;
  human.player.setDirectionAngle(0);human.moving=true;
  int straightUnits=human.roaming.predictionDistanceUnits();
  check(straightUnits==7,"prediction spans straight tile centers and covers heartbeat plus jitter");
  check(straightUnits/(8.0*ServerHumanRoaming.SPEED)>.5,"quantized horizon exceeds 500ms send interval");
  cell.getGridSquare(102,200,0).free=false;
  check(human.roaming.predictionDistanceUnits()==0,"blocked next straight tile prevents prediction across waypoint");
  cell.getGridSquare(102,200,0).free=true;
  waypointField.setInt(human.roaming,1);human.player.x=102.0f;
  int cornerUnits=human.roaming.predictionDistanceUnits();
  check(cornerUnits==2&&human.player.x+cornerUnits/8.0<=102.35,"prediction clips before corner with safety margin");
  human.player.x=102.4f;
  check(human.roaming.predictionDistanceUnits()==0,"prediction cannot continue old heading around corner");
  human.roaming.stop();
  check(!human.moving,"explicit stop clears movement");
  ServerHumanTransport.idle(human,viewer);
  check(PlayerPacketReliable.sent.getLast().prediction.distance==0&&PlayerPacketReliable.sent.getLast().prediction.type==0,"stopping clears prediction");
  float postX=human.player.x,postY=human.player.y;
  human.player.setDirectionAngle(37);human.actuator.returnHome();
  check(human.player.x==postX&&human.player.y==postY,"greeting recovery keeps stopping point instead of teleporting to spawn");
  check(human.player.facing==IsoDirections.S,"greeting recovery restores configured facing");
  check(cell.add.isEmpty()&&cell.objects.isEmpty(),"walking keeps proxy outside logged-in-player simulation lists");
  IsoPlayer visitor=new IsoPlayer(cell,new SurvivorDesc(false),0,0,0);visitor.id=7;visitor.instanceId=7;
  visitor.x=postX+1;visitor.y=postY;viewer.players[0]=visitor;
  GameServer.Players.add(visitor);GameServer.IDToPlayerMap.put(visitor.id,visitor);GameServer.owners.put(visitor,viewer);
  var observation=human.actuator.observe(100*STEP);
  check(observation.find(new HumanObservation.Identity(visitor.id,visitor.instanceId)).distanceSquared()==1,"visitor distance measured from roaming position");
  GameServer.Players.clear();GameServer.IDToPlayerMap.clear();
  check(human.roaming.start(0),"second route planned");
  human.roaming.update(STEP);float beforeX=human.player.x,beforeY=human.player.y;
  PolygonalMap2.instance.blocked=true;
  check(human.roaming.update(2*STEP)==HumanActuator.MotionStatus.FAILED,"new native collision aborts active path");
  check(!human.moving&&human.player.x==beforeX&&human.player.y==beforeY,"obstruction stops before crossing");
  PolygonalMap2.instance.blocked=false;
  check(human.roaming.start(0),"route can be retried after obstacle clears");
  for(var square:cell.tiles.values())square.occupied=true;
  human.roaming.update(STEP);
  check(human.roaming.update(2*STEP)==HumanActuator.MotionStatus.FAILED,"occupied destination stops movement");
  for(var square:cell.tiles.values())square.occupied=false;
  check(human.roaming.start(0),"route planned before unload");
  IsoGridSquare origin=cell.tiles.remove(key(100,200));
  check(human.roaming.update(STEP)==HumanActuator.MotionStatus.FAILED,"unloaded origin safely suspends route");cell.tiles.put(key(100,200),origin);
  human.dispose();
  GameServer.udpEngine.connections.clear();GameServer.udpEngine.connections.add(viewer);
  var constructor=ServerHumanFactory.class.getDeclaredConstructor();constructor.setAccessible(true);
  var factory=constructor.newInstance();factory.tick(0);factory.spawn(definition("factory"));
  PlayerPacketReliable.sent.clear();
  var humansField=ServerHumanFactory.class.getDeclaredField("humans");humansField.setAccessible(true);
  ServerHuman replica=(ServerHuman)((Map<?,?>)humansField.get(factory)).get("factory");
  int suppressedMotion=0,transitions=0,heartbeats=0;
  for(long now=STEP;now<=200*STEP;now+=STEP){
   float oldDirection=replica.player.getDirectionAngleRadians(),oldX=replica.player.x,oldY=replica.player.y;
   boolean oldMoving=replica.moving;int before=PlayerPacketReliable.sent.size();
   factory.tick(now);
   boolean changed=Float.compare(oldDirection,replica.player.getDirectionAngleRadians())!=0||oldMoving!=replica.moving;
   boolean heartbeat=now%ServerHumanFactory.UPDATE_NANOS==0;
   check(PlayerPacketReliable.sent.size()-before==(heartbeat||changed?1:0),"only heartbeat or immediate turn/start/stop sends movement packet");
   if(changed)transitions++;
   if(heartbeat)heartbeats++;
   if(!heartbeat&&!changed&&(oldX!=replica.player.x||oldY!=replica.player.y))suppressedMotion++;
  }
  check(suppressedMotion>0,"straight walking advances at 10Hz without sending every step");
  check(transitions>0&&heartbeats==40,"transition exceptions preserved alongside exactly two routine updates per second");
  boolean sawWalk=false,sawTranslation=false;float previousX=Float.NaN,previousY=Float.NaN;
  for(var packet:PlayerPacketReliable.sent){
   if((packet.booleanVariables&(1<<8))!=0)sawWalk=true;
   if(Float.isFinite(previousX)&&(packet.prediction.x!=previousX||packet.prediction.y!=previousY))sawTranslation=true;
   previousX=packet.prediction.x;previousY=packet.prediction.y;
  }
  check(sawWalk&&sawTranslation,"factory starts roaming and publishes translation even along constant heading");
  check(PlayerPacketReliable.sent.size()<=heartbeats+transitions,"extra movement packets require a transition");
  factory.remove("factory");
  System.out.println("Passed "+checks+" indoor route, motion and replication assertions.");
 }
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-roaming-tests-") as directory:
        work = Path(directory)
        sources = []
        fixture = dict(FILES)
        fixture["zombie/apocalipse/humans/RoamingTest.java"] = HARNESS
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
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.apocalipse.humans.RoamingTest"], check=True)


if __name__ == "__main__":
    main()
