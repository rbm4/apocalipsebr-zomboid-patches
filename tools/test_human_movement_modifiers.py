"""Production movement-condition resolver and native movement/transport integration."""
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
import zombie.apocalipse.humans.state.*;
import zombie.apocalipse.humans.movement.MovementModifiers;
import zombie.apocalipse.humans.movement.MovementModifiers.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.characters.*;
import zombie.core.raknet.UdpConnection;
import zombie.iso.*;
import zombie.network.*;
import zombie.network.packets.*;
import zombie.network.packets.character.*;
public class MovementModifiersTest {
 static int checks;static final long STEP=100_000_000L;
 static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static void near(float a,float b,String m){check(Math.abs(a-b)<.0001,m+" actual="+a+" expected="+b);}
 static void invalid(Runnable r){try{r.run();throw new AssertionError("invalid input accepted");}catch(IllegalArgumentException expected){checks++;}}
 static Conditions condition(Health h,Context c,Effects e){return new Conditions(h,c,e);}
 static Conditions health(Health h){return condition(h,Context.NORMAL,Effects.NONE);}
 static Context context(boolean sneak,boolean sweep,Posture posture){return new Context(sneak,sweep,posture,1,1,1,1);}
 static Effects effects(boolean flight,boolean hit,boolean sprint,BurstAdmission admission){return new Effects(new Window(flight,5*STEP),Pace.SPRINT,new Window(hit,5*STEP),new Window(sprint,5*STEP),admission);}
 static final BurstAdmission ADMITTED=new BurstAdmission(true,true,true);
 static Resolved resolve(Pace p,Conditions c){return MovementModifiers.resolve(p,c,Settings.DEFAULT,STEP,0);}
 static Settings settings(boolean run,boolean sprint,BurstStyle style,Rhythm rhythm){return new Settings(run,sprint,style,Speeds.DEFAULT,rhythm,Settings.DEFAULT.jogMinimum(),Settings.DEFAULT.sprintMinimum());}
 static long injuryMessages(){return INetworkPacket.messages.stream().filter(m->m.type()==PacketTypes.PacketType.PlayerInjuries).count();}
 static PlayerPacketReliable packet(){return PlayerPacketReliable.sent.getLast();}
 static boolean flag(PlayerPacketReliable p,int bit){return (p.booleanVariables&(1<<bit))!=0;}
 public static void main(String[] ignored)throws Exception {
  Resolved normal=resolve(Pace.WALK,Conditions.NORMAL);
  near(normal.speed(),2,"normal walking preserved");near(normal.animation().walkSpeed(),1,"normal animation preserved");
  check(resolve(Pace.RUN,Conditions.NORMAL).pace()==Pace.RUN,"ordinary running supported");
  near(resolve(Pace.RUN,Conditions.NORMAL).speed(),4,"run physical speed");
  check(resolve(Pace.SPRINT,Conditions.NORMAL).pace()==Pace.RUN,"sprint requires an admitted lease");
  Conditions sprint=condition(Health.HEALTHY,Context.NORMAL,effects(false,false,true,ADMITTED));
  near(resolve(Pace.SPRINT,sprint).speed(),6,"admitted sprint physical speed");
  check(resolve(Pace.RUN,sprint).pace()==Pace.SPRINT,"sprint burst can upgrade a run request");
  check(resolve(Pace.WALK,sprint).pace()==Pace.WALK,"burst does not commandeer walking intent");
  check(MovementModifiers.resolve(Pace.SPRINT,sprint,Settings.DEFAULT,5*STEP,0).pace()==Pace.RUN,"burst expires at exact deadline");
  for(BurstAdmission a:List.of(new BurstAdmission(false,true,true),new BurstAdmission(true,false,true),new BurstAdmission(true,true,false))){
   check(resolve(Pace.SPRINT,condition(Health.HEALTHY,Context.NORMAL,effects(false,false,true,a))).pace()==Pace.RUN,"every burst admission gate required");
  }
  check(MovementModifiers.resolve(Pace.SPRINT,sprint,settings(true,false,BurstStyle.SPRINTER,Rhythm.CONTINUOUS),STEP,0).pace()==Pace.RUN,"sprinting disabled independently");
  check(MovementModifiers.resolve(Pace.SPRINT,sprint,settings(true,true,BurstStyle.JOGGER,Rhythm.CONTINUOUS),STEP,0).pace()==Pace.RUN,"jogger style downgrades sprint");
  Conditions wounded=health(new Health(1,1,-.6f,false,false,false));
  for(Pace p:Pace.values()){
   Resolved r=resolve(p,wounded);check(r.pace()==Pace.WALK,"leg wound caps every request");near(r.speed(),1.32f,"limp speed scale");near(r.animation().walkInjury(),-.6f,"signed injury preserved");
   Conditions severe=condition(wounded.health(),Context.NORMAL,effects(true,true,true,ADMITTED));
   check(resolve(p,severe).pace()==Pace.WALK,"flight/hit/sprint cannot override leg wound");
   check(MovementModifiers.resolve(p,severe,settings(false,true,BurstStyle.SPRINTER,Rhythm.CONTINUOUS),STEP,0).pace()==Pace.WALK,"global run permission overrides effects");
  }
  for(Health h:List.of(new Health(1,1,0,true,false,false),new Health(1,1,0,false,true,false),new Health(1,1,0,false,false,true),new Health(0,1,0,false,false,false))){
   for(Pace p:Pace.values())check(resolve(p,condition(h,Context.NORMAL,effects(true,true,true,ADMITTED))).speed()==0,"physical stop dominates overrides");
  }
  Conditions low=health(new Health(1,0,0,false,false,false));
  check(resolve(Pace.RUN,low).pace()==Pace.WALK,"exhaustion caps ordinary running");
  check(resolve(Pace.SPRINT,condition(low.health(),Context.NORMAL,sprint.effects())).pace()==Pace.WALK,"exhaustion prevents sprint");
  float jogging=(Settings.DEFAULT.jogMinimum()+Settings.DEFAULT.sprintMinimum())/2;
  Conditions alert=condition(new Health(1,jogging,0,false,false,false),context(false,false,Posture.ALERT),Effects.NONE);
  check(resolve(Pace.RUN,alert).pace()==Pace.RUN,"danger allows jogging below sprint threshold");
  check(resolve(Pace.WALK,alert).pace()==Pace.WALK,"danger is a modifier rather than a new movement intent");
  check(resolve(Pace.RUN,condition(low.health(),alert.context(),Effects.NONE)).pace()==Pace.WALK,"danger cannot bypass exhaustion");
  Conditions flight=condition(Health.HEALTHY,Context.NORMAL,effects(true,false,false,BurstAdmission.REFUSED));
  check(resolve(Pace.WALK,flight).pace()==Pace.SPRINT,"explicit flight lease can upgrade walking");
  check(resolve(Pace.WALK,condition(low.health(),Context.NORMAL,flight.effects())).pace()==Pace.WALK,"flight respects endurance");
  check(MovementModifiers.resolve(Pace.WALK,flight,settings(false,true,BurstStyle.SPRINTER,Rhythm.CONTINUOUS),STEP,0).pace()==Pace.WALK,"flight respects run permission");
  Conditions hit=condition(Health.HEALTHY,Context.NORMAL,effects(false,true,false,BurstAdmission.REFUSED));
  check(resolve(Pace.WALK,hit).pace()==Pace.RUN,"post-hit escape lease enables run");
  check(resolve(Pace.GUARD,hit).pace()==Pace.WALK,"guard suppresses post-hit escape override");
  Conditions sweep=condition(Health.HEALTHY,context(false,true,Posture.RELAXED),Effects.NONE);
  check(resolve(Pace.RUN,sweep).pace()==Pace.WALK,"slow sweeping caps ordinary run");near(resolve(Pace.RUN,sweep).speed(),1.44f,"sweep tempo changes displacement");
  check(resolve(Pace.RUN,condition(Health.HEALTHY,sweep.context(),hit.effects())).pace()==Pace.WALK,"sweep precedes hit escape");
  check(resolve(Pace.WALK,condition(Health.HEALTHY,sweep.context(),flight.effects())).pace()==Pace.SPRINT,"flight precedes sweep");
  Conditions sneak=condition(Health.HEALTHY,context(true,false,Posture.RELAXED),sprint.effects());
  check(resolve(Pace.SPRINT,sneak).pace()==Pace.RUN,"stealth caps sprint");near(resolve(Pace.WALK,sneak).speed(),1.7f,"sneak walk factor");
  check(!resolve(Pace.WALK,condition(wounded.health(),sneak.context(),Effects.NONE)).animation().sneaking(),"limp takes precedence over crouch presentation");
  near(resolve(Pace.WALK,health(new Health(.5f,1,0,false,false,false))).speed(),2*.78f/1.02f,"low health walk ratio");
  near(resolve(Pace.RUN,health(new Health(.5f,1,0,false,false,false))).speed(),4*.60f/.78f,"low health run ratio");
  near(resolve(Pace.SPRINT,condition(new Health(.5f,1,0,false,false,false),Context.NORMAL,sprint.effects())).speed(),6*.66f/.84f,"low health sprint ratio");
  Context factors=new Context(false,false,Posture.RELAXED,.75f,.8f,.9f,.95f);
  near(resolve(Pace.WALK,condition(Health.HEALTHY,factors,Effects.NONE)).speed(),2*.75f*.8f*.9f*.95f,"terrain cold equipment and stride factors compose once");
  Settings periodic=settings(true,true,BurstStyle.SPRINTER,Rhythm.INTERMITTENT);
  check(MovementModifiers.resolve(Pace.RUN,Conditions.NORMAL,periodic,849_999_999,0).pace()==Pace.RUN,"periodic run active just before boundary");
  check(MovementModifiers.resolve(Pace.RUN,Conditions.NORMAL,periodic,850_000_000,0).pace()==Pace.WALK,"periodic run ends exactly at boundary");
  check(MovementModifiers.resolve(Pace.RUN,Conditions.NORMAL,periodic,14_000_000_000L,0).pace()==Pace.RUN,"periodic run restarts without replaying missed cycles");
  check(new Window(true,-1).active(-2)&&!new Window(true,-1).active(-1),"negative monotonic timestamps supported");
  Conditions boosts=condition(Health.HEALTHY,new Context(false,false,Posture.RELAXED,1.5f,1.5f,1.5f,1.5f),sprint.effects());
  Resolved capped=resolve(Pace.SPRINT,boosts);near(capped.speed(),8,"composed speed bounded");near(capped.animation().runSpeed(),8/6f,"animation uses same physical cap");
  invalid(()->new Health(Float.NaN,1,0,false,false,false));invalid(()->new Health(1,1.1f,0,false,false,false));
  invalid(()->new Health(1,1,Float.POSITIVE_INFINITY,false,false,false));invalid(()->new Context(false,false,Posture.RELAXED,0,1,1,1));
  invalid(()->new Speeds(2,1,3));invalid(()->new Speeds(2,4,Float.NaN));invalid(()->new Rhythm(2,1));
  invalid(()->new Settings(true,true,BurstStyle.SPRINTER,Speeds.DEFAULT,Rhythm.CONTINUOUS,.8f,.2f));
  invalid(()->new Effects(Window.NONE,Pace.GUARD,Window.NONE,Window.NONE,BurstAdmission.REFUSED));

  // Exercise the actual movement engine, factory cadence and native transport.
  var registry=ServerHumanProfiles.registry();
  registry.registerBehavior("movement-test",p->{},p->new HumanBehavior(){
   public boolean needsPerception(){return false;}public boolean available(){return true;}
   public void update(HumanObservation o,HumanActuator a){a.updateMovement(o.nowNanos());}
   public void suspend(long n,HumanActuator a){a.stopMoving();}public void resume(HumanObservation.Identity v,long n,HumanActuator a){}
   public void stop(long n,HumanActuator a){a.stopMoving();}
  });
  registry.register(new HumanProfile("movement-test",1,"movement-test",Map.of(),"","none",false,false));
  IsoWorld.instance.currentCell=new IsoCell();var viewer=new UdpConnection();GameServer.udpEngine.connections.add(viewer);
  var ctor=ServerHumanFactory.class.getDeclaredConstructor();ctor.setAccessible(true);var factory=ctor.newInstance();factory.tick(0);
  factory.spawn(new ServerHumanDefinition("test","test","test",100.5f,200.5f,0,IsoDirections.E,false,"Tourist","movement-test"));
  var field=ServerHumanFactory.class.getDeclaredField("humans");field.setAccessible(true);var npc=(ServerHuman)((Map<?,?>)field.get(factory)).get("test");
  List<Tile> route=List.of(new Tile(100,200),new Tile(101,200),new Tile(102,200),new Tile(103,200),new Tile(104,200),new Tile(105,200));
  npc.movement.start(route,0);factory.tick(STEP);
  check(npc.movement.snapshot().gait()==ServerHumanMovement.Gait.WALK&&flag(packet(),8),"physical walking flag remains native");
  int before=PlayerPacketReliable.sent.size();long injuryBefore=injuryMessages();float x=npc.player.x;float direction=npc.player.angle;
  npc.movement.requestPace(Pace.RUN);factory.tick(2*STEP);
  near(npc.player.x-x,.4f,"run integrates resolved speed");check(npc.player.angle==direction,"pace transition kept same heading");
  check(PlayerPacketReliable.sent.size()==before+1&&flag(packet(),3)&&!flag(packet(),4),"pace change publishes before heartbeat even without turn/start/stop");
  near(packet().prediction.speed,4,"running prediction speed matches integration");
  check(injuryMessages()==injuryBefore,"unchanged animation modifiers do not generate injury packet spam");
  npc.movement.conditions(sprint);npc.movement.requestPace(Pace.SPRINT);factory.tick(3*STEP);
  check(flag(packet(),4)&&!flag(packet(),3)&&npc.player.sprinting,"sprint native flag and actor agree");
  near(packet().prediction.speed,6,"sprint prediction uses same speed");
  npc.movement.conditions(Conditions.NORMAL);npc.state.dispatch(new HumanMutation.Damage(0,-.6f,0,0));before=PlayerPacketReliable.sent.size();injuryBefore=injuryMessages();x=npc.player.x;
  factory.tick(4*STEP);
  near(npc.player.x-x,.132f,"leg injury updates physical movement without replanning");
  check(PlayerPacketReliable.sent.size()==before+1&&!flag(packet(),3)&&!flag(packet(),4),"wound downgrade immediately clears stale run/sprint flags");
  near(npc.player.getVariableFloat("WalkInjury",0),-.6f,"native limp variable applied");near(npc.player.getNetworkCharacterAI().walkSpeed,.66f,"remote animation walk cache updated");
  check(injuryMessages()==injuryBefore+1,"animation change sends one native injuries update");
  ServerHumanTransport.idle(npc,viewer);check(injuryMessages()==injuryBefore+1,"same viewer does not resend unchanged modifiers");
  var late=new UdpConnection();ServerHumanTransport.announce(npc,late);ServerHumanTransport.idle(npc,late);
  check(injuryMessages()==injuryBefore+2,"late viewer receives current injury modifiers");
  npc.state.dispatch(new HumanMutation.Mobility(false,true));factory.tick(5*STEP);x=npc.player.x;
  check(!npc.movement.moving()&&packet().prediction.distance==0&&!flag(packet(),8),"downed state clears prediction and movement flags");
  factory.tick(6*STEP);near(npc.player.x,x,"downed actor holds route without moving");
  npc.movement.conditions(Conditions.NORMAL);npc.state.dispatch(new HumanMutation.Mobility(false,false));npc.state.dispatch(new HumanMutation.Treat(1,1,1));factory.tick(7*STEP);
  check(npc.player.x>x&&npc.movement.moving(),"cleared condition resumes owned route");
  // Exact lease expiry without changing position heading or waiting for a heartbeat.
  npc.movement.conditions(new Conditions(Health.HEALTHY,Context.NORMAL,new Effects(Window.NONE,Pace.RUN,Window.NONE,new Window(true,9*STEP),ADMITTED)));
  factory.tick(8*STEP);check(npc.movement.snapshot().gait()==ServerHumanMovement.Gait.SPRINT,"live admitted burst starts");
  before=PlayerPacketReliable.sent.size();factory.tick(9*STEP);
  check(npc.movement.snapshot().gait()==ServerHumanMovement.Gait.RUN&&PlayerPacketReliable.sent.size()==before+1,"burst expiry republishes immediately before heartbeat");
  factory.tick(10*STEP);before=PlayerPacketReliable.sent.size();x=npc.player.x;
  npc.movement.conditions(condition(Health.HEALTHY,new Context(false,false,Posture.RELAXED,.75f,1,1,1),Effects.NONE));
  factory.tick(11*STEP);
  near(npc.player.x-x,.3f,"same gait terrain update changes integration speed");
  check(npc.movement.snapshot().gait()==ServerHumanMovement.Gait.RUN&&PlayerPacketReliable.sent.size()==before+1,"speed modifier alone republishes before heartbeat");
  npc.movement.stop();check(!npc.player.running&&!npc.player.sprinting&&!npc.movement.moving(),"stop clears native pace flags");
  npc.state.dispatch(new HumanMutation.Damage(0,-.6f,0,0));factory.tick(12*STEP);
  check(npc.movement.snapshot().gait()==ServerHumanMovement.Gait.IDLE&&npc.player.getVariableFloat("WalkInjury",0)==-.6f,"idle condition presentation updates without moving");
  factory.remove("test");check(npc.movementPresentation.isEmpty(),"despawn clears per-viewer presentation caches");
  System.out.println("Passed "+checks+" movement modifier, precedence, native presentation and cadence assertions.");
 }
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-movement-modifiers-") as directory:
        work = Path(directory)
        fixture = dict(FILES)
        fixture["zombie/apocalipse/humans/MovementModifiersTest.java"] = HARNESS
        sources = []
        for relative, content in fixture.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources.extend(sorted((ROOT / "42.21.0/src/zombie/apocalipse/humans").rglob("*.java")))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources))
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.apocalipse.humans.MovementModifiersTest"], check=True)


if __name__ == "__main__":
    main()
