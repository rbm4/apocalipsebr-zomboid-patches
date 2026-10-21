"""Production NPC JDBC/worker integration in a disposable local cluster, never a live database.
Set APOCBR_TEST_POSTGRES_BIN to a directory containing initdb, pg_ctl and postgres.
"""
from pathlib import Path
import os, socket, subprocess, tempfile, sys
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'42.21.0/src/zombie'
SQL=ROOT.parent/'apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V15__Create_Npc_Engine.sql'
BIN=Path(os.environ['APOCBR_TEST_POSTGRES_BIN'])
JDK=ROOT/'jdk/bin'
GAME=Path(os.environ.get('APOCBR_TEST_GAME_JAR',r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
FILES={
'zombie/debug/DebugLog.java':'package zombie.debug;public class DebugLog {public static void log(String s){System.out.println(s);}}',
'zombie/savefile/ApocBRPostgresConnector.java':r"""package zombie.savefile;
import java.sql.*;
public class ApocBRPostgresConnector {
 static String url;public static Connection borrowServerConnection()throws SQLException{return DriverManager.getConnection(url,"pz_server_01","");}
 public static void runBackgroundDatabaseWork(Runnable work){work.run();}
}
""",
'zombie/savefile/ApocBRPersistenceOperations.java':r"""package zombie.savefile;
import java.util.*;
final class ApocBRPersistenceOperations {record Sql(String sql,List<Object> parameters){}record Account(List<Sql> statements){}}
""",
'zombie/savefile/ApocBRPersistence.java':r"""package zombie.savefile;
import java.util.*;
final class ApocBRPersistence {
 static final java.util.concurrent.ConcurrentLinkedQueue<ApocBRPersistenceOperations.Account> queue=new java.util.concurrent.ConcurrentLinkedQueue<>();
 static boolean enabled(){return true;}static boolean reachable(){return true;}
 static UUID submit(ApocBRPersistenceOperations.Account value){queue.add(value);return UUID.randomUUID();}
}
""",
'zombie/savefile/NpcStoreTest.java':r'''package zombie.savefile;
import java.util.*;import java.sql.*;import java.nio.file.*;import zombie.apocalipse.humans.state.*;
import zombie.apocalipse.humans.*;import zombie.apocalipse.humans.profiles.*;import zombie.iso.IsoDirections;
public class NpcStoreTest {
 static int checks;
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static void invalid(Connection c,String sql)throws Exception{try(var s=c.createStatement()){s.execute(sql);throw new AssertionError("Invalid SQL accepted: "+sql);}catch(SQLException expected){c.rollback();checks++;}}
 static long number(Connection c,String sql)throws Exception{try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
 static void apply(Connection c,List<ApocBRPersistenceOperations.Sql> list)throws Exception {
  for(var sql:list)try(var s=ApocBRNpcStore.statement(c,sql.sql(),sql.parameters().toArray())){s.executeUpdate();}c.commit();
 }
 static ApocBRNpcStore.State state(ServerHumanDefinition d,long revision,float x,HumanLifecycle.State lifecycle,boolean retired){var physical=HumanStateReducer.reduce(HumanState.INITIAL,new HumanMutation.Damage(revision==5?.4f:.25f,-.5f,.2f,.01f));
 physical=HumanStateReducer.reduce(physical,new HumanMutation.Adjust(HumanState.Stat.HUNGER,.3f));
 if(lifecycle==HumanLifecycle.State.DEAD)physical=HumanStateReducer.reduce(physical,new HumanMutation.Kill());
 return new ApocBRNpcStore.State(d,lifecycle,1,1,x,200,0,revision,retired,physical);}
 public static void main(String[] args)throws Exception {
  String url=args[0];
  try(var admin=DriverManager.getConnection(url,"postgres","");var s=admin.createStatement()){
   s.execute("CREATE ROLE apocalipsebr LOGIN;CREATE ROLE pz_server_01 LOGIN;GRANT CREATE ON DATABASE postgres TO apocalipsebr;SET ROLE apocalipsebr");
   s.execute(Files.readString(Path.of(args[1])));
   s.execute(Files.readString(Path.of(args[1]).resolveSibling("V20__Persist_Npc_Physical_State.sql")));
   s.execute(Files.readString(Path.of(args[1]).resolveSibling("V21__Seed_Militia_Engagement_Profile.sql")));
   s.execute(Files.readString(Path.of(args[1]).resolveSibling("V22__Describe_Npc_Weapon_Backend.sql")));
  }
  try(var c=DriverManager.getConnection(url,"pz_server_01","")) {
   c.setAutoCommit(false);
   var profiles=ApocBRNpcStore.readProfiles(c);check(profiles.size()==4,"seed profiles including militia scaffold");
   var militia=profiles.stream().filter(p->p.id().equals("militia-patrol")).findFirst().orElseThrow();
   check(!militia.dialogue()&&!militia.trading()&&militia.modules().getFirst().moduleId().equals("engage-zombies"),"database militia profile orders engagement before roaming");
   check(militia.modules().getFirst().parameters().get("retryMs")==2000L,"engagement timing hydrated through existing typed millisecond fields");
   var guide=profiles.stream().filter(p->p.id().equals("lobby-guide")).findFirst().orElseThrow();
   check(guide.modules().size()==2&&guide.announcementId().equals("operations")&&guide.dialogue()&&guide.trading(),"guide seed matches current baseline");
   check(guide.modules().getFirst().moduleId().equals("greet")&&guide.modules().getLast().moduleId().equals("roam"),"module priority ordering");
   c.commit();invalid(c,"UPDATE npc_engine.profile_module SET priority=50 WHERE profile_id='lobby-guide'");
   invalid(c,"UPDATE npc_engine.profile_revision SET perception_radius=9 WHERE profile_id='lobby-guide'");
   invalid(c,"INSERT INTO npc_engine.profile_module VALUES('lobby-guide',1,'missing',true,10,100)");
   try(var s=c.createStatement()){
    s.execute("INSERT INTO npc_engine.profile_revision SELECT profile_id,2,dialogue_enabled,trading_enabled,greeting_key,announcement_provider,12,500,require_los,now() FROM npc_engine.profile_revision WHERE profile_id='lobby-guide' AND revision=1");
    s.execute("INSERT INTO npc_engine.profile_module VALUES('lobby-guide',2,'greet',true,10,100)");
    s.execute("UPDATE npc_engine.profile SET current_revision=2 WHERE profile_id='lobby-guide'");c.commit();
   }
   check(ApocBRNpcStore.readProfiles(c).stream().anyMatch(p->p.id().equals("lobby-guide")&&p.revision()==2&&p.perceptionRadius()==12&&p.modules().size()==1),"maintenance publishes new profile revision");c.commit();
   invalid(c,"UPDATE npc_engine.profile SET current_revision=1 WHERE profile_id='lobby-guide'");
   try(var s=c.createStatement()){s.execute("INSERT INTO npc_engine.scope VALUES('test','world',1)");c.commit();}
   var d=new ServerHumanDefinition("guide","Ricardao","Ricardão",100,200,0,IsoDirections.E,false,"CostumeMonster","lobby-guide");
   apply(c,ApocBRNpcStore.statements("test","world",1,state(d,2,110,HumanLifecycle.State.ACTIVE,false)));
   check(number(c,"SELECT x FROM npc_engine.runtime_state")==110,"engine role writes snapshot independently of player graph");
   check(number(c,"SELECT round(body_health::numeric*100) FROM npc_engine.runtime_state")==75,"physical health checkpoint written atomically");
   check(number(c,"SELECT round(need_hunger::numeric*100) FROM npc_engine.runtime_state")==30,"needs use relational normalized columns");
   invalid(c,"UPDATE npc_engine.runtime_state SET body_endurance='NaN'::real");
   invalid(c,"UPDATE npc_engine.runtime_state SET injury_leg=2");
   invalid(c,"UPDATE npc_engine.runtime_state SET state_revision=-1");
   apply(c,ApocBRNpcStore.statements("test","world",1,state(d,1,100,HumanLifecycle.State.DORMANT,false)));
   check(number(c,"SELECT x FROM npc_engine.runtime_state")==110,"out of order snapshot rejected");
   check(number(c,"SELECT round(body_health::numeric*100) FROM npc_engine.runtime_state")==75,"stale checkpoint cannot overwrite physical state");
   apply(c,ApocBRNpcStore.statements("test","world",1,state(d,2,120,HumanLifecycle.State.ACTIVE,false)));
   check(number(c,"SELECT x FROM npc_engine.runtime_state")==110,"duplicate mutation cannot overwrite committed state");
   // An old in-flight writer holds a shared scope lock until its transaction ends.
   try(var held=DriverManager.getConnection(url,"pz_server_01","");var contender=DriverManager.getConnection(url,"pz_server_01","")) {
    held.setAutoCommit(false);contender.setAutoCommit(false);
    var first=ApocBRNpcStore.statements("test","world",1,state(d,3,125,HumanLifecycle.State.ACTIVE,false)).getFirst();
    try(var s=ApocBRNpcStore.statement(held,first.sql(),first.parameters().toArray())){s.executeUpdate();}
    try(var s=contender.createStatement()) {
     s.execute("SET LOCAL lock_timeout='150ms'");
     try{s.execute("UPDATE npc_engine.scope SET epoch=2");throw new AssertionError("Ownership crossed an in-flight checkpoint");}
     catch(SQLException expected){check("55P03".equals(expected.getSQLState()),"epoch change waits for old in-flight writer");contender.rollback();}
    }
    held.rollback();
   }
   try(var s=c.createStatement()){s.execute("UPDATE npc_engine.scope SET epoch=2");c.commit();}
   apply(c,ApocBRNpcStore.statements("test","world",1,state(d,100,900,HumanLifecycle.State.DEAD,false)));
   check(number(c,"SELECT x FROM npc_engine.runtime_state")==110,"old engine epoch fenced");
   apply(c,ApocBRNpcStore.statements("test","world",2,state(d,3,130,HumanLifecycle.State.ACTIVE,false)));
   check(number(c,"SELECT x FROM npc_engine.runtime_state")==130,"new engine epoch accepted");
   // Publish the real dialogue migration as the backend owner, then read as engine.
   try(var owner=DriverManager.getConnection(url,"apocalipsebr","")) {
    owner.setAutoCommit(false);
    for(String file:List.of("V16__Create_Npc_Trade_Policies.sql","V17__Create_Npc_Dialogue_Graphs.sql","V18__Npc_Server_Language.sql")) {
     try(var s=owner.createStatement()){s.execute(Files.readString(Path.of(args[1]).resolveSibling(file)));}owner.commit();
    }
   }
   var content=ApocBRNpcDialogues.read(c);c.commit();
   var labels=ApocBRNpcDialogues.labels(c);c.commit();
   check(((Map<?,?>)labels.get("IGUI_ApocExtractionMapUtils_GuideHello").get("PTBR")).get("text").equals("Olá!"),"engine role reads default Portuguese labels from V18");
   var graph=(Map<?,?>)content.get("lobby-guide");
   check(graph!=null&&graph.get("entryNode").equals("root")&&graph.get("revision").equals(1),"published graph loaded with engine permissions");
   var root=(Map<?,?>)((Map<?,?>)graph.get("nodes")).get("root");
   var options=(List<?>)root.get("options");
   check(options.size()==3,"all original dialogue options preserved");
   var hello=(Map<?,?>)options.getFirst();var helloTexts=(Map<?,?>)hello.get("texts");
   check(hello.get("id").equals("hello")&&hello.get("nextNode").equals("root")&&hello.get("emote").equals("wavehi"),"guide ordering/emotes/self loops preserved");
   check(((Map<?,?>)helloTexts.get("EN")).get("title").equals("Hello! How are you?"),"English title loaded from database");
   check(((Map<?,?>)helloTexts.get("PTBR")).get("speech").equals("Olá, sobrevivente! Que bom ver você por aqui."),"Portuguese speech loaded without translation tags");
   invalid(c,"UPDATE npc_engine.dialogue_choice_text SET title='changed' WHERE dialogue_id='guide'");
   invalid(c,"DELETE FROM npc_engine.dialogue_choice WHERE dialogue_id='guide'");
   invalid(c,"UPDATE npc_engine.dialogue SET current_revision=NULL WHERE dialogue_id='guide'");
   invalid(c,"INSERT INTO npc_engine.dialogue VALUES('bypass','Bad',1)");
   try(var s=c.createStatement()) {
    s.execute("INSERT INTO npc_engine.dialogue VALUES('draft','Draft',NULL)");
    s.execute("INSERT INTO npc_engine.dialogue_revision(dialogue_id,revision,entry_node,fallback_locale) VALUES('draft',1,'root','EN')");
    s.execute("INSERT INTO npc_engine.dialogue_node VALUES('draft',1,'root')");c.commit();
   }
   invalid(c,"UPDATE npc_engine.dialogue SET current_revision=1 WHERE dialogue_id='draft'");
   invalid(c,"INSERT INTO npc_engine.profile_dialogue VALUES('stationary','draft')");
   try(var s=c.createStatement()) {
    s.execute("INSERT INTO npc_engine.dialogue_node_text VALUES('draft',1,'root','EN','Title','Description')");
    s.execute("INSERT INTO npc_engine.dialogue_choice(dialogue_id,revision,choice_id,node_id,next_node,sort_order,required_flag,set_flag) VALUES('draft',1,'secret','root','root',1,'met_guide','knows_secret')");
    s.execute("INSERT INTO npc_engine.dialogue_choice_text VALUES('draft',1,'secret','EN','Ask','Detail','Answer','Speech')");
    s.execute("UPDATE npc_engine.dialogue SET current_revision=1 WHERE dialogue_id='draft'");
    s.execute("INSERT INTO npc_engine.profile_dialogue VALUES('stationary','draft')");c.commit();
   }
   var draft=(Map<?,?>)ApocBRNpcDialogues.read(c).get("stationary");
   var secret=(Map<?,?>)((List<?>)((Map<?,?>)((Map<?,?>)draft.get("nodes")).get("root")).get("options")).getFirst();
   check(secret.get("requiredFlag").equals("met_guide")&&secret.get("setFlag").equals("knows_secret"),"graph gate/effect data survives JDBC mapping");c.commit();
   try(var s=c.createStatement()) {
    s.execute("INSERT INTO npc_engine.dialogue_flag(server_key,world,npc_key,dialogue_id,identity_kind,player_identity,flag) VALUES('test','world','guide','guide','steam','123','met_guide')");c.commit();
   }
   // Start actual worker: adopts existing actor, never overwrites maintained identity/config.
   ApocBRPostgresConnector.url=url;System.setProperty("apocbr.npc.persistence.enabled","true");
   var store=ApocBRNpcStore.start("test","world",d);ApocBRNpcStore.Loaded loaded=null;
   long deadline=System.nanoTime()+10000000000L;
   while(loaded==null&&System.nanoTime()<deadline){Thread.sleep(20);loaded=store.takeLoaded();}
   check(loaded!=null&&loaded.instances().size()==1,"worker hydrates logical instances");
   check(loaded.instances().getFirst().state().physiology().vitals().hunger()>.29f,"worker hydrates physical state from PostgreSQL");
   check(zombie.apocalipse.humans.dialogue.HumanDialogueCatalog.current().profiles().containsKey("lobby-guide"),"dialogue cache is hot before NPC startup snapshot becomes visible");
   check(zombie.apocalipse.humans.dialogue.HumanDialogueCatalog.current().labels().containsKey("IGUI_ApocExtractionMapUtils_GuideHello"),"labels and graphs publish together before actor activation");
   var account=new ApocBRNpcStore.DialogueIdentity("guide","guide","steam","123");
   check(store.dialogueFlags(account).contains("met_guide"),"account dialogue facts hydrate with NPC startup");
   check(store.dialogueFlags(new ApocBRNpcStore.DialogueIdentity("guide","guide","steam","456")).isEmpty(),"facts isolated between accounts");
   var restored=loaded.instances().getFirst();check(restored.activate()&&restored.state().x()==130&&restored.state().activations()==1,"restart recovers lifecycle and last position");
   check(number(c,"SELECT epoch FROM npc_engine.scope")==3,"worker obtains new fenced epoch");c.commit();
   try(var other=DriverManager.getConnection(url,"pz_server_01","");var s=ApocBRNpcStore.statement(other,"SELECT pg_try_advisory_lock(hashtext(?),hashtext(?))","npc-engine:test","world");var r=s.executeQuery()){r.next();check(!r.getBoolean(1),"second owner rejected");}
   var field=ApocBRNpcStore.class.getDeclaredField("connection");field.setAccessible(true);
   var before=(Connection)field.get(store);int pid=before.unwrap(org.postgresql.PGConnection.class).getBackendPID();
   try(var admin=DriverManager.getConnection(url,"postgres","");var s=admin.createStatement()){s.execute("SELECT pg_terminate_backend("+pid+")");}
   deadline=System.nanoTime()+15000000000L;boolean sawUnavailable=false,recovered=false;
   while(System.nanoTime()<deadline) {
    if(!store.healthy())sawUnavailable=true;
    if(sawUnavailable&&store.healthy()){recovered=true;break;}Thread.sleep(20);
   }
   check(recovered,"worker reconnects after losing its PostgreSQL session");
   check(number(c,"SELECT epoch FROM npc_engine.scope")==3,"reconnect preserves ownership epoch");c.commit();
   store.offer(state(d,5,150,HumanLifecycle.State.ACTIVE,false));store.offer(state(d,4,140,HumanLifecycle.State.ACTIVE,false));
   check(store.remember(account,"knows_secret"),"trusted selection stages earned fact without SQL on request thread");
   check(store.remember(account,"knows_secret")&&store.dialogueFlags(account).size()==2,"repeated earned flag is idempotent");
   ApocBRNpcStore.shutdown();check(!ApocBRPersistence.queue.isEmpty(),"shutdown stages final detached checkpoint");
   try(var other=DriverManager.getConnection(url,"pz_server_01","");var s=ApocBRNpcStore.statement(other,"SELECT pg_try_advisory_lock(hashtext(?),hashtext(?))","npc-engine:test","world");var r=s.executeQuery()){
    r.next();check(r.getBoolean(1),"shutdown releases advisory ownership before returning pooled connection");
    try(var unlock=ApocBRNpcStore.statement(other,"SELECT pg_advisory_unlock(hashtext(?),hashtext(?))","npc-engine:test","world")){unlock.execute();}
   }
   while(!ApocBRPersistence.queue.isEmpty())apply(c,ApocBRPersistence.queue.remove().statements());
   check(number(c,"SELECT count(*) FROM npc_engine.dialogue_flag WHERE player_identity='123'")==2,"earned facts persist through shared worker/journal using engine role");
   check(number(c,"SELECT x FROM npc_engine.runtime_state")==150,"coalescing retains newest mutation");
   check(number(c,"SELECT round(body_health::numeric*100) FROM npc_engine.runtime_state")==60,"worker and journal retain newest physical checkpoint");
   apply(c,ApocBRNpcStore.statements("test","world",3,state(d,6,150,HumanLifecycle.State.DEAD,true)));
   check(number(c,"SELECT count(*) FROM npc_engine.runtime_state WHERE retired AND lifecycle='DEAD'")==1,"terminal and administrative state persisted");
   try(var s=c.createStatement()){s.execute("CREATE TABLE npc_engine.permission_probe(id bigserial PRIMARY KEY)");c.commit();}
  }
  // Default grants belong to the actual migration owner, including future sequences.
  try(var c=DriverManager.getConnection(url,"apocalipsebr","");var s=c.createStatement()){s.execute("CREATE TABLE npc_engine.future_probe(id bigserial PRIMARY KEY)");}
  try(var c=DriverManager.getConnection(url,"pz_server_01","");var s=c.createStatement()){s.execute("INSERT INTO npc_engine.future_probe DEFAULT VALUES");check(number(c,"SELECT count(*) FROM npc_engine.future_probe")==1,"creator default table and sequence grants");}
  System.out.println("Passed "+checks+" NPC migration, JDBC, permissions, replay fencing and worker assertions.");
 }
}
'''
}

def run(command,**kw):
    if 'stdout' not in kw:
        result=subprocess.run(list(map(str,command)),capture_output=True,text=True,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0),**kw)
        if result.stdout: print(result.stdout,end='',flush=True)
        if result.stderr: print(result.stderr,end='',flush=True)
        result.check_returncode();return result
    return subprocess.run(list(map(str,command)),check=True,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0),**kw)
