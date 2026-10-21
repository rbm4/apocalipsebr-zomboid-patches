"""Actual player DB and profile packet with isolated game/JDBC fixtures; no live DB."""
from postgres_test_support import inactive_persistence_stubs

from pathlib import Path
import os
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'42.21.0/src/zombie'
GAME=Path(os.environ.get('APOCBR_TEST_GAME_JAR',r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
LIB=ROOT/'42.21.0/lib'
JDK=ROOT/'jdk/bin'
STUBS={
'zombie/savefile/ApocBRPlayerRelationalReconstruction.java': '''package zombie.savefile;
final class ApocBRPlayerRelationalReconstruction {
 static zombie.characters.IsoPlayer reconstruct(ApocBRPlayerRelationalReader.Profile profile,int slot) {
  var player=new zombie.characters.IsoPlayer(null);player.serverPlayerIndex=slot;player.remote=true;
  player.version=((Number)profile.one("snapshot").get("source_world_version")).intValue();
  if(Boolean.TRUE.equals(profile.one("character_state").get("is_dead")))player.setHealth(0);return player;
 }
}''',
'zombie/core/Core.java': '''package zombie.core; public class Core {
 public static boolean debug; public static String gameSaveWorld="servertest";
 public static Core getInstance(){return new Core();} public boolean isNoSave(){return false;} }''',
'zombie/network/GameServer.java': '''package zombie.network; public class GameServer {
 public static boolean server=true,coop; public static final Engine udpEngine=new Engine();
 public static class Engine { public java.util.List<zombie.core.raknet.UdpConnection> connections=new java.util.ArrayList<>();} }''',
'zombie/network/GameClient.java': '''package zombie.network; public class GameClient {
 public static boolean client; public static String username="alice",ip="localhost";
 public static zombie.core.raknet.UdpConnection connection; }''',
'zombie/core/znet/SteamUtils.java': '''package zombie.core.znet; public class SteamUtils {
 public static boolean enabled; public static boolean isSteamModeEnabled(){return enabled;} }''',
'zombie/iso/IsoWorld.java': '''package zombie.iso; public class IsoWorld {
 public static final IsoWorld instance=new IsoWorld(); public Object currentCell=new Object();
 public static int getWorldVersion(){return 249;} }''',
'zombie/characters/IsoPlayer.java': '''package zombie.characters; public class IsoPlayer {
 public int serverPlayerIndex; public boolean remote,dead; public String username="alice";
 public static int payloadBytes=1; public int captures; public float health=100; public byte loaded; public int version;
 public IsoPlayer(Object cell){} public Desc getDescriptor(){return new Desc();}
 public static class Desc { public String getForename(){return "Alice";} public String getSurname(){return "Survivor";} }
 public float getX(){return 1;} public float getY(){return 2;} public float getZ(){return 0;}
 public int getIndex(){return 0;} public boolean isDead(){return dead;}
 public void save(java.nio.ByteBuffer out)throws java.io.IOException{captures++;for(int i=0;i<payloadBytes;i++)out.put((byte)17);}
 public void load(java.nio.ByteBuffer in,int v)throws java.io.IOException{loaded=in.get();version=v;if(loaded==99)throw new java.io.IOException("bad snapshot");}
 public void setHealth(float value){health=value;} public Damage getBodyDamage(){return new Damage();}
 public static class Damage {public void setOverallBodyHealth(float value){} } }''',
'zombie/core/raknet/UdpConnection.java': '''package zombie.core.raknet; public class UdpConnection {
 public zombie.characters.IsoPlayer[] players=new zombie.characters.IsoPlayer[4];
 public String getUserName(){return "alice";} public String getIDStr(){return "765";}
 public java.nio.ByteBuffer sent;
 public zombie.core.network.ByteBufferWriter startPacket(){sent=java.nio.ByteBuffer.allocate(4096);return new zombie.core.network.ByteBufferWriter(sent);} }''',
'zombie/network/PacketTypes.java': '''package zombie.network; public class PacketTypes {
 public enum PacketType { LoadPlayerProfile; public void doPacket(zombie.core.network.ByteBufferWriter out){}
 public void send(zombie.core.raknet.UdpConnection c){} } }''',
'zombie/GameWindow.java': '''package zombie; public class GameWindow {
 public static String ReadString(java.nio.ByteBuffer in){return new zombie.core.network.ByteBufferReader(in).getUTF();}
 public static void WriteString(java.nio.ByteBuffer out,String value){
  byte[] bytes=value==null?new byte[0]:value.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.putShort((short)bytes.length);out.put(bytes);
 }
}''',
}
HARNESS=r'''
package zombie.savefile;
import java.sql.*;import java.lang.reflect.*;import java.util.*;import javax.sql.DataSource;
import zombie.network.*;import zombie.characters.IsoPlayer;import zombie.core.raknet.UdpConnection;
public class PlayersTest {
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 static Object zero(Class<?> t){if(t==boolean.class)return false;if(t==int.class)return 0;if(t==long.class)return 0L;return null;}
 static class Fake {
  Map<String,Object> row;int opened,closed,committed,rolledBack;boolean fail,duplicate,relationalAbsent;
  List<String> queries=new ArrayList<>();
  Connection open(){opened++;return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
   switch(m.getName()){
    case "close":closed++;return null;
    case "commit":committed++;return null;
    case "rollback":rolledBack++;return null;
    case "prepareStatement":return statement((String)a[0]);
   }return zero(m.getReturnType());});}
  DataSource source(){return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->m.getName().equals("getConnection")?open():zero(m.getReturnType()));}
  PreparedStatement statement(String sql){queries.add(sql);Map<Integer,Object> args=new HashMap<>();
   return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
    if(m.getName().equals("setQueryTimeout")){check(a[0].equals(2),"bounded statements");return null;}
    if(m.getName().startsWith("set")){args.put((Integer)a[0],a[1]);return null;}
    if(m.getName().equals("execute")){if(fail)throw new SQLException("private error","08006");return false;}
    if(m.getName().equals("executeUpdate")){
     if(fail)throw new SQLException("private error","08006");
     if(sql.startsWith("DELETE")){row=null;return 1;}
     if(sql.startsWith("UPDATE public.networkplayers SET steamid=")){if(row!=null&&row.get("steamid").equals(""))row.put("steamid",args.get(1));return 1;}
     String[] fields={"world","username","steamid","playerindex","name","x","y","z","worldversion","isdead","splitusername","data","source_server_id","source_dictionary_namespace_id","source_dictionary_view_id"};
     row=new HashMap<>();row.put("id",42);for(int i=0;i<fields.length;i++)row.put(fields[i],args.get(i+1));return 1;
    }
    if(m.getName().equals("executeQuery")){
     if(fail)throw new SQLException("private error","08006");
     if(sql.startsWith("WITH selected")) {
      boolean found=!relationalAbsent&&row!=null&&Objects.equals(row.get("world"),args.get(1))&&Objects.equals(row.get(args.get(2).equals("steam_coop")?"steamid":"username"),args.get(3))&&Objects.equals(row.get("playerindex"),args.get(4));
      List<String[]> result=new ArrayList<>();
      if(found){result.add(new String[]{"snapshot","{\"status\":\"complete\",\"schema_version\":1,\"codec_version\":4,\"source_world_version\":249,\"expected_object_count\":0,\"expected_item_count\":0,\"expected_container_count\":0,\"expected_edge_count\":0}"});result.add(new String[]{"character_state","{\"is_dead\":"+row.get("isdead")+"}"});}
      if(found)result.getFirst()[1]=new org.json.JSONObject(result.getFirst()[1]).put("split_username",row.get("splitusername")).toString();
      int[] cursor={-1};return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(rp,rm,ra)->switch(rm.getName()){case "next"->++cursor[0]<result.size();case "getString"->result.get(cursor[0])[(int)ra[0]-1];default->zero(rm.getReturnType());});
     }
     final Map<String,Object> selected=row==null?null:new HashMap<>(row);
     boolean match=selected!=null&&!sql.contains("WHERE false");
     if(match&&sql.contains("playerindex=?")){
      String identity=sql.contains("WHERE steamid=?")?"steamid":"username";
      match=Objects.equals(selected.get(identity),args.get(1))&&Objects.equals(selected.get("world"),args.get(2))&&Objects.equals(selected.get("playerindex"),args.get(3));
     }
     final boolean found=match;int[] index={-1};
     return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(rp,rm,ra)->{
      if(rm.getName().equals("next"))return ++index[0]<(sql.startsWith("SELECT COUNT")?1:found?(duplicate?2:1):0);
      if(rm.getName().startsWith("get")){
       check(index[0]>=0,"positioned result");
       if(sql.startsWith("SELECT COUNT"))return 3;
       if(sql.startsWith("SELECT steamid"))return selected.get("steamid");
       if(sql.startsWith("SELECT id FROM"))return selected.get("id");
       String[] columns={"id","x","y","z","data","worldversion","isdead","splitusername","source_server_id","source_dictionary_namespace_id","source_dictionary_view_id"};return selected.get(columns[(int)ra[0]-1]);
      }return zero(rm.getReturnType());});
    }return zero(m.getReturnType());});}
 }
 // Read the transmitted reply: the cached request handler is no longer the response object.
 static zombie.network.packets.connection.LoadPlayerProfilePacket sentProfile(UdpConnection c) {
  var reply=new zombie.network.packets.connection.LoadPlayerProfilePacket();var bytes=c.sent.duplicate();bytes.flip();
  GameServer.server=false;
  try{reply.parse(new zombie.core.network.ByteBufferReader(bytes),null);check(!bytes.hasRemaining(),"sent profile consumed exactly");}
  finally{GameServer.server=true;}
  return reply;
 }
 static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
 static void vanillaProfileResponse(Object packet)throws Exception {
  var bytes=java.nio.ByteBuffer.allocate(4096);
  var writer=new zombie.core.network.ByteBufferWriter(bytes);
  ((zombie.network.packets.connection.LoadPlayerProfilePacket)packet).write(writer);bytes.flip();
  // Load only the original profile codec from the real game JAR. All its dependencies
  // remain shared with this fixture, including the real ByteBuffer reader/writer.
  String name="zombie.network.packets.connection.LoadPlayerProfilePacket";
  try(var vanilla=new java.net.URLClassLoader(new java.net.URL[]{new java.io.File(System.getProperty("apocbr.test.gameJar")).toURI().toURL()},PlayersTest.class.getClassLoader()) {
   @Override protected Class<?> loadClass(String type,boolean resolve)throws ClassNotFoundException {
    if(!type.equals(name))return super.loadClass(type,resolve);
    Class<?> found=findLoadedClass(type);if(found==null)found=findClass(type);if(resolve)resolveClass(found);return found;
   }
  }) {
   Object decoded=vanilla.loadClass(name).getConstructor().newInstance();
   GameServer.server=false;GameClient.client=true;
   try{decoded.getClass().getMethod("parse",zombie.core.network.ByteBufferReader.class,zombie.network.IConnection.class).invoke(decoded,new zombie.core.network.ByteBufferReader(bytes),null);}
   finally{GameServer.server=true;GameClient.client=false;}
   for(String field:List.of("isExist","playerIndex","x","y","z","worldVersion","isDead","username"))check(Objects.equals(field(packet,field),field(decoded,field)),"unpatched client profile codec receives "+field);
   check(Arrays.equals((byte[])field(packet,"buffer"),(byte[])field(decoded,"buffer"))&&!bytes.hasRemaining(),"unpatched client receives exact player bytes with no wire layout changes");
  }
 }
 public static void main(String[] ignored)throws Exception {
  Fake fake=new Fake();var connector=new ApocBRPostgresConnector(fake.source(),s->{});
  Field instance=ApocBRPostgresConnector.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,connector);
  Field configured=ApocBRPostgresConnector.class.getDeclaredField("configured");configured.setAccessible(true);configured.setBoolean(null,true);
  System.setProperty("apocbr.postgres.enabled","true");
  System.setProperty("apocbr.postgres.playerRelational.enabled","false"); // Isolate native save/profile routing; worker has its own fixture.
  System.clearProperty("apocbr.postgres.players.enabled");
  check(!ApocBRPlayerDatabase.enabled(),"master PostgreSQL enablement retains SQLite players by default");
  System.setProperty("apocbr.postgres.players.enabled","false");
  check(!ApocBRPlayerDatabase.enabled(),"explicit false retains SQLite players");
  System.setProperty("apocbr.postgres.players.enabled","true");
  check(ApocBRPlayerDatabase.enabled(),"authoritative PostgreSQL players require explicit opt-in");
  ServerPlayerDB.setAllow(true);ServerPlayerDB db=ServerPlayerDB.getInstance();
  check(db.conn==null,"PostgreSQL mode never opens players.db");
  var helper=new ApocBRPlayerDatabase();check(helper.load("servertest","alice",0,false)==null,"new account profile absent");
  UdpConnection connection=new UdpConnection();IsoPlayer player=new IsoPlayer(null);
  System.setProperty("apocbr.postgres.serverId","3");
  var encodedOrigin=new ApocBRPayloadProvenance(3,UUID.randomUUID(),UUID.randomUUID());ApocBRPayloadProvenance.published(encodedOrigin);
  db.serverUpdateNetworkCharacter(player,0,connection);check(player.captures==1 && fake.row==null,"native queue captures once before SQL");
  ApocBRPayloadProvenance.published(new ApocBRPayloadProvenance(3,UUID.randomUUID(),UUID.randomUUID()));
  db.process();check(fake.row!=null && connector.status().pending()==0,"native process commits primary data, no mirror queue");
  check(player.captures==1,"no second serialization");
  check(helper.load("servertest","alice",0,false).origin().equals(encodedOrigin),"queue retains encoding view when current dictionary changes before worker persistence");
  IsoPlayer loaded=db.serverLoadNetworkCharacter(0,"alice");
  check(loaded!=null&&loaded.loaded==0&&loaded.version==249&&loaded.remote&&loaded.serverPlayerIndex==0,"server routes relational profile without native deserialization");
  check(db.serverLoadNetworkCharacter(1,"alice")==null,"different character slot absent");
  // Simulate an old imported blob whose IDs/version belong to a different dictionary.
  fake.row.put("data",new byte[]{99});fake.row.put("worldversion",123);fake.row.put("splitusername","split-alice");
  int queriesBeforeProfile=fake.queries.size();
  var packet=new zombie.network.packets.connection.LoadPlayerProfilePacket();packet.processServer(null,connection);packet=sentProfile(connection);
  check((boolean)field(packet,"isExist") && ((byte[])field(packet,"buffer"))[0]==17,"profile packet serializes relational reconstruction, never forwards imported bytes");
  check(field(packet,"worldVersion").equals(249),"freshly serialized profile advertises current engine version, not imported version");
  check(fake.queries.subList(queriesBeforeProfile,fake.queries.size()).stream().noneMatch(q->q.startsWith("SELECT id,x,y,z,data")),"client profile does not read legacy native payload");
  check(db.serverLoadPostgresClientProfile(1,"alice",false,"alice")==null,"missing relational profile stays absent");
  var nativeProfile=db.serverLoadPostgresClientProfile(0,"alice",false,"alice");
  check(nativeProfile.origin().equals(ApocBRPayloadProvenance.localEncoding()),"profile payload uses current encoding provenance");
  check(field(packet,"username").equals("split-alice"),"profile preserves relational split username");
  vanillaProfileResponse(packet);
  for(byte slot=1;slot<4;slot++) {
   fake.row.put("playerindex",(int)slot);
   var secondary=new zombie.network.packets.connection.LoadPlayerProfilePacket();
   Field slotField=secondary.getClass().getDeclaredField("playerIndex");slotField.setAccessible(true);slotField.setByte(secondary,slot);
   secondary.processServer(null,connection);secondary=sentProfile(connection);check((boolean)field(secondary,"isExist"),"secondary slot uses relational reconstruction");vanillaProfileResponse(secondary);
  }
  fake.row.put("playerindex",0);fake.relationalAbsent=true;
  var missing=new zombie.network.packets.connection.LoadPlayerProfilePacket();missing.processServer(null,connection);missing=sentProfile(connection);
  check(!(boolean)field(missing,"isExist")&&fake.row.get("data")!=null,"legacy bytes cannot substitute for a missing relational profile");vanillaProfileResponse(missing);
  fake.relationalAbsent=false;
  IsoPlayer.payloadBytes=40000;
  check(db.serverLoadPostgresClientProfile(0,"alice",false,"alice").data().length==40000,"client serialization grows beyond initial buffer without truncation");
  IsoPlayer.payloadBytes=ApocBRPlayerRelationalLimits.SOURCE_BYTES+1;
  try{db.serverLoadPostgresClientProfile(0,"alice",false,"alice");throw new AssertionError("unbounded client payload accepted");}catch(java.io.IOException expected){}
  IsoPlayer.payloadBytes=1;
  player.dead=true;db.serverUpdateNetworkCharacter(player,0,connection);db.process();loaded=db.serverLoadNetworkCharacter(0,"alice");
  check(loaded.health==0,"dead player state restored");
  helper.convert("servertest","alice","765");GameServer.coop=true;zombie.core.znet.SteamUtils.enabled=true;
  loaded=db.serverLoadNetworkCharacter(0,"765");check(loaded!=null,"Steam coop identity lookup");
  var coopPacket=new zombie.network.packets.connection.LoadPlayerProfilePacket();coopPacket.processServer(null,connection);coopPacket=sentProfile(connection);
  check((boolean)field(coopPacket,"isExist"),"Steam coop client profile uses relational identity lookup");vanillaProfileResponse(coopPacket);
  check(db.getNetworkUserSteamID("nonexistent-local-dir","alice","servertest").equals("765"),"Steam lookup ignores absent SQLite file");
  check(db.countOtherNetworkAccounts("765","alice")==3,"account counts routed");
  check(PlayerDBHelper.containsNetworkPlayer("absent","alice","servertest"),"Lua/helper existence routed");
  fake.fail=true;check(db.serverLoadNetworkCharacter(0,"765")==null,"database failure does not fall back to SQLite");fake.fail=false;
  fake.duplicate=true;try{helper.load("servertest","765",0,true);throw new AssertionError("ambiguous lookup accepted");}catch(SQLException expected){}fake.duplicate=false;
  PlayerDBHelper.removePlayer("absent","alice","servertest");check(fake.row==null,"Lua/helper removal routed");
  check(db.serverLoadNetworkCharacter(0,"765")==null,"removed profile absent");
  check(fake.opened==fake.closed,"all operation leases returned");
  check(fake.queries.stream().noneMatch(q->q.startsWith("CREATE")||q.startsWith("ALTER")||q.contains("sqlite_master")),"no SQLite schema path");
  db.close();connector.close();System.out.println("Players: native queue/save/load, profile packet, death, coop, Steam/account helpers and no SQLite fallback passed");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='apocbr_players_') as folder:
    target=Path(folder);files=[]
    for name,body in STUBS.items():
        path=target/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf-8');files.append(path)
    harness=target/'PlayersTest.java';harness.write_text(HARNESS,encoding='utf-8');files.append(harness)
    files += [SRC/'savefile'/name for name in ['ApocBRPayloadProvenance.java','ApocBRPostgresConnector.java','ApocBRPlayerDatabase.java','ServerPlayerDB.java','PlayerDBHelper.java',
        'ApocBRPlayerLoadQuality.java','ApocBRPlayerRelationalReader.java','ApocBRPlayerReconstructionLog.java','ApocBRPlayerRelationalLimits.java','ApocBRPlayerRelationalSchema.java','ApocBRPlayerRelationalAccess.java',
        'ApocBRPlayerRelationalSnapshot.java','ApocBRPlayerRelationalCodecs.java','ApocBRPlayerRelationalCapture.java','ApocBRPlayerRelationalMutations.java','ApocBRPlayerRelationalDelta.java','ApocBRPlayerRelationalMetrics.java','ApocBRPlayerRelationalDatabase.java','ApocBRPlayerRelationalSync.java']]
    files += [SRC/path for path in ['world/ApocBRWorldDictionaryDecode.java','world/ApocBRWorldDictionarySnapshot.java','savefile/ApocBRWorldDictionaryDatabase.java']]
    files.append(SRC/'network/packets/connection/LoadPlayerProfilePacket.java')
    cp=os.pathsep.join(map(str,[GAME,LIB/'postgresql-42.7.14.jar',LIB/'HikariCP-7.1.0.jar']))
    files += inactive_persistence_stubs(target, mutations=False)
    subprocess.run([str(JDK/'javac.exe'),'--release','25','-cp',cp,'-d',folder,*map(str,files)],check=True)
    subprocess.run([str(JDK/'java.exe'),'-Dapocbr.test.gameJar='+str(GAME),'-cp',folder+os.pathsep+cp,'zombie.savefile.PlayersTest'],check=True)
