"""Execute actual ServerWorldDatabase/connector code with isolated engine and JDBC fixtures."""
from postgres_test_support import inactive_persistence_stubs

from pathlib import Path
import os
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / '42.21.0/src/zombie'
GAME = Path(os.environ.get('APOCBR_TEST_GAME_JAR', r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
JDK = ROOT / 'jdk/bin'
LIB = ROOT / '42.21.0/lib'

STUBS = {
'zombie/core/Core.java': 'package zombie.core; public class Core { public static boolean debug; public static String gameSaveWorld="servertest"; }',
'zombie/savefile/ServerPlayerDB.java': '''package zombie.savefile; public class ServerPlayerDB {
 private static final ServerPlayerDB instance=new ServerPlayerDB();
 public static ServerPlayerDB getInstance(){return instance;}
 public int countOtherNetworkAccounts(String steam,String name)throws java.sql.SQLException{
 return new ApocBRPlayerDatabase().countOtherAccounts(steam,name);}
 public String getNetworkUserSteamID(String dir,String name,String world)throws java.sql.SQLException{
 return new ApocBRPlayerDatabase().steamId(world,name);}
}''',
'zombie/core/znet/SteamUtils.java': '''package zombie.core.znet; public class SteamUtils {
 public static boolean enabled; public static boolean isSteamModeEnabled(){return enabled;}
 public static String convertSteamIDToString(long id){return Long.toUnsignedString(id);} }''',
'zombie/network/GameServer.java': '''package zombie.network; public class GameServer {
 public static String serverName="servertest"; public static boolean softReset,server=true;
 public static zombie.characters.IsoPlayer getPlayerByUserName(String name){return null;}
 public static zombie.characters.IsoPlayer getPlayerByUserNameForCommand(String name){return null;}
 public static zombie.core.raknet.UdpConnection getConnectionFromPlayer(zombie.characters.IsoPlayer p){return null;} }''',
'zombie/network/ServerOptions.java': '''package zombie.network; public class ServerOptions {
 public static final ServerOptions instance=new ServerOptions();
 public final Bool allowNonAsciiUsername=new Bool(), open=new Bool();
 public final Number maxAccountsPerUser=new Number();
 public boolean getBoolean(String name){return open.value;}
 public static class Bool { public boolean value; public boolean getValue(){return value;} }
 public static class Number { public int value; public int getValue(){return value;} } }''',
'zombie/characters/Roles.java': '''package zombie.characters; public class Roles {
 public static final Role user=new Role("user"), banned=new Role("banned");
 static { user.setId(1);user.addCapability(Capability.LoginOnServer);banned.setId(2); }
 public static void init(){}
 public static Role getRoleById(int id){return id==1?user:banned;}
 public static Role getRole(String name){return user;}
 public static Role getDefaultForNewUser(){return user;}
 public static Role getDefaultForUser(){return user;}
 public static Role getDefaultForBanned(){return banned;}
 public static Role getDefaultForAdmin(){return user;}
 public static Role getDefaultForModerator(){return user;}
 public static Role getDefaultForPriorityUser(){return user;} }''',
}

HARNESS = r'''
package zombie.savefile;
import java.sql.*; import java.lang.reflect.*; import java.util.*; import javax.sql.DataSource;
import zombie.network.*;
public class ServerDatabaseTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static Object zero(Class<?> t){if(t==boolean.class)return false;if(t==int.class)return 0;if(t==long.class)return 0L;return null;}
 static class Fake {
  int borrowed,returned,userlogWrites; boolean bannedSteam,bannedIP,failQuery,missingSchema,userlogExisting;
  List<String> queries=new ArrayList<>(); List<Map<Integer,Object>> changes=new ArrayList<>();
  DataSource source(){return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
   if(m.getName().equals("getConnection")) {borrowed++;return connection();} return zero(m.getReturnType());});}
  Connection connection(){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
   switch(m.getName()) {
    case "setAutoCommit":check((boolean)a[0],"accounts use vanilla autocommit");return null;
    case "setSchema":check(a[0].equals("public"),"schema is public");return null;
    case "close":returned++;return null;
    case "prepareStatement":return statement((String)a[0]);
   }return zero(m.getReturnType());});}
  PreparedStatement statement(String sql){
   Map<Integer,Object> params=new HashMap<>(); queries.add(sql);
   return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
    if(m.getName().equals("setQueryTimeout")){check(a[0].equals(2),"bounded queries");return null;}
    if(m.getName().startsWith("set") && a!=null && a.length==2){params.put((Integer)a[0],a[1]);return null;}
    if(m.getName().equals("executeUpdate")){
     if(sql.startsWith("INSERT INTO userlog")||sql.startsWith("UPDATE userlog")) {
      int amountIndex=sql.startsWith("INSERT")?5:1;
      check(params.get(amountIndex) instanceof Integer,"userlog.amount must use an integer JDBC binding");
      check(params.get(amountIndex).equals(userlogExisting?4:1),"userlog insert/increment retains numeric value");userlogWrites++;
     }
     changes.add(new HashMap<>(params));return 1;
    }
    if(m.getName().equals("executeQuery")){
     if(failQuery || (missingSchema && sql.contains("public.whitelist")))throw new SQLException("password=PRIVATE_SECRET row data", "42P01");
     List<Map<String,Object>> rows=new ArrayList<>();
     if(!sql.contains("WHERE false")) {
      if(sql.startsWith("SELECT * FROM userlog")&&userlogExisting)rows.add(Map.of("amount","3"));
      if(sql.contains("FROM whitelist") && ("alice".equals(params.get(1)) || "admin".equals(params.get(1)))) {
       Map<String,Object> row=new HashMap<>();row.put("id",1);row.put("role",1);row.put("password","stored_hash");row.put("authType",1);row.put("googleKey",null);rows.add(row);
      }
      if(sql.contains("FROM bannedid") && bannedSteam)rows.add(Map.of("steamid","765","reason","blocked"));
      if(sql.contains("FROM bannedip") && bannedIP)rows.add(Map.of("reason","blocked"));
     }
     int[] index={-1};
     return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(r,rm,ra)->{
      if(rm.getName().equals("next"))return ++index[0]<rows.size();
      if(rm.getName().startsWith("get") && ra!=null){check(index[0]>=0 && index[0]<rows.size(),"cursor positioned");Object value=rows.get(index[0]).get(ra[0].toString());
       if(rm.getName().equals("getInt"))return value==null?0:value;
       if(rm.getName().equals("getBoolean"))return value!=null && value.equals(true);return value;}
      return zero(rm.getReturnType());});
    }return zero(m.getReturnType());});
  }
 }
 public static void main(String[] args)throws Exception{
  Fake fake=new Fake();
  var mirror=new ApocBRPostgresConnector(fake.source(), s->{});
  Field instance=ApocBRPostgresConnector.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,mirror);
  Field configured=ApocBRPostgresConnector.class.getDeclaredField("configured");configured.setAccessible(true);configured.setBoolean(null,true);
  System.setProperty("apocbr.postgres.enabled","true");
  ServerWorldDatabase db=new ServerWorldDatabase();
  db.create(); // Validate migration and preserve existing administrator; no native file/DDL.
  check(fake.queries.stream().filter(q->q.contains("WHERE false")).count()==9,"all migrated schemas validated");
  check(fake.queries.stream().noneMatch(q->q.startsWith("CREATE")||q.startsWith("ALTER")),"backend owns migrations");
  check(fake.borrowed==fake.returned,"startup lease returned");
  check(db.containsUser("alice"),"existing whitelist read");
  check(!db.containsUser("bob"),"missing whitelist read");
  ServerOptions.instance.open.value=true;
  check(db.authClient("alice","stored_hash","127.0.0.1",765,1).authorized,"valid credentials allowed");
  check(!db.authClient("alice","wrong","127.0.0.1",765,1).authorized,"wrong password denied");
  check(db.authClient("bob","new_hash","127.0.0.1",765,1).authorized,"new account open-server policy");
  db.addUser("bob","new_hash",1);
  check(fake.queries.stream().anyMatch(q->q.startsWith("INSERT INTO whitelist")),"new account write routed");
  db.setPassword("alice","replacement_hash");
  db.banIp("1.2.3.4","alice","reason",true);db.banSteamID("765","reason",true);
  check(fake.queries.stream().anyMatch(q->q.startsWith("INSERT INTO bannedip")),"IP ban write routed");
  check(fake.queries.stream().anyMatch(q->q.startsWith("DELETE FROM allowedsteamid")),"Steam ban removes allow entry");
  fake.bannedSteam=true;check(!db.authClient(765L).authorized,"Steam ban denies login");
  check(!db.authOwner(765L,999L).authorized,"shared-game owner ban denies login");
  fake.bannedSteam=false;check(db.authClient(765L).authorized,"unbanned Steam allowed");
  check(!db.googleAuthClient("alice","wrong").authorized,"missing 2FA key does not authorize");
  fake.bannedIP=true;check(!db.authClient("alice","stored_hash","1.2.3.4",765,1).authorized,"IP ban denies login");fake.bannedIP=false;
  db.saveRoleCapabilities(zombie.characters.Roles.user);
  db.saveDefaultRole(zombie.characters.Roles.user,"defaultForUser");
  db.addTicket("alice","help",-1);db.addUserlog("alice",Userlog.UserlogType.WarningPoint,"reason","admin",1);
  fake.userlogExisting=true;
  db.addUserlog("alice",Userlog.UserlogType.SuspiciousActivity,"reason","admin",1);
  db.addUserlog("alice",Userlog.UserlogType.LuaChecksum,"reason","admin",1);
  check(fake.userlogWrites==3,"userlog insert and both aggregation update paths execute");
  fake.userlogExisting=false;
  check(fake.queries.stream().anyMatch(q->q.startsWith("INSERT INTO capabilities")),"capabilities routed");
  check(fake.queries.stream().anyMatch(q->q.startsWith("INSERT INTO defaultRoles")),"default roles routed");
  check(fake.queries.stream().anyMatch(q->q.startsWith("INSERT INTO tickets")),"tickets routed");
  int before=fake.borrowed;
  try(var outer=ApocBRServerDatabase.open(null)){try(var inner=ApocBRServerDatabase.open(null)){
   check(outer.connection()==inner.connection(),"nested API calls share one lease");
   check(fake.borrowed==before+1,"nested scopes do not consume pool slots");}}
  check(fake.borrowed==fake.returned,"all API leases released");
  fake.failQuery=true;check(!db.authClient("alice","stored_hash","127.0.0.1",765,1).authorized,"SQL failure does not authorize login");
  check(!db.authOwner(765L,999L).authorized,"owner-query failure does not authorize login");
  try(var call=ApocBRServerDatabase.open(null);var statement=call.connection().prepareStatement("SELECT private")){
   try{statement.executeQuery();throw new AssertionError("query should fail");}catch(SQLException expected){check(!expected.toString().contains("PRIVATE_SECRET"),"secret error details suppressed");}}
  fake.failQuery=false;fake.missingSchema=true;
  try{db.create();throw new AssertionError("missing schema accepted");}catch(SQLException expected){}
  fake.missingSchema=false;check(fake.borrowed==fake.returned,"failure scopes released");
  System.setProperty("apocbr.postgres.serverDatabase.enabled","false");
  before=fake.borrowed;try(var call=ApocBRServerDatabase.open(null)){check(call.connection()==null,"explicit SQLite mode uses original connection");}
  check(fake.borrowed==before,"disabled accounts do not borrow PostgreSQL");
  System.setProperty("apocbr.postgres.serverDatabase.enabled","true");
  mirror.close();try{db.containsUser("alice");throw new AssertionError("unavailable backend fell back");}catch(IllegalStateException expected){}
  check(!db.authClient(765L).authorized,"connection acquisition failure returns denied login");
  System.out.println("Server DB: actual account/auth/ban/role/support methods, schema checks, leases, failure and opt-out tests passed");
 }
}
'''

with tempfile.TemporaryDirectory(prefix='apocbr_server_db_') as folder:
    target = Path(folder)
    files = []
    for name, content in STUBS.items():
        path = target / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        files.append(path)
    harness = target / 'ServerDatabaseTest.java'
    harness.write_text(HARNESS, encoding='utf-8')
    files += [harness, SRC / 'network/ServerWorldDatabase.java', SRC / 'savefile/ApocBRServerDatabase.java', SRC / 'savefile/ApocBRPostgresConnector.java', SRC / 'savefile/ApocBRPlayerDatabase.java']
    files.append(SRC / 'savefile/ApocBRPayloadProvenance.java')
    cp = os.pathsep.join(map(str, [GAME, LIB / 'postgresql-42.7.14.jar', LIB / 'HikariCP-7.1.0.jar']))
    files += inactive_persistence_stubs(target, mutations=True)
    subprocess.run([str(JDK / 'javac.exe'), '--release', '25', '-cp', cp, '-d', folder, *map(str,files)], check=True)
    subprocess.run([str(JDK / 'java.exe'), '-cp', folder + os.pathsep + cp, 'zombie.savefile.ServerDatabaseTest'], check=True)

source = (SRC / 'network/ServerWorldDatabase.java').read_text(encoding='utf-8')
assert source.count('ApocBRServerDatabase.open(this.conn)') == 52
assert 'if (ApocBRServerDatabase.enabled()) {' in source
assert 'return !ApocBRServerDatabase.enabled();' in source
print('Account method routing coverage passed')
