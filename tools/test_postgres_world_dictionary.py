"""42.21.0 native dictionary capture, V6 JDBC publication and listener lifecycle; no live DB."""
from postgres_test_support import inactive_persistence_stubs

from pathlib import Path
import os, re, subprocess, tempfile

ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'42.21.0/src/zombie'
GAME=Path(os.environ.get('APOCBR_TEST_GAME_JAR',r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
JDK=ROOT/'jdk/bin'
LIB=ROOT/'42.21.0/lib'
MIGRATION=ROOT.parent/'apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V6__Create_World_Dictionary.sql'
if MIGRATION.exists():
    ddl=MIGRATION.read_text(encoding='utf-8')
    tables={name:set(re.findall(r'^    (\w+) (?:uuid|text|integer|bigint|smallint|boolean|timestamptz|jsonb)\b',body,re.M))
            for name,body in re.findall(r'CREATE TABLE player_rel\.(world_dictionary_\w+) \((.*?)\n\);',ddl,re.S)}
    repository=(SRC/'savefile/ApocBRWorldDictionaryDatabase.java').read_text(encoding='utf-8')
    v7=(MIGRATION.parent/'V7__Add_Server_And_Payload_Provenance.sql').read_text(encoding='utf-8')
    assert 'ALTER TABLE player_rel.world_dictionary_runtime_view ADD COLUMN server_id integer;' in v7
    tables["world_dictionary_runtime_view"].add("server_id")
    written=set()
    for table,columns in re.findall(r'INSERT INTO player_rel\.(world_dictionary_\w+)\(([^)]+)\)',repository):
        assert table in tables and set(columns.split(','))<=tables[table],f'V6 insert contract drift: {table}'
        written.add(table)
    assert len(written)==9,'Publication must cover all V6 tables'
SNAPSHOT=r'''
package zombie.world;
import java.util.*;import java.lang.reflect.*;import java.nio.*;
public class DictionarySnapshotFixture {
 static int checks;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
 @SuppressWarnings("unchecked") static Map<Short,Object> scriptMap(Object register)throws Exception{var f=register.getClass().getSuperclass().getDeclaredField("idMap");f.setAccessible(true);return (Map<Short,Object>)f.get(register);}
 @SuppressWarnings("unchecked") static Map<Short,Object> stringMap()throws Exception{var f=StringDictionary.StringRegister.class.getDeclaredField("idToStringMap");f.setAccessible(true);return (Map<Short,Object>)f.get(StringDictionary.Generic);}
 public static ApocBRWorldDictionarySnapshot sample()throws Exception {
  DictionaryData data=new DictionaryData();data.reset();StringDictionary.reset();ScriptsDictionary.reset();
  ItemInfo item=new ItemInfo();item.registryId=0;item.moduleName="Base";item.name="Axe";item.fullType="Base.Axe";
  item.modId="example";item.isModded=true;item.existsAsVanilla=true;item.modOverrides=new ArrayList<>(List.of("pz-vanilla","old-mod"));item.isLoaded=true;
  data.itemIdToInfoMap.put((short)0,item);data.itemTypeToInfoMap.put(item.fullType,item);data.nextInfoId=1;
  data.objectIdToNameMap.put((byte)0,"tree");data.objectNameToIdMap.put("tree",(byte)0);data.mextObjectNameId=1;
  data.spriteIdToNameMap.put(42,"sprite");data.spriteNameToIdMap.put("sprite",42);data.nextSpriteNameId=0;
  DictionaryStringInfo text=new DictionaryStringInfo();text.registryId=0;text.string="Base.TestString";text.isLoaded=true;stringMap().put((short)0,text);
  var namedString=StringDictionary.StringRegister.class.getDeclaredField("stringToIdMap");namedString.setAccessible(true);((Map)namedString.get(StringDictionary.Generic)).put(text.string,text);
  var nextString=StringDictionary.StringRegister.class.getDeclaredField("nextId");nextString.setAccessible(true);nextString.setShort(StringDictionary.Generic,(short)1);
  DictionaryScriptInfo<?> script=new DictionaryScriptInfo<>();script.registryId=0;script.name="Base.TestConfig";script.isLoaded=true;script.version=Long.MIN_VALUE+9;
  scriptMap(ScriptsDictionary.spriteConfigs).put((short)0,script);
  var namedScript=ScriptsDictionary.ScriptRegister.class.getDeclaredField("namedMap");namedScript.setAccessible(true);((Map)namedScript.get(ScriptsDictionary.spriteConfigs)).put(script.name,script);
  var next=ScriptsDictionary.ScriptRegister.class.getDeclaredField("nextId");next.setAccessible(true);next.setShort(ScriptsDictionary.spriteConfigs,(short)1);
  ByteBuffer bytes=ByteBuffer.allocate(1024*1024);data.saveToByteBuffer(bytes);bytes.flip();data.serverDataCache=new byte[bytes.remaining()];bytes.get(data.serverDataCache);
  byte[] before=data.serverDataCache.clone();var snapshot=ApocBRWorldDictionarySnapshot.capture(data);
  check(snapshot.registries().size()==7,"empty native registers preserved");check(snapshot.entries().size()==5,"all dictionary sections captured");
  var captured=snapshot.entries().stream().filter(e->e.kind().equals("item")).findFirst().orElseThrow();
  check(captured.loaded()&&captured.overrides().equals(List.of("pz-vanilla","old-mod")),"live availability and override order captured");
  check(snapshot.entries().stream().anyMatch(e->e.kind().equals("script")&&e.version()==Long.MIN_VALUE+9),"signed script hash preserved");
  check(Arrays.equals(before,data.serverDataCache)&&data.nextInfoId==1,"capture leaves vanilla cache and allocation untouched");
  script.version=5;item.modOverrides.clear();check(captured.overrides().size()==2,"detached override copy");
  try{snapshot.entries().clear();throw new AssertionError("mutable snapshot");}catch(UnsupportedOperationException expected){checks++;}
  try{ApocBRWorldDictionarySnapshot.decode(Arrays.copyOf(before,before.length-1),data);throw new AssertionError("truncated accepted");}catch(java.io.IOException expected){checks++;}
  System.out.println("Native dictionary checks="+checks);return snapshot;
 }
 public static void main(String[] args)throws Exception{sample();}
}
'''
DATABASE=r'''
package zombie.savefile;
import java.sql.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;
import zombie.world.*;
import org.postgresql.*;
public class DictionaryDatabaseTest {
 static int checks;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
 static Object zero(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
 static Object proxy(Class<?> type,InvocationHandler handler){return Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);}
 static class Fake {
  int opened,closed,commits,rollbacks,notifications;boolean failNotify,failBatch;long revision;String name;
  List<String> order=new ArrayList<>();Map<String,List<Object>> registries=new LinkedHashMap<>(),entries=new LinkedHashMap<>();
  List<Object[]> foreign=List.of();List<Object> viewValues;String event;String failPrefix;SQLException injected;
  List<List<Object>> states=new ArrayList<>(),overrides=new ArrayList<>(),registryStates=new ArrayList<>();
  ResultSet rows(List<Object[]> values){int[] index={-1};return (ResultSet)proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
   case "next"->++index[0]<values.size();case "getString","getObject"->values.get(index[0])[(int)a[0]-1];case "getInt"->((Number)values.get(index[0])[(int)a[0]-1]).intValue();case "getLong"->((Number)values.get(index[0])[(int)a[0]-1]).longValue();default->zero(m.getReturnType());});}
  List<Object[]> select(String sql,Map<Integer,Object> a)throws Exception {
   if(sql.startsWith("SELECT current_revision"))return List.<Object[]>of(new Object[]{revision,name});
   if(sql.startsWith("SELECT v.id"))return viewValues!=null&&viewValues.get(0).equals(a.get(1))&&viewValues.get(3).equals(a.get(2))&&viewValues.get(4).equals(a.get(3))&&viewValues.get(2).equals(a.get(4))?List.<Object[]>of(new Object[]{viewValues.get(1)}):List.of();
   if(sql.contains("FROM player_rel.world_dictionary_registry_state WHERE"))return registryStates.stream().map(v->v.subList(2,5).toArray()).toList();
   if(sql.contains("FROM player_rel.world_dictionary_entry_state WHERE"))return states.stream().map(v->v.subList(2,14).toArray()).toList();
   if(sql.contains("FROM player_rel.world_dictionary_mod_override WHERE"))return overrides.stream().sorted(Comparator.comparing((List<Object> v)->v.get(2)+":"+v.get(3)+":"+v.get(4)).thenComparingInt(v->(Integer)v.get(6))).map(v->new Object[]{v.get(2),v.get(3),v.get(4),v.get(7)}).toList();
   if(sql.startsWith("SELECT 1 FROM"))return List.of();
   if(sql.startsWith("SELECT family"))return registries.values().stream().map(v->new Object[]{v.get(1),v.get(2),v.get(3),v.get(4)}).toList();
   if(sql.startsWith("SELECT family,register_name,native_id"))throw new AssertionError("unreachable");
   if(sql.startsWith("SELECT DISTINCT"))return foreign;
   if(sql.startsWith("SELECT pg_notify")){order.add("notify");if(failNotify)throw new SQLException("secret SQL","08006");notifications++;return List.of();}
   throw new AssertionError(sql);
  }
  int write(String sql,List<Object> a)throws Exception {
   order.add(sql);if(injected!=null&&sql.startsWith(failPrefix))throw injected;
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_runtime_view(")){check(sql.contains("server_id")&&!sql.contains("server_key"),"publication supplies integer ID and lets V7 bind canonical key");viewValues=a;states.clear();overrides.clear();registryStates.clear();}
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_registry_state("))registryStates.add(new ArrayList<>(a));
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_entry_state("))states.add(new ArrayList<>(a));
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_mod_override("))overrides.add(new ArrayList<>(a));
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_journal"))event=(String)a.get(3);
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_namespace")){name=(String)a.get(1);return 1;}
   if(sql.startsWith("UPDATE player_rel.world_dictionary_namespace")){revision=((Number)a.get(0)).longValue();return 1;}
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_registry(")){registries.put(a.get(1)+":"+a.get(2),new ArrayList<>(a));return 1;}
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_entry(")){String key=a.get(1)+":"+a.get(2)+":"+a.get(3);check(!entries.containsKey(key),"identity not rewritten");entries.put(key,new ArrayList<>(a));return 1;}
   return 1;
  }
  Connection open(){opened++;return (Connection)proxy(Connection.class,(p,m,a)->{
   switch(m.getName()){
    case "close":closed++;return null;
    case "commit":order.add("commit");commits++;return null;
    case "rollback":order.add("rollback");rollbacks++;return null;
    case "prepareStatement": {
     String sql=(String)a[0];Map<Integer,Object> values=new TreeMap<>();List<List<Object>> batch=new ArrayList<>();
     return proxy(PreparedStatement.class,(q,n,b)->{
      switch(n.getName()){
       case "setObject":values.put((Integer)b[0],b[1]);return null;
       case "setQueryTimeout":check(b[0].equals(sql.startsWith("SELECT DISTINCT")||sql.startsWith("SELECT family")&&!sql.contains("WHERE namespace_id=? AND view_id=?")?5:30),"publication/comparison statements use thirty seconds while history/discovery reads retain five");return null;
       case "addBatch":batch.add(new ArrayList<>(values.values()));return null;
       case "clearBatch":batch.clear();return null;
       case "executeBatch":{int[] result=new int[batch.size()];for(int i=0;i<batch.size();i++)result[i]=failBatch?Statement.EXECUTE_FAILED:write(sql,batch.get(i));return result;}
       case "executeUpdate":return write(sql,new ArrayList<>(values.values()));
       case "executeQuery": {
        if(injected!=null&&sql.startsWith(failPrefix))throw injected;
        if(sql.startsWith("SELECT family,register_name,native_id,kind,stable_key"))return rows(entries.values().stream().map(v->new Object[]{v.get(1),v.get(2),v.get(3),v.get(4),v.get(5),v.get(6),v.get(7)}).toList());
        return rows(select(sql,values));
       }
      }return zero(n.getReturnType());
     });
    }
   }return zero(m.getReturnType());
  });}
 }
 static void database()throws Exception {
  var snapshot=DictionarySnapshotFixture.sample();Fake fake=new Fake();var database=new ApocBRWorldDictionaryDatabase(fake::open);
  UUID ns=UUID.randomUUID();var origin=new ApocBRWorldDictionaryDatabase.Origin(ns,"ns",1,"save");
  var first=database.publish(origin,snapshot);check(first.revision()==1&&fake.commits==1&&fake.notifications==1,"one transactional publication and notification");
  check(fake.viewValues.get(3).equals(1)&&new org.json.JSONObject(fake.event).getInt("server_id")==1&&!fake.event.contains("server_key"),"numeric owner is used in view and notification journal");
  check(fake.order.indexOf("notify")<fake.order.indexOf("commit"),"notification shares publication transaction");
  int before=fake.entries.size(),writes=fake.order.size();
  var second=new ApocBRWorldDictionaryDatabase(fake::open).publish(origin,snapshot);
  check(second.equals(first)&&fake.entries.size()==before&&fake.notifications==1,"unchanged restart reuses exact view without identity rewrites or notification");
  check(fake.order.subList(writes,fake.order.size()).stream().noneMatch(s->s.contains("INSERT INTO player_rel.world_dictionary_revision")||s.contains("entry_state")||s.contains("runtime_view")||s.contains("registry_state")||s.contains("journal")||s.contains("mod_override")),"reuse writes no revision, view, state, override or journal rows");
  var reversedEntries=new ArrayList<>(snapshot.entries());Collections.reverse(reversedEntries);var reversedRegistries=new ArrayList<>(snapshot.registries());Collections.reverse(reversedRegistries);
  check(database.publish(origin,new ApocBRWorldDictionarySnapshot(reversedRegistries,reversedEntries)).equals(first),"entry and registry order do not affect reuse");
  fake.registryStates.getFirst().set(4,0);var repaired=database.publish(origin,snapshot);
  check(repaired.revision()==first.revision()+1,"view counters are checked independently of global registry counters");
  fake.states.removeFirst();var complete=database.publish(origin,snapshot);
  check(complete.revision()==repaired.revision()+1,"missing state prevents reuse");
  // Every native mutable field must prevent reuse; restore the original between cases.
  for(var e:snapshot.entries())for(int field=7;field<ApocBRWorldDictionarySnapshot.Entry.class.getRecordComponents().length;field++) {
   var components=ApocBRWorldDictionarySnapshot.Entry.class.getRecordComponents();Object[] values=new Object[components.length];Class<?>[] types=new Class<?>[components.length];
   for(int i=0;i<values.length;i++){values[i]=components[i].getAccessor().invoke(e);types[i]=components[i].getType();}
   Object old=values[field];if(old==null||e.family().equals("object")||e.family().equals("sprite"))continue;
   values[field]=old instanceof Boolean b?!b:old instanceof Long n?n+1:old instanceof String s?s+"-changed":new ArrayList<>(List.of("changed","override"));
   var changed=ApocBRWorldDictionarySnapshot.Entry.class.getDeclaredConstructor(types).newInstance(values);
   var list=new ArrayList<>(snapshot.entries());list.set(list.indexOf(e),changed);var different=new ApocBRWorldDictionarySnapshot(snapshot.registries(),list);
   var previous=database.publish(origin,snapshot);var next=database.publish(origin,different);
   check(next.revision()==previous.revision()+1&&!next.view().equals(previous.view()),"changed "+e.family()+"/"+components[field].getName()+" publishes new view");
   check(database.publish(origin,different).equals(next),"changed snapshot itself is reusable");
  }
  database.publish(origin,snapshot);
  var reordered=new ArrayList<>(snapshot.entries());var item=reordered.getFirst();var overrideOrder=new ArrayList<>(item.overrides());Collections.reverse(overrideOrder);
  reordered.set(0,new ApocBRWorldDictionarySnapshot.Entry(item.family(),item.register(),item.id(),item.kind(),item.key(),item.module(),item.name(),item.loaded(),item.mod(),item.vanilla(),item.modded(),item.obsolete(),item.removed(),item.overridesPresent(),item.version(),overrideOrder));
  var prior=database.publish(origin,snapshot);check(database.publish(origin,new ApocBRWorldDictionarySnapshot(snapshot.registries(),reordered)).revision()==prior.revision()+1,"override order is significant");
  var counters=new ArrayList<>(snapshot.registries());var r=counters.getFirst();counters.set(0,new ApocBRWorldDictionarySnapshot.Registry(r.family(),r.name(),r.nextId()+1,r.maxLength()));
  prior=database.publish(origin,snapshot);var increased=new ApocBRWorldDictionarySnapshot(counters,snapshot.entries());
  check(database.publish(origin,increased).revision()==prior.revision()+1,"counter change alone publishes");
  prior=database.publish(origin,increased);var added=new ArrayList<>(increased.entries());added.add(new ApocBRWorldDictionarySnapshot.Entry("info","",1,"item","Base.New","Base","New",true,"pz-vanilla",true,false,false,false,false,null,List.of()));
  check(database.publish(origin,new ApocBRWorldDictionarySnapshot(counters,added)).revision()==prior.revision()+1,"new identity publishes");
  check(fake.opened==fake.closed,"publication leases returned");
  Fake readFailure=new Fake();var readDb=new ApocBRWorldDictionaryDatabase(readFailure::open);readDb.publish(origin,snapshot);readFailure.failPrefix="SELECT family,register_name,native_id,kind,is_loaded";readFailure.injected=new SQLException("PRIVATE_READ_ERROR","08006");
  try{readDb.publish(origin,snapshot);throw new AssertionError("comparison failure accepted");}catch(SQLException error){check(error.getMessage().contains("stage=COMPARE_READY_VIEW")&&!error.toString().contains("PRIVATE_"),"comparison failure is reported and sanitized");}
  check(readFailure.rollbacks==1&&readFailure.commits==1&&readFailure.notifications==1&&readFailure.opened==readFailure.closed,"comparison failure rolls back without publishing");
  Fake failing=new Fake();failing.failNotify=true;
  try{new ApocBRWorldDictionaryDatabase(failing::open).publish(origin,snapshot);throw new AssertionError("notify failure accepted");}catch(SQLException expected){check(expected.getCause()==null&&!expected.toString().contains("secret"),"driver failure sanitized");}
  check(failing.commits==0&&failing.rollbacks==1&&failing.opened==failing.closed,"failure rolls back and returns lease");
  Fake badBatch=new Fake();badBatch.failBatch=true;try{new ApocBRWorldDictionaryDatabase(badBatch::open).publish(origin,snapshot);throw new AssertionError("failed batch accepted");}catch(SQLException expected){check(badBatch.rollbacks==1&&badBatch.commits==0,"failed batch rolls back");}
  Fake missingColumn=new Fake();missingColumn.failPrefix="INSERT INTO player_rel.world_dictionary_runtime_view(";
  missingColumn.injected=new org.postgresql.util.PSQLException(new org.postgresql.util.ServerErrorMessage("SERROR\0C42703\0Mcolumn server_id does not exist\0splayer_rel\0tworld_dictionary_runtime_view\0cserver_id\0DPRIVATE_ROW_DATA\0WPRIVATE_FUNCTION_CONTEXT\0\0"));
  try{new ApocBRWorldDictionaryDatabase(missingColumn::open).publish(origin,snapshot);throw new AssertionError("missing V7 accepted");}catch(SQLException error){
   check(error.getMessage().contains("stage=CREATE_RUNTIME_VIEW")&&error.getMessage().contains("SQLState=42703")&&error.getMessage().contains("server_id does not exist")&&error.getMessage().contains("serverId=1"),"publication reports failing stage, SQLState, safe PG message and server ID");
   check(error.getMessage().contains("table=world_dictionary_runtime_view")&&error.getMessage().contains("column=server_id")&&error.getMessage().contains("V6/V7")&&!error.getMessage().contains("networkplayers"),"dictionary-specific diagnostics identify table/column/migration instead of player hints");
   check(error.getCause()==null&&!error.toString().contains("PRIVATE_"),"diagnostics exclude PostgreSQL DETAIL/WHERE and raw cause");
  }
  check(missingColumn.commits==0&&missingColumn.rollbacks==1&&missingColumn.opened==missingColumn.closed,"publication error still rolls back and returns lease");
  Fake failedBatch=new Fake();failedBatch.failPrefix="INSERT INTO player_rel.world_dictionary_entry(";
  var batch=new BatchUpdateException("Batch entry 0 INSERT values ('PRIVATE_BOUND_VALUE')", "23514",new int[0]);
  batch.setNextException(new org.postgresql.util.PSQLException(new org.postgresql.util.ServerErrorMessage("SERROR\0C23514\0Mnew row violates check constraint\0splayer_rel\0tworld_dictionary_entry\0nworld_dictionary_entry_check\0DPRIVATE_BOUND_ROW\0\0")));failedBatch.injected=batch;
  try{new ApocBRWorldDictionaryDatabase(failedBatch::open).publish(origin,snapshot);throw new AssertionError("batch accepted");}catch(SQLException error){
   check(error.getMessage().contains("stage=WRITE_IDENTITIES")&&error.getMessage().contains("constraint=world_dictionary_entry_check")&&error.getMessage().contains("next={"),"batch diagnostic finds original PostgreSQL constraint error");
   check(!error.toString().contains("PRIVATE_")&&!error.toString().contains("INSERT values")&&failedBatch.rollbacks==1,"batch SQL and literal row values never enter logs");
  }
  Fake timeout=new Fake();timeout.failPrefix="UPDATE player_rel.world_dictionary_runtime_view SET status='ready'";
  timeout.injected=new org.postgresql.util.PSQLException("private driver statement",org.postgresql.util.PSQLState.CONNECTION_FAILURE,new java.net.SocketTimeoutException("PRIVATE_SOCKET_VALUES"));
  try{new ApocBRWorldDictionaryDatabase(timeout::open).publish(origin,snapshot);throw new AssertionError("timeout accepted");}catch(SQLException error){
   check(error.getMessage().contains("stage=PUBLISH_READY_VIEW")&&error.getMessage().contains("transport=SOCKET_READ_TIMEOUT")&&error.getMessage().contains("stageMs=")&&error.getMessage().contains("publicationTimeoutSeconds=30"),"ready-view transport timeout exposes cause and duration/budget");
   check(timeout.rollbacks==1&&timeout.commits==0&&timeout.notifications==0&&!error.toString().contains("PRIVATE_"),"ready-view timeout rolls back and never reaches notification");
  }
  Fake history=new Fake();history.entries.put("info::0",Arrays.asList(ns,"info","",0,"item","Base.Wrong","Base","Wrong",0L));
  try{new ApocBRWorldDictionaryDatabase(history::open).publish(origin,snapshot);throw new AssertionError("history mismatch accepted");}catch(SQLException error){check(error.getMessage().contains("stage=VALIDATE_HISTORY")&&error.getMessage().contains("validation=Vanilla dictionary ID remapped"),"controlled history validation reason retained");}
  try{new ApocBRWorldDictionaryDatabase(()->{throw new SQLException("jdbc:postgresql://host/db?password=PRIVATE_PASSWORD","08006");}).publish(origin,snapshot);throw new AssertionError("connection failure accepted");}catch(SQLException error){check(error.getMessage().contains("stage=ACQUIRE_CONNECTION")&&error.getMessage().contains("SQLState=08006")&&!error.toString().contains("PRIVATE_"),"connection acquisition error is classified without credentials");}
  try{new ApocBRWorldDictionaryDatabase(()->{throw new SQLException();}).publish(origin,snapshot);throw new AssertionError("empty failure accepted");}catch(SQLException error){check(error.getMessage().contains("stage=ACQUIRE_CONNECTION")&&error.getSQLState().length()==5,"null message and SQLState remain diagnosable");}
  var registry=new ApocBRWorldDictionarySnapshot.Registry("info","",3,null);
  try{ApocBRWorldDictionaryDatabase.validateHistory(snapshot,Map.of(registry.key(),registry),Map.of());throw new AssertionError("counter rewind accepted");}catch(SQLException expected){checks++;}
  try{ApocBRWorldDictionaryDatabase.validateHistory(snapshot,Map.of(),Map.of("info::0",Arrays.asList("item","Base.Wrong","Base","Wrong")));throw new AssertionError("ID remap accepted");}catch(SQLException expected){checks++;}
  fake.foreign=List.<Object[]>of(new Object[]{UUID.randomUUID(),UUID.randomUUID(),5L,2,"save"});
  check(database.foreignHeads(1).getFirst().serverId()==2,"foreign discovery returns detached metadata");
 }
 public static void main(String[] args)throws Exception{database();System.out.println("Dictionary JDBC checks="+checks);}
}
'''
LISTENER_CONNECTOR=r'''
package zombie.savefile;
import java.sql.*;
public class ApocBRPostgresConnector {
 public static ApocBRWorldDictionaryDatabase.Connections queries,listeners;public static Runnable shutdown;
 public static Connection borrowDictionaryConnection()throws SQLException{return queries.open();}
 public static Connection openDictionaryListenerConnection()throws SQLException{return listeners.open();}
 public static void setDictionaryShutdown(Runnable action){shutdown=action;}
}
'''
ROLLOVER=r'''
package zombie.savefile;
import java.nio.file.*;import java.sql.*;import java.util.*;
import zombie.world.*;
public class DictionaryRolloverTest {
 static class Multi extends DictionaryDatabaseTest.Fake {
  Map<UUID,DictionaryDatabaseTest.Fake> histories=new HashMap<>();UUID active;
  @Override int write(String sql,List<Object> a)throws Exception {
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_namespace")) {
    active=(UUID)a.getFirst();var state=histories.computeIfAbsent(active,k->new DictionaryDatabaseTest.Fake());
    registries=state.registries;entries=state.entries;revision=state.revision;viewValues=state.viewValues;states=state.states;overrides=state.overrides;registryStates=state.registryStates;
   }
   int result=super.write(sql,a);
   if(sql.startsWith("UPDATE player_rel.world_dictionary_namespace"))histories.get(active).revision=revision;
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_runtime_view("))histories.get(active).viewValues=viewValues;
   return result;
  }
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static ApocBRWorldDictionaryDatabase.Published publish(ApocBRWorldDictionaryDatabase db,ApocBRWorldDictionaryDatabase.Origin origin,ApocBRWorldDictionarySnapshot snapshot,Path marker)throws Exception {
  var method=ApocBRWorldDictionaryMirror.class.getDeclaredMethod("publish",ApocBRWorldDictionaryDatabase.class,ApocBRWorldDictionaryDatabase.Origin.class,ApocBRWorldDictionarySnapshot.class,Path.class);method.setAccessible(true);
  try{return (ApocBRWorldDictionaryDatabase.Published)method.invoke(null,db,origin,snapshot,marker);}
  catch(java.lang.reflect.InvocationTargetException error){throw (Exception)error.getCause();}
 }
 public static void main(String[] args)throws Exception {
  var snapshot=DictionarySnapshotFixture.sample();Multi fake=new Multi();var db=new ApocBRWorldDictionaryDatabase(fake::open);
  UUID seed=UUID.randomUUID();var origin=new ApocBRWorldDictionaryDatabase.Origin(seed,"vanilla:"+seed,3,"same-save");
  Path marker=Path.of(args[0],"namespace.txt");
  var first=publish(db,origin,snapshot,marker);
  check(first.namespace().equals(seed)&&!Files.exists(marker),"existing compatible namespace needs no marker");
  fake.histories.get(seed).registries.put("info:",Arrays.asList(seed,"info","",99,null,0L));
  var reset=publish(db,origin,snapshot,marker);
  check(!reset.namespace().equals(seed)&&reset.revision()==1&&fake.rollbacks==1,"counter rewind starts fresh history after rollback");
  check(fake.histories.get(seed).registries.get("info:").get(3).equals(99),"old history is preserved");
  var restart=publish(db,origin,snapshot,marker);
  check(restart.equals(reset),"unchanged restart reuses persisted replacement view");
  fake.histories.get(reset.namespace()).entries.put("info::0",Arrays.asList(reset.namespace(),"info","",0,"item","Base.Wrong","Base","Wrong",0L));
  var remapped=publish(db,origin,snapshot,marker);
  check(!remapped.namespace().equals(reset.namespace()),"ID remap rolls over too");
  fake.failNotify=true;int histories=fake.histories.size();String before=Files.readString(marker);
  // Force a real publication; an unchanged startup correctly sends no notification.
  fake.states.getFirst().set(6,false);
  try{publish(db,origin,snapshot,marker);throw new AssertionError("DB failure accepted");}
  catch(SQLException expected){check(!(expected instanceof ApocBRWorldDictionaryDatabase.HistoryChangedException),"transport failure is not a history conflict");}
  check(histories==fake.histories.size()&&before.equals(Files.readString(marker)),"SQL failures never rotate namespace");fake.failNotify=false;
  fake.histories.get(remapped.namespace()).registries.put("info:",Arrays.asList(remapped.namespace(),"info","",99,null,0L));
  fake.failNotify=true;
  try{publish(db,origin,snapshot,marker);throw new AssertionError("replacement publication failure accepted");}
  catch(SQLException expected){check(expected.getMessage().contains("stage=NOTIFY"),"replacement SQL failure propagates");}
  UUID pending=UUID.fromString(Files.readAllLines(marker).get(1));
  check(!pending.equals(remapped.namespace()),"replacement persisted before failed DB publication");fake.failNotify=false;
  check(publish(db,origin,snapshot,marker).namespace().equals(pending),"restart after failed replacement reuses marker");
  UUID explicit=UUID.randomUUID();var configured=new ApocBRWorldDictionaryDatabase.Origin(explicit,"vanilla:"+explicit,3,"same-save");
  check(publish(db,configured,snapshot,marker).namespace().equals(explicit),"explicit config change ignores old marker");
  check(fake.opened==fake.closed,"all leases returned");
  System.out.println("Dictionary rollover: rewind, remap, restart, old history, SQL failure and configuration checks passed.");
 }
}
'''
LISTENER=r'''
package zombie.world;
import java.sql.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import org.postgresql.*;import zombie.savefile.*;
public class DictionaryListenerTest {
 static AtomicInteger sessions=new AtomicInteger(),queries=new AtomicInteger(),polls=new AtomicInteger(),closed=new AtomicInteger();
 static BlockingQueue<String> events=new LinkedBlockingQueue<>();static volatile UUID head=UUID.randomUUID();static UUID ns=UUID.randomUUID();static volatile boolean listening;
 static int checks;
 static void check(boolean value,String text){if(!value)throw new AssertionError(text);checks++;}
 interface Condition{boolean get();}
 static void await(Condition condition)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!condition.get()){if(System.nanoTime()>end)throw new AssertionError("listener deadline");Thread.sleep(10);}}
 static Object zero(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;return null;}
 static Object proxy(Class<?> type,InvocationHandler handler){return Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);}
 static Connection listener(){sessions.incrementAndGet();listening=false;AtomicBoolean stopped=new AtomicBoolean();
  PGConnection pg=(PGConnection)proxy(PGConnection.class,(p,m,a)->{
   if(m.getName().equals("getNotifications")){
    polls.incrementAndGet();String event=events.poll(100,TimeUnit.MILLISECONDS);
    if(stopped.get())throw new SQLException("closed","08003");if("disconnect".equals(event))throw new SQLException("secret transport","08006");
    if(event!=null)return new PGNotification[]{new PGNotification(){public String getName(){return ApocBRWorldDictionaryDatabase.CHANNEL;}public int getPID(){return 7;}public String getParameter(){return "changed";}}};
    return null;
   }return zero(m.getReturnType());
  });
  return (Connection)proxy(Connection.class,(p,m,a)->switch(m.getName()){
   case "createStatement"->proxy(Statement.class,(s,n,b)->{if(n.getName().equals("execute")){check(((String)b[0]).startsWith("LISTEN "),"native LISTEN command");listening=true;return false;}return zero(n.getReturnType());});
   case "unwrap"->pg;
   case "close"->{if(stopped.compareAndSet(false,true))closed.incrementAndGet();events.offer("closed");yield null;}
   default->zero(m.getReturnType());
  });
 }
 static Connection query(){check(listening,"subscribe before reconciliation");return (Connection)proxy(Connection.class,(p,m,a)->{
  if(m.getName().equals("prepareStatement"))return proxy(PreparedStatement.class,(s,n,b)->{
   if(n.getName().equals("setObject")){check(b[0].equals(1)&&b[1].equals(1),"exclude own server");return null;}
   if(n.getName().equals("executeQuery")){queries.incrementAndGet();UUID snapshot=head;int[] row={0};return proxy(ResultSet.class,(r,f,v)->switch(f.getName()){
    case "next"->row[0]++==0;case "getObject"->(int)v[0]==1?ns:snapshot;case "getLong"->1L;case "getInt"->2;case "getString"->"save";default->zero(f.getReturnType());});}return zero(n.getReturnType());
  });return zero(m.getReturnType());
 });}
 public static void main(String[] args)throws Exception{
  ApocBRPostgresConnector.queries=DictionaryListenerTest::query;ApocBRPostgresConnector.listeners=DictionaryListenerTest::listener;
  ApocBRWorldDictionaryDecode.install(new ApocBRPayloadProvenance(2,ns,head),new ApocBRWorldDictionarySnapshot(List.of(),List.of()));
  var start=ApocBRWorldDictionaryMirror.class.getDeclaredMethod("start",int.class);start.setAccessible(true);start.invoke(null,1);
  await(()->!ApocBRWorldDictionaryMirror.foreignViews().isEmpty());UUID initial=head;
  check(ApocBRWorldDictionaryMirror.foreignViews().getFirst().view().equals(initial),"initial committed head discovered");
  int before=queries.get();int previousPoll=polls.get();await(()->polls.get()>previousPoll+2);check(queries.get()==before,"idle notification waits do not query dictionary tables");
  head=UUID.randomUUID();ApocBRWorldDictionaryDecode.install(new ApocBRPayloadProvenance(2,ns,head),new ApocBRWorldDictionarySnapshot(List.of(),List.of()));UUID updated=head;events.offer("notify");await(()->ApocBRWorldDictionaryMirror.foreignViews().getFirst().view().equals(updated));checks++;
  head=UUID.randomUUID();ApocBRWorldDictionaryDecode.install(new ApocBRPayloadProvenance(2,ns,head),new ApocBRWorldDictionarySnapshot(List.of(),List.of()));UUID missed=head;events.offer("disconnect");await(()->sessions.get()>=2&&ApocBRWorldDictionaryMirror.foreignViews().getFirst().view().equals(missed));check(closed.get()>=1,"reconnect closes previous session and recovers missed update");
  try{ApocBRWorldDictionaryMirror.foreignViews().clear();throw new AssertionError("mutable discovery");}catch(UnsupportedOperationException expected){checks++;}
  ApocBRPostgresConnector.shutdown.run();await(()->closed.get()==sessions.get());check(ApocBRWorldDictionaryMirror.localView()==null,"listener does not install a foreign dictionary as local");
  System.out.println("Dictionary listener checks="+checks);
 }
}
'''

with tempfile.TemporaryDirectory(prefix='apocbr_dictionary_') as folder:
    target=Path(folder)
    fixtures=[]
    for name,body in [('DictionarySnapshotFixture.java',SNAPSHOT),('DictionaryDatabaseTest.java',DATABASE),('DictionaryRolloverTest.java',ROLLOVER)]:
        path=target/name;path.write_text(body,encoding='utf-8');fixtures.append(path)
    sources=[SRC/'world/ApocBRWorldDictionaryDecode.java',SRC/'savefile/ApocBRPayloadProvenance.java',SRC/'world/ApocBRWorldDictionarySnapshot.java',SRC/'savefile/ApocBRWorldDictionaryDatabase.java',SRC/'savefile/ApocBRPostgresConnector.java']
    sources += inactive_persistence_stubs(target, mutations=True)
    sources += [SRC/'world/ApocBRWorldDictionaryMirror.java']
    cp=os.pathsep.join(map(str,[GAME,LIB/'postgresql-42.7.14.jar',LIB/'HikariCP-7.1.0.jar']))
    subprocess.run([str(JDK/'javac.exe'),'--release','25','-cp',cp,'-d',folder,*map(str,sources+fixtures)],check=True)
    subprocess.run([str(JDK/'java.exe'),'-cp',folder+os.pathsep+cp,'zombie.savefile.DictionaryDatabaseTest'],check=True)
    subprocess.run([str(JDK/'java.exe'),'-cp',folder+os.pathsep+cp,'zombie.savefile.DictionaryRolloverTest',folder],check=True)
    listener_target=target/'listener';listener_target.mkdir()
    stub=listener_target/'ApocBRPostgresConnector.java';stub.write_text(LISTENER_CONNECTOR,encoding='utf-8')
    listener_harness=listener_target/'DictionaryListenerTest.java';listener_harness.write_text(LISTENER,encoding='utf-8')
    listener_cp=folder+os.pathsep+cp
    subprocess.run([str(JDK/'javac.exe'),'--release','25','-cp',listener_cp,'-d',str(listener_target),str(stub),str(SRC/'world/ApocBRWorldDictionaryMirror.java'),str(listener_harness)],check=True)
    subprocess.run([str(JDK/'java.exe'),'-cp',str(listener_target)+os.pathsep+listener_cp,'zombie.world.DictionaryListenerTest'],check=True)

hook=(SRC/'world/WorldDictionary.java').read_text(encoding='utf-8')
assert hook.index('data.save();')<hook.index('ApocBRWorldDictionaryMirror.afterRebuild(data);')<hook.index('data.deleteBackupCurrentDataSet();')
print('Vanilla save / mirror publication / backup cleanup ordering checked.')
