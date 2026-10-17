"""Relational login SQL/JDBC and actual native field restoration contracts (42.21.0)."""
from postgres_test_support import inactive_persistence_stubs
from pathlib import Path
import os, re, subprocess, tempfile

ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'42.21.0/src/zombie/savefile'
GAME=Path(os.environ.get('APOCBR_TEST_GAME_JAR',r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
JDK=ROOT/'jdk/bin'
# Reuse the reviewed native scalar table inventory; no test code is imported/executed.
contract=(ROOT/'tools/test_postgres_player_relational.py').read_text(encoding='utf-8')
roots=re.search(r'String\[\]\[\] roots=\{(.*?)\n  \};',contract,re.S).group(1)
HARNESS=r'''
package zombie.savefile;
import java.util.*;
import java.lang.reflect.*;
import java.sql.*;
public class ReconstructionTest {
 static int checks;
 static void check(boolean b,String text){if(!b)throw new AssertionError(text);checks++;}
 static Class<?> resolve(Class<?> type,String path)throws Exception {
  for(String part:path.split("\\.")) {
   Field field=null;
   for(Class<?> t=type;t!=null&&field==null;t=t.getSuperclass())try{field=t.getDeclaredField(part);}catch(NoSuchFieldException ignored){}
   if(field!=null){type=field.getType();continue;}
   String cap=Character.toUpperCase(part.charAt(0))+part.substring(1);Method found=null;
   for(String prefix:List.of("","get","is","Get","Is"))try{found=type.getMethod(prefix.isEmpty()?part:prefix+cap);break;}catch(NoSuchMethodException ignored){}
   if(found==null)throw new NoSuchFieldException(type.getName()+"."+part);type=found.getReturnType();
  }return type;
 }
 static sun.misc.Unsafe unsafe()throws Exception{var f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return (sun.misc.Unsafe)f.get(null);}
 static Object blank(Class<?> type)throws Exception{return unsafe().allocateInstance(type);}
 static Object sample(Class<?> type) {
  if(type==boolean.class)return true;if(type==byte.class)return (byte)1;if(type==short.class)return (short)1;if(type==int.class)return 1;
  if(type==long.class)return 1L;if(type==float.class)return 1f;if(type==double.class)return 1d;return null;
 }
 static void nativeFields()throws Exception {
  String[][] roots={ROOTS};
  List<String> failures=new ArrayList<>();
  for(var root:roots) {
   Class<?> type=Class.forName("zombie."+root[1],false,ReconstructionTest.class.getClassLoader());
   if(Modifier.isAbstract(type.getModifiers()))type=Class.forName("zombie.worldMap.symbols.WorldMapTextSymbol");
   Object target=blank(type);var skip=new HashSet<>(Arrays.asList(root[2].split(" ")));skip.add("snapshot_id");
   if(root[0].equals("character_state")) {skip.add("direction");ApocBRPlayerRelationalReconstruction.set(target,"descriptor",blank(Class.forName("zombie.characters.SurvivorDesc")));}
   if(root[0].equals("item"))skip.addAll(List.of("full_type","source_registry_id","texture_name","source_recorded_media_index"));
   for(var col:ApocBRPlayerRelationalSchema.TABLES.get(root[0]))if(!skip.contains(col.name()))try {
    String path=ApocBRPlayerRelationalCapture.path(root[0],col.name());
    if(path.contains(".")){String parent=path.substring(0,path.lastIndexOf('.'));Class<?> nested=resolve(type,parent);ApocBRPlayerRelationalReconstruction.set(target,parent,blank(nested));}
    Class<?> valueType=resolve(type,path);
    ApocBRPlayerRelationalReconstruction.set(target,path,sample(valueType));checks++;
   }catch(Throwable e){failures.add(root[0]+"."+col.name()+": "+e);}
  }
  check(failures.isEmpty(),String.join("\n",failures));
 }
 static class Fixture {
  int queries,commits,rollbacks,closed;boolean complete=true;boolean fail;
  List<Object[]> rows=List.of();
  Object proxy(Class<?> c,InvocationHandler handler){return Proxy.newProxyInstance(c.getClassLoader(),new Class[]{c},handler);}
  Object zero(Class<?> t){if(t==boolean.class)return false;if(t==int.class)return 0;if(t==long.class)return 0L;return null;}
  Connection open(){return (Connection)proxy(Connection.class,(p,m,a)->switch(m.getName()) {
   case "prepareStatement" -> {check(((String)a[0]).equals(ApocBRPlayerRelationalReader.SQL),"single pinned query");yield proxy(PreparedStatement.class,(q,n,b)->{
    if(n.getName().equals("executeQuery")){queries++;if(fail)throw new SQLException("sensitive SQL and identity","08006");int[] index={-1};return proxy(ResultSet.class,(r,f,v)->switch(f.getName()){case "next"->++index[0]<rows.size();case "getString"->rows.get(index[0])[(int)v[0]-1];default->zero(f.getReturnType());});}return zero(n.getReturnType());});}
   case "commit"->{commits++;yield null;}case "rollback"->{rollbacks++;yield null;}case "close"->{closed++;yield null;}default->zero(m.getReturnType());
  });}
 }
 static void jdbc()throws Exception {
  Fixture f=new Fixture();var reader=new ApocBRPlayerRelationalReader(f::open);
  check(reader.load("world","alice",-1,false)==null&&f.queries==0,"invalid slot performs no I/O");
  check(reader.load("world","alice",0,false)==null&&f.commits==1&&f.closed==1,"missing slot releases lease");
  f.rows=List.<Object[]>of(new Object[]{"snapshot","{\"status\":\"complete\",\"schema_version\":1,\"codec_version\":4,\"expected_object_count\":0,\"expected_item_count\":0,\"expected_container_count\":0,\"expected_edge_count\":0}"});
  var profile=reader.load("world","alice",0,false);check(profile!=null&&f.queries==2,"one executeQuery for full profile");
  try{profile.one("snapshot").put("status","bad");throw new AssertionError("mutable profile");}catch(UnsupportedOperationException expected){checks++;}
  f.rows=List.<Object[]>of(new Object[]{"snapshot","{\"status\":\"incomplete\",\"schema_version\":1,\"codec_version\":4}"});
  try{reader.load("world","alice",0,false);throw new AssertionError("incomplete accepted");}catch(SQLException expected){checks++;}
  check(f.rollbacks==1,"incomplete head rolls back");
  f.fail=true;try{reader.load("world","alice",0,false);throw new AssertionError();}catch(SQLException expected){check(!expected.toString().contains("sensitive")&&expected.getCause()==null,"driver details sanitized");}
  check(f.closed==4,"all read paths return leases");
  f.fail=false;f.rows=List.of();var executor=java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(r,"fixture-jdbc"));
  try{check(reader.loadAsync("world","alice",0,false,executor).get()==null,"future returns detached absent result");}finally{executor.shutdownNow();}
  var sourceOrigin=new ApocBRPayloadProvenance(2,UUID.randomUUID(),UUID.randomUUID());
  String metadataJson=new org.json.JSONObject().put("status","complete").put("schema_version",1).put("codec_version",4).put("expected_object_count",0).put("expected_item_count",0).put("expected_container_count",0).put("expected_edge_count",0).toString();
  String opaqueJson=new org.json.JSONObject().put("source_server_id",2).put("source_dictionary_namespace_id",sourceOrigin.namespaceId().toString()).put("source_dictionary_view_id",sourceOrigin.viewId().toString()).toString();
  f.rows=List.<Object[]>of(new Object[]{"snapshot",metadataJson},new Object[]{"value_node",opaqueJson});
  int graphLeases=f.closed;
  ApocBRPostgresConnector.dictionaryCheck=()->check(f.closed==graphLeases+1,"graph lease returned before cold dictionary acquisition, including poolSize=1");
  try{reader.load("world","alice",0,false);throw new AssertionError("unprepared source admitted");}catch(SQLException expected){checks++;}
  zombie.world.ApocBRWorldDictionaryDecode.install(sourceOrigin,new zombie.world.ApocBRWorldDictionarySnapshot(List.of(),List.of()));
  check(reader.load("world","alice",0,false)!=null,"warm attributed graph admitted without a second dictionary fetch");
  String sql=ApocBRPlayerRelationalReader.SQL;
  check(sql.contains("'persisted_character_id',s.character_id")&&sql.contains("'source_dataset_id',s.source_dataset_id"),"login retains persistent life/dataset UUIDs for immediate live tracking, separate from graph ordinals");
  for(String column:List.of("source_dictionary_namespace_id","source_dictionary_view_id"))check(sql.contains("'"+column+"',t."+column)&&!sql.contains(".id=t."+column),"external dictionary UUID is projected literally, never collapsed to an absent graph reference");
  check(!sql.contains("networkplayers")&&!sql.contains("to_jsonb(t)")&&!sql.contains("SELECT *"),"explicit relational projection, no native blob");
  for(var table:ApocBRPlayerRelationalSchema.TABLES.entrySet())if(table.getValue().stream().anyMatch(c->c.name().equals("snapshot_id"))&&!Set.of("character_head","sync_issue").contains(table.getKey())) {
   String section=sql.substring(sql.indexOf("UNION ALL SELECT '"+table.getKey()+"'"));int end=section.indexOf(" UNION ALL",1);if(end>=0)section=section.substring(0,end);
   int reference=0;
   for(var col:table.getValue())if(col.type().equals("uuid")&&!col.name().equals("snapshot_id")&&!col.name().startsWith("source_dictionary_")){String alias="r"+reference++;check(section.contains("'"+col.name()+"',"+alias+".ordinal")&&section.contains("LEFT JOIN refs "+alias+" ON "+alias+".id=t."+col.name()),"UUID stays in SQL: "+table.getKey()+"."+col.name());}
  }
 }
 static Object invoke(String name,Class<?>[] types,Object... args)throws Exception {
  var method=ApocBRPlayerRelationalReconstruction.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(null,args);
 }
 static void components()throws Exception {
  Object attributes=ApocBRPlayerRelationalReconstruction.create("zombie.entity.components.attributes.AttributeContainer");
  var attr=zombie.entity.components.attributes.Attribute.TestUses;
  ApocBRPlayerRelationalAccess.call(attributes,"put",attr,37);
  var enumAttr=zombie.entity.components.attributes.Attribute.TestCategories;
  var enums=EnumSet.of(zombie.entity.components.attributes.TestEnum.TestValueA);
  ApocBRPlayerRelationalAccess.call(attributes,"put",enumAttr,enums);
  var state=ApocBRPlayerRelationalSnapshot.componentState(attributes);
  Object restored=ApocBRPlayerRelationalReconstruction.create("zombie.entity.components.attributes.AttributeContainer");
  invoke("restoreComponentState",new Class[]{Object.class,String.class,Object.class},restored,"AttributeContainer",state);
  check(ApocBRPlayerRelationalAccess.call(restored,"get",attr).equals(37),"numeric attribute restored through native container");
  check(ApocBRPlayerRelationalAccess.call(restored,"get",enumAttr).equals(enums),"enum-set attribute restored");
  var origin=new ApocBRPayloadProvenance(2,UUID.randomUUID(),UUID.randomUUID());
  byte[] opaqueBytes={1,2,(byte)255};ApocBRPayloadProvenance.remember(opaqueBytes,new ApocBRPayloadProvenance.Metadata(origin,249));
  UUID character=UUID.randomUUID();var capture=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE);
  var write=ApocBRPlayerRelationalCapture.class.getDeclaredMethod("componentValue",UUID.class,String.class,Object.class,String.class);write.setAccessible(true);
  Map<Object,Object> payload=new LinkedHashMap<>();payload.put("text","saved");payload.put("values",Arrays.asList(12L,true,null));payload.put("bytes",opaqueBytes);payload.put(2.0,"numeric-key");
  write.invoke(capture,character,"test",payload,"complete");
  var field=ApocBRPlayerRelationalCapture.class.getDeclaredField("rows");field.setAccessible(true);
  @SuppressWarnings("unchecked") var graph=(List<ApocBRPlayerRelationalCapture.Row>)field.get(capture);
  Map<UUID,Integer> refs=new HashMap<>();for(var row:graph)for(Object value:row.values().values())if(value instanceof UUID u)refs.computeIfAbsent(u,k->refs.size()+1);
  Map<Integer,List<Map<String,Object>>> children=new HashMap<>();Map<String,Object> root=null;
  for(var row:graph)if(row.table().equals("value_node")) {
   Map<String,Object> r=new LinkedHashMap<>();row.values().forEach((k,v)->r.put(k,v instanceof UUID u?(k.startsWith("source_dictionary_")?u.toString():refs.get(u)):v instanceof byte[] b?HexFormat.of().formatHex(b):v));
   if(r.get("parent_id")==null)root=r;else children.computeIfAbsent(((Number)r.get("parent_id")).intValue(),k->new ArrayList<>()).add(r);
  }
  var decoded=(Map<?,?>)invoke("value",new Class[]{Map.class,Map.class,Set.class,int.class},root,children,new HashSet<Integer>(),0);
  check(decoded.get("text").equals("saved")&&decoded.get(2.0).equals("numeric-key"),"structured scalar keys survive capture/reconstruction");
  check(decoded.get("values").equals(Arrays.asList(12L,true,null)),"structured arrays and null values survive");
  check(Arrays.equals((byte[])decoded.get("bytes"),new byte[]{1,2,(byte)255}),"opaque component payload survives hex transport");
  var restoredMetadata=ApocBRPayloadProvenance.metadata(decoded.get("bytes"));
  check(restoredMetadata.origin().equals(origin)&&restoredMetadata.worldVersion()==249,"foreign opaque origin survives SQL hex transport and reconstruction");
  Object existing=new Object();
  Map<String,Object> reference=Map.of("id",9001,"value_kind","reference","reference_object_id",9002);
  check(invoke("value",new Class[]{Map.class,Map.class,Set.class,int.class,Map.class},reference,Map.of(),new HashSet<Integer>(),0,Map.of(9002,existing))==existing,"structured reference restores the already registered object");
  check(sqlReferenceColumn(),"login SELECT projects reference_object_id through the ordinal map");
  var restoredBuffer=java.nio.ByteBuffer.wrap((byte[])decoded.get("bytes"));ApocBRPayloadProvenance.copy(decoded.get("bytes"),restoredBuffer);
  check(ApocBRPayloadProvenance.metadata(restoredBuffer).equals(restoredMetadata),"native byteData wrapper retains reconstructed provenance for the next detached snapshot");
 }
 static boolean sqlReferenceColumn(){return ApocBRPlayerRelationalReader.SQL.contains("'reference_object_id',r");}
 static void collectionAccess()throws Exception {
  Object cheats=ApocBRPlayerRelationalAccess.get(new zombie.characters.PlayerCheats(),"cheats");
  var flag=zombie.characters.CheatType.values()[0];
  for(int i=0;i<2;i++) {
   ((Set<?>)cheats).clear();
   invoke("add",new Class[]{Object.class,Object.class},cheats,flag);
   check(((Set<?>)cheats).contains(flag),"native cheat EnumSet restored, including cached call");
   check(ApocBRPlayerRelationalAccess.get(cheats,"isEmpty").equals(false),"EnumSet getter uses public contract");
   ApocBRPlayerRelationalAccess.call(cheats,"clear");
   check(((Set<?>)cheats).isEmpty(),"EnumSet clear uses public contract");
  }
  Object map=Collections.checkedMap(new HashMap<String,String>(),String.class,String.class);
  ApocBRPlayerRelationalAccess.call(map,"put","key","value");
  check(ApocBRPlayerRelationalAccess.call(map,"get","key").equals("value"),"nonpublic Map implementation uses public contract");
  Object list=Arrays.asList("before");
  ApocBRPlayerRelationalAccess.call(list,"set",0,"after");
  check(ApocBRPlayerRelationalAccess.call(list,"get",0).equals("after"),"nonpublic List implementation uses public contract");
  try{ApocBRPlayerRelationalAccess.call(List.of(),"add","x");throw new AssertionError("immutable mutation accepted");}
  catch(IllegalStateException expected){check(expected.getCause() instanceof InvocationTargetException&&expected.getCause().getCause() instanceof UnsupportedOperationException,"native collection failure remains visible");}
 }
 static void bagLinks()throws Exception {
  var root=new zombie.inventory.ItemContainer();var outerContents=new zombie.inventory.ItemContainer();var innerContents=new zombie.inventory.ItemContainer();
  var outer=(zombie.inventory.types.InventoryContainer)blank(zombie.inventory.types.InventoryContainer.class);
  var inner=(zombie.inventory.types.InventoryContainer)blank(zombie.inventory.types.InventoryContainer.class);
  var leaf=(zombie.inventory.InventoryItem)blank(zombie.inventory.InventoryItem.class);
  ApocBRPlayerRelationalReconstruction.restoreBag(outer,outerContents,40);
  ApocBRPlayerRelationalReconstruction.restoreBag(inner,innerContents,20);
  ApocBRPlayerRelationalReconstruction.restoreContainerItem(root,outer);
  ApocBRPlayerRelationalReconstruction.restoreContainerItem(outerContents,inner);
  ApocBRPlayerRelationalReconstruction.restoreContainerItem(innerContents,leaf);
  check(outer.getContainer()==root&&outer.getInventory()==outerContents,"outer bag parent and contents remain distinct");
  check(inner.getContainer()==outerContents&&inner.getInventory()==innerContents&&leaf.getContainer()==innerContents,"nested bag and leaf preserve parent links");
  check(outerContents.containingItem==outer&&outerContents.inventoryContainer==outer&&innerContents.containingItem==inner&&innerContents.inventoryContainer==inner,"replaced bag contents retain native reverse links");
  check(outer.getWeightReduction()==40&&outerContents.getWeightReduction()==40&&inner.getWeightReduction()==20&&innerContents.getWeightReduction()==20,"weight reduction agrees on bag and contents");
  check(root.items.equals(List.of(outer))&&outerContents.items.equals(List.of(inner))&&innerContents.items.equals(List.of(leaf)),"nested membership remains acyclic");
  check(root.includingObsoleteItems.equals(root.items)&&outerContents.includingObsoleteItems.equals(outerContents.items),"native inventory lists agree");
  ApocBRPlayerRelationalReconstruction.set(outer,"actualWeight",1f);ApocBRPlayerRelationalReconstruction.set(inner,"actualWeight",2f);ApocBRPlayerRelationalReconstruction.set(leaf,"actualWeight",3f);
  for(var item:List.of(outer,inner,leaf))item.setName("fixture");
  check(root.getContentsWeight()==6f,"native nested contents weight completes with expected total");
 }
 static void fluidBag()throws Exception {
  // Supply only the Water name registry; no game scripts/native world are initialized.
  var registryField=zombie.entity.components.fluids.Fluid.class.getDeclaredField("fluidStringMap");registryField.setAccessible(true);
  @SuppressWarnings("unchecked") var registry=(Map<String,Object>)registryField.get(null);
  Object previous=registry.put("Water",zombie.entity.components.fluids.Fluid.Water);
  try {
   var root=new zombie.inventory.ItemContainer();var contents=new zombie.inventory.ItemContainer();
   var bag=(zombie.inventory.types.InventoryContainer)blank(zombie.inventory.types.InventoryContainer.class);bag.setName("fixture");
   ApocBRPlayerRelationalReconstruction.set(bag,"actualWeight",1f);
   ApocBRPlayerRelationalReconstruction.restoreBag(bag,contents,40);ApocBRPlayerRelationalReconstruction.restoreContainerItem(root,bag);
   Map<String,Object> state=new LinkedHashMap<>();state.put("capacity",4f);state.put("inputLocked",false);state.put("canPlayerEmpty",true);state.put("containerName","FluidContainer");state.put("hiddenAmount",false);state.put("rainCatcher",0f);
   state.put("whitelist",null);state.put("blacklist",null);state.put("fluids",List.of(Map.of("type","Water","amount",2.5f,"colorOverride",Map.of("r",0.2f,"g",0.4f,"b",0.8f))));
   invoke("restoreComponent",new Class[]{Object.class,String.class,Object.class},bag,"FluidContainer",state);
   var fluid=bag.getFluidContainer();
   check(fluid!=null&&fluid.getAmount()==2.5f&&fluid.getCapacity()==4f,"bag fluid component restores water amount and capacity");
   check(bag.getInventory()==contents&&bag.getContainer()==root&&contents.containingItem==bag,"fluid restoration preserves bag inventory ownership");
   check(root.getContentsWeight()==3.5f,"native bag weight includes reconstructed water");
   var bytes=java.nio.ByteBuffer.allocate(4096);fluid.save(bytes);bytes.flip();
   var client=(zombie.entity.components.fluids.FluidContainer)ApocBRPlayerRelationalReconstruction.create("zombie.entity.components.fluids.FluidContainer");
   client.load(bytes,249);
   check(!bytes.hasRemaining()&&client.getAmount()==2.5f&&client.getCapacity()==4f,"native fluid save/load preserves reconstructed water on client wire path");
  }finally{if(previous==null)registry.remove("Water");else registry.put("Water",previous);}
 }
 static void fitnessPersistence()throws Exception {
  var nativeFitness=new zombie.characters.BodyDamage.Fitness(null);
  Object exercise=blank(Class.forName("zombie.characters.BodyDamage.Fitness$FitnessExercise"));
  ApocBRPlayerRelationalReconstruction.set(exercise,"type","squats");
  ApocBRPlayerRelationalReconstruction.set(nativeFitness,"currentExe",exercise);
  nativeFitness.incRegularity();nativeFitness.incRegularity();
  float regularity=nativeFitness.getRegularity("squats");check(regularity>0,"native exercise repetition increases regularity");
  var tables=new HashMap<String,List<Map<String,Object>>>();var rows=new ArrayList<Map<String,Object>>();
  rows.add(Map.of("map_name","regularityMap","key","squats","real_value",(double)regularity));
  rows.add(Map.of("map_name","stiffnessIncMap","key","legs","real_value",12.5d));
  rows.add(Map.of("map_name","stiffnessTimerMap","key","legs","integer_value",72L));
  rows.add(Map.of("map_name","exeTimer","key","squats","integer_value",1729000000123L));
  tables.put("fitness_value",rows);tables.put("fitness_body_part",List.of(Map.of("position",0,"body_part_key","arms")));
  var profile=new ApocBRPlayerRelationalReader.Profile(tables);
  var constructor=ApocBRPlayerRelationalReconstruction.class.getDeclaredConstructor(ApocBRPlayerRelationalReader.Profile.class);constructor.setAccessible(true);
  Object reconstruction=constructor.newInstance(profile);
  var player=(zombie.characters.IsoPlayer)blank(zombie.characters.IsoPlayer.class);
  var restored=new zombie.characters.BodyDamage.Fitness(null);ApocBRPlayerRelationalReconstruction.set(player,"fitness",restored);
  var method=ApocBRPlayerRelationalReconstruction.class.getDeclaredMethod("sequences",zombie.characters.IsoPlayer.class);method.setAccessible(true);method.invoke(reconstruction,player);
  check(restored.getRegularity("squats")==regularity,"relational regularity restores without precision loss");
  check(restored.getCurrentExeStiffnessInc("legs")==12.5f&&restored.getCurrentExeStiffnessTimer("legs")==72,"exercise stiffness amount and timer restore");
  var bytes=java.nio.ByteBuffer.allocate(4096);restored.save(bytes);bytes.flip();
  var client=new zombie.characters.BodyDamage.Fitness(null);client.load(bytes,249);
  check(client.getRegularity("squats")==regularity&&!bytes.hasRemaining(),"vanilla fitness wire save/load preserves relational exercise progress");
  for(String field:List.of("regularityMap","stiffnessIncMap","stiffnessTimerMap","exeTimer","bodypartToIncStiffness"))check(ApocBRPlayerRelationalAccess.get(restored,field).equals(ApocBRPlayerRelationalAccess.get(client,field)),"fitness wire round trip preserves "+field);
 }
 static void itemTypes()throws Exception {
  for(Class<?> type:List.of(zombie.inventory.InventoryItem.class,zombie.inventory.types.DrainableComboItem.class,zombie.inventory.types.HandWeapon.class,zombie.inventory.types.Food.class)) {
   var item=(zombie.inventory.InventoryItem)blank(type);
   var row=new HashMap<String,Object>();row.put("runtime_class",type.getName());row.put("save_type",-1);
   ApocBRPlayerRelationalReconstruction.validateItemType(item,row);checks++;
   row.put("save_type",0);
   try{ApocBRPlayerRelationalReconstruction.validateItemType(item,row);throw new AssertionError("invalid native save sentinel accepted");}catch(IllegalStateException expected){checks++;}
   row.put("save_type",-1);row.put("runtime_class","zombie.inventory.types.Clothing");
   try{ApocBRPlayerRelationalReconstruction.validateItemType(item,row);throw new AssertionError("changed runtime class accepted");}catch(IllegalStateException expected){checks++;}
  }
  try{ApocBRPlayerRelationalReconstruction.validateItemType(null,Map.of("save_type",-1));throw new AssertionError("missing factory item accepted");}catch(IllegalStateException expected){checks++;}
 }
 static void ownership() {
  Map<String,List<Map<String,Object>>> tables=new HashMap<>();
  tables.put("character_state",List.of(Map.of("inventory_container_id",1)));
  tables.put("container",List.of(Map.of("id",1)));
  tables.put("item",List.of(Map.of("id",2),Map.of("id",3)));
  tables.put("item_location",List.of(Map.of("item_id",2,"container_id",1,"relation","container","position",0),Map.of("item_id",3,"parent_item_id",2,"relation","weapon_attachment","position",0)));
  ApocBRPlayerRelationalReconstruction.validateOwnership(new ApocBRPlayerRelationalReader.Profile(tables));checks++;
  tables.put("item_location",List.of(Map.of("item_id",2,"parent_item_id",3,"relation","weapon_attachment","position",0),Map.of("item_id",3,"parent_item_id",2,"relation","weapon_attachment","position",0)));
  try{ApocBRPlayerRelationalReconstruction.validateOwnership(new ApocBRPlayerRelationalReader.Profile(tables));throw new AssertionError("cycle accepted");}catch(IllegalStateException expected){checks++;}
  tables.put("item",List.of(Map.of("id",2),Map.of("id",4),Map.of("id",6)));
  tables.put("container",List.of(Map.of("id",1),Map.of("id",3,"owner_item_id",2),Map.of("id",5,"owner_item_id",4)));
  tables.put("bag_state",List.of(Map.of("item_id",2,"container_id",3),Map.of("item_id",4,"container_id",5)));
  tables.put("item_location",List.of(Map.of("item_id",2,"container_id",1,"relation","container","position",0),Map.of("item_id",4,"container_id",3,"relation","container","position",0),Map.of("item_id",6,"container_id",5,"relation","container","position",0)));
  ApocBRPlayerRelationalReconstruction.validateOwnership(new ApocBRPlayerRelationalReader.Profile(tables));checks++;
  tables.put("bag_state",List.of(Map.of("item_id",2,"container_id",1),Map.of("item_id",4,"container_id",5)));
  try{ApocBRPlayerRelationalReconstruction.validateOwnership(new ApocBRPlayerRelationalReader.Profile(tables));throw new AssertionError("bag pointing to root accepted");}catch(IllegalStateException expected){checks++;}
  tables.put("bag_state",List.of(Map.of("item_id",2,"container_id",5),Map.of("item_id",4,"container_id",3)));
  try{ApocBRPlayerRelationalReconstruction.validateOwnership(new ApocBRPlayerRelationalReader.Profile(tables));throw new AssertionError("contradictory bag ownership accepted");}catch(IllegalStateException expected){checks++;}
  tables.remove("bag_state");
  try{ApocBRPlayerRelationalReconstruction.validateOwnership(new ApocBRPlayerRelationalReader.Profile(tables));throw new AssertionError("unbound bag contents accepted");}catch(IllegalStateException expected){checks++;}
 }
 public static void main(String[] args)throws Exception{collectionAccess();bagLinks();fluidBag();fitnessPersistence();itemTypes();nativeFields();jdbc();components();ownership();System.out.println("Reconstruction checks="+checks);}
}
'''.replace('ROOTS',roots)
with tempfile.TemporaryDirectory(prefix='apocbr_reconstruct_') as folder:
    target=Path(folder)
    harness=target/'ReconstructionTest.java';harness.write_text(HARNESS,encoding='utf-8')
    connector=target/'ApocBRPostgresConnector.java';connector.write_text('package zombie.savefile; public class ApocBRPostgresConnector { public static int dictionaryPublicationTimeoutSeconds(){return 30;} public static String sqlDiagnostic(Throwable error,boolean playerHints){return "exception="+error.getClass().getSimpleName();}public static Runnable dictionaryCheck=()->{}; public static java.sql.Connection borrowDictionaryConnection() throws java.sql.SQLException {dictionaryCheck.run();throw new java.sql.SQLException();} public static java.sql.Connection borrowPlayerConnection() throws java.sql.SQLException {throw new java.sql.SQLException();}}',encoding='utf-8')
    names=['Limits','Schema','Access','Snapshot','Codecs','Capture','Mutations','Delta','Metrics','Database','Reader','Reconstruction']
    sources=[SRC/'ApocBRPayloadProvenance.java']+[SRC/('ApocBRPlayerRelational'+n+'.java') for n in names]
    sources += [SRC.parent/path for path in ['world/ApocBRWorldDictionaryDecode.java','world/ApocBRWorldDictionarySnapshot.java','savefile/ApocBRWorldDictionaryDatabase.java']]
    sources += inactive_persistence_stubs(target)
    subprocess.run([str(JDK/'javac.exe'),'--release','25','-cp',str(GAME),'-d',folder,*map(str,sources),str(connector),str(harness)],check=True)
    subprocess.run([str(JDK/'java.exe'),'-cp',folder+os.pathsep+str(GAME),'zombie.savefile.ReconstructionTest'],check=True)
