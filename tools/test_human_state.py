"""Production single-writer reducer, condition projection and native/factory integration."""
from pathlib import Path
import subprocess
import sys
import tempfile
sys.dont_write_bytecode = True
from test_server_humans import FILES, ROOT

HARNESS = r"""
package zombie.apocalipse.humans;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import zombie.apocalipse.humans.state.*;
import zombie.apocalipse.humans.movement.MovementModifiers;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.profiles.*;
import zombie.characters.*;
import zombie.iso.*;
import zombie.network.*;
public class HumanStateTest {
 static int checks;static final long STEP=100000000L;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void near(float a,float b,String message){check(Math.abs(a-b)<.00001,message+" actual="+a+" expected="+b);}
 static void invalid(Runnable operation){try{operation.run();throw new AssertionError("Invalid input accepted");}catch(IllegalArgumentException expected){checks++;}}
 static void apply(ServerHumanFactory f,String key,HumanMutation m){var r=f.mutate(key,f.lifecycle(key).activations(),f.state(key).revision(),m);check(r.status()==HumanStateStore.Status.APPLIED||r.status()==HumanStateStore.Status.NO_CHANGE,"trusted mutation accepted");}
 public static void main(String[] ignored)throws Exception{
  var initial=HumanState.INITIAL;var damage=new HumanMutation.Damage(.2f,-.6f,.3f,.01f);
  var hurt=HumanStateReducer.reduce(initial,damage);
  near(initial.vitals().health(),1,"reducer leaves old state unchanged");near(hurt.vitals().health(),.8f,"damage reduces health");
  check(hurt.revision()==1&&hurt.injuries().leg()==-.6f,"damage and wounds commit one revision");
  check(hurt.equals(HumanStateReducer.reduce(initial,damage)),"reduction deterministic without hidden clock or random state");
  var bleeding=HumanStateReducer.reduce(hurt,new HumanMutation.Advance(.1f,-.1f,.01f,.02f,.03f));
  near(bleeding.vitals().health(),.799f,"bleeding applies per simulated second");near(bleeding.vitals().endurance(),.99f,"exertion applied centrally");
  near(bleeding.vitals().hunger(),.001f,"explicit hunger rate supported");near(bleeding.vitals().thirst(),.002f,"explicit thirst rate supported");
  for(var stat:HumanState.Stat.values()){
   var next=HumanStateReducer.reduce(initial,new HumanMutation.Adjust(stat,.5f));
   check(next.vitals().health()==1,"need adjustment never accidentally changes health: "+stat);
   next=HumanStateReducer.reduce(next,new HumanMutation.Adjust(stat,1));
   var again=HumanStateReducer.reduce(next,new HumanMutation.Adjust(stat,1));
   check(again==next,"bounded stat changes stop allocating revisions at saturation: "+stat);
  }
  var treated=HumanStateReducer.reduce(hurt,new HumanMutation.Treat(1,1,1));
  near(treated.injuries().leg(),0,"treatment clears leg impairment");near(treated.injuries().bleedingPerSecond(),0,"treatment stops bleeding");
  var dead=HumanStateReducer.reduce(hurt,new HumanMutation.Kill());
  check(dead.dead()&&HumanStateReducer.reduce(dead,new HumanMutation.Heal(1))==dead,"death terminal, no accidental resurrection");
  check(HumanStateReducer.reduce(initial,new HumanMutation.Heal(.1f))==initial,"no-op does not advance state revision");
  invalid(()->new HumanMutation.Damage(Float.NaN,0,0,0));invalid(()->new HumanMutation.Adjust(HumanState.Stat.HUNGER,Float.POSITIVE_INFINITY));
  invalid(()->new HumanMutation.Advance(.2f,0,0,0,0));invalid(()->new HumanState.Injuries(2,0,0,false,false));
  var store=new HumanStateStore(initial);var result=store.dispatch(0,damage);
  check(result.status()==HumanStateStore.Status.APPLIED,"optimistic command accepted");
  check(store.dispatch(0,damage).status()==HumanStateStore.Status.STALE_REVISION,"repeated command cannot apply damage twice");
  near(store.snapshot().vitals().health(),.8f,"stale mutation leaves state unchanged");
  AtomicReference<Throwable> wrongThread=new AtomicReference<>();
  Thread thread=new Thread(()->{try{store.dispatch(damage);}catch(Throwable t){wrongThread.set(t);}});thread.start();thread.join();
  check(wrongThread.get() instanceof IllegalStateException,"single-writer ownership enforced");
  var restored=new HumanStateStore(initial);restored.restore(hurt);check(restored.snapshot()==hurt,"immutable durable state restored");
  try{restored.restore(initial);throw new AssertionError("restore repeated");}catch(IllegalStateException expected){checks++;}
  var cold=HumanStateReducer.reduce(hurt,new HumanMutation.Adjust(HumanState.Stat.COLD,1));
  var c=HumanConditionCalculator.movement(cold,MovementModifiers.Context.NORMAL,MovementModifiers.Effects.NONE);
  near(c.health().health(),.8f,"movement consumes central health");near(c.health().legInjury(),-.6f,"movement consumes central injuries");
  near(c.context().coldScale(),.7f,"cold projected deterministically");
  check(c.equals(HumanConditionCalculator.movement(cold,MovementModifiers.Context.NORMAL,MovementModifiers.Effects.NONE)),"projection has no accumulating modifiers");
  var tired=HumanStateReducer.reduce(initial,new HumanMutation.Adjust(HumanState.Stat.FATIGUE,.8f));
  near(HumanConditionCalculator.movement(tired,MovementModifiers.Context.NORMAL,MovementModifiers.Effects.NONE).health().endurance(),.2f,"fatigue caps available endurance");

  var registry=ServerHumanProfiles.registry();AtomicReference<HumanState> observed=new AtomicReference<>();
  registry.registerBehavior("state-test",p->{},p->new HumanBehavior(){
   public boolean needsPerception(){return false;}public boolean available(){return true;}
   public void update(HumanObservation o,HumanActuator a){observed.set(o.state());}
   public void suspend(long n,HumanActuator a){a.stopMoving();}public void resume(HumanObservation.Identity v,long n,HumanActuator a){}
   public void stop(long n,HumanActuator a){a.stopMoving();}
  });
  registry.register(new HumanProfile("state-test",1,"state-test",Map.of(),"","none",false,false));
  IsoWorld.instance.currentCell=new IsoCell();var ctor=ServerHumanFactory.class.getDeclaredConstructor();ctor.setAccessible(true);
  var factory=ctor.newInstance();
  check(factory.submitMutation("bot",1,0,damage).join().status()==HumanStateStore.Status.UNAVAILABLE,"inbox unavailable before initialization");
  factory.tick(0);factory.spawn(new ServerHumanDefinition("bot","bot","bot",100.5f,200.5f,0,IsoDirections.E,false,"Tourist","state-test"));
  var field=ServerHumanFactory.class.getDeclaredField("humans");field.setAccessible(true);
  var bot=(ServerHuman)((Map<?,?>)field.get(factory)).get("bot");
  int activation=factory.lifecycle("bot").activations();
  check(factory.mutate("bot",activation-1,0,damage).status()==HumanStateStore.Status.STALE_ACTIVATION,"old actor incarnation rejected");
  apply(factory,"bot",new HumanMutation.Damage(.2f,-.6f,.2f,0));factory.tick(STEP);
  near(bot.player.getHealth(),.8f,"native character health is projection");near(bot.player.getBodyDamage().getOverallBodyHealth(),80,"native body health projection uses correct units");
  near(bot.movement.resolved().animation().walkInjury(),-.6f,"central wounds automatically reach movement");
  check(observed.get().equals(factory.state("bot")),"behavior reads central state even without visitor perception");
  bot.player.setHealth(.7f);factory.tick(2*STEP);near(factory.state("bot").vitals().health(),.7f,"new native damage enters reducer");
  factory.tick(3*STEP);near(factory.state("bot").vitals().health(),.7f,"native projection is not reimported as duplicate damage");
  bot.player.setHealth(1);bot.player.getBodyDamage().setOverallBodyHealth(100);factory.tick(4*STEP);
  near(factory.state("bot").vitals().health(),.7f,"native resets cannot bypass central healing commands");
  apply(factory,"bot",new HumanMutation.Adjust(HumanState.Stat.HUNGER,.4f));factory.tick(5*STEP);
  near(bot.player.getStats().get(CharacterStat.HUNGER),.4f,"native hunger mirrors authoritative needs");
  var before=factory.state("bot");check(factory.deactivate("bot"),"bot enters dormancy");
  check(factory.state("bot").equals(before),"dormancy keeps physical state outside discarded IsoPlayer");
  factory.activate("bot");var replacement=(ServerHuman)((Map<?,?>)field.get(factory)).get("bot");
  check(replacement!=bot&&replacement.state==bot.state,"replacement actor binds same logical state store");near(replacement.player.getHealth(),.7f,"reactivation hydrates condition");
  check(factory.mutate("bot",activation,before.revision(),damage).status()==HumanStateStore.Status.STALE_ACTIVATION,"delayed prior-activation mutation refused");
  var queued=factory.submitMutation("bot",factory.lifecycle("bot").activations(),factory.state("bot").revision(),new HumanMutation.Heal(.1f));
  check(!queued.isDone(),"off-thread inbox queues values rather than mutating immediately");factory.tick(6*STEP);
  check(queued.join().status()==HumanStateStore.Status.APPLIED,"queued mutation acknowledged after simulation-thread reduction");near(factory.state("bot").vitals().health(),.8f,"queued healing applied once");
  long revision=factory.state("bot").revision();activation=factory.lifecycle("bot").activations();
  var tickets=new ArrayList<CompletableFuture<HumanStateStore.Result>>();
  for(int n=0;n<1024;n++)tickets.add(factory.submitMutation("bot",activation,revision,new HumanMutation.Adjust(HumanState.Stat.THIRST,.1f)));
  check(factory.submitMutation("bot",activation,revision,damage).join().status()==HumanStateStore.Status.QUEUE_FULL,"inbox rejects unbounded accumulation");
  factory.tick(7*STEP);check(tickets.stream().filter(CompletableFuture::isDone).count()==128,"per-tick mutation drain budget bounded");
  for(int n=8;n<=14;n++)factory.tick(n*STEP);
  check(tickets.stream().filter(t->t.join().status()==HumanStateStore.Status.APPLIED).count()==1,"conflicting queued revision applies only once");
  near(factory.state("bot").vitals().thirst(),.1f,"duplicate inbox requests do not multiply needs changes");
  apply(factory,"bot",new HumanMutation.Kill());factory.tick(15*STEP);
  check(factory.lifecycle("bot").state()==HumanLifecycle.State.DEAD&&factory.state("bot").dead(),"physical death drives terminal logical transition");
  check(factory.mutate("bot",activation,factory.state("bot").revision(),new HumanMutation.Heal(1)).status()==HumanStateStore.Status.INACTIVE,"dead NPC cannot accept ordinary healing");
  System.out.println("Passed "+checks+" centralized state, reducer, conditions, native bridge and command-inbox assertions.");
 }
}
"""

