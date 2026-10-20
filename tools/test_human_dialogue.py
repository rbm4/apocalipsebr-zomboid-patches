"""Execute production NPC dialogue leases/bridge with small engine boundary stubs."""
from pathlib import Path
import subprocess
import sys
import tempfile
sys.dont_write_bytecode = True
from test_server_humans import FILES, ROOT

HARNESS = r"""
package zombie.apocalipse.humans;
import zombie.characters.*;
import zombie.iso.*;
import zombie.network.*;
import zombie.core.raknet.*;
import zombie.apocalipse.humans.brain.*;
import zombie.Lua.*;
import se.krka.kahlua.vm.*;
public class DialogueTest {
 static int checks;
 static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static IsoPlayer visitor(short id) {
  var c=new UdpConnection();
  var p=new IsoPlayer(IsoWorld.instance.currentCell,new SurvivorDesc(false),0,0,0);
  p.id=id;p.instanceId=id;p.x=104;p.y=200;p.username="real"+id;
  GameServer.IDToPlayerMap.put(id,p);GameServer.Players.add(p);GameServer.owners.put(p,c);
  c.players[0]=p;GameServer.udpEngine.connections.add(c);return p;
 }
 static int token(KahluaTable t){return ((Number)t.rawget("token")).intValue();}
 public static void main(String[] ignored) {
  var factory=ServerHumanFactory.getInstance();factory.tick(0);
  short id=factory.spawn(new ServerHumanDefinition("dialogue","Dialog NPC","NPC",100,200,0,IsoDirections.E,false,"Tourist"));
  var h=ServerHumanFactory.tradingHuman(id); var p=visitor((short)1);var other=visitor((short)2);
  long now=System.nanoTime();
  var api=(KahluaTable)LuaManager.env.rawget("ApocBRHumanAPI");
  check(api!=null,"bridge installed on simulation thread");
  check(ServerHumanDialogue.list(p).values.size()==1,"nearby registered actors discoverable");
  var lease=ServerHumanDialogue.begin(p,id,now);int token=token(lease);
  check(h.brain.state()==GuideBrain.State.DIALOGUE&&!h.moving,"reservation stops roaming");
  check(ServerHumanDialogue.begin(other,id,now).rawget("error").equals("busy"),"NPC reserved exclusively");
  check(!h.brain.beginTrade(new HumanObservation.Identity(p.id,p.instanceId),now,h.actuator),"native trade cannot steal dialogue lease");
  check(!ServerHumanDialogue.arrive(p,id,token,now),"distant player cannot arrive");
  check(!ServerHumanDialogue.react(p,id,token,"hello",null,now),"cannot react before arrival");
  check(!ServerHumanDialogue.arrive(other,id,token,now),"another player cannot use token");
  p.x=101;
  check(!ServerHumanDialogue.arrive(p,id,token+1,now),"stale token rejected");
  check(ServerHumanDialogue.arrive(p,id,token,now),"arrival accepted nearby");
  check(ServerHumanDialogue.react(p,id,token,"hello","wavehi",now),"server registered speech/emote executes");
  check("wavehi".equals(h.emote),"reaction emote active");
  check(!ServerHumanDialogue.react(p,id,token,"x".repeat(257),null,now),"oversized speech rejected");
  ServerHumanDialogue.update(h,now+3_000_000_000L);
  check(h.emote==null&&h.brain.state()==GuideBrain.State.DIALOGUE,"emote settles without resuming roaming");
  p.z=1;check(!ServerHumanDialogue.react(p,id,token,"hello",null,now),"wrong floor rejected");
  ServerHumanDialogue.update(h,now+4_000_000_000L);p.z=0;
  check(h.brain.state()==GuideBrain.State.RETURNING_HOME,"moving away releases actor");
  check(!ServerHumanDialogue.arrive(p,id,token,now),"closed lease cannot be replayed");
  var next=ServerHumanDialogue.begin(other,id,now);int nextToken=token(next);
  check(nextToken!=token,"new lease has new token");
  ServerHumanDialogue.update(h,now+31_000_000_000L);
  check(h.brain.state()==GuideBrain.State.RETURNING_HOME,"failed path expires approach lease");
  other.x=101;nextToken=token(ServerHumanDialogue.begin(other,id,now));
  check(ServerHumanDialogue.arrive(other,id,nextToken,now),"new dialogue opens after expiration");
  ServerHumanDialogue.update(h,now+61_000_000_000L);
  check(h.brain.state()==GuideBrain.State.RETURNING_HOME,"idle conversation expires");
  nextToken=token(ServerHumanDialogue.begin(other,id,now));
  GameServer.owners.get(other).connected=false;ServerHumanDialogue.update(h,now);
  check(h.brain.state()==GuideBrain.State.RETURNING_HOME,"disconnect releases dialogue");
  GameServer.owners.get(other).connected=true;
  nextToken=token(ServerHumanDialogue.begin(other,id,now));
  factory.remove("dialogue");check(h.brain.state()==GuideBrain.State.STOPPED,"despawn closes dialogue");
  check(!ServerHumanDialogue.arrive(other,id,nextToken,now),"removed actor token invalid");
  check(LuaEventManager.events.contains("OnApocBRHumanDialogueReserved")
   &&LuaEventManager.events.contains("OnApocBRHumanDialogueOpened")
   &&LuaEventManager.events.contains("OnApocBRHumanDialogueClosed"),"lifecycle events delivered");
  var invalid=new LuaCallFrame(other,-1.0,1.0);
  ((JavaFunction)api.rawget("arrive")).call(invalid,3);
  check(Boolean.FALSE.equals(invalid.result),"Lua bridge rejects invalid actor ID");
  System.out.println("Passed "+checks+" dialogue authority, lifecycle and bridge assertions.");
 }
}
"""

def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-dialogue-tests-") as directory:
        work=Path(directory)
        fixture=dict(FILES)
        fixture["zombie/apocalipse/humans/DialogueTest.java"]=HARNESS
        sources=[]
        for relative,content in fixture.items():
            path=work/relative;path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(content,encoding="utf-8");sources.append(path)
        sources.extend(sorted((ROOT/"42.21.0/src/zombie/apocalipse/humans").rglob("*.java")))
        args=work/"javac.args"
        args.write_text("\n".join('"'+str(p).replace("\\","/")+'"' for p in sources),encoding="utf-8")
        output=work/"classes"
        subprocess.run([str(ROOT/"jdk/bin/javac.exe"),"--release","25","-encoding","UTF-8","-d",str(output),"@"+str(args)],check=True)
        subprocess.run([str(ROOT/"jdk/bin/java.exe"),"-cp",str(output),"zombie.apocalipse.humans.DialogueTest"],check=True)

if __name__=="__main__":main()
