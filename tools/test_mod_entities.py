"""Exercise production Java SQL mapping/async boundary and Lua repository lifecycle.
JDBC transport and worker admission are stubbed; no live database is modified.
"""
from pathlib import Path
import os
import subprocess
import tempfile
import argparse
from lupa import LuaRuntime

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--version', default='42.21.0', choices=['42.21.0', '42.22.0', '42.22.1', '42.22.2'])
VERSION = parser.parse_args().version
GAME = Path(os.environ.get('APOCBR_TEST_GAME_JAR', r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
PACK = Path(os.environ.get('APOCBR_TEST_PACK', r'C:\Users\ricar\Zomboid\Workshop\apocbr\Contents\mods\ApocalisebrPack\42.14'))
MOD = Path(os.environ.get('APOCBR_TEST_BEYONDTEN', r'C:\Users\ricar\Zomboid\Workshop\apocbr\Contents\mods\BeyondTen\42'))

HARNESS = r'''
package zombie.savefile;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import se.krka.kahlua.vm.*;
import se.krka.kahlua.j2se.KahluaTableImpl;
public class ModEntitiesTest {
 static int checks;static boolean missing,wrongType,wrongPK,loseCommit;static int changed=1,commits,rollbacks;
 static String lastSql;static Map<Integer,Object> parameters=new HashMap<>();
 static Thread owner=Thread.currentThread();
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
 static Object zero(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
 static <T>T proxy(Class<T> type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler));}
 static KahluaTable table(Object... pairs){KahluaTable t=new KahluaTableImpl(new HashMap<>());for(int i=0;i<pairs.length;i+=2)t.rawset(pairs[i],pairs[i+1]);return t;}
 static Object call(String name,Object... args)throws Exception {
  Method method=Arrays.stream(ApocBRModEntities.class.getDeclaredMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow();method.setAccessible(true);
  try{return method.invoke(null,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
 }
 static Connection connection(){
  check(Thread.currentThread()!=owner,"JDBC never executes on submit/poll thread");
  return proxy(Connection.class,(p,m,a)->switch(m.getName()) {
   case "prepareStatement" -> statement((String)a[0]);
   case "getMetaData" -> proxy(DatabaseMetaData.class,(d,n,b)->n.getName().equals("getPrimaryKeys")?rows(List.of("character_id",wrongPK?"wrong":"perk_id"),true):zero(n.getReturnType()));
   case "commit" -> {commits++;if(loseCommit)throw new SQLException("PRIVATE_DRIVER","08006");yield null;}
   case "rollback" -> {rollbacks++;yield null;}
   default -> zero(m.getReturnType());
  });
 }
 static ResultSet rows(List<String> names,boolean pk){int[] cursor={-1};return proxy(ResultSet.class,(p,m,a)->switch(m.getName()) {
  case "next" -> ++cursor[0]<names.size();
  case "getString" -> names.get(cursor[0]);
  case "getLong" -> 7L;
  case "getObject" -> switch((String)a[0]){case "character_id"->UUID.fromString("00000000-0000-0000-0000-000000000001");case "perk_id"->"Aiming";default->12.5;};
  case "getMetaData" -> proxy(ResultSetMetaData.class,(d,n,b)->switch(n.getName()){
   case "getColumnType" -> switch((int)b[0]){case 1->Types.OTHER;case 2->Types.VARCHAR;case 3->wrongType?Types.INTEGER:Types.DOUBLE;default->Types.BIGINT;};
   case "getColumnTypeName" -> "uuid";
   default -> zero(n.getReturnType());});
  default -> zero(m.getReturnType());
 });}
 static PreparedStatement statement(String sql){Map<Integer,Object> bound=new HashMap<>();return proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()) {
  case "setObject","setLong" -> {bound.put((Integer)a[0],a[1]);yield null;}
  case "setQueryTimeout" -> {check((int)a[0]==2,"bounded statement timeout");yield null;}
  case "executeQuery" -> {lastSql=sql;parameters=bound;yield rows(sql.endsWith("WHERE false")||missing?List.of():List.of("row"),false);}
  case "executeUpdate" -> {lastSql=sql;parameters=bound;yield changed;}
  default -> zero(m.getReturnType());
 });}
 static KahluaTable submit(String action,KahluaTable keys,KahluaTable values,String revision)throws Exception {
  return (KahluaTable)call("submit","BeyondTen.Mastery",action,keys,values,revision);
 }
 static KahluaTable finish()throws Exception {
  List<Runnable> tasks=new ArrayList<>(ApocBRPersistence.tasks);ApocBRPersistence.tasks.clear();
  Thread worker=new Thread(()->tasks.forEach(Runnable::run));worker.start();worker.join();
  return (KahluaTable)((KahluaTable)call("poll")).rawget(1);
 }
 static void invalid(Throwing work)throws Exception {try{work.run();throw new AssertionError("invalid accepted");}catch(IllegalArgumentException expected){checks++;}}
 interface Throwing{void run()throws Exception;}
 public static void main(String[] args)throws Exception {
  ApocBRModEntities.install();
  KahluaTable fields=table("character_id",table("type","uuid","id",true),"perk_id",table("type","string","id",true),"xp",table("type","number"));
  check(Boolean.TRUE.equals(call("define","BeyondTen.Mastery",table("table","beyondten_mastery","fields",fields))),"mapping defined");
  invalid(()->call("define","Injection",table("table","x;drop table y","fields",fields)));
  KahluaTable keys=table("character_id","00000000-0000-0000-0000-000000000001","perk_id","O'Connor");
  KahluaTable values=table("character_id",keys.rawget("character_id"),"perk_id",keys.rawget("perk_id"),"xp",10.0);
  check(Boolean.TRUE.equals(submit("save",keys,values,"0").rawget("accepted"))&&commits==0,"submission enqueues without JDBC");
  values.rawset("xp",999.0);
  check(finish().rawget("status").equals("OK"),"insert completion follows commit");
  check(lastSql.startsWith("INSERT INTO mod_data.\"beyondten_mastery\"")&&!lastSql.contains("O'Connor"),"SQL stays Java-side with bound values");
  check(parameters.get(2).equals("O'Connor")&&parameters.get(3).equals(10.0),"input detached before worker executes");
  submit("save",keys,values,"7");check(finish().rawget("revision").equals("8"),"update increments string revision");
  check(lastSql.contains("revision=revision+1")&&lastSql.endsWith("AND revision=?")&&parameters.get(4).equals(7L),"optimistic update binds expected revision");
  changed=0;submit("save",keys,values,"7");check(finish().rawget("status").equals("CONFLICT"),"stale update reported");changed=1;
  submit("find",keys,table(),"0");KahluaTable loaded=finish();check(loaded.rawget("revision").equals("7")&&((KahluaTable)loaded.rawget("values")).rawget("xp").equals(12.5),"find reconstructs detached result");
  missing=true;submit("find",keys,table(),"0");check(finish().rawget("status").equals("NOT_FOUND"),"missing distinct from failure");missing=false;
  wrongType=true;submit("validate",table(),table(),"0");check(finish().rawget("status").equals("SCHEMA_ERROR"),"wrong schema rejected");wrongType=false;
  wrongPK=true;submit("validate",table(),table(),"0");check(finish().rawget("status").equals("SCHEMA_ERROR"),"wrong primary key rejected");wrongPK=false;
  submit("delete",keys,table(),"7");check(finish().rawget("status").equals("OK")&&lastSql.startsWith("DELETE"),"delete checks revision");
  loseCommit=true;submit("save",keys,values,"7");check(finish().rawget("status").equals("OUTCOME_UNKNOWN"),"lost commit acknowledgement is not success or safe retry");loseCommit=false;
  values.rawset("xp",Double.NaN);invalid(()->submit("save",keys,values,"0"));values.rawset("xp",1.0);
  values.rawset("unknown",1.0);invalid(()->submit("save",keys,values,"0"));values.rawset("unknown",null);
  invalid(()->submit("find",table("character_id","bad","perk_id","Aiming"),table(),"0"));
  ApocBRPersistence.accept=false;check(submit("find",keys,table(),"0").rawget("status").equals("UNAVAILABLE"),"admission failure synchronous");ApocBRPersistence.accept=true;
  for(int i=0;i<256;i++)check(Boolean.TRUE.equals(submit("find",keys,table(),"0").rawget("accepted")),"bounded admission");
  check(submit("find",keys,table(),"0").rawget("status").equals("BUSY"),"completion reservation bounds abandoned requests");
  final boolean[] rejected={false};Thread other=new Thread(()->{try{call("guard");}catch(Exception e){rejected[0]=true;}});other.start();other.join();check(rejected[0],"unrelated threads cannot use Lua bridge");
  zombie.network.GameServer.server=false;
  try{call("guard");throw new AssertionError("client bridge access");}catch(IllegalStateException expected){checks++;}
  System.out.println("Java entity checks="+checks);
 }
}
'''

if VERSION == '42.22.2':
    HARNESS = HARNESS.replace('static int checks;', 'static boolean serverMapping; static int collectionCount=1; static int checks;')
    HARNESS = HARNESS.replace('List.of("character_id",wrongPK?"wrong":"perk_id")', 'serverMapping?List.of("server_id","world"):List.of("character_id",wrongPK?"wrong":"perk_id")')
    HARNESS = HARNESS.replace('case "character_id"->UUID', 'case "server_id"->1L;case "world"->"world-a";case "character_id"->UUID')
    HARNESS = HARNESS.replace('case 1->Types.OTHER;', 'case 1->serverMapping?Types.BIGINT:Types.OTHER;')
    HARNESS = HARNESS.replace('List.of("row"),false)', 'Collections.nCopies(serverMapping?collectionCount:1,"row"),false)')
    extra = r'''
  invalid(()->call("listForServer","BeyondTen.Mastery"));
  serverMapping=true;
  call("define","Test.Scoped",table("table","scoped","fields",table("server_id",table("type","integer","id",true),"world",table("type","string","id",true),"xp",table("type","number"))));
  check(Boolean.TRUE.equals(((KahluaTable)call("listForServer","Test.Scoped")).rawget("accepted")),"server list queues");
  zombie.iso.IsoWorld.world="changed-after-submit";
  KahluaTable collection=finish();
  check(collection.rawget("status").equals("OK")&&((KahluaTable)collection.rawget("rows")).rawget(1)!=null,"server list result");
  check(lastSql.contains("WHERE server_id=? AND world=?")&&lastSql.endsWith("LIMIT 257")&&parameters.get(1).equals(1L)&&parameters.get(2).equals("world-a"),"engine scope captured before worker and bound");
  collectionCount=0;call("listForServer","Test.Scoped");check(finish().rawget("status").equals("OK"),"empty scope success");
  collectionCount=257;call("listForServer","Test.Scoped");KahluaTable overflow=finish();check(overflow.rawget("status").equals("LIMIT_EXCEEDED")&&overflow.rawget("rows")==null,"oversize never returns partial rows");
  collectionCount=1;serverMapping=false;
'''
    HARNESS = HARNESS.replace('  for(int i=0;i<256;i++)check', extra+'  for(int i=0;i<256;i++)check')
    HARNESS = HARNESS.replace('  for(int i=0;i<256;i++)check', r'''
  KahluaTable document=table("text","PTBR\u00e7", "items",table(1.0,table("count",2.0,"active",true)));
  String encoded=(String)call("encode",document);
  KahluaTable decoded=(KahluaTable)call("decode",encoded);
  check(decoded.rawget("text").equals(document.rawget("text")),"bounded JSON Unicode roundtrip");
  check(((KahluaTable)((KahluaTable)decoded.rawget("items")).rawget(1.0)).rawget("count").equals(2.0),"JSON preserves numeric array keys and nested values");
  invalid(()->call("decode","{} trailing"));
  KahluaTable cyclic=table();cyclic.rawset("self",cyclic);invalid(()->call("encode",cyclic));
  KahluaTable bigFields=table("record_id",table("type","string","id",true),"payload",table("type","text"));
  check(Boolean.TRUE.equals(call("define","Wallet.Payload",table("table","wallet_test","fields",bigFields))),"bounded text mapping supported");
  check(Boolean.TRUE.equals(((KahluaTable)call("submit","Wallet.Payload","save",table("record_id","one"),table("record_id","one","payload","x".repeat(5000)),"0")).rawget("accepted")),"payload over old 4096 string limit admitted as text");
  finish();
'''+ '  for(int i=0;i<256;i++)check')

if VERSION == '42.22.2':
    HARNESS = HARNESS.replace('static boolean serverMapping;', 'static int documentQueries; static boolean documentMissing; static boolean serverMapping;')
    HARNESS = HARNESS.replace('case "setObject","setLong"', 'case "setObject","setLong","setString"')
    HARNESS = HARNESS.replace('yield rows(sql.endsWith', 'if(sql.contains("entity_document")){documentQueries++;yield documentRows(sql);}yield rows(sql.endsWith')
    HARNESS = HARNESS.replace(' static KahluaTable submit(', r''' static ResultSet documentRows(String sql){int[] cursor={0};return proxy(ResultSet.class,(p,m,a)->switch(m.getName()) {
      case "next" -> ++cursor[0]<=(documentMissing?0:1);
      case "getLong" -> 8L;
      case "getString" -> sql.contains("mutate_entity_document")?"OK":switch((int)a[0]){case 2->"purchases";case 3->"one";default->"{\"value\":{\"amount\":1}}";};
      default -> zero(m.getReturnType());
    });}
 static KahluaTable submit(''')
    HARNESS = HARNESS.replace('  for(int i=0;i<256;i++)check', r'''
  zombie.iso.IsoWorld.world="document-world";
  KahluaTable docOps=table(1.0,table("collection","purchases","key","one","payload","{\"value\":1}"));
  String token="00000000-0000-0000-0000-000000000099";
  int docBefore=documentQueries;
  KahluaTable admission=(KahluaTable)call("documentRequest","Wallet.Test","7",token,docOps);
  check(Boolean.TRUE.equals(admission.rawget("accepted"))&&documentQueries==docBefore,"mutation admission never queries on Lua thread");
  ((KahluaTable)docOps.rawget(1.0)).rawset("payload","changed-after-submit");
  zombie.iso.IsoWorld.world="changed-after-submit";
  KahluaTable docResult=finish();
  check(docResult.rawget("status").equals("OK")&&docResult.rawget("revision").equals("8"),"mutation result follows transaction commit");
  check(parameters.get(1).equals(1L)&&parameters.get(2).equals("document-world")&&parameters.get(3).equals("Wallet.Test"),"mutation scope captured from engine");
  check(((String)parameters.get(6)).contains("value")&&!((String)parameters.get(6)).contains("changed-after-submit"),"mutation batch detached before worker");
  check(documentQueries==docBefore+1,"one mutation SQL statement without repeated catalog validation");
  call("documentRequest","Wallet.Test",null,null,null);docResult=finish();
  check(docResult.rawget("status").equals("OK")&&((KahluaTable)docResult.rawget("records")).rawget(1)!=null,"document startup reconstructs records with revision");
  documentMissing=true;call("documentRequest","Wallet.Test",null,null,null);check(finish().rawget("status").equals("NOT_FOUND"),"missing document explicit");documentMissing=false;
  loseCommit=true;call("documentRequest","Wallet.Test","7",token,table());check(finish().rawget("status").equals("OUTCOME_UNKNOWN"),"lost document commit remains ambiguous");loseCommit=false;
  invalid(()->call("documentRequest","bad name","0",token,table()));
  invalid(()->call("documentRequest","Wallet.Test","-1",token,table()));
  invalid(()->call("documentRequest","Wallet.Test","0","invalid-token",table()));
  invalid(()->call("documentRequest","Wallet.Test","0",token,table(2.0,table("key","sparse"))));
  KahluaTable tooMany=table();for(int i=1;i<=257;i++)tooMany.rawset((double)i,table());
  invalid(()->call("documentRequest","Wallet.Test","7",token,tooMany));
  ApocBRPersistence.accept=false;check(((KahluaTable)call("documentRequest","Wallet.Test",null,null,null)).rawget("status").equals("UNAVAILABLE"),"document admission reports unavailable");ApocBRPersistence.accept=true;
'''+'  for(int i=0;i<256;i++)check')

with tempfile.TemporaryDirectory(prefix='apocbr_mod_entities_') as folder:
    target = Path(folder)
    sources = []
    stubs = {
        # Keep the Kahlua fixture in one unsigned package with its Core double.
        'zombie/core/BoxedStaticValues.java': (ROOT / '42.22.1/decompiled/zombie/core/BoxedStaticValues.java').read_text(encoding='utf-8'),
        'zombie/savefile/ApocBRPayloadProvenance.java': 'package zombie.savefile; public class ApocBRPayloadProvenance {public static int configuredServerId(){return 1;}}',
        'zombie/core/Core.java': 'package zombie.core; public class Core {public static boolean debug=false;}',
        'zombie/Lua/LuaManager.java': '''package zombie.Lua; public class LuaManager {
          public static class Platform {public se.krka.kahlua.vm.KahluaTable newTable(){return new se.krka.kahlua.j2se.KahluaTableImpl(new java.util.HashMap<>());}}
          public static Platform platform=new Platform();public static se.krka.kahlua.vm.KahluaTable env=platform.newTable();}''',
        'zombie/iso/IsoWorld.java': 'package zombie.iso; public class IsoWorld { public static IsoWorld instance=new IsoWorld(); public static String world="world-a"; public String getWorld(){return world;} }',
        'zombie/network/GameServer.java': 'package zombie.network; public class GameServer {public static boolean server=true;}',
        'zombie/characters/IsoPlayer.java': 'package zombie.characters; public class IsoPlayer { public long getSteamID(){return 76561198000000001L;}}',
        'zombie/savefile/ApocBRPlayerRelationalMutations.java': '''package zombie.savefile;
          public class ApocBRPlayerRelationalMutations {
            record Candidate(String world,String identity,int slot,boolean coop) {}
            record EntityContext(Candidate candidate,java.util.UUID session,java.util.UUID character) {}
            static EntityContext entityContext(zombie.characters.IsoPlayer player){return null;}
            static boolean entityContextCurrent(EntityContext context){return true;}
          }''',
        'zombie/savefile/ApocBRPersistence.java': '''package zombie.savefile; public class ApocBRPersistence {
          static boolean accept=true;static java.util.List<Runnable> tasks=new java.util.ArrayList<>();
          static boolean request(Runnable task){if(!accept)return false;tasks.add(task);return true;}
          static void failed(java.sql.SQLException failure){} }''',
        'zombie/savefile/ApocBRPostgresConnector.java': '''package zombie.savefile; public class ApocBRPostgresConnector {
          static java.sql.Connection borrowPlayerConnection(){return ModEntitiesTest.connection();}}''',
        'zombie/savefile/ModEntitiesTest.java': HARNESS,
    }
    for name, contents in stubs.items():
        path = target / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding='utf-8')
        sources.append(path)
    sources.append(ROOT / VERSION / 'src/zombie/savefile/ApocBRModEntities.java')
    subprocess.run([str(ROOT/'jdk/bin/javac.exe'), '--release', '25', '-cp', str(GAME), '-d', folder, *map(str, sources)], check=True)
    subprocess.run([str(ROOT/'jdk/bin/java.exe'), '-cp', folder+os.pathsep+str(GAME), 'zombie.savefile.ModEntitiesTest'], check=True, timeout=30)

lua = LuaRuntime()
lua.execute('''
function isServer() return true end
function require(name) return ApocBREntities end
Events = {OnTickEvenPaused = {Add = function(fn) tick = fn end},
          OnServerStarted = {Add = function(fn) startup = fn end}}
nextId = 0
requests = {}; responses = {}; reject = false
ApocBRModEntities = {
 define = function(name, definition) return true end,
 submit = function(name, op, keys, values, revision)
   if reject then return {accepted = false, status = 'UNAVAILABLE'} end
   nextId = nextId + 1; local id = tostring(nextId)
   requests[id] = {op=op, keys=keys, values=values, revision=revision}
   return {accepted = true, id = id}
 end,
 poll = function() local result = responses; responses = {}; return result end,
}
''')
lua.execute((PACK / 'media/lua/server/ApocBR/Entities.lua').read_text(encoding='utf-8'))
lua.execute((MOD / 'media/lua/server/BeyondTen/Persistence/MasteryEntity.lua').read_text(encoding='utf-8'))
lua.execute('''
assert(next(requests) == nil) -- Scaffold causes no automatic DB operations.
startup()
responses = {{id='1', status='OK'}}; tick()
requests = {}; nextId = 0
local repo = BeyondTenPersistence.Mastery
local entity = repo:new({character_id='trusted', perk_id='Aiming', xp=10})
assert(entity:isDirty() and entity:getRevision() == '0')
local callbacks = 0
entity:save(function(result) assert(result.status == 'OK'); callbacks = callbacks+1 end)
assert(callbacks == 0 and requests['1'].values.xp == 10)
entity:set('xp', 15)
assert(requests['1'].values.xp == 10) -- in-flight capture cannot mutate.
assert(not pcall(function() entity:save(function() end) end))
responses = {{id='1', status='OK', revision='1'}}; tick()
assert(callbacks == 1 and entity:isDirty() and entity:getRevision() == '1')
entity:save(function() error('consumer callback failure') end)
responses = {{id='2', status='OK', revision='2'}}; tick()
assert(not entity:isDirty() and not entity._inFlight)
entity:set('xp', 20)
entity:save(function(result) assert(result.status == 'CONFLICT') end)
responses = {{id='3', status='CONFLICT'}}; tick()
assert(entity:isDirty() and entity:getRevision() == '2')
assert(not pcall(function() entity:set('character_id', 'other') end))
reject = true
entity:save(function(result) assert(result.status == 'UNAVAILABLE') end)
assert(not entity._inFlight and entity:isDirty())
reject = false
repo:find({character_id='trusted', perk_id='Aiming'}, function(result)
 assert(result.entity:get('xp') == 40 and not result.entity:isDirty())
 assert(result.entity:getRevision() == '9007199254740993')
end)
responses = {{id='4', status='OK', revision='9007199254740993', values={character_id='trusted', perk_id='Aiming', xp=40}}}; tick()
entity:delete(function(result) assert(result.status == 'OK') end)
responses = {{id='5', status='OK'}}; tick()
assert(not pcall(function() entity:set('xp', 99) end))
''')
print('Lua entity lifecycle checks passed')

if VERSION == '42.22.2':
    lua.execute('''
    ApocBRModEntities.listForServer = function(name)
        nextId = nextId + 1
        return { accepted = true, id = tostring(nextId) }
    end
    local repo = ApocBREntities.define('Test.Scoped', {table='scoped', fields={server_id={type='integer',id=true},world={type='string',id=true},xp={type='number'}}})
    local called = false
    repo:listForServer(function(result)
        called=true
        assert(result.status=='OK' and result.entities[1]:get('xp')==42)
        assert(result.entities[1]:getRevision()=='9' and not result.entities[1]:isDirty())
    end)
    responses={{id=tostring(nextId),status='OK',rows={{values={server_id=1,world='world-a',xp=42},revision='9'}}}}
    tick(); assert(called)
    ApocBRModEntities.listForServer=function() return {accepted=false,status='UNAVAILABLE'} end
    local failed=false
    assert(repo:listForServer(function(r) failed=r.status=='UNAVAILABLE' end)==false and failed)
    ''')
    print('Lua scoped collection checks passed')
