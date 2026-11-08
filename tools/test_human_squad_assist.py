"""42.22.1 production squad board, assistance policy, composition and native authority adapters."""
from pathlib import Path
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True
from test_human_groups import FIXTURES, ROOT, SOURCE

HARNESS = r'''
package zombie.apocalipse.humans;
import java.util.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.groups.*;
import zombie.apocalipse.humans.survival.*;
import zombie.apocalipse.humans.state.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;
import static zombie.apocalipse.humans.brain.HumanSenses.*;
import static zombie.apocalipse.humans.brain.HumanCombat.*;
public class SquadTest {
 static int checks;static final long S=1_000_000_000L;
 static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
 static Identity id(int n,long gen){return new Identity(Kind.ZOMBIE,(short)n,n,gen);}
 static SquadCombatCoordinator.Threat threat(int n,long at){return new SquadCombatCoordinator.Threat(id(n,1),10,10,0,at);}
 static SquadCombatCoordinator.Member member(String key,String incarnation,float x,float y,int z,boolean helper,SquadCombatCoordinator.Threat threat){return new SquadCombatCoordinator.Member(key,incarnation,x,y,z,helper,threat);}
 static void board(){
  var c=new SquadCombatCoordinator();var victim=member("victim","v1",10,10,0,false,threat(1,0));
  var helper=member("helper","h1",30,10,0,true,null);var members=List.of(victim,helper);
  var orders=c.update(0,members,Set.of());check(orders.containsKey("helper"),"20-tile boundary admits assistance");
  check(!orders.containsKey("victim"),"reporter is not its own rescuer");
  check(c.update(0,List.of(victim,member("helper","h1",30.01f,10,0,true,null)),Set.of()).isEmpty(),"out-of-range helper rejected");
  check(c.update(0,List.of(victim,member("helper","h1",10,10,1,true,null)),Set.of()).isEmpty(),"other floor rejected");
  check(c.update(0,List.of(victim,member("helper","h1",10,10,0,false,null)),Set.of()).isEmpty(),"incapacitated or exclusive helper not assigned");
  var unseen=member("victim","v1",10,10,0,false,null);
  check(c.update(8*S,List.of(unseen,helper),Set.of()).containsKey("helper"),"last-known report remembered through eight seconds");
  check(c.update(8*S+1,List.of(unseen,helper),Set.of()).isEmpty(),"old threat expires");
  c.update(0,members,Set.of());var previous=c.reports().getFirst();
  check(c.update(S,List.of(unseen,helper),Set.of(previous)).isEmpty(),"dead or reused native target invalidates memory immediately");
  c.update(0,members,Set.of());
  check(c.update(S,List.of(member("victim","v2",10,10,0,false,null),helper),Set.of()).isEmpty(),"new ally incarnation cannot inherit old report");
  c.update(0,members,Set.of());check(c.update(S,List.of(helper),Set.of()).isEmpty(),"removed or dead ally cannot leave a rescue behind");
  check(new SquadCombatCoordinator().update(0,List.of(helper),Set.of()).isEmpty(),"separate group does not inherit reports");
  check(c.update(0,List.of(member("v","i",10,10,0,false,threat(1,S)),helper),Set.of()).isEmpty(),"future sample rejected");
  var far=new SquadCombatCoordinator.Threat(id(2,1),25,10,0,0);
  orders=c.update(0,List.of(victim,member("second","s1",25,10,0,false,far),member("helper","h1",24,10,0,true,null)),Set.of());
  check(orders.get("helper").ally().equals("second"),"nearest reported threat wins deterministically");
  var stale=new SquadCombatCoordinator.Threat(id(3,1),10,10,0,0);
  check(new SquadCombatCoordinator().update(S,List.of(member("v","i",10,10,0,false,stale),helper),Set.of()).isEmpty(),"new stale observation cannot refresh memory");
  try{c.update(0,List.of(helper,helper),Set.of());throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
 }
 static Frame frame(long now,List<Contact> contacts){return new Frame(true,now,contacts,Coverage.COMPLETE,Coverage.COMPLETE,false,new Work(0,0,0,0,0));}
 static Contact contact(Identity id,double distance,long now,boolean visible){return new Contact(id,10,10,0,distance,now,now,visible);}
 static HumanObservation observation(long now,List<Contact> contacts){return new HumanObservation(now,List.of(),true,HumanState.INITIAL,frame(now,contacts),new HumanTerritory(true,true,contacts.stream().map(Contact::identity).collect(java.util.stream.Collectors.toSet())));}
 static class Assist implements SquadAssistPort {
  Order order;int approaches,updates,stops;List<String> phases=new ArrayList<>();HumanActuator.MotionStatus status=HumanActuator.MotionStatus.RUNNING;
  public Order observe(){return order;}public boolean approach(Order o,long now){approaches++;return true;}
  public HumanActuator.MotionStatus updateApproach(long now){updates++;return status;}public void stopApproach(){stops++;}
  public void transition(String phase,Order order){phases.add(phase);}
  void report(long now){order=new Order(now,new SquadCombatCoordinator.Report("ally","inc",threat(1,now)));}
 }
 static class Actuator implements HumanActuator {
  int begins,cancels,stops;Request request;TacticalIntent intent=TacticalIntent.NONE;Status result=Status.RUNNING;
  public void tactical(TacticalIntent value){intent=value;}public void stopMoving(){stops++;}
  public Port combat(){return (r,now)->{begins++;request=r;return new Execution(){
   public Result update(long at){return new Result(result,Reason.NONE);}public void cancel(Reason why){cancels++;}};};}
 }
 static void policy(){
  var port=new Assist();var actuator=new Actuator();var module=new SquadAssistModule(port);
  check(!module.eligible(observation(0,List.of())),"no board order is not assistance");port.report(0);
  module.update(observation(0,List.of()),actuator);check(port.approaches==1&&actuator.begins==0,"unseen threat starts last-known approach without a blind attack");
  module.update(observation(100_000_000L,List.of()),actuator);check(port.approaches==1&&port.updates==2,"route execution retained across ticks");
  var local=id(1,99);module.update(observation(200_000_000L,List.of(contact(local,16,200_000_000L,true))),actuator);
  check(port.stops==1&&actuator.begins==1,"seeing target hands movement from search to combat once");
  check(actuator.request.target().equals(local)&&actuator.request.template()==Template.SQUAD_ASSIST,"combat uses helper-local generation and explicit assistance scope");
  check(actuator.intent.objective()==TacticalIntent.Objective.ASSIST,"support publishes its own tactical objective");
  module.update(observation(300_000_000L,List.of(contact(local,16,300_000_000L,true))),actuator);check(actuator.begins==1,"running strike execution is not recreated");
  var urgent=id(2,7);module.update(observation(400_000_000L,List.of(contact(local,16,400_000_000L,true),contact(urgent,1,400_000_000L,true))),actuator);
  check(actuator.request.target().equals(urgent)&&actuator.cancels==1,"immediate personal attacker overrides squad target");
  module.update(observation(S,List.of()),actuator);check(actuator.cancels==2&&!module.eligible(observation(S,List.of())),"stale world order cancels combat");
  port.report(2*S);module.update(observation(2*S,List.of(contact(local,16,2*S,false))),actuator);
  check(actuator.begins==2&&port.approaches==2,"remembered but invisible contact never authorizes attack");
  module.cancel(actuator);check(port.stops==2,"preemption releases owned approach");
  port.report(3*S);module.update(observation(3*S,List.of(contact(local,16,3*S,true))),actuator);
  actuator.result=Status.FAILED;module.update(observation(3*S,List.of(contact(local,16,3*S,true))),actuator);
  int begins=actuator.begins;port.report(4*S);module.update(observation(4*S,List.of(contact(local,16,4*S,true))),actuator);
  check(actuator.begins==begins,"failed combat retries are throttled");
  port.report(5*S);module.update(observation(5*S,List.of(contact(local,16,5*S,true))),actuator);check(actuator.begins==begins+1,"retry resumes at deadline");
  module.cancel(actuator);port.report(6*S);module.update(observation(6*S,List.of()),actuator);
  int stops=port.stops;
  port.report(20*S);module.update(observation(20*S,List.of()),actuator);check(port.stops==stops+1,"an endlessly refreshed report cannot hold one stalled approach forever");
  port.report(21*S);module.update(observation(21*S,List.of()),actuator);int approaches=port.approaches;
  port.report(22*S);module.update(observation(22*S,List.of()),actuator);check(port.approaches==approaches+1,"timed-out approaches retry after backoff");
  module.cancel(actuator);port.report(23*S);
  var staleFrame=new HumanObservation(23*S,List.of(),true,HumanState.INITIAL,frame(22*S,List.of(contact(local,16,22*S,true))),HumanTerritory.UNAVAILABLE);
  begins=actuator.begins;module.update(staleFrame,actuator);check(actuator.begins==begins,"a stale helper sensor frame cannot authorize a shot even with a fresh squad order");
 }
 static List<Point> route(){return List.of(new Point(9850,2440,0),new Point(9860,2440,0));}
 static EngageZombiesModule arm(ServerHuman human){
  var engage=new EngageZombiesModule(HumanEngagement.Settings.DEFAULT);
  human.activity.behavior=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("engage-zombies",100,100,engage),new HumanModuleRuntime.Entry("group-patrol",1000,100,human.module)));
  return engage;
 }
 static zombie.characters.IsoZombie zombie(float x,float y){var z=new zombie.characters.IsoZombie();z.x=x;z.y=y;return z;}
 static void see(ServerHuman human,Identity identity,zombie.characters.IsoZombie zombie,long now){
  human.perception.known.put(identity,zombie);double dx=zombie.x-human.player.x,dy=zombie.y-human.player.y;
  human.perception.frame=frame(now,List.of(new Contact(identity,zombie.x,zombie.y,0,dx*dx+dy*dy,now,now,true)));
 }
 static void adapters(){
  var groups=new ServerHumanGroups();var victim=new ServerHuman("victim","squad",0,route());var helper=new ServerHuman("helper","squad",1,route());
  var other=new ServerHuman("other","elsewhere",0,route());arm(victim);arm(helper);arm(other);
  victim.player.x=9850;victim.player.y=2474;helper.player.x=9850;helper.player.y=2470;
  var z=zombie(9851,2475);var sourceId=id(1,5);var helperId=id(1,90);
  see(victim,sourceId,z,0);see(helper,helperId,z,0);
  groups.register(victim);groups.register(helper);groups.register(other);groups.capture(0);
  check(helper.squadAssist.observe()!=null&&helper.perception.hints==1,"native capture shares a threatened member's report and discovery hint");
  check(other.squadAssist.observe()==null,"native capture preserves group isolation");
  helper.territory.inside=false;
  var combat=new ServerHumanCombat(helper);
  check(combat.begin(new Request(helperId,Template.GUARD_BASIC),0).update(0).reason()==Reason.OUTSIDE_TERRITORY,"ordinary guard scope remains unchanged");
  check(combat.begin(new Request(helperId,Template.SQUAD_ASSIST),0).update(0).status()==Status.COMPLETED,"squad report permits independently visible target just outside patrol bounds");
  int[] cancelled={0};
  var running=new ServerHumanCombat(helper,(a,target,execution,request,now)->new Execution(){
   public Result update(long at){return new Result(Status.RUNNING,Reason.NONE);}public void cancel(Reason reason){cancelled[0]++;}});
  var owned=running.begin(new Request(helperId,Template.SQUAD_ASSIST),0);
  check(owned.update(0).status()==Status.RUNNING,"valid assistance retains the native combat lease");
  helper.squadAssist.publish(0,null);
  check(owned.update(0).reason()==Reason.OUTSIDE_TERRITORY&&cancelled[0]==1,"revoked report cancels native execution before another backend update");
  owned.update(0);check(cancelled[0]==1,"terminal assistance cancellation does not repeat effects");groups.capture(0);
  check(combat.begin(new Request(id(1,91),Template.SQUAD_ASSIST),0).update(0).reason()==Reason.INVALID_TARGET,"reporter or recycled generation cannot replace helper binding");
  var unrelated=id(2,1);var z2=zombie(9855,2470);see(helper,unrelated,z2,0);
  check(!helper.squadAssist.permits(unrelated,0),"assistance scope cannot authorize unrelated distant zombie");
  z2.x=9851;see(helper,unrelated,z2,0);check(helper.squadAssist.permits(unrelated,0),"helper can defend against independently seen immediate attacker");
  z.y=2500;see(helper,helperId,z,0);check(!helper.squadAssist.permits(helperId,0),"bounded emergency area prevents unlimited pursuit");
  z.y=2475;see(helper,helperId,z,0);check(!helper.squadAssist.permits(helperId,S),"stale detached world sample is not combat permission");
  helper.activity.mode=HumanActivityController.Mode.DIALOGUE;groups.capture(100_000_000L);
  check(helper.squadAssist.observe()==null,"exclusive interaction does not receive assistance ownership");
  helper.activity.mode=HumanActivityController.Mode.AUTONOMOUS;groups.capture(200_000_000L);
  check(helper.squadAssist.observe()!=null,"autonomy restoration may receive fresh assistance");
  check(helper.squadAssist.approach(helper.squadAssist.observe(),200_000_000L)&&helper.movement.starts==1,"last-known native approach uses bounded production route planner");
  helper.squadAssist.stopApproach();check(helper.movement.stops>0,"native approach cancellation stops owned motion");
  helper.player.x=9831;helper.player.y=2475;
  check(helper.squadAssist.approach(helper.squadAssist.observe(),200_000_000L),"full 20-tile last-known approach makes progress within bounded A-star work");
  helper.squadAssist.stopApproach();helper.player.x=9850;helper.player.y=2470;
  helper.movement.loaded=false;check(!helper.squadAssist.approach(helper.squadAssist.observe(),200_000_000L),"unloaded world cannot start rescue movement");helper.movement.loaded=true;
  z.dead=true;groups.capture(300_000_000L);check(helper.squadAssist.observe()==null,"dead native target invalidates shared contact before memory timeout");
  z.dead=false;see(victim,sourceId,z,S);see(helper,helperId,z,S);groups.capture(S);
  var old=helper.squadAssist.observe();check(old!=null,"new live report is usable");groups.remove(victim);groups.capture(S+100_000_000L);
  check(helper.squadAssist.observe()==null,"removed reporter cannot leave an assistance command behind");
  groups.remove(helper);check(helper.squadAssist.observe()==null&&!helper.squadAssist.approach(old,S+200_000_000L),"removed helper's adapter is permanently fenced");
 }
 static void arbitration(){
  var assistance=new Assist();assistance.report(0);var engage=new EngageZombiesModule(HumanEngagement.Settings.DEFAULT);engage.bindAssistance(assistance);
  var survival=new SurvivalModule(SurvivalSettings.DEFAULT);var actuator=new Actuator();
  var runtime=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("survival",10,100,survival),new HumanModuleRuntime.Entry("engage-zombies",100,100,engage)));
  runtime.update(observation(0,List.of()),actuator);
  check(runtime.selectedModule().equals("engage-zombies")&&assistance.approaches==1,"existing engagement schedule adopts assistance without a database migration");
  var crowd=new ArrayList<Contact>();for(int i=0;i<4;i++)crowd.add(contact(id(i+10,1),4,100_000_000L,true));
  runtime.update(observation(100_000_000L,crowd),actuator);
  check(runtime.selectedModule().equals("engage-zombies"),"four nearby zombies remain within one member capacity");
  crowd.add(contact(id(19,1),4,100_000_000L,true));runtime.update(observation(200_000_000L,crowd),actuator);
  check(runtime.selectedModule().equals("survival")&&assistance.stops==1,"actual survival policy preempts and cancels squad approach");
  runtime.suspend(200_000_000L,actuator);int begins=actuator.begins;runtime.update(observation(300_000_000L,List.of()),actuator);
  check(actuator.begins==begins,"exclusive suspension cannot start support combat");
  runtime.stop(S,actuator);check(!runtime.available(),"shutdown stops assistance composition");
  var ordinary=new EngageZombiesModule(HumanEngagement.Settings.DEFAULT);var normalActuator=new Actuator();
  var normal=observation(0,List.of(contact(id(20,1),16,0,true)));ordinary.update(normal,normalActuator);
  check(normalActuator.request.template()==Template.GUARD_BASIC,"ungrouped combat retains ordinary territory template");
  assistance=new Assist();assistance.report(0);engage=new EngageZombiesModule(HumanEngagement.Settings.DEFAULT);engage.bindAssistance(assistance);
  var assistedActuator=new Actuator();engage.update(observation(0,List.of()),assistedActuator);
  assistance.order=null;engage.update(normal,assistedActuator);
  check(assistance.stops==1&&assistedActuator.request.template()==Template.GUARD_BASIC,"expired support releases movement and resumes ordinary engagement");
 }
 static class Flight extends Actuator {
  int escapes;
  public SurvivalPort survival(){return (threats,settings,now)->{escapes++;return true;};}
  public MotionStatus updateMovement(long now){return MotionStatus.RUNNING;}
 }
 static void distress(){
  var c=new SquadCombatCoordinator();var victim=new SquadCombatCoordinator.Member("victim","v",10,10,0,false,threat(1,0),true);
  var far=member("helper","h",266,10,0,true,null);
  check(c.update(0,List.of(victim,far),Set.of()).get("helper").distress(),"squad distress reaches the 256-tile boundary");
  check(c.update(0,List.of(victim,member("helper","h",266.01f,10,0,true,null)),Set.of()).isEmpty(),"distress radius remains bounded");
  var nearby=member("nearby","n",260,10,0,false,new SquadCombatCoordinator.Threat(id(3,1),260,10,0,0));
  check(c.update(0,List.of(victim,nearby,far),Set.of()).get("helper").ally().equals("victim"),"fleeing squadmate outranks an ordinary nearby report");
  var groups=new ServerHumanGroups();var fleeing=new ServerHuman("fleeing","squad",0,route());var helper=new ServerHuman("helper","squad",1,route());
  var engagement=arm(fleeing);arm(helper);var survival=new SurvivalModule(SurvivalSettings.DEFAULT);
  fleeing.activity.behavior=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("survival",10,100,survival),new HumanModuleRuntime.Entry("engage-zombies",100,100,engagement),new HumanModuleRuntime.Entry("group-patrol",1000,100,fleeing.module)));
  fleeing.player.x=9849;fleeing.player.y=2525;helper.player.x=9857;helper.player.y=2402;
  var contacts=new ArrayList<Contact>();
  for(int n=1;n<=7;n++){
   var z=zombie(9849+n*.1f,2524);var identity=id(n,1);fleeing.perception.known.put(identity,z);
   contacts.add(new Contact(identity,z.x,z.y,0,1,0,0,false));
  }
  fleeing.perception.frame=Frame.EMPTY;fleeing.perception.awarenessFrame=frame(0,contacts);
  survival.update(new HumanObservation(0,List.of(),true,HumanState.INITIAL,Frame.EMPTY,HumanTerritory.UNAVAILABLE,frame(0,contacts)),new Flight());
  check(survival.phase()==SurvivalModule.Phase.ESCAPING,"regression actor is fleeing despite an empty visual frame");
  groups.register(fleeing);groups.register(helper);groups.capture(0);
  var order=helper.squadAssist.observe();
  check(order!=null&&order.report().distress(),"rear awareness publishes distress for the real 123-tile separation");
  check(helper.squadAssist.approach(order,0),"available helper starts a bounded partial leg beyond old patrol-plus-20 scope");
  check(fleeing.squadAssist.observe()==null,"a fleeing member is not assigned to rescue another actor");
  var actuator=new Actuator();((HumanModuleRuntime)helper.activity.behavior()).update(observation(0,List.of()),actuator);
  check(((HumanModuleRuntime)helper.activity.behavior()).selectedModule().equals("engage-zombies")&&actuator.begins==0,"distress preempts helper patrol without authorizing blind fire");
  check(fleeing.squadAssist.retreatSupport(0).isEmpty(),"assignment alone does not attract retreat before actual pursuit starts");
  groups.capture(100_000_000L);
  check(fleeing.squadAssist.retreatSupport(100_000_000L).size()==1,"renewed helper execution publishes its detached actual position to the fleeing member");
  var nativeRetreat=new ServerHumanSurvival(fleeing);
  check(nativeRetreat.escape(contacts,SurvivalSettings.DEFAULT,100_000_000L),"native escape adapter consumes the support snapshot");
  var end=fleeing.movement.lastRoute.getLast();
  check(fleeing.movement.lastPolicy==fleeing.movement,"escape executes against physical scope, not planning occupancy or squad territory");
  fleeing.movement.freeBodies=false;check(fleeing.movement.lastPolicy.walkable(end),"a later whole-tile occupant does not manufacture scope rejection before physical step validation");fleeing.movement.freeBodies=true;
  double original=Math.hypot(fleeing.player.x-helper.player.x,fleeing.player.y-helper.player.y);
  check(Math.hypot(end.x()+.5-helper.player.x,end.y()+.5-helper.player.y)<original,"native retreat gradually closes with the pursuing helper behind the threats");
  check(fleeing.squadAssist.retreatSupport(S).isEmpty(),"stale support samples cannot steer retreat");
  helper.movement.loaded=false;helper.squadAssist.stopApproach();
  check(!helper.squadAssist.approach(order,0),"distress still respects unloaded terrain");
  groups.capture(200_000_000L);check(fleeing.squadAssist.retreatSupport(200_000_000L).isEmpty(),"unloaded helpers stop attracting the retreat even with an unexpired pursuit lease");
  helper.movement.loaded=true;
  ((HumanModuleRuntime)helper.activity.behavior()).suspend(200_000_000L,actuator);
  groups.capture(300_000_000L);check(fleeing.squadAssist.retreatSupport(300_000_000L).isEmpty(),"preempted helper execution clears rendezvous support");
 }
 static void relief(){
  var survival=new SurvivalModule(SurvivalSettings.DEFAULT);var flight=new Flight();
  var crowded=new ArrayList<Contact>();for(int n=0;n<7;n++)crowded.add(contact(id(n+30,1),4,0,true));
  survival.update(observation(0,crowded),flight);
  for(int cycle=0;cycle<6;cycle++){
   long now=cycle*700_000_000L+100_000_000L;
   survival.update(observation(now,List.of()),flight);survival.update(observation(now+500_000_000L,List.of()),flight);
   long pressure=now+600_000_000L;var next=new ArrayList<Contact>();for(int n=0;n<7;n++)next.add(contact(id(n+30,1),4,pressure,true));
   survival.update(observation(pressure,next),flight);
   check(survival.phase()==SurvivalModule.Phase.ESCAPING&&flight.escapes==1,"seven-zero-seven oscillation retains the current escape route");
  }
  survival.update(observation(5*S,List.of()),flight);survival.update(observation(6*S-1,List.of()),flight);
  check(survival.phase()==SurvivalModule.Phase.ESCAPING,"relief must last the full second");
  survival.update(observation(6*S,List.of()),flight);check(survival.phase()==SurvivalModule.Phase.RECOVERING,"sustained relief still allows recovery");
 }
 static void retreatPlanning(){
  var start=new zombie.apocalipse.humans.movement.MovementGrid.Tile(100,100);
  var open=new zombie.apocalipse.humans.movement.MovementGrid(){
   public boolean walkable(Tile t){return t.x()>=70&&t.x()<=130&&t.y()>=70&&t.y()<=130;}
   public boolean connected(Tile a,Tile b){return true;}
  };
  var threats=List.of(new RetreatPlanner.Threat(100.5,98.5));
  var ordinary=RetreatPlanner.plan(open,start,threats,12,false);
  check(ordinary.equals(RetreatPlanner.plan(open,start,threats,12,false,List.of())),"ungrouped retreat retains the exact previous route");
  var helper=new RetreatPlanner.Rendezvous(100.5,70.5);
  var flank=RetreatPlanner.plan(open,start,threats,12,false,List.of(helper));
  check(!flank.isEmpty(),"helper behind threat admits a safe flanking retreat");
  var end=flank.getLast();check(Math.hypot(end.x()+.5-helper.x(),end.y()+.5-helper.y())<30,"flank closes the squad gap rather than maximizing distance forever");
  for(var tile:flank)check(Math.hypot(tile.x()+.5-100.5,tile.y()+.5-98.5)>=2-.0001,"support preference never moves through the existing threat envelope");
  check(Math.hypot(end.x()+.5-100.5,end.y()+.5-98.5)>=4,"support endpoint still increases nearest threat clearance by two tiles");
  var corridor=new zombie.apocalipse.humans.movement.MovementGrid(){
   public boolean walkable(Tile t){return t.y()==100&&t.x()>=100&&t.x()<=112;}public boolean connected(Tile a,Tile b){return true;}
  };
  var behind=new RetreatPlanner.Rendezvous(70.5,100.5);var west=List.of(new RetreatPlanner.Threat(98.5,100.5));
  var only=RetreatPlanner.plan(corridor,start,west,12,false,List.of(behind));
  check(!only.isEmpty()&&only.getLast().x()>100,"when squadward movement is blocked the actor still escapes away from threats");
  var far=new RetreatPlanner.Rendezvous(100.5,0.5);
  check(flank.equals(RetreatPlanner.plan(open,start,threats,12,false,List.of(far,helper))),"nearest active helper supplies the rendezvous preference");
  var blocked=new zombie.apocalipse.humans.movement.MovementGrid(){public boolean walkable(Tile t){return false;}public boolean connected(Tile a,Tile b){return false;}};
  check(RetreatPlanner.plan(blocked,start,threats,12,false,List.of(helper)).isEmpty(),"support never bypasses an unavailable movement origin");
  try{new RetreatPlanner.Rendezvous(Double.NaN,0);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
 }
 static HumanObservation supported(long now,int count,HumanState state,HumanObservation.Support support){
  var contacts=new ArrayList<Contact>();for(int n=0;n<count;n++)contacts.add(contact(id(100+n,1),9,now,true));
  var frame=frame(now,contacts);
  return new HumanObservation(now,List.of(),true,state,frame,new HumanTerritory(true,true,contacts.stream().map(Contact::identity).collect(java.util.stream.Collectors.toSet())),frame,support);
 }
 static void retainedDefense(){
  class Motor extends Flight {HumanActuator.MotionStatus motion=HumanActuator.MotionStatus.RUNNING;public HumanActuator.MotionStatus updateMovement(long now){return motion;}}
  var module=new SurvivalModule(SurvivalSettings.DEFAULT);var a=new Motor();
  var low=HumanStateReducer.reduce(HumanState.INITIAL,new HumanMutation.Damage(.8f,0,0,0));
  long[] times={0,100_000_000L,200_000_000L,2_200_000_000L,2_300_000_000L};
  for(long now:times){
   a.motion=now==200_000_000L?HumanActuator.MotionStatus.FAILED:HumanActuator.MotionStatus.RUNNING;
   var sight=frame(now,List.of(contact(id(800,1),1,now,true)));
   module.update(new HumanObservation(now,List.of(),true,low,sight,HumanTerritory.UNAVAILABLE),a);
  }
  check(a.begins==1&&a.cancels==0&&a.escapes==2,"route failure and retry preserve a valid close defensive windup, including outside patrol bounds");
 }
 static void engagementCapacity(){
  var base=supported(0,12,HumanState.INITIAL,new HumanObservation.Support(0,2));
  check(!ThreatAssessment.assess(base,SurvivalSettings.DEFAULT).pressured(),"three ready members hold combat against twelve nearby zombies");
  check(!ThreatAssessment.assess(supported(0,4,HumanState.INITIAL,HumanObservation.Support.NONE),SurvivalSettings.DEFAULT).pressured(),"solo member handles four nearby zombies");
  var tight=new ArrayList<Contact>();for(int n=0;n<5;n++)tight.add(contact(id(300+n,1),1,0,true));
  var frame=frame(0,tight);var surrounded=new HumanObservation(0,List.of(),true,HumanState.INITIAL,frame,base.territory(),frame,base.support());
  check(ThreatAssessment.assess(surrounded,SurvivalSettings.DEFAULT).pressure().equals("surrounded"),"five immediate contacts trigger personal emergency despite healthy squad capacity");
  var helper=new SurvivalModule(SurvivalSettings.DEFAULT);var victim=new SurvivalModule(SurvivalSettings.DEFAULT);
  helper.update(base,new Flight());victim.update(surrounded,new Flight());
  check(helper.phase()==SurvivalModule.Phase.SAFE&&victim.phase()==SurvivalModule.Phase.ESCAPING,"one surrounded member escapes while capable helpers retain normal engagement");
  var recovering=new SurvivalModule(SurvivalSettings.DEFAULT);var actuator=new Actuator();
  recovering.update(supported(0,5,HumanState.INITIAL,HumanObservation.Support.NONE),actuator);
  recovering.update(supported(S,1,HumanState.INITIAL,new HumanObservation.Support(S,1)),actuator);
  recovering.update(supported(2*S,1,HumanState.INITIAL,new HumanObservation.Support(2*S,1)),actuator);
  check(recovering.phase()==SurvivalModule.Phase.RECOVERING&&actuator.request!=null
    &&actuator.request.template()==Template.SURVIVAL_DEFENSE&&actuator.request.externalMovement(),"safe recovery provides stationary ranged cover without releasing the survival latch");
  var nativeActor=new ServerHuman("defense","solo",0,route());nativeActor.territory.inside=false;
  var module=new SurvivalModule(SurvivalSettings.DEFAULT);nativeActor.activity.behavior=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("survival",10,100,module)));
  var low=HumanStateReducer.reduce(HumanState.INITIAL,new HumanMutation.Damage(.8f,0,0,0));
  module.update(supported(0,0,low,HumanObservation.Support.NONE),new Flight());
  var zid=id(600,1);var z=zombie(nativeActor.player.x+1,nativeActor.player.y);see(nativeActor,zid,z,0);
  var nativeCombat=new ServerHumanCombat(nativeActor);
  check(nativeCombat.begin(new Request(zid,Template.SURVIVAL_DEFENSE,true),0).update(0).status()==Status.COMPLETED,"immediate survival defense works outside patrol territory");
  check(nativeCombat.begin(new Request(zid,Template.GUARD_BASIC),0).update(0).reason()==Reason.OUTSIDE_TERRITORY,"ordinary patrol authorization still rejects outside targets");
  check(nativeCombat.begin(new Request(zid,Template.SURVIVAL_DEFENSE),0).update(0).reason()==Reason.OUTSIDE_TERRITORY,"survival cannot use defense permission to take over movement or pursue");
  z.x=nativeActor.player.x+4;see(nativeActor,zid,z,0);
  check(nativeCombat.begin(new Request(zid,Template.SURVIVAL_DEFENSE,true),0).update(0).status()==Status.COMPLETED,"fresh cleared recovery can fire outside assigned area without pursuit");
  int[] updates={0},cancelled={0};
  var sustained=new ServerHumanCombat(nativeActor,(a,t,id,r,n)->new Execution(){public Result update(long at){updates[0]++;return new Result(Status.RUNNING,Reason.NONE);}public void cancel(Reason why){cancelled[0]++;}});
  var cover=sustained.begin(new Request(zid,Template.SURVIVAL_DEFENSE,true),0);check(cover.update(0).status()==Status.RUNNING,"stationary cover owns an ongoing validated backend lease");
  nativeActor.perception.frame=frame(0,List.of(contact(zid,16,0,true),contact(id(601,1),1,0,true)));
  check(cover.update(0).reason()==Reason.BUSY&&updates[0]==1&&cancelled[0]==1,"new personal contact cancels ranged cover before a later backend update");
  see(nativeActor,zid,z,0);z.x=nativeActor.player.x+8;see(nativeActor,zid,z,0);
  check(nativeCombat.begin(new Request(zid,Template.SURVIVAL_DEFENSE,true),0).update(0).reason()==Reason.OUTSIDE_TERRITORY,"survival cover cannot pursue or shoot beyond its seven-tile envelope");
  nativeActor.activity.mode=HumanActivityController.Mode.DIALOGUE;
  check(nativeCombat.begin(new Request(zid,Template.SURVIVAL_DEFENSE,true),0).update(0).reason()==Reason.OUTSIDE_TERRITORY,"exclusive dialogue forbids survival fire");
 }
 static void localStrength(){
  var groups=new ServerHumanGroups();var a=new ServerHuman("a","squad",0,route());var b=new ServerHuman("b","squad",1,route());var c=new ServerHuman("c","squad",2,route());
  for(var h:List.of(a,b,c)){arm(h);h.player.x=9850;h.player.y=2510;groups.register(h);}
  b.player.x+=2;c.player.x+=4;groups.capture(0);
  for(var h:List.of(a,b,c)){
   var facts=ThreatAssessment.assess(supported(0,6,HumanState.INITIAL,h.squadAssist.strength()),SurvivalSettings.DEFAULT);
   check(facts.allies()==2&&facts.crowdLimit()==12&&!facts.pressured(),"six zombies facing three capable nearby squadmates do not trigger solo retreat");
  }
  check(ThreatAssessment.assess(supported(0,13,HumanState.INITIAL,a.squadAssist.strength()),SurvivalSettings.DEFAULT).pressured(),"a truly overwhelming crowd still forces a squad retreat");
  check(ThreatAssessment.assess(supported(500_000_001L,6,HumanState.INITIAL,a.squadAssist.strength()),SurvivalSettings.DEFAULT).allies()==0,"stale strength fails closed to solo capacity");
  check(ThreatAssessment.assess(supported(-1,6,HumanState.INITIAL,a.squadAssist.strength()),SurvivalSettings.DEFAULT).allies()==0,"future strength cannot increase capacity");
  var low=HumanStateReducer.reduce(HumanState.INITIAL,new HumanMutation.Damage(.8f,0,0,0));
  check(ThreatAssessment.assess(supported(0,6,low,a.squadAssist.strength()),SurvivalSettings.DEFAULT).pressure().equals("low_health"),"allies cannot override personal health emergency");
  var tired=HumanStateReducer.reduce(HumanState.INITIAL,new HumanMutation.Adjust(HumanState.Stat.ENDURANCE,-.9f));
  check(ThreatAssessment.assess(supported(0,6,tired,a.squadAssist.strength()),SurvivalSettings.DEFAULT).pressure().equals("exhausted"),"allies cannot override personal exhaustion");
  b.player.x=a.player.x+6;c.player.x=a.player.x+6.01f;groups.capture(0);
  check(a.squadAssist.strength().allies()==1,"local strength includes six-tile boundary and excludes distant reinforcement");
  c.player.x=a.player.x+4;b.player.z=1;groups.capture(0);check(a.squadAssist.strength().allies()==1,"another floor supplies no capacity");b.player.z=0;
  b.activity.mode=HumanActivityController.Mode.DIALOGUE;groups.capture(0);check(a.squadAssist.strength().allies()==1,"exclusive dialogue actor is not a combat reserve");b.activity.mode=HumanActivityController.Mode.AUTONOMOUS;
  b.state.value=low;groups.capture(0);check(a.squadAssist.strength().allies()==1,"wounded ally is excluded");b.state.value=tired;groups.capture(0);check(a.squadAssist.strength().allies()==1,"exhausted ally is excluded");
  b.state.value=new HumanState(1,HumanState.Vitals.HEALTHY,new HumanState.Injuries(0,0,.01f,false,false));groups.capture(0);check(a.squadAssist.strength().allies()==1,"actively bleeding ally is excluded");
  b.state.value=new HumanState(1,HumanState.Vitals.HEALTHY,new HumanState.Injuries(0,0,0,false,true));groups.capture(0);check(a.squadAssist.strength().allies()==1,"downed ally is excluded");b.state.value=HumanState.INITIAL;
  b.movement.loaded=false;groups.capture(0);check(a.squadAssist.strength().allies()==1,"unloaded ally is excluded");b.movement.loaded=true;
  b.retaliation.aggressor=new HumanObservation.Identity((short)3,7);groups.capture(0);check(a.squadAssist.strength().allies()==1,"ally occupied with player retaliation is excluded");b.retaliation.aggressor=null;
  b.activity.behavior=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("healing",10,100,new HumanModule(){
   public boolean eligible(HumanObservation o){return true;}public boolean available(){return true;}
   public void update(HumanObservation o,HumanActuator p){}public void interrupt(long now,HumanActuator p){}
   public void suspend(long now,HumanActuator p){}public void resume(HumanObservation.Identity visitor,long now,HumanActuator p){}public void stop(long now,HumanActuator p){}
  }),new HumanModuleRuntime.Entry("group-patrol",1000,100,b.module)));
  ((HumanModuleRuntime)b.activity.behavior()).update(observation(0,List.of()),new Actuator());groups.capture(0);check(a.squadAssist.strength().allies()==1,"ally executing healing does not lend combat capacity");arm(b);
  a.perception.supportVisible=false;groups.capture(0);check(a.squadAssist.strength().allies()==0,"occluded allies cannot increase local capacity");a.perception.supportVisible=true;
  var outsider=new ServerHuman("outsider","other",0,route());arm(outsider);outsider.player.x=a.player.x;outsider.player.y=a.player.y;groups.register(outsider);groups.capture(0);
  check(a.squadAssist.strength().allies()==2&&outsider.squadAssist.strength().allies()==0,"support stays within registered squad membership");
  var engagement=arm(b);var survival=new SurvivalModule(SurvivalSettings.DEFAULT);var flight=new Flight();
  b.activity.behavior=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("survival",10,100,survival),new HumanModuleRuntime.Entry("engage-zombies",100,100,engagement),new HumanModuleRuntime.Entry("group-patrol",1000,100,b.module)));
  survival.update(supported(0,6,HumanState.INITIAL,HumanObservation.Support.NONE),flight);
  check(survival.phase()==SurvivalModule.Phase.ESCAPING,"isolated actor first enters survival");groups.capture(0);
  check(a.squadAssist.strength().allies()==2&&b.squadAssist.strength().allies()==2,"survival phase does not recursively erase physically capable support");
  for(int second=1;second<=9;second++){
   groups.capture(second*S);survival.update(supported(second*S,6,HumanState.INITIAL,b.squadAssist.strength()),flight);
  }
  check(survival.phase()==SurvivalModule.Phase.SAFE&&flight.escapes==1,"reinforced actor stops fleeing after sustained relief even with a close enemy; no repeated escape legs");
  groups.remove(c);groups.capture(10*S);check(a.squadAssist.strength().allies()==1&&c.squadAssist.strength().allies()==0,"removal fences old incarnation and removes its contribution");
  var runtime=(HumanModuleRuntime)b.activity.behavior();var motor=new Actuator();
  runtime.update(supported(10*S,6,HumanState.INITIAL,b.squadAssist.strength()),motor);
  check(runtime.selectedModule().equals("engage-zombies"),"squad-aware eligibility lets combat win after relief");
  groups.remove(a);groups.capture(11*S);check(b.squadAssist.strength().allies()==0,"last separated member returns to solo capacity");
  runtime.update(supported(11*S,6,HumanState.INITIAL,b.squadAssist.strength()),new Flight());
  check(runtime.selectedModule().equals("survival"),"loss of local reinforcements restores normal retreat priority");
 }
 public static void main(String[] args){board();policy();adapters();arbitration();distress();relief();retreatPlanning();retainedDefense();engagementCapacity();localStrength();System.out.println("Squad assistance: "+checks+" assertions passed (42.22.1 production policies/adapters; engine doubles)");}
}
'''

