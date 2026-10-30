"""42.22 production bridge scoped transactions with explicit JDBC/identity doubles.

Reuse the existing generic entity fixture, extending its SQL transport doubles.
No running server or PostgreSQL database is changed.
"""
import ast
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
GAME = Path(os.environ.get('APOCBR_TEST_GAME_JAR', r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
tree = ast.parse((ROOT / 'tools/test_mod_entities.py').read_text())
harness = next(ast.literal_eval(n.value) for n in tree.body
               if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'HARNESS' for t in n.targets))
harness = harness.replace('static Thread owner=', '''
 static int writes,failWriteAt,collectionReads;static boolean active=true;
 static Map<Integer,Object> lastWriteParameters;
 static ResultSet activeRows(){int[] index={0};return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
  case "next" -> ++index[0]==1;
  case "getObject" -> UUID.fromString(active?"00000000-0000-0000-0000-000000000001":"00000000-0000-0000-0000-000000000002");
  default -> zero(m.getReturnType());
 });}
 static KahluaTable scoped(String action,KahluaTable rows,String session)throws Exception {
  return (KahluaTable)call("submitCharacter","BeyondTen.Mastery",action,new zombie.characters.IsoPlayer(),session,rows);
 }
 static Thread owner=''')
harness = harness.replace('yield rows(sql.endsWith', 'if(sql.startsWith("SELECT p.active_character_id"))yield activeRows();if(sql.contains("WHERE character_id=? ORDER BY"))collectionReads++;yield rows(sql.endsWith')
harness = harness.replace('parameters=bound;yield changed;', 'parameters=bound;lastWriteParameters=new HashMap<>(bound);writes++;yield writes==failWriteAt?0:changed;')
scoped_tests = r'''
  String session="00000000-0000-0000-0000-000000000010";
  KahluaTable input=table(1.0,table("values",table("perk_id","Aiming","xp",15.0),"revision","0"));
  KahluaTable admitted=scoped("loadOrCreate",input,session);
  check(Boolean.TRUE.equals(admitted.rawget("accepted")),"scoped admission without JDBC");
  KahluaTable result=finish();
  check(result.rawget("status").equals("OK")&&Boolean.FALSE.equals(result.rawget("imported"))&&writes==0,"database rows win without seeding");
  KahluaTable row=(KahluaTable)((KahluaTable)result.rawget("rows")).rawget(1.0);
  check(((KahluaTable)row.rawget("values")).rawget("xp").equals(12.5),"scoped collections marshalled into Lua arrays");
  missing=true;writes=0;
  scoped("loadOrCreate",input,session);
  ((KahluaTable)((KahluaTable)input.rawget(1.0)).rawget("values")).rawset("xp",999.0);
  result=finish();
  check(result.rawget("status").equals("OK")&&Boolean.TRUE.equals(result.rawget("imported"))&&writes==1,"empty collection is initialized atomically");
  check(lastWriteParameters.get(1).equals(UUID.fromString("00000000-0000-0000-0000-000000000001"))&&lastWriteParameters.get(3).equals(15.0),"identity injected and input detached before worker");
  missing=false;writes=0;failWriteAt=2;int oldRollback=rollbacks;
  input.rawset(2.0,table("values",table("perk_id","Reloading","xp",3.0),"revision","7"));
  ((KahluaTable)input.rawget(1.0)).rawset("revision","7");
  scoped("saveAll",input,session);result=finish();
  check(result.rawget("status").equals("CONFLICT")&&writes==2&&rollbacks==oldRollback+1,"second-row conflict rolls entire batch back");
  failWriteAt=0;writes=0;loseCommit=true;
  scoped("saveAll",input,session);check(finish().rawget("status").equals("OUTCOME_UNKNOWN"),"uncertain scoped commit never acknowledged");loseCommit=false;
  writes=0;active=false;
  scoped("saveAll",input,session);check(finish().rawget("status").equals("IDENTITY_PENDING")&&writes==0,"replacement character cannot receive old-life writes");active=true;
  ApocBRPlayerRelationalMutations.current=false;
  scoped("saveAll",input,session);check(finish().rawget("status").equals("STALE_SESSION"),"worker rechecks detached session");
  ApocBRPlayerRelationalMutations.current=true;
  check(scoped("saveAll",input,"wrong-session").rawget("status").equals("STALE_SESSION"),"wrong session rejected before admission");
  ((KahluaTable)((KahluaTable)input.rawget(1.0)).rawget("values")).rawset("character_id","00000000-0000-0000-0000-000000000002");
  invalid(()->scoped("saveAll",input,session));
  ((KahluaTable)((KahluaTable)input.rawget(1.0)).rawget("values")).rawset("character_id",null);
  invalid(()->scoped("loadOrCreate",input,session)); // nonzero seed revision
  KahluaTable tooMany=table();for(int i=1;i<=257;i++)tooMany.rawset((double)i,table("values",table("perk_id","P"+i,"xp",0.0),"revision","0"));
  invalid(()->scoped("loadOrCreate",tooMany,session));
  writes=0;collectionReads=0;
  KahluaTable sparse=table(1.0,table("values",table("perk_id","Aiming","xp",21.0),"revision","7"));
  scoped("patch",sparse,session);result=finish();
  check(result.rawget("status").equals("OK")&&writes==1&&collectionReads==0,"patch writes only submitted rows without full collection reads");
  row=(KahluaTable)((KahluaTable)result.rawget("rows")).rawget(1.0);
  check(row.rawget("revision").equals("8")&&((KahluaTable)row.rawget("values")).rawget("character_id") instanceof String,"patch ack has exact new revision and scalar UUID");
  KahluaTable multi=table("character_id",table("type","uuid","id",true),"perk_id",table("type","string","id",true),"xp",table("type","number"),"zz_count",table("type","integer"));
  call("define","Multi",table("table","multi","fields",multi));
  // A second mutable field must not appear in a sparse UPDATE or its bind list.
  call("submit","Multi","patch",table("character_id","00000000-0000-0000-0000-000000000001","perk_id","Aiming"),table("xp",22.0),"7");
  result=finish();
  check(result.rawget("status").equals("OK")&&lastSql.contains("\"xp\"=?")&&!lastSql.contains("zz_count")&&parameters.size()==4,"generic patch binds only changed column, keys and expected revision");
  call("submitCharacter","Multi","patch",new zombie.characters.IsoPlayer(),session,sparse);
  result=finish();
  check(result.rawget("status").equals("OK")&&!lastSql.contains("zz_count")&&lastWriteParameters.size()==4,"scoped patch also omits unchanged mutable columns");
  sparse.rawset(2.0,table("values",table("perk_id","Reloading","xp",2.0),"revision","7"));
  writes=0;failWriteAt=2;oldRollback=rollbacks;
  scoped("patch",sparse,session);result=finish();
  check(result.rawget("status").equals("CONFLICT")&&writes==2&&rollbacks==oldRollback+1,"sparse batch conflict rolls back every changed row");failWriteAt=0;
  sparse.rawset(2.0,null);
  ((KahluaTable)sparse.rawget(1.0)).rawset("revision","0");
  scoped("patch",sparse,session);result=finish();
  check(result.rawget("status").equals("OK")&&lastSql.startsWith("INSERT")&&((KahluaTable)((KahluaTable)result.rawget("rows")).rawget(1.0)).rawget("revision").equals("1"),"new row patch inserts once with revision one");
  invalid(()->call("submitCharacter","Multi","patch",new zombie.characters.IsoPlayer(),session,sparse)); // incomplete insert
  invalid(()->submit("patch",table("character_id","00000000-0000-0000-0000-000000000001","perk_id","Aiming"),table(),"7"));
  invalid(()->submit("patch",table("character_id","00000000-0000-0000-0000-000000000001","perk_id","Aiming"),table("perk_id","other","xp",1.0),"7"));
  invalid(()->scoped("patch",table(1.0,table("values",table("perk_id","Aiming"),"revision","7")),session));
  writes=0;commits=0;rollbacks=0;
'''
harness = harness.replace('  KahluaTable keys=', scoped_tests + '\n  KahluaTable keys=')
stubs = {
    'zombie/core/Core.java': 'package zombie.core; public class Core {public static boolean debug=false;}',
    'zombie/Lua/LuaManager.java': '''package zombie.Lua; public class LuaManager {
      public static class Platform {public se.krka.kahlua.vm.KahluaTable newTable(){return new se.krka.kahlua.j2se.KahluaTableImpl(new java.util.HashMap<>());}}
      public static Platform platform=new Platform();public static se.krka.kahlua.vm.KahluaTable env=platform.newTable();}''',
    'zombie/network/GameServer.java': 'package zombie.network; public class GameServer {public static boolean server=true;}',
    'zombie/characters/IsoPlayer.java': 'package zombie.characters; public class IsoPlayer {}',
    'zombie/savefile/ApocBRPlayerRelationalMutations.java': '''package zombie.savefile;
      public class ApocBRPlayerRelationalMutations {
        static boolean current=true;
        record Candidate(String world,String identity,int slot,boolean coop) {}
        record EntityContext(Candidate candidate,java.util.UUID session,java.util.UUID character) {}
        static EntityContext entityContext(zombie.characters.IsoPlayer player){return new EntityContext(
          new Candidate("test","trusted-account",0,false),java.util.UUID.fromString("00000000-0000-0000-0000-000000000010"),
          java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));}
        static boolean entityContextCurrent(EntityContext context){return current;}
      }''',
    'zombie/savefile/ApocBRPersistence.java': '''package zombie.savefile; public class ApocBRPersistence {
      static boolean accept=true;static java.util.List<Runnable> tasks=new java.util.ArrayList<>();
      static boolean request(Runnable task){if(!accept)return false;tasks.add(task);return true;}
      static void failed(java.sql.SQLException failure){} }''',
    'zombie/savefile/ApocBRPostgresConnector.java': '''package zombie.savefile; public class ApocBRPostgresConnector {
      static java.sql.Connection borrowPlayerConnection(){return ModEntitiesTest.connection();}}''',
    'zombie/savefile/ModEntitiesTest.java': harness,
}
with tempfile.TemporaryDirectory(prefix='apocbr_character_entities_') as folder:
    target = Path(folder)
    sources = []
    for name, contents in stubs.items():
        path = target / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
        sources.append(path)
    sources.append(ROOT / '42.22.0/src/zombie/savefile/ApocBRModEntities.java')
    subprocess.run([str(ROOT/'jdk/bin/javac.exe'), '--release', '25', '-cp', str(GAME), '-d', folder, *map(str, sources)], check=True)
    subprocess.run([str(ROOT/'jdk/bin/java.exe'), '-cp', folder+os.pathsep+str(GAME), 'zombie.savefile.ModEntitiesTest'], check=True, timeout=30)
