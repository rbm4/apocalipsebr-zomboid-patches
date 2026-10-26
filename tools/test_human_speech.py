"""Production speech policy, semantic-memory and native presentation boundary regression tests."""
from pathlib import Path
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True
from test_server_humans import ROOT, FILES

HARNESS = r'''
package zombie.apocalipse.humans;
import java.util.*;
import zombie.apocalipse.humans.speech.*;
import zombie.apocalipse.humans.brain.*;
import zombie.characters.*;
import zombie.iso.*;
import zombie.network.*;
import zombie.network.chat.*;
import zombie.network.packets.INetworkPacket;
import zombie.core.raknet.UdpConnection;
public class SpeechTest {
 static int checks;
 static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
 static SpeechContext c(String account,float hp,float damage){return new SpeechContext(new SpeechContext.Player("username",account,account),hp,damage,SpeechContext.Temperament.STEADY,SpeechContext.AttackKind.MELEE,1,false);}
 static HumanSpeechEngine engine(String npc,HumanSpeechMemory memory,double random){return new HumanSpeechEngine(npc,"EN",memory,HumanSpeechDefaults::catalog,()->random);}
 static void policy(){
  var memory=new HumanSpeechMemory();var e=engine("guard",memory,0);long wall=100000;
  var a=e.onAttackedByPlayer(c("Alice",1,0),0,wall);
  check(a.speaks()&&a.hey()&&a.event()==SpeechEvent.FIRST_HIT&&a.text().contains("Alice"),"first attack produces named guaranteed opening");
  check(a.reaction()==HumanSpeechEngine.Reaction.DEFEND,"reaction available independently of delivery");
  var repeat=e.onAttackedByPlayer(c("Alice",1,0),1,wall+1);
  check(repeat.event()==SpeechEvent.REPEATED_HIT&&!repeat.speaks()&&repeat.reaction()==HumanSpeechEngine.Reaction.DEFEND,"throttle does not lose repeated-hit classification");
  var b=e.onAttackedByPlayer(c("Bob",1,0),2,wall+2);
  check(b.event()==SpeechEvent.FIRST_HIT,"different attacker has independent first-hit history");
  check(e.delivered(a),"delivered line enters memory");
  var newer=e.onAttackedByPlayer(c("Alice",1,0),7_000_000_000L,wall+3);
  check(newer.speaks()&&!newer.hey()&&!newer.messageId().equals(a.messageId()),"recent line avoided and repeat does not yell hey again");
  var persisted=new HashMap<HumanSpeechMemory.Key,HumanSpeechMemory.State>();for(var change:memory.pending())persisted.put(change.key(),change.state());
  var restored=new HumanSpeechMemory();restored.restore(persisted);
  check(engine("guard",restored,0).onAttackedByPlayer(c("Alice",1,0),0,wall+4).event()==SpeechEvent.REPEATED_HIT,"account familiarity survives restart");
  check(engine("guard",restored,0).onAttackedByPlayer(c("Alice",1,0),0,wall+4+HumanSpeechMemory.ATTACK_TTL_MS).event()==SpeechEvent.FIRST_HIT,"24-hour inactivity expiry");
  check(engine("other",restored,0).onAttackedByPlayer(c("Alice",1,0),0,wall+5).event()==SpeechEvent.FIRST_HIT,"NPC memory isolation");
  check(restored.get(new HumanSpeechMemory.Key("guard","npc","_")).recent().contains(a.messageId()),"recent delivered IDs survive hydration");
  check(engine("guard",restored,0).onAttackedByPlayer(c("Alice",1,0),0,1).event()==SpeechEvent.REPEATED_HIT,"backward wall clock preserves familiarity");
  var stale=memory.pending().getFirst();memory.attacked(stale.key(),wall+10);memory.staged(stale);
  check(memory.pending().stream().anyMatch(v->v.key().equals(stale.key())),"staging old snapshot cannot discard concurrent mutation");
  check(engine("hurt",new HumanSpeechMemory(),0).onDamageTaken(c("Alice",1,0),0).status()==HumanSpeechEngine.Status.NO_CONTENT,"zero health loss does not invent pain");
  check(engine("dead",new HumanSpeechMemory(),0).onAttackedByPlayer(c("Alice",0,1),0,wall).status()==HumanSpeechEngine.Status.DEAD,"dead actors do not remember or speak");
  var chance=engine("quiet",new HumanSpeechMemory(),.99);
  var quiet=chance.onAttackPlayer(c("Alice",1,0),0);
  check(quiet.status()==HumanSpeechEngine.Status.CHANCE&&quiet.reaction()==HumanSpeechEngine.Reaction.DEFEND,"failed chance keeps reaction result");
  check(chance.onAttackPlayer(c("Alice",1,0),1).status()==HumanSpeechEngine.Status.COOLDOWN,"chance not rolled each update");
  var priorities=engine("priorities",new HumanSpeechMemory(),0);
  check(priorities.onAttackPlayer(c("Alice",1,0),0).speaks()&&priorities.onDamageTaken(c("Alice",.9f,.1f),1).speaks(),"urgent injury bypasses lower priority global gap");
  check(priorities.onDamageTaken(c("Alice",.8f,.1f),2).status()==HumanSpeechEngine.Status.COOLDOWN,"priority never bypasses same-family cooldown");
  check(engine("stub",new HumanSpeechMemory(),0).onEvent(SpeechEvent.RELOAD,c("Alice",1,0),0).status()==HumanSpeechEngine.Status.NO_CONTENT,"unconfigured extension hook reports missing content");
  var fresh=new SpeechContext(c("Alice",1,0).player(),1,0,SpeechContext.Temperament.FRESH,SpeechContext.AttackKind.MELEE,1,false);
  check(engine("fresh",new HumanSpeechMemory(),0).onAttackedByPlayer(fresh,0,wall).reaction()==HumanSpeechEngine.Reaction.PLEAD,"fresh first-hit reaction hook");
  var aggressor=new SpeechContext(c("Alice",1,0).player(),.3f,.1f,SpeechContext.Temperament.VETERAN,SpeechContext.AttackKind.MELEE,1,true);
  var retreat=engine("veteran",new HumanSpeechMemory(),0).onAttackedByPlayer(aggressor,0,wall);
  check(retreat.reaction()==HumanSpeechEngine.Reaction.RETREAT&&!retreat.hey(),"initiating veteran does not falsely accuse defender");
  var civilian=new SpeechContext(aggressor.player(),.3f,.1f,SpeechContext.Temperament.FRESH,SpeechContext.AttackKind.MELEE,1,true);
  check(engine("civilian",new HumanSpeechMemory(),0).onAttackedByPlayer(civilian,0,wall).reaction()==HumanSpeechEngine.Reaction.SURRENDER,"losing civilian surrender hook");
  for(var event:SpeechEvent.values())check(engine("extension",new HumanSpeechMemory(),0).onEvent(event,c("Alice",1,.1f),0)!=null,"typed hook: "+event);
  var unsafe=new SpeechContext(new SpeechContext.Player("username","id","<RGB:1,0,0>[Alice]\n"),1,0,SpeechContext.Temperament.STEADY,SpeechContext.AttackKind.MELEE,1,false);
  var rendered=engine("markup",new HumanSpeechMemory(),0).onAttackedByPlayer(unsafe,0,wall).text();
  check(!rendered.contains("<")&&!rendered.contains("[")&&!rendered.contains("\n"),"username cannot inject chat markup");
  for(var event:List.of(SpeechEvent.ATTACK_PLAYER,SpeechEvent.ATTACKED_BY_PLAYER,SpeechEvent.ZOMBIE_KILL,SpeechEvent.DAMAGE_TAKEN))
   for(String locale:List.of("EN","PTBR"))check(HumanSpeechDefaults.catalog().lines(event,locale).size()>=20,"20 authored variants per pool and locale");
  var key=new HumanSpeechMemory.Key("bounded","npc","_");for(int i=0;i<100;i++)memory.remember(key,"line."+i);
  check(memory.get(key).recent().size()==40&&memory.get(key).recent().getFirst().equals("line.60"),"recent history bounded to newest 40");
  var full=new HumanSpeechMemory();for(int i=0;i<HumanSpeechMemory.PENDING_LIMIT;i++)full.attacked(new HumanSpeechMemory.Key("full","username","p"+i),1);
  check(!full.attacked(new HumanSpeechMemory.Key("full","username","overflow"),1).accepted(),"backpressure rejects growth explicitly");
 }
 static void presentation(){
  var h=new ServerHuman(new ServerHumanDefinition("speech-test","__speech","Guide",100,200,0,IsoDirections.E,false,"Tourist"),(short)1200,IsoWorld.instance.currentCell);
  var viewer=new UdpConnection();var p=new IsoPlayer(IsoWorld.instance.currentCell,new SurvivorDesc(false),0,0,0);
  p.id=7;p.instanceId=70;p.setUsername("Alice");p.x=101;p.y=200;viewer.players[0]=p;viewer.players[1]=p;
  h.viewers.add(viewer);GameServer.IDToPlayerMap.put(p.id,p);ChatServer.players.add(p.id);
  zombie.chat.ChatBase.sent.clear();INetworkPacket.messages.clear();
  var result=h.speech.attacked(p,null,0);check(result.speaks(),"native adapter queues opening");h.speech.update(0);
  check(zombie.chat.ChatBase.sent.size()==1&&zombie.chat.ChatBase.sent.getFirst().text.equals("Ei!"),"opening shout once per split-screen connection");
  check(INetworkPacket.messages.stream().filter(m->m.type()==PacketTypes.PacketType.PlaySound&&m.args()[0].equals("ShoutHey")&&(byte)m.args()[1]==2).count()==1,"native vocal packet uses suffix and vocal flag");
  check(h.player.noises==1,"hey creates authoritative zombie-hearing stimulus");
  h.speech.update(349_000_000L);check(zombie.chat.ChatBase.sent.size()==1,"accusation waits");
  h.speech.update(350_000_000L);check(zombie.chat.ChatBase.sent.size()==2&&zombie.chat.ChatBase.sent.getLast().text.contains("Alice"),"named accusation after delay");
  h.speech.update(351_000_000L);check(zombie.chat.ChatBase.sent.size()==2,"completed sequence cannot replay");
  p.x=161;check(!ServerHumanTransport.shout(h,"far"),"configured shout range enforced");p.x=101;p.z=1;check(!ServerHumanTransport.shout(h,"floor"),"floor filtering");p.z=0;
  var crowbar=new zombie.inventory.types.HandWeapon(500,"Base.Crowbar");INetworkPacket.messages.clear();ServerHumanCombatAudio.impact(h,p,crowbar);
  var impact=INetworkPacket.messages.getLast();check(impact.type()==PacketTypes.PacketType.PlaySound&&impact.args()[3]==zombie.audio.parameters.ParameterMeleeHitSurface.Material.Body,"melee impact carries native body material");
  zombie.chat.ChatUtility.enabled=false;check(!ServerHumanTransport.shout(h,"disabled"),"disabled chat stream respected");zombie.chat.ChatUtility.enabled=true;
  zombie.chat.ChatBase.sent.clear();p.id=8;p.instanceId=80;p.setUsername("Bob");GameServer.IDToPlayerMap.put(p.id,p);ChatServer.players.add(p.id);
  h.speech.attacked(p,null,7_000_000_000L);h.speech.update(7_000_000_000L);h.speech.cancel();h.speech.update(8_000_000_000L);
  check(zombie.chat.ChatBase.sent.size()==1,"cancel between hey and accusation drops pending line");
  p.id=9;p.instanceId=90;p.setUsername("Carol");GameServer.IDToPlayerMap.put(p.id,p);ChatServer.players.add(p.id);
  zombie.chat.ChatBase.sent.clear();h.speech.attacked(p,null,14_000_000_000L);p.instanceId=91;h.speech.update(14_000_000_000L);
  check(zombie.chat.ChatBase.sent.isEmpty(),"recycled player identity cancels opening before delivery");
  p.id=10;p.instanceId=100;p.setUsername("Dave");GameServer.IDToPlayerMap.put(p.id,p);ChatServer.players.add(p.id);
  h.speech.attacked(p,null,21_000_000_000L);h.speech.update(24_000_000_000L);
  check(zombie.chat.ChatBase.sent.isEmpty(),"stale queued reaction expires instead of replaying late");
  p.id=11;p.instanceId=110;p.setUsername("Eve");GameServer.IDToPlayerMap.put(p.id,p);ChatServer.players.add(p.id);
  h.speech.attacked(p,null,28_000_000_000L);h.activity.beginDialogue(new HumanObservation.Identity(p.id,p.instanceId),28_000_000_000L,h.actuator);h.speech.update(28_000_000_000L);
  check(zombie.chat.ChatBase.sent.isEmpty(),"exclusive dialogue cancels pending combat speech");
 }
 public static void main(String[] args){policy();presentation();System.out.println("Passed "+checks+" speech policy, memory, lifecycle and sound/chat assertions.");}
}
'''

def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-speech-tests-") as directory:
        work=Path(directory);sources=[]
        fixture=dict(FILES);fixture['zombie/apocalipse/humans/SpeechTest.java']=HARNESS
        for name,content in fixture.items():
            p=work/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8');sources.append(p)
        sources.extend(sorted((ROOT/'42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
        args=work/'javac.args';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources),encoding='utf-8')
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-encoding','UTF-8','-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',str(work/'classes'),'zombie.apocalipse.humans.SpeechTest'],check=True)

if __name__=='__main__':main()