def main():
    production = list((SOURCE/'groups').glob('*.java'))
    production += [SOURCE/name for name in ('ServerHumanGroups.java', 'ServerHumanSquadAssist.java', 'ServerHumanCombat.java', 'ServerHumanSurvival.java')]
    production += [SOURCE/'brain'/name for name in ('HumanObservation.java', 'HumanSenses.java', 'HumanTerritory.java', 'TacticalIntent.java', 'HumanModule.java', 'HumanBehavior.java', 'HumanModuleRuntime.java', 'HumanAction.java', 'HumanActionExecution.java', 'HumanCombat.java', 'HumanEngagement.java', 'EngageZombiesModule.java')]
    production += [SOURCE/'movement'/name for name in ('MovementGrid.java', 'MovementModifiers.java', 'CombatRoutePlanner.java', 'DestinationRoutePlanner.java')]
    production += [SOURCE/'walking'/name for name in ('WalkingPatternSpec.java', 'MovementRequest.java')]
    production += [SOURCE/'state'/name for name in ('HumanState.java', 'HumanMutation.java', 'HumanStateReducer.java')]
    production += [SOURCE/'survival'/name for name in ('SurvivalSettings.java', 'SurvivalPort.java', 'ThreatAssessment.java', 'SurvivalModule.java', 'RetreatPlanner.java', 'RouteRefusals.java')]
    production += [SOURCE/'healing'/name for name in ('HealingSettings.java', 'HumanHealingPort.java', 'HealingModule.java')]
    production += [SOURCE/'speech/SpeechEvent.java']
    with tempfile.TemporaryDirectory(prefix='apocbr-squad-assist-') as folder:
        work=Path(folder);sources=list(production)
        for name,content in {**FIXTURES, 'zombie/apocalipse/humans/SquadTest.java': HARNESS}.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
        args=work/'sources.txt';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources))
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'), '--release', '25', '-d', str(work/'classes'), '@'+str(args)], check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'), '-ea', '-cp', str(work/'classes'), 'zombie.apocalipse.humans.SquadTest'], check=True)

if __name__ == '__main__':
    main()
