"""42.22.1 shared patrol, segmented navigation, group retaliation and health capacity production fixtures."""
from pathlib import Path
import subprocess
import tempfile
import sys
sys.dont_write_bytecode=True
from test_human_healing import STUBS, ROOT, SOURCE

FIXTURES=dict(STUBS)
FIXTURES['zombie/characters/IsoGameCharacter.java']='package zombie.characters; public class IsoGameCharacter {}'
FIXTURES['zombie/characters/IsoPlayer.java']=STUBS['zombie/characters/IsoPlayer.java'].replace('public class IsoPlayer {','public class IsoPlayer extends IsoGameCharacter {public short onlineId=768;public short getOnlineID(){return onlineId;}')
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
class ServerHuman {
 record Definition(String key,HumanGroupMembership group){}
 final Definition definition;final String incarnation=UUID.randomUUID().toString();final IsoPlayer player=new IsoPlayer();final Store state=new Store();
 final Profile profile;final HumanActivityController activity;final Territory territory=new Territory();final GroupPatrolModule module=new GroupPatrolModule();
 final ServerHumanRetaliation.Active retaliation=new ServerHumanRetaliation.Active();
 ServerHuman(String key,String group,int slot,List<WalkingPatternSpec.Point> route){
  definition=new Definition(key,new HumanGroupMembership(group,slot));profile=new Profile(route);
  activity=new HumanActivityController(new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("group-patrol",1000,100,module))));
  player.x=9857.5f;player.y=2419.5f;
 }
 static class Store {HumanState value=HumanState.INITIAL;HumanState snapshot(){return value;}}
 static class Profile {double capacity=1;double healthMultiplier(){return capacity;}final List<WalkingPatternSpec> patterns;Profile(List<WalkingPatternSpec.Point> points){patterns=List.of(new WalkingPatternSpec("background","waypoint-circuit",64,points,new WalkingPatternSpec.Area(9788,2395,9894,2472)));}List<WalkingPatternSpec> walkingPatterns(){return patterns;}}
 static class Territory {boolean inside=true;boolean contains(float x,float y,float z){return inside;}}
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
  var c=new GroupPatrolCoordinator(route(),2,2);var lead=at("hunk",0,c.destination(0),true);
  var follower=at("vector",1,new Point(9857,2419,0),true);
  c.update(0,List.of(lead,follower));check(c.waypoint()==0&&c.phase()==GroupPatrolCoordinator.Phase.REGROUPING,"leader waits for its group");
  c.update(21*S,List.of(lead,follower));check(c.waypoint()==0&&c.phase()==GroupPatrolCoordinator.Phase.STALLED,"blocked member reports a stall without silently skipping patrol points");
  c.update(22*S,List.of(lead,at("vector",1,c.destination(1),true)));check(c.waypoint()==1,"shared waypoint advances once after all arrive");
  check(c.arrived(at("vector",1,route().get(1),true)),"center fallback satisfies arrival even when rounded lateral offset exceeds two tiles");
  int index=c.waypoint();c.update(23*S,List.of(at("hunk",0,c.destination(0),false)));check(c.waypoint()==index,"all-combat group retains PATROL progress");
  c.update(24*S,List.of(at("hunk",0,c.destination(0),true),at("vector",1,new Point(9857,2419,0),false)));
  check(c.waypoint()==index,"a recovering member is not abandoned");
  check(!c.destination(0).equals(c.destination(1))&&!c.destination(1).equals(c.destination(2)),"separate formation slots");
  for(int i=0;i<7;i++){var members=List.of(at("a",0,c.destination(0),true),at("b",1,c.destination(1),true),at("c",2,c.destination(2),true));c.update((25+i)*S,members);}
  check(c.waypoint()==0,"eighth point loops to first");
  try{c.update(40*S,List.of(at("a",0,c.destination(0),true),at("b",0,c.destination(0),true)));throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
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
   var to=(MovementRequest.To)request;check(to.partialLeg(),"group movement explicitly requests segmented legs");
   route=DestinationRoutePlanner.planLeg(new Grid(),new Tile((int)actor.player.x,(int)actor.player.y),new Tile(to.destination().x(),to.destination().y()));cursor=0;return !route.isEmpty();
  }
  public MotionStatus updateMovement(long now){
   if(returning)return MotionStatus.RUNNING;
   if(cursor>=route.size())return MotionStatus.SUCCEEDED;
   var next=route.get(cursor++);actor.player.x=next.x()+.5f;actor.player.y=next.y()+.5f;
   return cursor>=route.size()?MotionStatus.SUCCEEDED:MotionStatus.RUNNING;
  }
  public void stopMoving(){cancels++;route=List.of();}
 }
 static void adapterAndPatrol(){
  var groups=new ServerHumanGroups();var members=new ArrayList<ServerHuman>();var motors=new ArrayList<Motor>();
  for(int i=0;i<3;i++){var h=new ServerHuman("npc"+i,"alpha",i,route());groups.register(h);members.add(h);motors.add(new Motor(h));}
  groups.capture(0);groups.update(0);
  for(int i=0;i<3;i++)members.get(i).module.update(observe(0),motors.get(i));
  check(motors.get(0).starts==1&&motors.get(1).starts==0&&motors.get(2).starts==0,"startup is staggered to separate shared spawn occupants");
  var initialX=members.get(1).player.x;
  for(int tick=1;tick<1500;tick++){
   long now=tick*S/10;groups.capture(now);groups.update(now);
   for(int i=0;i<3;i++)members.get(i).module.update(observe(now),motors.get(i));
  }
  check(members.get(1).player.x!=initialX&&motors.stream().allMatch(m->m.starts>8),"three members execute multiple coordinated patrol legs");
  long stale=160*S;int before=motors.get(0).starts;members.get(0).module.update(observe(stale),motors.get(0));
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
  members.get(0).territory.inside=false;groups.capture(stale);groups.update(stale);members.get(0).module.interrupt(stale,motors.get(0));members.get(0).module.update(observe(stale),motors.get(0));
  check(motors.get(0).returning,"out-of-area member uses existing area recovery capability");
  int count=motors.get(1).starts;members.get(1).module.suspend(stale,motors.get(1));members.get(1).module.update(observe(stale),motors.get(1));
  check(motors.get(1).starts==count,"exclusive interaction suspension prevents patrol actuation");
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
 public static void main(String[] args){coordinator();navigation();adapterAndPatrol();capacity();System.out.println("Group patrol: "+checks+" assertions passed (42.22.1 production classes; engine doubles)");}
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
    production=list((SOURCE/'groups').glob('*.java'))+[SOURCE/'ServerHumanGroups.java']
    production += [SOURCE/'brain'/name for name in ('HumanObservation.java','HumanSenses.java','HumanTerritory.java','TacticalIntent.java','HumanModule.java','HumanBehavior.java','HumanModuleRuntime.java','HumanAction.java','HumanActionExecution.java','HumanCombat.java')]
    production += [SOURCE/'walking'/name for name in ('WalkingPatternSpec.java','MovementRequest.java')]
    production += [SOURCE/'movement'/name for name in ('MovementGrid.java','DestinationRoutePlanner.java')]
    production += [SOURCE/'state'/name for name in ('HumanState.java','HumanMutation.java','HumanStateReducer.java','HumanHealthCapacity.java')]
    production += [SOURCE/'survival'/name for name in ('SurvivalSettings.java','SurvivalPort.java')]
    with tempfile.TemporaryDirectory(prefix='apocbr-groups-') as folder:
        work=Path(folder);sources=list(production)
        for name,content in {**FIXTURES,'zombie/apocalipse/humans/GroupTest.java':HARNESS}.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
        args=work/'sources.txt';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources))
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-ea','-cp',str(work/'classes'),'zombie.apocalipse.humans.GroupTest'],check=True)

if __name__=='__main__':main()
