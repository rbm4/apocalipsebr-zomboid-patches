"""Production retaliation, player-hit routing and safety tests with native boundary stubs."""
from pathlib import Path
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True
from test_server_humans import FILES, ROOT
from test_human_dialogue import HARNESS as DIALOGUE
from test_human_encounters import HARNESS as ENCOUNTERS

STUBS = {
'zombie/network/fields/hit/Player.java':r'''package zombie.network.fields.hit;
public class Player {
 public short id;public zombie.characters.IsoPlayer player;public static int swings;
 public short getID(){return id;}public zombie.characters.IsoPlayer getPlayer(){return player;}public zombie.characters.IsoGameCharacter getCharacter(){return player;}
 public void set(zombie.characters.IsoPlayer p,boolean crit){player=p;id=p.getOnlineID();}
 public void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c){}public void write(zombie.core.network.ByteBufferWriter b){}
 public boolean isConsistent(zombie.network.IConnection c){return player!=null;}public void process(){}public void react(){}
 public float getX(){return player.getX();}public float getY(){return player.getY();}public float getZ(){return player.getZ();}
 public void attack(zombie.inventory.types.HandWeapon w,boolean pvp,byte shot,int hits){swings++;}
}''',
'zombie/network/fields/hit/Fall.java':r'''package zombie.network.fields.hit;
public class Fall {public float dropDirection;public void set(Object ai){}public void set(float x,float y,byte z,float angle){}
 public void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c){}public void write(zombie.core.network.ByteBufferWriter b){}public void process(Object c){} }''',
'zombie/network/packets/hit/PlayerHitCharacter.java':r'''package zombie.network.packets.hit;
import java.util.*;import zombie.network.fields.hit.*;import zombie.characters.*;import zombie.inventory.types.HandWeapon;
public abstract class PlayerHitCharacter implements HitCharacter,zombie.network.packets.INetworkPacket {
 protected final Player wielder=new Player();protected List<WeaponHit> hits=new ArrayList<>();protected byte shotID;protected int hitCount;protected HandWeapon weapon;
 public static int ordinaryServer,localHits,sends;
 public void set(IsoPlayer p,HandWeapon w,boolean critical,List<?> tracers,List<WeaponHit> hits){wielder.set(p,critical);weapon=w;this.hits=new ArrayList<>(hits);hitCount=hits.size();}
 public void setData(Object... values){}public void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c){}public void write(zombie.core.network.ByteBufferWriter b){}
 public boolean isConsistent(zombie.network.IConnection c){return true;}public void update(){}public void preProcess(){}public void postProcess(){}public void log(zombie.core.raknet.UdpConnection c){}
 public void attack(){}public void react(){}public void processTracers(){}public abstract IsoGameCharacter getTargetCharacter();public IsoGameCharacter getTarget(){return getTargetCharacter();}
 public float getDistance(){return 0;}public boolean isMelee(){return !weapon.isRanged();}
 public IsoPlayer getWielder(){return wielder.getPlayer();}public HandWeapon getHandWeapon(){return weapon;}public int getHitCount(){return hitCount;}public byte getShotID(){return shotID;}
 public void processHits(IsoGameCharacter target){localHits++;if(target instanceof IsoPlayer p){p.setHealth(p.getHealth()-.1f);p.getBodyDamage().setOverallBodyHealth(p.getHealth()*100);}}
 public void processHitHumanoid(IsoGameCharacter target){}public zombie.network.fields.hit.Hit getHit(int i){return null;}
 public void processClient(Object c){preProcess();process();postProcess();attack();react();}public void process(){}
 public void processServer(zombie.network.PacketTypes.PacketType type,zombie.core.raknet.UdpConnection c){ordinaryServer++;processClient(c);}
 public void sendToClient(zombie.network.PacketTypes.PacketType type,zombie.core.raknet.UdpConnection c){sends++;}
}''',
'zombie/network/anticheats/AbstractAntiCheat.java':r'''package zombie.network.anticheats;public class AbstractAntiCheat {public String validate(zombie.core.raknet.UdpConnection c,zombie.network.packets.INetworkPacket p){return "";}}''',
'zombie/characters/SafetySystemManager.java':r'''package zombie.characters;public class SafetySystemManager {public static boolean isPlayerSafetyChangeDelayed(IsoPlayer p){return false;}}''',
'zombie/util/Type.java':r'''package zombie.util;public class Type {public static <T>T tryCastTo(Object o,Class<T> c){return c.isInstance(o)?c.cast(o):null;}}''',
'zombie/core/logger/LoggerManager.java':r'''package zombie.core.logger;public class LoggerManager {public static String getPlayerCoords(zombie.characters.IsoPlayer p){return "";}}''',
'zombie/network/PVPLogTool.java':r'''package zombie.network;public class PVPLogTool {public static void logCombat(String a,String b,String c,String d,float x,float y,float z,String weapon,float damage){}}''',
}

