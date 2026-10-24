"""Exercise the production controller, sessions, geometry, ownership and packet preparation."""
from pathlib import Path
import subprocess
import sys
import tempfile
sys.dont_write_bytecode = True
from test_zombie_auth_algorithms import STUBS as AUTH_STUBS

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "42.21.0/src"
STUBS = dict(AUTH_STUBS)
STUBS.pop("zombie/apocalipse/zombies/ServerZombieController.java")
STUBS.update({
"zombie/ai/astar/Mover.java": """package zombie.ai.astar;public interface Mover {int getID();int getPathFindIndex();}""",
"zombie/pathfind/IPathfinder.java": """package zombie.pathfind;public interface IPathfinder {
void Succeeded(Path p,zombie.ai.astar.Mover m);void Failed(zombie.ai.astar.Mover m);}""",
"zombie/pathfind/Path.java": """package zombie.pathfind;public class Path {
public static class Node {public float x,y,z;Node(float x,float y,float z){this.x=x;this.y=y;this.z=z;}}
public final java.util.List<Node> nodes=new java.util.ArrayList<>();
public int size(){return nodes.size();}public Node getNode(int n){return nodes.get(n);}
public void addNode(float x,float y,float z){nodes.add(new Node(x,y,z));}}""",
"zombie/pathfind/nativeCode/PathfindNative.java": """package zombie.pathfind.nativeCode;
public class PathfindNative {
public static boolean useNativeCode=true;public static final PathfindNative instance=new PathfindNative();
public record Job(zombie.pathfind.IPathfinder finder,zombie.ai.astar.Mover mover){}
public final java.util.Map<zombie.ai.astar.Mover,Job> jobs=new java.util.IdentityHashMap<>();
public int requests,cancels,pumps;
public void addRequest(zombie.pathfind.IPathfinder f,zombie.ai.astar.Mover m,float x,float y,float z,float a,float b,float c){requests++;jobs.put(m,new Job(f,m));}
public void cancelRequest(zombie.ai.astar.Mover m){cancels++;jobs.remove(m);}
public void updateMain(){pumps++;}
public void complete(zombie.pathfind.Path path){for(var job:new java.util.ArrayList<>(jobs.values())){jobs.remove(job.mover());job.finder().Succeeded(path,job.mover());}}
}""",
"zombie/characters/NetworkZombieVariables.java": """package zombie.characters;public class NetworkZombieVariables {
public enum Flag {IsFakeDead,IsLunger,IsRunning}}""",
"zombie/apocalipse/humans/HumanLog.java": """package zombie.apocalipse.humans;
public class HumanLog {public enum Level{DEBUG}public static void log(Level l,java.util.function.Supplier<String> s){}}""",
"zombie/network/NetworkVariables.java": """package zombie.network;public class NetworkVariables {
public enum ZombieState {Idle,WalkToward,WalkTowardNetwork,PathFind,Lunge,LungeNetwork,Attack,OnGround,HitReaction}}
""",
"zombie/network/ServerMap.java": """package zombie.network;public class ServerMap {
public static final ServerMap instance=new ServerMap();public final java.util.Map<Short,zombie.characters.IsoZombie> zombieMap=new java.util.HashMap<>();}""",
"zombie/iso/Vector2.java": """package zombie.iso;public class Vector2 {float x,y;public Vector2(float x,float y){this.x=x;this.y=y;}
public void setLength(float v){x=v;y=0;}public float getLength(){return (float)Math.hypot(x,y);}}""",
"zombie/iso/IsoGridSquare.java": """package zombie.iso;public class IsoGridSquare {
public final int x,y;public boolean free=true,stairs;public final java.util.List<Object> bodies=new java.util.ArrayList<>();
public IsoGridSquare(int x,int y){this.x=x;this.y=y;}public boolean TreatAsSolidFloor(){return true;}
public boolean isFree(boolean players){return free;}public boolean HasStairs(){return stairs;}
public boolean isSomethingTo(IsoGridSquare other){return other==null||!other.free;}
public java.util.List<Object> getMovingObjects(){return bodies;}}""",
"zombie/iso/IsoCell.java": """package zombie.iso;public class IsoCell {
public final java.util.ArrayList<zombie.characters.IsoZombie> zombies=new java.util.ArrayList<>();
public final java.util.Set<zombie.characters.IsoZombie> objects=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
public final java.util.Map<String,IsoGridSquare> squares=new java.util.HashMap<>();
public java.util.ArrayList<zombie.characters.IsoZombie> getZombieList(){return zombies;}
public java.util.Set<zombie.characters.IsoZombie> getObjectList(){return objects;}
public IsoGridSquare getGridSquare(int x,int y,int z){return z==0?squares.get(x+","+y):null;}
public static class Queries {public void invalidateZombies(){}}public Queries getPlayerSpatialQueries(){return new Queries();}}""",
"zombie/iso/LosUtil.java": """package zombie.iso;public class LosUtil {
public enum TestResults{Clear,Blocked,ClearThroughClosedDoor}public static TestResults result=TestResults.Clear;
public static TestResults lineClear(IsoCell c,int x,int y,int z,int a,int b,int d,boolean windows){return result;}}""",
"zombie/pathfind/PolygonalMap2.java": """package zombie.pathfind;public class PolygonalMap2 {
public static final PolygonalMap2 instance=new PolygonalMap2();public boolean blocked;public int requests;
public void addRequest(IPathfinder f,zombie.ai.astar.Mover m,float x,float y,float z,float a,float b,float c){requests++;zombie.pathfind.nativeCode.PathfindNative.instance.addRequest(f,m,x,y,z,a,b,c);}
public void cancelRequest(zombie.ai.astar.Mover m){zombie.pathfind.nativeCode.PathfindNative.instance.cancelRequest(m);}
public void updateMain(){zombie.pathfind.nativeCode.PathfindNative.instance.updateMain();}
public boolean lineClearCollide(float x,float y,float a,float b,int z,Object o,boolean d,boolean w){
 if(blocked)return true;
 int steps=Math.max(1,(int)(Math.hypot(a-x,b-y)*20));
 for(int i=0;i<=steps;i++){var s=zombie.iso.IsoWorld.instance.currentCell.getGridSquare((int)Math.floor(x+(a-x)*i/steps),(int)Math.floor(y+(b-y)*i/steps),z);if(s==null||!s.free)return true;}
 return false;}}""",
"zombie/characters/IsoZombie.java": """package zombie.characters;
import zombie.core.raknet.UdpConnection;
public class IsoZombie {
 public float x,y,realx,realy,speedMod=.6f,health=1;public byte realz;public int id,dies;public short onlineId=1;
 public long lastChangeOwner;public IsoPlayer target,ownerPlayer;public UdpConnection owner;
 public boolean dead,reanimated,running,sprinter,knocked,floor,stagger,inactive;public int speedType=2;public String walkType="1";
 public String getWalkType(){return walkType;}public int getSpeedType(){return speedType;}
 public zombie.ai.State state=zombie.ai.states.ZombieIdleState.instance();
 public zombie.iso.IsoGridSquare square,movingSquare;
 public zombie.network.NetworkVariables.ZombieState realState=zombie.network.NetworkVariables.ZombieState.Idle;
 public final zombie.network.packets.character.ZombiePacket zombiePacket=new zombie.network.packets.character.ZombiePacket();
 public static class Grapple{public Object other;public Object getGrappledBy(){return other;}}
 public final Grapple grapple=new Grapple();public Grapple getWrappedGrappleable(){return grapple;}
 public int getID(){return id;}public float getX(){return x;}public float getY(){return y;}public float getZ(){return 0;}public int getZi(){return 0;}
 public void setX(float v){x=v;}public void setY(float v){y=v;}public void setLastX(float v){}public void setLastY(float v){}
 public void setNextX(float v){}public void setNextY(float v){}public void setCurrentSquare(zombie.iso.IsoGridSquare s){square=s;}
 public void setMovingSquareNow(){if(movingSquare!=null)movingSquare.bodies.remove(this);movingSquare=square;if(!square.bodies.contains(this))square.bodies.add(this);}
 public zombie.iso.IsoGridSquare getCurrentSquare(){return square;}public zombie.iso.IsoCell getCell(){return zombie.iso.IsoWorld.instance.currentCell;}
 public short getOnlineID(){return onlineId;}public UdpConnection getOwner(){return owner;}public void setOwner(UdpConnection c){owner=c;}
 public IsoPlayer getOwnerPlayer(){return ownerPlayer;}public void setOwnerPlayer(IsoPlayer p){ownerPlayer=p;}
 public void setTarget(IsoPlayer p){target=p;}public boolean isRemoteZombie(){return owner==null;}public boolean isDead(){return dead;}
 public zombie.ai.State getCurrentState(){return state;}public boolean isReanimatedPlayer(){return reanimated;}
 public boolean crawling;public boolean isCrawling(){return crawling;}public boolean isReanimatedForGrappleOnly(){return false;}
 public boolean isUseless(){return false;}public boolean isCanWalk(){return true;}
 public boolean isKnockedDown(){return knocked;}public boolean isOnFloor(){return floor;}public boolean isRagdoll(){return false;}
 public boolean isStaggerBack(){return stagger;}public boolean isFakeDead(){return false;}public boolean isBeingGrappled(){return grapple.other!=null;}
 public boolean isGrappling(){return false;}public boolean isNoTeeth(){return false;}public float getSpeedMod(){return speedMod;}
 public void getZombieWalkTowardSpeed(float base,float distance,zombie.iso.Vector2 out){out.setLength(sprinter?.08f:base*speedMod+.006f);if(sprinter)running=true;}
 public void setDirectionAngle(float angle){}public void faceThisObject(IsoPlayer p){}public float timeSinceSeenFlesh;
 public static class AI{public void resetSpeedLimiter(){}}public final AI ai=new AI();public AI getNetworkCharacterAI(){return ai;}
 public void die(){dies++;}public void removeFromWorld(){}public void removeFromSquare(){}
}
""",
"zombie/network/packets/character/ZombiePacket.java": """package zombie.network.packets.character;public class ZombiePacket {
public short id,health,target,booleanVariables;public float x,y,realX,realY;public byte z,realZ,update,predictionType;
public zombie.network.NetworkVariables.ZombieState realState=zombie.network.NetworkVariables.ZombieState.Idle;
public static class Pfb{public void reset(){}}public final Pfb pfb=new Pfb();
public void prepareFrame(zombie.characters.IsoZombie body){id=body.onlineId;}
public void setPrepared(zombie.characters.IsoZombie body){id=body.onlineId;health=(short)(body.health*1000);target=body.target==null?-1:body.target.getOnlineID();update=16;}}
""",
})
STUBS["zombie/characters/IsoPlayer.java"] = STUBS["zombie/characters/IsoPlayer.java"].replace(
    "public float x,y;", "public int getZi(){return 0;}public boolean isGhostMode(){return false;}public float x,y;").replace(
    "public short getOnlineID(){return 1;}", "public short getOnlineID(){return 1024;}")

