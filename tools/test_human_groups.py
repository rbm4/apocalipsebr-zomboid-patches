"""42.22.1 shared patrol, segmented navigation, group retaliation and health capacity production fixtures."""
from pathlib import Path
import subprocess
import tempfile
import sys
sys.dont_write_bytecode=True
from test_human_healing import STUBS, ROOT, SOURCE

FIXTURES=dict(STUBS)
FIXTURES['zombie/characters/IsoGameCharacter.java']='package zombie.characters; public class IsoGameCharacter {}'
FIXTURES['zombie/characters/IsoZombie.java']='''package zombie.characters;public class IsoZombie extends IsoGameCharacter {
 public float x,y;public int z;public boolean dead;public short id;public int instance;
 public float getX(){return x;}public float getY(){return y;}public float getZ(){return z;}public int getZi(){return z;}public boolean isDead(){return dead;}}
'''
FIXTURES['zombie/apocalipse/humans/brain/HumanCombatTactics.java']='package zombie.apocalipse.humans.brain;public class HumanCombatTactics {public static final double IMMEDIATE_RANGE=1.5;}'
FIXTURES['zombie/apocalipse/zombies/ServerZombieController.java']='''package zombie.apocalipse.zombies;public class ServerZombieController {
 static final ServerZombieController INSTANCE=new ServerZombieController();public static ServerZombieController getInstance(){return INSTANCE;}
 public void engage(zombie.characters.IsoZombie target,short player){}}'''
FIXTURES['zombie/apocalipse/humans/ServerHumanWeaponBackend.java']='''package zombie.apocalipse.humans;import zombie.apocalipse.humans.brain.*;
class ServerHumanWeaponBackend implements ServerHumanCombat.Backend {ServerHumanWeaponBackend(ServerHuman human){}
 public HumanCombat.Execution begin(zombie.characters.IsoPlayer a,zombie.characters.IsoZombie z,long id,HumanCombat.Request r,long now){return HumanCombat.finished(new HumanCombat.Result(HumanCombat.Status.COMPLETED,HumanCombat.Reason.NONE));}}'''
FIXTURES['zombie/characters/IsoPlayer.java']=STUBS['zombie/characters/IsoPlayer.java'].replace('public class IsoPlayer {','public class IsoPlayer extends IsoGameCharacter {public short onlineId=768;public short getOnlineID(){return onlineId;}public int getXi(){return (int)Math.floor(x);}public int getYi(){return (int)Math.floor(y);}')
FIXTURES['zombie/network/GameServer.java']='package zombie.network;public class GameServer {public static boolean server=true;}'
FIXTURES['zombie/apocalipse/humans/ServerHumanFactory.java']='''package zombie.apocalipse.humans;class ServerHumanFactory {
 static final int FIRST_ID=768,LAST_ID=1023;static ServerHuman target;static int queries;
 static ServerHuman tradingHuman(short id){queries++;return target!=null&&target.player.getOnlineID()==id?target:null;}}'''
FIXTURES['zombie/apocalipse/humans/brain/HumanActuator.java']=STUBS['zombie/apocalipse/humans/brain/HumanActuator.java'].replace(
 ' enum MotionStatus', ''' default boolean startMovement(zombie.apocalipse.humans.walking.MovementRequest request,long now){return false;}
 default boolean face(HumanObservation.Identity visitor){return true;}default boolean startEmote(String name){return true;}
 default void stopEmote(){}default boolean say(String text){return true;}default boolean returnHome(){return true;}
 enum MotionStatus''')
FIXTURES['zombie/apocalipse/humans/brain/HumanActivityController.java']='''package zombie.apocalipse.humans.brain;
public class HumanActivityController {public enum Mode{AUTONOMOUS,DIALOGUE,TRADE,STOPPED}public Mode mode=Mode.AUTONOMOUS;
 public HumanBehavior behavior;public HumanActivityController(HumanBehavior value){behavior=value;}public HumanBehavior behavior(){return behavior;}public Mode mode(){return mode;}}'''
FIXTURES['zombie/apocalipse/humans/HumanLog.java']='''package zombie.apocalipse.humans;public class HumanLog {
 public enum Level{DEBUG}public static void log(Level level,java.util.function.Supplier<String> text){text.get();}}'''
FIXTURES['zombie/apocalipse/humans/ServerHumanRetaliation.java']='''package zombie.apocalipse.humans;import zombie.apocalipse.humans.brain.HumanObservation;
class ServerHumanRetaliation {static Active module(ServerHuman actor){return actor.retaliation;}
 static class Active {int attacks;HumanObservation.Identity aggressor;HumanObservation.Identity aggressor(){return aggressor;}void attacked(HumanObservation.Identity value,long now){attacks++;aggressor=value;}}}'''
