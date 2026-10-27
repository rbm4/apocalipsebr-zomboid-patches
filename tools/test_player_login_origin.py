"""Production login audit bridge/store/journal; only engine and JDBC transport are stubbed."""
from pathlib import Path
import subprocess, tempfile
ROOT = Path(__file__).resolve().parents[1]
STUBS = {
 'ApocBRPayloadProvenance': 'public static int id=3; public static int configuredServerId(){return id;}',
 'ApocBRPlayerRelationalMutations': 'static java.util.UUID character; static java.util.UUID loginCharacterId(zombie.characters.IsoPlayer p){return character;}',
 'ApocBRPersistence': '''static java.util.List<ApocBRSuccessfulLogin.Login> operations=new java.util.ArrayList<>();static boolean fail;
 static void submit(ApocBRPersistenceJournal.Operation op){if(fail)throw new IllegalStateException();operations.add((ApocBRSuccessfulLogin.Login)op);}''',
 'ApocBRPostgresConnector': 'static java.sql.Connection connection;static java.sql.Connection borrowPlayerConnection(){return connection;}',
}
HARNESS = r'''package zombie.savefile;
import zombie.characters.IsoPlayer;import zombie.network.GameServer;
import java.util.*;import java.sql.*;import java.lang.reflect.*;import java.nio.file.*;
public class LoginTest {
 static int checks; static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static IsoPlayer login(){IsoPlayer p=new IsoPlayer();ApocBRPlayerLogin.successfulLogin(p,"world","O'Connor",false,0,"O'Connor","76561198000000000");return p;}
 static ApocBRSuccessfulLogin.Login last(){return ApocBRPersistence.operations.getLast();}
 static int commits,rollbacks;static boolean failCommit,failQuery;static Map<Integer,Object> parameters=new HashMap<>();
 static Object proxy(Class<?> type,InvocationHandler h){return Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},h);}
 static Connection jdbc(){return (Connection)proxy(Connection.class,(p,m,a)->switch(m.getName()){
  case "prepareStatement" -> {check(a[0].equals(ApocBRSuccessfulLogin.SQL),"parameterized function call");yield proxy(PreparedStatement.class,(s,n,b)->{
   if(n.getName().startsWith("set")&&!n.getName().equals("setQueryTimeout")){parameters.put((Integer)b[0],b[1]);return null;}
   if(n.getName().equals("executeQuery")){if(failQuery)throw new SQLException("fixture","08006");return proxy(ResultSet.class,(r,k,c)->switch(k.getName()){case "next"->true;case "getInt"->2;default->null;});}
   return null;});}
  case "commit"->{commits++;if(failCommit)throw new SQLException("lost commit","08006");yield null;}
  case "rollback"->{rollbacks++;yield null;}default->null;});}
 public static void main(String[] args)throws Exception {
  System.setProperty("apocbr.postgres.enabled","true");
  check(!ApocBRPlayerLogin.isReady(new IsoPlayer()),"getter cannot fabricate successful login");
  check(ApocBRPersistence.operations.isEmpty(),"getters cannot record attempts");
  IsoPlayer foreign=login();var event=last();
  check(event.character()==null,"audit does not require character import/graph");
  check(!ApocBRPlayerLogin.isReady(foreign)&&!ApocBRPlayerLogin.hasChangedServer(foreign),"pending differs from ready unknown");
  ApocBRPlayerLogin.successfulLogin(foreign,"world","ignored",false,0,"ignored",null);
  check(ApocBRPersistence.operations.size()==1,"duplicate acceptance records once");
  ApocBRPostgresConnector.connection=jdbc();failCommit=true;
  try{ApocBRSuccessfulLogin.apply(event);throw new AssertionError("commit failure accepted");}catch(SQLException expected){}
  check(!ApocBRPlayerLogin.isReady(foreign)&&rollbacks==1,"publish only after commit");failCommit=false;
  Thread worker=new Thread(()->{try{ApocBRSuccessfulLogin.apply(event);}catch(Exception e){throw new AssertionError(e);}});worker.start();worker.join();
  check(ApocBRPlayerLogin.isReady(foreign)&&ApocBRPlayerLogin.getPreviousServerId(foreign)==2&&ApocBRPlayerLogin.hasChangedServer(foreign),"worker publishes foreign origin");
  check(parameters.get(1).equals(event.event())&&parameters.get(5).equals("username")&&parameters.get(6).equals("O'Connor")&&parameters.get(10)==null,"bind identity/event/null character safely");
  check(ApocBRPlayerLogin.getPreviousServerId(foreign)==2,"getter preserves origin");
  ApocBRPlayerRelationalMutations.character=UUID.randomUUID();IsoPlayer reconnect=login();
  check(last().character().equals(ApocBRPlayerRelationalMutations.character),"known persistent life enriches event");
  ApocBRPlayerLogin.recorded(last().event(),3);check(!ApocBRPlayerLogin.hasChangedServer(reconnect),"same-server reconnect stays");
  ApocBRPayloadProvenance.id=4;IsoPlayer next=login();ApocBRPlayerLogin.recorded(last().event(),3);
  check(ApocBRPlayerLogin.getPreviousServerId(next)==3&&ApocBRPlayerLogin.hasChangedServer(next),"C sees previous A");
  IsoPlayer first=login();ApocBRPlayerLogin.recorded(last().event(),0);
  check(ApocBRPlayerLogin.isReady(first)&&!ApocBRPlayerLogin.hasChangedServer(first),"first audited login is ready unknown");
  ApocBRPersistence.fail=true;check(!ApocBRPlayerLogin.isReady(login()),"queue failure never invents origin");ApocBRPersistence.fail=false;
  IsoPlayer offline=login();var durable=last();
  try(var journal=new ApocBRPersistenceJournal(Path.of(args[0]),4096,s->{})){journal.submit(durable);journal.flush();}
  try(var journal=new ApocBRPersistenceJournal(Path.of(args[0]),4096,s->{})){
   check(journal.head().operation().equals(durable),"crash replay preserves event UUID and detached audit");}
  check(!ApocBRPlayerLogin.teleportToLobby(foreign,Double.NaN,2,0),"finite coordinates required");
  foreign.connected=false;check(!ApocBRPlayerLogin.teleportToLobby(foreign,100.5,200.5,0),"connection required");
  foreign.connected=true;foreign.dead=true;check(!ApocBRPlayerLogin.teleportToLobby(foreign,100.5,200.5,0),"dead player rejected");foreign.dead=false;
  check(ApocBRPlayerLogin.teleportToLobby(foreign,100.5,200.5,0),"native teleport accepted");
  check(ApocBRPlayerLogin.teleportToLobby(foreign,100.5,200.5,0)&&GameServer.packets==1,"teleport once");
  Thread other=new Thread(()->check(!ApocBRPlayerLogin.isReady(foreign)&&!ApocBRPlayerLogin.hasChangedServer(foreign),"worker cannot read engine state"));other.start();other.join();
  System.setProperty("apocbr.postgres.loginHistory.enabled","false");check(ApocBRPlayerLogin.isReady(new IsoPlayer()),"disabled retains geometry fallback");
  Object session=new Object();
  IsoPlayer initial=new IsoPlayer();initial.connection=session;
  ApocBRPlayerLogin.successfulLogin(initial,"world","user",false,0,"user",null);
  check(!ApocBRPlayerLogin.isRespawn(initial),"first accepted character is a login");
  ApocBRPlayerLogin.successfulLogin(initial,"world","user",false,0,"user",null);
  check(!ApocBRPlayerLogin.isRespawn(initial),"duplicate acceptance cannot become a respawn");
  IsoPlayer respawn=new IsoPlayer();respawn.connection=session;
  ApocBRPlayerLogin.successfulLogin(respawn,"world","user",false,0,"user",null);
  check(ApocBRPlayerLogin.isRespawn(respawn),"replacement in same authenticated slot is respawn even with auditing disabled");
  IsoPlayer coop=new IsoPlayer();coop.connection=session;
  ApocBRPlayerLogin.successfulLogin(coop,"world","user",true,1,"user2",null);
  check(!ApocBRPlayerLogin.isRespawn(coop),"new co-op slot is independent");
  IsoPlayer reconnected=new IsoPlayer();
  ApocBRPlayerLogin.successfulLogin(reconnected,"world","user",false,0,"user",null);
  check(!ApocBRPlayerLogin.isRespawn(reconnected),"new connection is a login");
  check(!ApocBRPlayerLogin.isRespawn(new IsoPlayer()),"unknown character is not a respawn");
  Thread reader=new Thread(()->check(!ApocBRPlayerLogin.isRespawn(respawn),"worker cannot read respawn classification"));reader.start();reader.join();
  System.out.println("PASS: successful-login audit, journal replay, async readiness and native teleport; checks="+checks);
 }
}'''
with tempfile.TemporaryDirectory(prefix='apocbr_login_') as folder:
 target=Path(folder);sources=[]
 files={f'zombie/savefile/{name}.java': f'package zombie.savefile; public class {name} {{{body}}}' for name,body in STUBS.items()}
 files.update({
  'zombie/debug/DebugLog.java': 'package zombie.debug; public class DebugLog {public static void log(String s){}}',
  'zombie/characters/IsoPlayer.java': '''package zombie.characters; public class IsoPlayer {
   public Object connection=this; public boolean dead,connected=true;public boolean isDead(){return dead;}public Object getNetworkCharacterAI(){return this;} }''',
  'zombie/network/GameServer.java': '''package zombie.network; public class GameServer {
   public static boolean server=true;public static Thread mainThread=Thread.currentThread();public static int packets;
   public static Object getConnectionFromPlayer(zombie.characters.IsoPlayer p){return p.connected?p.connection:null;}
   public static void sendTeleport(zombie.characters.IsoPlayer p,float a,float b,float c){packets++;} }''',
  'LoginTest.java': HARNESS,
 })
 for name,body in files.items():
  p=target/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');sources.append(p)
 for name in ['ApocBRPlayerLogin','ApocBRSuccessfulLogin','ApocBRPersistenceJournal']:
  sources.append(ROOT/f'42.21.0/src/zombie/savefile/{name}.java')
 subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-d',folder,*map(str,sources)],check=True)
 subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',folder,'zombie.savefile.LoginTest',str(target/'journal')],check=True)
s=(ROOT/'42.21.0/src/zombie/network/GameServer.java').read_text(encoding='utf-8')
assert s.count('ApocBRPlayerLogin.successfulLogin(')==1
assert s.index('fully connected ') < s.index('ApocBRPlayerLogin.successfulLogin(')
assert 'ApocBR.LastConnectedServerId' not in (ROOT/'42.21.0/src/zombie/savefile/ApocBRPlayerLogin.java').read_text()
