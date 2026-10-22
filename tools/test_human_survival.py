"""Fight/flight production policy, route planning, interruption and activation flags."""
from pathlib import Path
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True
from test_server_humans import FILES, ROOT

HARNESS=r'''
package zombie.apocalipse.humans;
import java.util.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.survival.*;
import zombie.apocalipse.humans.profiles.*;
import zombie.apocalipse.humans.state.*;
import zombie.apocalipse.humans.movement.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import static zombie.apocalipse.humans.brain.HumanSenses.*;
import static zombie.apocalipse.humans.brain.HumanCombat.*;
public class SurvivalTest {
 static int checks;
 static final long SECOND=1_000_000_000L;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static Contact contact(int n,double distance,long now){return new Contact(new Identity(Kind.ZOMBIE,(short)n,n,n),10+n,10,0,distance,now,now,true);}
 static HumanState vital(float health,float endurance){return HumanStateReducer.reduce(HumanStateReducer.reduce(HumanState.INITIAL,new HumanMutation.Damage(1-health,0,0,0)),new HumanMutation.Adjust(HumanState.Stat.ENDURANCE,endurance-1));}
 static HumanObservation observe(long now,HumanState state,int count,double distance,Coverage coverage){
  var contacts=new ArrayList<Contact>();for(int n=0;n<count;n++)contacts.add(contact(n,distance,now));
  return new HumanObservation(now,List.of(),true,state,new Frame(true,now,contacts,Coverage.COMPLETE,coverage,false,new Work(0,0,0,0,0)),new HumanTerritory(true,true,contacts.stream().map(Contact::identity).collect(java.util.stream.Collectors.toSet())));
 }
 static class Port implements HumanActuator {
  int escapes,cancels,begins,stops;boolean possible=true;MotionStatus motion=MotionStatus.RUNNING;List<String> transitions=new ArrayList<>();
  public SurvivalPort survival(){return new SurvivalPort(){
   public boolean escape(List<Contact> c,SurvivalSettings s,long now){escapes++;return possible;}
   public void transition(String phase,String reason,int nearby){transitions.add(phase+":"+reason);}
  };}
  public HumanCombat.Port combat(){return (request,now)->{begins++;return new Execution(){
   public Result update(long at){return new Result(Status.RUNNING,Reason.NONE);}public void cancel(Reason reason){cancels++;}
  };};}
  public MotionStatus updateMovement(long now){return motion;}public void stopMoving(){stops++;}
  public boolean face(HumanObservation.Identity id){return true;}public boolean startEmote(String s){return true;}
  public void stopEmote(){}public boolean say(String s){return true;}public boolean returnHome(){return true;}
 }
 static void lifecycle(){
  var s=new SurvivalModule(SurvivalSettings.DEFAULT);var p=new Port();
  check(!s.eligible(observe(0,HumanState.INITIAL,3,9,Coverage.COMPLETE)),"three threats do not trigger default crowd threshold");
  var crowd=observe(0,HumanState.INITIAL,4,9,Coverage.PARTIAL);
  check(s.eligible(crowd)&&s.eligible(crowd)&&p.escapes==0&&s.phase()==SurvivalModule.Phase.SAFE,"eligibility is pure; known crowd triggers despite partial coverage");
  s.update(crowd,p);check(s.phase()==SurvivalModule.Phase.ESCAPING&&p.escapes==1,"crowd starts escape once");
  s.update(observe(SECOND,HumanState.INITIAL,4,9,Coverage.PARTIAL),p);check(p.escapes==1,"running route not restarted on every tick");
  p.motion=HumanActuator.MotionStatus.SUCCEEDED;
  s.update(observe(2*SECOND,HumanState.INITIAL,0,0,Coverage.PARTIAL),p);
  check(s.phase()==SurvivalModule.Phase.RECOVERING,"arrival begins recovery");
  s.update(observe(7*SECOND,HumanState.INITIAL,0,0,Coverage.PARTIAL),p);
  check(s.phase()!=SurvivalModule.Phase.SAFE,"minimum retreat duration prevents instant recommit");
  s.update(observe(8*SECOND,HumanState.INITIAL,0,0,Coverage.PARTIAL),p);
  check(s.phase()==SurvivalModule.Phase.SAFE,"sustained clear observations and recovery release survival");
  s.update(observe(9*SECOND,vital(1,.2f),0,0,Coverage.COMPLETE),p);
  check(s.phase()==SurvivalModule.Phase.RECOVERING,"exhaustion starts recovery even without enemies");
  s.update(observe(30*SECOND,vital(1,.5f),0,0,Coverage.COMPLETE),p);
  check(s.phase()!=SurvivalModule.Phase.SAFE,"endurance recovery threshold is higher than retreat threshold");
  s.update(observe(31*SECOND,HumanState.INITIAL,0,0,Coverage.UNAVAILABLE),p);
  s.update(observe(32*SECOND,HumanState.INITIAL,0,0,Coverage.COMPLETE),p);
  check(s.phase()!=SurvivalModule.Phase.SAFE,"unavailable sensing resets safety hold");
  s.update(observe(37*SECOND,HumanState.INITIAL,0,0,Coverage.COMPLETE),p);
  check(s.phase()==SurvivalModule.Phase.SAFE,"sensing resumes with a new full hold");
  s.update(observe(40*SECOND,vital(.3f,1),0,0,Coverage.COMPLETE),p);
  s.update(observe(60*SECOND,vital(.45f,1),0,0,Coverage.COMPLETE),p);
  check(s.phase()!=SurvivalModule.Phase.SAFE,"wounded actor cannot reengage until health recovers");
  s.suspend(61*SECOND,p);check(!s.eligible(crowd),"interaction suspension prevents actuation");
  s.resume(null,62*SECOND,p);check(s.eligible(observe(62*SECOND,HumanState.INITIAL,0,0,Coverage.COMPLETE)),"survival latch survives suspension");
  s.stop(63*SECOND,p);check(!s.available()&&!s.eligible(crowd),"stop closes policy");
  var cornered=new SurvivalModule(SurvivalSettings.DEFAULT);p=new Port();p.possible=false;
  cornered.update(observe(0,HumanState.INITIAL,4,1,Coverage.COMPLETE),p);
  check(cornered.phase()==SurvivalModule.Phase.CORNERED&&p.begins==1,"blocked escape permits immediate self-defense");
  cornered.update(observe(SECOND,HumanState.INITIAL,4,1,Coverage.COMPLETE),p);
  check(p.escapes==1&&p.begins==1,"cornered actor retains execution and backs off route retries");
  cornered.update(observe(2*SECOND,HumanState.INITIAL,4,9,Coverage.COMPLETE),p);
  check(p.cancels==1&&p.begins==1,"self-defense never pursues a departing target");
  p.possible=true;cornered.update(observe(4*SECOND,HumanState.INITIAL,4,9,Coverage.COMPLETE),p);
  check(cornered.phase()==SurvivalModule.Phase.ESCAPING,"cornered actor retries and escapes once possible");
  cornered.update(observe(19*SECOND,HumanState.INITIAL,0,0,Coverage.COMPLETE),p);
  check(cornered.phase()==SurvivalModule.Phase.RECOVERING,"route deadline terminates stuck movement");
 }
 static void arbitration(){
  var survival=new SurvivalModule(SurvivalSettings.DEFAULT);var engagement=new EngageZombiesModule(HumanEngagement.Settings.DEFAULT);var p=new Port();
  var runtime=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("survival",0,100,survival),new HumanModuleRuntime.Entry("engage-zombies",100,100,engagement)));
  runtime.update(observe(0,HumanState.INITIAL,1,9,Coverage.COMPLETE),p);
  runtime.update(observe(SECOND,HumanState.INITIAL,4,9,Coverage.COMPLETE),p);
  check(p.begins==1&&p.cancels==1&&p.escapes==1&&runtime.selectedModule().equals("survival"),"survival preempts and cancels active fight before starting movement");
  runtime.update(observe(2*SECOND,HumanState.INITIAL,1,9,Coverage.COMPLETE),p);
  check(runtime.selectedModule().equals("survival")&&p.begins==1,"crowd dropping below threshold cannot immediately restart pursuit");
 }
 static class Grid implements MovementGrid {
  int calls;Set<Tile> blocked=new HashSet<>();
  public boolean available(){return true;}
  public boolean walkable(Tile t){calls++;return Math.abs(t.x())<=16&&Math.abs(t.y())<=16&&!blocked.contains(t);}
  public boolean connected(Tile a,Tile b){return !blocked.contains(b);}
 }
 static void routes(){
  var grid=new Grid();var start=new Tile(0,0);var threats=List.of(new RetreatPlanner.Threat(2.5,.5));
  var route=RetreatPlanner.plan(grid,start,threats,12);
  check(route.size()>2&&route.getFirst().equals(start)&&route.getLast().x()<0,"escape leads away from crowd");
  check(grid.calls<1100,"escape search has a bounded world-query budget");
  for(int n=1;n<route.size();n++)check(Math.abs(route.get(n).x()-route.get(n-1).x())+Math.abs(route.get(n).y()-route.get(n-1).y())==1,"route is cardinal and executable");
  grid.blocked.addAll(List.of(new Tile(1,0),new Tile(-1,0),new Tile(0,1),new Tile(0,-1)));
  check(RetreatPlanner.plan(grid,start,threats,12).isEmpty(),"trapped actor never receives fabricated escape success");
  check(RetreatPlanner.plan(new Grid(),start,List.of(),12).isEmpty(),"no threats is not an escape destination");
  var two=RetreatPlanner.plan(new Grid(),start,List.of(new RetreatPlanner.Threat(2.5,.5),new RetreatPlanner.Threat(-1.5,.5)),12);
  check(!two.isEmpty()&&Math.abs(two.getLast().y())>0,"escape considers threats on both sides");
 }
 static void flags(){
  var base=ServerHumanProfiles.registry().require("militia-patrol");
  check(base.modules().getFirst().moduleId().equals("survival"),"standalone militia includes survival");
  var profile=new HumanProfile("survival-test",1,base.behaviorId(),base.parameters(),base.greetingKey(),base.announcementId(),false,false,base.modules(),8,100,true,base.walkingPatterns(),new HumanCheats(false,true,true),base.survival());
  ServerHumanProfiles.registry().register(profile);
  var actor=new ServerHuman(new ServerHumanDefinition("flags","flags","Flags",100.5f,200.5f,0,zombie.iso.IsoDirections.S,false,"Tourist",profile.id()),(short)1234,new zombie.iso.IsoCell());
  check(!actor.player.god&&actor.player.ghost&&actor.player.invisible,"authored flags applied independently at activation");actor.dispose();
 }
 static void adapter(){
  var spec=new zombie.apocalipse.humans.walking.WalkingPatternSpec("background","area-wander",16,List.of(),new zombie.apocalipse.humans.walking.WalkingPatternSpec.Area(100,200,112,204));
  var profile=new HumanProfile("retreat-adapter",1,"modules",Map.of(),"","none",false,false,List.of(new HumanModuleBinding("survival",0,100,Map.of())),8,100,true,List.of(spec));
  ServerHumanProfiles.registry().register(profile);
  var cell=new zombie.iso.IsoCell();cell.strict=true;
  for(int x=100;x<=112;x++)for(int y=200;y<=204;y++)cell.tiles.put(x+","+y+",0",new zombie.iso.IsoGridSquare(x,y,0));
  var actor=new ServerHuman(new ServerHumanDefinition("escape","escape","Escape",104.5f,202.5f,0,zombie.iso.IsoDirections.S,false,"Tourist",profile.id()),(short)1235,cell);
  var c=new Contact(new Identity(Kind.ZOMBIE,(short)1,1,1),102.5f,202.5f,0,4,0,0,true);
  check(actor.actuator.survival().escape(List.of(c),profile.survival(),0),"production adapter starts a scoped physical retreat");
  HumanActuator.MotionStatus result=HumanActuator.MotionStatus.RUNNING;
  for(long now=100_000_000L;now<=20*SECOND&&result==HumanActuator.MotionStatus.RUNNING;now+=100_000_000L){
   result=actor.actuator.updateMovement(now);
   check(actor.player.x>=100&&actor.player.x<113&&actor.player.y>=200&&actor.player.y<205,"emergency movement remains inside assigned area");
  }
  check(result==HumanActuator.MotionStatus.SUCCEEDED&&actor.player.x>106,"adapter progresses the actual shared movement engine away from threat");
  actor.dispose();
 }
 public static void main(String[]args){lifecycle();arbitration();routes();flags();adapter();System.out.println("Passed "+checks+" survival, routing, arbitration and cheat activation assertions.");}
}
'''

def main():
    with tempfile.TemporaryDirectory(prefix='apocbr-survival-tests-') as directory:
        work=Path(directory);fixture=dict(FILES);sources=[]
        fixture['zombie/apocalipse/humans/SurvivalTest.java']=HARNESS
        for name,content in fixture.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
        sources.extend(sorted((ROOT/'42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
        args=work/'sources.txt';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources))
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',str(work/'classes'),'zombie.apocalipse.humans.SurvivalTest'],check=True)

if __name__=='__main__':main()
