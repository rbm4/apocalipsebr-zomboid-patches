"""Actual dictionary getters, decode scopes and exact-view JDBC reads; no live database."""
from pathlib import Path
import os, subprocess, tempfile

ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'42.21.0/src/zombie'
JDK=ROOT/'jdk/bin'
GAME=Path(os.environ.get('APOCBR_TEST_GAME_JAR',r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
HARNESS=r'''
package zombie.world;
import java.util.*;import java.nio.*;import java.sql.*;import java.lang.reflect.*;import java.util.concurrent.*;
import zombie.savefile.*;
public class DictionaryDecodeTest {
 static int checks;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
 @SuppressWarnings("unchecked") static <T> Map<Object,T> map(Object register,String name)throws Exception{Class<?> type=register.getClass();Field field=null;while(type!=null&&field==null){try{field=type.getDeclaredField(name);}catch(NoSuchFieldException ignored){type=type.getSuperclass();}}field.setAccessible(true);return (Map<Object,T>)field.get(register);}
 static ItemInfo item(short id,String name){ItemInfo i=new ItemInfo();i.registryId=id;i.fullType=name;i.moduleName="Base";i.name=name.substring(5);i.isLoaded=true;return i;}
 static ApocBRWorldDictionarySnapshot.Entry entry(String family,String register,int id,String kind,String key,Long version){return new ApocBRWorldDictionarySnapshot.Entry(family,register,id,kind,key,Set.of("item","entity").contains(kind)?"Base":null,Set.of("item","entity").contains(kind)?key.substring(5):null,true,"pz-vanilla",true,false,false,false,false,version,List.of());}
 static ApocBRWorldDictionarySnapshot snapshot(String item){return new ApocBRWorldDictionarySnapshot(List.of(
  new ApocBRWorldDictionarySnapshot.Registry("info","",2,null),new ApocBRWorldDictionarySnapshot.Registry("object","",1,null),new ApocBRWorldDictionarySnapshot.Registry("sprite","",0,null),new ApocBRWorldDictionarySnapshot.Registry("string","Generic",1,64),new ApocBRWorldDictionarySnapshot.Registry("script","Test",1,null)),List.of(
  entry("info","",0,"item",item,null),entry("info","",1,"entity","Base.Entity",null),entry("object","",0,"object",item+"Object",null),entry("sprite","",0,"sprite",item+"Sprite",null),entry("string","Generic",0,"string",item+"String",null),entry("script","Test",0,"script","Base.Test",7L)));}
 static ApocBRPayloadProvenance origin(int server){return new ApocBRPayloadProvenance(server,UUID.randomUUID(),UUID.randomUUID());}
 static ByteBuffer tagged(ApocBRPayloadProvenance origin){ByteBuffer b=ByteBuffer.allocate(32);ApocBRPayloadProvenance.remember(b,new ApocBRPayloadProvenance.Metadata(origin,249));return b;}
 static ByteBuffer encoded(ApocBRPayloadProvenance origin){ByteBuffer b=tagged(origin);b.put((byte)1).putShort((short)0).flip();return b;}
 static class Script extends zombie.scripting.objects.BaseScriptObject {Script(){super(null);}}
 static class Register extends ScriptsDictionary.ScriptRegister<Script>{Register(){super("Test");}}
 static void routing()throws Exception {
  DictionaryData data=new DictionaryData();data.reset();
  for(ItemInfo item:List.of(item((short)0,"Base.Native"),item((short)9,"Base.Axe"),item((short)10,"Base.Hammer"))){data.itemIdToInfoMap.put(item.registryId,item);data.itemTypeToInfoMap.put(item.fullType,item);}
  EntityInfo entity=new EntityInfo();entity.fullType="Base.Entity";entity.registryId=11;entity.isLoaded=true;data.entityIdToInfoMap.put((short)11,entity);data.entityTypeToInfoMap.put(entity.fullType,entity);
  data.spriteIdToNameMap.put(0,"nativeSprite");data.objectIdToNameMap.put((byte)0,"nativeObject");
  var field=WorldDictionary.class.getDeclaredField("data");field.setAccessible(true);field.set(null,data);
  DictionaryStringInfo string=new DictionaryStringInfo();string.string="nativeString";string.registryId=0;string.isLoaded=true;map(StringDictionary.Generic,"idToStringMap").put((short)0,string);
  var b=origin(2);var c=origin(3);ApocBRWorldDictionaryDecode.install(b,snapshot("Base.Axe"));ApocBRWorldDictionaryDecode.install(c,snapshot("Base.Hammer"));
  Register register=new Register();var script=new DictionaryScriptInfo<Script>();script.name="Base.Test";script.script=new Script();script.registryId=9;script.version=7;script.isLoaded=true;map(register,"namedMap").put(script.name,script);map(register,"idMap").put((short)9,script);
  check(WorldDictionary.getItemTypeFromID((short)0).equals("Base.Native"),"untagged native lookup");
  try(var scope=ApocBRWorldDictionaryDecode.enter(tagged(b))) {
   check(WorldDictionary.getItemInfoFromID((short)0).fullType.equals("Base.Axe"),"source item ID resolves through B while target native ID is 9");
   check(WorldDictionary.getEntityInfoFromID((short)1).fullType.equals("Base.Entity")&&WorldDictionary.getEntityRegistryID("Base.Entity")==11,"source entity IDs resolve while native allocation stays local");
   check(WorldDictionary.getItemRegistryID("Base.Axe")==9,"encoding allocator remains native inside decode");
   check(WorldDictionary.getSpriteNameFromID(0).equals("Base.AxeSprite")&&WorldDictionary.getObjectNameFromID((byte)0).equals("Base.AxeObject"),"sprite and object source lookups");
   check(StringDictionary.Generic.get((short)0).equals("Base.AxeString"),"string source lookup");
   check(register.getIdFor("Base.Test")==9,"script encoding IDs stay native");
   check(register.get((short)0)==script.script,"script ID translates to matching local definition");
   try(var nested=ApocBRWorldDictionaryDecode.enter(tagged(c))){check(WorldDictionary.getItemTypeFromID((short)0).equals("Base.Hammer"),"nested payload selects C independently");}
   check(WorldDictionary.getItemTypeFromID((short)0).equals("Base.Axe"),"outer B context restored");
   try(var unknown=ApocBRWorldDictionaryDecode.enter(tagged(null))){check(WorldDictionary.getItemTypeFromID((short)0).equals("Base.Native"),"explicit unknown payload uses native even inside foreign decode");}
   try(var inherited=ApocBRWorldDictionaryDecode.enter(ByteBuffer.allocate(1))){check(WorldDictionary.getItemTypeFromID((short)0).equals("Base.Axe"),"nested native stream inherits enclosing source");}
   ByteBuffer child=ByteBuffer.allocate(1);ApocBRWorldDictionaryDecode.inherit(child,null);check(ApocBRPayloadProvenance.metadata(child).origin().equals(b),"retained embedded bytes inherit source for later decode");
   var executor=Executors.newSingleThreadExecutor();try{check(executor.submit(()->WorldDictionary.getItemTypeFromID((short)0)).get().equals("Base.Native"),"decode context isolated by thread");}finally{executor.shutdownNow();}
  }
  check(ApocBRWorldDictionaryDecode.current()==null&&WorldDictionary.getItemTypeFromID((short)0).equals("Base.Native"),"scope cleanup restores native");
  check(StringDictionary.Generic.loadString(encoded(c),249).equals("Base.HammerString"),"standalone string decoder selects tagged view");
  check(StringDictionary.Generic.loadString(encoded(null),249).equals("nativeString"),"unattributed standalone bytes use native register");
  check(register.loadScript(encoded(b),249)==script.script,"standalone script decoder selects tagged view");
  script.version=8;
  try{register.loadScript(encoded(b),249);throw new AssertionError("script mismatch accepted");}catch(ApocBRWorldDictionaryDecode.Unavailable expected){checks++;}
  check(ApocBRWorldDictionaryDecode.current()==null,"script failure restores context");
  try{try(var scope=ApocBRWorldDictionaryDecode.enter(tagged(b))){try{WorldDictionary.getItemTypeFromID((short)123);}catch(ApocBRWorldDictionaryDecode.Unavailable swallowed){}}throw new AssertionError("scope failed to retain swallowed failure");}catch(ApocBRWorldDictionaryDecode.Unavailable expected){checks++;}
  check(ApocBRWorldDictionaryDecode.current()==null,"swallowed native failure cannot leak scope");
 }
 static Object zero(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
 static Object proxy(Class<?> type,InvocationHandler handler){return Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);}
 static class Database {
  ApocBRPayloadProvenance origin;ApocBRWorldDictionarySnapshot snapshot;int opened,closed,commits,rollbacks,queries;boolean fail;
  CountDownLatch started,release;
  Database(ApocBRPayloadProvenance origin){this.origin=origin;snapshot=snapshot("Base.Axe");}
  ResultSet rows(List<Object[]> values){int[] index={-1};return (ResultSet)proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
   case "next"->++index[0]<values.size();case "getString","getObject"->values.get(index[0])[(int)a[0]-1];case "getInt"->((Number)values.get(index[0])[(int)a[0]-1]).intValue();case "getLong"->((Number)values.get(index[0])[(int)a[0]-1]).longValue();default->zero(m.getReturnType());});}
  Connection open()throws SQLException {opened++;if(started!=null){started.countDown();try{release.await();}catch(InterruptedException error){throw new SQLException();}}
   return (Connection)proxy(Connection.class,(p,m,a)->switch(m.getName()) {
    case "close"->{closed++;yield null;}case "commit"->{commits++;yield null;}case "rollback"->{rollbacks++;yield null;}
    case "prepareStatement"->{String sql=(String)a[0];Map<Integer,Object> args=new HashMap<>();yield proxy(PreparedStatement.class,(s,n,b)->{
     if(n.getName().equals("setObject")){args.put((Integer)b[0],b[1]);return null;}
     if(n.getName().equals("setQueryTimeout")){check(b[0].equals(5),"bounded dictionary query");return null;}
     if(n.getName().equals("executeQuery")) {
      queries++;if(fail)throw new SQLException("private JDBC detail","08006");
      if(sql.startsWith("SELECT v.identity_revision")){check(sql.contains("v.status='ready'")&&sql.contains("n.world_version=249"),"exact ready compatible source view required");return rows(args.get(1).equals(origin.namespaceId())&&args.get(2).equals(origin.viewId())&&args.get(3).equals(origin.serverId())?List.<Object[]>of(new Object[]{1L}):List.of());}
      if(sql.startsWith("SELECT s.family"))return rows(snapshot.registries().stream().map(r->new Object[]{r.family(),r.name(),r.nextId(),r.maxLength()}).toList());
      if(sql.contains("mod_override"))return rows(List.of());
      if(sql.startsWith("SELECT e.family")){check(args.get(1).equals(origin.viewId())&&args.get(2).equals(origin.namespaceId())&&args.get(3).equals(1L)&&sql.contains("e.created_revision<=?"),"historical identity catalog filtered by view revision");return rows(snapshot.entries().stream().map(e->new Object[]{e.family(),e.register(),e.id(),e.kind(),e.key(),e.module(),e.name(),e.loaded(),e.mod(),e.vanilla(),e.modded(),e.obsolete(),e.removed(),e.overridesPresent(),e.version()}).toList());}
      throw new AssertionError(sql);
     }return zero(n.getReturnType());});}
    default->zero(m.getReturnType());
   });
  }
 }
 static void database()throws Exception {
  var origin=origin(2);Database db=new Database(origin);var repository=new ApocBRWorldDictionaryDatabase(db::open);
  check(repository.load(origin).equals(db.snapshot)&&db.commits==1&&db.closed==1,"SQL typed view round trip with lease cleanup");
  try{repository.load(new ApocBRPayloadProvenance(3,origin.namespaceId(),origin.viewId()));throw new AssertionError("wrong server accepted");}catch(SQLException expected){checks++;}
  check(db.rollbacks==1&&db.closed==2,"wrong server/view tuple rejected and rolled back");
  db.fail=true;try{repository.load(origin);throw new AssertionError("failure accepted");}catch(SQLException error){check(!error.toString().contains("private")&&error.getCause()==null,"dictionary JDBC failure sanitized");}db.fail=false;
  ApocBRPostgresConnector.factory=db::open;
  var pendingOrigin=origin(2);db.origin=pendingOrigin;db.started=new CountDownLatch(1);db.release=new CountDownLatch(1);
  var executor=Executors.newSingleThreadExecutor();var future=ApocBRWorldDictionaryDecode.prepareAsync(List.of(pendingOrigin,pendingOrigin),executor);
  try{
   db.started.await();int queries=db.queries;
   try{ApocBRWorldDictionaryDecode.enter(tagged(pendingOrigin));throw new AssertionError("unprepared source fell back to native");}catch(ApocBRWorldDictionaryDecode.Unavailable expected){checks++;}
   check(db.queries==queries&&!future.isDone(),"decode does not wait for or query an in-flight dictionary");
   db.release.countDown();future.get();int opened=db.opened;ApocBRWorldDictionaryDecode.prepare(pendingOrigin);
   check(db.opened==opened,"duplicate exact-view preparation uses cache");
   try(var scope=ApocBRWorldDictionaryDecode.enter(tagged(pendingOrigin))){check(WorldDictionary.getItemTypeFromID((short)0).equals("Base.Axe"),"prepared historical view used by actual getter");}
   check(db.opened==opened,"warm decode makes zero SQL calls");
  }finally{db.release.countDown();executor.shutdownNow();}
  var publishedOrigin=origin(2);db.origin=publishedOrigin;db.started=new CountDownLatch(1);db.release=new CountDownLatch(1);
  var publishingIo=Executors.newSingleThreadExecutor();var preparing=ApocBRWorldDictionaryDecode.prepareAsync(List.of(publishedOrigin),publishingIo);
  try {
   db.started.await();ApocBRWorldDictionaryDecode.install(publishedOrigin,db.snapshot);
   try(var scope=ApocBRWorldDictionaryDecode.enter(tagged(publishedOrigin))){check(WorldDictionary.getItemTypeFromID((short)0).equals("Base.Axe"),"local publication completes a concurrently pending exact-view cache entry");}
   db.fail=true;db.release.countDown();preparing.get();int opened=db.opened;ApocBRWorldDictionaryDecode.prepare(publishedOrigin);
   check(db.opened==opened,"late JDBC failure cannot discard an already installed immutable publication");
  }finally{db.release.countDown();publishingIo.shutdownNow();}
 }
 public static void main(String[] args)throws Exception{routing();database();System.out.println("Dictionary decode checks="+checks);}
}
'''
with tempfile.TemporaryDirectory(prefix='apocbr_decode_') as folder:
    target=Path(folder)
    harness=target/'DictionaryDecodeTest.java';harness.write_text(HARNESS,encoding='utf-8')
    connector=target/'ApocBRPostgresConnector.java';connector.write_text('''package zombie.savefile;
public class ApocBRPostgresConnector { public static int dictionaryPublicationTimeoutSeconds(){return 30;}
 public static String sqlDiagnostic(Throwable error,boolean playerHints){return "exception="+error.getClass().getSimpleName();}
 public interface Factory {java.sql.Connection open()throws java.sql.SQLException;}
 public static Factory factory=()->{throw new AssertionError("Unexpected dictionary SQL");};
 public static java.sql.Connection borrowDictionaryConnection()throws java.sql.SQLException{return factory.open();}
}''',encoding='utf-8')
    mirror=target/'ApocBRWorldDictionaryMirror.java';mirror.write_text('package zombie.world; public class ApocBRWorldDictionaryMirror {static void afterRebuild(DictionaryData data){}}',encoding='utf-8')
    sources=[SRC/p for p in ['savefile/ApocBRPayloadProvenance.java','savefile/ApocBRWorldDictionaryDatabase.java','world/ApocBRWorldDictionaryDecode.java','world/ApocBRWorldDictionarySnapshot.java','world/WorldDictionary.java','world/StringDictionary.java','world/ScriptsDictionary.java']]
    subprocess.run([str(JDK/'javac.exe'),'--release','25','-cp',str(GAME),'-d',folder,*map(str,sources),str(connector),str(mirror),str(harness)],check=True)
    subprocess.run([str(JDK/'java.exe'),'-cp',folder+os.pathsep+str(GAME),'zombie.world.DictionaryDecodeTest'],check=True)

# Native dispatch hooks must encompass the virtual subclass decoder and propagate embedded origin.
for path,signature in [('inventory/InventoryItem.java','public static InventoryItem loadItem(ByteBuffer input, int worldVersion, boolean doSaveTypeCheck, InventoryItem i)'),('iso/IsoObject.java','public final void load(ByteBuffer input, int worldVersion)')]:
    body=(SRC/path).read_text(encoding='utf-8').split(signature,1)[1]
    assert body.index('ApocBRWorldDictionaryDecode.enter(input)')<body.index('.load(input, worldVersion')
for path in ['iso/objects/IsoDeadBody.java','iso/objects/IsoMannequin.java']:
    body=(SRC/path).read_text(encoding='utf-8').split('public void load(ByteBuffer input, int worldVersion, boolean isDebugSave)',1)[1]
    assert body.index('ApocBRWorldDictionaryDecode.enter(input)')<body.index('super.load(input')
assert 'ApocBRWorldDictionaryDecode.inherit(this.byteData,null)' in (SRC/'inventory/InventoryItem.java').read_text(encoding='utf-8')
print('Native item/object dispatch scope and embedded payload inheritance checked.')
