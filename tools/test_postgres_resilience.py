"""Execute the real journal, coordinator, JDBC receipt adapter and foreground deadlines.
Only engine initialization and the JDBC transport are stubbed; no live server/database.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / '42.21.0/src/zombie'
GAME = Path(os.environ.get('APOCBR_TEST_GAME_JAR', r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
JDK = ROOT / 'jdk/bin'
LIB = ROOT / '42.21.0/lib'

HARNESS = r'''
package zombie.savefile;
import java.io.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;

public class ApocBRResilienceTest {
 static int checks;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
 static void until(java.util.function.BooleanSupplier test,String message)throws Exception {
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
  while(!test.getAsBoolean()){if(System.nanoTime()>deadline)throw new AssertionError(message);Thread.sleep(10);}
  checks++;
 }
 record Value(int value) implements ApocBRPersistenceJournal.Operation {
  public String kind(){return "test-v1";}public long estimatedBytes(){return 128;}public String playerKey(){return "identity-"+value;}
 }
 static void journal(Path root)throws Exception {
  Path directory=root.resolve("journal");List<UUID> ids=new ArrayList<>();
  try(var journal=new ApocBRPersistenceJournal(directory,256,s->{})) {
   for(int i=0;i<12;i++)ids.add(journal.submit(new Value(i)));
   check(journal.status().stagedBytes()<=256,"RAM staging stays bounded and overflow spills locally");
   check(journal.status().durable()>0,"overflow persisted without a database");journal.flush();
   check(journal.status().durable()==12,"all flushed records are durable");
   check(journal.pendingPlayer("identity-0")&&!journal.pendingPlayer("unrelated"),"pending-player index distinguishes unrelated identities");
   try{journal.acknowledge(ids.get(1));throw new AssertionError("out-of-order acknowledgement accepted");}catch(IOException expected){}
   journal.acknowledge(ids.get(0));
  }
  // Simulate a torn final append after a crash; complete earlier records must survive.
  Path log;try(var files=Files.list(directory)){log=files.filter(p->p.toString().endsWith(".log")).findFirst().orElseThrow();}
  Files.write(log,new byte[]{1,2,3,4,5},StandardOpenOption.APPEND);
  try(var journal=new ApocBRPersistenceJournal(directory,256,s->{})) {
   check(journal.status().durable()==11,"restart truncates only the incomplete tail");
   check(!journal.pendingPlayer("identity-0")&&journal.pendingPlayer("identity-1"),"restart rebuilds pending-player index excluding acknowledged work");
   for(int i=1;i<12;i++) {
    var entry=journal.head();check(entry.id().equals(ids.get(i))&&((Value)entry.operation()).value()==i,"restart preserves UUID, payload and FIFO");
    journal.acknowledge(entry.id());
   }
   check(journal.empty()&&journal.status().bytes()==0,"fully drained journal compacts");journal.submit(new Value(99));
  }
  try(var journal=new ApocBRPersistenceJournal(directory,256,s->{})) {
   check(((Value)journal.head().operation()).value()==99,"new generation survives restart after compaction");
   try{new ApocBRPersistenceJournal(directory,256,s->{});throw new AssertionError("two journal owners accepted");}
   catch(java.nio.channels.OverlappingFileLockException|IOException expected){}
  }
  Path corrupt=root.resolve("corrupt");
  try(var journal=new ApocBRPersistenceJournal(corrupt,256,s->{})){journal.submit(new Value(1));}
  try(var files=Files.list(corrupt)){log=files.filter(p->p.toString().endsWith(".log")).findFirst().orElseThrow();}
  try(var file=new RandomAccessFile(log.toFile(),"rw")){file.seek(15);file.write(127);}
  try{new ApocBRPersistenceJournal(corrupt,256,s->{});throw new AssertionError("corrupt complete record discarded");}catch(IOException expected){}
 }
 static Object zero(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
 static class Fake {
  volatile boolean offline,loseCommit,blockWrite,permanentFailure,missingReceipt;
  final Set<UUID> receipts=ConcurrentHashMap.newKeySet();
  final List<String> committed=new CopyOnWriteArrayList<>();
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  volatile int acquired,networkTimeout,mainAcquired;
  DataSource source(){return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(p,m,a)->{
   if(!m.getName().equals("getConnection"))return zero(m.getReturnType());
   acquired++;if(Thread.currentThread().getName().equals("main"))mainAcquired++;if(offline)throw new SQLTransientConnectionException("PRIVATE_URL","08001");
   return connection();
  });}
  Connection connection(){
   class Tx implements InvocationHandler {
    UUID receipt;final List<String> writes=new ArrayList<>();
    public Object invoke(Object p,Method m,Object[] a)throws Throwable {
     switch(m.getName()) {
      case "prepareStatement":return statement((String)a[0]);
      case "setNetworkTimeout":networkTimeout=(int)a[1];return null;
      case "getNetworkTimeout":return networkTimeout;
      case "rollback":receipt=null;writes.clear();return null;
      case "commit":
       if(offline)throw new SQLException("PRIVATE_SOCKET","08006");
       if(receipt!=null)receipts.add(receipt);committed.addAll(writes);receipt=null;writes.clear();
       if(loseCommit){loseCommit=false;offline=true;throw new SQLException("PRIVATE_COMMIT_ACK","08006");}return null;
     }
     return zero(m.getReturnType());
    }
    PreparedStatement statement(String sql){
     Map<Integer,Object> values=new HashMap<>();
     return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
      if(m.getName().startsWith("set")&&a!=null&&a.length>=2&&a[0] instanceof Integer){values.put((int)a[0],a[1]);return null;}
      if(m.getName().equals("executeQuery")) {
       if(offline)throw new SQLException("PRIVATE_QUERY","08006");
       if(missingReceipt&&sql.startsWith("SELECT operation_id"))throw new SQLException("PRIVATE_SCHEMA","42P01");
       return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(q,n,b)->zero(n.getReturnType()));
      }
      if(m.getName().equals("executeUpdate")) {
       if(offline)throw new SQLException("PRIVATE_WRITE","08006");
       if(sql.startsWith("INSERT INTO public.apocbr_persistence_receipt")) {
        UUID id=(UUID)values.get(1);if(receipts.contains(id))return 0;receipt=id;return 1;
       }
       if(permanentFailure&&"constraint".equals(values.get(1)))throw new SQLException("PRIVATE_ROW","23505");
       if(blockWrite){entered.countDown();release.await();}
       writes.add(sql.startsWith("INSERT INTO public.networkplayers")?"native:"+((byte[])values.get(12))[0]:String.valueOf(values.get(1)));return 1;
      }
      return zero(m.getReturnType());
     });
    }
   }
   return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},new Tx());
  }
 }
 static ApocBRPersistenceOperations.Account write(String value){return new ApocBRPersistenceOperations.Account(List.of(new ApocBRPersistenceOperations.Sql("UPDATE whitelist SET displayname=? WHERE username='fixture'",List.of(value))));}
 static void runtime(Path root)throws Exception {
  Fake fake=new Fake();fake.offline=true;
  Field singleton=ApocBRPostgresConnector.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,new ApocBRPostgresConnector(fake.source(),s->{}));
  Field configured=ApocBRPostgresConnector.class.getDeclaredField("configured");configured.setAccessible(true);configured.setBoolean(null,true);
  System.setProperty("apocbr.postgres.enabled","true");System.setProperty("apocbr.postgres.journalDirectory",root.resolve("runtime").toString());
  // Seed a previous-process durable operation before startup.
  UUID first;
  try(var journal=new ApocBRPersistenceJournal(root.resolve("runtime"),256,s->{})){first=journal.submit(write("startup"));}
  Thread start=new Thread(ApocBRPostgresConnector::prepareStartup);start.start();
  until(()->fake.acquired>0,"startup probes database");
  check(start.isAlive()&&!ApocBRPostgresConnector.acceptingLogins(),"startup outage keeps admission closed");
  fake.offline=false;start.join(15000);check(!start.isAlive(),"startup finishes after database recovery");
  check(fake.committed.equals(List.of("startup"))&&fake.receipts.contains(first),"startup replay precedes admission");
  until(ApocBRPostgresConnector::acceptingLogins,"startup opens admission after replay");
  fake.offline=true;ApocBRPersistence.failed(new SQLException("secret","08006"));
  int before=fake.mainAcquired;
  final int[] disconnected={0};
  var connection=(zombie.network.IConnection)Proxy.newProxyInstance(ApocBRResilienceTest.class.getClassLoader(),new Class[]{zombie.network.IConnection.class},(p,m,a)->{if(m.getName().equals("forceDisconnect"))disconnected[0]++;return zero(m.getReturnType());});
  check(!ApocBRLoginGuard.ready(connection)&&disconnected[0]==1&&zombie.network.packets.INetworkPacket.Counter.denials==1,"unavailable login explicitly sends denial and disconnects");
  try{ApocBRPostgresConnector.borrowServerConnection();throw new AssertionError("foreground lease during outage");}catch(SQLException expected){}
  check(fake.mainAcquired==before,"outage gate does not attempt foreground acquisition");
  check(ApocBRServerDatabase.queue("UPDATE whitelist SET displayname=?","outage"),"independent account write queues without a lease");
  UUID uncertain=ApocBRPersistence.submit(write("commit-ack-lost"));
  ApocBRPersistence.submit(write("last"));check(!ApocBRPostgresConnector.acceptingLogins(),"pending outage work denies login");
  fake.loseCommit=true;fake.offline=false;
  until(()->fake.receipts.size()>=2&&fake.offline,"simulate lost commit acknowledgement");
  fake.offline=false;
  until(()->fake.committed.size()==4&&ApocBRPostgresConnector.acceptingLogins(),"recovery drains backlog before login");
  check(fake.committed.equals(List.of("startup","outage","commit-ack-lost","last")),"FIFO and receipt deduplication preserve every operation exactly once");
  check(fake.receipts.contains(uncertain),"receipt committed with operation");
  // Replay a known UUID after a simulated crash before local acknowledgement.
  Field operation=ApocBRPersistence.class.getDeclaredField("operation");operation.setAccessible(true);
  @SuppressWarnings("unchecked") ThreadLocal<UUID> context=(ThreadLocal<UUID>)operation.get(null);
  context.set(uncertain);ApocBRPersistence.background(true);
  try{ApocBRPersistenceOperations.apply(write("must-not-repeat"));}finally{context.remove();ApocBRPersistence.background(false);}
  check(fake.committed.size()==4,"already committed UUID cannot mutate twice");
  fake.offline=true;ApocBRPersistence.failed(new SQLException("secret","08006"));before=fake.mainAcquired;
  byte[] payload={7};
  ApocBRPostgresConnector.enqueuePlayerSnapshot(new ApocBRPostgresConnector.Snapshot("world","fixture",null,0,"player",1,2,0,249,false,"fixture",false,0,payload));
  payload[0]=99;
  check(fake.mainAcquired==before,"native save submission performs no JDBC on game thread");
  fake.offline=false;until(()->fake.committed.size()==5&&ApocBRPostgresConnector.acceptingLogins(),"native snapshot recovers through journal worker");
  check(fake.committed.getLast().equals("native:7"),"native journal adapter retains immutable bytes");
  fake.blockWrite=true;ApocBRPersistence.submit(write("blocked-worker"));
  check(fake.entered.await(5,TimeUnit.SECONDS),"replay holds an in-flight JDBC operation");before=fake.mainAcquired;
  ApocBRPostgresConnector.enqueuePlayerSnapshot(new ApocBRPostgresConnector.Snapshot("world","fixture",null,0,"player",1,2,0,249,false,"fixture",false,0,new byte[]{8}));
  check(ApocBRPostgresConnector.acceptingLogins()&&!ApocBRPostgresConnector.playerReadable("world","fixture",0,false)
      &&ApocBRPostgresConnector.playerReadable("world","other",0,false),"healthy backlog blocks only the affected player while unrelated login remains available");
  for(int i=0;i<300;i++)ApocBRPersistence.submit(write("overflow-"+i));
  var status=ApocBRPostgresConnector.persistenceStatus();
  check(status.staged()<=256&&status.durable()>0&&status.inFlight(),"journal writer and RAM overflow progress while replay is blocked in JDBC");
  check(fake.mainAcquired==before,"backlog producers never acquire JDBC connections");
  fake.blockWrite=false;fake.release.countDown();
  until(()->fake.committed.size()==307&&ApocBRPostgresConnector.acceptingLogins(),"blocked worker resumes and drains every overflow operation");
  for(int i=0;i<300;i++)check(fake.committed.get(i+7).equals("overflow-"+i),"overflow preserves FIFO");
  fake.permanentFailure=true;ApocBRPersistence.submit(write("constraint"));ApocBRPersistence.submit(write("after-constraint"));
  until(()->ApocBRPersistence.state().equals("RECOVERING"),"non-connectivity SQL failure remains distinguishable from outage");
  check(fake.committed.size()==307&&!ApocBRPostgresConnector.acceptingLogins(),"permanent failure retains head and later writes without false acknowledgement");
  fake.permanentFailure=false;
  until(()->fake.committed.size()==309&&ApocBRPostgresConnector.acceptingLogins(),"correcting permanent failure allows retained FIFO replay");
  fake.offline=true;ApocBRPersistence.failed(new SQLException("secret","08006"));ApocBRPersistence.submit(write("shutdown"));
  final int[] shutdownAttempts={0};
  Thread stop=new Thread(()->{ApocBRPersistence.shutdownWork(()->{if(++shutdownAttempts[0]==1)throw new RuntimeException(new SQLException("PRIVATE_ROLE_SAVE","08006"));});ApocBRPersistence.close();});stop.start();
  until(()->ApocBRPersistence.state().equals("DRAINING"),"shutdown enters draining state");
  check(stop.isAlive(),"shutdown waits during outage");fake.offline=false;stop.join(15000);
  check(!stop.isAlive()&&fake.committed.getLast().equals("shutdown")&&ApocBRPersistence.state().equals("CLOSED"),"shutdown drains before closing");
  check(shutdownAttempts[0]==2,"connection loss during synchronous shutdown work retries before workers/pool close");
 }
 static void deadlines()throws Exception {
  Fake fake=new Fake();
  try(var budget=ApocBRForegroundDatabase.begin();Connection c=ApocBRForegroundDatabase.wrap(fake.connection())) {
   c.prepareStatement("SELECT 1").close();check(fake.networkTimeout>0&&fake.networkTimeout<=250,"foreground socket work bounded to remaining budget");
   Field deadline=ApocBRForegroundDatabase.class.getDeclaredField("deadline");deadline.setAccessible(true);
   @SuppressWarnings("unchecked") ThreadLocal<Long> time=(ThreadLocal<Long>)deadline.get(null);
   time.set(System.nanoTime()-1);
   // A new nested scope retains the expired workflow deadline rather than resetting it.
   try(var nested=ApocBRForegroundDatabase.begin();Connection expired=ApocBRForegroundDatabase.wrap(fake.connection())) {
    try{expired.prepareStatement("SELECT 2");throw new AssertionError("nested call reset expired budget");}catch(SQLException expected){}
   }
  }
 }
 static void missingReceipt(Path root)throws Exception {
  Fake fake=new Fake();fake.missingReceipt=true;
  Field singleton=ApocBRPostgresConnector.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,new ApocBRPostgresConnector(fake.source(),s->{}));
  Field configured=ApocBRPostgresConnector.class.getDeclaredField("configured");configured.setAccessible(true);configured.setBoolean(null,true);
  System.setProperty("apocbr.postgres.enabled","true");System.setProperty("apocbr.postgres.journalDirectory",root.toString());
  try(var journal=new ApocBRPersistenceJournal(root,256,s->{})){journal.submit(write("waiting-for-migration"));}
  try{ApocBRPostgresConnector.prepareStartup();throw new AssertionError("missing receipt migration accepted");}
  catch(IllegalStateException expected){check(!expected.toString().contains("PRIVATE_"),"startup schema failure is sanitized");}
  check(fake.committed.isEmpty()&&!ApocBRPostgresConnector.acceptingLogins(),"missing migration cannot discard startup backlog or open admission");
  fake.missingReceipt=false;ApocBRPostgresConnector.prepareStartup();ApocBRPostgresConnector.finishStartup();
  check(fake.committed.equals(List.of("waiting-for-migration")),"repairing migration replays retained startup work");ApocBRPersistence.close();
 }
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);if(args.length>1){missingReceipt(root);System.out.println("Missing receipt checks="+checks);return;}
  journal(root);deadlines();runtime(root);
  System.out.println("Resilience checks="+checks);
 }
}
'''

with tempfile.TemporaryDirectory(prefix='apocbr_resilience_') as folder:
    target = Path(folder)
    files = []
    stubs = {
        'zombie/debug/DebugLog.java': 'package zombie.debug; public class DebugLog {public static void log(String text){System.out.println(text);}}',
        'zombie/network/GameServer.java': 'package zombie.network; public class GameServer {public static boolean server=true,coop;public static String serverName="fixture";}',
        'zombie/ZomboidFileSystem.java': 'package zombie; public class ZomboidFileSystem {public static final ZomboidFileSystem instance=new ZomboidFileSystem();public String getSaveDir(){return "unused";}public String getCurrentSaveDir(){return "fixture";}}',
    }
    stubs['zombie/network/PacketTypes.java'] = 'package zombie.network; public class PacketTypes {public enum PacketType {AccessDenied;}}'
    stubs['zombie/network/packets/INetworkPacket.java'] = 'package zombie.network.packets; public interface INetworkPacket {static void send(zombie.network.IConnection c,zombie.network.PacketTypes.PacketType type,Object...args){Counter.denials++;} class Counter {public static int denials;}}'
    for name, content in stubs.items():
        path = target / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        files.append(path)
    harness = target / 'ApocBRResilienceTest.java'
    harness.write_text(HARNESS, encoding='utf-8')
    files += list((SRC / 'savefile').glob('ApocBR*.java'))
    files += [SRC / 'world/ApocBRWorldDictionarySnapshot.java', SRC / 'world/ApocBRWorldDictionaryDecode.java', harness]
    cp = os.pathsep.join(map(str, [GAME, LIB / 'postgresql-42.7.14.jar', LIB / 'HikariCP-7.1.0.jar']))
    subprocess.run([str(JDK / 'javac.exe'), '--release', '25', '-cp', cp, '-d', folder, *map(str, files)], check=True)
    subprocess.run([str(JDK / 'java.exe'), '-cp', folder + os.pathsep + cp, 'zombie.savefile.ApocBRResilienceTest', str(target / 'data')], check=True, timeout=90)
    subprocess.run([str(JDK / 'java.exe'), '-cp', folder + os.pathsep + cp, 'zombie.savefile.ApocBRResilienceTest', str(target / 'missing'), 'missing'], check=True, timeout=30)