FIXTURES['zombie/apocalipse/humans/ServerHuman.java']='''package zombie.apocalipse.humans;
import java.util.*;import zombie.characters.IsoPlayer;import zombie.apocalipse.humans.groups.*;import zombie.apocalipse.humans.state.*;import zombie.apocalipse.humans.brain.*;import zombie.apocalipse.humans.walking.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
class ServerHuman {
 final Speech speech=new Speech();static class Speech {int calls;void state(zombie.apocalipse.humans.speech.SpeechEvent event,long now){calls++;}}
 record Definition(String key,HumanGroupMembership group){int z(){return 0;}}
 final Definition definition;final String incarnation=UUID.randomUUID().toString();final IsoPlayer player=new IsoPlayer();final Store state=new Store();
 final Profile profile;final HumanActivityController activity;final Territory territory=new Territory();final GroupPatrolModule module=new GroupPatrolModule();
 final ServerHumanRetaliation.Active retaliation=new ServerHumanRetaliation.Active();
 Healing healing;static class Healing {boolean active;boolean recovering(){return active;}}
 ServerHumanSquadAssist squadAssist;final Perception perception=new Perception();final Movement movement=new Movement();final Walking walking=new Walking();
 ServerHuman(String key,String group,int slot,List<WalkingPatternSpec.Point> route){
  definition=new Definition(key,new HumanGroupMembership(group,slot));profile=new Profile(route);
  activity=new HumanActivityController(new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("group-patrol",1000,100,module))));
  player.x=9857.5f;player.y=2419.5f;
 }
 static class Store {HumanState value=HumanState.INITIAL;HumanState snapshot(){return value;}}
 static class Profile {double capacity=1;double healthMultiplier(){return capacity;}final List<WalkingPatternSpec> patterns;Profile(List<WalkingPatternSpec.Point> points){patterns=List.of(new WalkingPatternSpec("background","waypoint-circuit",64,points,new WalkingPatternSpec.Area(9788,2395,9894,2472)));}List<WalkingPatternSpec> walkingPatterns(){return patterns;}}
 static class Territory {boolean inside=true;boolean contains(float x,float y,float z){return inside;}}
 static class Perception {
  boolean supportVisible=true;boolean visible(zombie.characters.IsoGameCharacter body){return supportVisible;}
  HumanSenses.Frame frame=HumanSenses.Frame.EMPTY;final Map<HumanSenses.Identity,zombie.characters.IsoZombie> known=new HashMap<>();int hints;
  HumanSenses.Frame awarenessFrame;HumanSenses.Frame snapshot(){return frame;}HumanSenses.Frame awareness(){return awarenessFrame==null?frame:awarenessFrame;}
  zombie.characters.IsoZombie trackedZombie(HumanSenses.Identity id){var z=known.get(id);return z!=null&&!z.dead?z:null;}
  zombie.characters.IsoZombie resolveZombie(HumanSenses.Identity id){return frame.contacts().stream().anyMatch(c->c.identity().equals(id)&&c.visible())?trackedZombie(id):null;}
  void hintZombie(zombie.characters.IsoZombie z){hints++;}
 }
 static class Square {}
 static class Movement {
  record Failure(zombie.apocalipse.humans.movement.MovementGrid.Tile from,zombie.apocalipse.humans.movement.MovementGrid.Tile to,String reason){}
  Failure lastFailure(){return null;}zombie.apocalipse.humans.movement.MovementGrid.Tile nextTile(){return null;}
  boolean bodyClear(float a,float b,float c,float d){return true;}
  zombie.apocalipse.humans.movement.MovementModifiers.Resolved resolved(){return zombie.apocalipse.humans.movement.MovementModifiers.resolve(zombie.apocalipse.humans.movement.MovementModifiers.Pace.RUN,zombie.apocalipse.humans.movement.MovementModifiers.Conditions.NORMAL,zombie.apocalipse.humans.movement.MovementModifiers.Settings.DEFAULT,0,0);}
  List<zombie.apocalipse.humans.movement.MovementGrid.Tile> lastRoute=List.of();
  int starts,stops;boolean loaded=true;HumanActuator.MotionStatus result=HumanActuator.MotionStatus.RUNNING;
  boolean available(){return loaded;}Square square(int x,int y){return loaded?new Square():null;}
  boolean traversing(){return false;}
  boolean walkable(zombie.apocalipse.humans.movement.MovementGrid.Tile t){return loaded;}
  boolean freeOfBlockingBodies(Square s){return true;}
  boolean connected(zombie.apocalipse.humans.movement.MovementGrid.Tile a,zombie.apocalipse.humans.movement.MovementGrid.Tile b){return true;}
  int traversalCost(zombie.apocalipse.humans.movement.MovementGrid.Tile a,zombie.apocalipse.humans.movement.MovementGrid.Tile b){return 1;}
  boolean start(List<zombie.apocalipse.humans.movement.MovementGrid.Tile> r,zombie.apocalipse.humans.movement.MovementGrid g,zombie.apocalipse.humans.movement.MovementModifiers.Pace p,long n){starts++;lastRoute=List.copyOf(r);return !r.isEmpty();}
  HumanActuator.MotionStatus update(long now){return result;}void stop(){stops++;}
 }
 static class Walking {int stops;boolean loaded=true;void stop(){stops++;}
  zombie.apocalipse.humans.movement.MovementGrid scope(String slot){return new zombie.apocalipse.humans.movement.MovementGrid(){
   public boolean walkable(Tile t){return loaded&&t.x()>=9788&&t.x()<=9894&&t.y()>=2395&&t.y()<=2472;}
   public boolean connected(Tile a,Tile b){return walkable(a)&&walkable(b);}};}}
}'''