HARNESS = r"""package zombie.apocalipse.zombies;
import java.util.*;
import zombie.characters.*;import zombie.iso.*;import zombie.network.*;import zombie.popman.*;import zombie.core.math.PZMath;
import zombie.apocalipse.zombies.ZombieVictimPort.Victim;
public class ControlTest {
 static int checks;static void check(boolean ok,String what){checks++;if(!ok)throw new AssertionError(what);}
 static class Victims implements ZombieVictimPort {
  Victim victim;int strikes;String result="would_hit_god_mode";
  public List<Victim> snapshot(){return victim==null?List.of():List.of(victim);}
  public Victim resolve(short id){return victim!=null&&victim.id()==id?victim:null;}
  public String strike(Victim v,IsoZombie z,long attack){strikes++;return result;}
 }
 static IsoZombie body(int id,float x,float y){var z=new IsoZombie();z.id=id;z.onlineId=(short)id;z.x=x;z.y=y;z.square=IsoWorld.instance.currentCell.getGridSquare((int)x,(int)y,0);
  z.setMovingSquareNow();ServerMap.instance.zombieMap.put(z.onlineId,z);IsoWorld.instance.currentCell.zombies.add(z);IsoWorld.instance.currentCell.objects.add(z);return z;}
 static Victim victim(float x,float y){var p=new IsoPlayer();p.x=x;p.y=y;return new Victim((short)1024,p,x,y,0);}
 public static void main(String[] args)throws Exception {
  var cell=IsoWorld.instance.currentCell;for(int x=-2;x<30;x++)for(int y=-3;y<8;y++)cell.squares.put(x+","+y,new IsoGridSquare(x,y));
  var port=new Victims();port.victim=victim(2.5f,.5f);
  var z=body(1,.5f,.5f);var adapter=new ServerZombieNativeAdapter(z,1.2f);var session=new ServerZombieSession(adapter,port.victim,0);
  if(args.length>0){var disabled=ServerZombieController.getInstance();disabled.install(port);disabled.beginFrame();
   check(!disabled.retainOrAcquire(z),"startup flag disables acquisition");System.out.println("Passed disabled-controller assertion.");return;}
  check(session.update(port.victim,port,100_000_000L)==null,"pursuit active");
  check(Math.abs(z.x-.62f)<.0001,"native sampled speed applied with elapsed time");
  var prediction=new zombie.network.packets.character.ZombiePacket();adapter.packet(prediction);
  check(prediction.predictionType==1 && Math.abs(prediction.x-z.x-.24f)<.001 && prediction.realX==z.x,
   "moving packet predicts two 100ms frames while preserving real position");
  zombie.pathfind.PolygonalMap2.instance.blocked=true;adapter.packet(prediction);
  check(prediction.predictionType==0 && prediction.x==z.x,"collision suppresses prediction");
  zombie.pathfind.PolygonalMap2.instance.blocked=false;
  adapter.present(false,false);adapter.packet(prediction);
  check(prediction.predictionType==0,"idle never inherits moving prediction");
  var runner=body(20,10.5f,4.5f);runner.sprinter=true;
  var runnerAdapter=new ServerZombieNativeAdapter(runner,2.4f);var runnerVictim=victim(20.5f,4.5f);
  check(!runner.running,"presentation does not mutate native running flag");
  check(runnerAdapter.move(runnerVictim,.1f),"sprinter moves");runnerAdapter.present(true,false);runnerAdapter.packet(prediction);
  check(Math.abs(runner.x-10.74f)<.001 && Math.abs(prediction.x-runner.x-.48f)<.001
   && (prediction.booleanVariables&4)!=0,"sprinter pace and running presentation match prediction");
  runner.speedMod*=2;runnerAdapter.move(runnerVictim,.15f);runnerAdapter.packet(prediction);
  check(Math.abs(runner.x-11.1f)<.001,"native sprinter pace does not multiply speedMod twice");
  check(prediction.x-runner.x<=.601f,"speed changes and delayed frames keep prediction spatially bounded");
  var shortGoal=victim(runner.x+.02f,runner.y);runnerAdapter.move(shortGoal,.001f);runnerAdapter.packet(prediction);
  check(prediction.x<=shortGoal.x()+.0001f,"prediction stops at pursuit goal");
  runnerAdapter.present(false,true);runnerAdapter.packet(prediction);
  check(prediction.predictionType==0 && (prediction.booleanVariables&4)==0,"attack removes prediction and running flag");
  var start=cell.getGridSquare(0,0,0);z.x=.98f;session.update(port.victim,port,200_000_000L);
  check(z.square==cell.getGridSquare(1,0,0)&&!start.bodies.contains(z)&&z.square.bodies.contains(z),"native moving-square membership follows movement");
  z.x=2;session.update(port.victim,port,300_000_000L);check(session.phase==ServerZombieSession.Phase.WINDUP,"contact begins windup");
  session.update(port.victim,port,800_000_000L);check(port.strikes==0,"no early strike");
  session.update(port.victim,port,850_000_000L);check(port.strikes==1,"one strike on deadline");
  session.update(port.victim,port,900_000_000L);check(port.strikes==1,"repeated updates do not duplicate strike");
  check(session.phase==ServerZombieSession.Phase.RECOVERY,"attack recovery");
  var p=new zombie.network.packets.character.ZombiePacket();adapter.present(false,true);adapter.packet(p);
  check(p.realState==NetworkVariables.ZombieState.Attack && (p.update&16)==0 && p.predictionType==0,"native attack packet and stale path removal");
  port.strikes=0;session=new ServerZombieSession(adapter,port.victim,0);session.update(port.victim,port,1);
  var far=new Victim(port.victim.id(),port.victim.body(),4,.5f,0);session.update(far,port,550_000_001L);
  check(port.strikes==0&&session.result.equals("contact_lost"),"moving victim escapes windup");
  session=new ServerZombieSession(adapter,port.victim,0);session.update(port.victim,port,1);z.knocked=true;
  check(session.update(port.victim,port,550_000_001L).equals("native_reaction")&&port.strikes==0,"knockdown cancels pending strike");z.knocked=false;
  session=new ServerZombieSession(adapter,port.victim,0);session.update(port.victim,port,1);session.update(port.victim,port,3_000_000_000L);
  check(session.result.equals("strike_expired")&&port.strikes==0,"late frame cannot land stale attack");
  check(session.update(victim(2.5f,.5f),port,3_100_000_000L).equals("victim_lost"),"same id new body cannot inherit attack");
  z.crawling=true;check(adapter.interrupted(),"crawlers keep native execution");z.crawling=false;
  z.state=null;check(!adapter.interrupted(),"missing server AI state alone permits control");
  z.knocked=true;check(adapter.interrupted(),"null AI state cannot bypass knockdown protection");z.knocked=false;
  z.grapple.other=new Object();check(adapter.interrupted(),"null AI state cannot bypass grapple protection");z.grapple.other=null;
  LosUtil.result=LosUtil.TestResults.ClearThroughClosedDoor;check(!adapter.contact(port.victim),"closed door blocks contact");LosUtil.result=LosUtil.TestResults.Clear;
  cell.getGridSquare(1,1,0).free=false;var around=body(2,.5f,1.5f);var nav=new ServerZombieNativeAdapter(around,1);
  check(nav.route(new Victim(port.victim.id(),port.victim.body(),2.5f,1.5f,0)),"bounded path routes around furniture");
  check(!nav.move(port.victim,.1f),"pending native route cannot move directly through furniture");
  var path=new zombie.pathfind.Path();path.addNode(.5f,1.5f,0);path.addNode(.5f,.5f,0);path.addNode(2.5f,.5f,0);path.addNode(2.5f,1.5f,0);
  zombie.pathfind.nativeCode.PathfindNative.instance.complete(path);
  for(int n=0;n<15;n++)check(nav.move(port.victim,.1f),"route advances without static collision");
  check(around.square.free,"route never enters appliance square");
  var replan=body(21,.9f,1.5f);var replanner=new ServerZombieNativeAdapter(replan,1);
  var detour=victim(2.5f,1.5f);check(replanner.route(detour),"detour planned from off-centre start");
  zombie.pathfind.nativeCode.PathfindNative.instance.complete(path);
  float oldY=replan.y;replanner.move(detour,.1f);
  check(replan.y!=oldY,"detour advances toward next tile rather than root centre");
  oldY=replan.y;replanner.route(detour);replanner.move(detour,.1f);
  check(Math.abs(replan.y-1.5f)>Math.abs(oldY-1.5f),"replan continues detour without reversing to root");
  var recovering=body(22,2,.5f);var recovery=new ServerZombieSession(new ServerZombieNativeAdapter(recovering,1),port.victim,0);
  recovery.update(port.victim,port,1);recovery.update(port.victim,port,550_000_001L);
  recovery.update(port.victim,port,12_000_000_001L);
  check(recovery.update(new Victim(port.victim.id(),port.victim.body(),8,.5f,0),port,12_100_000_001L)==null,
   "attack and recovery time do not count as pursuit stall");
  var waiting=body(23,.5f,5.5f);var waitingVictim=victim(8,5.5f);
  var waitingSession=new ServerZombieSession(new ServerZombieNativeAdapter(waiting,1),waitingVictim,0);
  zombie.pathfind.PolygonalMap2.instance.blocked=true;
  check(waitingSession.update(waitingVictim,port,100_000_000L)==null,"unavailable route retains session");
  check(waitingSession.update(waitingVictim,port,3_000_000_000L)==null && waiting.x==.5f,
   "temporary blockage does not hand off or fall back to stale direct movement");
  check(waitingSession.update(waitingVictim,port,10_100_000_000L).equals("pursuit_stalled"),"uncompleted native route eventually releases");
  zombie.pathfind.PolygonalMap2.instance.blocked=false;
  var incoming=new zombie.network.packets.character.ZombiePacket();incoming.realX=.5f;incoming.realY=.5f;incoming.x=2.5f;incoming.y=1.5f;
  check(incomingSquare(incoming)==cell.getGridSquare(0,0,0),"production incoming square follows real position across prediction boundary");
  incoming.realX=-.25f;
  check(incomingSquare(incoming)==cell.getGridSquare(-1,0,0),"incoming real square floors negative coordinates");
  var controller=ServerZombieController.getInstance();controller.install(port);controller.beginFrame();
  z.x=.5f;z.y=.5f;z.square=cell.getGridSquare(0,0,0);z.setMovingSquareNow();
  var connection=new zombie.core.raknet.UdpConnection();var viewer=new IsoPlayer();viewer.x=10;connection.players[0]=viewer;GameServer.udpEngine.connections.add(connection);
  NetworkZombieManager.getInstance().beginAuthUpdate();NetworkZombieManager.getInstance().moveZombie(z,connection,viewer);
  check(z.getOwner()==connection,"ordinary native owner before acquisition");
  check(z.state==null,"acquisition fixture retains missing server AI state");
  LosUtil.result=LosUtil.TestResults.Blocked;
  check(!controller.retainOrAcquire(z),"native two-second ownership cooldown respected");
  z.lastChangeOwner=System.currentTimeMillis()-2000;
  LosUtil.result=LosUtil.TestResults.Blocked;
  check(controller.retainOrAcquire(z)&&z.getOwner()==null&&controller.controls(z),"close reachable NPC acquires without direct sight and pins null owner");
  LosUtil.result=LosUtil.TestResults.Clear;
  NetworkZombieManager.getInstance().updateAuth(z);check(z.getOwner()==null,"ordinary auth traversal cannot reassign controlled zombie");
  NetworkZombieManager.getInstance().moveZombie(z,connection,viewer);check(z.getOwner()==null,"direct transfer cannot steal active session");
  var auth=new zombie.network.packets.character.ZombieListPacket();NetworkZombieManager.getInstance().getZombieAuth(connection,auth);
  check(!auth.zombiesAuth.contains(z.onlineId),"previous client authorization revoked");
  var outgoing=new ArrayList<IsoZombie>();controller.addRelays(outgoing);check(outgoing.contains(z),"ownerless controlled zombie is an explicit relay candidate");
  var prepared=new ServerZombiePacketPreparation();prepared.beginFrame();prepared.prepare(z);
  check(z.zombiePacket.target==1024 && (z.zombiePacket.update&16)==0,"real packet preparation invokes controller synthesis");
  controller.hit(z);check(!controller.controls(z)&&z.getOwner()==connection,"weapon hit returns authority to native owner");
  check(!controller.retainOrAcquire(z),"hit handoff has reacquisition cooldown");
  int hits=port.strikes;controller.beginFrame();check(port.strikes==hits,"released windup cannot commit damage");
  controller.reset();check(!controller.controls(z),"shutdown clears sessions");
  controller.install(port);controller.beginFrame();z.target=viewer;z.lastChangeOwner=0;
  check(controller.retainOrAcquire(z),"closer NPC takes priority over farther existing player target");
  port.victim=new Victim(port.victim.id(),port.victim.body(),5.1f,.5f,0);
  controller.reset();controller.install(port);controller.beginFrame();z.target=null;z.lastChangeOwner=0;
  viewer.x=z.x;viewer.y=z.y;check(!controller.retainOrAcquire(z),"closer real player prevents acquisition");viewer.x=10;
  check(controller.retainOrAcquire(z),"reacquire after fresh controller lifecycle");
  z.id++;check(!controller.controls(z),"pooled incarnation cannot inherit authority");controller.beginFrame();
  controller.reset();controller.install(port);controller.beginFrame();
  var touching=body(30,.6f,.5f);viewer.x=.7f;viewer.y=.5f;
  NetworkZombieManager.getInstance().moveZombie(touching,connection,viewer);
  check(controller.retainOrAcquire(touching),"visible NPC at 4.5 tiles overrides recent owner and closer spectator");
  controller.beginFrame();check(controller.controls(touching),"nearby player cannot immediately undo close-range NPC control");
  controller.hit(touching);check(!controller.engage(touching,port.victim.id()),"combat intent does not bypass native hit-reaction cooldown");
  controller.reset();controller.install(port);controller.beginFrame();
  var provoked=body(31,.5f,.5f);viewer.x=.6f;
  NetworkZombieManager.getInstance().moveZombie(provoked,connection,viewer);
  check(!controller.retainOrAcquire(provoked),"distant unprovoked NPC retains ordinary player priority");
  check(controller.engage(provoked,port.victim.id()) && provoked.getOwner()==null,
   "validated NPC combat intent immediately takes authority despite observer and owner cooldown");
  controller.beginFrame();check(controller.controls(provoked),"combat ownership persists after acquisition frame");
  provoked.grapple.other=new Object();controller.beginFrame();
  check(!controller.controls(provoked),"native grapple still preempts provoked control");
  check(!controller.engage(body(32,.5f,.5f),(short)999),"unknown victim cannot provoke acquisition");
  controller.reset();controller.install(port);controller.beginFrame();
  var stairBody=body(33,8.95f,6.5f);var stairVictim=victim(12,6.5f);port.victim=stairVictim;
  cell.getGridSquare(9,6,0).stairs=true;
  var stairAdapter=new ServerZombieNativeAdapter(stairBody,1);
  var stairSession=new ServerZombieSession(stairAdapter,stairVictim,0);
  check(stairSession.update(stairVictim,port,100_000_000L).equals("native_stairs"),"approaching stairs yields before ten-second stall");
  check(stairBody.x<9,"standing integrator never projects onto stairs");
  check(controller.engage(stairBody,stairVictim.id()),"flat stair approach can initially acquire NPC target");
  zombie.pathfind.PolygonalMap2.instance.blocked=true;controller.beginFrame();
  var stairPath=new zombie.pathfind.Path();stairPath.addNode(8.95f,6.5f,0);stairPath.addNode(9.5f,6.5f,0);
  zombie.pathfind.nativeCode.PathfindNative.instance.complete(stairPath);
  zombie.pathfind.PolygonalMap2.instance.blocked=false;controller.beginFrame();
  check(!controller.controls(stairBody)&&stairBody.getOwner()==connection&&stairBody.target==stairVictim.body(),
   "native stair handoff restores client authority and preserves NPC target");
  var onStairs=body(34,9.5f,6.5f);
  check(!controller.engage(onStairs,stairVictim.id()),"stairs cannot be immediately reacquired by combat handoff");
  cell.getGridSquare(9,6,0).stairs=false;
  controller.reset();GameServer.server=false;check(!controller.controls(z),"client execution cannot claim authority");
  var service=zombie.pathfind.nativeCode.PathfindNative.instance;
  var pathBody=body(25,15.5f,5.5f);var request=new ServerZombiePath(pathBody);
  request.request(18.5f,5.5f);check(request.pending(),"native request remains pending until main-thread callback");
  int requestCount=service.requests;request.request(18.5f,5.5f);
  check(service.requests==requestCount,"pending path request is not restarted each refresh");
  var fractional=new zombie.pathfind.Path();fractional.addNode(15.5f,5.5f,0);fractional.addNode(16.25f,5.125f,0);fractional.addNode(18.5f,5.5f,0);
  service.complete(fractional);fractional.nodes.clear();
  check(!request.pending() && request.points().get(1).x()==16.25f && request.points().get(1).y()==5.125f,
   "fractional polygon corners copied before native result pool reuse");
  request.request(18.5f,5.5f);check(service.requests==requestCount,"unchanged target retains completed path without root reversal");
  request.request(20.5f,5.5f);var cancelled=service.jobs.get(request);request.cancel();
  cancelled.finder().Succeeded(path,cancelled.mover());
  check(!request.pending() && request.points().isEmpty(),"cancelled callback cannot revive released route");
  request.request(20.5f,5.5f);var stale=service.jobs.get(request);pathBody.id++;
  stale.finder().Succeeded(path,stale.mover());check(request.points().isEmpty(),"pooled body rejects old path callback");request.cancel();
  zombie.pathfind.nativeCode.PathfindNative.useNativeCode=false;
  var fallback=new ServerZombiePath(pathBody);int fallbackBefore=zombie.pathfind.PolygonalMap2.instance.requests;
  fallback.request(18,5);check(zombie.pathfind.PolygonalMap2.instance.requests==fallbackBefore+1,"configured Java polygon backend is supported");
  var failedJob=service.jobs.get(fallback);failedJob.finder().Failed(failedJob.mover());
  check(!fallback.pending() && fallback.points().isEmpty(),"solver failure clears pending state without direct-line fallback");
  fallback.request(18,5);var wrongFloor=new zombie.pathfind.Path();wrongFloor.addNode(18,5,1);
  service.complete(wrongFloor);check(!fallback.pending() && fallback.points().isEmpty(),"unsupported floor route is rejected");
  fallback.cancel();zombie.pathfind.nativeCode.PathfindNative.useNativeCode=true;
  var normal=body(40,10.5f,1.5f);var metrics=new ServerZombiePace();float nativePace=ServerZombiePace.nativeSpeed(normal);
  metrics.sample(nativePace*1.1f,nativePace,200_000_000L);
  check(!metrics.usable(200_000_000L),"single owner correction cannot establish pace");
  metrics.sample(nativePace*1.1f,nativePace,400_000_000L);metrics.sample(nativePace*1.1f,nativePace,600_000_000L);
  check(metrics.usable(600_000_000L)&&Math.abs(metrics.speed(normal,600_000_000L)-nativePace*1.1f)<.001,"three consistent owner root-motion samples calibrate native pace");
  check(Math.abs(metrics.speed(normal,3_000_000_001L)-nativePace)<.001,"stale calibration falls back to native pace");
  metrics.sample(1.7f,nativePace,800_000_000L);check(!metrics.usable(800_000_000L),"correction burst rejected rather than permanent acceleration");
  metrics.sample(.1f,nativePace,1_000_000_000L);check(!metrics.usable(1_000_000_000L),"obstruction creep rejected rather than permanent slow gait");
  var budgetAdapter=new ServerZombieNativeAdapter(normal,1);var budgetVictim=victim(14.5f,1.5f);
  cell.getGridSquare(11,1,0).free=false;budgetAdapter.route(budgetVictim);
  var smallNodes=new zombie.pathfind.Path();smallNodes.addNode(10.5f,1.5f,0);
  for(int n=1;n<=8;n++)smallNodes.addNode(10.5f,1.5f-n*.04f,0);
  service.complete(smallNodes);check(budgetAdapter.move(budgetVictim,.15f),"short-node route advances");
  check(Math.abs(normal.y-1.35f)<.001,"one frame spends full movement budget across polygon nodes");
  cell.getGridSquare(11,1,0).free=true;
  var captured=body(50,10.5f,3.5f);captured.walkType="2";captured.speedMod=1;
  check(ServerZombiePace.measured(captured),"captured fast shambler selects measured gait");
  var captureMetrics=new ServerZombiePace();
  for(int n=1;n<=3;n++)captureMetrics.sample(1.0475f*1.2f,1.0475f,n*200_000_000L);
  check(captureMetrics.usable(600_000_000L) && Math.abs(captureMetrics.speed(captured,600_000_000L)-1.0475f)<.0001,
   "owner receipt timing cannot rescale captured root motion");
  var measuredAdapter=new ServerZombieNativeAdapter(captured,ServerZombiePace.WALK_2_SPEED);
  var distant=victim(25.5f,3.5f);
  for(int n=0;n<10;n++)measuredAdapter.move(distant,.1f);
  measuredAdapter.present(true,false);measuredAdapter.packet(prediction);
  check(Math.abs(captured.x-11.5475f)<.0002,"one second walking matches captured distance");
  check(prediction.realState==NetworkVariables.ZombieState.WalkToward && Math.abs(prediction.x-captured.x-.52375f)<.001,
   "measured walking uses fixed 500ms prediction independent of server frame time");
  var fasterTicks=body(51,10.5f,2.5f);fasterTicks.walkType="2";fasterTicks.speedMod=1;
  var fasterAdapter=new ServerZombieNativeAdapter(fasterTicks,ServerZombiePace.WALK_2_SPEED);
  for(int n=0;n<20;n++)fasterAdapter.move(victim(25.5f,2.5f),.05f);
  check(Math.abs(fasterTicks.x-captured.x)<.0002,"measured one-second displacement is independent of frame rate");
  float walked=captured.x;var close=victim(captured.x+3,captured.y);
  measuredAdapter.move(close,.1f);measuredAdapter.present(true,false);measuredAdapter.packet(prediction);
  check(Math.abs(captured.x-walked-.15227f)<.0001 && prediction.realState==NetworkVariables.ZombieState.Lunge,
   "clear close pursuit uses captured lunge displacement and native lunge presentation");
  check((prediction.booleanVariables&4)==0,"measured lunge does not become a sprinter");
  float lunged=captured.x;measuredAdapter.move(distant,.05f);measuredAdapter.packet(prediction);
  check(Math.abs(captured.x-lunged-.052375f)<.0001 && prediction.realState==NetworkVariables.ZombieState.WalkToward,
   "long pursuit returns to measured walking with elapsed-time scaling");
  check(measuredAdapter.relayDue(0),"acquisition presentation publishes immediately");
  check(!measuredAdapter.relayDue(199_999_999L),"steady motion does not publish before 200ms");
  check(measuredAdapter.relayDue(200_000_000L),"steady motion publishes at 200ms");
  check(!measuredAdapter.relayDue(200_000_000L),"same frame cannot enqueue duplicate relay");
  measuredAdapter.present(false,true);
  check(measuredAdapter.relayDue(210_000_000L),"attack starts without waiting for cadence");
  measuredAdapter.present(false,false);
  check(measuredAdapter.relayDue(220_000_000L),"attack completion publishes immediately");
  measuredAdapter.move(distant,.05f);measuredAdapter.present(true,false);
  check(measuredAdapter.relayDue(230_000_000L),"movement restart publishes immediately");
  measuredAdapter.move(victim(captured.x,7.5f),.05f);
  check(measuredAdapter.relayDue(240_000_000L),"sharp turn publishes immediately");
  check(measuredAdapter.relayDue(5_000_000_000L) && !measuredAdapter.relayDue(5_000_000_001L),
   "delayed frame sends current presentation once without catch-up burst");
  captured.inactive=true;check(!ServerZombiePace.measured(captured),"inactive gait remains unsampled");captured.inactive=false;
  captured.speedMod=.8f;check(!ServerZombiePace.measured(captured),"different speedMod keeps fallback");captured.speedMod=1;
  captured.sprinter=true;check(!ServerZombiePace.measured(captured),"native sprint override keeps fallback");captured.sprinter=false;
  captured.walkType="3";check(!ServerZombiePace.measured(captured),"unsampled walk variant keeps fallback");
  System.out.println("Passed "+checks+" server-zombie controller assertions.");
 }
}
"""

