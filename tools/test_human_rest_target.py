"""Exercise real target-built rest/profile/greeting code with mechanical ports."""
from pathlib import Path
import argparse, os, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
HARNESS=r'''
import java.util.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.rest.*;
import zombie.apocalipse.humans.profiles.*;
import zombie.apocalipse.humans.furniture.*;
import zombie.apocalipse.humans.furniture.FurnitureAffordances.*;
import zombie.apocalipse.humans.seating.HumanSeating;
import zombie.apocalipse.humans.state.HumanState;
import zombie.apocalipse.humans.ServerHumanProfiles;
public class RestTest {
 static int checks;static final long SEC=1000000000L;
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static final class E implements HumanSeating.Execution {
  HumanSeating.Status status=HumanSeating.Status.RUNNING;boolean seated=true;int cancels;
  public HumanSeating.Status update(long now){return status;}public void cancel(){cancels++;status=HumanSeating.Status.CANCELLED;}
  public boolean seated(){return seated&&status==HumanSeating.Status.RUNNING;}
 }
 static class A implements HumanActuator {
  E e;int begins,walks,faces,emotes,says,homes,stopSeats;boolean reject;HumanSeating.Request request;
  public HumanSeating seating(){return (r,n)->{begins++;request=r;if(reject)return null;e=new E();return e;};}
  public boolean seated(){return e!=null&&e.seated();}public void stopSeating(){stopSeats++;if(e!=null)e.cancel();}
  public boolean face(HumanObservation.Identity i){faces++;return true;}public boolean say(String s){says++;return true;}
  public boolean startEmote(String s){emotes++;return false;}public void stopEmote(){}public boolean returnHome(){homes++;return true;}
  public boolean startMovement(zombie.apocalipse.humans.walking.MovementRequest r,long n){walks++;return true;}
  public MotionStatus updateMovement(long n){return MotionStatus.RUNNING;}
 }
 static Candidate seat(int id,long now){return new Candidate(new Token(id,10+id,10,0,"S"),Kind.SIT,
  List.of(new Approach(new Position(10+id,11,0),"Front")),"S",false,now);}
 static HumanObservation observation(long now,boolean safe,boolean seats){
  var contacts=safe?List.<HumanSenses.Contact>of():List.of(new HumanSenses.Contact(new HumanSenses.Identity(HumanSenses.Kind.ZOMBIE,(short)1,2,3),10,10,0,1,now,now,true));
  var senses=new HumanSenses.Frame(true,now,contacts,HumanSenses.Coverage.COMPLETE,HumanSenses.Coverage.COMPLETE,false,new HumanSenses.Work(0,0,0,0,0));
  var furniture=seats?new Frame(true,now,List.of(seat(1,now),seat(2,now)),Coverage.COMPLETE,false,new Work(1,2)):Frame.EMPTY;
  return new HumanObservation(now,List.of(),true,HumanState.INITIAL,senses,HumanTerritory.UNAVAILABLE,senses,HumanObservation.Support.NONE,furniture,new Position(10,10,0));
 }
 static RestModule module(){return new RestModule(RestSettings.from(Map.of()),new Random(7));}
 static void tick(RestModule m,A a,long n){var o=observation(n,true,true);m.monitor(o,a);if(m.eligible(o))m.update(o,a);}
 static void lifecycle(){
  var m=module();var a=new A();check(m.furnitureDemand().kinds().contains(Kind.SIT),"module enables sensing");
  check(!m.eligible(observation(0,true,false))&&!m.eligible(observation(0,false,true)),"missing chairs or danger allows routine walking");
  tick(m,a,0);check(a.begins==1&&a.request.durationMs()>=600000&&a.request.durationMs()<=1200000,"long real-time seating duration");
  m.interrupt(SEC,a);check(a.e.cancels==0,"greeting retains stable seat");
  m.suspend(2*SEC,a);check(!m.eligible(observation(3*SEC,true,true))&&a.e.cancels==0,"dialogue suspends policy while native posture survives");
  m.resume(null,4*SEC,a);tick(m,a,4*SEC);check(a.begins==1,"resume reuses owned execution");
  a.e.status=HumanSeating.Status.SUCCEEDED;tick(m,a,5*SEC);check(!m.eligible(observation(34*SEC,true,true)),"walk interval prevents immediate reseating");
  tick(m,a,35*SEC);check(a.begins==2,"seat reacquired after short walk");
  m.update(observation(36*SEC,false,true),a);check(a.e.cancels==1,"danger cancels native posture");
  var b=new A();var n=module();tick(n,b,0);b.e.seated=false;n.suspend(SEC,b);check(b.e.cancels==1,"transient entry cannot transfer to dialogue");
  var c=new A();var k=module();tick(k,c,0);k.stop(SEC,c);check(c.e.cancels==1&&!k.available(),"shutdown releases retained pose");
  var d=new A();tick(module(),d,0);check(d.e!=a.e,"actor execution state isolated");
 }
 static void retry(){
  var m=module();var a=new A();a.reject=true;tick(m,a,0);var first=a.request.target();
  check(!m.eligible(observation(SEC/2,true,true)),"failed begin does not spin");tick(m,a,SEC);
  check(!a.request.target().equals(first)&&a.begins==2,"alternate chair tried after rejection");
  tick(m,a,2*SEC);check(a.begins==2,"refused chairs yield to walking");
  tick(m,a,32*SEC);check(a.begins==3,"refusals expire and retry is bounded");
  var b=new A();var k=module();tick(k,b,0);b.e.status=HumanSeating.Status.FAILED;tick(k,b,SEC);var failed=b.request.target();tick(k,b,2*SEC);
  check(!b.request.target().equals(failed),"path failure also selects another chair");
 }
 static void controller(){
  var rest=module();var runtime=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("rest",500,100,rest),new HumanModuleRuntime.Entry("walk",1000,100,new WalkingModule(0))));
  var controller=new HumanActivityController(runtime,true,true);var a=new A();controller.update(observation(0,true,true),a);
  check(a.begins==1&&a.walks==0,"rest beats always-eligible walking");
  var visitor=new HumanObservation.Identity((short)8,9);check(controller.beginDialogue(visitor,SEC,a),"dialogue admitted while seated");
  controller.update(observation(2*SEC,true,true),a);check(a.faces==1&&a.e.cancels==0,"dialogue keeps seat");controller.finishDialogue(3*SEC,a);
  check(controller.beginTrade(visitor,4*SEC,a),"trade admitted while seated");controller.finishTrade(5*SEC,a);
  controller.setBlocked(true,6*SEC,a);check(a.stopSeats==1&&a.e.cancels==1,"external block cancels posture");controller.setBlocked(false,7*SEC,a);
  controller.update(observation(7*SEC,true,true),a);controller.update(observation(7*SEC+SEC/10,true,true),a);check(a.walks>0,"cancelled seat yields during retry");
 }
 static void greeting(){
  var a=new A();a.e=new E();var g=new GuideBrain(GuidePolicy.defaults(),()->"hello",HumanAnnouncementProvider.NONE).greetingOnly();
  var visitor=new HumanObservation.Visitor(new HumanObservation.Identity((short)2,3),11,11,0,2);
  for(long n=0;n<20*SEC;n+=SEC/2)g.update(new HumanObservation(n,List.of(visitor)),a);
  check(a.says==1&&a.emotes==0&&a.homes==1&&g.state()==GuideBrain.State.IDLE,"seated greeting speaks without full-body wave or failure");
 }
 static HumanProfile profile(String id,int priority,Map<String,Long> params){return new HumanProfile(id,1,"modules",Map.of(),"","none",true,true,
  List.of(new HumanModuleBinding("rest",priority,100,params),new HumanModuleBinding("walk",1000,100,Map.of())),8,100,true);}
 static void profiles(){
  var registry=ServerHumanProfiles.registry();var p=profile("test-rest",500,Map.of());registry.register(p);
  check(registry.create(p)!=registry.create(p)&&registry.create(p).furnitureDemand().radius()==8,"allowlisted fresh factory requests sensing");
  try{registry.register(profile("bad-priority",1001,Map.of()));throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  try{registry.register(profile("bad-duration",500,Map.of("minSeatMs",2000000L)));throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  try{registry.register(profile("bad-safety",500,Map.of("clearTiles",9L)));throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  try{RestSettings.from(Map.of("unknown",1L));throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
 }
 public static void main(String[] args){lifecycle();retry();controller();greeting();profiles();System.out.println("PASS: "+checks+" target-built rest assertions");}
}
'''
if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--classes",type=Path,required=True);args=parser.parse_args()
    cp=os.pathsep.join((str(args.classes.resolve()),"Z:/SteamLibrary/steamapps/common/ProjectZomboid/*",str(ROOT/"42.22.2/lib/*")))
    with tempfile.TemporaryDirectory(prefix="apocbr-rest-") as directory:
        work=Path(directory);source=work/"RestTest.java";source.write_text(HARNESS,encoding="utf-8")
        subprocess.run([str(ROOT/"jdk/bin/javac.exe"),"--release","25","-cp",cp,"-d",str(work),str(source)],check=True)
        subprocess.run([str(ROOT/"jdk/bin/java.exe"),"-cp",os.pathsep.join((str(work),cp)),"RestTest"],check=True)