HARNESS=r'''
package zombie.apocalipse.humans;
import java.util.*;
import zombie.apocalipse.humans.groups.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.state.*;
import zombie.apocalipse.humans.walking.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;
import zombie.apocalipse.humans.movement.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
public class GroupTest {
 static int checks;static final long S=1_000_000_000L;
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static List<Point> route(){return List.of(new Point(9868,2419,0),new Point(9888,2409,0),new Point(9886,2450,0),new Point(9850,2449,0),new Point(9794,2463,0),new Point(9818,2466,0),new Point(9846,2442,0),new Point(9851,2401,0));}
 static HumanObservation observe(long now){return new HumanObservation(now,List.of(),true);}
 static GroupPatrolCoordinator.Member at(String key,int slot,Point p,boolean ready){return new GroupPatrolCoordinator.Member(key,key,slot,p.x()+.5f,p.y()+.5f,p.z(),ready);}
 static void coordinator(){
  var c=new GroupPatrolCoordinator(route(),2,2,()->15000);var lead=at("hunk",0,c.destination(0),true);
  var follower=at("vector",1,new Point(9857,2419,0),true);
  c.update(0,List.of(lead,follower));check(c.waypoint()==0&&c.phase()==GroupPatrolCoordinator.Phase.REGROUPING,"leader waits for its group");
  c.update(21*S,List.of(lead,follower));check(c.waypoint()==0&&c.phase()==GroupPatrolCoordinator.Phase.STALLED,"blocked member reports a stall without silently skipping patrol points");
  var arrived=List.of(lead,at("vector",1,c.destination(1),true));c.update(22*S,arrived);
  check(c.waypoint()==0&&c.phase()==GroupPatrolCoordinator.Phase.DWELLING,"shared pause starts after all arrive");
  c.update(37*S,arrived);check(c.waypoint()==1,"shared waypoint advances once after the pause");
  check(c.arrived(at("vector",1,route().get(1),true)),"center fallback satisfies arrival even when rounded lateral offset exceeds two tiles");
  int index=c.waypoint();c.update(38*S,List.of(at("hunk",0,c.destination(0),false)));check(c.waypoint()==index,"all-combat group retains PATROL progress");
  c.update(39*S,List.of(at("hunk",0,c.destination(0),true),at("vector",1,new Point(9857,2419,0),false)));
  check(c.waypoint()==index,"a recovering member is not abandoned");
  check(!c.destination(0).equals(c.destination(1))&&!c.destination(1).equals(c.destination(2)),"separate formation slots");
  for(int i=0;i<7;i++){var members=List.of(at("a",0,c.destination(0),true),at("b",1,c.destination(1),true),at("c",2,c.destination(2),true));c.update((40+i*16)*S,members);c.update((55+i*16)*S,members);}
  check(c.waypoint()==0,"eighth point loops to first");
  try{c.update(40*S,List.of(at("a",0,c.destination(0),true),at("b",0,c.destination(0),true)));throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
 }
 static void dwellTiming(){
  var draws=new java.util.concurrent.atomic.AtomicInteger();
  var c=new GroupPatrolCoordinator(route(),2,2,()->draws.incrementAndGet()==1?15000:45000);
  var members=List.of(at("a",0,c.destination(0),true),at("b",1,c.destination(1),true));
  c.update(0,members);c.update(14999_000_000L,members);
  check(c.waypoint()==0&&draws.get()==1&&c.goal(0,true).dwelling(),"minimum dwell is shared, sampled once and holds current waypoint");
  c.update(15*S,members);check(c.waypoint()==1&&!c.goal(0,true).dwelling(),"15-second boundary releases the group");
  members=List.of(at("a",0,c.destination(0),true),at("b",1,c.destination(1),true));c.update(16*S,members);
  c.update(60999_000_000L,members);check(c.waypoint()==1&&draws.get()==2,"fresh maximum dwell applies at the next waypoint");
  c.update(61*S,members);check(c.waypoint()==2,"45-second boundary releases once");
  c=new GroupPatrolCoordinator(route(),2,2,()->15000);members=List.of(at("a",0,c.destination(0),true),at("b",1,c.destination(1),true));
  c.update(0,members);c.update(5*S,members);
  c.update(6*S,List.of(at("a",0,c.destination(0),true),at("b",1,c.destination(1),false)));
  c.update(60*S,List.of(at("a",0,c.destination(0),false),at("b",1,c.destination(1),false)));
  c.update(65*S,members);c.update(74999_000_000L,members);
  check(c.waypoint()==0,"combat or healing interruption does not consume remaining patrol rest");
  c.update(75*S,members);check(c.waypoint()==1,"rest resumes with remaining time after interruption");
  c=new GroupPatrolCoordinator(route(),2,2,()->15000);members=List.of(at("a",0,c.destination(0),true));c.update(0,members);c.update(5*S,members);c.pauseDwell();c.update(60*S,members);
  check(c.waypoint()==0,"missing world observations pause dwell rather than charging an unknown interval");
  c.update(70*S,members);check(c.waypoint()==1,"fresh observations resume dwell without restarting it");
  c=new GroupPatrolCoordinator(route(),2,2,()->15000);members=List.of(at("a",0,c.destination(0),true),at("b",1,c.destination(1),true));c.update(0,members);c.update(5*S,members);
  c.update(6*S,List.of(members.getFirst(),at("b",1,new Point(9857,2419,0),true)));c.update(7*S,members);c.update(21999_000_000L,members);
  check(c.waypoint()==0,"displacement requires regrouping and a fresh full waypoint pause");c.update(22*S,members);check(c.waypoint()==1,"regrouped pause finishes normally");
  try{var invalid=new GroupPatrolCoordinator(route(),2,2,()->45001);invalid.update(0,List.of(at("a",0,invalid.destination(0),true)));throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
 }
 static class Grid implements MovementGrid {
  final Set<Tile> blocked=new HashSet<>();int reads;
  public boolean walkable(Tile t){reads++;return t.x()>=9788&&t.x()<=9894&&t.y()>=2395&&t.y()<=2472&&!blocked.contains(t);}
  public boolean connected(Tile a,Tile b){return walkable(b);}
 }
 static void navigation(){
  var grid=new Grid();var start=new Tile(9850,2449);var goal=new Tile(9794,2463);
  check(DestinationRoutePlanner.plan(grid,start,goal).isEmpty(),"legacy complete-route limit preserved");
  var leg=DestinationRoutePlanner.planLeg(grid,start,goal);
  check(leg.size()>1&&leg.size()<=65&&!leg.getLast().equals(goal),"70-step authored leg yields bounded progress, not false arrival");
  int segments=1;while(!leg.getLast().equals(goal)&&segments<20){leg=DestinationRoutePlanner.planLeg(grid,leg.getLast(),goal);check(leg.size()>1,"each segment progresses");segments++;}
  check(leg.getLast().equals(goal),"long leg eventually reaches exact authored waypoint");
  grid=new Grid();grid.blocked.addAll(List.of(new Tile(9851,2449),new Tile(9849,2449),new Tile(9850,2448),new Tile(9850,2450)));
  check(DestinationRoutePlanner.planLeg(grid,start,goal).isEmpty(),"enclosed actor receives no fabricated path");
 }
 static class Motor implements HumanActuator {
  final ServerHuman actor;List<Tile> route=List.of();int cursor;int starts,cancels;boolean returning;
  Motor(ServerHuman actor){this.actor=actor;}
  public boolean startMovement(MovementRequest request,long now){
   starts++;
   if(request instanceof MovementRequest.Pattern){returning=true;return true;}
   if(request instanceof MovementRequest.Nearby nearby){
    route=DestinationRoutePlanner.planLeg(new Grid(),new Tile((int)actor.player.x,(int)actor.player.y),new Tile(nearby.destination().x(),nearby.destination().y()));cursor=0;return !route.isEmpty();
   }
   var to=(MovementRequest.To)request;returning=to.returnToArea();check(to.partialLeg(),"group movement explicitly requests segmented legs");
   var grid=new zombie.apocalipse.humans.movement.CohesionGrid(new Grid(),to.companions());
   route=DestinationRoutePlanner.planLeg(grid,new Tile((int)actor.player.x,(int)actor.player.y),new Tile(to.destination().x(),to.destination().y()));cursor=0;return !route.isEmpty();
  }
  public MotionStatus updateMovement(long now){
   if(cursor>=route.size())return MotionStatus.SUCCEEDED;
   var next=route.get(cursor++);actor.player.x=next.x()+.5f;actor.player.y=next.y()+.5f;
   return cursor>=route.size()?MotionStatus.SUCCEEDED:MotionStatus.RUNNING;
  }
  public void stopMoving(){cancels++;route=List.of();}
 }
 static void moduleDwell(){
  var actor=new ServerHuman("hold","alpha",0,route());var motor=new Motor(actor);var module=new GroupPatrolModule();
  var point=route().getFirst();var current=new GroupPatrolPort.Observation[]{new GroupPatrolPort.Observation(0,new GroupPatrolCoordinator.Goal(0,point,point,true,false),9857.5f,2419.5f,0,true)};
  module.bind(()->current[0]);module.update(observe(0),motor);int starts=motor.starts;
  current[0]=new GroupPatrolPort.Observation(S/10,new GroupPatrolCoordinator.Goal(0,point,point,true,true),9857.5f,2419.5f,0,true);
  module.update(observe(S/10),motor);module.update(observe(2*S/10),motor);
  check(motor.cancels>0&&motor.route.isEmpty()&&motor.starts==starts,"shared dwell cancels owned movement and prevents corrective walking");
  var next=route().get(1);current[0]=new GroupPatrolPort.Observation(S,new GroupPatrolCoordinator.Goal(1,next,next,true,false),9857.5f,2419.5f,0,true);module.update(observe(S),motor);
  check(motor.starts==starts+1,"next waypoint resumes movement after shared dwell");
 }
 static void adapterAndPatrol(){
  var groups=new ServerHumanGroups(()->15000);var members=new ArrayList<ServerHuman>();var motors=new ArrayList<Motor>();
  for(int i=0;i<3;i++){var h=new ServerHuman("npc"+i,"alpha",i,route());groups.register(h);members.add(h);motors.add(new Motor(h));}
  groups.capture(0);groups.update(0);
  for(int i=0;i<3;i++)members.get(i).module.update(observe(0),motors.get(i));
  check(motors.get(0).starts==1&&motors.get(1).starts==0&&motors.get(2).starts==0,"startup is staggered to separate shared spawn occupants");
  var initialX=members.get(1).player.x;
  for(int tick=1;tick<5000;tick++){
   long now=tick*S/10;groups.capture(now);groups.update(now);
   for(int i=0;i<3;i++)members.get(i).module.update(observe(now),motors.get(i));
  }
  check(members.get(1).player.x!=initialX&&motors.stream().allMatch(m->m.starts>8),"three members execute multiple coordinated patrol legs");
  long stale=501*S;int before=motors.get(0).starts;members.get(0).module.update(observe(stale),motors.get(0));
  check(motors.get(0).starts==before,"stale world snapshots cannot start movement");
  var isolated=new ServerHuman("other","beta",0,route());groups.register(isolated);
  var attacker=new HumanObservation.Identity((short)12,41);groups.attacked(members.get(0),attacker,stale);
  check(members.get(1).retaliation.attacks==1&&members.get(2).retaliation.attacks==1&&isolated.retaliation.attacks==0,"one authenticated group alert reaches allies only");
  check(members.get(1).retaliation.aggressor.equals(attacker),"shared aggressor retains player incarnation identity");
  check(members.get(0).retaliation.attacks==0,"victim is not assigned twice by group fanout");
  try{groups.register(new ServerHuman("duplicate","alpha",0,route()));throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  var dead=members.get(2);dead.state.value=HumanStateReducer.reduce(dead.state.value,new HumanMutation.Kill());groups.attacked(members.get(0),attacker,stale);
  check(dead.retaliation.attacks==1,"dead allies do not receive aggression");groups.remove(dead);
  int stopped=motors.get(2).starts;dead.module.update(observe(stale),motors.get(2));check(motors.get(2).starts==stopped,"retired incarnation cannot actuate through an old port");
  members.get(0).territory.inside=false;members.get(0).player.x=9931.5f;members.get(0).player.y=2510.5f;
  groups.capture(stale);groups.update(stale);members.get(0).module.interrupt(stale,motors.get(0));members.get(0).module.update(observe(stale),motors.get(0));
  check(motors.get(0).route.isEmpty(),"an interrupted squadmate holds patrol instead of sending others away");
  members.get(1).retaliation.aggressor=null;groups.capture(stale+S/10);groups.update(stale+S/10);members.get(0).module.update(observe(stale+S/10),motors.get(0));
  check(motors.get(0).returning,"out-of-area member uses the shared return destination");
  int count=motors.get(1).starts;members.get(1).module.suspend(stale,motors.get(1));members.get(1).module.update(observe(stale),motors.get(1));
  check(motors.get(1).starts==count,"exclusive interaction suspension prevents patrol actuation");
 }
 static GroupPatrolPort port(ServerHuman actor)throws Exception{var field=GroupPatrolModule.class.getDeclaredField("port");field.setAccessible(true);return (GroupPatrolPort)field.get(actor.module);}
 static void cohesion()throws Exception{
  var closed=new ArrayList<>(route());closed.add(closed.getFirst());var c=new GroupPatrolCoordinator(closed,2,2,()->15000);
  check(!c.destination(0).equals(c.destination(1)),"repeated closing point does not collapse first-leg formation offsets");
  var policy=new GroupCohesionCoordinator();var target=new Point(9894,2472,0);
  var outside=List.of(new GroupCohesionCoordinator.Member(at("a",0,new Point(9931,2510,0),true),false),new GroupCohesionCoordinator.Member(at("b",1,new Point(9931,2510,0),true),false));
  policy.update(outside,target);long revision=policy.revision();check(policy.mode()==GroupCohesionCoordinator.Mode.RETURN&&policy.rally().equals(target),"one shared re-entry goal");
  policy.update(outside,target);check(policy.revision()==revision,"stable rally does not restart routes every tick");
  var split=List.of(new GroupCohesionCoordinator.Member(at("a",0,target,true),true),new GroupCohesionCoordinator.Member(at("b",1,new Point(9860,2440,0),true),true));
  policy.update(split,target);check(policy.mode()==GroupCohesionCoordinator.Mode.RETURN,"individual area entry cannot release shared recovery");
  var arrived=List.of(new GroupCohesionCoordinator.Member(at("a",0,target,true),true),new GroupCohesionCoordinator.Member(at("b",1,new Point(9892,2472,0),true),true));
  policy.update(arrived,target);check(policy.mode()==GroupCohesionCoordinator.Mode.PATROL,"whole roster rejoins before patrol resumes");
  policy.update(split,new Point(9877,2456,0));check(policy.mode()==GroupCohesionCoordinator.Mode.REGROUP,"separation triggers shared regroup");
  policy.update(List.of(arrived.get(0),new GroupCohesionCoordinator.Member(at("b",1,target,false),true)),null);
  check(policy.mode()==GroupCohesionCoordinator.Mode.HOLD,"bandaging/exclusive interruption holds remaining patrol only");
  var edgeActor=new ServerHuman("edge","edge",0,closed);var edgeMotor=new Motor(edgeActor);var edgeModule=new GroupPatrolModule();
  edgeModule.bind(()->new GroupPatrolPort.Observation(0,new GroupPatrolCoordinator.Goal(0,target,target,true,false),9894.5f,2473.5f,0,false,1,true,false,List.of()));
  edgeModule.update(observe(0),edgeMotor);check(edgeMotor.starts==1,"arrival tolerance outside the rectangle cannot prematurely stop re-entry");
  var dwell=new GroupPatrolCoordinator(closed,2,2,()->15000);var parked=List.of(at("one",0,dwell.destination(0),true));
  dwell.update(0,parked);dwell.update(14*S,parked);dwell.displaced();dwell.update(20*S,parked);dwell.update(21*S,parked);
  check(dwell.waypoint()==0,"regroup displacement resets dwell without advancing the waypoint");
  var weighted=new zombie.apocalipse.humans.movement.CohesionGrid(new Grid(),List.of(new Point(9860,2440,0)));
  check(weighted.traversalCost(new Tile(9860,2440),new Tile(9861,2440))<weighted.traversalCost(new Tile(9880,2440),new Tile(9881,2440)),"soft cost favors paths near companions");
  check(weighted.walkable(new Tile(9890,2440)),"soft cohesion is not a hard leash that fabricates blocked geometry");
  var groups=new ServerHumanGroups(()->15000);var actors=new ArrayList<ServerHuman>();
  for(int i=0;i<3;i++){var a=new ServerHuman("recover"+i,"recovery",i,closed);a.player.x=9931.5f;a.player.y=2510.5f;a.territory.inside=false;groups.register(a);actors.add(a);}
  groups.capture(0);groups.update(0);var shared=port(actors.getFirst()).observe().goal().center();
  check(actors.stream().map(a->{try{return port(a).observe().goal().destination();}catch(Exception e){throw new RuntimeException(e);}}).distinct().count()==3,"boundary formation clamps slot destinations without collapsing everyone onto one point");
  for(var a:actors){var view=port(a).observe();check(view.regroup()&&view.goal().center().equals(shared)&&view.companions().size()==2,"native adapter publishes same rally and detached peers to all members");
   var motor=new Motor(a);a.module.update(observe(2*S/10),motor);check(motor.returning||a.definition.group().slot()!=0,"recovery emits explicit shared To request, not personal background pattern");}
  for(var a:actors)a.walking.loaded=false;groups.capture(S);groups.update(S);
  for(var a:actors)check(!port(a).observe().goal().available(),"unloaded shared rally pauses everyone without divergent home fallback");
 }
 static void recoveryCamp()throws Exception{
  var groups=new ServerHumanGroups(()->15000);var actors=new ArrayList<ServerHuman>();
  for(int i=0;i<3;i++){var a=new ServerHuman("care"+i,"care",i,route());a.player.x=9850.5f;a.player.y=2449.5f;groups.register(a);actors.add(a);}
  var patient=actors.get(2);patient.healing=new ServerHuman.Healing();patient.healing.active=true;
  groups.capture(0);groups.update(0);var center=port(patient).observe().goal().center();int waypoint=port(patient).observe().goal().waypoint();
  check(port(patient).observe().recovery()&&!port(patient).observe().hold(),"recovering patient replaces whole-squad HOLD with local camp");
  check(Math.abs(port(patient).observe().goal().destination().x()-center.x())<=1,"patient receives small local steps");
  var first=port(actors.get(0)).observe().goal().destination();
  var motor=new Motor(actors.get(0));actors.get(0).module.update(observe(0),motor);
  check(motor.starts==1&&!motor.route.isEmpty(),"healthy helper starts roaming rather than waiting for full healing");
  groups.capture(9*S);groups.update(9*S);actors.get(0).module.update(observe(9*S),motor);
  check(port(actors.get(0)).observe().goal().dwelling()&&motor.route.isEmpty(),"roam has a short settled pause");
  groups.capture(10*S);groups.update(10*S);var next=port(actors.get(0)).observe();
  check(!next.goal().destination().equals(first)&&next.goal().waypoint()==waypoint,"next short walk changes destination without advancing patrol");
  check(next.revision()<0,"camp revision namespace cannot alias normal patrol state");
  actors.get(0).retaliation.aggressor=new HumanObservation.Identity((short)1,1);groups.capture(11*S);groups.update(11*S);
  check(!port(patient).observe().goal().available(),"authenticated aggression pauses recovery roaming before emergency modules act");
  actors.get(0).retaliation.aggressor=null;
  var other=actors.get(1);other.healing=new ServerHuman.Healing();other.healing.active=true;
  groups.capture(12*S);groups.update(12*S);
  check(port(other).observe().goal().center().equals(center),"another wounded member does not thrash the retained patient anchor");
  groups.remove(patient);groups.capture(13*S);groups.update(13*S);
  check(port(other).observe().recovery(),"patient removal switches to another live recovering member");
  other.healing.active=false;groups.capture(14*S);groups.update(14*S);
  check(!port(other).observe().recovery()&&port(other).observe().goal().waypoint()==waypoint,"all recovered returns to the same shared patrol waypoint");
  var standalone=new GroupRecoveryCoordinator();
  var pose=at("p",0,new Point(9850,2449,0),false);
  standalone.update(0,List.of(new GroupRecoveryCoordinator.Member(pose,true,true)));
  standalone.pause();check(!standalone.goal("p",0,S).available(),"missing member sample pauses camp movement");
  standalone.update(S,List.of(new GroupRecoveryCoordinator.Member(new GroupPatrolCoordinator.Member("p","new-life",0,9860.5f,2449.5f,0,false),true,true)));
  check(standalone.goal("p",0,S).center().x()==9860,"new incarnation cannot inherit an old-life camp anchor");
 }
 static void capacity(){
  check(HumanHealthCapacity.damage(20,1)==20&&HumanHealthCapacity.damage(20,2)==10,"double HP scales native loss once");
  float health=15;health=Math.max(0,health-HumanHealthCapacity.damage(20,2));check(health==5,"near-death hit has a linear loss");
  health=Math.max(0,health-HumanHealthCapacity.damage(20,2));check(health==0,"next lethal hit reaches zero, not asymptotic immortality");
  try{HumanHealthCapacity.damage(10,0);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  var actor=new ServerHuman("capacity","alpha",0,route());actor.profile.capacity=2;ServerHumanFactory.target=actor;
  var nativePart=new NativeBodyPartFixture(actor.player,15);nativePart.ReduceHealth(20);check(nativePart.health==5,"actual native seam scales before clamping");
  nativePart.ReduceHealth(20);check(nativePart.health==0,"actual native seam permits death after double capacity is exhausted");
  int queries=ServerHumanFactory.queries;var real=new zombie.characters.IsoPlayer();real.onlineId=1;var realPart=new NativeBodyPartFixture(real,15);realPart.ReduceHealth(20);
  check(realPart.health==0&&ServerHumanFactory.queries==queries,"real player damage avoids NPC ownership lookup and stays unchanged");
  var zombiePart=new NativeBodyPartFixture(new zombie.characters.IsoGameCharacter(),15);zombiePart.ReduceHealth(20);check(zombiePart.health==0,"non-player native body damage stays unchanged");
  var impostor=new zombie.characters.IsoPlayer();var impostorPart=new NativeBodyPartFixture(impostor,15);impostorPart.ReduceHealth(20);check(impostorPart.health==0,"native identity must match the registered actor reference");
  zombie.network.GameServer.server=false;nativePart=new NativeBodyPartFixture(actor.player,15);nativePart.ReduceHealth(20);check(nativePart.health==0,"client damage processing stays vanilla");zombie.network.GameServer.server=true;
 }
 public static void main(String[] args)throws Exception{coordinator();dwellTiming();navigation();moduleDwell();adapterAndPatrol();cohesion();recoveryCamp();capacity();System.out.println("Group patrol: "+checks+" assertions passed (42.22.1 production classes; engine doubles)");}
}
'''

