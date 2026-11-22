"""Production encounter policy and native dialogue/order gates, without game/native startup."""
from pathlib import Path
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True
from test_server_humans import FILES, ROOT
from test_human_dialogue import HARNESS as DIALOGUE

HARNESS = r'''
package zombie.apocalipse.humans;
import java.util.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.encounters.*;
import zombie.apocalipse.humans.profiles.*;
import zombie.apocalipse.humans.tasks.*;
import zombie.characters.*;
import zombie.iso.*;
public class EncounterTest {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static final EncounterPolicy POLICY=new EncounterPolicy("test-militia",8,8000,1000,1000,300000,60000);
 static final HumanRelationships.Key KEY=new HumanRelationships.Key("test-militia","steam","123");
 static HumanObservation observation(long now){return new HumanObservation(now,List.of());}
 static final class Port implements EncounterPort,HumanActuator {
  final HumanRelationships memory; List<Player> players=new ArrayList<>();long wall=100000;
  int lines,stops;String last;boolean speech=true,face=true;
  Port(HumanRelationships memory){this.memory=memory;set(true,1);}
  void set(boolean armed,int instance){players=List.of(new Player(new HumanObservation.Identity((short)1,instance),KEY,4,armed,false));}
  public List<Player> players(){return players;}
  public HumanRelationships.State relationship(Player p){return memory.get(p.account());}
  public long wallTimeMs(){return wall;}
  public boolean claim(Player p,Object owner,long now){return memory.claim(p.account(),owner,now);}
  public void release(Player p,Object owner){memory.release(p.account(),owner);}
  public boolean record(Player p,HumanRelationships.Outcome outcome,EncounterPolicy policy){return memory.record(p.account(),"guard",outcome,wall,policy);}
  public String text(String event){return event;}
  public boolean face(HumanObservation.Identity id){return face;}
  public boolean say(String text){lines++;last=text;return speech;}
  public void stopMoving(){stops++;}public boolean startEmote(String name){return true;}public void stopEmote(){}public boolean returnHome(){return true;}
 }
 static PlayerEncounterModule module(Port p){var module=new PlayerEncounterModule(POLICY);module.bind(p);return module;}
 public static void main(String[] args){
  var memory=new HumanRelationships();var p=new Port(memory);var m=module(p);
  check(m.eligible(observation(1)),"armed stranger eligible");check(memory.pending().isEmpty()&&p.lines==0,"eligibility has no effects");
  m.update(observation(1),p);check(m.phase()==PlayerEncounterModule.Phase.WARNING&&p.lines==1&&p.last.equals("warning"),"one warning, no teleport or weapon removal");
  m.update(observation(500000001),p);check(p.lines==1,"warning is not repeated every tick");
  var other=new Port(memory);var second=module(other);check(!second.eligible(observation(500000001)),"shared cooldown suppresses second speaker");
  p.set(false,1);m.update(observation(1000000001),p);check(m.phase()==PlayerEncounterModule.Phase.VERIFYING,"equipment change starts verification");
  m.update(observation(1500000001),p);check(memory.get(KEY).complied()==0,"brief unequip is insufficient");
  p.set(true,1);m.update(observation(1600000001),p);p.set(false,1);m.update(observation(1700000001),p);
  m.update(observation(2700000001L),p);check(memory.get(KEY).complied()==1&&memory.get(KEY).permits(p.wall),"stable unequip grants passage");
  check(m.phase()==PlayerEncounterModule.Phase.IDLE&&p.last.equals("accepted"),"settled encounter releases execution");
  p.wall+=2000;check(!m.eligible(observation(4000000001L)),"known compliant visitor is not spammed");
  var refusedMemory=new HumanRelationships();var rp=new Port(refusedMemory);var rm=module(rp);
  rm.update(observation(1),rp);rm.update(observation(8000000001L),rp);
  check(refusedMemory.get(KEY).refused()==1&&!refusedMemory.get(KEY).permits(rp.wall),"refusal denies permission");
  check(rp.last.equals("refused"),"refusal speaks without attacking");
  check(rm.emergency(observation(9000000001L)),"armed state blocks exclusive lease even during cooldown");
  var disconnected=new HumanRelationships();var dp=new Port(disconnected);var dm=module(dp);dm.update(observation(1),dp);
  dp.set(false,2);dm.update(observation(1000000001),dp);
  check(disconnected.get(KEY).complied()==0&&disconnected.pending().getLast().outcome()==HumanRelationships.Outcome.LEFT,"replacement character cannot complete old encounter");
  var interrupted=new HumanRelationships();var ip=new Port(interrupted);var im=module(ip);im.update(observation(1),ip);
  im.suspend(2,ip);check(!im.eligible(observation(9000000001L))&&im.emergency(observation(9000000001L)),"suspension stops execution but not threat query");
  im.resume(null,9000000001L,ip);check(im.phase()==PlayerEncounterModule.Phase.IDLE&&interrupted.get(KEY).refused()==0,"interruption discards countdown without penalty");
  ip.speech=false;ip.wall+=2000;im.update(observation(10000000001L),ip);check(im.phase()==PlayerEncounterModule.Phase.IDLE,"failed speech cannot leave warning timer active");
  var restored=new HumanRelationships();restored.restore(Map.of(KEY,memory.get(KEY)));check(restored.get(KEY).permits(p.wall)&&restored.pending().isEmpty(),"restore retains semantic state without replaying outcomes");
  Object owner=new Object();check(restored.claim(KEY,owner,1)&&!restored.claim(KEY,new Object(),2),"one spokesperson per account and affiliation");
  check(restored.claim(KEY,new Object(),2000000002L),"abandoned speaker lease expires");
  int before=memory.pending().size();memory.staged(memory.pending().getFirst().id());check(memory.pending().size()==before-1,"worker acknowledges exactly one event");
  var bounded=new HumanRelationships();
  for(int n=0;n<HumanRelationships.PENDING_LIMIT;n++)if(!bounded.record(KEY,"guard",HumanRelationships.Outcome.GREETED,100000,POLICY))throw new AssertionError("queue refused before its bound");
  var previous=bounded.get(KEY);check(!bounded.record(KEY,"guard",HumanRelationships.Outcome.COMPLIED,100000,POLICY)&&bounded.get(KEY).equals(previous),"full queue refuses outcome without partial permission mutation");
  check(restored.get(new HumanRelationships.Key("other-militia","steam","123")).equals(HumanRelationships.State.EMPTY),"affiliations have independent memory");
  check(restored.get(new HumanRelationships.Key("test-militia","steam","124")).equals(HumanRelationships.State.EMPTY),"accounts have independent memory");
  nativeGates();System.out.println("Passed "+checks+" encounter, semantic memory, and native authority assertions.");
 }
 static void nativeGates(){
  var factory=ServerHumanFactory.getInstance();factory.tick(0);
  var base=ServerHumanProfiles.registry().require("roaming-greeter");
  var profile=new HumanProfile("encounter-test",1,"modules",Map.of(),"","none",true,false,
    List.of(new HumanModuleBinding("player-encounter",100,100,Map.of()),new HumanModuleBinding("assigned-task",200,100,Map.of()),new HumanModuleBinding("walk",1000,100,Map.of())),
    8,100,true,base.walkingPatterns(),base.cheats(),base.survival(),POLICY);
  ServerHumanProfiles.registry().register(profile);
  short id=factory.spawn(new ServerHumanDefinition("encounter-test","Guard test","Guard",100,200,0,IsoDirections.E,false,"Tourist","encounter-test"));
  var human=ServerHumanFactory.tradingHuman(id);var player=DialogueTest.visitor((short)1);player.x=101;
  check(ServerHumanDialogue.begin(player,id,System.nanoTime()).rawget("error")!=null,"unrecognized account cannot bypass greeting with menu");
  var key=ServerHumanEncounters.key(human,player);var mem=ServerHumanEncounters.memory();
  mem.record(key,"other-guard",HumanRelationships.Outcome.COMPLIED,System.currentTimeMillis(),POLICY);
  check(ServerHumanEncounters.authorized(human,player),"other militia member shares account permission");
  var lease=ServerHumanDialogue.begin(player,id,System.nanoTime());check(lease.rawget("token")!=null,"compliant account can converse");
  player.setSecondaryHandItem(new zombie.inventory.types.HandWeapon(1,"Base.Axe"));
  check(!ServerHumanTasks.accepts(human,new HumanTaskCommand(HumanTaskCommand.Kind.HOLD_POSITION,Map.of()),player),"secondary-hand weapon prevents task authority");
  ServerHumanDialogue.update(human,System.nanoTime());check(human.activity.mode()==HumanActivityController.Mode.AUTONOMOUS,"rearming interrupts dialogue");
  player.setSecondaryHandItem(null);player.setPrimaryHandItem(new zombie.inventory.InventoryItem(2,"Base.WaterBottle"));
  check(ServerHumanEncounters.authorized(human,player),"ordinary held item is not a weapon");
  mem.record(key,"other-guard",HumanRelationships.Outcome.REFUSED,System.currentTimeMillis(),POLICY);
  check(!ServerHumanEncounters.authorized(human,player),"refusal remains active after unequipping");
  factory.remove("encounter-test");
 }
}
'''

def main():
    with tempfile.TemporaryDirectory(prefix='apocbr-encounters-') as directory:
        work=Path(directory)
        fixture=dict(FILES)
        fixture['zombie/apocalipse/humans/EncounterTest.java']=HARNESS
        fixture['zombie/apocalipse/humans/DialogueTest.java']=DIALOGUE
        sources=[]
        for relative, content in fixture.items():
            path=work/relative
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(content,encoding='utf-8')
            sources.append(path)
        sources.extend(sorted((ROOT/'42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
        args=work/'javac.args'
        args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources),encoding='utf-8')
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-encoding','UTF-8','-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',str(work/'classes'),'zombie.apocalipse.humans.EncounterTest'],check=True)

if __name__=='__main__':
    main()
