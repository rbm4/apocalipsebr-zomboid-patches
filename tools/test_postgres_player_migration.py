"""Production migration worker with real read-only SQLite and isolated engine/PostgreSQL ports."""
from pathlib import Path
import os
import sqlite3
import subprocess
import tempfile
from contextlib import closing

ROOT = Path(__file__).resolve().parents[1]
GAME = Path(os.environ.get('APOCBR_TEST_GAME_JAR', r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
JDK = ROOT / 'jdk/bin'
STUBS = {
'zombie/ZomboidFileSystem.java': '''package zombie;public class ZomboidFileSystem {
 public static final ZomboidFileSystem instance=new ZomboidFileSystem();
 public String getCurrentSaveDir(){return System.getProperty("fixture")+"/save";}
 public String getCacheDir(){return System.getProperty("fixture");}}''',
'zombie/core/Core.java': '''package zombie.core;public class Core {public static String gameSaveWorld="world";}''',
'zombie/core/znet/SteamUtils.java': '''package zombie.core.znet;public class SteamUtils {
 public static boolean isSteamModeEnabled(){return false;} public static String convertSteamIDToString(long id){return Long.toString(id);}}''',
'zombie/core/raknet/UdpConnection.java': '''package zombie.core.raknet;public class UdpConnection {
 public String user;public String[] usernames=new String[4];public long steam;
 public String getUserName(){return user;} public long getSteamId(){return steam;}}''',
'zombie/network/GameServer.java': '''package zombie.network;public class GameServer {
 public static boolean server=true,coop;public static String serverName="fixture";public static Thread mainThread;
 public static final java.util.List<zombie.characters.IsoPlayer> Players=new java.util.ArrayList<>();
 public static final Engine udpEngine=new Engine();public static class Engine {
 public final java.util.List<zombie.core.raknet.UdpConnection> connections=new java.util.ArrayList<>();}}''',
'zombie/iso/IsoWorld.java': '''package zombie.iso;public class IsoWorld {
 public static final IsoWorld instance=new IsoWorld();public Object currentCell=new Object();}''',
'zombie/characters/IsoPlayer.java': '''package zombie.characters;public class IsoPlayer {
 public boolean remote;public int serverPlayerIndex,tag;public IsoPlayer(Object cell){}
 public String getUsername(){return "unused";}public void load(java.nio.ByteBuffer data,int version)throws java.io.IOException {
 if(Thread.currentThread()!=zombie.network.GameServer.mainThread)throw new AssertionError("native load on worker");
 tag=data.get();if(tag==99)throw new java.io.IOException("invalid native character");}
 public void setHealth(float v){}public Damage getBodyDamage(){return new Damage();}
 public static class Damage {public void setOverallBodyHealth(float v){}}}''',
'zombie/debug/DebugLog.java': '''package zombie.debug;public class DebugLog {
 public static final java.util.List<String> lines=new java.util.concurrent.CopyOnWriteArrayList<>();
 public static void log(String text){lines.add(text);}}''',
'zombie/savefile/ApocBRLoginGuard.java': '''package zombie.savefile;public class ApocBRLoginGuard {
 public static int denied;public static void deny(Object c){denied++;}}''',
'zombie/savefile/ApocBRPlayerRelationalSync.java': '''package zombie.savefile;public class ApocBRPlayerRelationalSync {
 public static boolean enabled(){return Boolean.getBoolean("apocbr.postgres.enabled");}}''',
'zombie/savefile/ApocBRPostgresConnector.java': '''package zombie.savefile;public class ApocBRPostgresConnector {
 static final ThreadLocal<Boolean> background=ThreadLocal.withInitial(()->false);
 public static void runBackgroundDatabaseWork(Runnable work){background.set(true);try{work.run();}finally{background.remove();}}
 public static java.sql.Connection borrowPlayerConnection(){throw new AssertionError("stub database owns JDBC port");}}''',
'zombie/savefile/ApocBRPlayerRelationalSnapshot.java': '''package zombie.savefile;final class ApocBRPlayerRelationalSnapshot {
 record Snapshot(int tag){}static Snapshot copy(Object player){return new Snapshot(((zombie.characters.IsoPlayer)player).tag);}}''',
'zombie/savefile/ApocBRPlayerRelationalCapture.java': '''package zombie.savefile;final class ApocBRPlayerRelationalCapture {
 static class IncompleteGraphException extends java.sql.SQLException {String diagnostic(){return "graphIssues=fixture";}}
 record Row(String table,java.util.Map<String,Object> values){}
 record Graph(int tag,java.util.List<Row> rows){Graph(int tag){this(tag,java.util.List.of());}int items(){return 0;}
 java.util.UUID snapshot(){return ApocBRPlayerRelationalDatabase.named("snapshot:"+tag);}}
 static void prepare(){}ApocBRPlayerRelationalCapture(java.util.UUID id,java.util.Map<Integer,java.util.UUID> aliases,long budget){}
 Graph capture(ApocBRPlayerRelationalSnapshot.Snapshot snapshot,java.util.List<String> issues){return new Graph(snapshot.tag());}
 static void requireComplete(Graph graph,String stage)throws java.sql.SQLException {if(graph.tag()==88)throw new java.sql.SQLException("incomplete","22000");}}''',
'zombie/savefile/ApocBRPlayerRelationalDatabase.java': '''package zombie.savefile;
import java.util.*;import java.sql.*;
final class ApocBRPlayerRelationalDatabase {
 interface Connections {Connection open()throws SQLException;}
 record Candidate(UUID dataset,String label,String world,String identity,boolean coop,int slot,int nativeId,int worldVersion,String blobHash,List<String> loadIssues){}
 record Receipt(UUID character,boolean complete){Map<Integer,UUID> aliases(){return Map.of();}UUID head(){return named("snapshot:1");}}
 static final List<Candidate> saved=new java.util.concurrent.CopyOnWriteArrayList<>();
 static final List<Long> times=new java.util.concurrent.CopyOnWriteArrayList<>();
 static final Map<String,ApocBRPlayerRelationalCapture.Graph> graphs=new java.util.concurrent.ConcurrentHashMap<>();
 static final java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
 static volatile boolean persisting;
 ApocBRPlayerRelationalDatabase(Connections c){}
 static UUID named(String value){return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 Receipt inspect(Candidate candidate)throws SQLException {
 if(!ApocBRPostgresConnector.background.get())throw new AssertionError("SQL on simulation thread");
 if(candidate.identity().equals("collision"))throw new SQLException("Player slot belongs to another source dataset","22023");
 return new Receipt(named(candidate.identity()),candidate.identity().equals("already"));}
 boolean persist(Candidate candidate,Receipt r,ApocBRPlayerRelationalCapture.Graph g)throws SQLException {
 if(candidate.identity().equals("failure"))throw new SQLException("injected write failure","08006");
 if(candidate.identity().equals("old")){persisting=true;try{release.await();}catch(InterruptedException stop){throw new SQLException("stopped","57014");}}
 saved.add(candidate);graphs.put(candidate.identity(),g);times.add(System.nanoTime());return true;}}
''',
'zombie/savefile/ApocBRPlayerRelationalReader.java': '''package zombie.savefile;import java.util.*;
public class ApocBRPlayerRelationalReader {
 record ValidationSlot(String identity,boolean coop,int slot,String username) {}
 static final java.util.List<java.util.UUID> validationLookups=new java.util.concurrent.CopyOnWriteArrayList<>();
 static final java.util.Map<String,ApocBRPlayerValidationTargets.Target> validationTargets=new java.util.concurrent.ConcurrentHashMap<>();
 ValidationSlot validationSlot(String world,UUID character)throws java.sql.SQLException {
 if(!ApocBRPostgresConnector.background.get())throw new AssertionError("validation lookup on game thread");
 validationLookups.add(character);
 var target=ApocBRPlayerValidationTargets.failedOctober26().stream().filter(t->t.character().equals(character)).findFirst().orElseThrow();
 String user=switch(target.localId()){case 3->"validation-ok";case 20->"restorefail";case 71->"encodefail";case 79->"changed";case 81->"connecting";case 85->"lookupfail";case 90->"coop";default->null;};
 if(user==null)return null;
 if(user.equals("lookupfail"))throw new java.sql.SQLException("PRIVATE_VALIDATION_QUERY","08006");
 validationTargets.put(user,target);return new ValidationSlot(user,user.equals("coop"),0,user);
 }
 public record Profile(Map<String,List<Map<String,Object>>> tables) {
 List<Map<String,Object>> rows(String table){return tables.getOrDefault(table,List.of());}
 Map<String,Object> one(String table){return rows(table).getFirst();}}
 Profile load(String world,String identity,int slot,boolean coop)throws java.sql.SQLException {
 if(!ApocBRPostgresConnector.background.get())throw new AssertionError("verification JDBC on game thread");
 if(identity.equals("readfail"))throw new java.sql.SQLException("PRIVATE_READ_DATA","08006");
 if(System.getProperty("apocbr.postgres.playerMigration.mode","").equals("validate-failed")) {
 var target=validationTargets.get(identity);if(target==null)throw new AssertionError("nonallowlisted lookup");
 int tag=identity.equals("restorefail")?81:identity.equals("encodefail")?82:1;
 return new Profile(Map.of("snapshot",List.of(Map.of("snapshot_id",identity.equals("changed")?UUID.randomUUID():target.snapshot(),
 "persisted_character_id",target.character(),"source_dataset_id",UUID.randomUUID())),"fixture",List.of(Map.of("tag",tag))));
 }
 var graph=ApocBRPlayerRelationalDatabase.graphs.get(identity);int tag=graph==null?1:graph.tag();
 String save=zombie.ZomboidFileSystem.instance.getCurrentSaveDir();
 return new Profile(Map.of("snapshot",List.of(Map.of("snapshot_id",ApocBRPlayerRelationalDatabase.named("snapshot:"+tag),
 "persisted_character_id",ApocBRPlayerRelationalDatabase.named(identity.equals("wronghead")?"other":identity),
 "source_dataset_id",ApocBRPlayerRelationalDatabase.named("sqlite-save:"+save))),"fixture",List.of(Map.of("tag",tag)),
 "character_trait",identity.equals("unknown")?List.of(Map.of("trait_key","fixture:removed")):List.of()));
 }}''',
'zombie/savefile/ApocBRPlayerRelationalReconstruction.java': '''package zombie.savefile;
final class ApocBRPlayerRelationalReconstruction {
 static zombie.characters.IsoPlayer reconstruct(ApocBRPlayerRelationalReader.Profile p,int slot) {
 if(Thread.currentThread()!=zombie.network.GameServer.mainThread)throw new AssertionError("reconstruction on worker");
 int tag=((Number)p.one("fixture").get("tag")).intValue();
 if(!p.rows("character_trait").isEmpty()) {
 try(var trace=ApocBRPlayerReconstructionLog.begin("RESTORE_PLAYER",slot)){ApocBRPlayerReconstructionLog.missingTrait("fixture:removed");}
 }
 if(System.getProperty("apocbr.postgres.playerMigration.mode","").equals("validate-failed")&&tag==1) {
 if(ApocBRPlayerMigration.admitLogin(new zombie.core.raknet.UdpConnection(),"validation-ok"))throw new AssertionError("validation reservation missing");
 }
 if(tag==81) {
 try(var trace=ApocBRPlayerReconstructionLog.begin("RESTORE_PLAYER",slot)) {
 trace.event("ITEM_TYPE_MISMATCH","; fullType=Base.FixtureMissing; expectedClass=zombie.inventory.InventoryItem; actualClass=MISSING");
 throw new IllegalStateException("PRIVATE_RECONSTRUCTION_DATA",new java.lang.reflect.InvocationTargetException(new IllegalArgumentException("PRIVATE_NESTED_DATA")));
 }
 }
 var player=new zombie.characters.IsoPlayer(null);player.tag=tag;player.serverPlayerIndex=slot;return player;
 }}''',
'zombie/savefile/ServerPlayerDB.java': '''package zombie.savefile;
public class ServerPlayerDB {
 record Encoded(byte[] data){}
 static Encoded encodePostgresClientProfile(zombie.characters.IsoPlayer player,ApocBRPlayerRelationalReader.Profile profile,int slot,String username)throws java.io.IOException {
 if(Thread.currentThread()!=zombie.network.GameServer.mainThread)throw new AssertionError("serialize on worker");
 if(player.tag==82)throw new java.io.IOException("PRIVATE_ENCODING_DATA");return new Encoded(new byte[]{1});
 }}''',
'zombie/savefile/MigrationTest.java': r'''package zombie.savefile;
import java.nio.file.*;import java.time.*;import java.util.*;import zombie.network.GameServer;import zombie.core.raknet.UdpConnection;
public class MigrationTest {
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
 static String logs()throws Exception{return Files.readString(Path.of(System.getProperty("fixture"),"save","player-migration.log"));}
 static void equipmentDiagnostics() {
  var items=List.<Map<String,Object>>of(Map.of("id",1,"native_id",10),Map.of("id",2,"native_id",11));
  List<Map<String,Object>> equipment=new ArrayList<>();
  for(int i=0;i<10;i++)equipment.add(Map.of("kind","worn","slot","slot"+i,"position",i,"item_id",1));
  var profile=new ApocBRPlayerRelationalReader.Profile(Map.of("item",items,"equipment",equipment));
  for(boolean positionsOnly:List.of(true,false)) {
   List<ApocBRPlayerRelationalCapture.Row> rows=new ArrayList<>();
   items.forEach(item->rows.add(new ApocBRPlayerRelationalCapture.Row("item",item)));
   equipment.forEach(item->{var changed=new HashMap<>(item);changed.put(positionsOnly?"position":"item_id",positionsOnly?9-((Number)item.get("position")).intValue():2);rows.add(new ApocBRPlayerRelationalCapture.Row("equipment",changed));});
   try{ApocBRPlayerMigrationVerification.compare(profile,new ApocBRPlayerRelationalCapture.Graph(1,rows),step->{});throw new AssertionError("equipment discrepancy accepted");}
   catch(ApocBRPlayerMigrationVerification.AssertionFailure failure) {
    check(failure.code.equals("EQUIPMENT_TARGET_MISMATCH"),"equipment assertion preserved");
    String diagnostic=ApocBRPlayerMigration.verificationDiagnostic(failure);
    check(diagnostic.contains("equipmentPositionsOnly="+positionsOnly),"classifies position-only versus target changes");
    check(diagnostic.contains("equipmentDifferenceCount="+(positionsOnly?20:10)),"reports total discrepancies");
    check(diagnostic.contains("expectedNativeId=10"),"expected item identified");
    check(diagnostic.contains(positionsOnly?"actualNativeId=NONE":"actualNativeId=11"),"actual item identified");
    check(diagnostic.contains("equipmentDiff7=")&&!diagnostic.contains("equipmentDiff8="),"diagnostic bounded at eight differences");
   }
  }
 }
 static void comparisons() {
  equipmentDiagnostics();
  normalizedEquipment();
  var tables=new LinkedHashMap<String,List<Map<String,Object>>>();
  tables.put("item",List.of(Map.of("id",1,"native_id",10,"full_type","Base.Axe","condition",5,"current_uses",1,"current_ammo_count",0)));
  tables.put("equipment",List.of(Map.of("kind","hand","slot","primary","position",0,"item_id",1)));
  tables.put("character_trait",List.of(Map.of("trait_key","Brave")));
  tables.put("character_stat",List.of(Map.of("stat_key","Hunger","value",0.25)));
  tables.put("character_perk",List.of(Map.of("perk_key","Axe","level",3,"xp",125.0)));
  tables.put("body_part",List.of(Map.of("body_part_key","Head","health",95.0)));
  tables.put("character_state",List.of(Map.of("is_dead",false,"hours_survived",30.0,"total_xp",125.0,"xp_level",3)));
  List<ApocBRPlayerRelationalCapture.Row> rows=new ArrayList<>();
  tables.forEach((table,values)->values.forEach(value->rows.add(new ApocBRPlayerRelationalCapture.Row(table,new HashMap<>(value)))));
  // Verify allocation differences and float precision do not masquerade as state corruption.
  UUID item=UUID.randomUUID();rows.get(0).values().put("id",item);rows.get(1).values().put("item_id",item);
  var profile=new ApocBRPlayerRelationalReader.Profile(tables);var graph=new ApocBRPlayerRelationalCapture.Graph(1,rows);
  ApocBRPlayerMigrationVerification.compare(profile,graph,step->{});
  rows.get(0).values().put("native_id",10L);
  try{ApocBRPlayerMigrationVerification.compare(profile,graph,step->{});throw new AssertionError("key type discrepancy accepted");}
  catch(ApocBRPlayerMigrationVerification.AssertionFailure failure) {
   check(failure.code.equals("KEYS_MISMATCH_item"),"key mismatch preserved");
   String diagnostic=ApocBRPlayerMigration.verificationDiagnostic(failure);
   check(diagnostic.contains("missingKeyCount=1")&&diagnostic.contains("Integer:10")&&diagnostic.contains("Long:10"),"key samples identify Java numeric representation differences");
  }finally{rows.get(0).values().put("native_id",10);}
  check(ApocBRPlayerMigrationVerification.equalValue(0.1,(float)0.1),"float normalization accepted");
  check(!ApocBRPlayerMigrationVerification.equalValue(125,126),"real state discrepancy rejected");
  for(var target:List.of(List.of("item","condition"),List.of("character_trait","trait_key"),List.of("character_stat","value"),List.of("character_perk","xp"),List.of("body_part","health"),List.of("character_state","total_xp"),List.of("equipment","item_id"))) {
   var row=rows.stream().filter(r->r.table().equals(target.get(0))).findFirst().orElseThrow();
   String field=target.get(1);Object before=row.values().get(field);row.values().put(field,999);
   try{ApocBRPlayerMigrationVerification.compare(profile,graph,step->{});throw new AssertionError("comparison accepted mismatch "+target);}
   catch(IllegalStateException expected){}finally{row.values().put(field,before);}
  }
  try{ApocBRPlayerMigrationVerification.identity(profile,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());throw new AssertionError("missing metadata accepted");}catch(RuntimeException expected){}
  UUID character=UUID.randomUUID(),snapshot=UUID.randomUUID(),dataset=UUID.randomUUID();
  var metadata=new HashMap<String,Object>(Map.of("persisted_character_id",character,"snapshot_id",snapshot,"source_dataset_id",dataset));
  var identified=new ApocBRPlayerRelationalReader.Profile(Map.of("snapshot",List.of(metadata)));
  ApocBRPlayerMigrationVerification.identity(identified,character,snapshot,dataset);
  for(String field:List.of("persisted_character_id","snapshot_id","source_dataset_id")) {
   Object before=metadata.put(field,UUID.randomUUID());
   try{ApocBRPlayerMigrationVerification.identity(identified,character,snapshot,dataset);throw new AssertionError("changed identity accepted "+field);}
   catch(ApocBRPlayerMigrationVerification.AssertionFailure expected){}finally{metadata.put(field,before);}
  }
 }
 static void normalizedEquipment() {
  var items=List.<Map<String,Object>>of(Map.of("id",1,"native_id",10),Map.of("id",2,"native_id",11));
  var expected=List.<Map<String,Object>>of(
   Map.of("kind","worn","slot","Back","position",7,"item_id",1),
   Map.of("kind","worn","slot","Belt","position",20,"item_id",2),
   Map.of("kind","attached","slot","BeltLeft","position",4,"item_id",2),
   Map.of("kind","hand","slot","secondary","position",1,"item_id",1));
  var source=new ApocBRPlayerRelationalReader.Profile(Map.of("item",items,"equipment",expected));
  List<ApocBRPlayerRelationalCapture.Row> rows=new ArrayList<>();items.forEach(i->rows.add(new ApocBRPlayerRelationalCapture.Row("item",i)));
  var a=new HashMap<>(expected.get(0));a.put("position",0);
  var b=new HashMap<>(expected.get(1));b.put("position",1);
  var c=new HashMap<>(expected.get(2));c.put("position",0);
  rows.add(new ApocBRPlayerRelationalCapture.Row("equipment",b)); // unordered JDBC rows are allowed
  rows.add(new ApocBRPlayerRelationalCapture.Row("equipment",expected.get(3)));
  rows.add(new ApocBRPlayerRelationalCapture.Row("equipment",c));rows.add(new ApocBRPlayerRelationalCapture.Row("equipment",a));
  var graph=new ApocBRPlayerRelationalCapture.Graph(1,rows);
  ApocBRPlayerMigrationVerification.compare(source,graph,s->{});
  a.put("position",2);
  try{ApocBRPlayerMigrationVerification.compare(source,graph,s->{});throw new AssertionError("relative worn order change accepted");}
  catch(ApocBRPlayerMigrationVerification.AssertionFailure failure){check(failure.code.equals("EQUIPMENT_TARGET_MISMATCH"),"relative order enforced");}
  a.put("position",0);a.put("item_id",2);
  try{ApocBRPlayerMigrationVerification.compare(source,graph,s->{});throw new AssertionError("target swap accepted");}catch(IllegalStateException expectedFailure){}
  a.put("item_id",1);a.put("slot","WrongSlot");
  try{ApocBRPlayerMigrationVerification.compare(source,graph,s->{});throw new AssertionError("slot change accepted");}catch(IllegalStateException expectedFailure){}
  a.put("slot","Back");a.put("position",1);
  try{ApocBRPlayerMigrationVerification.compare(source,graph,s->{});throw new AssertionError("duplicate position accepted");}
  catch(ApocBRPlayerMigrationVerification.AssertionFailure failure){check(failure.code.equals("INVALID_EQUIPMENT_POSITION"),"ambiguous positions rejected");}
  var traits=new ApocBRPlayerRelationalReader.Profile(Map.of("character_trait",List.of(Map.of("trait_key","mod:removed"))));
  var empty=new ApocBRPlayerRelationalCapture.Graph(1,List.of());
  ApocBRPlayerMigrationVerification.compare(traits,empty,Set.of("mod:removed"),s->{});
  try{ApocBRPlayerMigrationVerification.compare(traits,empty,Set.of(),s->{});throw new AssertionError("unrecorded trait loss accepted");}catch(IllegalStateException expectedFailure){}
 }
 public static void main(String[] args)throws Exception {
 GameServer.mainThread=Thread.currentThread();System.setProperty("apocbr.postgres.enabled","true");
 if(args.length>0&&args[0].equals("validate")) {
  System.setProperty("fixture",System.getProperty("fixture")+"/validation");
  Files.createDirectories(Path.of(System.getProperty("fixture"),"save"));
  System.setProperty("apocbr.postgres.playerMigration.enabled","true");
  System.setProperty("apocbr.postgres.playerMigration.mode","validate-failed");
  check(ApocBRPlayerValidationTargets.failedOctober26().size()==84,"exact failure cohort size");
  var connecting=new UdpConnection();connecting.user="connecting";GameServer.udpEngine.connections.add(connecting);
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(105);
  while(true) {
   ApocBRPlayerMigration.update();
   if(Files.exists(Path.of(System.getProperty("fixture"),"save","player-migration.log"))&&logs().contains("DRAINED"))break;
   check(System.nanoTime()<deadline,"validator drains fixed cohort");Thread.sleep(5);
  }
  ApocBRPlayerMigration.shutdown();String log=logs();
  check(log.contains("selected=84; migrated=0; skipped=79; collisions=0; failed=1; verified=1; verificationFailed=3"),"targeted validation outcomes isolated");
  check(ApocBRPlayerRelationalDatabase.saved.isEmpty(),"validator never persists");
  check(!log.contains("LOAD_REQUESTED")&&!log.contains("PERSISTING")&&!log.contains("LAST_LOGIN")&&!log.contains("PRIVATE_"),"no SQLite/backfill/recency or private failure text");
  check(log.contains("SNAPSHOT_CHANGED")&&log.contains("actualSnapshot="),"changed head reported without reconstruction");
  check(log.contains("VERIFICATION_SKIPPED_ONLINE")&&log.contains("VALIDATION_TARGET_NOT_ACTIVE")&&log.contains("VALIDATION_TARGET_IDENTITY_DENIED"),"online/inactive/mode exclusion");
  check(ApocBRLoginGuard.denied==1,"reconstruction reserves account");
  check(ApocBRPlayerRelationalReader.validationLookups.equals(ApocBRPlayerValidationTargets.failedOctober26().stream().map(ApocBRPlayerValidationTargets.Target::character).toList()),"only exact allowlisted character IDs queried");
  System.out.println("Targeted validator: exact 84-ID cohort, no SQLite or writes, identity/head/online checks, per-target failures and reservation passed");return;
 }
 if(args.length>0&&args[0].startsWith("stop")) {
  System.setProperty("apocbr.postgres.playerMigration.enabled","true");ApocBRPlayerMigration.update();
  var field=ApocBRPlayerMigration.class.getDeclaredField("instance");field.setAccessible(true);
  Object current=field.get(null);var request=ApocBRPlayerMigration.class.getDeclaredField("request");request.setAccessible(true);
  long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
  while(request.get(current)==null){check(System.nanoTime()<until,"capture requested without server continuation");Thread.sleep(5);}
  boolean persisted=args[0].equals("stop-verification");
  if(persisted) {
   ApocBRPlayerRelationalDatabase.release.countDown();ApocBRPlayerMigration.update();
   var verify=ApocBRPlayerMigration.class.getDeclaredField("verification");verify.setAccessible(true);
   while(verify.get(current)==null){check(System.nanoTime()<until,"verification requested without continuation");Thread.sleep(5);}
  }
  ApocBRPlayerMigration.shutdown();check(logs().contains("STOPPED"),"shutdown cancels pending capture without deadlock");
  check(ApocBRPlayerRelationalDatabase.saved.size()==(persisted?1:0),"shutdown retains only completed commits");
  System.out.println("Migration shutdown while awaiting server-thread capture passed");return;
 }
 comparisons();
 check(!ApocBRPlayerMigration.enabled(),"migration defaults off");
 var readOnly=ApocBRPlayerMigration.class.getDeclaredMethod("readOnly",Path.class);readOnly.setAccessible(true);
 try(var c=(java.sql.Connection)readOnly.invoke(null,Path.of(System.getProperty("fixture"),"save","players.db"));var s=c.createStatement()) {
  try{s.executeUpdate("DELETE FROM networkPlayers");throw new AssertionError("source writable");}catch(java.sql.SQLException expected){}
 }
 Instant now=Instant.parse("2026-10-26T12:00:00Z");ZoneId utc=ZoneId.of("UTC");
 check(ApocBRPlayerMigration.recent(now.minusSeconds(604800),now),"seven-day boundary skipped");
 check(!ApocBRPlayerMigration.recent(now.minusSeconds(604801),now),"older eligible");
 for(String bad:new String[]{null,"","garbage","2026-02-30 00:00:00","2026-10-27 00:00:00"})
 check(ApocBRPlayerMigration.lastLogin(bad,utc,now)==null,"invalid/missing/future timestamp eligible");
 check(ApocBRPlayerMigration.lastLogin("2026-10-19 12:00:00",utc,now).equals(now.minusSeconds(604800)),"timestamp parse");
 check(ApocBRPlayerMigration.lastLogin("2026-11-01 01:30:00",ZoneId.of("America/New_York"),now.plusSeconds(9999999))==null,"ambiguous timezone eligible");
 var row=new ApocBRPlayerMigration.Row(1,"world","user","123",0,"name",249,false,1);
 check(ApocBRPlayerMigration.matches(row,"user","456",false),"username ownership");
 check(ApocBRPlayerMigration.matches(row,"other","123",true),"coop steam ownership");
 check(!ApocBRPlayerMigration.matches(row,"other","123",false),"dedicated identity unchanged");
 System.setProperty("apocbr.postgres.playerMigration.enabled","true");
 var online=new UdpConnection();online.user="online";GameServer.udpEngine.connections.add(online);
 var pending=new UdpConnection();GameServer.udpEngine.connections.add(pending);
 check(ApocBRPlayerMigration.admitLogin(pending,"connecting"),"pre-auth attempt admitted");
 long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);boolean testedReservation=false;
 while(true) {
  ApocBRPlayerMigration.update();
  if(ApocBRPlayerRelationalDatabase.persisting&&!testedReservation){
   check(!ApocBRPlayerMigration.admitLogin(new UdpConnection(),"old"),"login cannot race reserved import");
   check(ApocBRLoginGuard.denied==1,"temporary denial");
   check(ApocBRPlayerMigration.admitLogin(new UdpConnection(),"unrelated"),"other logins unaffected");
   ApocBRPlayerRelationalDatabase.release.countDown();testedReservation=true;
  }
  if(Files.exists(Path.of(System.getProperty("fixture"),"save","player-migration.log"))&&logs().contains("DRAINED"))break;
  check(System.nanoTime()<deadline,"worker drained bounded fixture");Thread.sleep(5);
 }
 ApocBRPlayerMigration.shutdown();check(testedReservation,"reservation tested");
 check(ApocBRPlayerMigration.admitLogin(new UdpConnection(),"old"),"login admitted after worker drains");
 String log=logs();
 for(String code:List.of("START","SELECTED","SKIPPED_RECENT_LOGIN","LAST_LOGIN_UNKNOWN","LOADED","PERSISTING","PERSISTED","COLLISION_DISCARDED","SKIPPED_ONLINE_OR_CONNECTING","ALREADY_MIGRATED","FAILED","DRAINED"))check(log.contains(code),"logged "+code);
 check(log.contains("collisions=1; failed=3; verified=4; verificationFailed=4"),"migration and verification failures counted separately");
 for(String stage:List.of("RECONSTRUCT_PLAYER","ENCODE_CLIENT_PROFILE","READ_PROFILE","PROFILE_IDENTITY"))check(log.contains("stage="+stage),"verification exception stage "+stage);
 check(log.contains("RECONSTRUCTION_DIAGNOSTIC")&&log.contains("fullType=Base.FixtureMissing"),"item failure context persisted in migration audit");
 check(log.contains("traitKey=fixture:removed")&&log.contains("check=TRAITS_SKIPPED_MISSING=1"),"recorded trait omission survives native future and worker comparison with audit");
 check(log.contains("causedBy=java.lang.reflect.InvocationTargetException")&&log.contains("causedBy=java.lang.IllegalArgumentException"),"nested reconstruction exceptions logged");
 check(log.contains("ASSERTION_PASSED")&&log.contains("VERIFIED"),"verification successes logged");
 check(log.indexOf("PERSISTED; localId=2")<log.indexOf("VERIFIED; localId=2"),"verification only after commit");
 check(!log.contains("PRIVATE_"),"exception messages cannot expose private data");
 var saved=ApocBRPlayerRelationalDatabase.saved;
 check(saved.stream().map(c->c.identity()).toList().equals(List.of("old","unknown","restorefail","encodefail","readfail","wronghead","after")),"verification exceptions retain commits and do not stop later characters");
 check(saved.stream().allMatch(c->c.blobHash()!=null),"normal provenance hash");
 check(ApocBRPlayerRelationalDatabase.times.get(1)-ApocBRPlayerRelationalDatabase.times.get(0)>=1_000_000_000L,"fixed interval between characters");
 check(log.indexOf("LOAD_REQUESTED; localId=2")<log.indexOf("PERSISTED; localId=2"),"load and commit log order");
 // Read-only sources: failures and collision must never delete or rewrite a local character.
 try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+System.getProperty("fixture")+"/save/players.db");var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM networkPlayers")) {
  r.next();check(r.getInt(1)==16,"all source rows preserved");
 }
 check(zombie.debug.DebugLog.lines.stream().noneMatch(s->s.contains("password")),"no credentials in logs");
 System.out.println("Migration worker: SQLite, recency, collisions, online/pre-auth exclusion, reservation, pacing, failure isolation, provenance and source preservation passed");
 }}''',
}

with tempfile.TemporaryDirectory(prefix='apocbr_migration_') as folder:
    target = Path(folder)
    (target / 'save').mkdir()
    (target / 'db').mkdir()
    with closing(sqlite3.connect(target / 'save/players.db')) as c, c:
        c.execute('CREATE TABLE networkPlayers(id INTEGER PRIMARY KEY,world TEXT,username TEXT,steamid TEXT,playerIndex INTEGER,name TEXT,worldversion INTEGER,isDead BOOLEAN,data BLOB)')
        for i, (user, tag) in enumerate([('recent', 1), ('old', 1), ('collision', 1), ('unknown', 1), ('online', 1), ('connecting', 1), ('already', 1), ('corrupt', 99), ('incomplete', 88), ('failure', 1), ('otherworld', 1), ('restorefail', 81), ('encodefail', 82), ('readfail', 83), ('wronghead', 84), ('after', 1)], 1):
            c.execute('INSERT INTO networkPlayers VALUES(?,?,?,?,?,?,?,?,?)', (i, 'elsewhere' if user == 'otherworld' else 'world', user, '', 0, 'Survivor;\n'+user, 249, False, bytes([tag])))
    from datetime import datetime, timedelta
    with closing(sqlite3.connect(target / 'db/fixture.db')) as c, c:
        c.execute('CREATE TABLE whitelist(username TEXT,lastConnection TEXT)')
        c.executemany('INSERT INTO whitelist VALUES(?,?)', [('recent', (datetime.now()-timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S')), ('old', '2020-01-01 00:00:00')])
    files = []
    for name, content in STUBS.items():
        path = target / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        files.append(str(path))
    source = ROOT / '42.21.0/src/zombie/savefile'
    files += [str(source / name) for name in ('ApocBRPlayerMigration.java', 'ApocBRPlayerMigrationVerification.java', 'ApocBRPlayerReconstructionLog.java', 'ApocBRPlayerValidationTargets.java', 'ApocBRPlayerLoadQuality.java', 'ApocBRPlayerRelationalLimits.java')]
    subprocess.run([str(JDK/'javac.exe'), '--release', '25', '-cp', str(GAME), '-d', folder, *files], check=True)
    subprocess.run([str(JDK/'java.exe'), '-Dfixture='+folder, '-cp', folder+os.pathsep+str(GAME), 'zombie.savefile.MigrationTest'], check=True, timeout=40)
    subprocess.run([str(JDK/'java.exe'), '-Dfixture='+folder, '-cp', folder+os.pathsep+str(GAME), 'zombie.savefile.MigrationTest', 'stop'], check=True, timeout=12)
    subprocess.run([str(JDK/'java.exe'), '-Dfixture='+folder, '-cp', folder+os.pathsep+str(GAME), 'zombie.savefile.MigrationTest', 'stop-verification'], check=True, timeout=12)
    subprocess.run([str(JDK/'java.exe'), '-Dfixture='+folder, '-cp', folder+os.pathsep+str(GAME), 'zombie.savefile.MigrationTest', 'validate'], check=True, timeout=115)