def main():
    def method(path,signature):
        text=path.read_text(encoding='utf-8');start=text.index(signature);opening=text.index('{',start);depth=1;end=opening+1
        while depth:
            if text[end]=='{':depth+=1
            elif text[end]=='}':depth-=1
            end+=1
        return text[start:end]
    seam=method(ROOT/'42.22.1/src/zombie/characters/BodyDamage/BodyPart.java','public void ReduceHealth(float val)')
    adapter=method(SOURCE/'ServerHumanPlayerDamage.java','public static float nativeDamage(')
    FIXTURES['zombie/apocalipse/humans/NativeBodyPartFixture.java']='''package zombie.apocalipse.humans;
class NativeBodyPartFixture {final zombie.characters.IsoGameCharacter parentChar;float health;
 NativeBodyPartFixture(zombie.characters.IsoGameCharacter owner,float health){parentChar=owner;this.health=health;}
 static class PZMath {static float clamp(float value,float min,float max){return Math.clamp(value,min,max);}}
'''+seam+'}'
    FIXTURES['zombie/apocalipse/humans/ServerHumanPlayerDamage.java']='package zombie.apocalipse.humans;import zombie.characters.IsoPlayer;public class ServerHumanPlayerDamage {'+adapter+'}'
    production=list((SOURCE/'groups').glob('*.java'))+[SOURCE/'ServerHumanGroups.java',SOURCE/'ServerHumanSquadAssist.java',SOURCE/'ServerHumanCombat.java',SOURCE/'speech/SpeechEvent.java',SOURCE/'movement/CohesionGrid.java']
    production += [SOURCE/'brain'/name for name in ('HumanObservation.java','HumanSenses.java','HumanTerritory.java','TacticalIntent.java','HumanModule.java','HumanBehavior.java','HumanModuleRuntime.java','HumanAction.java','HumanActionExecution.java','HumanCombat.java','HumanEngagement.java','EngageZombiesModule.java')]
    production += [SOURCE/'walking'/name for name in ('WalkingPatternSpec.java','MovementRequest.java')]
    production += [SOURCE/'movement'/name for name in ('MovementGrid.java','DestinationRoutePlanner.java','CombatRoutePlanner.java','MovementModifiers.java')]
    production += [SOURCE/'state'/name for name in ('HumanState.java','HumanMutation.java','HumanStateReducer.java','HumanHealthCapacity.java')]
    production += [SOURCE/'survival'/name for name in ('SurvivalSettings.java','SurvivalPort.java','SurvivalModule.java','ThreatAssessment.java','RetreatPlanner.java')]
    production += [SOURCE/'healing'/name for name in ('HealingSettings.java','HumanHealingPort.java','HealingModule.java')]
    with tempfile.TemporaryDirectory(prefix='apocbr-groups-') as folder:
        work=Path(folder);sources=list(production)
        for name,content in {**FIXTURES,'zombie/apocalipse/humans/GroupTest.java':HARNESS}.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
        args=work/'sources.txt';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources))
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-ea','-cp',str(work/'classes'),'zombie.apocalipse.humans.GroupTest'],check=True)

if __name__=='__main__':main()
