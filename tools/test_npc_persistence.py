"""Production NPC JDBC/worker integration in a disposable local cluster, never a live database.
Set APOCBR_TEST_POSTGRES_BIN to a directory containing initdb, pg_ctl and postgres.
"""
from pathlib import Path
import os, socket, subprocess, tempfile
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
import java.util.*;import java.sql.*;import java.nio.file.*;
import zombie.apocalipse.humans.*;import zombie.apocalipse.humans.profiles.*;import zombie.iso.IsoDirections;
public class NpcStoreTest {
 static int checks;
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static void invalid(Connection c,String sql)throws Exception{try(var s=c.createStatement()){s.execute(sql);throw new AssertionError("Invalid SQL accepted: "+sql);}catch(SQLException expected){c.rollback();checks++;}}
 static long number(Connection c,String sql)throws Exception{try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
 static void apply(Connection c,List<ApocBRPersistenceOperations.Sql> list)throws Exception {
  for(var sql:list)try(var s=ApocBRNpcStore.statement(c,sql.sql(),sql.parameters().toArray())){s.executeUpdate();}c.commit();
 }
 static ApocBRNpcStore.State state(ServerHumanDefinition d,long revision,float x,HumanLifecycle.State lifecycle,boolean retired){return new ApocBRNpcStore.State(d,lifecycle,1,1,x,200,0,revision,retired);}
 public static void main(String[] args)throws Exception {
  String url=args[0];
  try(var admin=DriverManager.getConnection(url,"postgres","");var s=admin.createStatement()){
   s.execute("CREATE ROLE apocalipsebr LOGIN;CREATE ROLE pz_server_01 LOGIN;GRANT CREATE ON DATABASE postgres TO apocalipsebr;SET ROLE apocalipsebr");
   s.execute(Files.readString(Path.of(args[1])));
  }
  try(var c=DriverManager.getConnection(url,"pz_server_01","")) {
   c.setAutoCommit(false);
   var profiles=ApocBRNpcStore.readProfiles(c);check(profiles.size()==3,"seed profiles");
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
   apply(c,ApocBRNpcStore.statements("test","world",1,state(d,1,100,HumanLifecycle.State.DORMANT,false)));
   check(number(c,"SELECT x FROM npc_engine.runtime_state")==110,"out of order snapshot rejected");
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
   // Start actual worker: adopts existing actor, never overwrites maintained identity/config.
   ApocBRPostgresConnector.url=url;System.setProperty("apocbr.npc.persistence.enabled","true");
   var store=ApocBRNpcStore.start("test","world",d);ApocBRNpcStore.Loaded loaded=null;
   long deadline=System.nanoTime()+10000000000L;
   while(loaded==null&&System.nanoTime()<deadline){Thread.sleep(20);loaded=store.takeLoaded();}
   check(loaded!=null&&loaded.instances().size()==1,"worker hydrates logical instances");
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
   ApocBRNpcStore.shutdown();check(!ApocBRPersistence.queue.isEmpty(),"shutdown stages final detached checkpoint");
   try(var other=DriverManager.getConnection(url,"pz_server_01","");var s=ApocBRNpcStore.statement(other,"SELECT pg_try_advisory_lock(hashtext(?),hashtext(?))","npc-engine:test","world");var r=s.executeQuery()){
    r.next();check(r.getBoolean(1),"shutdown releases advisory ownership before returning pooled connection");
    try(var unlock=ApocBRNpcStore.statement(other,"SELECT pg_advisory_unlock(hashtext(?),hashtext(?))","npc-engine:test","world")){unlock.execute();}
   }
   while(!ApocBRPersistence.queue.isEmpty())apply(c,ApocBRPersistence.queue.remove().statements());
   check(number(c,"SELECT x FROM npc_engine.runtime_state")==150,"coalescing retains newest mutation");
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
    sources += [SRC/'savefile/ApocBRNpcStore.java',SRC/'apocalipse/humans/ServerHumanDefinition.java',SRC/'apocalipse/humans/HumanLifecycle.java',SRC/'apocalipse/humans/profiles/HumanProfile.java',SRC/'apocalipse/humans/profiles/HumanModuleBinding.java']
    cp=os.pathsep.join(map(str,[GAME,ROOT/'42.21.0/lib/postgresql-42.7.14.jar']))
    argfile=work/'sources.txt';argfile.write_text('\n'.join('"'+str(s).replace('\\','/')+'"' for s in sources),encoding='utf-8')
    run([JDK/'javac.exe','--release','25','-encoding','UTF-8','-cp',cp,'-d',work/'classes','@'+str(argfile)])
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    run([BIN/'initdb.exe','-D',work/'data','-U','postgres','-A','trust','--no-locale','-E','UTF8'],stdout=subprocess.DEVNULL)
    try:
        run([BIN/'pg_ctl.exe','-D',work/'data','-l',work/'postgres.log','-o',f'-h 127.0.0.1 -p {port}','-w','start'],stdout=subprocess.DEVNULL)
        run([JDK/'java.exe','-cp',str(work/'classes')+os.pathsep+cp,'zombie.savefile.NpcStoreTest',f'jdbc:postgresql://127.0.0.1:{port}/postgres',SQL])
    finally:
        run([BIN/'pg_ctl.exe','-D',work/'data','-m','immediate','-w','stop'],stdout=subprocess.DEVNULL)
