"""Production 42.22.1 append bridge + V36 in a disposable PostgreSQL cluster.

Uses separate migration/engine roles; never connects to the installed server/data.
Also exercises actual producer Lua with a detached asynchronous transport double.
"""
from pathlib import Path
import os
import socket
import subprocess
import tempfile
from lupa import LuaRuntime

ROOT = Path(__file__).resolve().parents[1]
PACK = Path(os.environ.get('APOCBR_TEST_PACK', r'C:\Users\ricar\Zomboid\Workshop\apocbr\Contents\mods\ApocalisebrPack\42.14'))
BIN = Path(os.environ.get('APOCBR_TEST_POSTGRES_BIN', r'Z:\postgresql\bin'))
GAME = Path(os.environ.get('APOCBR_TEST_GAME_JAR', r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
MIGRATIONS = ROOT.parent / 'apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration'

HARNESS = r'''
package zombie.savefile;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import se.krka.kahlua.vm.*;
import se.krka.kahlua.j2se.KahluaTableImpl;
public class BackendMessagesTest {
 static int checks;static Thread owner=Thread.currentThread();
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
 static KahluaTable table(Object... pairs){KahluaTable t=new KahluaTableImpl(new HashMap<>());for(int i=0;i<pairs.length;i+=2)t.rawset(pairs[i],pairs[i+1]);return t;}
 static Object call(String name,Object...args)throws Exception {
  Method method=Arrays.stream(ApocBRModEntities.class.getDeclaredMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow();method.setAccessible(true);
  try{return method.invoke(null,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
 }
 interface Attempt {void run()throws Exception;}
 static void invalid(Attempt attempt)throws Exception{try{attempt.run();throw new AssertionError("expected rejection");}catch(IllegalArgumentException expected){checks++;}}
 static void denied(Attempt attempt)throws Exception{try{attempt.run();throw new AssertionError("expected SQL rejection");}catch(SQLException expected){checks++;}}
 static KahluaTable finish()throws Exception {
  List<Runnable> tasks=new ArrayList<>(ApocBRPersistence.tasks);ApocBRPersistence.tasks.clear();
  Thread worker=new Thread(()->tasks.forEach(Runnable::run));worker.start();worker.join();
  return (KahluaTable)((KahluaTable)call("poll")).rawget(1.0);
 }
 static KahluaTable append(Object token,String id,String payload)throws Exception {
  return (KahluaTable)call("append","ApocBR.BackendMessage",token,id,table("event_type","ZombieKillsRecorded.v1","payload",payload));
 }
 static Connection backend()throws SQLException{return DriverManager.getConnection(ApocBRPostgresConnector.url,"apocalipsebr","");}
 static long scalar(Connection c,String sql)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
 static String literal(String text){return "'"+text+"'";}
 public static void main(String[] args)throws Exception {
  ApocBRPostgresConnector.url=args[0];ApocBRModEntities.install();
  KahluaTable fields=table();
  for(String name:List.of("message_id","character_id","session_id"))fields.rawset(name,table("type","uuid","id",name.equals("message_id")));
  for(String name:List.of("source_server_id","player_index"))fields.rawset(name,table("type","integer"));
  for(String name:List.of("world","identity_mode","identity_key","event_type","payload"))fields.rawset(name,table("type","string"));
  call("define","ApocBR.BackendMessage",table("table","backend_message","appendOnly",true,"fields",fields));
  invalid(()->call("define","Unsafe",table("table","backend_message","fields",fields)));
  invalid(()->call("submit","ApocBR.BackendMessage","save",table(),table(),"0"));
  invalid(()->call("submitCharacter","ApocBR.BackendMessage","saveAll",new zombie.characters.IsoPlayer(),"fake",table()));
  KahluaTable context=(KahluaTable)call("messageContext",new zombie.characters.IsoPlayer());Object token=context.rawget("token");
  check(context.rawget("characterId").equals("00000000-0000-0000-0000-000000000001"),"identity comes from Java binding");
  String id=UUID.randomUUID().toString();
  invalid(()->append(table("characterId",context.rawget("characterId")),id,"{\"count\":5}"));
  invalid(()->call("append","ApocBR.BackendMessage",token,id,table("character_id",UUID.randomUUID().toString())));
  ApocBRPersistence.accept=false;check(append(token,id,"{\"count\":5}").rawget("status").equals("UNAVAILABLE"),"admission rejection");ApocBRPersistence.accept=true;
  KahluaTable values=table("event_type","ZombieKillsRecorded.v1","payload","{\"count\":5}");
  call("append","ApocBR.BackendMessage",token,id,values);values.rawset("payload","{\"count\":999}");
  ApocBRPlayerRelationalMutations.bound=false;
  KahluaTable result=finish();check(result.rawget("status").equals("OK"),"detached historical append survives unbinding");
  check(((KahluaTable)call("messageContext",new zombie.characters.IsoPlayer())).rawget("status").equals("IDENTITY_PENDING"),"no fabricated identity for unbound player");
  try(Connection c=backend()) {
   check(scalar(c,"SELECT (payload::jsonb->>'count')::bigint FROM mod_data.backend_message WHERE message_id="+literal(id))==5,"input detached before worker");
   check(scalar(c,"SELECT source_server_id FROM mod_data.backend_message WHERE message_id="+literal(id))==1,"trusted source server injected");
  }
  append(token,id,"{\"count\":5}");result=finish();check(result.rawget("status").equals("OK")&&Boolean.TRUE.equals(result.rawget("duplicate")),"same UUID/content acknowledges duplicate");
  append(token,id,"{\"count\":6}");check(finish().rawget("status").equals("CONFLICT"),"same UUID/different content rejected");
  String uncertain=UUID.randomUUID().toString();ApocBRPostgresConnector.loseCommit=true;
  append(token,uncertain,"{\"count\":3}");check(finish().rawget("status").equals("OUTCOME_UNKNOWN"),"commit ack lost after database commit");
  append(token,uncertain,"{\"count\":3}");check(finish().rawget("status").equals("OK"),"uncertain retry reconciles without duplicate");
  append(token,UUID.randomUUID().toString(),"{\"count\":0}");check(!finish().rawget("status").equals("OK"),"database rejects invalid kill payload");
  try(Connection c=DriverManager.getConnection(args[0],"pz_server_01","");var s=c.createStatement()) {
   denied(()->s.execute("UPDATE mod_data.backend_message SET status='processed'"));
   denied(()->s.execute("DELETE FROM mod_data.backend_message"));
   denied(()->s.execute("SELECT status FROM mod_data.backend_message"));
   denied(()->s.execute("INSERT INTO mod_data.backend_message(message_id,recipient) VALUES ('"+UUID.randomUUID()+"','game_engine')"));
   denied(()->s.execute("INSERT INTO mod_data.backend_message_error(message_id,error_code,detail) VALUES ("+literal(id)+",'x','y')"));
  }
  try(Connection c=backend();var s=c.createStatement()) {
   denied(()->s.execute("UPDATE mod_data.backend_message SET payload='{\"count\":99}' WHERE message_id="+literal(id)));
   String error="INSERT INTO mod_data.backend_message_error(message_id,error_code,detail) VALUES ("+literal(id)+",'TEST','failure detail')";
   c.setAutoCommit(false);s.execute(error);c.rollback();c.setAutoCommit(true);
   check(scalar(c,"SELECT failure_count FROM mod_data.backend_message WHERE message_id="+literal(id))==0,"failed transaction cannot leave failure count/history");
   for(int n=1;n<=5;n++)s.execute(error);
   check(scalar(c,"SELECT count(*) FROM mod_data.backend_message WHERE message_id="+literal(id)+" AND status='pending' AND failure_count=5")==1,"five failures still retryable");
   s.execute(error);
   check(scalar(c,"SELECT count(*) FROM mod_data.backend_message_failed WHERE message_id="+literal(id)+" AND failure_count=6")==1,"sixth failure quarantines with history");
   check(scalar(c,"SELECT count(*) FROM mod_data.backend_message_error WHERE message_id="+literal(id))==6,"all failure details retained");
   denied(()->s.execute(error));
   check(scalar(c,"SELECT count(*) FROM mod_data.backend_message_error WHERE message_id="+literal(id))==6,"quarantined event cannot record spurious retries");
   s.execute("UPDATE mod_data.backend_message SET status='processed',processed_at=clock_timestamp() WHERE message_id="+literal(uncertain));
  }
  append(token,id,"{\"count\":5}");check(finish().rawget("status").equals("OK"),"duplicate does not reset failed state");
  append(token,uncertain,"{\"count\":3}");check(finish().rawget("status").equals("OK"),"duplicate does not reset processed state");
  try(Connection c=backend()) {
   check(scalar(c,"SELECT count(*) FROM mod_data.backend_message")==2,"only two valid immutable messages persisted");
   check(scalar(c,"SELECT count(*) FROM mod_data.backend_message WHERE status='processed'")==1,"consumer decision retained");
  }
  boolean[] rejected={false};Thread other=new Thread(()->{try{call("guard");}catch(IllegalStateException expected){rejected[0]=true;}catch(Exception e){throw new RuntimeException(e);}});other.start();other.join();
  check(rejected[0],"unrelated threads cannot call bridge");
  zombie.network.GameServer.server=false;try{call("guard");throw new AssertionError("client admitted");}catch(IllegalStateException expected){checks++;}
  System.out.println("PASS production JDBC append / migration / permissions / failure history: "+checks+" checks");
 }
}
'''

STUBS = {
    'zombie/core/Core.java': 'package zombie.core;public class Core {public static boolean debug=false;}',
    'zombie/Lua/LuaManager.java': '''package zombie.Lua;public class LuaManager {
        public static class Platform {public se.krka.kahlua.vm.KahluaTable newTable(){return new se.krka.kahlua.j2se.KahluaTableImpl(new java.util.HashMap<>());}}
        public static Platform platform=new Platform();public static se.krka.kahlua.vm.KahluaTable env=platform.newTable();}''',
    'zombie/network/GameServer.java': 'package zombie.network;public class GameServer {public static boolean server=true;}',
    'zombie/characters/IsoPlayer.java': 'package zombie.characters;public class IsoPlayer {}',
    'zombie/savefile/ApocBRPayloadProvenance.java': 'package zombie.savefile;public class ApocBRPayloadProvenance {public static int configuredServerId(){return 1;}}',
    'zombie/savefile/ApocBRPlayerRelationalMutations.java': '''package zombie.savefile;public class ApocBRPlayerRelationalMutations {
        static boolean bound=true;record Candidate(String world,String identity,int slot,boolean coop) {}
        record EntityContext(Candidate candidate,java.util.UUID session,java.util.UUID character) {}
        static EntityContext entityContext(zombie.characters.IsoPlayer player){return bound?new EntityContext(new Candidate("test","account",0,false),
          java.util.UUID.fromString("00000000-0000-0000-0000-000000000010"),java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")):null;}
        static boolean entityContextCurrent(EntityContext context){return bound;}}''',
    'zombie/savefile/ApocBRPersistence.java': '''package zombie.savefile;public class ApocBRPersistence {
        static boolean accept=true;static java.util.List<Runnable> tasks=new java.util.ArrayList<>();
        static boolean request(Runnable task){if(!accept)return false;tasks.add(task);return true;}
        static void failed(java.sql.SQLException error) {}}''',
    'zombie/savefile/ApocBRPostgresConnector.java': '''package zombie.savefile;import java.sql.*;import java.lang.reflect.*;
        public class ApocBRPostgresConnector {static String url;static boolean loseCommit;
        static Connection borrowPlayerConnection()throws SQLException{
          if(Thread.currentThread()==BackendMessagesTest.owner)throw new AssertionError("JDBC on Lua thread");
          Connection connection=DriverManager.getConnection(url,"pz_server_01","");connection.setAutoCommit(false);
          return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            try {Object result=m.invoke(connection,a);if(m.getName().equals("commit")&&loseCommit){loseCommit=false;throw new SQLException("lost acknowledgement","08006");}return result;}
            catch(InvocationTargetException e){throw e.getCause();}});}}''',
    'zombie/savefile/BackendMessagesTest.java': HARNESS,
}

with tempfile.TemporaryDirectory(prefix='apocbr_backend_messages_') as directory:
    work = Path(directory)
    STUBS['zombie/core/BoxedStaticValues.java'] = (ROOT / '42.22.1/decompiled/zombie/core/BoxedStaticValues.java').read_text(encoding='utf-8')
    sources = []
    for name, source in STUBS.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding='utf-8')
        sources.append(path)
    sources.append(ROOT / '42.22.1/src/zombie/savefile/ApocBRModEntities.java')
    cp = os.pathsep.join(map(str, [GAME, ROOT / '42.22.1/lib/postgresql-42.7.14.jar']))
    subprocess.run([str(ROOT / 'jdk/bin/javac.exe'), '--release', '25', '-cp', cp, '-d', str(work / 'classes'), *map(str, sources)], check=True)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    subprocess.run([str(BIN / 'initdb.exe'), '-D', str(work / 'data'), '-U', 'postgres', '-A', 'trust', '--no-locale', '-E', 'UTF8'], check=True, stdout=subprocess.DEVNULL)
    started = False
    try:
        subprocess.run([str(BIN / 'pg_ctl.exe'), '-D', str(work / 'data'), '-l', str(work / 'postgres.log'), '-o', f'-h 127.0.0.1 -p {port}', '-w', 'start'], check=True, stdout=subprocess.DEVNULL)
        started = True
        bootstrap = "CREATE ROLE apocalipsebr LOGIN; CREATE ROLE pz_server_01 LOGIN; GRANT CREATE ON DATABASE postgres TO apocalipsebr; SET ROLE apocalipsebr;\n"
        # Also simulate broad creator defaults: V36 must remove them for the engine.
        sql = bootstrap + (MIGRATIONS / 'V32__Create_Mod_Entities.sql').read_text(encoding='utf-8')
        sql += '\nALTER DEFAULT PRIVILEGES FOR ROLE apocalipsebr IN SCHEMA mod_data GRANT ALL ON TABLES TO pz_server_01;\n'
        sql += (MIGRATIONS / 'V36__Create_Backend_Messages.sql').read_text(encoding='utf-8')
        subprocess.run([str(BIN / 'psql.exe'), '-X', '-h', '127.0.0.1', '-p', str(port), '-U', 'postgres', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1'], input=sql, text=True, check=True, stdout=subprocess.DEVNULL)
        subprocess.run([str(ROOT / 'jdk/bin/java.exe'), '-cp', str(work / 'classes') + os.pathsep + cp, 'zombie.savefile.BackendMessagesTest', f'jdbc:postgresql://127.0.0.1:{port}/postgres'], check=True, timeout=45)
    finally:
        if started:
            subprocess.run([str(BIN / 'pg_ctl.exe'), '-D', str(work / 'data'), '-m', 'immediate', '-w', 'stop'], check=True, stdout=subprocess.DEVNULL)


def runtime(server=True):
    lua = LuaRuntime()
    lua.globals().server = server
    lua.execute('''
        clock=0; sequence=0; messageSequence=0; requests={}; responses={}; logs={}
        function isServer() return server end
        function getTimestampMs() return clock end
        function print(value) logs[#logs+1]=value end
        Events=setmetatable({}, {__index=function(t,name)
            local event={handlers={}}
            event.Add=function(fn) event.handlers[#event.handlers+1]=fn end
            event.fire=function(...) for _,fn in ipairs(event.handlers) do fn(...) end end
            rawset(t,name,event);return event
        end})
        player={cid='life1',session='session1',online=true,bound=true}
        function player:isDead() return self.dead == true end
        function player:getOnlineID() return 1 end
        function getPlayerByOnlineID(id) if id==1 and player.online then return player end end
        function instanceof(obj,name) return obj==player and name=='IsoPlayer' end
        zombie={getAttackedBy=function() return player end}
        ApocBRModEntities={
            define=function(name,def) assert(def.appendOnly);mapping=def end,
            newMessageId=function() messageSequence=messageSequence+1;return 'message'..messageSequence end,
            messageContext=function(p)
                if not p.bound then return {status='IDENTITY_PENDING'} end
                return {status='OK',characterId=p.cid,session=p.session,token=p.session..':'..p.cid}
            end,
            append=function(name,token,id,values)
                if reject then return {accepted=false,status=reject} end
                sequence=sequence+1
                requests[#requests+1]={id=tostring(sequence),token=token,messageId=id,values=values}
                return {accepted=true,id=tostring(sequence)}
            end,
            poll=function() local batch=responses;responses={};return batch end,
        }
        function tick(ms) clock=ms;Events.OnTickEvenPaused.fire() end
        function kill(n) for i=1,n do Events.OnZombieDead.fire(zombie) end end
        function reply(index,status)
            responses[#responses+1]={id=requests[index].id,status=status}
            Events.OnTickEvenPaused.fire()
        end
    ''')
    for path in (PACK / 'media/lua/server/ApocBR').glob('*.lua'):
        lua.globals().source = path.read_text(encoding='utf-8')
        lua.globals().name = 'ApocBR/' + path.stem
        lua.execute('local text=source;package.preload[name]=function() return assert(load(text))() end')
    lua.globals().source = (PACK / 'media/lua/shared/ZKC_Config.lua').read_text(encoding='utf-8')
    lua.execute("local text=source;package.preload['ZKC_Config']=function() return assert(load(text))() end")
    lua.execute((PACK / 'media/lua/server/ZKC_ServerHandler.lua').read_text(encoding='utf-8'))
    return lua

lua = runtime()
lua.execute('''
    tick(30000);assert(#requests==0) -- no online-player heartbeat
    kill(5);tick(59999);assert(#requests==0)
    tick(61000);assert(#requests==1 and requests[1].values.payload=='{"count":5}')
    kill(2);reply(1,'OUTCOME_UNKNOWN')
    local original=requests[1].messageId
    player.online=false;player.dead=true
    tick(92000);assert(#requests==2 and requests[2].messageId==original)
    reply(2,'OK');tick(123000)
    assert(#requests==3 and requests[3].values.payload=='{"count":2}' and requests[3].messageId~=original)
    reply(3,'OK');tick(200000);assert(#requests==3)
    player.online=true;player.dead=false;player.cid='life2';player.session='session2'
    kill(3);reject='UNAVAILABLE';tick(231000);assert(#requests==3)
    local ids=messageSequence;reject=nil;tick(262000)
    assert(#requests==4 and messageSequence==ids and requests[4].token=='session2:life2')
    reply(4,'OK')
    player.bound=false;kill(4);tick(300000);assert(#requests==4)
    player.bound=true;tick(302000);tick(333000)
    assert(#requests==5 and requests[5].values.payload=='{"count":4}')
    reply(5,'OK')
    player.bound=false;kill(2);Events.OnPlayerDeath.fire(player)
    player.bound=true;player.cid='life3';tick(400000);assert(#requests==5)
    assert(not pcall(function() BackendMessages.publish('ArbitraryCommand',BackendMessages.context(player),{},function() end) end))
    assert(not pcall(function() BackendMessages.publish('ZombieKillsRecorded.v1',BackendMessages.context(player),{count=2,x=1},function() end) end))
    assert(not pcall(function() BackendMessages.publish('ZombieKillsRecorded.v1',BackendMessages.context(player),{count=0},function() end) end))
    local before=#requests
    player.online=false;kill(5);tick(500000);assert(#requests==before)
''')
client = runtime(False)
client.execute('assert(BackendMessages==nil and ZKC_ServerHandler==nil and #Events.OnZombieDead.handlers==0)')
bounded = runtime()
bounded.execute('''
    ZKC_Config.maxEntries=1
    kill(101);tick(29000);assert(#requests==0) -- no old count threshold
    player.cid='different-life';player.session='different-session';kill(5)
    tick(30000);assert(#requests==1 and requests[1].values.payload=='{"count":101}')
    reply(1,'SCHEMA_ERROR');tick(61000);assert(#requests==1) -- terminal errors retain/block
    Events.OnClientCommand.fire('ZombieKillsRecorded.v1',player,{count=999})
    tick(92000);assert(#requests==1) -- no client database command handler
''')
assert not (PACK.parent.parent / 'BeyondTen/42/media/lua/server/ApocBR/Entities.lua').exists()
assert 'require=APOCALISEBR_PACK' in (PACK.parent.parent / 'BeyondTen/42/mod.info').read_text(encoding='utf-8')
print('PASS actual Lua 30-second batching, immutable retries, in-flight kills, late lives, identity pending, client isolation and dependency')
