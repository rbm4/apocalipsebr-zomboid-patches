"""Execute production composites and injected announcement policy without engine stubs."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HARNESS = r"""
import java.util.*;
import zombie.apocalipse.humans.brain.*;
public class SequenceTest {
 static int checks;
 static final long S=1_000_000_000L;
 static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static void invalid(Runnable r){try{r.run();throw new AssertionError("invalid plan accepted");}catch(IllegalArgumentException|NullPointerException ok){checks++;}}
 static class Port implements HumanActuator {
  List<String> text=new ArrayList<>();List<Long> times=new ArrayList<>();long now;int starts,stops,homes,halts;boolean fail,throwSpeech;
  public boolean face(HumanObservation.Identity v){return true;}
  public boolean startEmote(String n){starts++;return true;}
  public void stopEmote(){stops++;}
  public boolean say(String s){text.add(s);times.add(now);if(throwSpeech)throw new IllegalStateException();return !fail;}
  public boolean returnHome(){homes++;return true;}
  public boolean startRoaming(long now){return true;}
  public MotionStatus updateRoaming(long now){return MotionStatus.RUNNING;}
  public void stopMoving(){halts++;}
 }
 static HumanObservation.Identity visitor=new HumanObservation.Identity((short)1,11);
 static HumanObservation seen(long n,boolean present){return new HumanObservation(n,present?List.of(new HumanObservation.Visitor(visitor,1,2,0,1)):List.of());}
 static void tick(GuideBrain b,Port p,long n,boolean present){p.now=n;b.update(seen(n,present),p);}
 static HumanActionExecution speech(Port p){return new HumanActionExecution(HumanAction.Sequence.speech(List.of("one","two"),4*S));}
 public static void main(String[] args){
  var original=new ArrayList<HumanAction>();original.add(new HumanAction.Say("one"));
  var immutable=new HumanAction.Sequence(original);original.clear();
  check(immutable.actions().size()==1,"sequence defensively copies actions");
  try{immutable.actions().clear();throw new AssertionError("mutable plan");}catch(UnsupportedOperationException ok){checks++;}
  invalid(()->new HumanAction.Wait(-1));invalid(()->new HumanAction.Sequence(Arrays.asList((HumanAction)null)));
  invalid(()->HumanAction.Sequence.speech(List.of(""),S));invalid(()->HumanAction.Sequence.speech(List.of("x".repeat(257)),S));
  invalid(()->HumanAction.Sequence.speech(Collections.nCopies(33,"x"),S));
  invalid(()->new HumanAction.Sequence(Collections.nCopies(65,new HumanAction.Wait(0))));
  HumanAction deep=new HumanAction.Wait(0);for(int i=0;i<8;i++)deep=new HumanAction.Sequence(List.of(deep));
  HumanAction depth=deep;invalid(()->new HumanAction.Sequence(List.of(depth)));
  var empty=new HumanActionExecution(new HumanAction.Sequence(List.of()));empty.update(0,new Port());
  check(empty.status()==HumanActionExecution.Status.SUCCEEDED,"empty sequence is successful no-op");
  Port p=new Port();var execution=speech(p);execution.update(0,p);
  check(p.text.equals(List.of("one"))&&!execution.terminal(),"first phrase once, wait begins without blocking");
  for(long n=0;n<4*S;n+=S/10){p.now=n;execution.update(n,p);}
  check(p.text.size()==1,"second phrase withheld for complete pause");
  p.now=4*S;execution.update(4*S,p);execution.update(20*S,p);
  check(execution.status()==HumanActionExecution.Status.SUCCEEDED&&p.text.equals(List.of("one","two")),"second phrase sent once and parent succeeds");
  Port other=new Port();var independent=new HumanActionExecution(execution.action());independent.update(0,other);
  check(other.text.equals(List.of("one")),"immutable plan has independent execution state per NPC");
  independent.cancel(other);independent.update(10*S,other);
  check(independent.status()==HumanActionExecution.Status.CANCELLED&&other.text.size()==1,"cancellation during wait discards remaining speech");
  Port pending=new Port();var cancelled=speech(pending);cancelled.cancel(pending);cancelled.update(0,pending);
  check(pending.text.isEmpty(),"pending cancellation performs no actions");
  Port failing=new Port();failing.fail=true;var failure=speech(failing);failure.update(0,failing);failure.update(10*S,failing);
  check(failure.status()==HumanActionExecution.Status.FAILED&&failing.text.size()==1,"child failure stops sequence and later siblings");
  Port throwing=new Port();throwing.throwSpeech=true;var thrown=speech(throwing);thrown.update(0,throwing);thrown.update(10*S,throwing);
  check(thrown.status()==HumanActionExecution.Status.FAILED&&"IllegalStateException".equals(thrown.failure())&&throwing.text.size()==1,"child exception settles parent once with failure detail");
  Port waving=new Port();var nested=new HumanActionExecution(new HumanAction.Sequence(List.of(new HumanAction.Sequence(List.of(new HumanAction.Emote("wavehi",S))),new HumanAction.Say("later"))));
  nested.update(0,waving);nested.cancel(waving);nested.cancel(waving);nested.update(2*S,waving);
  check(waving.starts==1&&waving.stops==1&&waving.text.isEmpty(),"nested cancellation cleans active animation once");
  Port moving=new Port();var route=new HumanActionExecution(new HumanAction.Sequence(List.of(new HumanAction.Roam(),new HumanAction.Say("later"))));
  route.update(0,moving);route.cancel(moving);
  check(moving.halts==1&&moving.text.isEmpty(),"composite cancellation cleans active movement without starting siblings");
  Port late=new Port();var three=new HumanActionExecution(HumanAction.Sequence.speech(List.of("one","two","three"),4*S));
  three.update(0,late);three.update(20*S,late);three.update(21*S,late);
  check(late.text.equals(List.of("one","two")),"long scheduling gap does not collapse remaining inter-phrase waits");
  int[] resolutions={0};List<String> lines=new ArrayList<>(List.of("active","next"));
  GuideBrain brain=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "Ol?!", ()->{resolutions[0]++;return lines;});Port actor=new Port();
  long first=-1;
  for(long n=0;n<10*S;n+=S/10){tick(brain,actor,n,true);if(actor.text.contains("active")){first=n;break;}}
  check(first>=0&&resolutions[0]==1,"provider resolved once when announcement begins after wave");
  lines.set(1,"changed");
  for(long n=first+S/10;n<first+4*S;n+=S/10)tick(brain,actor,n,true);
  check(!actor.text.contains("next")&&!actor.text.contains("changed"),"brain respects inter-phrase pause");
  tick(brain,actor,first+4*S,true);
  check(actor.text.contains("next")&&!actor.text.contains("changed")&&resolutions[0]==1,"announcement uses captured content for all phrases");
  for(int kind=0;kind<4;kind++){
   GuideBrain interrupted=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "Ol?!", ()->List.of("active","next"));Port port=new Port();long at=0;
   for(;at<10*S&&!port.text.contains("active");at+=S/10)tick(interrupted,port,at,true);
   if(kind==0)new HumanActivityController(interrupted,true,true).beginDialogue(visitor,at,port);
   if(kind==1)new HumanActivityController(interrupted,true,true).beginTrade(visitor,at,port);
   if(kind==2)interrupted.stop(at,port);
   if(kind==3)tick(interrupted,port,at,false);
   for(long n=at;n<at+10*S;n+=S/10)tick(interrupted,port,n,kind!=3);
   check(!port.text.contains("next"),"dialogue/trade/despawn/visitor loss cancels remaining announcement: "+kind);
  }
  GuideBrain broken=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "Ol?!", ()->{throw new IllegalStateException();});Port recovery=new Port();
  for(long n=0;n<15*S;n+=S/10)tick(broken,recovery,n,true);
  check(recovery.homes==1&&recovery.text.size()==1,"provider failure restores post without repeating greeting");
  GuideBrain invalidContent=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "Ol?!", ()->List.of("partial","x".repeat(257)));Port atomic=new Port();
  for(long n=0;n<15*S;n+=S/10)tick(invalidContent,atomic,n,true);
  check(!atomic.text.contains("partial")&&atomic.homes==1,"invalid second phrase rejects complete announcement before first phrase is sent");
  GuideBrain noContent=new GuideBrain(zombie.apocalipse.humans.profiles.GuidePolicy.defaults(), () -> "Ol?!", HumanAnnouncementProvider.NONE);Port quiet=new Port();
  for(long n=0;n<15*S;n+=S/10)tick(noContent,quiet,n,true);
  check(quiet.text.size()==1&&quiet.homes==1,"empty provider preserves greeting and completes without extra speech");
  System.out.println("Passed "+checks+" sequence, wait, provider, snapshot and interruption assertions.");
 }
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-sequence-tests-") as directory:
        work = Path(directory)
        harness = work / "SequenceTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        sources = [harness, *sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans/brain").glob("*.java")), *sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans/profiles").glob("*.java"))]
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in sources), encoding="utf-8")
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-encoding", "UTF-8", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "SequenceTest"], check=True)


if __name__ == "__main__":
    main()
