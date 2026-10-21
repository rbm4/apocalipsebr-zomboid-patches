"""Production module scheduling and NPC-owned persistence lifecycle with explicit engine/IO stubs."""
from pathlib import Path
import subprocess,tempfile,sys
sys.dont_write_bytecode=True
from test_server_humans import ROOT,FILES
HARNESS=r'''
package zombie.apocalipse.humans;
import java.util.*;import zombie.apocalipse.humans.brain.*;import zombie.apocalipse.humans.profiles.*;
import zombie.savefile.ApocBRNpcStore;import zombie.iso.IsoDirections;
public class NpcRuntimeTest {
 static int checks;
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static void invalid(Runnable r){try{r.run();throw new AssertionError("invalid accepted");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
 static class Port implements HumanActuator {
  int roams,stops,says,homes;public boolean face(HumanObservation.Identity i){return true;}
  public boolean startRoaming(long n){roams++;return true;}public MotionStatus updateRoaming(long n){return MotionStatus.RUNNING;}
  public void stopMoving(){stops++;}public boolean startEmote(String n){return true;}public void stopEmote(){}
  public boolean say(String t){says++;return true;}public boolean returnHome(){homes++;return true;}
 }
 static class Module implements HumanModule {
  boolean eligible;int updates,interrupts,suspends,resumes,stops;boolean broken;
  public boolean eligible(HumanObservation o){return eligible;}public boolean available(){return true;}
  public void update(HumanObservation o,HumanActuator a){updates++;if(broken)throw new IllegalStateException();}
  public void interrupt(long n,HumanActuator a){interrupts++;}public void suspend(long n,HumanActuator a){suspends++;}
  public void resume(HumanObservation.Identity v,long n,HumanActuator a){resumes++;}public void stop(long n,HumanActuator a){stops++;}
 }
 static HumanObservation observe(long ms,List<HumanObservation.Visitor> visitors){return new HumanObservation(ms*1000000L,visitors,true);}
 static ServerHumanDefinition definition(String key){return new ServerHumanDefinition(key,"npc-"+key,key,100,200,0,IsoDirections.E,false,"Tourist","lobby-guide");}
 static ApocBRNpcStore.Restored restored(String key,HumanLifecycle.State state,boolean activate,boolean retired){return new ApocBRNpcStore.Restored(new ApocBRNpcStore.State(definition(key),state,4,1,110,210,0,20,retired),activate);}
 public static void main(String[] args)throws Exception {
  var port=new Port();var high=new Module();var low=new Module();low.eligible=true;
  var runtime=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("low",100,100,low),new HumanModuleRuntime.Entry("high",1,500,high)));
  runtime.update(observe(0,List.of()),port);check(low.updates==1&&high.updates==0,"eligible fallback selected");
  high.eligible=true;runtime.update(observe(100,List.of()),port);check(low.updates==2,"module eligibility respects its configured cycle");
  runtime.update(observe(500,List.of()),port);check(high.updates==1&&low.interrupts==1,"higher priority reaction preempts and cancels movement owner");
  runtime.update(observe(600,List.of()),port);check(high.updates==2,"selected module advances every simulation tick regardless of selection interval");
  high.eligible=false;runtime.update(observe(700,List.of()),port);check(low.updates==3&&high.interrupts==1,"module relinquishes ownership");
  runtime.suspend(800000000L,port);runtime.update(observe(900,List.of()),port);check(low.updates==3,"exclusive interaction suspends arbitration");
  runtime.resume(null,1000000000L,port);runtime.update(observe(1100,List.of()),port);check(low.updates==4&&low.resumes==1&&high.resumes==1,"interaction releases all reusable policies");
  runtime.stop(1200000000L,port);runtime.stop(1300000000L,port);check(low.stops==1&&high.stops==1,"stop cleans each policy once");
  var a=new Module();a.eligible=true;var b=new Module();b.eligible=true;
  var ties=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("z",5,100,b),new HumanModuleRuntime.Entry("a",5,100,a)));
  ties.update(observe(0,List.of()),port);check(a.updates==1&&b.updates==0,"deterministic module ID tie break");
  a.broken=true;var owner=new HumanActivityController(ties,true,true);owner.update(observe(100,List.of()),port);
  check(owner.mode()==HumanActivityController.Mode.STOPPED&&a.stops==1&&b.stops==1,"failure contained by common control boundary");
  var pausedModule=new Module();pausedModule.eligible=true;
  var paused=new HumanActivityController(new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("pause",1,100,pausedModule))),true,true);
  paused.setBlocked(true,0,port);paused.update(observe(100,List.of()),port);
  check(pausedModule.updates==0&&pausedModule.suspends==1,"availability gate pauses policies");
  check(!paused.beginDialogue(new HumanObservation.Identity((short)1,1),0,port),"availability gate refuses new exclusive leases");
  paused.setBlocked(false,200000000L,port);paused.update(observe(300,List.of()),port);
  check(pausedModule.updates==1&&pausedModule.resumes==1,"availability recovery resumes policies");
  var registry=ServerHumanProfiles.registry();var profile=registry.require("lobby-guide");
  invalid(()->registry.register(new HumanProfile("unknown-module",1,"modules",Map.of(),"","none",true,false,List.of(new HumanModuleBinding("fire-weapon",10,100,Map.of())),8,100,true)));
  var one=registry.create(profile);var two=registry.create(profile);check(one!=two,"per-NPC module execution state");
  var visitor=new HumanObservation.Visitor(new HumanObservation.Identity((short)3,100),101,200,0,1);
  var actual=new Port();
  for(long ms=0;ms<=22000;ms+=100)one.update(observe(ms,ms>=4000?List.of(visitor):List.of()),actual);
  check(actual.says==1,"greeting uses per-visitor cooldown across module selection");
  check(actual.homes==1&&actual.roams>=2,"completed greeting relinquishes control and roaming resumes");
  check(actual.stops>=1,"greeting preemption cancels active route");
  var freshPort=new Port();two.update(observe(23000,List.of(visitor)),freshPort);check(freshPort.roams==0,"another NPC can independently begin greeting");
  // Hydration normalizes active/spawning into dormant logical records before fresh activation.
  ApocBRNpcStore.enabled=true;
  ApocBRNpcStore.loaded=new ApocBRNpcStore.Loaded(List.of(profile,registry.require("stationary")),List.of(
   restored("active",HumanLifecycle.State.ACTIVE,true,false),restored("dormant",HumanLifecycle.State.DORMANT,false,false),
   restored("dead",HumanLifecycle.State.DEAD,true,false),restored("retired",HumanLifecycle.State.DEAD,false,true)));
  var factory=ServerHumanFactory.getInstance();factory.tick(0);
  check(factory.lifecycle("active").state()==HumanLifecycle.State.ACTIVE&&factory.lifecycle("active").activations()==5,"logical identity survives restart while activation is fresh");
  check(factory.lifecycle("dormant").state()==HumanLifecycle.State.DORMANT,"dormant actor has no presentation");
  check(factory.lifecycle("dead").state()==HumanLifecycle.State.DEAD,"death survives restart");
  invalid(()->factory.activate("dead"));invalid(()->factory.registerDormant(definition("retired")));
  var human=ServerHumanFactory.dialogueHumans().iterator().next();check(human.player.getX()==110&&human.player.getY()==210,"presentation restored from logical position");
  check(human.player.getOnlineID()>=1024,"fresh presentation ID independent of saved logical identity");
  int writes=ApocBRNpcStore.offers.size();factory.tick(1000000000L);check(ApocBRNpcStore.offers.size()==writes,"unchanged state does not enqueue another checkpoint");
  human.player.setX(115);factory.tick(2000000000L);var saved=ApocBRNpcStore.offers.getLast();check(saved.x()==115&&saved.revision()>20,"position mutation produces detached checkpoint");
  human.player.setX(120);check(saved.x()==115,"queued snapshots do not retain live actors");
  factory.markDead("active");check(ApocBRNpcStore.offers.getLast().lifecycle()==HumanLifecycle.State.DEAD,"death mutation checkpointed immediately");
  factory.remove("active");check(ApocBRNpcStore.offers.getLast().retired(),"administrative removal creates durable tombstone");
  invalid(()->factory.registerDormant(definition("active")));
  System.out.println("Passed "+checks+" module scheduling, cancellation, isolation and persistence lifecycle assertions.");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='apocbr-npc-runtime-') as folder:
    work=Path(folder);fixture=dict(FILES)
    stub=fixture['zombie/savefile/ApocBRNpcStore.java']
    stub=stub.replace('public static boolean enabled(){return false;}', 'public static boolean enabled;public static Loaded loaded;public static List<State> offers=new ArrayList<>();public static boolean enabled(){return enabled;}')
    stub=stub.replace('throw new AssertionError("Unexpected database initialization");','return new ApocBRNpcStore();').replace('public Loaded takeLoaded(){return null;}', 'public Loaded takeLoaded(){var v=loaded;loaded=null;return v;}').replace('public void offer(State s){}','public void offer(State s){offers.add(s);}')
    fixture['zombie/savefile/ApocBRNpcStore.java']=stub
    fixture['zombie/apocalipse/humans/NpcRuntimeTest.java']=HARNESS
    sources=[]
    for name,content in fixture.items():
        p=work/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8');sources.append(p)
    sources.extend(sorted((ROOT/'42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
    args=work/'sources.txt';args.write_text('\n'.join('"'+str(s).replace('\\','/')+'"' for s in sources),encoding='utf-8')
    subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-encoding','UTF-8','-d',str(work/'classes'),'@'+str(args)],check=True)
    subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',str(work/'classes'),'zombie.apocalipse.humans.NpcRuntimeTest'],check=True)
