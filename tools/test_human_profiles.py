"""Run production profile assembly, generic control and logical lifecycle with engine stubs."""
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
import zombie.apocalipse.humans.profiles.*;
import zombie.iso.*;
import zombie.network.*;
public class ProfilesTest {
 static int checks;
 static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static void invalid(Runnable r){try{r.run();throw new AssertionError("invalid operation accepted");}catch(IllegalArgumentException|IllegalStateException|NullPointerException ok){checks++;}}
 static HumanProfile profile(String id,int revision,String behavior,Map<String,Long> values){return new HumanProfile(id,revision,behavior,values,"","none",true,false);}
 static ServerHumanDefinition definition(String key,String profile){return new ServerHumanDefinition(key,"npc-"+key,key,100,200,0,IsoDirections.E,false,"Tourist",profile);}
 static class Port implements HumanActuator {
  int says,waves,stops,moves;boolean fail;
  public boolean face(HumanObservation.Identity id){return true;}
  public boolean startEmote(String name){waves++;return true;}
  public void stopEmote(){stops++;}
  public boolean say(String text){says++;return !fail;}
  public boolean returnHome(){return true;}
  public void stopMoving(){moves++;}
 }
 static class Policy extends IdleBehaviorForTest { }
 static class IdleBehaviorForTest implements HumanBehavior {
  int updates,suspends,resumes,stops;boolean broken;
  public void update(HumanObservation o,HumanActuator a){updates++;if(broken)throw new IllegalStateException();}
  public void suspend(long n,HumanActuator a){suspends++;}
  public void resume(HumanObservation.Identity id,long n,HumanActuator a){resumes++;}
  public void stop(long n,HumanActuator a){stops++;}
  public boolean available(){return true;}
 }
 public static void main(String[] ignored) throws Exception {
  var registry=new HumanProfileRegistry();
  registry.registerBehavior("test",p->{if(!p.parameters().isEmpty())throw new IllegalArgumentException();},p->new Policy());
  var p=profile("test",1,"test",Map.of());registry.register(p);
  invalid(()->registry.register(p));invalid(()->registry.registerBehavior("test",x->{},x->new Policy()));
  invalid(()->registry.require("missing"));invalid(()->registry.register(profile("bad",1,"unknown",Map.of())));
  check(registry.create(p)!=registry.create(p),"behavior factory creates independent per-NPC state");
  invalid(()->registry.replace(p));registry.replace(profile("test",2,"test",Map.of()));
  check(p.revision()==1&&registry.require("test").revision()==2,"revision replacement leaves old immutable snapshot untouched");
  invalid(()->registry.replace(profile("test",3,"test",Map.of("unknown",1L))));
  check(registry.require("test").revision()==2,"invalid update does not replace accepted profile");
  var values=new HashMap<String,Long>();values.put("announcementPauseMs",900L);
  var configured=profile("configured",1,"guide",values);values.put("announcementPauseMs",1L);
  check(configured.parameters().get("announcementPauseMs")==900,"profile parameters defensively copied");
  try{configured.parameters().clear();throw new AssertionError("mutable profile");}catch(UnsupportedOperationException ok){checks++;}
  invalid(()->profile("bad id",1,"guide",Map.of()));invalid(()->profile("x",0,"guide",Map.of()));
  invalid(()->profile("x",1,"guide",Map.of("attentionMs",-1L)));
  invalid(()->GuidePolicy.from(Map.of("attentionMs",Long.MAX_VALUE)));
  var builtins=ServerHumanProfiles.registry();
  invalid(()->builtins.register(profile("bad-parameters",1,"guide",Map.of("typo",1L))));
  invalid(()->builtins.register(profile("zero-wave",1,"guide",Map.of("salutationMs",0L))));
  invalid(()->builtins.register(new HumanProfile("provider",1,"guide",Map.of(),"","unknown",true,true)));
  check(GuidePolicy.from(configured.parameters()).announcementPause()==900000000L,"profile milliseconds converted to execution nanoseconds");
  var behavior=new Policy();var activity=new HumanActivityController(behavior,true,true);var port=new Port();
  var visitor=new HumanObservation.Identity((short)1,1);
  activity.update(new HumanObservation(0,List.of()),port);
  check(activity.beginDialogue(visitor,1,port)&&behavior.suspends==1,"dialogue acquires generic behavior without guide methods");
  check(!activity.beginTrade(visitor,2,port)&&!activity.beginDialogue(visitor,2,port),"only one interaction can own NPC");
  activity.update(new HumanObservation(3,List.of()),port);
  check(behavior.updates==1,"autonomous policy not ticked while dialogue owns actor");
  activity.finishTrade(4,port);check(activity.mode()==HumanActivityController.Mode.DIALOGUE,"unrelated finish cannot release owner");
  activity.finishDialogue(5,port);check(behavior.resumes==1&&activity.mode()==HumanActivityController.Mode.AUTONOMOUS,"release resumes selected policy");
  check(activity.beginTrade(visitor,6,port),"trade reuses generic arbitration");activity.recordingSale(7,port);
  activity.finishTrade(8,port);activity.stop(9,port);activity.stop(10,port);
  check(behavior.stops==1&&!activity.beginTrade(visitor,11,port),"stop terminal and cleanup once");
  var disabled=new HumanActivityController(new Policy(),false,false);
  check(!disabled.beginDialogue(visitor,0,port)&&!disabled.beginTrade(visitor,0,port),"profile capabilities enforced");
  var broken=new Policy();broken.broken=true;var guarded=new HumanActivityController(broken,true,true);
  guarded.update(new HumanObservation(0,List.of()),port);guarded.update(new HumanObservation(1,List.of()),port);
  check(guarded.mode()==HumanActivityController.Mode.STOPPED&&broken.updates==1&&guarded.failure()!=null,"bad custom policy isolated without retry storm");
  var perceptive=new HumanActivityController(new Policy(),true,true);
  perceptive.update(0,n->{throw new IllegalStateException();},port);
  check(perceptive.mode()==HumanActivityController.Mode.STOPPED,"perception failures isolated at controller boundary");
  var quiet=new HumanActivityController(new IdleBehavior(),true,false);
  quiet.update(0,n->{throw new AssertionError("idle must not scan world");},port);
  check(quiet.mode()==HumanActivityController.Mode.AUTONOMOUS,"idle policy suppresses perception work");
  var parallel=new HumanActionExecution(new HumanAction.Parallel(List.of(new HumanAction.Emote("wavehi",100),new HumanAction.Say("hello"))));
  port.fail=true;parallel.update(0,port);int says=port.says;parallel.update(1000,port);
  check(parallel.status()==HumanActionExecution.Status.FAILED&&port.says==says,"concurrent speech failure cancels animation without replay");
  invalid(()->new HumanAction.Parallel(List.of(new HumanAction.Move(new zombie.apocalipse.humans.walking.MovementRequest.Pattern("background")))));
  invalid(()->new HumanAction.Parallel(List.of(new HumanAction.Say("a"),new HumanAction.Say("b"))));
  var f=ServerHumanFactory.getInstance();f.tick(0);
  invalid(()->f.spawn(definition("unknown","missing")));
  check(f.lifecycle("unknown")==null,"unknown profile rejected before logical or world registration");
  f.registerDormant(definition("guide","stationary"));
  check(f.lifecycle("guide").state()==HumanLifecycle.State.DORMANT&&ServerHumanFactory.dialogueHumans().isEmpty(),"dormant logical NPC has no live presentation or roster entry");
  short first=f.activate("guide");var stationary=ServerHumanFactory.tradingHuman(first);
  check(stationary.activity.behavior() instanceof IdleBehavior,"actor key guide does not select guide policy");
  check(f.lifecycle("guide").activations()==1&&f.lifecycle("guide").state()==HumanLifecycle.State.ACTIVE,"activation commits lifecycle after construction");
  check(!stationary.activity.beginTrade(visitor,1,stationary.actuator),"stationary trade-disabled profile blocks native controller");
  invalid(()->f.activate("guide"));
  var snapshot=f.lifecycle("guide");check(f.deactivate("guide")&&!f.deactivate("guide"),"deactivation idempotent");
  check(snapshot.state()==HumanLifecycle.State.ACTIVE&&f.lifecycle("guide").state()==HumanLifecycle.State.DORMANT,"snapshot immutable across lifecycle changes");
  check(ServerHumanFactory.tradingHuman(first)==null&&stationary.activity.mode()==HumanActivityController.Mode.STOPPED,"deactivation removes routing and stops runtime");
  short second=f.activate("guide");check(second>first&&f.lifecycle("guide").activations()==2,"reactivation retains logical identity but never reuses presentation ID");
  check(f.markDead("guide")&&f.lifecycle("guide").state()==HumanLifecycle.State.DEAD,"death terminal at outer lifecycle");
  invalid(()->f.activate("guide"));check(f.remove("guide")&&f.lifecycle("guide")==null,"administrative removal clears logical record");
  short other=f.spawn(definition("different-key","lobby-guide"));
  check(ServerHumanFactory.tradingHuman(other).activity.behavior() instanceof HumanModuleRuntime,"guide profile works under arbitrary actor key");
  var active=ServerHumanFactory.tradingHuman(other);active.player.dead=true;f.tick(ServerHumanFactory.BRAIN_UPDATE_NANOS);
  check(f.lifecycle("different-key").state()==HumanLifecycle.State.DEAD,"simulation detects active NPC death");
  var live=profile("editable",1,"idle",Map.of());builtins.register(live);short version=f.spawn(definition("editable","editable"));
  builtins.replace(profile("editable",2,"idle",Map.of()));
  check(ServerHumanFactory.tradingHuman(version).profile.revision()==1,"live NPC retains bound profile revision");
  f.deactivate("editable");short replacement=f.activate("editable");
  check(ServerHumanFactory.tradingHuman(replacement).profile.revision()==2,"new activation uses updated profile snapshot");
  check(f.lifecycle("editable").profileRevision()==2,"logical snapshot reports last bound profile revision");
  builtins.register(new HumanProfile("hidden",1,"idle",Map.of(),"","none",false,false));
  short hidden=f.spawn(definition("hidden","hidden"));
  var publicRoster=ServerHumanDialogue.roster();
  boolean advertised=false;for(Object value:publicRoster.values.values())if(((Number)((se.krka.kahlua.vm.KahluaTable)value).rawget("id")).intValue()==hidden)advertised=true;
  check(!advertised,"dialogue-disabled profile omitted from world roster");
  var next=ServerHumanFactory.class.getDeclaredField("nextId");next.setAccessible(true);next.setInt(f,ServerHumanFactory.LAST_ID+1);
  f.registerDormant(definition("exhausted","stationary"));invalid(()->f.activate("exhausted"));
  check(f.lifecycle("exhausted").state()==HumanLifecycle.State.DORMANT,"activation failure rolls spawning state back to dormant");
  check(f.lifecycle("exhausted").activations()==0,"failed activation never increments successful activation count");
  System.out.println("Passed "+checks+" profile, control, configuration and lifecycle assertions.");
 }
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-profile-tests-") as directory:
        work = Path(directory)
        fixture = dict(FILES)
        fixture["zombie/apocalipse/humans/ProfilesTest.java"] = HARNESS
        sources = []
        for relative, content in fixture.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources.extend(sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans").rglob("*.java")))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in sources), encoding="utf-8")
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-encoding", "UTF-8", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.apocalipse.humans.ProfilesTest"], check=True)


if __name__ == "__main__":
    main()
