"""Production 42.22.2 seating math, phases, action ownership and stock-asset verification.

Native rotation oracle is extracted from target SeatingManager. Native collision,
animation playback, transport and world calls are explicit ports/doubles here.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "42.22.2/src/zombie/apocalipse/humans"

HARNESS = r'''
import java.util.*;import java.nio.file.*;
import zombie.apocalipse.humans.seating.*;import zombie.apocalipse.humans.seating.HumanSeating.*;
import zombie.apocalipse.humans.furniture.*;import zombie.apocalipse.humans.furniture.FurnitureAffordances.*;
import zombie.apocalipse.humans.brain.*;
public class SeatingTest {
 static int checks;static final long SEC=1_000_000_000L;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static final Token TARGET=new Token(1,10,10,0,"S");
 static Candidate candidate(String facing){return new Candidate(new Token(1,10,10,0,facing),Kind.SIT,List.of(),facing,false,0,new Seat(.1f,.08f,.2f,Set.of("Front","Left","Right")));}
 static final class Vec {float x,y;void set(float x,float y){this.x=x;this.y=y;}void rotate(float angle){float ox=x,oy=y;x=(float)(ox*Math.cos(angle)-oy*Math.sin(angle));y=(float)(ox*Math.sin(angle)+oy*Math.cos(angle));}}
 static Vec nativeEntry(String sitDirectionStr,String sideStr,float sxf,float syf,Vec dm){Vec worldPos=new Vec();NATIVE_MATH return worldPos;}
 static void geometry(){
  for(String direction:List.of("N","S","W","E"))for(String side:List.of("Front","Left","Right")){
   var c=candidate(direction);var actual=SeatingGeometry.plan(c,side,false);var solid=SeatingGeometry.plan(c,side,true);
   var dm=new Vec();dm.x=-.003f*1.5f;dm.y=(side.equals("Front")?-.263279f:.287944f)*1.5f;
   var nativePoint=nativeEntry(direction,side,.1f,.08f,dm);
   check(Math.abs(actual.entry().x()-(10.5f+nativePoint.x))<.00001&&Math.abs(actual.entry().y()-(10.5f+nativePoint.y))<.00001,"entry matches exact native rotation "+direction+side);
   check(solid!=null&&SeatingGeometry.distance(solid.approach(),solid.seat())<1.6,"bounded native solid-seat staging "+direction+side);
   check(solid.approach().x()<10||solid.approach().x()>=11||solid.approach().y()<10||solid.approach().y()>=11,"solid seat staging outside owned tile");
   check(actual.target().slot().equals(direction)&&actual.side().equals(side),"immutable plan qualification");
  }
  var blocked=new Candidate(TARGET,Kind.SIT,List.of(),"S",false,0,new Seat(0,0,0,Set.of("Front")));
  check(SeatingGeometry.plan(blocked,"Left",true)==null,"authored blocked side unavailable");
  check(SeatingGeometry.plan(new Candidate(TARGET,Kind.SIT,List.of(),"S",false,0,new Seat(4,0,0,Set.of("Front"))),"Front",true)==null,"oversized transition unavailable");
 }
 static final class Port implements SeatingExecution.Port {
  boolean valid=true,blocked,throwEnd,throwSit;int approaches,sits,seated,stands,ends,aborts,exitQueries;
  SeatingExecution.Motion motion=SeatingExecution.Motion.SUCCEEDED;
  public boolean valid(){return valid;}public SeatingExecution.Motion approach(long now){approaches++;return motion;}
  public void sit(){sits++;if(throwSit)throw new IllegalStateException("injected sit failure");}
  public boolean sitting(double fraction){return true;}public void seated(){seated++;}
  public SeatingGeometry.Plan exit(){exitQueries++;return blocked?null:SeatingGeometry.plan(candidate("S"),"Left",true);}
  public void stand(SeatingGeometry.Plan plan){stands++;check(plan.side().equals("Left"),"alternate side accepted from native validation port");}
  public boolean standing(double fraction){return !blocked;}
  public void end(boolean abort){ends++;if(abort)aborts++;if(throwEnd)throw new IllegalStateException("injected end failure");}
 }
 static SeatingExecution execution(FurnitureReservations r,Port p){return new SeatingExecution(new Object(),r,new Request(TARGET,"Front",100),p,0);}
 static void phases(){
  var reservations=new FurnitureReservations();var p=new Port();var e=execution(reservations,p);
  check(e.update(0)==Status.RUNNING&&e.phase()==Phase.SIT_DOWN&&p.sits==1,"approach commits one sit");
  e.update(SEC/2);check(p.seated==0,"no premature seated authority");e.update(1_250_000_000L);
  check(e.phase()==Phase.SEATED&&p.seated==1,"native timing settles once");e.update(1_400_000_000L);check(e.phase()==Phase.WAIT_EXIT,"dwell measured from actual seating");
  p.blocked=true;e.update(1_500_000_000L);e.update(1_600_000_000L);
  check(p.exitQueries==1&&p.stands==0&&reservations.acquire(new Object(),TARGET,1_600_000_000L)==null,"blocked exit retains seat and bounded retry");
  p.blocked=false;e.update(2_500_000_000L);check(e.phase()==Phase.STAND_UP&&p.stands==1,"available alternate exit commits stand");
  p.blocked=true;e.update(3_800_000_000L);check(e.status()==Status.RUNNING&&reservations.acquire(new Object(),TARGET,3_800_000_000L)==null,"new obstruction during standing retains ownership");
  p.blocked=false;check(e.update(3_900_000_000L)==Status.SUCCEEDED&&p.ends==1,"finish only after validated exit");
  e.cancel();e.update(4*SEC);check(p.ends==1&&reservations.acquire(new Object(),TARGET,4*SEC)!=null,"terminal effects and cleanup exactly once");
  reservations=new FurnitureReservations();p=new Port();e=execution(reservations,p);e.cancel();
  check(e.update(0)==Status.CANCELLED&&p.sits==0&&p.ends==1,"cancel before sit never commits posture");
  reservations=new FurnitureReservations();p=new Port();e=execution(reservations,p);e.update(0);e.cancel();e.update(1_250_000_000L);e.update(1_400_000_000L);
  check(e.phase()==Phase.STAND_UP,"cancelled action leaves native cleanup running");e.update(2_700_000_000L);check(e.status()==Status.CANCELLED&&p.ends==1,"cancelled posture completes safe standing");
  reservations=new FurnitureReservations();p=new Port();e=execution(reservations,p);e.update(0);check(e.update(6*SEC)==Status.FAILED&&p.aborts==1,"stalled lease cannot be silently renewed");
  reservations=new FurnitureReservations();p=new Port();e=execution(reservations,p);p.valid=false;check(e.update(0)==Status.FAILED&&p.aborts==1,"removed/reused actor or furniture aborts");
  reservations=new FurnitureReservations();p=new Port();p.throwSit=true;e=execution(reservations,p);
  try{e.update(0);throw new AssertionError("exception swallowed");}catch(IllegalStateException expected){checks++;}
  check(e.status()==Status.FAILED&&p.ends==1&&reservations.acquire(new Object(),TARGET,0)!=null,"partial native begin failure releases lease");
  reservations=new FurnitureReservations();p=new Port();p.motion=SeatingExecution.Motion.RUNNING;e=execution(reservations,p);
  for(int i=0;i<16;i++)e.update(i*SEC);check(e.status()==Status.FAILED&&p.sits==0,"approach time is bounded independently of scheduling");
  reservations=new FurnitureReservations();p=new Port();p.throwEnd=true;e=execution(reservations,p);p.valid=false;
  try{e.update(0);throw new AssertionError("end failure swallowed");}catch(IllegalStateException expected){checks++;}
  check(e.status()==Status.FAILED&&reservations.acquire(new Object(),TARGET,0)!=null,"cleanup exception still releases lease");
 }
 static final class Behavior implements HumanBehavior {
  int suspends,resumes,updates;boolean emergency;
  public boolean emergency(HumanObservation o){return emergency;}public boolean available(){return true;}
  public void update(HumanObservation o,HumanActuator a){updates++;}public void suspend(long n,HumanActuator a){suspends++;}
  public void resume(HumanObservation.Identity i,long n,HumanActuator a){resumes++;}public void stop(long n,HumanActuator a){}
 }
 static final class Actuator implements HumanActuator {
  final FurnitureReservations reservations=new FurnitureReservations();final Port nativePort=new Port();SeatingExecution execution;int starts;
  public HumanSeating seating(){return (request,now)->{starts++;execution=new SeatingExecution(this,reservations,request,nativePort,now);return new HumanSeating.Execution(){public Status update(long at){return execution.update(at);}public void cancel(){execution.cancel();}};};}
 }
 static void actions(){
  var a=new Actuator();var b=new Behavior();var controller=new HumanActivityController(b,true,true);
  var request=new HumanAction.Sit(new Request(TARGET,"Front",100));
  check(controller.beginAction(new HumanAction.Sequence(List.of(request,new HumanAction.Wait(1))),0,a),"generic exclusive action admitted");
  check(controller.mode()==HumanActivityController.Mode.ACTION&&b.suspends==1&&a.starts==0,"queue does not actuate before update");
  check(!controller.beginDialogue(new HumanObservation.Identity((short)1,1),0,a),"dialogue cannot steal active motion");
  controller.update(new HumanObservation(0,List.of()),a);check(a.starts==1,"seating description starts once");
  b.emergency=true;controller.update(new HumanObservation(SEC/10,List.of()),a);
  check(controller.mode()==HumanActivityController.Mode.AUTONOMOUS&&b.resumes==1&&a.execution.status()==Status.RUNNING,"emergency returns policy ownership but cleanup stays native-owned");
  a.execution.update(1_250_000_000L);a.execution.update(1_400_000_000L);a.execution.update(2_700_000_000L);
  check(a.execution.status()==Status.CANCELLED&&a.nativePort.ends==1,"native phase settles after parent cancellation");
  var unsupported=new HumanActionExecution(request);unsupported.update(0,new HumanActuator(){});check(unsupported.status()==HumanActionExecution.Status.FAILED,"unsupported capability fails without actuation");
  try{new HumanAction.Parallel(List.of(request));throw new AssertionError("competing motion accepted");}catch(IllegalArgumentException expected){checks++;}
  var another=new Actuator();var fresh=new HumanActionExecution(request);fresh.update(0,another);check(another.starts==1&&a.starts==1,"reused action data has fresh execution state");fresh.cancel(another);
 }
 static void assets(String directory)throws Exception{
  var root=Path.of(directory);check(StockSeatingAssets.supported(p->Files.readAllBytes(root.resolve(p))),"installed target fingerprints supported");
  String changed=StockSeatingAssets.HASHES.keySet().iterator().next();
  check(!StockSeatingAssets.supported(p->{var bytes=Files.readAllBytes(root.resolve(p));if(p.equals(changed))bytes[0]^=1;return bytes;}),"modified stock assets unavailable");
  check(!StockSeatingAssets.supported(p->null),"missing animations unavailable");
  var inventory=new HashSet<String>();
  for(String dir:StockSeatingAssets.INVENTORIES)try(var paths=Files.walk(root.resolve(dir))){paths.filter(p->p.toString().endsWith(".xml")).forEach(p->inventory.add(root.relativize(p).toString().replace('\\','/').toLowerCase(Locale.ROOT)));}
  check(StockSeatingAssets.inventorySupported(inventory),"stock state inventory supported");
  inventory.add("media/animsets/player/sitonfurniture/mod-added.xml");check(!StockSeatingAssets.inventorySupported(inventory),"additional animation nodes cannot bypass stock fingerprints");
 }
 public static void main(String[] args)throws Exception{geometry();phases();actions();assets(args[0]);System.out.println("PASS: "+checks+" production seating geometry/execution/action/asset assertions");}
}
'''


def main():
    native = (ROOT / "42.22.2/decompiled/zombie/seating/SeatingManager.java").read_text(encoding="utf-8")
    math = native[native.index("float angleRadians = 0.0F;", native.index("private boolean getAdjacentPosition(")):native.index("worldPos.set(sxf - dm.x, syf - dm.y);") + len("worldPos.set(sxf - dm.x, syf - dm.y);")]
    fixture = {
        "SeatingTest.java": HARNESS.replace("NATIVE_MATH", math),
        "zombie/apocalipse/humans/profiles/HumanProfile.java": "package zombie.apocalipse.humans.profiles;public class HumanProfile {public static void identifier(String id){if(id==null||id.isBlank())throw new IllegalArgumentException();}}",
        "zombie/apocalipse/humans/brain/HumanActuator.java": r'''package zombie.apocalipse.humans.brain;
public interface HumanActuator {
 enum MotionStatus{RUNNING,SUCCEEDED,FAILED}
 default zombie.apocalipse.humans.seating.HumanSeating seating(){return zombie.apocalipse.humans.seating.HumanSeating.UNSUPPORTED;}
 default boolean startMovement(zombie.apocalipse.humans.walking.MovementRequest request,long now){return false;}
 default MotionStatus updateMovement(long now){return MotionStatus.FAILED;}default void stopMoving(){}default void stopEmote(){}
 default boolean face(HumanObservation.Identity i){return true;}default boolean say(String s){return true;}default boolean startEmote(String n){return true;}default boolean returnHome(){return true;}
}''',
    }
    with tempfile.TemporaryDirectory(prefix="apocbr-seating-") as directory:
        work = Path(directory)
        sources = []
        for name, content in fixture.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        for package in ("furniture", "interactions", "seating"):
            sources.extend(sorted((SOURCE / package).glob("*.java")))
        for name in ("brain/HumanBehavior", "brain/HumanObservation", "brain/HumanSenses", "brain/HumanTerritory", "brain/HumanAction", "brain/HumanActionExecution", "brain/HumanActivityController", "state/HumanState", "walking/MovementRequest", "walking/WalkingPatternSpec"):
            sources.append(SOURCE / (name + ".java"))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources), encoding="utf-8")
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-encoding", "UTF-8", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "SeatingTest", "Z:/SteamLibrary/steamapps/common/ProjectZomboid"], check=True)


if __name__ == "__main__":
    main()
