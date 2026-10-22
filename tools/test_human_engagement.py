"""Production policy, target retention, arbitration and native combat handoff scaffold."""
from pathlib import Path
import subprocess
import sys
import tempfile
sys.dont_write_bytecode = True
from test_server_humans import FILES, ROOT

HARNESS=r"""
package zombie.apocalipse.humans;
import java.util.*;
import java.util.concurrent.atomic.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.state.*;
import static zombie.apocalipse.humans.brain.HumanSenses.*;
import static zombie.apocalipse.humans.brain.HumanCombat.*;
import static zombie.apocalipse.humans.brain.HumanEngagement.*;
import zombie.iso.*;
import zombie.characters.*;
public class HumanEngagementTest {
 static int checks;static long STEP=100_000_000L;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void invalid(Runnable r){try{r.run();throw new AssertionError("invalid accepted");}catch(IllegalArgumentException expected){checks++;}}
 static Identity id(int n){return new Identity(Kind.ZOMBIE,(short)n,n,n);}
 static Contact contact(int n,long seen,boolean visible,double distance){return new Contact(id(n),101+n,200,0,distance,0,seen,visible);}
 static HumanObservation observation(long now,long sample,List<Contact> contacts,Set<Identity> inside,HumanState state){
  return new HumanObservation(now,List.of(),true,state,new Frame(true,sample,contacts,Coverage.COMPLETE,Coverage.PARTIAL,false,new Work(0,0,0,0,0)),new HumanTerritory(true,true,inside));
 }
 static HumanObservation observation(long now,Contact... contacts){return observation(now,now,List.of(contacts),Arrays.stream(contacts).map(Contact::identity).collect(java.util.stream.Collectors.toSet()),HumanState.INITIAL);}
 static final class Port implements HumanActuator {
  int begins,cancels,polls,stops;Result output=new Result(Status.RUNNING,Reason.NONE);
  public HumanCombat.Port combat(){return (request,now)->{begins++;return new Execution(){
   boolean cancelled;public Result update(long at){polls++;return output;}
   public void cancel(Reason reason){if(!cancelled){cancelled=true;cancels++;}}
  };};}
  public void stopMoving(){stops++;}public boolean face(HumanObservation.Identity id){return true;}
  public boolean startEmote(String name){return true;}public void stopEmote(){}public boolean say(String text){return true;}public boolean returnHome(){return true;}
 }
 public static void main(String[] ignored)throws Exception{
  var selector=new Selector();var settings=Settings.DEFAULT;
  var a=contact(1,0,true,9);var b=contact(2,0,true,4);
  var first=selector.preview(observation(0,a,b),GUARD_ZOMBIES,settings);
  check(first.target().identity().equals(b.identity()),"nearest eligible zombie chosen");
  check(selector.target()==null,"eligibility preview never mutates target ownership");selector.retain(first);
  var retained=selector.preview(observation(STEP,contact(1,STEP,true,1),contact(2,STEP,true,8)),GUARD_ZOMBIES,settings);
  check(retained.target().identity().equals(a.identity()),"immediate closer threat overrides retained pursuit target");
  var stable=selector.preview(observation(STEP,contact(1,STEP,true,4),contact(2,STEP,true,8)),GUARD_ZOMBIES,settings);
  check(stable.target().identity().equals(b.identity()),"non-immediate nearer challenger does not interrupt pursuit");
  var jitter=selector.preview(observation(STEP,contact(1,STEP,true,1),contact(2,STEP,true,1.21)),GUARD_ZOMBIES,settings);
  check(jitter.target().identity().equals(b.identity()),"small distance changes inside reach retain target without flapping");
  var lostDanger=selector.preview(observation(STEP,contact(1,STEP,true,1),contact(2,0,false,8)),GUARD_ZOMBIES,settings);
  check(lostDanger.target().identity().equals(a.identity()),"immediate threat overrides watching an occluded target");
  var watch=selector.preview(observation(2*STEP,contact(2,STEP,false,8)),GUARD_ZOMBIES,settings);
  check(watch.choice()==Choice.WATCH,"lost visibility holds intent without attacking last-known coordinates");
  check(selector.preview(observation(12*STEP,contact(2,STEP,false,8)),GUARD_ZOMBIES,settings).choice()==Choice.IGNORE,"target released after lost-contact grace");
  check(selector.preview(observation(STEP,0,List.of(a,b),Set.of(a.identity()),HumanState.INITIAL),GUARD_ZOMBIES,settings).target().identity().equals(a.identity()),"territory exit releases held target and permits another");
  check(new Selector().preview(observation(6*STEP,0,List.of(a),Set.of(a.identity()),HumanState.INITIAL),GUARD_ZOMBIES,settings).choice()==Choice.IGNORE,"stale sample cannot acquire a target");
  var player=new Contact(new Identity(Kind.PLAYER,(short)1,1,1),0,0,0,0,0,0,true);
  check(new Selector().preview(observation(0,player),GUARD_ZOMBIES,settings).choice()==Choice.IGNORE,"player contact never grants aggression");
  var downed=HumanStateReducer.reduce(HumanState.INITIAL,new HumanMutation.Mobility(false,true));
  check(new Selector().preview(observation(0,0,List.of(a),Set.of(a.identity()),downed),GUARD_ZOMBIES,settings).choice()==Choice.IGNORE,"incapacitated actor cannot engage");
  check(new Selector().preview(new HumanObservation(0,List.of()),GUARD_ZOMBIES,settings).choice()==Choice.IGNORE,"unsampled/unavailable territory fails closed");
  invalid(()->Settings.fromMilliseconds(Map.of("pace",1L)));invalid(()->Settings.fromMilliseconds(Map.of("retryMs",0L)));
  invalid(()->Settings.fromMilliseconds(Map.of("maxSampleAgeMs",501L)));

  var port=new Port();var module=new EngageZombiesModule(settings);
  check(module.eligible(observation(0,a))&&module.eligible(observation(0,a))&&module.target()==null&&port.begins==0,"repeated scheduler probes are pure");
  module.update(observation(0,a),port);module.update(observation(STEP,contact(1,STEP,true,9)),port);
  check(port.begins==1&&port.polls==2,"one combat execution polled across ticks");
  module.update(observation(2*STEP,contact(1,STEP,false,9)),port);
  check(port.cancels==1&&module.target().equals(a.identity()),"watch cancels combat while briefly retaining target");
  module.update(observation(3*STEP,contact(1,3*STEP,true,9)),port);check(port.begins==2,"reacquisition starts a fresh execution");
  module.suspend(4*STEP,port);module.suspend(4*STEP,port);check(port.cancels==2&&!module.eligible(observation(4*STEP,a)),"suspension cancels exactly once");
  module.resume(null,5*STEP,port);module.update(observation(5*STEP,contact(1,5*STEP,true,9)),port);
  module.interrupt(6*STEP,port);module.interrupt(6*STEP,port);check(port.cancels==3&&module.target()==null,"preemption releases target and execution exactly once");
  module.update(observation(7*STEP,contact(1,7*STEP,true,9)),port);module.stop(8*STEP,port);
  check(!module.available()&&port.cancels==4,"stop cleans up and prevents reuse");
  var unsupported=new EngageZombiesModule(settings);var fallback=new Port();fallback.output=new Result(Status.UNSUPPORTED,Reason.NOT_IMPLEMENTED);
  unsupported.update(observation(0,a),fallback);unsupported.interrupt(STEP,fallback);
  check(!unsupported.eligible(observation(STEP,contact(1,STEP,true,9)))&&fallback.begins==1,"unsupported outcome backs off even after scheduler interruption");
  unsupported.update(observation(20*STEP,contact(1,20*STEP,true,9)),fallback);
  check(fallback.begins==2&&unsupported.lastResult().status()==Status.UNSUPPORTED,"retry deadline permits a later attempt, never fake success");
  var closeRetry=new EngageZombiesModule(settings);var closePort=new Port();closePort.output=new Result(Status.FAILED,Reason.UNREACHABLE);
  closeRetry.update(observation(0,contact(1,0,true,9)),closePort);
  check(!closeRetry.eligible(observation(STEP,contact(2,STEP,true,1))),"urgent combat retry still has a minimum bounded backoff");
  check(closeRetry.eligible(observation(3*STEP,contact(2,3*STEP,true,1))),"an approaching threat bypasses the ordinary two-second combat retry after bounded backoff");
  var done=new EngageZombiesModule(settings);var completed=new Port();completed.output=new Result(Status.COMPLETED,Reason.NONE);
  done.update(observation(0,a),completed);check(done.target()==null&&done.lastResult().status()==Status.COMPLETED,"completed backend releases engagement");

  var bg=new AtomicInteger();var interrupts=new AtomicInteger();
  HumanModule background=new HumanModule(){
   public boolean eligible(HumanObservation o){return true;}public boolean available(){return true;}
   public void update(HumanObservation o,HumanActuator p){bg.incrementAndGet();}public void interrupt(long n,HumanActuator p){interrupts.incrementAndGet();}
   public void suspend(long n,HumanActuator p){}public void resume(HumanObservation.Identity i,long n,HumanActuator p){}public void stop(long n,HumanActuator p){}
  };
  var selected=new EngageZombiesModule(settings);var runtime=new HumanModuleRuntime(List.of(
   new HumanModuleRuntime.Entry("engage-zombies",100,100,selected),new HumanModuleRuntime.Entry("roam",1000,100,background)));
  var arbitration=new Port();runtime.update(observation(0),arbitration);runtime.update(observation(STEP,contact(1,STEP,true,9)),arbitration);
  check(bg.get()==1&&interrupts.get()==1&&arbitration.begins==1,"threat module preempts background through existing scheduler");
  var urgent=new EngageZombiesModule(settings);var urgentPort=new Port();
  urgent.update(observation(0,contact(1,0,true,9)),urgentPort);
  urgent.update(observation(STEP,contact(1,STEP,true,9),contact(2,STEP,true,1)),urgentPort);
  check(urgentPort.cancels==1&&urgentPort.begins==2&&urgent.target().equals(id(2)),"immediate challenger cancels owned attack and starts a fresh execution without retry delay");
  runtime.update(observation(20*STEP),arbitration);check(arbitration.cancels==1&&bg.get()==2,"lost target returns control to background");

  var registry=ServerHumanProfiles.registry();var profile=registry.require("militia-patrol");
  check(!profile.dialogue()&&!profile.trading()&&registry.create(profile)!=registry.create(profile),"profile disables exclusive interactions and creates fresh module state");
  var cell=new IsoCell();var building=new BuildingDef();cell.getGridSquare(100,200,0).building=building;cell.getGridSquare(101,200,0).building=building;
  var actor=new ServerHuman(new ServerHumanDefinition("guard","guard","guard",100.5f,200.5f,0,IsoDirections.E,false,"Tourist","militia-patrol"),(short)1050,cell);
  var zombie=new IsoZombie();zombie.x=101.5f;zombie.y=200.5f;zombie.online=3;zombie.instanceId=300;
  zombie.square=cell.getGridSquare(101,200,0);zombie.square.moving.add(zombie);cell.objects.add(zombie);
  var nativeObservation=actor.actuator.observe(0);var target=nativeObservation.senses().visible(Kind.ZOMBIE).getFirst().identity();
  check(nativeObservation.territory().actorInside()&&nativeObservation.territory().contains(target),"native adapter supplies home-building territory facts");
  var request=new Request(target,Template.GUARD_BASIC);
  var production=actor.actuator.combat().begin(request,0);
  check(production.update(0).status()==Status.RUNNING,"real port reaches the installed weapon backend");
  var last=HumanState.INITIAL;check(actor.state.snapshot().equals(last)&&zombie.health==1
    &&actor.player.getPrimaryHandItem().getFullType().equals("Base.Crowbar"),"first phase equips the fixed weapon without committing damage");
  production.cancel(Reason.PREEMPTED);
  var backendBegins=new AtomicInteger();var backendCancels=new AtomicInteger();var ids=new ArrayList<Long>();
  var service=new ServerHumanCombat(actor,(owner,victim,executionId,command,now)->{
   backendBegins.incrementAndGet();ids.add(executionId);check(owner==actor.player&&victim==zombie,"native bindings resolved only behind combat port");
   return new Execution(){public Result update(long at){return new Result(Status.RUNNING,Reason.NONE);}public void cancel(Reason why){backendCancels.incrementAndGet();}};
  });
  var running=service.begin(request,0);
  check(service.begin(request,0).update(0).reason()==Reason.BUSY&&backendBegins.get()==1,"one native combat lease per actor");
  check(running.update(STEP).status()==Status.RUNNING,"valid fresh native target progresses backend");
  cell.getGridSquare(101,200,0).building=new BuildingDef();
  check(running.update(2*STEP).reason()==Reason.OUTSIDE_TERRITORY&&backendCancels.get()==1,"live territory revalidated despite cached sensor sample");
  running.cancel(Reason.STOPPED);check(backendCancels.get()==1,"native cancellation idempotent");
  cell.getGridSquare(101,200,0).building=building;actor.perception.update(3*STEP);
  var stale=service.begin(request,10*STEP);check(stale.update(10*STEP).reason()==Reason.STALE_OBSERVATION,"stale sensor rejected before backend begin");
  actor.perception.update(11*STEP);var again=service.begin(request,11*STEP);
  check(ids.size()==2&&ids.get(1)>ids.get(0),"new execution identity allocated on the owning activation");
  cell.objects.remove(zombie);cell.objects.add(zombie);
  check(again.update(12*STEP).reason()==Reason.INVALID_TARGET&&backendCancels.get()==2,"pooled native target rejected before backend can progress");
  actor.perception.update(13*STEP);var replacement=actor.perception.snapshot().visible(Kind.ZOMBIE).getFirst().identity();
  check(!replacement.equals(target),"new membership generates a distinct sensor handle");
  actor.state.dispatch(new HumanMutation.Mobility(true,false));
  check(service.begin(new Request(replacement,Template.GUARD_BASIC),13*STEP).update(13*STEP).reason()==Reason.INCAPACITATED,"live physical restriction enforced independently of policy");
  actor.state.dispatch(new HumanMutation.Mobility(false,false));
  var failed=new ServerHumanCombat(actor,(owner,victim,e,command,now)->{throw new IllegalStateException();});
  check(failed.begin(new Request(replacement,Template.GUARD_BASIC),13*STEP).update(13*STEP).status()==Status.FAILED,"backend start exception becomes an explicit failure");
  var timed=service.begin(new Request(replacement,Template.GUARD_BASIC),13*STEP);
  check(timed.update(13*STEP+HumanCombat.MAX_EXECUTION_NANOS).reason()==Reason.TIMEOUT,"combat lease has a bounded lifetime independently of backend progress");
  var pollFailures=new AtomicInteger();var cleanup=new AtomicInteger();
  var broken=new ServerHumanCombat(actor,(owner,victim,e,command,now)->new Execution(){
   public Result update(long at){pollFailures.incrementAndGet();throw new IllegalStateException();}
   public void cancel(Reason reason){cleanup.incrementAndGet();throw new IllegalStateException();}
  });
  var brokenExecution=broken.begin(new Request(replacement,Template.GUARD_BASIC),13*STEP);
  check(brokenExecution.update(13*STEP).status()==Status.FAILED&&cleanup.get()==1,"failed polling cleans up once even if backend cancellation fails");
  check(brokenExecution.update(14*STEP).status()==Status.FAILED&&pollFailures.get()==1,"terminal execution cannot repeat backend work");
  actor.dispose();check(actor.perception.snapshot().contacts().isEmpty(),"actor disposal clears target source");
  System.out.println("Passed "+checks+" engagement policy, target, arbitration, combat lease and native validation assertions.");
 }
}
"""

def main():
    with tempfile.TemporaryDirectory(prefix='apocbr-engagement-tests-') as directory:
        work=Path(directory);fixture=dict(FILES);sources=[]
        fixture['zombie/apocalipse/humans/HumanEngagementTest.java']=HARNESS
        for name,content in fixture.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
        sources.extend(sorted((ROOT/'42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
        args=work/'sources.txt';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources))
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',str(work/'classes'),'zombie.apocalipse.humans.HumanEngagementTest'],check=True)

if __name__=='__main__':main()
