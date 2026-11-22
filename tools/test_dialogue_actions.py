"""42.22.1 production selection/completion methods, with explicit world/lease/delivery doubles."""
from pathlib import Path
import subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'42.22.1/src/zombie'
source=(SRC/'apocalipse/humans/ServerHumanDialogue.java').read_text(encoding='utf-8')
def method(signature):
    start=source.index(signature); brace=source.index('{',start); depth=1; end=brace+1
    while depth:
        if source[end]=='{': depth+=1
        elif source[end]=='}': depth-=1
        end+=1
    return source[start:end]
methods='\n'.join(method(s) for s in ['static KahluaTable graphSelect(', 'static KahluaTable graphComplete(', 'private static Object luaValue(', 'private static int integer('])
files={
'se/krka/kahlua/vm/KahluaTable.java':'''package se.krka.kahlua.vm;
import java.util.*;
public class KahluaTable {
 Map<Object,Object> data=new LinkedHashMap<>();public Object rawget(Object k){return data.get(k);}
 public void rawset(Object k,Object v){if(v==null)data.remove(k);else data.put(k,v);}
 public Cursor iterator(){return new Cursor(data.entrySet().iterator());}
 public static class Cursor {Iterator<Map.Entry<Object,Object>> it;Map.Entry<Object,Object> e;
  Cursor(Iterator<Map.Entry<Object,Object>> it){this.it=it;}public boolean advance(){if(!it.hasNext())return false;e=it.next();return true;}
  public Object getKey(){return e.getKey();}public Object getValue(){return e.getValue();}}
}''',
'zombie/Lua/LuaManager.java':'''package zombie.Lua;import se.krka.kahlua.vm.*;
public class LuaManager {public static Platform platform=new Platform();public static class Platform {public KahluaTable newTable(){return new KahluaTable();}}}''',
'zombie/debug/DebugLog.java':'package zombie.debug;public class DebugLog{public static void log(String s){}}',
'zombie/characters/IsoPlayer.java':'package zombie.characters;public class IsoPlayer{}',
'zombie/apocalipse/humans/tasks/HumanTaskCommand.java':'package zombie.apocalipse.humans.tasks;public class HumanTaskCommand{}',
'zombie/apocalipse/humans/ServerHumanTasks.java':'''package zombie.apocalipse.humans;import zombie.characters.*;import zombie.apocalipse.humans.tasks.*;
class ServerHumanTasks {static boolean accepts(Object h,HumanTaskCommand t,IsoPlayer p){return true;}static void apply(Object h,HumanTaskCommand t,IsoPlayer p){}}''',
'zombie/apocalipse/humans/ServerHumanDialogue.java':'''package zombie.apocalipse.humans;
import java.util.*;import zombie.characters.*;import zombie.Lua.*;import se.krka.kahlua.vm.*;import zombie.apocalipse.humans.dialogue.*;
public class ServerHumanDialogue {
 static class Session {IsoPlayer visitor;Object human=new Object();boolean arrived=true;HumanDialogueSession graph;long nextChoice;
  HumanDialogueSession.Choice pendingAction;HumanDialogueResponses responses=new HumanDialogueResponses(n->0);}
 static Session active;static boolean nearby=true;static int spoken,remembered;
 static Session session(IsoPlayer p,int id,int token,long now){return active!=null&&active.visitor==p&&id==768&&token==7?active:null;}
 static boolean near(Object h,IsoPlayer p,double r){return nearby;}
 static Set<String> flags(IsoPlayer p,Object h,HumanDialogueSession s){return Set.of();}
 static boolean remember(IsoPlayer p,int id,int token,Object d,Object f){remembered++;return true;}
 static boolean react(IsoPlayer p,int id,int token,String text,String emote,long now){spoken++;return true;}
 static KahluaTable rejected(String s){var t=new KahluaTable();t.rawset("error",s);return t;}
'''+methods+'''
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static KahluaTable outcome(int seq,boolean success,String pool){var t=new KahluaTable();t.rawset("sequence",(double)seq);t.rawset("success",success);t.rawset("responsePool",pool);return t;}
 public static void main(String[] args){
  var player=new IsoPlayer();active=new Session();active.visitor=player;
  var texts=Map.of("PTBR",Map.of("title","Travel","description","","response","static","speech","static"));
  var action=new HashMap<String,Object>();action.put("id","travel");action.put("nextNode","more");action.put("setFlag","earned");action.put("close",true);action.put("texts",texts);
  action.put("action",Map.of("id","player.travel","responsePrefix","travel"));
  var back=Map.of("id","back","nextNode","root","texts",texts,"close",false);
  var graph=Map.<String,Object>of("id","test","revision",1,"entryNode","root","fallbackLocale","PTBR","nodes",Map.of(
   "root",Map.of("texts",texts,"options",List.of(action)),"more",Map.of("texts",texts,"options",List.of(back))));
  active.graph=new HumanDialogueSession(graph);
  HumanDialogueCatalog.publish(Map.of(),Map.of("travel.success.1",Map.of("PTBR",Map.of("text","Ready")),"travel.unavailable.1",Map.of("PTBR",Map.of("text","Try later"))));
  check(graphSelect(player,768,7,"root",1,"travel",1,0).rawget("action")!=null,"valid selection returns server action");
  check(spoken==0&&remembered==0&&active.graph.sequence()==0,"prepare has no speech, flags or cursor effects");
  check(graphSelect(player,768,7,"root",1,"travel",1,1000000000).rawget("error")!=null,"pending selection cannot run twice");
  check(graphComplete(new IsoPlayer(),768,7,outcome(1,true,"travel.success"),1).rawget("error")!=null,"foreign visitor rejected");
  check(graphComplete(player,768,8,outcome(1,true,"travel.success"),1).rawget("error")!=null,"foreign token rejected");
  check(graphComplete(player,768,7,outcome(2,true,"travel.success"),1).rawget("error")!=null,"wrong sequence rejected");
  nearby=false;check(graphComplete(player,768,7,outcome(1,true,"travel.success"),1).rawget("error")!=null,"range revalidated");nearby=true;
  check(graphComplete(player,768,7,outcome(1,true,"foreign.success"),1).rawget("error")!=null,"binding limits response pools");
  check(graphComplete(player,768,7,outcome(1,false,"travel.unavailable"),1).rawget("response").equals("Try later"),"failed action speaks pooled response");
  check(active.graph.node().equals("root")&&active.graph.sequence()==1&&!active.graph.completed()&&remembered==0,"failure consumes sequence without unlocking, transition or close");
  check(graphComplete(player,768,7,outcome(1,false,"travel.unavailable"),2).rawget("error")!=null&&spoken==1,"completion replay cannot speak again");
  check(graphSelect(player,768,7,"root",1,"travel",2,500000001).rawget("action")!=null,"next legitimate request accepted");
  var result=graphComplete(player,768,7,outcome(2,true,"travel.success"),500000002);
  check(result.rawget("response").equals("Ready")&&Boolean.TRUE.equals(result.rawget("close"))&&spoken==2&&remembered==1,"success speaks and applies authored progression");
  check(active.graph.completed()&&active.graph.node().equals("more"),"successful action advances graph");
  check(graphComplete(player,768,7,outcome(2,true,"travel.success"),500000003).rawget("error")!=null&&remembered==1,"successful completion replay rejected");
  System.out.println("Passed "+checks+" production dialogue action selection/completion checks.");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='apocbr-dialogue-actions-') as folder:
    work=Path(folder); sources=[]
    for name,text in files.items():
        p=work/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');sources.append(p)
    for name in ['HumanDialogueSession','HumanDialogueResponses','HumanDialogueCatalog','HumanLanguage']:
        sources.append(SRC/f'apocalipse/humans/dialogue/{name}.java')
    argfile=work/'sources.txt';argfile.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources),encoding='utf-8')
    subprocess.run([ROOT/'jdk/bin/javac.exe','--release','25','-encoding','UTF-8','-d',work/'classes','@'+str(argfile)],check=True)
    subprocess.run([ROOT/'jdk/bin/java.exe','-cp',work/'classes','zombie.apocalipse.humans.ServerHumanDialogue'],check=True)