def main():
    # These integration guards complement the executable native manager/preparation tests.
    packer = (SRC / "zombie/popman/NetworkZombiePacker.java").read_text(encoding="utf-8")
    assert packer.index("if (zombie.getOwner() != connection)") < packer.index("this.applyZombie(zombie)")
    assert "ServerZombieController.getInstance().addRelays(this.zombiesProcessing)" in packer
    assert packer.count("ServerZombieController.getInstance().controls(zombie)") == 3
    apply_body = packer[packer.index("private void applyZombie("):]
    lookup = apply_body.split("IsoGridSquare g =", 1)[1].split(";", 1)[0].replace("this.packet", "packet")
    harness = HARNESS.replace(" public static void main", " static IsoGridSquare incomingSquare(zombie.network.packets.character.ZombiePacket packet){return " + lookup + ";}\n public static void main")
    native = (SRC / "zombie/characters/IsoGameCharacter.java").read_text(encoding="utf-8")
    hit = native[native.index("public void hitConsequences("):]
    assert hit.index("ServerZombieController.getInstance().hit(zombie)") < hit.index("applyDamage(this, damage)")
    with tempfile.TemporaryDirectory(prefix="apocbr-zombie-control-") as directory:
        work = Path(directory)
        files = STUBS | {"zombie/apocalipse/zombies/ControlTest.java": harness}
        sources = []
        for relative, content in files.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources.extend((SRC / "zombie/apocalipse/zombies").glob("*.java"))
        sources.extend(SRC / relative for relative in [
            "zombie/apocalipse/humans/movement/MovementGrid.java",
            "zombie/apocalipse/humans/movement/DestinationRoutePlanner.java",
            "zombie/popman/NetworkZombieManager.java", "zombie/popman/ZombieAuthCoverage.java",
            "zombie/popman/ZombieOwnershipIndex.java", "zombie/popman/ServerZombiePacketPreparation.java",
        ])
        args = work / "javac.args"
        args.write_text("\n".join('"'+str(p).replace("\\", "/")+'"' for p in sources), encoding="utf-8")
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@"+str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.apocalipse.zombies.ControlTest"], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-Dapocbr.npc.zombieControl=false", "-cp", str(output), "zombie.apocalipse.zombies.ControlTest", "disabled"], check=True)

if __name__ == "__main__":
    main()
