"""Production death/loot/lifecycle scheduling with native world and persistence boundaries stubbed."""
from pathlib import Path
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True
from test_server_humans import FILES, ROOT
from test_human_dialogue import HARNESS as DIALOGUE

HARNESS = r'''
package zombie.apocalipse.humans;
import java.util.*;import zombie.apocalipse.humans.death.*;import zombie.apocalipse.humans.death.HumanDeathPolicy.*;
import zombie.apocalipse.humans.state.*;import zombie.apocalipse.humans.profiles.*;import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.encounters.*;import zombie.iso.*;import zombie.iso.objects.*;
public class DeathRespawnTest {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static void invalid(Runnable r){try{r.run();throw new AssertionError("accepted invalid transition");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
 static Random random(int... values){return new Random(1){int i;public int nextInt(int bound){return Math.floorMod(values[i++%values.length],bound);}};}
 static HumanDeathPolicy policy(boolean enabled,LootTable table){return new HumanDeathPolicy("test",enabled,1000,table);}
 static ServerHumanDefinition definition(String key,HumanDeathPolicy p){return new ServerHumanDefinition(key,key,key,100,200,0,IsoDirections.E,false,"Tourist","respawn-test",p);}
 static void pure(){
  var entries=new ArrayList<Entry>();for(var tier:Rarity.values())entries.add(new Entry(tier,"Base."+tier,1,1,1));
  var table=new LootTable("test",1,List.of(6000,3000,900,100),entries);entries.clear();
  var counts=new int[4];for(int i=0;i<10000;i++){var drop=table.roll(random(i,0,0)).getFirst();counts[Rarity.valueOf(drop.item().substring(5)).ordinal()]++;}
  check(Arrays.equals(counts,new int[]{6000,3000,900,100}),"rarity thresholds exactly implement 60/30/9/1");
  check(LootTable.EMPTY.roll(random(9999)).isEmpty(),"empty tier has no fallback or forced reward");
  var weighted=new LootTable("weighted",1,List.of(10000,0,0,0),List.of(new Entry(Rarity.COMMON,"Base.A",1,2,4),new Entry(Rarity.COMMON,"Base.B",3,1,1)));
  check(weighted.roll(random(0,0,2)).getFirst().equals(new Drop("Base.A",4)),"within-tier weight and bounded quantity");
  check(weighted.roll(random(0,1,0)).getFirst().item().equals("Base.B"),"relative weight boundary selects second item");
  invalid(()->new LootTable("bad",1,List.of(60,30,9,1),List.of()));invalid(()->new Entry(Rarity.COMMON,"not-module-qualified",1,1,1));
  var l=new HumanLifecycle(definition("pure",policy(true,LootTable.EMPTY)));l.spawning();l.active(1);l.physiology().dispatch(new HumanMutation.Kill());l.dead(1000);
  check(l.state()==HumanLifecycle.State.DEAD&&l.death().respawnAtMs()==2000,"death owns persisted epoch deadline");
  invalid(l::spawning);invalid(()->l.respawning(1999));long revision=l.physiology().snapshot().revision();l.respawning(2000);
  check(!l.physiology().snapshot().dead()&&l.physiology().snapshot().revision()>revision,"new incarnation has healthy state and increasing revision");
  l.spawnFailed();check(l.state()==HumanLifecycle.State.DEAD&&l.physiology().snapshot().dead()&&l.death().respawnAtMs()==2000,"construction failure preserves death and original deadline");
  l.respawning(2001);l.active(2);check(l.activations()==2&&l.death().equals(HumanDeathState.NONE),"successful respawn commits fresh incarnation and clears old deadline");
  var dead=HumanDeathState.died(1000,policy(false,LootTable.EMPTY));check(dead.respawnAtMs()==0&&!dead.due(Long.MAX_VALUE),"disabled respawn has no deadline");
 }
 public static void main(String[] args)throws Exception{
  pure();var f=ServerHumanFactory.getInstance();f.tick(0);
  var base=ServerHumanProfiles.registry().require("roaming-greeter");var encounter=new EncounterPolicy("militia",8,16000,1000,60000,300000,60000);
  ServerHumanProfiles.registry().register(new HumanProfile("respawn-test",1,"modules",Map.of(),"","none",true,false,
   List.of(new HumanModuleBinding("retaliate-player",20,100,Map.of()),new HumanModuleBinding("player-encounter",100,100,Map.of()),new HumanModuleBinding("walk",1000,100,Map.of())),
   8,100,true,base.walkingPatterns(),new HumanCheats(false,false,false),base.survival(),encounter));
  var loot=new LootTable("test",1,List.of(10000,0,0,0),List.of(new Entry(Rarity.COMMON,"Base.Bandage",1,2,2)));
  short id=f.spawn(definition("guard",policy(true,loot)));var old=ServerHumanFactory.tradingHuman(id);
  var killer=DialogueTest.visitor((short)1);killer.x=101;old.player.setAttackedBy(killer);old.inventory.weapons();
  var reputation=new ArrayList<HumanReputationPort.Kill>();f.reputationPort(reputation::add);
  int bodies=IsoDeadBody.created.size();int packets=zombie.network.GameServer.corpsesSent;
  check(f.markDead("guard"),"death accepted once");check(!f.markDead("guard"),"duplicate death rejected");
  var deathField=ServerHumanFactory.class.getDeclaredField("instances");deathField.setAccessible(true);
  var logical=((Map<String,HumanLifecycle>)deathField.get(f)).get("guard");var death=logical.death();
  check(death.respawnAtMs()-death.diedAtMs()==1000,"authored respawn delay used");
  check(ServerHumanFactory.tradingHuman(id)==null&&ServerHumanFactory.dialogueHumans().isEmpty()&&f.perception("guard")==null,"DEAD has no active packet/interaction/perception routing");
  check(IsoDeadBody.created.size()==bodies+1&&zombie.network.GameServer.corpsesSent==packets+1,"native corpse constructed and published once");
  var corpse=IsoDeadBody.created.getLast();check(corpse.getContainer().items.size()==2,"corpse has rolled loot without fixed combat test supplies");
  check(corpse.getContainer().items.stream().allMatch(i->i.getFullType().equals("Base.Bandage")),"rolled item instances are ordinary corpse contents");
  check(corpse.getModData().rawget("apocbr.npc.deathId").equals(death.deathId()),"corpse records stable death identifier");
  check(reputation.size()==1&&reputation.getFirst().killer().affiliation().equals("militia"),"single typed killer-affiliation reputation hook");
  check(ServerHumanEncounters.memory().pending().stream().anyMatch(e->e.outcome()==HumanRelationships.Outcome.KILLED_NPC),"kill relationship outcome captured for worker persistence");
  var due=ServerHumanFactory.class.getDeclaredMethod("respawnDue",long.class);due.setAccessible(true);due.invoke(f,death.respawnAtMs()-1);
  check(f.lifecycle("guard").state()==HumanLifecycle.State.DEAD,"no early respawn");
  var next=ServerHumanFactory.class.getDeclaredField("nextId");next.setAccessible(true);int savedNext=next.getInt(f);next.setInt(f,ServerHumanFactory.LAST_ID+1);
  due.invoke(f,death.respawnAtMs());check(f.lifecycle("guard").state()==HumanLifecycle.State.DEAD&&f.state("guard").dead(),"failed native activation rolls back to dead and retries");
  next.setInt(f,savedNext);due.invoke(f,death.respawnAtMs()+1001);
  var fresh=ServerHumanFactory.dialogueHumans().iterator().next();
  check(fresh!=old&&fresh.player!=old.player&&fresh.player.getOnlineID()!=id,"respawn uses a fresh native actor and presentation ID");
  check(fresh.player.getX()==100&&fresh.player.getY()==200&&f.state("guard").vitals().health()==1,"respawn restores healthy state at authored home");
  check(f.lifecycle("guard").activations()==2&&fresh.inventory.weapons().stream().anyMatch(w->w.ranged()&&w.rounds()>0),"new incarnation receives a fresh finite combat inventory");
  check(IsoDeadBody.created.contains(corpse)&&reputation.size()==1,"respawn keeps the old corpse and does not replay kill reputation");
  due.invoke(f,death.respawnAtMs()+2000);check(f.lifecycle("guard").activations()==2,"consumed timer cannot respawn twice");
  for(int n=0;n<70;n++){f.markDead("guard");due.invoke(f,logical.death().respawnAtMs()+1001);}
  check(f.lifecycle("guard").activations()==72,"repeated respawns exceed the former 64-lifetime-ID limit safely");
  short disabled=f.spawn(definition("disabled",policy(false,LootTable.EMPTY)));f.markDead("disabled");due.invoke(f,Long.MAX_VALUE);
  check(f.lifecycle("disabled").state()==HumanLifecycle.State.DEAD&&ServerHumanFactory.tradingHuman(disabled)==null,"disabled NPC remains dead and outside networking");
  f.remove("guard");f.remove("disabled");System.out.println("Passed "+checks+" mortality, loot, corpse, reputation and respawn assertions.");
 }
}
'''

with tempfile.TemporaryDirectory(prefix='apocbr-respawn-') as directory:
    work=Path(directory);fixture=dict(FILES)
    fixture['zombie/apocalipse/humans/DeathRespawnTest.java']=HARNESS
    fixture['zombie/apocalipse/humans/DialogueTest.java']=DIALOGUE
    sources=[]
    for name,content in fixture.items():
        path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
    sources.extend(sorted((ROOT/'42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
    args=work/'sources.args';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources),encoding='utf-8')
    subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-encoding','UTF-8','-d',str(work/'classes'),'@'+str(args)],check=True)
    subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',str(work/'classes'),'zombie.apocalipse.humans.DeathRespawnTest'],check=True)