HARNESS = r'''
package zombie.apocalipse.humans;
import java.util.*;import zombie.apocalipse.humans.brain.*;import zombie.apocalipse.humans.encounters.*;import zombie.apocalipse.humans.profiles.*;
import zombie.characters.*;import zombie.iso.*;import zombie.network.*;import zombie.network.packets.hit.*;import zombie.network.fields.hit.*;import zombie.inventory.types.HandWeapon;
public class RetaliationTest {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static HumanObservation o(long now){return new HumanObservation(now,List.of());}
 static class Port implements RetaliationPort {
  boolean valid=true,visible=true;int starts,cancels,updates;HumanObservation.Identity target;
  public Contact observe(HumanObservation.Identity id){return new Contact(valid,visible);}
  public HumanCombat.Execution begin(HumanObservation.Identity id,long now){target=id;starts++;return new HumanCombat.Execution(){
   public HumanCombat.Result update(long now){updates++;return new HumanCombat.Result(HumanCombat.Status.RUNNING,HumanCombat.Reason.NONE);}
   public void cancel(HumanCombat.Reason why){cancels++;}
  };}
 }
 static void policy(){
  var actor=new EncounterTest.Port(new HumanRelationships());var port=new Port();var m=new RetaliatePlayerModule(RetaliatePlayerModule.Settings.from(Map.of()));m.bind(port);
  var id=new HumanObservation.Identity((short)1,1);var second=new HumanObservation.Identity((short)2,2);
  check(!m.eligible(o(1)),"no aggression on proximity or weapon state");m.attacked(id,1);
  check(m.emergency(o(2)),"attack preempts exclusive interaction");check(port.starts==0,"emergency query does not attack");
  m.update(o(2),actor);m.update(o(3),actor);check(port.starts==1&&port.updates==2,"one owned execution is polled");
  m.attacked(second,4);m.update(o(5),actor);check(port.cancels==1&&port.target.equals(second),"new actual attacker replaces old target without stale strike");
  m.suspend(6,actor);check(!m.eligible(o(7))&&m.emergency(o(7)),"suspension preserves emergency evidence but stops execution");
  m.resume(null,8,actor);m.update(o(9),actor);check(port.starts==3,"resume revalidates and rebuilds execution");
  port.visible=false;m.update(o(1000000009L),actor);check(port.cancels==3,"lost visibility immediately cancels weapon execution");
  m.update(o(3000000010L),actor);check(m.aggressor()==null,"lost contact grace ends pursuit");
  port.visible=true;m.attacked(id,4000000000L);m.update(o(34000000001L),actor);check(m.aggressor()==null,"retaliation expires without permanent hunt");
  m.attacked(id,35000000000L);port.valid=false;m.update(o(35000000001L),actor);check(m.aggressor()==null,"invalid target cancels immediately");
  m.stop(36000000000L,actor);m.attacked(id,37000000000L);check(!m.available()&&m.aggressor()==null,"shutdown cannot reacquire an attacker");
  var warningMemory=new HumanRelationships();var warningPort=new EncounterTest.Port(warningMemory);
  var warning=new PlayerEncounterModule(new EncounterPolicy("test-militia",8,16000,1000,60000,300000,60000));warning.bind(warningPort);
  warning.update(o(1),warningPort);warning.update(o(8000000001L),warningPort);
  check(warningMemory.get(EncounterTest.KEY).refused()==0,"old eight-second deadline no longer refuses");
  warning.update(o(16000000001L),warningPort);check(warningMemory.get(EncounterTest.KEY).refused()==1,"sixteen-second deadline refuses once");
 }
 public static void main(String[] args){policy();nativeBridge();System.out.println("Passed "+checks+" retaliation, attempt authority, PvP routing and doubled-warning assertions.");}
 static void nativeBridge(){
  var building=new BuildingDef();for(int x=90;x<=110;x++)for(int y=190;y<=210;y++)IsoWorld.instance.currentCell.getGridSquare(x,y,0).building=building;
  var factory=ServerHumanFactory.getInstance();factory.tick(0);var base=ServerHumanProfiles.registry().require("roaming-greeter");
  var policy=new EncounterPolicy("test-militia",8,16000,1000,60000,300000,60000);
  var profile=new HumanProfile("retaliation-test",1,"modules",Map.of(),"","none",true,false,
   List.of(new HumanModuleBinding("retaliate-player",20,100,Map.of()),new HumanModuleBinding("player-encounter",100,100,Map.of()),new HumanModuleBinding("walk",1000,100,Map.of())),
   8,100,true,base.walkingPatterns(),new HumanCheats(false,false,false),base.survival(),policy);
  ServerHumanProfiles.registry().register(profile);
  short id=factory.spawn(new ServerHumanDefinition("retaliation-test","Guard","Guard",100,200,0,IsoDirections.E,false,"Tourist","retaliation-test"));
  var h=ServerHumanFactory.tradingHuman(id);var p=DialogueTest.visitor((short)1);p.x=101;p.y=200;
  var weapon=new HandWeapon(77,"Base.Crowbar");p.getInventory().AddItem(weapon);p.setPrimaryHandItem(weapon);
  var connection=GameServer.getConnectionFromPlayer(p);h.viewers.add(connection);
  check(!h.player.isGodMod()&&(h.player.getExtraInfoFlags()&1)==0,"militia with disabled god mode is targetable through ordinary presentation");
  check(p.getSafety().isEnabled()&&!h.player.getSafety().isEnabled(),"combat NPC advertises safety off so a stock safety-on visitor can hit");
  h.player.god=true;check(!ServerHumanPlayerAttacks.receive(p,h.player,weapon,connection,1,(byte)0),"protected NPCs cannot acquire retaliation from blocked attacks");h.player.god=false;
  var targetField=new Player();targetField.id=id;ServerHumanPlayerAttacks.resolve(targetField);check(targetField.getPlayer()==h.player,"reserved NPC target resolves without registering a fake player account");
  var occupied=new Player();occupied.id=id;occupied.player=p;ServerHumanPlayerAttacks.resolve(occupied);check(occupied.player==p,"resolver does not replace an already resolved player");
  var hit=new WeaponHit();hit.set(1,1,1,1,0,false,false,false,false);
  // Serialize through exact native writer methods, then parse as a stock client
  // packet. The NPC is absent from GameServer.IDToPlayerMap throughout.
  p.playerIndex=0;
  var clientPacket=new PlayerHitPlayerPacket();clientPacket.set(p,weapon,false,List.of(),List.of(hit),h.player);
  var writer=new zombie.core.network.ByteBufferWriter();clientPacket.write(writer);writer.bb.flip();
  var fromWire=new PlayerHitPlayerPacket();fromWire.parse(new zombie.core.network.ByteBufferReader(writer.bb),connection);
  check(!writer.bb.hasRemaining(),"stock client payload including variable player fields and fall is fully consumed");
  check(fromWire.getWielder()==p&&fromWire.target.getPlayer()==h.player&&fromWire.getHandWeapon()==weapon,"wire resolves connection-owned attacker, inventory weapon and detached NPC target");
  check(fromWire.isConsistent(connection)&&fromWire.getHitCount()==1,"native serialized hit passes consistency before server intake");
  var packet=fromWire;
  var safety=new zombie.network.anticheats.AntiCheatSafety();
  check(safety.validate(connection,packet).isEmpty(),"native safety admits a non-god militia attack attempt");
  float botBefore=h.player.getHealth();int swings=Player.swings;h.nextPlayerHitTrace=0;
  packet.processServer(PacketTypes.PacketType.PlayerHitPlayer,connection);
  var module=ServerHumanRetaliation.module(h);
  check(module.aggressor().equals(new HumanObservation.Identity(p.id,p.getID())),"validated actual hit arms retaliation");
  check(h.player.getHealth()<botBefore&&PlayerHitCharacter.localHits==0,"incoming hit commits native body loss without native player-account processing");
  float botDamaged=h.player.getHealth();check(h.state.snapshot().vitals().health()==botDamaged,"body loss projects into durable logical state immediately");
  check(zombie.debug.DebugLog.messages.stream().anyMatch(s->s.contains("received native PlayerHitPlayer")),"default receive diagnostics precede native payload parsing");
  check(zombie.debug.DebugLog.messages.stream().anyMatch(s->s.contains("damage committed")),"committed NPC damage is logged even at default NPC ERROR threshold");
  check(Player.swings==swings+1,"accepted attack spends native swing once");
  packet.processServer(PacketTypes.PacketType.PlayerHitPlayer,connection);check(Player.swings==swings+1,"duplicate attempt cannot spend ammo or refresh twice");
  check(h.player.getHealth()==botDamaged,"duplicate shot cannot damage NPC twice");
  check(ServerHumanEncounters.memory().get(ServerHumanEncounters.key(h,p)).permissionUntilMs()==0,"attack revokes interaction permission");
  check(!ServerHumanPlayerAttacks.receive(p,h.player,weapon,new zombie.core.raknet.UdpConnection(),1,(byte)1),"wrong connection rejected");
  var q=DialogueTest.visitor((short)2);q.x=102;var other=GameServer.getConnectionFromPlayer(q);
  check(!ServerHumanPlayerAttacks.receive(q,h.player,weapon,other,1,(byte)1),"another account cannot name someone else's weapon");
  ServerOptions.instance.pvp.value=false;check(!safety.validate(connection,packet).isEmpty(),"PvP disabled remains a native rejection");
  check(!ServerHumanPlayerAttacks.receive(p,h.player,weapon,connection,1,(byte)1),"manual intake also refuses disabled PvP");ServerOptions.instance.pvp.value=true;
  zombie.iso.areas.NonPvpZone.blocked=true;check(!ServerHumanPlayerAttacks.receive(p,h.player,weapon,connection,1,(byte)1),"safe zone prevents retaliation trigger");zombie.iso.areas.NonPvpZone.blocked=false;
  var ordinary=new PlayerHitPlayerPacket();q.god=true;ordinary.set(p,weapon,false,List.of(),List.of(hit),q);
  check(safety.validate(connection,ordinary).contains("god-mode"),"ordinary player god-mode is never exempted");q.god=false;
  var pvpBefore=p.getHealth();int nativeBefore=PlayerHitCharacter.localHits;
  long now=System.nanoTime();
  for(int n=0;n<8&&PlayerHitCharacter.localHits==nativeBefore;n++)module.update(o(now+n*500000000L),h.actuator);
  check(PlayerHitCharacter.localHits==nativeBefore+1&&p.getHealth()<pvpBefore,"NPC uses native PvP hit processing to damage the aggressor");
  check(h.player.getHealth()==botDamaged&&q.getHealth()==1,"outgoing retaliation does not damage NPC or unrelated player");
  int nativeAfter=PlayerHitCharacter.localHits;module.update(o(now+3600000000L),h.actuator);
  check(PlayerHitCharacter.localHits==nativeAfter,"recovery polling does not replay committed strike");
  ServerOptions.instance.pvp.value=false;module.update(o(now+4000000000L),h.actuator);check(module.aggressor()==null,"disabling PvP cancels active retaliation");ServerOptions.instance.pvp.value=true;
  module.attacked(new HumanObservation.Identity(p.id,p.getID()),System.nanoTime());p.x=104;
  var ranged=h.inventory.weapons().stream().filter(HumanCombatInventory.Weapon::ranged).findFirst().orElseThrow();int rounds=ranged.rounds();
  int previousHits=PlayerHitCharacter.localHits;now=System.nanoTime();
  for(int n=0;n<8&&PlayerHitCharacter.localHits==previousHits;n++)module.update(o(now+n*500000000L),h.actuator);
  var remaining=h.inventory.weapons().stream().filter(HumanCombatInventory.Weapon::ranged).findFirst().orElseThrow();
  check(PlayerHitCharacter.localHits==previousHits+1&&remaining.rounds()==rounds-1,"ranged retaliation commits one native hit and one round");
  p.dead=true;module.update(o(now+4000000000L),h.actuator);check(module.aggressor()==null,"dead aggressor ends retaliation");p.dead=false;
  module.attacked(new HumanObservation.Identity(p.id,p.getID()),System.nanoTime());p.instanceId++;
  module.update(o(System.nanoTime()),h.actuator);check(module.aggressor()==null,"recycled online ID cannot inherit retaliation");
  int normal=PlayerHitCharacter.ordinaryServer;ordinary.processServer(PacketTypes.PacketType.PlayerHitPlayer,connection);
  check(PlayerHitCharacter.ordinaryServer==normal+1,"real player versus player still follows vanilla dispatch");
  p.x=101;p.god=true;p.invisible=true;
  check(ServerHumanPlayerAttacks.receive(p,h.player,weapon,connection,1,(byte)2),"protected invisible attacker may inflict a validated hit; retaliation protection is separate");
  check(!ServerHumanRetaliation.pvp(h.player,p,false),"protected attacker remains immune to retaliation");p.god=false;p.invisible=false;
  var gun=new HandWeapon(78,"Base.Pistol");p.getInventory().AddItem(gun);p.setPrimaryHandItem(gun);
  var tracer=new TracerInfo();tracer.hasEndPosition=true;tracer.range=15;tracer.endX=100;tracer.endY=200;tracer.endZ=0;
  var rangedClient=new PlayerHitPlayerPacket();rangedClient.set(p,gun,false,List.of(tracer),List.of(hit),h.player);
  var rangedWriter=new zombie.core.network.ByteBufferWriter();rangedClient.write(rangedWriter);rangedWriter.bb.flip();
  var rangedServer=new PlayerHitPlayerPacket();rangedServer.parse(new zombie.core.network.ByteBufferReader(rangedWriter.bb),connection);
  check(!rangedWriter.bb.hasRemaining()&&rangedServer.isConsistent(connection),"ranged stock wire with optional tracer endpoint consumes exact payload");
  float beforeBullet=h.player.getHealth();rangedServer.processServer(PacketTypes.PacketType.PlayerHitPlayer,connection);
  check(h.player.getHealth()<beforeBullet&&module.aggressor()!=null,"parsed ranged client packet inflicts NPC damage and arms retaliation");p.setPrimaryHandItem(weapon);
  var invalid=new WeaponHit();invalid.damage=Float.NaN;
  check(!ServerHumanPlayerDamage.valid(List.of(invalid),weapon),"nonfinite client damage is rejected");
  check(!ServerHumanPlayerDamage.valid(Collections.nCopies(4,hit),weapon),"projectile count bounds incoming native rolls");
  float beforeShove=h.player.getHealth();var shove=new WeaponHit();shove.damage=0;
  ServerHumanPlayerDamage.apply(p,h.player,weapon,List.of(shove));check(h.player.getHealth()==beforeShove,"zero-damage shove does not invent a wound");
  var ignoredHit=new WeaponHit();ignoredHit.set(1,1,0,0,0,false,false,false,true);
  ServerHumanPlayerDamage.apply(p,h.player,weapon,List.of(ignoredHit));check(h.player.getHealth()==beforeShove,"native ignore-damage flag prevents positive-damage shove or ignored pellet wounds");
  for(int n=0;n<12;n++)ServerHumanPlayerDamage.apply(p,h.player,weapon,List.of(hit));
  check(h.state.snapshot().dead()&&h.player.getHealth()==0,"incoming body damage can kill durable NPC state");
  ServerHumanPlayerDamage.apply(p,h.player,weapon,List.of(hit));check(h.player.getHealth()==0,"death is terminal and later packets cannot revive");
  h.nextPlayerHitTrace=0;int logs=zombie.debug.DebugLog.messages.size();for(int n=0;n<50;n++)ServerHumanPlayerAttacks.trace(h.player,"trace limit test");
  check(zombie.debug.DebugLog.messages.size()==logs+8,"receive diagnostic output is bounded per NPC");
  System.setProperty("apocbr.npc.playerHitTrace","false");logs=zombie.debug.DebugLog.messages.size();ServerHumanPlayerAttacks.received();ServerHumanPlayerAttacks.missed();
  check(zombie.debug.DebugLog.messages.size()==logs,"receive traces can be disabled explicitly");System.clearProperty("apocbr.npc.playerHitTrace");
  factory.remove("retaliation-test");
 }
}
'''

