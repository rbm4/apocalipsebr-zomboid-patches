"""42.22.2 production furniture discovery/identity/leases and traversal regressions.

The world and unrelated brain types are explicit doubles. No native furniture
classification, geometry, packets, pathfinding or multiplayer is exercised.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "42.22.2/src/zombie/apocalipse/humans"

HARNESS = r'''
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import zombie.apocalipse.humans.interactions.*;
import zombie.apocalipse.humans.furniture.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.traversal.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import static zombie.apocalipse.humans.furniture.FurnitureAffordances.*;
public class FurnitureTest {
 static int checks;
 static final long SEC=1_000_000_000L;
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static void rejects(Runnable f){try{f.run();throw new AssertionError("accepted invalid input");}catch(IllegalArgumentException|NullPointerException expected){checks++;}}
 static Token token(long generation,int x,int y,String slot){return new Token(generation,x,y,0,slot);}
 static Candidate seat(long generation,int x,int y,long at,boolean busy){return new Candidate(token(generation,x,y,"S"),Kind.SIT,
   List.of(new Approach(new Position(x+.5f,y+1.5f,0),"Front"),new Approach(new Position(x+1.5f,y+.5f,0),"Left"),new Approach(new Position(x-.5f,y+.5f,0),"Right")),"S",busy,at);}
 static void leases() throws Exception {
  var r=new InteractionReservations<String>(4);var owner=new Object();
  var first=r.acquire(owner,List.of("chair","arrival"),0,SEC);
  check(first!=null&&r.owns(first,0),"atomic resource acquisition");
  check(r.acquire(new Object(),List.of("other","arrival"),0,SEC)==null&&r.resources()==2,"collision does not partially acquire");
  check(r.renew(first,SEC/2,SEC)&&r.owns(first,SEC),"long activity renews lease");
  check(!r.renew(first,2*SEC,SEC),"expired lease cannot renew");
  var replacement=r.acquire(new Object(),List.of("chair"),2*SEC,SEC);
  r.release(first);check(r.owns(replacement,2*SEC),"late release cannot erase replacement");
  var other=r.acquire(owner,List.of("x","y","z"),2*SEC,SEC);
  check(other!=null&&r.acquire(owner,List.of("capacity"),2*SEC,SEC)==null,"capacity bounded");
  r.releaseOwner(owner);check(r.resources()==1,"owner cleanup releases all owned resources");
  check(!r.owns(first,2*SEC),"old receipt is permanently invalid");
  rejects(()->r.acquire(owner,List.of("a","a"),0,SEC));rejects(()->r.acquire(owner,List.of(),0,SEC));
  rejects(()->r.acquire(owner,List.of("a"),0,0));rejects(()->new InteractionReservations<>(4097));
  var wrong=new AtomicBoolean();var t=new Thread(()->{try{r.release(replacement);}catch(IllegalStateException expected){wrong.set(true);}});t.start();t.join();
  check(wrong.get()&&r.owns(replacement,2*SEC),"unrelated thread rejected before mutation");
  var constructed=new java.util.concurrent.atomic.AtomicReference<CrossingReservations>();
  var initializer=new Thread(()->constructed.set(new CrossingReservations()));initializer.start();initializer.join();
  check(constructed.get().acquire(owner,new TraversalEdge(new Tile(1,1),new Tile(2,1),0,TraversalEdge.Kind.WINDOW,new Object()),0)!=null,"factory composition thread need not be runtime authority");
  var furniture=new FurnitureReservations();var a=token(1,10,10,"S");var b=token(1,10,10,"N");
  var held=furniture.acquire(owner,a,0);
  check(held!=null&&furniture.acquire(new Object(),b,0)==null,"different native slots exclude whole object initially");
  check(furniture.renew(held,4*SEC)&&furniture.owns(held,8*SEC),"seat remains reserved through long rest");
  furniture.releaseOwner(owner);check(!furniture.owns(held,8*SEC),"incarnation cleanup");
  check(furniture.acquire(new Object(),token(2,10,10,"S"),0)!=null,"replacement object has independent generation");
 }
 static void identities() throws Exception {
  var ids=new FurnitureBindings<Object>(2);var chair=new Object();
  var a=ids.bind(chair,"sprite-a",10,10,0,"S");
  check(a.equals(ids.bind(chair,"sprite-a",10,10,0,"S")),"stable native binding");
  check(ids.bind(chair,"sprite-a",10,10,0,"N").generation()==a.generation(),"slots share object generation");
  var b=ids.bind(chair,"sprite-b",10,10,0,"S");check(ids.resolve(a)==null&&ids.resolve(b)==chair,"sprite mutation invalidates old generation");
  var c=ids.bind(chair,"sprite-b",11,10,0,"S");check(ids.resolve(b)==null&&ids.resolve(c)==chair,"move invalidates generation");
  ids.remove(chair);var d=ids.bind(chair,"sprite-b",11,10,0,"S");
  check(ids.resolve(c)==null&&d.generation()>c.generation(),"remove/re-add and pooled object reuse cannot revive token");
  var replacement=new Object();var e=ids.bind(replacement,"sprite-b",11,10,0,"S");
  check(e.generation()!=d.generation()&&ids.resolve(e)==replacement,"same coordinates and sprite do not alias objects");
  ids.bind(new Object(),"sprite-c",12,10,0,"S");check(ids.size()==2&&ids.resolve(d)==null,"binding retention bounded");
  ids.clear();check(ids.resolve(e)==null&&ids.size()==0,"world cleanup");
  check(ids.bind(replacement,"sprite-b",11,10,0,"S").generation()>e.generation(),"clear does not recycle generation");
  rejects(()->ids.bind(chair,"bad",-1,10,0,"S"));check(ids.size()==1,"invalid bind leaves accepted state unchanged");
 }
 static class World implements FurnitureScanner.World {
  int count=1,calls,revision,loaded=1;boolean seats=true;final Set<String> visited=new HashSet<>();
  public int objectCount(int x,int y,int z){calls++;visited.add(x+","+y);return loaded==0?-1:count;}
  public long revision(int x,int y,int z){return revision;}
  public Candidate inspect(int x,int y,int z,int index,Set<Kind> kinds,long now){
   if(index>=count)throw new AssertionError("cursor outside object list");
   return seats?seat(((long)x*10000+y)*1000+index+1,x,y,now,false):null;
  }
 }
 static FurnitureScanBudget budget(){var b=new FurnitureScanBudget();b.reset();return b;}
 static void scans(){
  var world=new World();var none=new FurnitureScanner(FurnitureDemand.NONE);
  check(none.update(10,10,0,0,world,budget())==Frame.EMPTY&&world.calls==0,"no-demand zero world work");
  var small=new FurnitureScanner(new FurnitureDemand(Set.of(Kind.SIT),1,1000));
  var frame=small.update(10,10,0,0,world,budget());
  check(frame.coverage()==Coverage.COMPLETE&&frame.work().squares()==5&&frame.work().objects()==5&&frame.candidates().size()==5,"complete bounded neighborhood");
  int calls=world.calls;check(small.update(10,10,0,SEC/2,world,budget())==frame&&world.calls==calls,"cadence skips world");
  var moved=small.update(11,10,0,SEC/2,world,budget());check(moved!=frame&&world.calls>calls,"moving origin immediately invalidates cached frame");
  world.loaded=0;frame=small.update(11,10,0,2*SEC,world,budget());check(frame.coverage()==Coverage.UNAVAILABLE&&frame.candidates().isEmpty(),"unloaded is unavailable, not empty complete");
  world.loaded=1;world.seats=false;world.visited.clear();
  var large=new FurnitureScanner(new FurnitureDemand(Set.of(Kind.SIT),12,1000));
  int passes=0;do{frame=large.update(20,20,0,passes++*SEC/10,world,budget());check(frame.work().squares()<=64&&frame.work().objects()<=128,"per-observer limits");}while(frame.coverage()!=Coverage.COMPLETE&&passes<20);
  check(passes==7&&world.visited.size()==441,"cursor visits entire circle fairly");
  var shared=budget();var observers=new ArrayList<FurnitureScanner>();for(int i=0;i<5;i++)observers.add(new FurnitureScanner(new FurnitureDemand(Set.of(Kind.SIT),12,1000)));
  int squares=0,objects=0;for(var scanner:observers){frame=scanner.update(20,20,0,0,world,shared);squares+=frame.work().squares();objects+=frame.work().objects();}
  check(squares==256&&objects==256,"global pass ceiling shared across observers");
  check(observers.getLast().snapshot().coverage()==Coverage.UNAVAILABLE,"budget denial is not complete empty");
  // Dense square pauses within its object list. Same-size replacement changes revision.
  world.count=200;world.seats=false;var dense=new FurnitureScanner(new FurnitureDemand(Set.of(Kind.SIT),1,1000));
  frame=dense.update(20,20,0,0,world,budget());check(frame.work().objects()==128&&frame.work().squares()==1,"dense square consumes at most object budget");
  world.revision++;world.count=1;world.seats=true;
  frame=dense.update(20,20,0,SEC/10,world,budget());check(frame.candidates().stream().anyMatch(c->c.token().x()==20&&c.token().y()==20),"changed list restarts square cursor");
  world.count=200;var capped=new FurnitureScanner(new FurnitureDemand(Set.of(Kind.SIT),1,1000));
  frame=capped.update(20,20,0,0,world,budget());check(frame.truncated()&&frame.candidates().size()==32,"candidate output bounded and explicit truncation");
  frame=capped.update(20,20,0,3*SEC,world,budget());check(frame.candidates().stream().allMatch(c->c.fresh(3*SEC)),"old sweep candidates never gain artificial freshness");
  rejects(()->new FurnitureDemand(Set.of(Kind.SIT),13,1000));rejects(()->new FurnitureDemand(Set.of(Kind.SIT),1,100));
  var input=new ArrayList<>(frame.candidates());var detached=new Frame(true,0,input,Coverage.PARTIAL,false,new Work(0,0));input.clear();
  check(!detached.candidates().isEmpty(),"observation defensive copy");
 }
 static void selection(){
  var near=seat(1,10,10,0,true);var far=seat(2,12,10,0,false);
  var frame=new Frame(true,0,List.of(near,far),Coverage.COMPLETE,false,new Work(2,2));
  var choice=FurnitureSelector.choose(frame,Kind.SIT,new Position(10,10,0),0,Set.of());
  check(choice.candidate()==far&&choice.approach().side().equals("Right"),"skip occupied chair and rank all side approaches");
  check(FurnitureSelector.choose(frame,Kind.SIT,new Position(10,10,0),0,Set.of(far.token()))==null,"refused target yields to routine");
  check(FurnitureSelector.choose(frame,Kind.SIT,new Position(10,10,0),3*SEC,Set.of())==null,"stale frame rejected");
  var refreshed=new Frame(true,3*SEC,List.of(far),Coverage.PARTIAL,false,new Work(0,0));
  check(FurnitureSelector.choose(refreshed,Kind.SIT,new Position(10,10,0),3*SEC,Set.of())==null,"new frame timestamp does not refresh stale candidate");
 }
 static class Module implements HumanModule {
  final FurnitureDemand demand;int starts,interrupts;boolean eligible=true;
  Module(FurnitureDemand demand){this.demand=demand;}
  public FurnitureDemand furnitureDemand(){return demand;}
  public boolean eligible(HumanObservation o){return eligible;}public boolean available(){return true;}
  public void update(HumanObservation o,HumanActuator a){starts++;}
  public void interrupt(long now,HumanActuator a){interrupts++;}public void suspend(long now,HumanActuator a){}public void resume(HumanObservation.Identity i,long now,HumanActuator a){}public void stop(long now,HumanActuator a){}
 }
 static void composition(){
  var walk=new Module(FurnitureDemand.NONE);var rest=new Module(new FurnitureDemand(Set.of(Kind.SIT),8,2000));
  var runtime=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("walk",1000,100,walk),new HumanModuleRuntime.Entry("rest",500,100,rest)));
  check(runtime.furnitureDemand().equals(rest.demand),"composition derives demand from module contract");
  var actuator=new HumanActuator(){};runtime.update(new HumanObservation(0,List.of()),actuator);
  check(rest.starts==1&&walk.starts==0,"eligible rest outranks walking");
  rest.eligible=false;runtime.update(new HumanObservation(SEC,List.of()),actuator);check(rest.interrupts==1&&walk.starts==1,"rest backoff yields control");
  var other=new HumanModuleRuntime(List.of(new HumanModuleRuntime.Entry("walk",1000,100,new Module(FurnitureDemand.NONE))));
  check(other.furnitureDemand().equals(FurnitureDemand.NONE)&&rest.starts==1,"unrelated actor has no demand or shared execution state");
  check(rest.demand.merge(new FurnitureDemand(Set.of(Kind.SIT),12,1000)).equals(new FurnitureDemand(Set.of(Kind.SIT),12,1000)),"aggregate union/radius/cadence");
  var old=new HumanObservation(0,List.of(),false,zombie.apocalipse.humans.state.HumanState.INITIAL,HumanSenses.Frame.EMPTY,HumanTerritory.UNAVAILABLE,HumanSenses.Frame.EMPTY,HumanObservation.Support.NONE);
  check(old.furniture()==Frame.EMPTY,"old eight-argument observation constructor preserves empty sensor default");
  var furniture=new Frame(true,0,List.of(seat(1,10,10,0,false)),Coverage.COMPLETE,false,new Work(1,1));
  var combined=new HumanObservation(0,List.of(),false,old.state(),old.senses(),old.territory(),old.awareness(),old.support(),furniture);
  check(combined.furniture()==furniture&&combined.senses()==HumanSenses.Frame.EMPTY,"furniture domain composes independently of living contacts");
 }
 static class Port implements TraversalExecution.Port {
  TraversalEdge edge;int begins,lands,ends;boolean throwing;
  Port(TraversalEdge edge){this.edge=edge;}
  public TraversalEdge inspect(TraversalEdge e){return edge;}
  public TraversalExecution.Landing landing(TraversalEdge e){return TraversalExecution.Landing.CLEAR;}
  public boolean prepare(TraversalEdge e){return true;}
  public void begin(TraversalEdge e){begins++;if(throwing)throw new IllegalStateException("injected begin failure");}
  public void land(TraversalEdge e){lands++;}public void end(TraversalEdge e,boolean landed){ends++;}
 }
 static void traversal(){
  var reservations=new CrossingReservations();var a=new Tile(1,1);var b=new Tile(2,1);var c=new Tile(2,2);var barrier=new Object();
  var edge=new TraversalEdge(a,b,0,TraversalEdge.Kind.WINDOW,barrier);
  var claim=reservations.acquire(new Object(),edge,0);check(claim!=null,"legacy crossing acquires shared primitive");
  check(reservations.acquire(new Object(),new TraversalEdge(b,a,0,TraversalEdge.Kind.WINDOW,barrier),0)==null,"opposite crossing excluded");
  check(reservations.acquire(new Object(),new TraversalEdge(c,b,0,TraversalEdge.Kind.FENCE,new Object()),0)==null,"shared landing excluded");
  check(reservations.acquire(new Object(),new TraversalEdge(a,b,1,TraversalEdge.Kind.WINDOW,barrier),0)!=null,"different floors independent");
  check(reservations.owns(claim,9*SEC-1)&&!reservations.owns(claim,9*SEC),"original nine-second expiry exact");
  var replacement=reservations.acquire(new Object(),edge,9*SEC);reservations.release(claim);check(reservations.owns(replacement,9*SEC),"late crossing release safe");reservations.release(replacement);
  var port=new Port(edge);var execution=new TraversalExecution(new Object(),reservations,port);execution.start(edge,10*SEC);
  check(execution.update(10*SEC)==TraversalExecution.Result.RUNNING&&port.begins==1,"native begin once");
  execution.update(12*SEC);check(port.lands==1,"single authoritative landing commit");
  check(execution.update(13*SEC)==TraversalExecution.Result.SUCCEEDED&&port.ends==1,"normal crossing completes with shared leases");
  execution.cancel();check(port.ends==1,"terminal cancellation idempotent");
  port.throwing=true;execution.start(edge,14*SEC);try{execution.update(14*SEC);throw new AssertionError("missing exception");}catch(IllegalStateException expected){checks++;}
  check(!execution.active()&&port.ends==2&&reservations.acquire(new Object(),edge,14*SEC)!=null,"exception releases crossing and landing");
 }
 static void differential(){
  var actual=new CrossingReservations();var original=new OriginalCrossingReservations();
  var receipts=new ArrayList<CrossingReservations.Lease>();var baseline=new ArrayList<OriginalCrossingReservations.Lease>();
  var random=new Random(712611);long now=0;
  for(int step=0;step<4000;step++){
   now+=random.nextInt(600)*1_000_000L;
   int operation=random.nextInt(3);
   if(operation==0||receipts.isEmpty()){
    int x=random.nextInt(12),y=random.nextInt(6),z=random.nextInt(2);var from=new Tile(x,y);var to=new Tile(x+1,y);
    if(random.nextBoolean()){var swap=from;from=to;to=swap;}
    var edge=new TraversalEdge(from,to,z,TraversalEdge.Kind.WINDOW,new Object());var owner=new Object();
    var a=actual.acquire(owner,edge,now);var b=original.acquire(owner,edge,now);
    check((a==null)==(b==null),"differential acquire step "+step);
    if(a!=null){check(a.token()==b.token()&&a.until()==b.until()&&a.owner()==b.owner(),"differential receipt");receipts.add(a);baseline.add(b);}
   }else{
    int index=random.nextInt(receipts.size());var a=receipts.get(index);var b=baseline.get(index);
    if(operation==1){actual.release(a);original.release(b);}
    check(actual.owns(a,now)==original.owns(b,now),"differential ownership step "+step);
   }
  }
 }
 public static void main(String[] args)throws Exception{leases();identities();scans();selection();composition();traversal();differential();System.out.println("PASS: "+checks+" production furniture, module-demand and traversal assertions");}
}
'''


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-furniture-") as directory:
        work = Path(directory)
        fixture = {
            "FurnitureTest.java": HARNESS,
            "zombie/apocalipse/humans/brain/HumanActuator.java": "package zombie.apocalipse.humans.brain; public interface HumanActuator {default void moduleChanged(String a,String b){}}",
            "zombie/apocalipse/humans/profiles/HumanProfile.java": "package zombie.apocalipse.humans.profiles; public final class HumanProfile {public static void identifier(String id){if(id==null||id.isBlank())throw new IllegalArgumentException();}}",
        }
        # Exact previous-build implementation, not a model of the refactored algorithm.
        previous = (ROOT / "42.22.1/src/zombie/apocalipse/humans/traversal/CrossingReservations.java").read_text(encoding="utf-8")
        fixture["zombie/apocalipse/humans/traversal/OriginalCrossingReservations.java"] = previous.replace("public final class CrossingReservations", "public final class OriginalCrossingReservations")
        sources = []
        for name, content in fixture.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        for package in ("furniture", "interactions"):
            sources.extend(sorted((SOURCE / package).glob("*.java")))
        for name in ("brain/HumanBehavior", "brain/HumanModule", "brain/HumanModuleRuntime", "brain/HumanObservation", "brain/HumanSenses", "brain/HumanTerritory", "state/HumanState", "movement/MovementGrid", "traversal/CrossingReservations", "traversal/TraversalEdge", "traversal/TraversalExecution"):
            sources.append(SOURCE / (name + ".java"))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources), encoding="utf-8")
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-encoding", "UTF-8", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "FurnitureTest"], check=True)


if __name__ == "__main__":
    main()