with tempfile.TemporaryDirectory(prefix='apocbr-npc-persistence-') as folder:
    work=Path(folder);sources=[]
    for name,content in FILES.items():
        p=work/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8');sources.append(p)
    sources += [SRC/'savefile/ApocBRNpcStore.java',SRC/'savefile/ApocBRNpcDialogues.java',SRC/'apocalipse/humans/dialogue/HumanDialogueCatalog.java',SRC/'apocalipse/humans/ServerHumanDefinition.java',SRC/'apocalipse/humans/HumanLifecycle.java',SRC/'apocalipse/humans/profiles/HumanProfile.java',SRC/'apocalipse/humans/profiles/HumanModuleBinding.java']
    sources += list((SRC/'apocalipse/humans/state').glob('*.java')) + list((SRC/'apocalipse/humans/movement').glob('*.java'))
    cp=os.pathsep.join(map(str,[GAME,ROOT/'42.21.0/lib/postgresql-42.7.14.jar']))
    argfile=work/'sources.txt';argfile.write_text('\n'.join('"'+str(s).replace('\\','/')+'"' for s in sources),encoding='utf-8')
    run([JDK/'javac.exe','--release','25','-encoding','UTF-8','-cp',cp,'-d',work/'classes','@'+str(argfile)])
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    run([BIN/'initdb.exe','-D',work/'data','-U','postgres','-A','trust','--no-locale','-E','UTF8'],stdout=subprocess.DEVNULL)
    try:
        run([BIN/'pg_ctl.exe','-D',work/'data','-l',work/'postgres.log','-o',f'-h 127.0.0.1 -p {port}','-w','start'],stdout=subprocess.DEVNULL)
        run([JDK/'java.exe','-cp',str(work/'classes')+os.pathsep+cp,'zombie.savefile.NpcStoreTest',f'jdbc:postgresql://127.0.0.1:{port}/postgres',SQL])
        if '--manager-test' in sys.argv:
            manager=ROOT.parent/'apocalipsebr-manager/manager'
            env=dict(os.environ,JAVA_HOME=str(JDK.parent),GAME_RUNTIME_TEST_POSTGRES_URL=f'jdbc:postgresql://127.0.0.1:{port}/postgres')
            run([manager/'mvnw.cmd','-o','-Dmaven.resources.skip=true','-Dmaven.main.skip=true','-Dtest=NpcTradePolicyMigrationTest,NpcPhysicalStateMigrationTest,NpcEngagementProfileMigrationTest','test'],cwd=manager,env=env)
    finally:
        run([BIN/'pg_ctl.exe','-D',work/'data','-m','immediate','-w','stop'],stdout=subprocess.DEVNULL)
