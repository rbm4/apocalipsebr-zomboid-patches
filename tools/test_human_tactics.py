"""Production tactical intent, facing/travel replication and shared escape/combat ownership."""
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
import zombie.apocalipse.humans.movement.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.humans.movement.MovementModifiers.Pace;
import zombie.characters.*;
import zombie.iso.*;
import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.PlayerPacketReliable;
import zombie.network.packets.hit.PlayerHitZombiePacket;
public class TacticalTest {
 static int checks;static final long STEP=100_000_000L;
 static void check(boolean b,String text){checks++;if(!b)throw new AssertionError(text);}
 static record Fixture(ServerHuman actor,IsoZombie target,HumanSenses.Identity identity){}
 static Fixture fixture(String key){
  var cell=new IsoCell();var building=new BuildingDef();
  for(int x=90;x<=110;x++)for(int y=190;y<=210;y++)cell.getGridSquare(x,y,0).building=building;
  var human=new ServerHuman(new ServerHumanDefinition(key,key,key,100.5f,200.5f,0,IsoDirections.E,false,"Tourist","militia-patrol"),(short)1234,cell);
  var target=new IsoZombie();target.x=101.5f;target.y=200.5f;target.online=44;target.instanceId=44;target.health=100;
  target.square=cell.getGridSquare(101,200,0);target.square.moving.add(target);target.square.occupied=true;cell.objects.add(target);
  human.actuator.observe(0);
  return new Fixture(human,target,human.perception.snapshot().visible(HumanSenses.Kind.ZOMBIE).getFirst().identity());
 }
 static TacticalIntent guard(Fixture f,boolean elevated){return new TacticalIntent(TacticalIntent.Objective.ESCAPE,TacticalIntent.Posture.GUARDED,f.identity(),elevated);}
 static void facing(){
  var f=fixture("facing");var h=f.actor();
  h.actuator.tactical(guard(f,false));
  check(h.aiming&&h.player.aiming,"guarded intent applies native aim state");
  check(h.movement.start(List.of(new Tile(100,200),new Tile(99,200),new Tile(98,200)),h.movement,Pace.RUN,0),"route starts opposite the faced target");
  h.movement.update(STEP);
  check(h.player.getX()<100.5f&&Math.cos(h.player.getDirectionAngleRadians())>.99,"backward movement preserves enemy facing");
  check(h.movement.snapshot().speed()<=1.3001f&&h.movement.snapshot().gait()==ServerHumanMovement.Gait.WALK,"guard posture caps speed and gait despite RUN request");
  check(h.movement.predictionDistanceUnits()>0,"different facing does not suppress safe travel prediction");
  ServerHumanTransport.idle(h,new UdpConnection());var packet=PlayerPacketReliable.sent.getLast();
  check(Math.cos(packet.prediction.direction)>.99&&Math.cos(packet.prediction.moveDirection)<-.99,"heartbeat encodes facing independently from travel");
  h.actuator.tactical(TacticalIntent.NONE);h.movement.update(2*STEP);
  check(!h.aiming&&Math.cos(h.player.getDirectionAngleRadians())<-.99,"release clears aim and restores travel-facing ownership");
  check(h.movement.snapshot().speed()>1.3,"releasing guard restores permitted requested running speed");
  h.dispose();
 }
 static void concurrent(){
  System.setProperty("apocbr.npc.combat.testWeapon","RANGED");
  var f=fixture("concurrent");var h=f.actor();h.actuator.tactical(guard(f,false));
  h.movement.start(List.of(new Tile(100,200),new Tile(99,200),new Tile(98,200),new Tile(97,200)),h.movement,Pace.WALK,0);
  var execution=h.combat.begin(new HumanCombat.Request(f.identity(),HumanCombat.Template.GUARD_BASIC,true),0);
  int before=PlayerHitZombiePacket.localProcesses;
  execution.update(0);
  for(long now=STEP;now<15*STEP&&PlayerHitZombiePacket.localProcesses==before;now+=STEP){
   h.perception.update(now);h.actuator.tactical(guard(f,false));h.movement.update(now);execution.update(now);
  }
  check(PlayerHitZombiePacket.localProcesses==before+1,"external combat commits an attack while escape route advances");
  check(h.player.getX()<100&&h.movement.moving(),"weapon phase does not cancel externally owned movement");
  execution.cancel(HumanCombat.Reason.PREEMPTED);
  check(h.movement.moving()&&h.aiming,"defense cancellation leaves route and stance with survival owner");
  h.actuator.tactical(TacticalIntent.NONE);h.movement.stop();h.dispose();System.clearProperty("apocbr.npc.combat.testWeapon");
 }
 static void risk(){
  var f=fixture("risk");var h=f.actor();var cell=h.player.getCell();
  // Sparse occupancy in the destination's corner leaves a physically usable center corridor.
  var body=new IsoZombie();body.x=99.05f;body.y=200.95f;body.square=cell.getGridSquare(99,200,0);
  body.square.moving.add(body);body.square.occupied=true;
  h.movement.start(List.of(new Tile(100,200),new Tile(99,200)),h.movement,Pace.WALK,0);
  check(!h.movement.occupancyAllows(100.05f,200.1f,99.9f,200.1f),"ordinary escape rejects occupied destination tiles");
  h.actuator.tactical(guard(f,true));
  check(h.movement.occupancyAllows(100.05f,200.1f,99.9f,200.1f),"elevated exposure accepts a clear segment into a sparsely occupied tile");
  body.x=99.8f;body.y=200.1f;
  check(!h.movement.occupancyAllows(100.05f,200.1f,99.9f,200.1f),"desperate exposure still rejects moving into an overlapping body");
  body.x=99.02f;body.y=200.98f;
  cell.getGridSquare(100,199,0).free=false;cell.getGridSquare(100,201,0).free=false;
  var threats=h.perception.snapshot().visible(HumanSenses.Kind.ZOMBIE);
  h.actuator.tactical(guard(f,false));
  check(!h.actuator.survival().escape(threats,zombie.apocalipse.humans.survival.SurvivalSettings.DEFAULT,0,false),"ordinary planner cannot enter the only occupied exit");
  h.actuator.tactical(guard(f,true));
  check(h.actuator.survival().escape(threats,zombie.apocalipse.humans.survival.SurvivalSettings.DEFAULT,0,true),"elevated planner finds a body-clear detour through that occupied tile");
  check(h.movement.nextTile().equals(new Tile(100,200))||h.movement.nextTile().equals(new Tile(99,200)),"risk route still begins with an executable adjacent step");
  h.dispose();
 }
 public static void main(String[] args){facing();concurrent();risk();System.out.println("Passed "+checks+" tactical intent, native heartbeat, mobile attack and exposure assertions.");}
}
'''

def main():
    with tempfile.TemporaryDirectory(prefix='apocbr-tactical-tests-') as directory:
        work=Path(directory);fixture=dict(FILES);sources=[]
        fixture['zombie/apocalipse/humans/TacticalTest.java']=HARNESS
        for name,content in fixture.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
        sources.extend(sorted((ROOT/'42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
        args=work/'sources.txt';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources))
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',str(work/'classes'),'zombie.apocalipse.humans.TacticalTest'],check=True)

if __name__=='__main__':main()