LOG_HARNESS = r"""
package zombie.apocalipse.humans;
import zombie.apocalipse.humans.state.*;
import zombie.iso.IsoDirections;
public class HumanMutationLogTest {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  boolean debug=args[0].equals("DEBUG");boolean off=args[0].equals("OFF");
  var definition=new ServerHumanDefinition("log-bot","bot","bot",0,0,0,IsoDirections.E,false,"Tourist","lobby-guide");
  var state=new HumanLifecycle(definition).physiology();
  state.dispatch(new HumanMutation.Advance(.1f,-.25f/140,0,0,0));
  var lines=zombie.debug.DebugLog.messages;
  check(lines.size()==(debug?1:0),"only DEBUG emits actual state mutations");
  if(debug){
   String line=lines.getFirst();
   check(line.contains("[ApocBR][NPC][DEBUG] mutation key=log-bot; type=Advance; revision=0 -> 1; endurance=1.0 -> 0.999"),"walking drain has key, command, revisions and precise before/after values: "+line);
   check(!line.contains("health="),"unchanged properties excluded");
  }
  int count=lines.size();
  state.dispatch(new HumanMutation.Heal(.1f));
  state.dispatch(0,new HumanMutation.Damage(.1f,0,0,0));
  check(lines.size()==count,"no-op and stale commands do not emit mutation lines");
  state.dispatch(new HumanMutation.Damage(.1f,-.5f,.25f,.01f));
  state.dispatch(new HumanMutation.Mobility(true,true));
  if(debug){
   check(lines.get(count).contains("health=1.0 -> 0.9")&&lines.get(count).contains("pain=")
     &&lines.get(count).contains("leg=0.0 -> -0.5")&&lines.get(count).contains("arms=0.0 -> 0.25")
     &&lines.get(count).contains("bleedingPerSecond=0.0 -> 0.01"),"damage reports all changed fields atomically");
   check(lines.get(count+1).contains("immobile=false -> true")&&lines.get(count+1).contains("downed=false -> true"),"mobility flags included");
  }
  for(var stat:HumanState.Stat.values())state.dispatch(new HumanMutation.Adjust(stat,stat==HumanState.Stat.ENDURANCE?-.1f:.1f));
  if(debug)for(var stat:HumanState.Stat.values())check(lines.stream().anyMatch(s->s.contains(stat.name().toLowerCase(java.util.Locale.ROOT)+"=")),"tracked stat logged: "+stat);
  count=lines.size();
  HumanLog.log(HumanLog.Level.OFF,()->{throw new AssertionError("disabled message supplier evaluated");});
  if(!debug)HumanLog.log(HumanLog.Level.INFO,()->{throw new AssertionError("disabled message supplier evaluated");});
  HumanLog.log(HumanLog.Level.ERROR,()->"test error");
  check(lines.size()==count+(off?0:1),"ERROR enabled by default, OFF suppresses everything");
  System.out.println("Passed mutation logging configuration: "+args[0]);
 }
}
"""

def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-human-state-") as folder:
        work=Path(folder);fixture=dict(FILES);fixture['zombie/apocalipse/humans/HumanStateTest.java']=HARNESS;sources=[]
        fixture['zombie/apocalipse/humans/HumanMutationLogTest.java']=LOG_HARNESS
        fixture['zombie/debug/DebugLog.java']='''package zombie.debug;
public class DebugLog {
 public static final java.util.List<String> messages=new java.util.ArrayList<>();
 public static void log(String text){messages.add(text);}
}'''
        for name,content in fixture.items():
            p=work/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8');sources.append(p)
        sources.extend(sorted((ROOT/'42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
        args=work/'sources.txt';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources))
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',str(work/'classes'),'zombie.apocalipse.humans.HumanStateTest'],check=True)
        for setting,expected in [(None,'ERROR'),('invalid','ERROR'),('OFF','OFF'),(' dEbUg ','DEBUG')]:
            options=[] if setting is None else ['-Dapocbr.npc.logLevel='+setting]
            subprocess.run([str(ROOT/'jdk/bin/java.exe'),*options,'-cp',str(work/'classes'),
                            'zombie.apocalipse.humans.HumanMutationLogTest',expected],check=True)
if __name__=='__main__':main()
