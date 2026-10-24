"""Compile and execute the actual engine-independent guide brain and action runner."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HARNESS = r"""
import java.util.*;
import zombie.apocalipse.humans.brain.*;
public class BrainTest {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static final long SECOND=1_000_000_000L;
 static HumanObservation.Identity a=new HumanObservation.Identity((short)1,11),b=new HumanObservation.Identity((short)2,22);
 static HumanObservation.Visitor visitor(HumanObservation.Identity id,double distance){return new HumanObservation.Visitor(id,1,2,0,distance);}
 static HumanObservation seen(long now,HumanObservation.Identity... ids){
  List<HumanObservation.Visitor> visitors=new ArrayList<>();for(var id:ids)visitors.add(visitor(id,4));return new HumanObservation(now,visitors);
 }
 static class Port implements HumanActuator {
  int faces,starts,stops,says,homes,announcements;boolean faceOK=true,speechOK=true,emoteOK=true,homeOK=true,throwsSpeech;
  HumanObservation.Identity target;List<String> history=new ArrayList<>();
  public boolean face(HumanObservation.Identity id){faces++;target=id;history.add("face");return faceOK;}
  public boolean startEmote(String name){check(name.equals("wavehi"),"native greeting emote");starts++;history.add("wave");return emoteOK;}
  public void stopEmote(){stops++;history.add("stop");}
  public boolean say(String text){if(text.equals("operation")){announcements++;history.add("announcement");return true;}says++;history.add("hi");check(text.equals("hello"),"requested literal greeting");if(throwsSpeech)throw new IllegalStateException();return speechOK;}
  public boolean returnHome(){homes++;history.add("home");return homeOK;}
 }
 static class RoamingPort extends Port {
  int walks,steps,halts;MotionStatus motion=MotionStatus.RUNNING;
  public boolean startMovement(zombie.apocalipse.humans.walking.MovementRequest request,long now){walks++;return true;}
  public MotionStatus updateMovement(long now){steps++;return motion;}
  public void stopMoving(){halts++;}
 }
 static void cycle(GuideBrain brain,Port port,long from,HumanObservation.Identity... ids){
  for(long now=from;now<from+15*SECOND;now+=SECOND/2)brain.update(seen(now,ids),port);
 }
 public static void main(String[] args){
  Port p=new Port();GuideBrain brain=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));brain.update(seen(0),p);
  check(brain.state()==GuideBrain.State.IDLE&&p.faces==0,"empty observation stays idle");
  var mutable=new ArrayList<HumanObservation.Visitor>();mutable.add(visitor(a,9));var immutable=new HumanObservation(0,mutable);mutable.clear();
  check(immutable.visitors().size()==1,"snapshot list defensively copied");
  brain.update(new HumanObservation(SECOND,List.of(visitor(b,9),visitor(a,4))),p);
  check(p.target.equals(a),"nearest visitor selected");
  check(brain.state()==GuideBrain.State.HOLDING_ATTENTION,"successful facing enters attention");
  check(brain.lastTransition().from()==GuideBrain.State.FACING_VISITOR,"transition exposes previous state");
  brain.update(seen(SECOND+SECOND/2,a,b),p);check(p.starts==0,"attention starts without immediate salutation");
  brain.update(seen(2*SECOND,a,b),p);check(p.starts==0,"attention holds for real elapsed duration");
  cycle(brain,p,2*SECOND+SECOND/2,a);
  check(p.starts==1&&p.stops==1&&p.says==1&&p.homes==1,"complete greeting executes each one-shot once");
  check(p.announcements==1&&p.history.indexOf("hi")<p.history.indexOf("stop")
    &&p.history.indexOf("stop")<p.history.indexOf("announcement"),"hello during wave, separate announcement after wave");
  check(p.history.indexOf("wave")<p.history.indexOf("hi")&&p.history.indexOf("hi")<p.history.indexOf("home"),"salutation speech return sequence");
  check(brain.state()==GuideBrain.State.IDLE,"cooldown ends at idle");
  cycle(brain,p,20*SECOND,a);check(p.says==1,"lingering visitor not greeted repeatedly");
  brain.update(seen(40*SECOND),p);cycle(brain,p,41*SECOND,a);check(p.says==1,"leaving and returning does not bypass visitor cooldown");
  brain.update(seen(140*SECOND),p);cycle(brain,p,141*SECOND,a);check(p.says==2,"visitor can be greeted again after two-minute cooldown");
  Port tie=new Port();GuideBrain tied=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));tied.update(seen(0,b,a),tie);check(tie.target.equals(a),"equal-distance selection deterministic by online ID");
  Port lost=new Port();GuideBrain interrupted=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));
  for(long now=0;now<=2*SECOND;now+=SECOND/2)interrupted.update(seen(now,a),lost);
  check(lost.starts==1,"salutation began before cancellation");
  interrupted.update(seen(2*SECOND+SECOND/2),lost);
  check(lost.stops==1&&lost.says==1&&lost.announcements==0&&lost.homes==1,"visitor loss cancels wave and suppresses follow-up");
  var replacement=new HumanObservation.Identity(a.onlineId(),999);
  Port replaced=new Port();GuideBrain replaceBrain=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));replaceBrain.update(seen(0,a),replaced);replaceBrain.update(seen(SECOND/2,replacement),replaced);
  check(replaced.homes==1&&replaced.says==0,"reused online ID cannot retain old target incarnation");
  Port failed=new Port();failed.faceOK=false;GuideBrain failureBrain=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));cycle(failureBrain,failed,0,a);
  check(failed.says==0&&failed.homes==1,"failed facing abandons greeting without retry storm");
  Port speech=new Port();speech.speechOK=false;GuideBrain speechBrain=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));cycle(speechBrain,speech,0,a);
  check(speech.says==1&&speech.homes==1,"unavailable chat settles failed once and restores post");
  Port thrown=new Port();thrown.throwsSpeech=true;GuideBrain thrownBrain=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));cycle(thrownBrain,thrown,0,a);
  check(thrown.says==1&&thrown.homes==1,"port exception cannot repeat speech or escape action runner");
  Port homeFailure=new Port();homeFailure.faceOK=false;homeFailure.homeOK=false;GuideBrain stopped=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));cycle(stopped,homeFailure,0,a);
  check(stopped.state()==GuideBrain.State.STOPPED,"failed home restoration stops brain");
  Port remove=new Port();GuideBrain removed=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));for(long now=0;now<=2*SECOND;now+=SECOND/2)removed.update(seen(now,a),remove);
  removed.stop(3*SECOND,remove);cycle(removed,remove,4*SECOND,a);
  check(removed.state()==GuideBrain.State.STOPPED&&remove.stops==1&&remove.says==1&&remove.announcements==0,"actor removal cancels active wave and follow-up");
  Port tracking=new Port();GuideBrain trackingBrain=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));
  trackingBrain.update(seen(0,a),tracking);
  trackingBrain.update(seen(SECOND/10,a),tracking);
  int attentionFaces=tracking.faces;
  trackingBrain.update(new HumanObservation(2*SECOND/10,List.of(visitor(a,16),visitor(b,1))),tracking);
  check(tracking.faces>attentionFaces&&tracking.target.equals(a),"attention tracks locked identity rather than switching to a closer visitor");
  trackingBrain.update(seen(11*SECOND/10,a),tracking);
  trackingBrain.update(seen(12*SECOND/10,a),tracking);
  check(tracking.starts==1,"tracking test reaches running salutation");
  int saluteFaces=tracking.faces;
  trackingBrain.update(new HumanObservation(13*SECOND/10,List.of(visitor(a,25))),tracking);
  check(tracking.faces>saluteFaces&&tracking.starts==1,"salutation keeps tracking without restarting emote");
  trackingBrain.update(seen(4*SECOND,a),tracking);
  int speakingFaces=tracking.faces;trackingBrain.update(seen(41*SECOND/10,a),tracking);
  check(tracking.faces>speakingFaces&&tracking.says==1,"speech retains live attention before restoring facing");
  Port lostTracking=new Port();GuideBrain failedTracking=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));
  for(long now=0;now<=2*SECOND;now+=SECOND/2)failedTracking.update(seen(now,a),lostTracking);
  lostTracking.faceOK=false;failedTracking.update(seen(21*SECOND/10,a),lostTracking);
  check(lostTracking.stops==1&&lostTracking.says==1&&lostTracking.announcements==0&&lostTracking.homes==1,"failed tracking during wave cancels follow-up");
  Port actions=new Port();HumanActionExecution once=new HumanActionExecution(new HumanAction.Say("hello"));
  check(once.status()==HumanActionExecution.Status.PENDING,"new action pending");once.update(0,actions);once.update(SECOND,actions);
  check(once.status()==HumanActionExecution.Status.SUCCEEDED&&actions.says==1,"terminal action never executes twice");
  HumanActionExecution wait=new HumanActionExecution(new HumanAction.HoldAttention(a,SECOND));wait.update(0,actions);
  check(wait.status()==HumanActionExecution.Status.RUNNING,"timed action yields while running");wait.cancel(actions);wait.update(2*SECOND,actions);
  check(wait.status()==HumanActionExecution.Status.CANCELLED,"cancelled action stays terminal");
  actions.emoteOK=false;var noWave=new HumanActionExecution(new HumanAction.Emote("wavehi",SECOND));noWave.update(0,actions);
  check(noWave.status()==HumanActionExecution.Status.FAILED&&actions.stops==1,"partial emote start failure cleaned up");
  Port delayed=new Port();GuideBrain delayedBrain=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));cycle(delayedBrain,delayed,0,a);int count=delayed.says;
  delayedBrain.update(seen(1000*SECOND,a),delayed);check(delayed.says==count,"long scheduling gap does not replay completed commands");
  RoamingPort roaming=new RoamingPort();GuideBrain walker=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));
  walker.update(new HumanObservation(0,List.of(),true),roaming);
  walker.update(new HumanObservation(SECOND,List.of(),true),roaming);
  check(roaming.walks==0,"idle pause before roaming");
  walker.update(new HumanObservation(3*SECOND,List.of(),true),roaming);
  check(walker.state()==GuideBrain.State.ROAMING&&roaming.walks==1,"indoor observation starts a roaming action");
  walker.update(new HumanObservation(31*SECOND/10,List.of(visitor(a,4)),true),roaming);
  check(roaming.halts==1&&walker.state()==GuideBrain.State.HOLDING_ATTENTION,"visitor interrupts route and begins greeting");
  for(long now=32*SECOND/10;now<15*SECOND;now+=SECOND/10)walker.update(new HumanObservation(now,List.of(visitor(a,4)),true),roaming);
  check(roaming.says==1&&roaming.walks>=2,"roaming resumes after greeting without greeting lingering visitor again");
  int stopsBefore=roaming.halts;walker.update(new HumanObservation(16*SECOND,List.of(),false),roaming);
  check(walker.state()==GuideBrain.State.IDLE&&roaming.halts==stopsBefore+1,"loss of loaded roaming area cancels movement");
  RoamingPort completed=new RoamingPort();GuideBrain patrol=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));
  patrol.update(new HumanObservation(0,List.of(),true),completed);patrol.update(new HumanObservation(3*SECOND,List.of(),true),completed);
  completed.motion=HumanActuator.MotionStatus.SUCCEEDED;patrol.update(new HumanObservation(4*SECOND,List.of(),true),completed);
  check(patrol.state()==GuideBrain.State.IDLE&&completed.halts==1,"completed route settles and pauses");
  completed.motion=HumanActuator.MotionStatus.FAILED;patrol.update(new HumanObservation(7*SECOND,List.of(),true),completed);
  check(patrol.state()==GuideBrain.State.IDLE&&completed.homes==0,"blocked roaming waits without teleporting home");
  Port visitorCooldown=new Port();GuideBrain timedGreeter=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "hello", () -> List.of("operation"));cycle(timedGreeter,visitorCooldown,0,a);
  cycle(timedGreeter,visitorCooldown,20*SECOND,b);
  check(visitorCooldown.says==2,"visitor cooldown does not prevent greeting a different visitor");
  cycle(timedGreeter,visitorCooldown,40*SECOND,a);
  check(visitorCooldown.says==2,"original visitor remains on cooldown after leaving perception");
  cycle(timedGreeter,visitorCooldown,140*SECOND,a);
  check(visitorCooldown.says==3,"original visitor becomes eligible after two minutes");
  System.out.println("Passed "+checks+" brain, transition, cancellation and action assertions.");
 }
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-brain-tests-") as directory:
        work = Path(directory)
        harness = work / "BrainTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        sources = [harness, *sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans/brain").glob("*.java")), *sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans/profiles").glob("*.java"))]
        sources.extend(sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans/state").glob("*.java")))
        sources.extend(sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans/movement").glob("*.java")))
        sources.extend(sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans/walking").glob("*.java")))
        sources.extend(sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans/survival").glob("*.java")))
        sources.extend(sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans/tasks").glob("*.java")))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources))
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-encoding", "UTF-8", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "BrainTest"], check=True)


if __name__ == "__main__":
    main()
