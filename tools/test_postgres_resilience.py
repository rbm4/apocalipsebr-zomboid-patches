"""Execute the real journal, coordinator, JDBC receipt adapter and foreground deadlines.
Only engine initialization and the JDBC transport are stubbed; no live server/database.
"""
from pathlib import Path
import os
import ast
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
 record PlayerValue(String playerKey,boolean completePlayerState,String state) implements ApocBRPersistenceJournal.Operation {
  public String kind(){return "fixture-player-v1";}public long estimatedBytes(){return 128;}
 }
 static void quarantine(Path root)throws Exception {
  Path directory=root.resolve("isolation");UUID rejected;
  try(var journal=new ApocBRPersistenceJournal(directory,256,s->{})) {
   rejected=journal.submit(new PlayerValue("blocked",true,"bad-capture"));journal.submit(new Value(1));journal.flush();
   // Simulate failure after archive/marker fsync but before the active checkpoint advances.
   Files.createDirectory(directory.resolve("checkpoint.tmp"));
   try{journal.quarantine(rejected);throw new AssertionError("checkpoint failure ignored");}catch(IOException expected){}
   check(journal.head().id().equals(rejected)&&journal.quarantined(rejected),"checkpoint failure keeps original head plus durable rejected payload");
   Files.delete(directory.resolve("checkpoint.tmp"));
  }
  try(var journal=new ApocBRPersistenceJournal(directory,256,s->{})) {
   check(journal.head().id().equals(rejected)&&journal.pendingPlayer("blocked"),"restart recovers rejected head and player reload marker");
   journal.quarantine(rejected);
   check(((Value)journal.head().operation()).value()==1,"idempotent quarantine completes acknowledgement and exposes next operation");
   journal.acknowledgeCommitted(journal.head().id());
   check(journal.empty()&&journal.pendingPlayer("blocked")&&!journal.pendingPlayer("unrelated"),"quarantined player does not obstruct active queue or unrelated identities");
   journal.submit(new PlayerValue("blocked",false,"scalar-only"));journal.flush();journal.acknowledgeCommitted(journal.head().id());
   check(journal.pendingPlayer("blocked"),"scalar-only commit cannot authorize loading an older incomplete graph");
  }
  try(var journal=new ApocBRPersistenceJournal(directory,256,s->{})) {
   check(journal.pendingPlayer("blocked"),"reload protection persists even after active journal compaction/restart");
   journal.submit(new PlayerValue("blocked",true,"new-full-capture"));journal.flush();journal.acknowledgeCommitted(journal.head().id());
   check(!journal.pendingPlayer("blocked"),"newer committed full capture resolves only the affected reload protection");
   check(journal.quarantined(rejected),"superseded rejected payload is retained for diagnosis and never re-executed automatically");
  }
  try(var journal=new ApocBRPersistenceJournal(directory,256,s->{})){check(journal.empty()&&!journal.pendingPlayer("blocked"),"resolved reload protection survives restart");}
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
  // The newest player capture carries identity independently of native-ID aliases and old captures.
  Path playerJournal=root.resolve("player-identity");P player=new P();Item item=new Item(7);player.inventory.items.add(item);
  UUID character=UUID.randomUUID(),identity=UUID.randomUUID();var tracker=new ApocBRPlayerRelationalSnapshot.ItemTracker(player);
  ApocBRPlayerRelationalSnapshot.copyLive(player,tracker,character,Map.of(7,identity));item.id=70;
  var snapshot=ApocBRPlayerRelationalSnapshot.copyLive(player,tracker,character,Map.of());
  var candidate=new ApocBRPlayerRelationalDatabase.Candidate(UUID.randomUUID(),"fixture","world","identity",false,0,0,249,null,List.of());
  var batch=new ApocBRPlayerRelationalMutations.Batch(candidate,UUID.randomUUID(),character,Map.of(),java.time.Instant.now(),snapshot,1);
  try(var journal=new ApocBRPersistenceJournal(playerJournal,256,s->{})){journal.submit(new ApocBRPersistenceOperations.Live(batch));journal.flush();}
  try(var journal=new ApocBRPersistenceJournal(playerJournal,256,s->{})) {
   var replay=(ApocBRPersistenceOperations.Live)journal.head().operation();
   var graph=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(replay.batch().snapshot(),List.of());
   check(graph.rows().stream().anyMatch(r->r.table().equals("item")&&identity.equals(r.values().get("id"))&&Integer.valueOf(70).equals(r.values().get("native_id"))),"restart replay retains owned item UUID across a native ID change without runtime tracker or aliases");
   journal.acknowledgeCommitted(journal.head().id());check(journal.empty(),"replayed identity operation drains normally");
  }
 }
 static Object zero(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
 static class Fake {
  volatile boolean offline,loseCommit,blockWrite,permanentFailure,missingReceipt,missingSales;
  final Set<UUID> receipts=ConcurrentHashMap.newKeySet();
  final List<String> committed=new CopyOnWriteArrayList<>();
  final Map<String,Integer> writeAttempts=new ConcurrentHashMap<>();
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  final List<String> sqlWrites=new CopyOnWriteArrayList<>();
  final List<String> sqlQueries=new CopyOnWriteArrayList<>();
  final List<Map<Integer,Object>> sqlParameters=new CopyOnWriteArrayList<>();
  volatile int acquired,networkTimeout,mainAcquired,minQueryMillis;
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
       sqlQueries.add(sql);
       if(sql.contains("apocbr_human_sale")&&(missingSales||!sql.contains("FROM player_rel.apocbr_human_sale")))throw new SQLException("PRIVATE_SALES_SCHEMA","42P01");
       if(offline)throw new SQLException("PRIVATE_QUERY","08006");
       if(minQueryMillis>0&&networkTimeout<minQueryMillis)throw new SQLException("PRIVATE_NETWORK_BUDGET","08006",new java.net.SocketTimeoutException("PRIVATE_TIMEOUT"));
       if(missingReceipt&&sql.startsWith("SELECT operation_id"))throw new SQLException("PRIVATE_SCHEMA","42P01");
       return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(q,n,b)->zero(n.getReturnType()));
      }
      if(m.getName().equals("executeUpdate")) {
       if(sql.contains("apocbr_human_sale")&&!sql.startsWith("INSERT INTO player_rel.apocbr_human_sale"))throw new SQLException("PRIVATE_SALES_SCHEMA","42P01");
       if(offline)throw new SQLException("PRIVATE_WRITE","08006");
       if(sql.startsWith("INSERT INTO public.apocbr_persistence_receipt")) {
        UUID id=(UUID)values.get(1);if(receipts.contains(id))return 0;receipt=id;return 1;
       }
       sqlWrites.add(sql);sqlParameters.add(new HashMap<>(values));
       String value=String.valueOf(values.get(1));
       int attempt=writeAttempts.merge(value,1,Integer::sum);
       if(value.equals("incomplete-live"))ApocBRPlayerRelationalCapture.requireComplete(
        new ApocBRPlayerRelationalCapture.Graph(UUID.randomUUID(),UUID.randomUUID(),java.time.Instant.EPOCH,List.of(),List.of("UNSUPPORTED_ITEM_SUBTYPE","PRIVATE_VALUE token=secret"),0,0,0,0,0),"live_fields");
       if(permanentFailure&&value.startsWith("constraint"))throw new SQLException("PRIVATE_ROW","23505");
       if(value.equals("transient-four")&&attempt<=4)throw new SQLException("PRIVATE_TRANSIENT","22000");
       if(value.equals("runtime-failure"))throw new IllegalStateException("PRIVATE_RUNTIME");
       if(value.equals("runtime-retry-four")&&attempt<=4)throw new SQLException("PRIVATE_LOCK","55P03");
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

 static void humanSales(Path root)throws Exception {
  List<Integer> ids=new ArrayList<>(List.of(17,23));UUID saleId=UUID.randomUUID();
  var sale=new ApocBRHumanSales.Sale(saleId,1,"world","O'Connor","76561198000000000",2,"guide",123456789L,ids);ids.clear();
  check(sale.itemIds().equals(List.of(17,23)),"sale takes detached immutable offered-money IDs");
  check(sale.quantity()==2&&sale.bundleItemIds().isEmpty(),"legacy note-only constructor retains its quantity");
  check(new ApocBRHumanSales.Sale(UUID.randomUUID(),1,"w","u",null,0,"guide",0,List.of(1),null).quantity()==1,"missing old journal bundle component defaults to empty");
  try{new ApocBRHumanSales.Sale(UUID.randomUUID(),1,"w","u",null,0,"guide",0,List.of(1),List.of(2));throw new AssertionError("foreign bundle ID accepted");}catch(IllegalArgumentException expected){checks++;}
  try{new ApocBRHumanSales.Sale(UUID.randomUUID(),1,"w","u",null,0,"guide",0,List.of(1),List.of(1,1));throw new AssertionError("duplicate bundle ID accepted");}catch(IllegalArgumentException expected){checks++;}
  var bundleIds=new ArrayList<>(List.of(17));
  var mixedSale=new ApocBRHumanSales.Sale(UUID.randomUUID(),1,"world","O'Connor",null,0,"guide",0,List.of(17,23),bundleIds);bundleIds.clear();
  check(mixedSale.quantity()==101&&mixedSale.bundleItemIds().equals(List.of(17)),"mixed sale freezes bundle IDs and values each at 100");
  var maximumIds=java.util.stream.IntStream.range(0,500).boxed().toList();
  check(new ApocBRHumanSales.Sale(UUID.randomUUID(),1,"w","u",null,0,"guide",0,maximumIds,maximumIds).quantity()==50000,"500-item limit supports 500 bundles");
  try(var journal=new ApocBRPersistenceJournal(root.resolve("bundle-sales"),65536,s->{})) {
   journal.submitDurable(mixedSale);
   check(journal.head().operation().equals(mixedSale),"bundle quantities and source IDs survive durable journal serialization");
  }
  try{new ApocBRHumanSales.Sale(UUID.randomUUID(),1,"w","u",null,0,"guide",0,List.of(1,1));throw new AssertionError("duplicate source items accepted");}catch(IllegalArgumentException expected){checks++;}
  try{new ApocBRHumanSales.Sale(UUID.randomUUID(),1,"w","u",null,0,"guide",0,List.of());throw new AssertionError("empty money sale accepted");}catch(IllegalArgumentException expected){checks++;}
  Path path=root.resolve("human-sales");UUID operation;
  try(var journal=new ApocBRPersistenceJournal(path,65536,s->{})) {
   operation=journal.submitDurable(sale);
   check(journal.status().staged()==0&&journal.status().durable()==1,"sale fsynced before caller consumes inventory");
   check(journal.head().operation().equals(sale),"sale record round trips through actual journal filter");
  }
  try(var journal=new ApocBRPersistenceJournal(path,65536,s->{})) {
   check(journal.head().id().equals(operation)&&journal.head().operation().equals(sale),"sale UUID and payload survive restart before SQL");
   journal.quarantine(operation);check(journal.empty()&&journal.quarantined(operation),"failed sale retained for recovery without blocking worker");
  }
  try(var journal=new ApocBRPersistenceJournal(root.resolve("failed-sale-fsync"),65536,s->{})) {
   Field file=ApocBRPersistenceJournal.class.getDeclaredField("file");file.setAccessible(true);((RandomAccessFile)file.get(journal)).close();
   try{journal.submitDurable(sale);throw new AssertionError("closed disk accepted sale");}catch(IOException expected){checks++;}
   check(journal.status().staged()==0&&journal.status().durable()==0,"failed fsync leaves no deferred sale for unchanged inventory");
  }
  Fake fake=new Fake();
  System.setProperty("apocbr.guide.enabled","true");
  System.setProperty("apocbr.guide.trade.enabled","false");
  ApocBRHumanSales.validate(fake.connection());
  check(fake.sqlQueries.isEmpty(),"disabled guide trades do not require V10");
  System.setProperty("apocbr.guide.trade.enabled","true");
  ApocBRHumanSales.validate(fake.connection());
  check(fake.sqlQueries.size()==1&&fake.sqlQueries.getFirst().contains("FROM player_rel.apocbr_human_sale WHERE false"),"startup validates the manager V10 table in player_rel without reading sales");
  fake.missingSales=true;
  try{ApocBRHumanSales.validate(fake.connection());throw new AssertionError("missing V10 accepted");}
  catch(SQLException expected){check("42P01".equals(expected.getSQLState()),"missing V10 still fails startup validation");}
  fake.missingSales=false;
  Field singleton=ApocBRPostgresConnector.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,new ApocBRPostgresConnector(fake.source(),s->{}));
  Field configured=ApocBRPostgresConnector.class.getDeclaredField("configured");configured.setAccessible(true);configured.setBoolean(null,true);
  Field field=ApocBRPersistence.class.getDeclaredField("operation");field.setAccessible(true);
  @SuppressWarnings("unchecked") ThreadLocal<UUID> context=(ThreadLocal<UUID>)field.get(null);
  context.set(operation);ApocBRPersistence.background(true);
  try{ApocBRPersistenceOperations.apply(sale);ApocBRPersistenceOperations.apply(sale);}finally{context.remove();ApocBRPersistence.background(false);}
  check(fake.committed.equals(List.of(saleId.toString()))&&fake.receipts.contains(operation),"sale INSERT and replay receipt share transaction; retry does not execute twice");
  String sql=fake.sqlWrites.getFirst();var parameters=fake.sqlParameters.getFirst();
  check(sql.startsWith("INSERT INTO player_rel.apocbr_human_sale"),"durable sale replay writes manager V10 table rather than a public shadow table");
  check(sql.contains("ON CONFLICT (sale_id) DO NOTHING")&&sql.contains("'Base.Money'")&&!sql.contains("O'Connor"),"money type fixed; account text stays in bound parameters");
  check(parameters.get(4).equals("O'Connor")&&parameters.get(9).equals(2)&&parameters.get(10).equals("[17, 23]"),"receipt SQL contains money quantity and JSON item IDs");
  context.set(UUID.randomUUID());ApocBRPersistence.background(true);
  try{ApocBRPersistenceOperations.apply(mixedSale);}finally{context.remove();ApocBRPersistence.background(false);}
  var mixedParameters=fake.sqlParameters.getLast();
  check(mixedParameters.get(9).equals(101)&&mixedParameters.get(10).equals("[17, 23]")&&mixedParameters.get(11).equals("[17]"),"SQL persists normalized value alongside physical currency and bundle IDs");
 }
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
  check(fake.sqlQueries.stream().anyMatch(sql->sql.contains("FROM player_rel.apocbr_human_sale WHERE false")),"full startup admits guide with V10 table available only in player_rel");
  until(ApocBRPostgresConnector::acceptingLogins,"startup opens admission after replay");
  try(var budget=ApocBRForegroundDatabase.begin()) {
   Field deadline=ApocBRForegroundDatabase.class.getDeclaredField("deadline");deadline.setAccessible(true);
   @SuppressWarnings("unchecked") ThreadLocal<Long> time=(ThreadLocal<Long>)deadline.get(null);time.set(System.nanoTime()-1);
   try(Connection expired=ApocBRForegroundDatabase.wrap(fake.connection())) {
    try{expired.prepareStatement("SELECT 1");throw new AssertionError("expired request continued");}
    catch(SQLTimeoutException expected){check("HYT00".equals(expected.getSQLState()),"request deadline is distinct from connection failure");}
   }
   check(ApocBRPostgresConnector.acceptingLogins()&&ApocBRPersistence.state().equals("AVAILABLE"),"expired login does not close admission for everyone");
   final int[] denied={0};
   var expiredClient=(zombie.network.IConnection)Proxy.newProxyInstance(ApocBRResilienceTest.class.getClassLoader(),new Class[]{zombie.network.IConnection.class},(p,m,a)->{if(m.getName().equals("forceDisconnect"))denied[0]++;return zero(m.getReturnType());});
   check(!ApocBRLoginGuard.continueRequest(expiredClient)&&denied[0]==1,"expired request cannot continue after native code catches its SQL error");
   zombie.network.packets.INetworkPacket.Counter.denials=0;
  }
  try(var next=ApocBRServerDatabase.open(null)){next.connection().prepareStatement("SELECT 1").close();}
  check(ApocBRPostgresConnector.acceptingLogins(),"next independent account request can use the healthy database");
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
  fake.permanentFailure=true;UUID constraint=ApocBRPersistence.submit(write("constraint"));ApocBRPersistence.submit(write("after-constraint"));
  until(()->fake.committed.size()==308&&ApocBRPostgresConnector.acceptingLogins(),"rejected row no longer closes global admission or stalls unrelated account sync");
  check(!fake.receipts.contains(constraint)&&fake.writeAttempts.get("constraint")==1,"data rejection quarantines without a success receipt or pointless retries");
  check(Files.isRegularFile(root.resolve("runtime/quarantine/"+constraint+".record")),"rejected account operation remains durable for inspection");
  fake.permanentFailure=false;
  var candidate=new ApocBRPlayerRelationalDatabase.Candidate(UUID.randomUUID(),"fixture","world","blocked",false,0,0,249,null,List.of("UNSUPPORTED_TEST_STATE"));
  var snapshot=ApocBRPlayerRelationalSnapshot.copy(new P());
  var badBatch=new ApocBRPlayerRelationalMutations.Batch(candidate,UUID.randomUUID(),UUID.randomUUID(),Map.of(),java.time.Instant.EPOCH,snapshot,1);
  UUID invalidPlayer=ApocBRPersistence.submit(new ApocBRPersistenceOperations.Live(badBatch));
  ApocBRPersistence.submit(write("after-invalid-player"));
  ApocBRPostgresConnector.enqueuePlayerSnapshot(new ApocBRPostgresConnector.Snapshot("world","blocked",null,0,"player",1,2,0,249,false,"fixture",false,0,new byte[]{9}));
  until(()->fake.committed.size()==310&&ApocBRPostgresConnector.acceptingLogins(),"actual incomplete live graph cannot block following SQL or native player saves");
  check(Files.isRegularFile(root.resolve("runtime/quarantine/"+invalidPlayer+".record"))&&!fake.receipts.contains(invalidPlayer),"real SQLState 22000 graph is retained without being reported committed");
  check(!ApocBRPostgresConnector.playerReadable("world","blocked",0,false)&&ApocBRPostgresConnector.playerReadable("world","other",0,false),"only affected identity stays protected against stale relational reload; a legacy blob does not clear that guard");
  UUID runtimeFailure=ApocBRPersistence.submit(write("runtime-failure"));ApocBRPersistence.submit(write("runtime-retry-four"));
  until(()->fake.committed.size()==311&&ApocBRPostgresConnector.acceptingLogins(),"local operation failure is bounded and retryable lock failure may succeed on fifth try");
  check(fake.writeAttempts.get("runtime-failure")==5&&Files.isRegularFile(root.resolve("runtime/quarantine/"+runtimeFailure+".record")),"local operation failure gets five attempts then durable isolation");
  check(fake.writeAttempts.get("runtime-retry-four")==5,"runtime retry preserves fifth-attempt success");
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
  check(!ApocBRPersistence.background(),"fixture begins outside a background scope");
  try{ApocBRPostgresConnector.runBackgroundDatabaseWork(()->{
   check(ApocBRPersistence.background(),"dedicated database worker bypasses foreground budgets and admission");
   ApocBRPostgresConnector.runBackgroundDatabaseWork(()->check(ApocBRPersistence.background(),"nested worker retains background context"));
   check(ApocBRPersistence.background(),"nested scope restores background context");throw new IllegalStateException("fixture failure");
  });throw new AssertionError("worker failure swallowed");}catch(IllegalStateException expected){}
  check(!ApocBRPersistence.background(),"worker context is restored after an exception");
  Field deadline=ApocBRForegroundDatabase.class.getDeclaredField("deadline");deadline.setAccessible(true);
  @SuppressWarnings("unchecked") ThreadLocal<Long> time=(ThreadLocal<Long>)deadline.get(null);
  Method connectivity=ApocBRPersistence.class.getDeclaredMethod("connectionFailure",Throwable.class);connectivity.setAccessible(true);
  try(var login=ApocBRForegroundDatabase.beginLogin()) {
   long original=time.get();check(original-System.nanoTime()>TimeUnit.SECONDS.toNanos(4),"login has a five-second workflow budget");
   try(var account=ApocBRForegroundDatabase.begin()){check(time.get().equals(original),"nested account calls inherit login budget");}
   // Represent 600 ms spent on prior login work without waiting in the fixture.
   time.set(original-TimeUnit.MILLISECONDS.toNanos(600));
   try(Connection c=ApocBRForegroundDatabase.wrap(fake.connection())){c.prepareStatement("SELECT 1").close();}
   check(fake.networkTimeout==3000,"login socket budget permits graph queries beyond 250 ms");
   check(ApocBRForegroundDatabase.acquisitionMillis()<=250,"login pool acquisition still fails quickly under contention");
   fake.minQueryMillis=350;
   try(var nested=ApocBRForegroundDatabase.begin();Connection c=ApocBRForegroundDatabase.wrap(fake.connection());
       PreparedStatement q=c.prepareStatement("SELECT slow_fixture");ResultSet rows=q.executeQuery()) {
    rows.next();check(fake.networkTimeout==3000,"statement and result-set wrappers inherit login tolerance through nested account scope");
   }
  }
  check(time.get()==null,"login scope clears its deadline");
  try(Connection c=ApocBRForegroundDatabase.wrap(fake.connection());PreparedStatement q=c.prepareStatement("SELECT slow_fixture")) {
   try{q.executeQuery();throw new AssertionError("ordinary foreground budget widened");}
   catch(SQLException expected){check(expected.getSQLState().equals("08006")&&fake.networkTimeout<=250,"non-login 350 ms query remains outside the short socket budget");}
  }
  fake.minQueryMillis=0;
  System.setProperty("apocbr.postgres.loginWorkflowTimeoutMillis","9000");
  try(var login=ApocBRForegroundDatabase.beginLogin()){check(time.get()-System.nanoTime()>TimeUnit.SECONDS.toNanos(8),"login workflow budget is configurable");}
  finally{System.clearProperty("apocbr.postgres.loginWorkflowTimeoutMillis");}
  System.setProperty("apocbr.postgres.loginNetworkTimeoutMillis","1200");
  try(var login=ApocBRForegroundDatabase.beginLogin();Connection c=ApocBRForegroundDatabase.wrap(fake.connection())) {
   c.prepareStatement("SELECT 1").close();check(fake.networkTimeout==1200,"login network cap is independently configurable");
  }finally{System.clearProperty("apocbr.postgres.loginNetworkTimeoutMillis");}
  System.setProperty("apocbr.postgres.loginNetworkTimeoutMillis","-1");
  try(var login=ApocBRForegroundDatabase.beginLogin();Connection c=ApocBRForegroundDatabase.wrap(fake.connection())) {
   c.prepareStatement("SELECT 1").close();check(fake.networkTimeout==250,"login network cap has a safe lower bound");
  }finally{System.clearProperty("apocbr.postgres.loginNetworkTimeoutMillis");}
  System.setProperty("apocbr.postgres.loginNetworkTimeoutMillis","60000");
  try(var login=ApocBRForegroundDatabase.beginLogin()) {
   time.set(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(800));
   try(Connection c=ApocBRForegroundDatabase.wrap(fake.connection())){
    c.prepareStatement("SELECT 1").close();check(fake.networkTimeout>0&&fake.networkTimeout<=800,"network wait cannot exceed remaining workflow deadline");
   }
  }finally{System.clearProperty("apocbr.postgres.loginNetworkTimeoutMillis");}
  try(var budget=ApocBRForegroundDatabase.begin();Connection c=ApocBRForegroundDatabase.wrap(fake.connection())) {
   c.prepareStatement("SELECT 1").close();check(fake.networkTimeout>0&&fake.networkTimeout<=250,"foreground socket work bounded to remaining budget");
   try(var login=ApocBRForegroundDatabase.beginLogin();Connection nested=ApocBRForegroundDatabase.wrap(fake.connection())){
    nested.prepareStatement("SELECT 1").close();check(fake.networkTimeout<=250,"nested login cannot enlarge an existing ordinary workflow");
   }
   time.set(System.nanoTime()-1);
   // A new nested scope retains the expired workflow deadline rather than resetting it.
   try(var nested=ApocBRForegroundDatabase.begin();Connection expired=ApocBRForegroundDatabase.wrap(fake.connection())) {
    try{expired.prepareStatement("SELECT 2");throw new AssertionError("nested call reset expired budget");}
    catch(SQLTimeoutException expected){check(expected.getSQLState().equals("HYT00")&&!(boolean)connectivity.invoke(null,expected),"local deadline cannot mark PostgreSQL globally unavailable");}
   }
  }
  check((boolean)connectivity.invoke(null,new SQLException("PRIVATE_SOCKET","08006")),"real transport failure still closes admission");
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
 static void shutdownBudget(Path root,boolean offline)throws Exception {
  Fake fake=new Fake();
  Field singleton=ApocBRPostgresConnector.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,new ApocBRPostgresConnector(fake.source(),s->{}));
  Field configured=ApocBRPostgresConnector.class.getDeclaredField("configured");configured.setAccessible(true);configured.setBoolean(null,true);
  System.setProperty("apocbr.postgres.enabled","true");System.setProperty("apocbr.postgres.journalDirectory",root.toString());
  ApocBRPostgresConnector.prepareStartup();ApocBRPostgresConnector.finishStartup();
  fake.blockWrite=true;ApocBRPersistence.submit(write("barrier"));
  check(fake.entered.await(5,TimeUnit.SECONDS),"barrier holds replay until shutdown starts");
  UUID bad=ApocBRPersistence.submit(write("constraint-one"));
  ApocBRPersistence.submit(write("constraint-two"));
  if(!offline){ApocBRPersistence.submit(write("transient-four"));ApocBRPersistence.submit(write("runtime-failure"));}
  ApocBRPersistence.submit(write("after-failures"));
  fake.permanentFailure=true;
  List<Throwable> failures=new CopyOnWriteArrayList<>();
  Thread stop=new Thread(()->{try{ApocBRPersistence.drain();}catch(Throwable error){failures.add(error);}});
  stop.setDaemon(true);stop.start();
  until(()->ApocBRPersistence.state().equals("DRAINING"),"bounded shutdown enters draining");
  fake.offline=offline;fake.blockWrite=false;fake.release.countDown();stop.join(15000);
  check(!stop.isAlive()&&failures.isEmpty(),"drain completes despite permanent failures");
  check(ApocBRPersistence.status().durable()==0&&ApocBRPersistence.status().staged()==0,"discard advances every failed head");
  if(!offline){
   check(fake.writeAttempts.get("constraint-one")==5&&fake.writeAttempts.get("constraint-two")==5,"each SQL failure gets its own five-attempt budget");
   check(fake.writeAttempts.get("runtime-failure")==5,"runtime failures also exhaust after five attempts");
   check(fake.writeAttempts.get("transient-four")==5&&fake.committed.contains("transient-four"),"success on fifth attempt commits rather than discards");
   check(fake.committed.equals(List.of("barrier","transient-four","after-failures")),"later operations commit in FIFO order after discard");
   check(!fake.receipts.contains(bad),"discard does not forge a database success receipt");
  }else check(fake.committed.isEmpty(),"offline discard cannot report committed writes");
  final int[] workAttempts={0};
  ApocBRPersistence.shutdownWork(()->{workAttempts[0]++;throw new RuntimeException(new SQLException("PRIVATE_SHUTDOWN","08006"));});
  check(workAttempts[0]==5,"synchronous shutdown database work is bounded too");
  try{ApocBRPersistence.shutdownWork(()->{throw new IllegalArgumentException("programming error");});throw new AssertionError("non-database failure swallowed");}
  catch(IllegalArgumentException expected){checks++;}
  ApocBRPersistence.close();check(ApocBRPersistence.state().equals("CLOSED"),"empty shutdown can close while database remains offline");
  try(var journal=new ApocBRPersistenceJournal(root,256,s->{})){check(journal.empty(),"discard checkpoint survives journal reopen");}
 }
 static void startupBudget(Path root)throws Exception {
  Fake fake=new Fake();fake.permanentFailure=true;fake.blockWrite=true;
  Field singleton=ApocBRPostgresConnector.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,new ApocBRPostgresConnector(fake.source(),s->{}));
  Field configured=ApocBRPostgresConnector.class.getDeclaredField("configured");configured.setAccessible(true);configured.setBoolean(null,true);
  System.setProperty("apocbr.postgres.enabled","true");System.setProperty("apocbr.postgres.journalDirectory",root.toString());
  UUID bad;
  try(var journal=new ApocBRPersistenceJournal(root,256,s->{})){
   journal.submit(write("barrier"));bad=journal.submit(write("constraint-one"));
   journal.submit(write("constraint-two"));journal.submit(write("incomplete-live"));journal.submit(write("transient-four"));
   journal.submit(write("runtime-failure"));journal.submit(write("after-failures"));
  }
  List<Throwable> failures=new CopyOnWriteArrayList<>();
  Thread start=new Thread(()->{try{ApocBRPostgresConnector.prepareStartup();ApocBRPostgresConnector.finishStartup();}catch(Throwable error){failures.add(error);}});
  start.setDaemon(true);start.start();
  check(fake.entered.await(5,TimeUnit.SECONDS),"startup replays the previous process journal");
  check(start.isAlive()&&!ApocBRPostgresConnector.acceptingLogins(),"startup admission stays closed until replay completes");
  fake.blockWrite=false;fake.release.countDown();start.join(15000);
  check(!start.isAlive()&&failures.isEmpty(),"startup completes after discarding exhausted entries");
  check(fake.writeAttempts.get("constraint-one")==5&&fake.writeAttempts.get("constraint-two")==5,"startup SQL entries have independent five-attempt budgets");
  check(fake.writeAttempts.get("runtime-failure")==5,"startup runtime failures are bounded too");
  check(fake.writeAttempts.get("incomplete-live")==5,"incomplete graph retains its independent retry budget");
  check(fake.writeAttempts.get("transient-four")==5,"startup allows success on fifth attempt");
  check(fake.committed.equals(List.of("barrier","transient-four","after-failures")),"startup progresses through failed heads in FIFO order");
  check(!fake.receipts.contains(bad),"startup discard does not fabricate a success receipt");
  check(ApocBRPersistence.status().durable()==0&&ApocBRPostgresConnector.acceptingLogins(),"startup opens admission after journal exhaustion");
  ApocBRPersistence.close();
  try(var journal=new ApocBRPersistenceJournal(root,256,s->{})){check(journal.empty(),"startup discards remain removed after reopen");}
 }
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);if(args.length>1){
   if(args[1].equals("missing"))missingReceipt(root);
   else if(args[1].equals("startup"))startupBudget(root);
   else shutdownBudget(root,args[1].equals("offline"));
   System.out.println(args[1]+" checks="+checks);return;
  }
  journal(root);quarantine(root);deadlines();humanSales(root);runtime(root);
  System.out.println("Resilience checks="+checks);
 }
}
'''

_fixture_tree = ast.parse((ROOT / 'tools/test_postgres_player_relational.py').read_text(encoding='utf-8'))
_fixture_harness = next(ast.literal_eval(node.value) for node in _fixture_tree.body
                        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'HARNESS' for t in node.targets))
_models = _fixture_harness[_fixture_harness.index(' static class Color'):_fixture_harness.index(' static void captures()')]
HARNESS = HARNESS.replace(' static int checks;', _models + '\n static int checks;', 1)

with tempfile.TemporaryDirectory(prefix='apocbr_resilience_') as folder:
    target = Path(folder)
    files = []
    stubs = {
        'zombie/debug/DebugLog.java': 'package zombie.debug; public class DebugLog {public static void log(String text){System.out.println(text);}}',
        'zombie/network/GameServer.java': 'package zombie.network; public class GameServer {public static Thread mainThread=Thread.currentThread();public static boolean server=true,coop;public static String serverName="fixture";public static zombie.core.raknet.UdpEngine udpEngine;public static boolean isDelayedDisconnect(zombie.core.raknet.UdpConnection c){return false;}}',
        'zombie/ZomboidFileSystem.java': 'package zombie; public class ZomboidFileSystem {public static final ZomboidFileSystem instance=new ZomboidFileSystem();public String getSaveDir(){return "unused";}public String getCurrentSaveDir(){return "fixture";}}',
    }
    stubs['zombie/network/ServerWorldDatabase.java'] = 'package zombie.network; public class ServerWorldDatabase {public static class LoginRoles {}public static void useLoginRoles(LoginRoles roles){}}'
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
    for mode in ('shutdown', 'offline', 'startup'):
        result = subprocess.run([str(JDK / 'java.exe'), '-cp', folder + os.pathsep + cp, 'zombie.savefile.ApocBRResilienceTest', str(target / mode), mode], check=True, timeout=30, capture_output=True, text=True)
        print(result.stdout)
        assert 'PRIVATE_' not in result.stdout, 'discard diagnostics must not expose exception messages or bound values'
        discards = [line for line in result.stdout.splitlines() if 'REPLAY_DISCARDED;' in line]
        assert len(discards) == (3 if mode == 'shutdown' else 4), 'every discarded operation must be logged without rate limiting'
        assert all('attempts=5;' in line for line in discards), 'discard logs must report the five-attempt budget'
        if mode == 'startup':
            assert any('stage=live_fields;' in line and 'graphIssues=UNSUPPORTED_ITEM_SUBTYPE,UNCLASSIFIED_CAPTURE_ISSUE' in line for line in discards), 'discard must retain sanitized graph rejection details'
        first_failures = [line for line in result.stdout.splitlines() if 'REPLAY_FAILED;' in line]
        assert len({line.split('operation=')[1].split(';')[0] for line in first_failures}) == len(first_failures), 'replay emits detailed first failure once per operation'
