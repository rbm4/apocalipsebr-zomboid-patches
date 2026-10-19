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
  int faces,starts,stops,says,homes;boolean faceOK=true,speechOK=true,emoteOK=true,homeOK=true,throwsSpeech;
  HumanObservation.Identity target;List<String> history=new ArrayList<>();
  public boolean face(HumanObservation.Identity id){faces++;target=id;history.add("face");return faceOK;}
  public boolean startEmote(String name){check(name.equals("wavehi"),"native greeting emote");starts++;history.add("wave");return emoteOK;}
  public void stopEmote(){stops++;history.add("stop");}
  public boolean say(String text){says++;history.add("hi");check(text.equals("hi"),"requested literal greeting");if(throwsSpeech)throw new IllegalStateException();return speechOK;}
  public boolean returnHome(){homes++;history.add("home");return homeOK;}
 }
 static void cycle(GuideBrain brain,Port port,long from,HumanObservation.Identity... ids){
  for(long now=from;now<from+15*SECOND;now+=SECOND/2)brain.update(seen(now,ids),port);
 }
 public static void main(String[] args){
  Port p=new Port();GuideBrain brain=new GuideBrain();brain.update(seen(0),p);
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
  check(p.history.indexOf("wave")<p.history.indexOf("hi")&&p.history.indexOf("hi")<p.history.indexOf("home"),"salutation speech return sequence");
  check(brain.state()==GuideBrain.State.IDLE,"cooldown ends at idle");
  cycle(brain,p,20*SECOND,a);check(p.says==1,"lingering visitor not greeted repeatedly");
  brain.update(seen(40*SECOND),p);cycle(brain,p,41*SECOND,a);check(p.says==2,"visitor can be greeted after leaving and returning");
  Port tie=new Port();GuideBrain tied=new GuideBrain();tied.update(seen(0,b,a),tie);check(tie.target.equals(a),"equal-distance selection deterministic by online ID");
  Port lost=new Port();GuideBrain interrupted=new GuideBrain();
  for(long now=0;now<=2*SECOND;now+=SECOND/2)interrupted.update(seen(now,a),lost);
  check(lost.starts==1,"salutation began before cancellation");
  interrupted.update(seen(2*SECOND+SECOND/2),lost);
  check(lost.stops==1&&lost.says==0&&lost.homes==1,"visitor loss cancels wave and restores post without speech");
  var replacement=new HumanObservation.Identity(a.onlineId(),999);
  Port replaced=new Port();GuideBrain replaceBrain=new GuideBrain();replaceBrain.update(seen(0,a),replaced);replaceBrain.update(seen(SECOND/2,replacement),replaced);
  check(replaced.homes==1&&replaced.says==0,"reused online ID cannot retain old target incarnation");
  Port failed=new Port();failed.faceOK=false;GuideBrain failureBrain=new GuideBrain();cycle(failureBrain,failed,0,a);
  check(failed.says==0&&failed.homes==1,"failed facing abandons greeting without retry storm");
  Port speech=new Port();speech.speechOK=false;GuideBrain speechBrain=new GuideBrain();cycle(speechBrain,speech,0,a);
  check(speech.says==1&&speech.homes==1,"unavailable chat settles failed once and restores post");
  Port thrown=new Port();thrown.throwsSpeech=true;GuideBrain thrownBrain=new GuideBrain();cycle(thrownBrain,thrown,0,a);
  check(thrown.says==1&&thrown.homes==1,"port exception cannot repeat speech or escape action runner");
  Port homeFailure=new Port();homeFailure.faceOK=false;homeFailure.homeOK=false;GuideBrain stopped=new GuideBrain();cycle(stopped,homeFailure,0,a);
  check(stopped.state()==GuideBrain.State.STOPPED,"failed home restoration stops brain");
  Port remove=new Port();GuideBrain removed=new GuideBrain();for(long now=0;now<=2*SECOND;now+=SECOND/2)removed.update(seen(now,a),remove);
  removed.stop(3*SECOND,remove);cycle(removed,remove,4*SECOND,a);
  check(removed.state()==GuideBrain.State.STOPPED&&remove.stops==1&&remove.says==0,"actor removal cancels active wave and permanently stops");
  Port tracking=new Port();GuideBrain trackingBrain=new GuideBrain();
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
  Port lostTracking=new Port();GuideBrain failedTracking=new GuideBrain();
  for(long now=0;now<=2*SECOND;now+=SECOND/2)failedTracking.update(seen(now,a),lostTracking);
  lostTracking.faceOK=false;failedTracking.update(seen(21*SECOND/10,a),lostTracking);
  check(lostTracking.stops==1&&lostTracking.says==0&&lostTracking.homes==1,"failed tracking during wave cancels interaction");
  Port actions=new Port();HumanActionExecution once=new HumanActionExecution(new HumanAction.Say("hi"));
  check(once.status()==HumanActionExecution.Status.PENDING,"new action pending");once.update(0,actions);once.update(SECOND,actions);
  check(once.status()==HumanActionExecution.Status.SUCCEEDED&&actions.says==1,"terminal action never executes twice");
  HumanActionExecution wait=new HumanActionExecution(new HumanAction.HoldAttention(a,SECOND));wait.update(0,actions);
  check(wait.status()==HumanActionExecution.Status.RUNNING,"timed action yields while running");wait.cancel(actions);wait.update(2*SECOND,actions);
  check(wait.status()==HumanActionExecution.Status.CANCELLED,"cancelled action stays terminal");
  actions.emoteOK=false;var noWave=new HumanActionExecution(new HumanAction.Emote("wavehi",SECOND));noWave.update(0,actions);
  check(noWave.status()==HumanActionExecution.Status.FAILED&&actions.stops==1,"partial emote start failure cleaned up");
  Port delayed=new Port();GuideBrain delayedBrain=new GuideBrain();cycle(delayedBrain,delayed,0,a);int count=delayed.says;
  delayedBrain.update(seen(1000*SECOND,a),delayed);check(delayed.says==count,"long scheduling gap does not replay completed commands");
  System.out.println("Passed "+checks+" brain, transition, cancellation and action assertions.");
 }
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-brain-tests-") as directory:
        work = Path(directory)
        harness = work / "BrainTest.java"
        harness.write_text(HARNESS)
        sources = [harness, *sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans/brain").glob("*.java"))]
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources))
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "BrainTest"], check=True)


if __name__ == "__main__":
    main()
