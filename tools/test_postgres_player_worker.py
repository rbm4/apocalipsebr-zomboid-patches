"""Run the production player/vehicle worker against deterministic failing initialization."""
from pathlib import Path
import subprocess, tempfile

ROOT=Path(__file__).resolve().parents[1]
JDK=ROOT/'jdk/bin'
FILES={
 'zombie/savefile/ApocBRPostgresConnector.java': '''package zombie.savefile;
 public class ApocBRPostgresConnector {
 public static final ThreadLocal<Boolean> background=ThreadLocal.withInitial(()->false);
 public static volatile boolean entered,exited;public static int shutdowns;
 public static void runBackgroundDatabaseWork(Runnable work){boolean old=background.get();background.set(true);entered=true;try{work.run();}finally{background.set(old);exited=true;}}
 public static void process(){if(!background.get())throw new AssertionError("foreground worker");}
 public static void shutdown(){shutdowns++;}
 }''',
 'zombie/savefile/ServerPlayerDB.java': '''package zombie.savefile;
 public class ServerPlayerDB {
 public static final java.util.concurrent.atomic.AtomicInteger attempts=new java.util.concurrent.atomic.AtomicInteger(),processed=new java.util.concurrent.atomic.AtomicInteger();
 private static final ServerPlayerDB instance=new ServerPlayerDB();
 public static ServerPlayerDB getInstance(){if(!ApocBRPostgresConnector.background.get())throw new AssertionError("foreground initialization");
 if(attempts.incrementAndGet()<=3)throw new IllegalStateException("injected connection unavailability");return instance;}
 public void process(){processed.incrementAndGet();}
 }''',
 'zombie/vehicles/VehiclesDB2.java': '''package zombie.vehicles;public class VehiclesDB2 {
 public static final VehiclesDB2 instance=new VehiclesDB2();public final java.util.concurrent.atomic.AtomicInteger updates=new java.util.concurrent.atomic.AtomicInteger();
 public void updateWorldStreamer(){updates.incrementAndGet();}}''',
 'zombie/core/logger/ExceptionLogger.java': '''package zombie.core.logger;public class ExceptionLogger {
 public static final java.util.List<Throwable> errors=new java.util.concurrent.CopyOnWriteArrayList<>();
 public static void logException(Throwable error){errors.add(error);}}''',
 'zombie/debug/DebugLog.java': '''package zombie.debug;public class DebugLog {
 public static final java.util.List<String> logs=new java.util.concurrent.CopyOnWriteArrayList<>();public static void log(String text){logs.add(text);}}''',
 'WorkerTest.java': '''import zombie.network.ServerPlayersVehicles;import zombie.savefile.*;
 public class WorkerTest {
 static void check(boolean b,String text){if(!b)throw new AssertionError(text);}
 static void await(java.util.function.BooleanSupplier condition)throws Exception{long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
 while(!condition.getAsBoolean()){if(System.nanoTime()>until)throw new AssertionError("worker did not progress");Thread.sleep(5);}}
 public static void main(String[] args)throws Exception {
 var worker=ServerPlayersVehicles.instance;worker.init();
 try {
  await(()->ServerPlayerDB.attempts.get()>=3);
  check(zombie.core.logger.ExceptionLogger.errors.size()==1,"repeated initialization failure logs once rather than spinning");
  var field=ServerPlayersVehicles.class.getDeclaredField("thread");field.setAccessible(true);Thread thread=(Thread)field.get(worker);
  await(()->thread.getState()==Thread.State.TIMED_WAITING);
  check(thread.isAlive(),"failed initialization waits and retries instead of terminating");
  await(()->zombie.vehicles.VehiclesDB2.instance.updates.get()>=2);
  check(ServerPlayerDB.processed.get()>=2,"player work resumes after initialization recovers");
  check(zombie.debug.DebugLog.logs.stream().filter(s->s.contains("PLAYER_VEHICLE_WORKER_RECOVERED")).count()==1,"one recovery event");
 }finally{worker.stop();}
 check(ApocBRPostgresConnector.entered&&ApocBRPostgresConnector.exited,"worker owns and releases background scope");
 check(ApocBRPostgresConnector.shutdowns==1,"worker shutdown still drains persistence once");
 System.out.println("Player/vehicle worker: background initialization, paced failure retry, log suppression, recovery and shutdown passed");
 }}'''
}
with tempfile.TemporaryDirectory(prefix='apocbr_player_worker_') as folder:
    target=Path(folder);sources=[]
    for name,body in FILES.items():
        path=target/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf-8');sources.append(path)
    sources.append(ROOT/'42.21.0/src/zombie/network/ServerPlayersVehicles.java')
    subprocess.run([str(JDK/'javac.exe'),'--release','25','-d',folder,*map(str,sources)],check=True)
    subprocess.run([str(JDK/'java.exe'),'-cp',folder,'WorkerTest'],check=True,timeout=15)
