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
 record Graph(int tag){int items(){return 1;}java.util.List<Integer> rows(){return java.util.List.of(1);}}
 static void prepare(){}ApocBRPlayerRelationalCapture(java.util.UUID id,java.util.Map<Integer,java.util.UUID> aliases,long budget){}
 Graph capture(ApocBRPlayerRelationalSnapshot.Snapshot snapshot,java.util.List<String> issues){return new Graph(snapshot.tag());}
 static void requireComplete(Graph graph,String stage)throws java.sql.SQLException {if(graph.tag()==88)throw new java.sql.SQLException("incomplete","22000");}}''',
'zombie/savefile/ApocBRPlayerRelationalDatabase.java': '''package zombie.savefile;
import java.util.*;import java.sql.*;
final class ApocBRPlayerRelationalDatabase {
 interface Connections {Connection open()throws SQLException;}
 record Candidate(UUID dataset,String label,String world,String identity,boolean coop,int slot,int nativeId,int worldVersion,String blobHash,List<String> loadIssues){}
 record Receipt(UUID character,boolean complete){Map<Integer,UUID> aliases(){return Map.of();}}
 static final List<Candidate> saved=new java.util.concurrent.CopyOnWriteArrayList<>();
 static final List<Long> times=new java.util.concurrent.CopyOnWriteArrayList<>();
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
 saved.add(candidate);times.add(System.nanoTime());return true;}}
''',
'zombie/savefile/MigrationTest.java': r'''package zombie.savefile;
import java.nio.file.*;import java.time.*;import java.util.*;import zombie.network.GameServer;import zombie.core.raknet.UdpConnection;
public class MigrationTest {
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
 static String logs()throws Exception{return Files.readString(Path.of(System.getProperty("fixture"),"save","player-migration.log"));}
 public static void main(String[] args)throws Exception {
 GameServer.mainThread=Thread.currentThread();System.setProperty("apocbr.postgres.enabled","true");
 if(args.length>0&&args[0].equals("stop")) {
  System.setProperty("apocbr.postgres.playerMigration.enabled","true");ApocBRPlayerMigration.update();
  var field=ApocBRPlayerMigration.class.getDeclaredField("instance");field.setAccessible(true);
  Object current=field.get(null);var request=ApocBRPlayerMigration.class.getDeclaredField("request");request.setAccessible(true);
  long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
  while(request.get(current)==null){check(System.nanoTime()<until,"capture requested without server continuation");Thread.sleep(5);}
  ApocBRPlayerMigration.shutdown();check(logs().contains("STOPPED"),"shutdown cancels pending capture without deadlock");
  check(ApocBRPlayerRelationalDatabase.saved.isEmpty(),"cancelled capture never commits");
  System.out.println("Migration shutdown while awaiting server-thread capture passed");return;
 }
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
 check(log.contains("collisions=1; failed=3"),"all failures and collisions reported");
 var saved=ApocBRPlayerRelationalDatabase.saved;
 check(saved.stream().map(c->c.identity()).toList().equals(List.of("old","unknown")),"only eligible offline complete characters persisted");
 check(saved.stream().allMatch(c->c.blobHash()!=null),"normal provenance hash");
 check(ApocBRPlayerRelationalDatabase.times.get(1)-ApocBRPlayerRelationalDatabase.times.get(0)>=1_000_000_000L,"fixed interval between characters");
 check(log.indexOf("LOAD_REQUESTED; localId=2")<log.indexOf("PERSISTED; localId=2"),"load and commit log order");
 // Read-only sources: failures and collision must never delete or rewrite a local character.
 try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+System.getProperty("fixture")+"/save/players.db");var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM networkPlayers")) {
  r.next();check(r.getInt(1)==11,"all source rows preserved");
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
        for i, (user, tag) in enumerate([('recent', 1), ('old', 1), ('collision', 1), ('unknown', 1), ('online', 1), ('connecting', 1), ('already', 1), ('corrupt', 99), ('incomplete', 88), ('failure', 1), ('otherworld', 1)], 1):
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
    files += [str(source / name) for name in ('ApocBRPlayerMigration.java', 'ApocBRPlayerLoadQuality.java', 'ApocBRPlayerRelationalLimits.java')]
    subprocess.run([str(JDK/'javac.exe'), '--release', '25', '-cp', str(GAME), '-d', folder, *files], check=True)
    subprocess.run([str(JDK/'java.exe'), '-Dfixture='+folder, '-cp', folder+os.pathsep+str(GAME), 'zombie.savefile.MigrationTest'], check=True, timeout=40)
    subprocess.run([str(JDK/'java.exe'), '-Dfixture='+folder, '-cp', folder+os.pathsep+str(GAME), 'zombie.savefile.MigrationTest', 'stop'], check=True, timeout=12)
