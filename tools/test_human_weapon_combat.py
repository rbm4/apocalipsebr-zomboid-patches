"""Production weapon phases, fixed inventory port, approach and native packet bridge."""
from pathlib import Path
import subprocess
import sys
import tempfile
sys.dont_write_bytecode=True
from test_server_humans import FILES, ROOT

HARNESS=r"""
package zombie.apocalipse.humans;
import java.util.*;
import zombie.apocalipse.humans.brain.*;
import static zombie.apocalipse.humans.brain.HumanCombat.*;
import zombie.apocalipse.humans.movement.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.characters.*;
import zombie.iso.*;
import zombie.network.*;
import zombie.network.packets.*;
import zombie.network.packets.hit.*;
import zombie.network.packets.character.*;
import zombie.core.raknet.UdpConnection;
public class HumanWeaponCombatTest {
 static int checks;static final long S=1_000_000_000L,STEP=S/10;
 static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
 static void near(float a,float b,String message){check(Math.abs(a-b)<.0001,message+" actual="+a+" expected="+b);}
 static class Inventory implements HumanCombatInventory {
  int rounds=2,reserve=2,spends,reloads,equips;boolean ranged=true;
  public List<Weapon> weapons(){return List.of(new Weapon(1,ranged?"Base.Pistol":"Base.Crowbar",ranged,true,false,ranged?15:1.4f,rounds,reserve,400_000_000L,800_000_000L));}
  public boolean equip(int id){equips++;return true;}public boolean spendRound(int id){if(rounds==0)return false;rounds--;spends++;return true;}
  public boolean reload(int id){reloads++;rounds=reserve;reserve=0;return rounds>0;}
 }
 static class Operations implements HumanWeaponCombat.Operations {
  int commits,stops,repositions;boolean dead,clear=true,aim,reposition;double distance=1;HumanActuator.MotionStatus motion=HumanActuator.MotionStatus.SUCCEEDED;
  public double distance(){return distance;}public boolean defeated(){return dead;}
  public HumanActuator.MotionStatus approach(HumanCombatInventory.Weapon w,long now){return motion;}
  public boolean clearAttackLine(HumanCombatInventory.Weapon w){return clear;}
  public void face(){}public void stop(){stops++;}public void aim(boolean on){aim=on;}
  public boolean commit(HumanCombatInventory.Weapon w,long strike,long at){commits++;return true;}
  public boolean reposition(HumanCombatInventory.Weapon w,long at,boolean recovering){repositions++;return reposition;}
 }
 static record Fixture(ServerHumanFactory factory,ServerHuman human,IsoZombie target){}
 static Fixture fixture(String key,String mode)throws Exception{
  System.setProperty("apocbr.npc.combat.testWeapon",mode);
  GameServer.Players.clear();GameServer.IDToPlayerMap.clear();GameServer.udpEngine.connections.clear();
  PlayerHitZombiePacket.sent.clear();PlayerHitZombiePacket.localProcesses=PlayerHitZombiePacket.serverAttackCallbacks=0;
  INetworkPacket.messages.clear();PlayerPacketReliable.sent.clear();
  var cell=new IsoCell();IsoWorld.instance.currentCell=cell;var building=new BuildingDef();
  for(int x=90;x<=110;x++)for(int y=190;y<=210;y++)cell.getGridSquare(x,y,0).building=building;
  var constructor=ServerHumanFactory.class.getDeclaredConstructor();constructor.setAccessible(true);var factory=constructor.newInstance();factory.tick(0);
  factory.spawn(new ServerHumanDefinition(key,key,key,100.5f,200.5f,0,IsoDirections.E,false,"Tourist","militia-patrol"));
  var field=ServerHumanFactory.class.getDeclaredField("humans");field.setAccessible(true);var actor=(ServerHuman)((Map<?,?>)field.get(factory)).get(key);
  var target=new IsoZombie();target.x=106.5f;target.y=200.5f;target.health=10;target.online=30;target.instanceId=300;
  target.square=cell.getGridSquare(106,200,0);target.square.occupied=true;target.square.moving.add(target);cell.objects.add(target);
  var first=new UdpConnection();var second=new UdpConnection();GameServer.udpEngine.connections.add(first);GameServer.udpEngine.connections.add(second);
  return new Fixture(factory,actor,target);
 }
 static long untilCommit(Fixture f,long start,int desired){
  for(long now=start;now<start+15*S;now+=STEP){f.factory().tick(now);if(PlayerHitZombiePacket.localProcesses>=desired)return now;}
  throw new AssertionError("Combat never reached native commit");
 }
 public static void main(String[] ignored)throws Exception{
  var inventory=new Inventory();var operations=new Operations();var phase=new HumanWeaponCombat(inventory,operations,HumanWeaponCombat.Mode.RANGED,()->.5);
  phase.update(0);check(inventory.equips==1&&operations.aim,"equip phase calls inventory port and aim intent");
  phase.update(300_000_000L);check(phase.phase()==HumanWeaponCombat.Phase.WINDUP,"approach arrival starts windup");
  phase.cancel(Reason.PREEMPTED);phase.update(S);
  check(inventory.spends==0&&operations.commits==0&&!operations.aim,"cancel before commit spends no round and clears aim");
  inventory=new Inventory();operations=new Operations();phase=new HumanWeaponCombat(inventory,operations,HumanWeaponCombat.Mode.RANGED,()->.5);
  phase.update(0);phase.update(300_000_000L);phase.update(700_000_000L);phase.update(700_000_000L);
  check(inventory.spends==1&&operations.commits==1&&phase.phase()==HumanWeaponCombat.Phase.RECOVER,"one-shot commit marker prevents repeated same-time polls");
  phase.cancel(Reason.STOPPED);check(inventory.rounds==1&&operations.commits==1,"cancel after commit does not refund or repeat it");
  inventory=new Inventory();inventory.rounds=0;operations=new Operations();phase=new HumanWeaponCombat(inventory,operations,HumanWeaponCombat.Mode.RANGED,()->.5);
  phase.update(0);phase.update(300_000_000L);phase.update(S);
  check(inventory.reloads==0&&phase.phase()==HumanWeaponCombat.Phase.RELOAD,"reload waits until its insert deadline");
  phase.cancel(Reason.STOPPED);phase.update(2*S);check(inventory.reloads==0&&inventory.reserve==2,"cancelled reload creates no ammunition");
  var reload=new HumanWeaponCombat(inventory,new Operations(),HumanWeaponCombat.Mode.RANGED,()->.5);reload.update(0);reload.update(300_000_000L);reload.update(2*S);reload.update(2*S);
  check(inventory.reloads==1&&inventory.reserve==0,"reload commit is single and debits reserve through inventory port");
  inventory=new Inventory();operations=new Operations();phase=new HumanWeaponCombat(inventory,operations,HumanWeaponCombat.Mode.RANGED,()->.5);
  phase.update(0);phase.update(300_000_000L);operations.clear=false;phase.update(S);
  check(operations.commits==0&&inventory.spends==0,"blocked attack line re-enters approach before spending or damage");
  inventory=new Inventory();operations=new Operations();operations.motion=HumanActuator.MotionStatus.FAILED;phase=new HumanWeaponCombat(inventory,operations,HumanWeaponCombat.Mode.RANGED,()->.5);
  phase.update(0);check(phase.update(S).reason()==Reason.UNREACHABLE&&!operations.aim,"unreachable approach fails and cleans up");

  inventory=new Inventory();operations=new Operations();phase=new HumanWeaponCombat(inventory,operations,HumanWeaponCombat.Mode.RANGED,()->.5);
  phase.update(0);phase.update(300_000_000L);phase.update(700_000_000L);int probes=operations.repositions;
  phase.update(S);check(operations.repositions==probes,"first 350ms after attack locks movement rather than instantly dodging");
  phase.update(1_100_000_000L);check(operations.repositions==probes+1&&operations.commits==1,"late recovery permits defensive footwork without a second shot");
  phase.cancel(Reason.STOPPED);
  inventory=new Inventory();operations=new Operations();operations.reposition=true;phase=new HumanWeaponCombat(inventory,operations,HumanWeaponCombat.Mode.RANGED,()->.5);
  phase.update(0);phase.update(300_000_000L);phase.update(700_000_000L);
  check(phase.phase()==HumanWeaponCombat.Phase.APPROACH&&inventory.spends==0,"starting defensive footwork aborts aim before commit without spending ammo");
  for(double sample:new double[]{0,1}){
   inventory=new Inventory();operations=new Operations();phase=new HumanWeaponCombat(inventory,operations,HumanWeaponCombat.Mode.RANGED,()->sample);
   phase.update(0);phase.update(300_000_000L);long impact=300_000_000L+(sample==0?320_000_000L:480_000_000L);
   phase.update(impact-1);check(operations.commits==0,"sampled windup deadline cannot commit early");
   phase.update(impact);check(operations.commits==1,"bounded windup variance commits on its sampled deadline");
   long recovery=sample==0?640_000_000L:960_000_000L;
   phase.update(impact+recovery-1);check(phase.phase()==HumanWeaponCombat.Phase.RECOVER,"sampled recovery is stable across polls");
   phase.update(impact+recovery);check(phase.phase()==HumanWeaponCombat.Phase.WINDUP&&operations.commits==1,"sampled recovery completes without catching up attacks");
  }
  inventory=new Inventory();var gun=inventory.weapons().getFirst();var club=new HumanCombatInventory.Weapon(2,"Base.Crowbar",false,true,false,1.4f,0,0,400_000_000L,800_000_000L);
  check(HumanCombatTactics.minimum(gun)>HumanCombatTactics.maximum(club),"ranged standoff and melee reach have distinct spacing bands");
  var tactics=new HumanCombatTactics(()->0);
  check(tactics.update(gun,1,true,false,0).maneuver()==HumanCombatTactics.Maneuver.HOLD,"surprised gunner does not instantly retreat");
  check(tactics.update(gun,1,true,false,299_999_999L).maneuver()==HumanCombatTactics.Maneuver.HOLD,"reaction is sampled rather than rerolled every physics tick");
  check(tactics.update(gun,1,true,false,300_000_000L).maneuver()==HumanCombatTactics.Maneuver.WITHDRAW,"gunner requests retreat after reaction delay");
  check(tactics.update(gun,1,true,false,600_000_000L).maneuver()==HumanCombatTactics.Maneuver.HOLD,"retreat cooldown leaves a fight or exposure window under continued pressure");
  check(tactics.update(gun,1,true,false,2*S).maneuver()==HumanCombatTactics.Maneuver.WITHDRAW,"retreat becomes available again after bounded cooldown");
  tactics=new HumanCombatTactics(()->0);
  check(tactics.update(gun,10,true,false,0).maneuver()==HumanCombatTactics.Maneuver.CLOSE,"ranged maximum range does not replace preferred firing band");
  tactics=new HumanCombatTactics(()->0);tactics.update(club,1.1,true,false,0);
  check(tactics.update(club,1.1,true,true,300_000_000L).maneuver()==HumanCombatTactics.Maneuver.WITHDRAW,"melee may create a short recovery gap while remaining near weapon reach");
  check(tactics.update(gun,6,true,false,400_000_000L).maximum()==7,"weapon switch resets sampled spacing band without resetting retreat cooldown");

  var melee=fixture("melee-test","MELEE");long at=untilCommit(melee,STEP,1);
  check(melee.human().player.getPrimaryHandItem().getFullType().equals(ServerHumanInventory.MELEE),"melee mode selects Crowbar through real inventory adapter");
  check(melee.human().player.getX()>100.5&&melee.human().player.getX()<106,"combat approaches rather than hitting remotely or occupying target");
  check(PlayerPacketReliable.sent.stream().anyMatch(p->(p.booleanVariables&(1<<NetworkPlayerVariables.Flags.isRunning.ordinal()))!=0),"running approach is represented by existing movement packets");
  check(PlayerHitZombiePacket.localProcesses==1&&melee.target().nativeHits==1,"native local hit pipeline executes once");
  check(PlayerHitZombiePacket.sent.size()==2,"one native hit event per relevant connection, independent of heartbeat");
  check(PlayerHitZombiePacket.serverAttackCallbacks==0,"server attack callback cannot consume a second round");
  check(melee.factory().state("melee-test").vitals().endurance()<1,"native combat endurance changes feed central state");
  check(melee.human().inventory.weapons().stream().map(HumanCombatInventory.Weapon::fullType).toList().equals(List.of("Base.Crowbar","Base.Pistol")),"fixed weapon data returned by inventory controller");
  check(melee.human().player.getInventory().items.stream().anyMatch(i->i.getFullType().equals("Base.9mmClip"))
    &&melee.human().player.getInventory().items.stream().anyMatch(i->i.getFullType().equals("Base.Bullets9mm")),"configured magazine and bullets are real test items");
  float health=melee.target().health;melee.factory().tick(at+STEP);near(melee.target().health,health,"recovery does not repeat hit");
  melee.factory().deactivate("melee-test");check(!melee.human().aiming&&!melee.human().movement.moving(),"lifecycle cancellation stops combat movement and aim");

  var ranged=fixture("ranged-test","RANGED");at=untilCommit(ranged,STEP,1);
  var weapon=(zombie.inventory.types.HandWeapon)ranged.human().player.getPrimaryHandItem();
  check(weapon.getFullType().equals("Base.Pistol")&&ranged.human().player.getX()==100.5f,"ranged mode equips Pistol and fires from range");
  check(weapon.getCurrentAmmoCount()+(weapon.isRoundChambered()?1:0)==14,"one firearm commit consumes exactly one of fifteen rounds");
  check(INetworkPacket.messages.stream().filter(m->m.type()==PacketTypes.PacketType.RangedWeaponSound).count()==2,"shot sound sent to both viewers");
  check(ranged.human().player.noises==1,"one authoritative gunshot stimulus, not one per viewer");
  var late=new UdpConnection();int equips=(int)INetworkPacket.messages.stream().filter(m->m.type()==PacketTypes.PacketType.Equip).count();
  ServerHumanTransport.announce(ranged.human(),late);
  check(INetworkPacket.messages.stream().filter(m->m.type()==PacketTypes.PacketType.Equip).count()==equips+1,"late viewer receives current equipped item snapshot");
  int messages=INetworkPacket.messages.size();ServerHumanTransport.equipment(ranged.human());
  check(INetworkPacket.messages.size()==messages,"unchanged equipment snapshot is deduplicated per viewer");
  var descriptor=ranged.human().inventory.weapons().stream().filter(HumanCombatInventory.Weapon::ranged).findFirst().orElseThrow();
  int reserve=descriptor.reserve();while(ranged.human().inventory.spendRound(weapon.getID())){}
  check(!ranged.human().inventory.spendRound(weapon.getID()),"finite magazine cannot fire below zero");
  check(ranged.human().inventory.reload(weapon.getID()),"fixed inventory controller has a real reload bridge");
  var loaded=ranged.human().inventory.weapons().stream().filter(HumanCombatInventory.Weapon::ranged).findFirst().orElseThrow();
  check(loaded.rounds()==15&&loaded.reserve()==reserve-15,"reload transfers finite reserve, never synthesizes bullets");
  ranged.factory().deactivate("ranged-test");
  var miss=fixture("miss-test","RANGED");zombie.core.random.Rand.roll=99;untilCommit(miss,STEP,1);
  near(miss.target().health,10,"ranged miss sends an attack without damaging target");
  check(miss.target().nativeHits==0&&((zombie.inventory.types.HandWeapon)miss.human().player.getPrimaryHandItem()).getCurrentAmmoCount()==13,"miss spends a round despite empty native hit list");
  miss.factory().deactivate("miss-test");zombie.core.random.Rand.roll=0;System.clearProperty("apocbr.npc.combat.testWeapon");

  var blocked=fixture("blocked-test","RANGED");zombie.pathfind.PolygonalMap2.instance.blocked=true;
  for(long now=STEP;now<4*S;now+=STEP)blocked.factory().tick(now);
  check(PlayerHitZombiePacket.localProcesses==0&&blocked.human().inventory.weapons().stream().filter(HumanCombatInventory.Weapon::ranged).findFirst().orElseThrow().rounds()==15,
    "blocked geometry causes bounded route failure without damage or ammunition expenditure");
  blocked.factory().deactivate("blocked-test");zombie.pathfind.PolygonalMap2.instance.blocked=false;
  var pressure=fixture("pressure-test","RANGED");
  for(long now=STEP;now<=6*STEP;now+=STEP)pressure.factory().tick(now);
  pressure.human().state.dispatch(new zombie.apocalipse.humans.state.HumanMutation.Mobility(false,true));pressure.factory().tick(7*STEP);
  check(PlayerHitZombiePacket.localProcesses==0&&!pressure.human().aiming&&!pressure.human().movement.moving(),"incapacitation during preparation cancels the live native backend before commit");
  pressure.factory().deactivate("pressure-test");
  var body=fixture("body-test","RANGED");
  var bystander=new IsoPlayer(body.human().player.getCell(),new SurvivorDesc(false),0,0,0);bystander.x=103.5f;bystander.y=200.5f;
  bystander.getCurrentSquare().moving.add(bystander);
  check(!ServerHumanCombatNetwork.clearBodies(body.human(),body.target()),"body in direct lane blocks the initial shot");
  untilCommit(body,STEP,1);
  check(body.human().player.getY()!=200.5f&&ServerHumanCombatNetwork.clearBodies(body.human(),body.target()),"blocked lane causes reposition to a clear attack point before committing");
  body.factory().deactivate("body-test");System.clearProperty("apocbr.npc.combat.testWeapon");

  var spacing=fixture("spacing-test","RANGED");spacing.factory().tick(STEP);spacing.human().combat.stop();
  spacing.target().square.moving.remove(spacing.target());spacing.target().square.occupied=false;
  spacing.target().x=101.5f;spacing.target().square=spacing.human().player.getCell().getGridSquare(101,200,0);
  spacing.target().square.moving.add(spacing.target());spacing.target().square.occupied=true;
  spacing.human().perception.update(2*STEP);
  var movement=new ServerHumanCombatApproach(spacing.human(),spacing.target(),()->0);
  var gunSnapshot=spacing.human().inventory.weapons().stream().filter(HumanCombatInventory.Weapon::ranged).findFirst().orElseThrow();
  movement.update(gunSnapshot,S);near(spacing.human().player.getX(),100.5f,"native spacing holds during surprise reaction");
  movement.update(gunSnapshot,S+300_000_000L);movement.update(gunSnapshot,S+400_000_000L);
  check(spacing.human().player.getX()<100.5f&&spacing.human().movement.snapshot().gait()==ServerHumanMovement.Gait.WALK,"native ranged pressure produces a walking escape rather than perfect running kite");
  for(long tick=S+500_000_000L;tick<=S+1_300_000_000L;tick+=STEP)movement.update(gunSnapshot,tick);
  check(100.5f-spacing.human().player.getX()<=1.81f&&!spacing.human().movement.moving(),"retreat leg stops at its time budget even without restoring ideal range");
  var actorTile=new Tile(spacing.human().player.getXi(),spacing.human().player.getYi());
  for(int x=actorTile.x()-2;x<=actorTile.x()+2;x++)for(int y=actorTile.y()-2;y<=actorTile.y()+2;y++)
   if(x!=actorTile.x()||y!=actorTile.y())spacing.human().player.getCell().getGridSquare(x,y,0).occupied=true;
  movement=new ServerHumanCombatApproach(spacing.human(),spacing.target(),()->0);
  movement.update(gunSnapshot,3*S);
  check(movement.update(gunSnapshot,3*S+300_000_000L)==HumanActuator.MotionStatus.SUCCEEDED&&!spacing.human().movement.moving(),"native cornered retreat holds for combat instead of inventing an escape or failing the lease");
  spacing.factory().deactivate("spacing-test");System.clearProperty("apocbr.npc.combat.testWeapon");

  var pressured=fixture("pressured-gunner","RANGED");
  pressured.target().square.moving.remove(pressured.target());pressured.target().square.occupied=false;
  pressured.target().x=102.5f;pressured.target().square=pressured.human().player.getCell().getGridSquare(102,200,0);
  pressured.target().square.moving.add(pressured.target());pressured.target().square.occupied=true;
  boolean withdrew=false,lowered=true,aimed=false;
  for(long tick=STEP;tick<15*S&&PlayerHitZombiePacket.localProcesses==0;tick+=STEP){
   pressured.factory().tick(tick);
   if(pressured.human().player.getX()<100.5f&&pressured.human().movement.moving()){withdrew=true;lowered&=!pressured.human().aiming;}
   if(!pressured.human().movement.moving()&&pressured.human().aiming)aimed=true;
  }
  check(withdrew&&lowered&&aimed&&PlayerHitZombiePacket.localProcesses==1,"native weapon backend lowers aim to retreat then reacquires and commits one shot");
  pressured.factory().deactivate("pressured-gunner");System.clearProperty("apocbr.npc.combat.testWeapon");

  CombatRoutePlanner.Grid grid=new CombatRoutePlanner.Grid(){
   public boolean walkable(Tile tile){return tile.x()>=0&&tile.x()<8&&tile.y()>=0&&tile.y()<3;}
   public boolean connected(Tile from,Tile to){return walkable(to);}
   public boolean clearAttack(Tile from){return from.y()==1;}
  };
  var route=CombatRoutePlanner.plan(grid,new Tile(0,0),7.5f,1.5f,1.4f);
  check(!route.isEmpty()&&route.getLast().x()==6&&route.getLast().y()==1,"bounded planner finds a reachable clear stopping point");
  check(CombatRoutePlanner.plan(grid,new Tile(0,0),1000,1000,1).isEmpty(),"unreachable target has explicit empty route");
  var band=CombatRoutePlanner.plan(grid,new Tile(0,0),7.5f,1.5f,3,5);
  check(!band.isEmpty()&&Math.hypot(band.getLast().x()+.5-7.5,band.getLast().y()+.5-1.5)>=3,"approach planner stops inside preferred annulus rather than hugging target");
  CombatRoutePlanner.Grid corner=new CombatRoutePlanner.Grid(){
   public boolean walkable(Tile tile){return tile.equals(new Tile(0,0));}public boolean connected(Tile a,Tile b){return false;}
   public boolean clearAttack(Tile tile){return true;}
  };
  check(CombatRoutePlanner.withdraw(corner,new Tile(0,0),1.5f,.5f,2).isEmpty(),"cornered retreat fails without teleporting or bypassing geometry");
  CombatRoutePlanner.Grid crowd=new CombatRoutePlanner.Grid(){
   public boolean walkable(Tile tile){return Math.abs(tile.x())<=2&&Math.abs(tile.y())<=2;}public boolean connected(Tile a,Tile b){return true;}
   public boolean clearAttack(Tile tile){return true;}public double placementScore(Tile tile){return tile.y()>=0?-20:0;}
  };
  var escape=CombatRoutePlanner.withdraw(crowd,new Tile(0,0),1.5f,.5f,2);
  check(!escape.isEmpty()&&escape.getLast().y()<0&&escape.size()<=3,"escape scores pressure and keeps the leg within two cardinal steps");
  var rays=new java.util.concurrent.atomic.AtomicInteger();
  CombatRoutePlanner.Grid limited=new CombatRoutePlanner.Grid(){
   public boolean walkable(Tile tile){return Math.abs(tile.x())<20&&Math.abs(tile.y())<20;}public boolean connected(Tile a,Tile b){return true;}
   public boolean clearAttack(Tile tile){rays.incrementAndGet();return false;}
  };
  check(CombatRoutePlanner.plan(limited,new Tile(0,0),0,0,15).isEmpty()&&rays.get()==16,"route search bounds expensive attack-line probes independently of visited nodes");
  System.out.println("Passed "+checks+" weapon phase, inventory, native bridge, movement and replication assertions.");
 }
}
"""

def main():
    with tempfile.TemporaryDirectory(prefix='apocbr-weapon-tests-') as directory:
        work=Path(directory);fixture=dict(FILES);sources=[]
        fixture['zombie/apocalipse/humans/HumanWeaponCombatTest.java']=HARNESS
        for name,content in fixture.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
        sources.extend(sorted((ROOT/'42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
        args=work/'sources.txt';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources))
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',str(work/'classes'),'zombie.apocalipse.humans.HumanWeaponCombatTest'],check=True)

if __name__=='__main__':main()
