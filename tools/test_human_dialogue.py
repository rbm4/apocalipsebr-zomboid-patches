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
  check(Double.valueOf(4).equals(api.rawget("version")),"server-owned dialogue bridge advertises v4");
  check(LuaEventManager.events.contains("OnApocBRHumanRosterChanged"),"spawn emits roster change");
  int changes=java.util.Collections.frequency(LuaEventManager.events,"OnApocBRHumanRosterChanged");
  check(!factory.remove("missing")&&java.util.Collections.frequency(LuaEventManager.events,"OnApocBRHumanRosterChanged")==changes,"missing removal does not emit changes");
  p.x=500;p.z=1;
  var full=ServerHumanDialogue.roster();
  check(full.values.size()==1&&ServerHumanDialogue.list(p).values.isEmpty(),"world roster independent of player range and floor");
  var publicActor=(KahluaTable)full.values.values().iterator().next();
  check(publicActor.rawget("x")==null&&publicActor.rawget("z")==null&&publicActor.rawget("token")==null,"roster excludes positions and session state");
  var rosterCall=new LuaCallFrame();
  ((JavaFunction)api.rawget("roster")).call(rosterCall,0);
  check(rosterCall.result instanceof KahluaTable,"zero-argument roster facade callable");
  p.x=104;p.z=0;
  check(ServerHumanDialogue.list(p).values.size()==1,"nearby registered actors discoverable");
  var lease=ServerHumanDialogue.begin(p,id,now);int token=token(lease);
  check(h.activity.mode()==HumanActivityController.Mode.DIALOGUE&&!h.movement.moving(),"reservation stops roaming");
  check(ServerHumanDialogue.begin(other,id,now).rawget("error").equals("busy"),"NPC reserved exclusively");
  check(!h.activity.beginTrade(new HumanObservation.Identity(p.id,p.instanceId),now,h.actuator),"native trade cannot steal dialogue lease");
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
  check(h.emote==null&&h.activity.mode()==HumanActivityController.Mode.DIALOGUE,"emote settles without resuming roaming");
  p.z=1;check(!ServerHumanDialogue.react(p,id,token,"hello",null,now),"wrong floor rejected");
  ServerHumanDialogue.update(h,now+4_000_000_000L);p.z=0;
  check((h.activity.mode()==HumanActivityController.Mode.AUTONOMOUS&&((HumanModuleRuntime)h.activity.behavior()).selectedModule()==null),"moving away releases actor");
  check(!ServerHumanDialogue.arrive(p,id,token,now),"closed lease cannot be replayed");
  var next=ServerHumanDialogue.begin(other,id,now);int nextToken=token(next);
  check(nextToken!=token,"new lease has new token");
  ServerHumanDialogue.update(h,now+31_000_000_000L);
  check((h.activity.mode()==HumanActivityController.Mode.AUTONOMOUS&&((HumanModuleRuntime)h.activity.behavior()).selectedModule()==null),"failed path expires approach lease");
  other.x=101;nextToken=token(ServerHumanDialogue.begin(other,id,now));
  check(ServerHumanDialogue.arrive(other,id,nextToken,now),"new dialogue opens after expiration");
  ServerHumanDialogue.update(h,now+61_000_000_000L);
  check((h.activity.mode()==HumanActivityController.Mode.AUTONOMOUS&&((HumanModuleRuntime)h.activity.behavior()).selectedModule()==null),"idle conversation expires");
  nextToken=token(ServerHumanDialogue.begin(other,id,now));
  GameServer.owners.get(other).connected=false;ServerHumanDialogue.update(h,now);
  check((h.activity.mode()==HumanActivityController.Mode.AUTONOMOUS&&((HumanModuleRuntime)h.activity.behavior()).selectedModule()==null),"disconnect releases dialogue");
  GameServer.owners.get(other).connected=true;
  nextToken=token(ServerHumanDialogue.begin(other,id,now));
  factory.remove("dialogue");check(h.activity.mode()==HumanActivityController.Mode.STOPPED,"despawn closes dialogue");
  check(!ServerHumanDialogue.arrive(other,id,nextToken,now),"removed actor token invalid");
  check(ServerHumanDialogue.roster().values.isEmpty(),"removed actor absent from world roster");
  check(java.util.Collections.frequency(LuaEventManager.events,"OnApocBRHumanRosterChanged")==changes+1,"despawn emits one roster change after removal");
  check(LuaEventManager.events.contains("OnApocBRHumanDialogueReserved")
   &&LuaEventManager.events.contains("OnApocBRHumanDialogueOpened")
   &&LuaEventManager.events.contains("OnApocBRHumanDialogueClosed"),"lifecycle events delivered");
  var invalid=new LuaCallFrame(other,-1.0,1.0);
  ((JavaFunction)api.rawget("arrive")).call(invalid,3);
  check(Boolean.FALSE.equals(invalid.result),"Lua bridge rejects invalid actor ID");
  graphTests(factory,other,now);
  System.out.println("Passed "+checks+" dialogue authority, lifecycle and bridge assertions.");
 }
 static java.util.Map<String,Object> text(String title,String speech) {return java.util.Map.of("title",title,"description","details","response",title,"speech",speech);}
 static java.util.Map<String,Object> choice(String id,String next) {return java.util.Map.of("id",id,"nextNode",next,"texts",java.util.Map.of("EN",text("English","English speech"),"PTBR",text("Portuguese","Portuguese speech")),"close",false);}
 static void graphTests(ServerHumanFactory factory,IsoPlayer p,long now) {
  var rootChoice=choice("ask","more");var back=choice("back","root");
  var blocked=new java.util.HashMap<>(choice("secret","root"));blocked.put("requiredFlag","secret");
  var nodeTexts=java.util.Map.of("EN",text("English node",""),"PTBR",text("Portuguese node",""));
  var nodes=java.util.Map.of("root",java.util.Map.of("texts",nodeTexts,"options",java.util.List.of(rootChoice,blocked)),
    "more",java.util.Map.of("texts",nodeTexts,"options",java.util.List.of(back)));
  var graph=java.util.Map.<String,Object>of("id","test","revision",1,"entryNode","root","fallbackLocale","EN","nodes",nodes);
  var granted=new java.util.HashMap<>(rootChoice);granted.put("setFlag","secret");
  var gatedGraph=new java.util.HashMap<>(graph);gatedGraph.put("nodes",java.util.Map.of("root",java.util.Map.of("texts",nodeTexts,"options",java.util.List.of(granted,blocked)),"more",nodes.get("more")));
  var cursor=new zombie.apocalipse.humans.dialogue.HumanDialogueSession(gatedGraph);
  check(cursor.choose("root",1,"secret",1,java.util.Set.of())==null,"graph conditions hide unearned choices");
  cursor.advance(cursor.choose("root",1,"ask",1,java.util.Set.of()));
  cursor.advance(cursor.choose("more",1,"back",2,java.util.Set.of()));
  check(cursor.choose("root",1,"secret",3,java.util.Set.of())!=null,"accepted graph effect unlocks later choices");
  var terminal=new java.util.HashMap<>(back);terminal.put("close",true);
  gatedGraph.put("nodes",java.util.Map.of("root",java.util.Map.of("texts",nodeTexts,"options",java.util.List.of(rootChoice)),"more",java.util.Map.of("texts",nodeTexts,"options",java.util.List.of(terminal))));
  cursor=new zombie.apocalipse.humans.dialogue.HumanDialogueSession(gatedGraph);
  cursor.advance(cursor.choose("root",1,"ask",1,java.util.Set.of()));cursor.advance(cursor.choose("more",1,"back",2,java.util.Set.of()));
  check(cursor.completed()&&cursor.choose("root",1,"ask",3,java.util.Set.of())==null,"terminal transition cannot be selected again");
  zombie.apocalipse.humans.dialogue.HumanDialogueCatalog.publish(java.util.Map.of("roaming-greeter",graph));
  check(zombie.apocalipse.humans.dialogue.HumanLanguage.locale().equals("PTBR"),"NPC language defaults to PTBR independently of JVM language");
  check(zombie.apocalipse.humans.dialogue.HumanLanguage.text("IGUI_ApocExtractionMapUtils_GuideHello").equals("Olá!"),"autonomous greeting defaults to Portuguese without SQL");
  short id=factory.spawn(new ServerHumanDefinition("graph","Graph NPC","NPC",100,200,0,IsoDirections.E,false,"Tourist"));p.x=101;
  var roster=ServerHumanDialogue.graphRoster(p);var actor=(KahluaTable)roster.rawget(1.0);
  var view=(KahluaTable)actor.rawget("dialogue");var publicNodes=(KahluaTable)view.rawget("nodes");
  check(publicNodes.rawget("more")==null,"unvisited future nodes stay server-side");
  var root=(KahluaTable)publicNodes.rawget("root");var options=(KahluaTable)root.rawget("options");
  check(options.values.size()==1&&((KahluaTable)options.rawget(1.0)).rawget("title").equals("Portuguese"),"server resolves labels and filters hidden choices");
  check(((KahluaTable)options.rawget(1.0)).rawget("texts")==null&&((KahluaTable)options.rawget(1.0)).rawget("nextNode")==null,"client receives no locales or transitions");
  check(ServerHumanDialogue.graphBegin(p,id,1,"root","secret",now).rawget("error")!=null,"guessed hidden option cannot reserve");
  check(ServerHumanDialogue.graphBegin(p,id,2,"root","ask",now).rawget("error")!=null,"stale graph cannot reserve");
  int token=token(ServerHumanDialogue.graphBegin(p,id,1,"root","ask",now));
  check(ServerHumanDialogue.graphSelect(p,id,token,"root",1,"ask",1,now).rawget("error")!=null,"engine rejects selection before arrival");
  check(ServerHumanDialogue.arrive(p,id,token,now),"graph arrives through normal lease");
  var result=ServerHumanDialogue.graphSelect(p,id,token,"root",1,"ask",1,now);
  check(result.rawget("response").equals("Portuguese")&&((KahluaTable)result.rawget("dialogue")).rawget("entryNode").equals("more"),"engine speaks and advances pinned graph");
  check(ServerHumanDialogue.graphSelect(p,id,token,"root",1,"ask",1,now+1_000_000_000L).rawget("error")!=null,"replayed selection rejected");
  check(ServerHumanDialogue.graphSelect(p,id,token,"more",1,"back",2,now+100).rawget("error")!=null,"engine owns cooldown");
  var revised=new java.util.HashMap<>(graph);revised.put("revision",2);
  zombie.apocalipse.humans.dialogue.HumanDialogueCatalog.publish(java.util.Map.of("roaming-greeter",revised));
  check(ServerHumanDialogue.graphSelect(p,id,token,"more",2,"back",2,now+1_000_000_000L).rawget("error")!=null,"new catalog cannot change pinned revision");
  check(ServerHumanDialogue.graphSelect(p,id,token,"more",1,"back",2,now+1_000_000_000L).rawget("error")==null,"pinned revision remains usable after refresh");
  p.z=1;check(ServerHumanDialogue.graphSelect(p,id,token,"root",1,"ask",3,now+2_000_000_000L).rawget("error")!=null,"graph selection revalidates actor range/floor");p.z=0;
  factory.remove("graph");
 }
}
"""
LANGUAGE = r'''
package zombie.apocalipse.humans;
import java.util.*;import zombie.apocalipse.humans.dialogue.*;
public class LanguageTest {
 public static void main(String[] args) {
  String expected=args[0];if(!HumanLanguage.locale().equals(expected))throw new AssertionError("locale");
  var translations=Map.<String,Object>of("EN",Map.of("title","English"),"PTBR",Map.of("title","Portuguese"));
  if(!HumanLanguage.resolve(translations,"EN").get("title").equals(expected.equals("EN")?"English":"Portuguese"))throw new AssertionError("selection");
  if(!HumanLanguage.resolve(Map.of("EN",Map.of("title","fallback")),"EN").get("title").equals("fallback"))throw new AssertionError("fallback");
  String template=HumanLanguage.text("IGUI_ApocExtractionMapUtils_EventCountdown","Operation%2","10s");
  if(!template.contains("Operation%2")||!template.contains("10s"))throw new AssertionError("placeholder interpretation");
  var labels=Map.<String,Map<String,Object>>of("custom",Map.of("PTBR",Map.of("text","Banco"),"EN",Map.of("text","Database")));
  HumanDialogueCatalog.publish(Map.of(),labels);long generation=HumanDialogueCatalog.current().generation();
  if(!HumanLanguage.text("custom").equals(expected.equals("EN")?"Database":"Banco"))throw new AssertionError("DB labels");
  HumanDialogueCatalog.publish(Map.of(),labels);if(HumanDialogueCatalog.current().generation()!=generation)throw new AssertionError("stable generation");
  HumanDialogueCatalog.publish(Map.of(),Map.of());if(HumanDialogueCatalog.current().generation()!=generation+1)throw new AssertionError("labels invalidate generation");
  System.out.println("NPC language, fallback, DB labels and refresh passed: "+expected);
 }
}
'''

def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-dialogue-tests-") as directory:
        work=Path(directory)
        fixture=dict(FILES)
        fixture["zombie/apocalipse/humans/DialogueTest.java"]=HARNESS
        fixture["zombie/apocalipse/humans/LanguageTest.java"]=LANGUAGE
        sources=[]
        for relative,content in fixture.items():
            path=work/relative;path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(content,encoding="utf-8");sources.append(path)
        sources.extend(sorted((ROOT/"42.21.0/src/zombie/apocalipse/humans").rglob("*.java")))
        args=work/"javac.args"
        args.write_text("\n".join('"'+str(p).replace("\\","/")+'"' for p in sources),encoding="utf-8")
        output=work/"classes"
        subprocess.run([str(ROOT/"jdk/bin/javac.exe"),"--release","25","-encoding","UTF-8","-d",str(output),"@"+str(args)],check=True)
        subprocess.run([str(ROOT/"jdk/bin/java.exe"),"-Duser.language=EN","-cp",str(output),"zombie.apocalipse.humans.DialogueTest"],check=True)
        for extra,expected in (([],"PTBR"),(["-Dapocbr.npc.language=EN"],"EN")):
            subprocess.run([str(ROOT/"jdk/bin/java.exe"),"-Duser.language=EN",*extra,"-cp",str(output),"zombie.apocalipse.humans.LanguageTest",expected],check=True)

if __name__=="__main__":main()