def main():
    with tempfile.TemporaryDirectory(prefix='apocbr-retaliation-') as directory:
        work=Path(directory);fixture=dict(FILES)
        fixture.pop('zombie/network/packets/hit/PlayerHitPlayerPacket.java')
        fixture.update(STUBS)
        fixture['zombie/characters/IsoGameCharacter.java']=fixture['zombie/characters/IsoGameCharacter.java'].replace('public abstract boolean isDead();','public float realx,realy;public byte realz;public boolean isRemote(){return false;}public abstract boolean isDead();')
        fixture['zombie/inventory/types/HandWeapon.java']=fixture['zombie/inventory/types/HandWeapon.java'].replace('public boolean isRanged()', 'public String getName(){return getFullType();}public boolean isMelee(){return !isRanged();}public boolean isRanged()')
        fixture['zombie/characters/IsoPlayer.java']=fixture['zombie/characters/IsoPlayer.java'].replace('public float useChargeDelta;', 'public Object getHitReactionNetworkAI(){return null;}public boolean isRemote(){return remote;}public float useChargeDelta;')
        fixture['zombie/apocalipse/humans/RetaliationTest.java']=HARNESS
        fixture['zombie/apocalipse/humans/DialogueTest.java']=DIALOGUE
        fixture['zombie/apocalipse/humans/EncounterTest.java']=ENCOUNTERS
        from human_hit_wire_fixture import configure
        configure(fixture,ROOT)
        sources=[]
        for relative,content in fixture.items():
            path=work/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
        sources.extend(sorted((ROOT/'42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
        sources.extend([ROOT/'42.21.0/src/zombie/network/packets/hit/PlayerHitPlayerPacket.java',ROOT/'42.21.0/decompiled/zombie/network/anticheats/AntiCheatSafety.java'])
        args=work/'javac.args';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources),encoding='utf-8')
        game=Path('Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar')
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-encoding','UTF-8','-cp',str(game),'-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',str(work/'classes')+';'+str(game),'zombie.apocalipse.humans.RetaliationTest'],check=True)

if __name__=='__main__':main()
