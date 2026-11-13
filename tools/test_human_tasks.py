"""Production task policies and dialogue command acceptance; no running game required."""
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
import zombie.iso.*;
public class TasksTest {
 static int checks;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static void invalid(Runnable run){try{run.run();throw new AssertionError("Invalid task accepted");}catch(IllegalArgumentException expected){checks++;}}
 static HumanObservation observation(long now){return new HumanObservation(now,List.of(),true);}
 static HumanTaskCommand command(HumanTaskCommand.Kind kind,String... parameters){var p=new HashMap<String,String>();for(int i=0;i<parameters.length;i+=2)p.put(parameters[i],parameters[i+1]);return new HumanTaskCommand(kind,p);}
 static class Port implements HumanActuator,HumanTaskWorld {
  WalkingPatternSpec.Point here=new WalkingPatternSpec.Point(100,200,0),target=new WalkingPatternSpec.Point(108,200,0),goal;
  int starts,stops;boolean reject,blocked;MotionStatus result=MotionStatus.RUNNING;
  public HumanTaskWorld taskWorld(){return this;}
  public WalkingPatternSpec.Point position(){return here;}
  public WalkingPatternSpec.Point locate(HumanObservation.Identity id){return target;}
  public boolean permitted(WalkingPatternSpec.Point point){return !blocked&&point.z()==0&&!point.equals(target);}
  public boolean startMovement(MovementRequest request,long now){starts++;goal=((MovementRequest.To)request).destination();return !reject;}
  public MotionStatus updateMovement(long now){return result;}
  public void stopMoving(){stops++;}
  public boolean face(HumanObservation.Identity id){return true;}
  public boolean startEmote(String name){return true;}
  public void stopEmote(){}
  public boolean say(String text){return true;}
  public boolean returnHome(){return true;}
 }
 public static void main(String[] args){
  var id=new HumanObservation.Identity((short)1,2);
  invalid(()->command(HumanTaskCommand.Kind.MOVE_TO,"x","1","y","2"));
  invalid(()->command(HumanTaskCommand.Kind.MOVE_TO,"x","1.2","y","2","z","0"));
  invalid(()->command(HumanTaskCommand.Kind.HOLD_POSITION,"durationMs","-1"));
  invalid(()->command(HumanTaskCommand.Kind.FOLLOW_PLAYER,"playerId","99"));
  invalid(()->command(HumanTaskCommand.Kind.FOLLOW_PLAYER,"distanceTiles","9"));
  invalid(()->command(HumanTaskCommand.Kind.RESUME_ROUTINE,"x","1"));
  var mutable=new HashMap<String,String>();mutable.put("durationMs","1000");var hold=new HumanTaskCommand(HumanTaskCommand.Kind.HOLD_POSITION,mutable);mutable.put("durationMs","2");
  check(hold.durationMs()==1000,"immutable authored parameters");
  var p=new Port();var task=new AssignedTaskModule();task.assign(hold,id);
  task.update(observation(1),p);task.update(observation(500_000_001L),p);task.interrupt(500_000_002L,p);
  task.update(observation(20_000_000_000L),p);
  check(task.active(),"preemption does not consume hold duration");
  task.update(observation(20_500_000_001L),p);
  check(task.snapshot().status()==AssignedTaskModule.Status.SUCCEEDED&&p.starts==0,"hold completes without movement");
  task.assign(command(HumanTaskCommand.Kind.MOVE_TO,"x","104","y","200","z","0"),id);
  task.update(observation(21_000_000_000L),p);check(p.starts==1,"movement started once");
  task.interrupt(21_100_000_000L,p);check(p.stops==1&&task.active(),"combat cancels route and retains assignment");
  task.update(observation(22_000_000_000L),p);check(p.starts==2,"route replanned on resumption");
  p.result=HumanActuator.MotionStatus.FAILED;task.update(observation(22_100_000_000L),p);
  int starts=p.starts;task.update(observation(23_000_000_000L),p);
  check(task.snapshot().reason().equals("route_failed")&&!task.active()&&p.starts==starts,"route failure is terminal without retry loop");
  p.result=HumanActuator.MotionStatus.RUNNING;task.assign(command(HumanTaskCommand.Kind.FOLLOW_PLAYER),id);task.update(observation(24_000_000_000L),p);
  check(p.goal.x()==106&&!p.goal.equals(p.target),"follow routes near player rather than onto occupied tile");
  p.target=new WalkingPatternSpec.Point(109,200,0);starts=p.starts;task.update(observation(24_500_000_000L),p);check(p.starts==starts,"follow replanning is bounded");
  task.update(observation(25_000_000_000L),p);check(p.starts==starts+1,"moving target replans after interval");
  p.target=null;task.update(observation(26_000_000_000L),p);check(task.snapshot().reason().equals("target_unavailable"),"missing target ends task");
  task.assign(hold,id);task.suspend(27_000_000_000L,p);check(!task.eligible(observation(28_000_000_000L)),"dialogue owns execution exclusively");
  task.assign(command(HumanTaskCommand.Kind.RESUME_ROUTINE),id);task.resume(id,28_000_000_000L,p);check(!task.active(),"resume routine clears assignment");
  task.assign(hold,id);p.blocked=true;task.assign(command(HumanTaskCommand.Kind.MOVE_TO,"x","104","y","200","z","0"),id);task.update(observation(29_000_000_000L),p);
  check(task.snapshot().reason().equals("outside_movement_scope"),"scope refusal is explicit");
  task.stop(30_000_000_000L,p);check(!task.available(),"actor shutdown cancels task");
  dialogue();System.out.println("Passed "+checks+" task lifecycle, parameters and dialogue integration assertions.");
 }
 static void dialogue(){
  var factory=ServerHumanFactory.getInstance();factory.tick(0);var p=DialogueTest.visitor((short)1);p.x=101;
  var profile=new HumanProfile("task-test",1,"modules",Map.of(),"","none",true,false,List.of(new HumanModuleBinding("assigned-task",200,100,Map.of()),new HumanModuleBinding("walk",1000,100,Map.of())),8,100,true);
  ServerHumanProfiles.registry().register(profile);
  var option=new HashMap<String,Object>(DialogueTest.choice("hold","root"));option.put("close",true);option.put("task",command(HumanTaskCommand.Kind.HOLD_POSITION));
  var graph=Map.<String,Object>of("id","orders","revision",1,"entryNode","root","fallbackLocale","EN","nodes",Map.of("root",Map.of("texts",Map.of("EN",Map.of("title","Orders","description","")),"options",List.of(option))));
  zombie.apocalipse.humans.dialogue.HumanDialogueCatalog.publish(Map.of("task-test",graph,"roaming-greeter",graph));
  long now=System.nanoTime();
  short id=factory.spawn(new ServerHumanDefinition("task","Task","Task",100,200,0,IsoDirections.E,false,"Tourist","task-test"));
  var h=ServerHumanFactory.tradingHuman(id);var module=ServerHumanTasks.module(h);
  var lease=ServerHumanDialogue.graphBegin(p,id,1,"root","hold",now);int token=DialogueTest.token(lease);
  check(ServerHumanDialogue.arrive(p,id,token,now),"task dialogue arrival validated");
  var roster=ServerHumanDialogue.graphRoster(p);var actor=(se.krka.kahlua.vm.KahluaTable)roster.rawget(1.0);
  var publicOption=(se.krka.kahlua.vm.KahluaTable)((se.krka.kahlua.vm.KahluaTable)actor.rawget("options")).rawget(1.0);
  check(publicOption.rawget("task")==null,"authoritative effect not sent to client");
  check(ServerHumanDialogue.graphSelect(p,id,token,"root",1,"hold",1,now).rawget("error")==null,"validated selection assigns task");
  check(module.active()&&h.activity.mode()==HumanActivityController.Mode.DIALOGUE,"assignment waits for dialogue close");
  check(ServerHumanDialogue.graphSelect(p,id,token,"root",1,"hold",1,now+1_000_000_000L).rawget("error")!=null,"selection replay cannot reassign task");
  var api=(se.krka.kahlua.vm.KahluaTable)zombie.Lua.LuaManager.env.rawget("ApocBRHumanAPI");
  var close=new se.krka.kahlua.vm.LuaCallFrame(p,(double)id,(double)token);
  ((se.krka.kahlua.vm.JavaFunction)api.rawget("finish")).call(close,3);
  check(Boolean.TRUE.equals(close.result)&&h.activity.mode()==HumanActivityController.Mode.AUTONOMOUS,"closing releases task to scheduler");
  factory.remove("task");check(!module.available(),"despawn cancels command state");
  id=factory.spawn(new ServerHumanDefinition("unsupported","NoTasks","NoTasks",100,200,0,IsoDirections.E,false,"Tourist"));
  lease=ServerHumanDialogue.graphBegin(p,id,1,"root","hold",now);token=DialogueTest.token(lease);ServerHumanDialogue.arrive(p,id,token,now);
  check(ServerHumanDialogue.graphSelect(p,id,token,"root",1,"hold",1,now).rawget("error")!=null,"profile without task capability rejects effect");
  factory.remove("unsupported");
 }
}
'''

def main():
    with tempfile.TemporaryDirectory(prefix='apocbr-tasks-') as folder:
        work=Path(folder);fixture=dict(FILES)
        fixture['zombie/apocalipse/humans/DialogueTest.java']=DIALOGUE
        fixture['zombie/apocalipse/humans/TasksTest.java']=HARNESS
        sources=[]
        for name,content in fixture.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
        sources.extend(sorted((ROOT/'42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
        args=work/'sources.txt';args.write_text('\n'.join('"'+str(s).replace('\\','/')+'"' for s in sources),encoding='utf-8')
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-encoding','UTF-8','-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',str(work/'classes'),'zombie.apocalipse.humans.TasksTest'],check=True)

if __name__=='__main__':main()
