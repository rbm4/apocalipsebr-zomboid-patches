"""Production corpse task execution, native world adapter and authenticated dialogue metadata."""
from pathlib import Path
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True
from test_server_humans import FILES, ROOT
from test_human_dialogue import HARNESS as DIALOGUE

HARNESS = r'''
package zombie.apocalipse.humans;
import java.util.*;
import zombie.apocalipse.humans.tasks.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.profiles.*;
import zombie.apocalipse.humans.walking.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;
import zombie.iso.*;
import zombie.iso.objects.*;
public class CorpsesTest {
 static int checks;
 static void check(boolean b,String text){checks++;if(!b)throw new AssertionError(text);}
 static Point p(int x){return new Point(x,200,0);}
 static HumanObservation o(long now){return new HumanObservation(now,List.of(),true);}
 static HumanTaskCommand order(int x){return new HumanTaskCommand(HumanTaskCommand.Kind.CLEAR_CORPSES,Map.of("x",""+x,"y","200","z","0","radiusTiles","2"));}
 static void invalid(Map<String,String> parameters){try{new HumanTaskCommand(HumanTaskCommand.Kind.CLEAR_CORPSES,parameters);throw new AssertionError("invalid command accepted");}catch(IllegalArgumentException expected){checks++;}}
 static class Port implements HumanActuator,HumanTaskWorld,HumanCorpsePort {
  Point here=p(100),goal;Map<Long,Point> bodies=new LinkedHashMap<>();Set<Long> held=new HashSet<>();
  int moves,stops;boolean loaded=true,reject,fail,deferralFull;
  Map<Long,Long> deferred=new LinkedHashMap<>();List<Point> alternatives=List.of(),routes=new ArrayList<>();Set<Point> rejectedGoals=new HashSet<>();
  int retries;
  public HumanTaskWorld taskWorld(){return this;}public HumanCorpsePort corpses(){return this;}
  public Point position(){return here;}public Point locate(HumanObservation.Identity id){return null;}public boolean permitted(Point p){return p!=null&&p.z()==0;}
  public String movementSlot(){return "background";}
  public Search find(Point source,int radius,Point dest){return new Search(bodies.entrySet().stream().filter(e->!e.getValue().equals(dest)&&Math.abs(e.getValue().x()-source.x())<=radius).map(e->new Body(e.getKey(),e.getValue())).findFirst().orElse(null),true,loaded);}
  public Search find(Point source,int radius,Point dest,long now){
   long next=Long.MAX_VALUE;
   for(var entry:bodies.entrySet()){
    if(entry.getValue().equals(dest))continue;
    long until=deferred.getOrDefault(entry.getKey(),Long.MIN_VALUE);
    if(until>now){next=Math.min(next,until);continue;}
    if(deferred.containsKey(entry.getKey())||Math.abs(entry.getValue().x()-source.x())<=radius){deferred.remove(entry.getKey());return new Search(new Body(entry.getKey(),entry.getValue()),true,loaded);}
   }
   return new Search(null,true,loaded,next);
  }
  public List<Point> approaches(long token){return alternatives.isEmpty()?List.of(resolve(token).position()):alternatives;}
  public boolean defer(long token,long until){if(deferralFull)return false;deferred.put(token,until);held.remove(token);return true;}
  public void retry(long token,int count,String reason,long until){retries++;}
  public Body resolve(long token){var point=bodies.get(token);return point==null?null:new Body(token,point);}
  public boolean claim(long token){return held.add(token);}public void release(long token){held.remove(token);}
  public boolean move(long token,Point point){if(!held.contains(token)||!bodies.containsKey(token))return false;bodies.put(token,point);moves++;return true;}
  public boolean startMovement(MovementRequest r,long now){goal=((MovementRequest.To)r).destination();routes.add(goal);return !reject&&!rejectedGoals.contains(goal);}
  public MotionStatus updateMovement(long now){if(fail)return MotionStatus.FAILED;here=p(here.x()+Integer.signum(goal.x()-here.x()));return here.equals(goal)?MotionStatus.SUCCEEDED:MotionStatus.RUNNING;}
  public void stopMoving(){stops++;}public boolean face(HumanObservation.Identity i){return true;}public boolean startEmote(String s){return true;}public void stopEmote(){}public boolean say(String s){return true;}public boolean returnHome(){return true;}
 }
 static void execution(){
  invalid(Map.of("x","1","y","2"));invalid(Map.of("x","1","y","2","z","0","radiusTiles","3"));
  invalid(Map.of("x","1","y","2","z","0","sourceX","4"));
  check(order(105).destination().equals(p(105))&&order(107).destination().equals(p(107)),"drop-off is per-dialogue metadata, never an engine constant");
  var port=new Port();port.bodies.put(1L,p(101));port.bodies.put(2L,p(99));
  var task=new AssignedTaskModule();task.assign(order(105),null,p(100));
  long now=1;
  for(int n=0;n<30&&port.here.x()!=103;n++)task.update(o(now+=100_000_000),port);
  check(port.bodies.get(1L).equals(p(103)),"corpse follows actual reached tiles during hauling");
  task.interrupt(now,port);check(port.held.isEmpty()&&task.snapshot().status()==AssignedTaskModule.Status.SUSPENDED,"defensive preemption releases body without deleting it");
  port.here=p(110); // Defense repositioned the NPC away from the dropped body.
  for(int n=0;n<70&&task.active();n++)task.update(o(now+=100_000_000),port);
  check(task.active()&&port.bodies.values().stream().allMatch(p(105)::equals),"resume recovers in-flight body, delivers all bodies and retains order");
  check(port.held.isEmpty(),"completion leaves no corpse claims");
  check(port.here.equals(p(100)),"completion returns to captured source before routine resumes outside cleanup scope");
  check(task.snapshot().reason().equals("cleanup_waiting_corpses"),"empty area waits without finishing order");
  int routesBefore=port.routes.size();port.here=p(110);
  for(int n=0;n<30;n++)task.update(o(now+=100_000_000),port);
  check(port.routes.size()==routesBefore,"idle scanning does not repeatedly recall routine movement to the source");
  port.bodies.put(3L,p(101));
  for(int n=0;n<100;n++)task.update(o(now+=100_000_000),port);
  check(port.bodies.get(3L).equals(p(105))&&task.active(),"new corpse is collected after the initial area became empty");
  task.interrupt(now,port);task.assign(new HumanTaskCommand(HumanTaskCommand.Kind.RESUME_ROUTINE,Map.of()),null,port.here);
  check(!task.active()&&port.held.isEmpty(),"go back to normal cancels continuous cleanup and releases ownership");
  task=new AssignedTaskModule();port=new Port();port.loaded=false;task.assign(order(105),null,p(100));task.update(o(1),port);
  check(task.active()&&task.snapshot().reason().equals("cleanup_waiting_area")&&!task.eligible(o(2)),"unloaded source retains intent and yields until a bounded retry");
  task=new AssignedTaskModule();port=new Port();port.bodies.put(1L,p(101));port.reject=true;task.assign(order(105),null,p(100));task.update(o(1),port);
  check(task.active()&&task.snapshot().reason().equals("cleanup_retrying")&&port.bodies.get(1L).equals(p(101))&&port.held.isEmpty(),"route rejection retains intent, leaves body intact and releases claim");
  task=new AssignedTaskModule();port=new Port();port.bodies.put(1L,p(101));task.assign(order(105),null,p(100));task.update(o(1),port);port.bodies.remove(1L);task.update(o(2),port);task.update(o(3),port);
  check(task.active()&&port.bodies.isEmpty(),"removed or reanimated body is not recreated and order stays active");
  task=new AssignedTaskModule();port=new Port();port.bodies.put(1L,p(101));task.assign(order(105),null,p(100));task.update(o(1),port);task.stop(2,port);
  check(port.held.isEmpty()&&port.bodies.size()==1&&!task.active(),"shutdown drops claim and retains corpse");
  task=new AssignedTaskModule();port=new Port();port.bodies.put(1L,p(101));port.fail=true;task.assign(order(105),null,p(100));task.update(o(1),port);
  check(task.active()&&task.snapshot().reason().equals("cleanup_retrying")&&port.held.isEmpty(),"failed route retries and releases ownership without deleting the body");
  task=new AssignedTaskModule();port=new Port();port.bodies.put(1L,p(102));task.assign(order(105),null,p(100));task.update(o(1),port);task.update(o(121_000_000_001L),port);
  check(task.active()&&task.snapshot().reason().equals("cleanup_retrying")&&port.held.isEmpty(),"stalled route has a bounded attempt lifetime without cancelling intent");
 }
 static HumanProfile profile(){
  var specs=List.of(new WalkingPatternSpec("background","area-wander",16,List.of(),new WalkingPatternSpec.Area(100,200,102,202)),
   new WalkingPatternSpec("corpse-cleanup","area-wander",16,List.of(),new WalkingPatternSpec.Area(98,198,112,204)));
  var profile=new HumanProfile("corpse-test",1,"modules",Map.of(),"","none",true,false,
   List.of(new HumanModuleBinding("assigned-task",200,100,Map.of()),new HumanModuleBinding("walk",1000,100,Map.of())),8,100,true,specs);
  ServerHumanProfiles.registry().register(profile);return profile;
 }
 static void intentRetries(){
  var port=new Port();port.here=p(99);port.bodies.put(1L,p(101));port.alternatives=List.of(p(102),p(100));port.rejectedGoals.add(p(102));
  var task=new AssignedTaskModule();task.assign(order(105),null,p(99));task.update(o(1),port);
  check(task.active()&&port.retries==1&&port.held.isEmpty(),"blocked head approach retains intent and releases ownership");
  check(!task.eligible(o(500_000_001L)),"retry backoff yields the scheduler without losing the assignment");
  task.update(o(500_000_001L),port);check(port.routes.size()==1,"no route churn inside retry backoff");
  long now=1_000_000_001L;task.update(o(now),port);
  check(port.routes.size()==2&&port.routes.get(1).equals(p(100)),"next attempt selects opposite body endpoint");
  for(int n=0;n<35&&task.active();n++)task.update(o(now+=100_000_000),port);
  check(task.active()&&port.bodies.get(1L).equals(p(105)),"alternate approach recovers a transient obstruction without a new dialogue order");

  port=new Port();port.bodies.put(1L,p(101));port.bodies.put(2L,p(99));port.rejectedGoals.add(p(101));
  task=new AssignedTaskModule();task.assign(order(105),null,p(100));now=1;
  for(int n=0;n<CorpseCleanupExecution.MAX_ATTEMPTS;n++){task.update(o(now),port);if(n+1<CorpseCleanupExecution.MAX_ATTEMPTS)now+=CorpseCleanupExecution.RETRY_NANOS;}
  long due=now+CorpseCleanupExecution.DEFER_NANOS;
  check(port.deferred.get(1L)==due&&port.retries==CorpseCleanupExecution.MAX_ATTEMPTS&&port.held.isEmpty(),"exactly five failures defer only that corpse for five minutes");
  for(int n=0;n<35;n++)task.update(o(now+=100_000_000),port);
  check(port.bodies.get(2L).equals(p(105))&&port.bodies.get(1L).equals(p(101)),"other corpses are delivered while blocked corpse cools down");
  check(task.active()&&!task.eligible(o(now+1))&&task.snapshot().reason().equals("cleanup_waiting_deferred"),"all-deferred search remains pending and yields routine until the timer expires");
  port.bodies.put(3L,p(98));
  for(int n=0;n<70;n++)task.update(o(now+=100_000_000),port);
  check(port.bodies.get(3L).equals(p(105))&&port.deferred.get(1L)==due,"new corpses are discovered during another body's five-minute cooldown");
  port.rejectedGoals.clear();now=due;
  for(int n=0;n<50&&task.active();n++)task.update(o(now+=100_000_000),port);
  check(task.active()&&port.bodies.get(1L).equals(p(105)),"deferred corpse is retried automatically after expiry");

  port=new Port();port.bodies.put(1L,p(101));port.rejectedGoals.add(p(105));task=new AssignedTaskModule();task.assign(order(105),null,p(100));now=1;
  for(int n=0;n<100&&!port.deferred.containsKey(1L);n++)task.update(o(now+=1_000_000_000L),port);
  check(port.deferred.containsKey(1L)&&task.active()&&port.held.isEmpty(),"blocked dump route also retries and defers instead of cancelling cleanup");
  port.rejectedGoals.clear();long retry=port.deferred.get(1L);
  for(int n=0;n<50&&task.active();n++)task.update(o(retry+=100_000_000),port);
  check(task.active()&&port.bodies.get(1L).equals(p(105)),"dump access recovery delivers body while retaining assignment");

  port=new Port();port.bodies.put(1L,p(101));port.rejectedGoals.add(p(100));task=new AssignedTaskModule();task.assign(order(105),null,p(100));now=1;
  for(int n=0;n<40&&port.retries==0;n++)task.update(o(now+=100_000_000),port);
  check(port.bodies.get(1L).equals(p(105))&&port.retries==1,"return retry begins only after actual cleanup work");
  for(int n=1;n<CorpseCleanupExecution.MAX_ATTEMPTS;n++)task.update(o(now+=CorpseCleanupExecution.RETRY_NANOS),port);
  check(task.active()&&!task.eligible(o(now+CorpseCleanupExecution.DEFER_NANOS-1)),"blocked return route retains intent with bounded retry rounds");
  port.rejectedGoals.clear();now+=CorpseCleanupExecution.DEFER_NANOS;
  for(int n=0;n<20&&task.active();n++)task.update(o(now+=100_000_000),port);
  check(task.active()&&port.here.equals(p(100)),"return route recovers after a long obstruction");
  port=new Port();port.bodies.put(1L,p(101));port.reject=true;port.deferralFull=true;
  task=new AssignedTaskModule();task.assign(order(105),null,p(100));now=1;
  for(int n=0;n<CorpseCleanupExecution.MAX_ATTEMPTS;n++){task.update(o(now),port);now+=CorpseCleanupExecution.RETRY_NANOS;}
  check(task.active()&&port.held.isEmpty()&&task.snapshot().reason().equals("cleanup_waiting_deferral_capacity"),"full deferral capacity releases ownership and backs off without terminating order");
  port=new Port();port.bodies.put(1L,p(101));port.reject=true;task=new AssignedTaskModule();task.assign(order(105),null,p(100));
  var backgroundUpdates=new java.util.concurrent.atomic.AtomicInteger();
  HumanModule background=new HumanModule(){
   public boolean available(){return true;}public boolean eligible(HumanObservation o){return true;}
   public void update(HumanObservation o,HumanActuator a){backgroundUpdates.incrementAndGet();}
   public void interrupt(long n,HumanActuator a){}public void suspend(long n,HumanActuator a){}public void resume(HumanObservation.Identity i,long n,HumanActuator a){}public void stop(long n,HumanActuator a){}
  };
  var runtime=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("assigned-task",100,100,task),new HumanModuleRuntime.Entry("background",1000,100,background)));
  runtime.update(o(1),port);runtime.update(o(100_000_001L),port);
  check("background".equals(runtime.selectedModule())&&backgroundUpdates.get()==1&&task.active(),"production scheduler runs routine during retry backoff while retaining intent");
  port.reject=false;runtime.update(o(1_000_000_001L),port);
  check("assigned-task".equals(runtime.selectedModule())&&task.active(),"scheduled intent takes control back when its retry becomes due");
  port=new Port();port.bodies.put(1L,p(101));task=new AssignedTaskModule();task.assign(order(105),null,p(100));task.update(o(-10_000_000_000L),port);
  check(port.routes.size()==1&&task.active(),"initial intent also executes under a negative monotonic clock origin");task.stop(-9_000_000_000L,port);
 }
 static void passiveIntent(){
  class RoamingPort extends Port {
   int searches,roamStarts;boolean partial;
   List<String> selected=new ArrayList<>();
   public Search find(Point source,int radius,Point dest,long now){searches++;return partial?new Search(null,false,true):super.find(source,radius,dest,now);}
   public boolean startMovement(MovementRequest request,long now){
    if(request instanceof MovementRequest.Pattern){roamStarts++;goal=p(here.x()+20);return true;}
    return super.startMovement(request,now);
   }
   public void moduleChanged(String previous,String next){selected.add(next);}
  }
  var port=new RoamingPort();port.here=p(110);
  var task=new AssignedTaskModule();task.assign(order(105),null,p(100));
  var threatened=new java.util.concurrent.atomic.AtomicBoolean();
  HumanModule defense=new HumanModule(){
   public boolean available(){return true;}public boolean eligible(HumanObservation o){return threatened.get();}
   public void update(HumanObservation o,HumanActuator a){}
   public void interrupt(long n,HumanActuator a){}public void suspend(long n,HumanActuator a){}public void resume(HumanObservation.Identity i,long n,HumanActuator a){}public void stop(long n,HumanActuator a){}
  };
  var runtime=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("defense",10,100,defense),new HumanModuleRuntime.Entry("assigned-task",100,100,task),new HumanModuleRuntime.Entry("walk",1000,100,new WalkingModule(3_000_000_000L))));
  long now=1;
  for(int n=0;n<120;n++)runtime.update(o(now+=100_000_000),port);
  check(port.searches>=5&&port.selected.equals(List.of("walk")),"repeated empty scans never preempt the real walking module");
  check(port.roamStarts>0&&port.here.x()>110&&task.active(),"roaming pause can finish and NPC physically roams while cleanup intent stays assigned");
  check(port.routes.isEmpty(),"empty intent never routes back to captured source before any work");
  port.partial=true;int before=port.here.x();
  for(int n=0;n<60;n++)runtime.update(o(now+=100_000_000),port);
  check(port.here.x()>before&&"walk".equals(runtime.selectedModule()),"partial bounded discovery also leaves roaming in control");
  port.partial=false;port.bodies.put(1L,p(101));
  for(int n=0;n<30&&port.held.isEmpty();n++)runtime.update(o(now+=100_000_000),port);
  check("assigned-task".equals(runtime.selectedModule())&&!port.held.isEmpty(),"discovered corpse activates cleanup without another dialogue command");
  threatened.set(true);runtime.update(o(now+=100_000_000),port);
  check("defense".equals(runtime.selectedModule())&&port.held.isEmpty()&&task.active(),"defense overrides cleanup and releases the corpse while preserving intent");
  threatened.set(false);runtime.update(o(now+=100_000_000),port);
  check("assigned-task".equals(runtime.selectedModule())&&!port.held.isEmpty(),"cleanup reclaims body after defense ends");
  task.interrupt(now,port);task.assign(new HumanTaskCommand(HumanTaskCommand.Kind.RESUME_ROUTINE,Map.of()),null,port.here);
  int scanned=port.searches;
  for(int n=0;n<50;n++)runtime.update(o(now+=100_000_000),port);
  check(!task.active()&&"walk".equals(runtime.selectedModule())&&port.searches==scanned,"resume routine removes passive intent as well as active cleanup");
 }
 static IsoDeadBody body(IsoCell cell,int x,int y){var b=new IsoDeadBody();b.x=x+.5f;b.y=y+.5f;b.square=cell.getGridSquare(x,y,0);b.square.addCorpse(b,true);return b;}
 static void nativeAdapter(HumanProfile profile){
  var cell=new IsoCell();var actor=new ServerHuman(new ServerHumanDefinition("corpse-native","CorpseNative","Native",100.5f,200.5f,0,IsoDirections.E,false,"Tourist",profile.id()),(short)1200,cell);
  var adapter=actor.actuator.corpses();var b=body(cell,102,202);var inventory=b.inventory;
  var outside=body(cell,103,200);var animal=body(cell,99,200);animal.animal=true;var fake=body(cell,100,201);fake.fake=true;
  var search=adapter.find(p(100),2,p(109));check(search.body()!=null&&search.body().position().equals(new Point(102,202,0)),"square radius includes two-tile diagonal; animals/fake-dead/outside radius skipped");
  long token=search.body().token();check(adapter.claim(token),"native corpse claim accepted");
  check(adapter.approaches(token).get(0).equals(new Point(103,202,0))&&adapter.approaches(token).get(1).equals(new Point(101,202,0)),"native head and feet approaches follow corpse orientation");
  b.angle=(float)(Math.PI/2);
  check(adapter.approaches(token).get(0).equals(new Point(102,203,0))&&adapter.approaches(token).get(1).equals(new Point(102,201,0)),"rotating corpse rotates its approach endpoints");b.angle=0;
  check(adapter.find(p(100),2,p(109)).body().token()==token,"discovery cannot invalidate an owned body token");
  var other=new ServerHuman(new ServerHumanDefinition("corpse-other","CorpseOther","Other",100.5f,200.5f,0,IsoDirections.E,false,"Tourist",profile.id()),(short)1201,cell);
  check(other.actuator.corpses().find(p(100),2,p(109)).body()==null,"another bot cannot acquire the same corpse");
  check(!adapter.move(token,p(109)),"native adapter refuses teleporting corpse across route");
  check(!adapter.move(token,new Point(102,201,0)),"native adapter refuses moving corpse to a tile the actor has not reached");
  actor.player.setX(102.5f);actor.player.setY(201.5f);
  b.square.blockedEdges.add("0,-1");check(!adapter.move(token,new Point(102,201,0)),"corpse cannot cross a newly blocked wall edge");b.square.blockedEdges.clear();
  check(adapter.move(token,new Point(102,201,0))&&b.inventory==inventory&&b.square.statics.contains(b),"native relocation preserves corpse instance, inventory and world membership");
  adapter.release(token);check(!adapter.move(token,p(102)),"released corpse cannot be moved");
  check(adapter.permitted(p(109))&&!actor.walking.permitted(new zombie.apocalipse.humans.movement.MovementGrid.Tile(109,200)),"cleanup scope does not widen patrol scope");
  b.square.removeCorpse(b,false);check(adapter.resolve(token)==null,"world removal invalidates native corpse token");
  actor.dispose();other.dispose();
  var cooldown=new ServerHuman(new ServerHumanDefinition("corpse-cooldown","CorpseCooldown","Cooldown",100.5f,200.5f,0,IsoDirections.E,false,"Tourist",profile.id()),(short)1204,cell);
  var delayed=body(cell,100,200);var cp=cooldown.actuator.corpses();var selected=cp.find(p(100),0,p(109),1).body();
  check(cp.claim(selected.token()),"in-flight body is owned before a delivery obstruction");cooldown.player.setX(101.5f);
  check(cp.move(selected.token(),p(101)),"body can leave original search radius before delivery is deferred");
  check(cp.defer(selected.token(),300_000_000_001L),"native adapter records cooldown by corpse identity");
  var pending=cp.find(p(100),0,p(109),2);
  check(pending.body()==null&&pending.retryAtNanos()==300_000_000_001L,"native discovery skips deferred corpse and reports exact wake-up time");
  var ready=cp.find(p(100),0,p(109),300_000_000_001L);
  check(ready.body()!=null&&ready.body().position().equals(p(101))&&cp.resolve(ready.body().token())!=null,"native corpse outside original radius becomes eligible at cooldown boundary");
  check(cp.claim(ready.body().token()),"cooldown does not retain a stale claim");cp.release(ready.body().token());
  delayed.square.removeCorpse(delayed,false);cooldown.dispose();
  var bounded=new ServerHuman(new ServerHumanDefinition("corpse-bounded","CorpseBounded","Bounded",100.5f,200.5f,0,IsoDirections.E,false,"Tourist",profile.id()),(short)1202,cell);
  for(int n=0;n<130;n++)body(cell,100,200).animal=true;
  body(cell,100,200);
  check(!bounded.actuator.corpses().find(p(100),0,p(109)).complete(),"corpse discovery respects 128-object budget");
  check(bounded.actuator.corpses().find(p(100),0,p(109)).body()!=null,"bounded scan resumes instead of starving deep body piles");bounded.dispose();
 }
 static void dialogue(HumanProfile profile){
  var factory=ServerHumanFactory.getInstance();factory.tick(0);var player=DialogueTest.visitor((short)1);player.x=102;player.y=200;
  var option=new HashMap<String,Object>(DialogueTest.choice("cleanup","root"));option.put("close",true);option.put("task",order(109));
  var graph=Map.<String,Object>of("id","corpse-orders","revision",1,"entryNode","root","fallbackLocale","EN","nodes",Map.of("root",Map.of("texts",Map.of("EN",Map.of("title","Orders","description","")),"options",List.of(option))));
  zombie.apocalipse.humans.dialogue.HumanDialogueCatalog.publish(Map.of(profile.id(),graph));
  short id=factory.spawn(new ServerHumanDefinition("corpse-dialogue","CorpseDialogue","Dialogue",100.5f,200.5f,0,IsoDirections.E,false,"Tourist",profile.id()));var actor=ServerHumanFactory.tradingHuman(id);
  var body=body(actor.player.getCell(),98,200);long now=System.nanoTime();
  var lease=ServerHumanDialogue.graphBegin(player,id,1,"root","cleanup",now);int token=DialogueTest.token(lease);check(ServerHumanDialogue.arrive(player,id,token,now),"cleanup uses normal authenticated dialogue arrival");
  check(ServerHumanDialogue.graphSelect(player,id,token,"root",1,"cleanup",1,now).rawget("error")==null,"dialogue metadata assigns cleanup");
  var task=ServerHumanTasks.module(actor);check(task.active()&&actor.activity.mode()==HumanActivityController.Mode.DIALOGUE,"cleanup waits for exclusive conversation release");
  actor.activity.finishDialogue(now,actor.actuator); // Fixture releases activity; production Lua finishes the lease.
  actor.player.setX(101.5f); // The captured center must not follow later actor movement.
  task.update(o(now),actor.actuator);check(task.snapshot().status()==AssignedTaskModule.Status.RUNNING,"source is NPC selection tile, not the participant tile two tiles away");
  check(actor.actuator.corpses().resolve(1)!=null,"cleanup acquires a body within the fixed NPC-centered area");
  factory.remove("corpse-dialogue");check(body.square!=null,"dialogue task cancellation preserves body");
 }
 static void movementOccupancy(HumanProfile profile){
  var cell=new IsoCell();
  var actor=new ServerHuman(new ServerHumanDefinition("occupancy","CorpseOccupancy","Occupancy",100.5f,200.5f,0,IsoDirections.E,false,"Tourist",profile.id()),(short)1203,cell);
  var destination=cell.getGridSquare(101,200,0);var dead=new zombie.characters.IsoZombie();dead.x=101.5f;dead.y=200.5f;dead.dead=true;dead.square=destination;destination.moving.add(dead);
  check(!destination.isFree(true)&&destination.isFree(false),"fixture reproduces vanilla moving-object occupancy even for dead characters");
  check(actor.movement.occupancyAllows(100.9f,200.5f,101.1f,200.5f),"dead moving character does not block crossing into its tile");
  dead.dead=false;dead.health=0;
  check(actor.movement.occupancyAllows(100.9f,200.5f,101.1f,200.5f),"zero-health character does not block before death flag settles");
  var route=List.of(new zombie.apocalipse.humans.movement.MovementGrid.Tile(100,200),new zombie.apocalipse.humans.movement.MovementGrid.Tile(101,200),new zombie.apocalipse.humans.movement.MovementGrid.Tile(102,200));
  actor.movement.start(route,0);HumanActuator.MotionStatus result=HumanActuator.MotionStatus.RUNNING;
  for(int n=1;n<=20&&result==HumanActuator.MotionStatus.RUNNING;n++)result=actor.movement.update(n*100_000_000L);
  check(result==HumanActuator.MotionStatus.SUCCEEDED&&actor.player.getXi()==102,"production route executes through stale dead occupant");
  actor.player.setX(100.5f);actor.player.setY(200.5f);dead.health=1;
  check(!actor.movement.occupancyAllows(100.9f,200.5f,101.1f,200.5f),"live zombie still blocks entering its occupied tile");
  actor.movement.start(route,0);for(int n=1;n<=20;n++){result=actor.movement.update(n*100_000_000L);if(result!=HumanActuator.MotionStatus.RUNNING)break;}
  check(result==HumanActuator.MotionStatus.FAILED&&actor.movement.lastFailure().reason().equals("body_occupied"),"live character collision remains enforced in production route");
  check(zombie.debug.DebugLog.messages.stream().anyMatch(s->s.contains("reason=body_occupied")&&s.contains("IsoZombie")&&s.contains("/blocks=true/dead=false/health=1.0")),"occupancy failure identifies the actual blocker and health");
  destination.moving.clear();dead.x=100.8f;dead.square=cell.getGridSquare(100,200,0);dead.square.moving.add(dead);actor.player.setX(100.5f);
  actor.movement.start(route,0);actor.movement.update(100_000_000L);
  check(zombie.debug.DebugLog.messages.stream().anyMatch(s->s.contains("reason=body_occupied")&&s.contains("occupancyTile=Tile[x=100, y=200]")&&s.contains("IsoZombie")),"diagnostic describes blocking origin square instead of unrelated next waypoint");dead.square.moving.clear();
  destination.moving.clear();destination.moving.add(actor.player);
  check(actor.movement.occupancyAllows(100.9f,200.5f,101.1f,200.5f),"actor's own native square membership does not block movement");
  destination.moving.clear();var corpse=body(cell,101,200);destination.moving.add(corpse);
  check(actor.movement.occupancyAllows(100.9f,200.5f,101.1f,200.5f)&&actor.movement.bodyClear(100.9f,200.5f,101.1f,200.5f),"corpse representation is nonblocking in ordinary and tactical occupancy checks");
  destination.free=false;check(!actor.movement.occupancyAllows(100.9f,200.5f,101.1f,200.5f),"dead occupants never relax static geometry");destination.free=true;
  destination.moving.clear();destination.occupied=true;check(!actor.movement.occupancyAllows(100.9f,200.5f,101.1f,200.5f),"unexplained occupancy remains fail-closed");destination.occupied=false;
  destination.moving.clear();destination.moving.add(new IsoZombieGiblets());
  check(actor.movement.occupancyAllows(100.9f,200.5f,101.1f,200.5f),"giblets do not block normal movement");
  check(actor.movement.bodyClear(100.9f,200.5f,101.1f,200.5f),"giblets do not block combat or emergency clearance");
  check(zombie.apocalipse.humans.traversal.BodyOccupancy.free(destination,actor.player),"traversal landing ignores giblets");
  actor.dispose();
  check(zombie.debug.DebugLog.messages.stream().anyMatch(s->s.contains("corpse search key=")&&s.contains("result=selected")&&s.contains("corpses=")),"corpse discovery emits selected-body diagnostics independently of movement failures");
  check(zombie.debug.DebugLog.messages.stream().anyMatch(s->s.contains("corpse search key=")&&s.contains("result=complete")&&s.contains("source=")),"empty/completed search reports the actual source and scan result");
 }
 public static void main(String[]args){execution();intentRetries();passiveIntent();var profile=profile();nativeAdapter(profile);dialogue(profile);movementOccupancy(profile);System.out.println("Passed "+checks+" corpse cleanup execution, native and dialogue assertions.");}
}
'''

def main():
    with tempfile.TemporaryDirectory(prefix='apocbr-corpses-') as folder:
        work=Path(folder); fixture=dict(FILES)
        fixture['zombie/apocalipse/humans/DialogueTest.java']=DIALOGUE
        fixture['zombie/apocalipse/humans/CorpsesTest.java']=HARNESS
        sources=[]
        for name,content in fixture.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
        sources.extend(sorted((ROOT/'42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
        args=work/'sources.txt';args.write_text('\n'.join('"'+str(s).replace('\\','/')+'"' for s in sources),encoding='utf-8')
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-encoding','UTF-8','-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-Dapocbr.npc.logLevel=DEBUG','-cp',str(work/'classes'),'zombie.apocalipse.humans.CorpsesTest'],check=True)

if __name__=='__main__':main()
