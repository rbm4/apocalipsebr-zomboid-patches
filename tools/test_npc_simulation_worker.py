"""Production NPC worker clock and factory async/sync integration, with engine stubs."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_server_humans import FILES, ROOT

HARNESS = r"""
package zombie.apocalipse.humans;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import zombie.apocalipse.humans.state.*;
import zombie.iso.*;

public class NpcWorkerTest {
 static int checks;
 static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
 static void await(CountDownLatch latch)throws Exception{check(latch.await(5,TimeUnit.SECONDS),"latch completed");}
 static void waitFor(java.util.function.BooleanSupplier ready)throws Exception{
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(!ready.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(2);
  check(ready.getAsBoolean(),"condition completed");
 }
 static void clock()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var ran=new CountDownLatch(3);
  var active=new AtomicInteger();var maximum=new AtomicInteger();var calls=new AtomicInteger();
  var error=new AtomicReference<Throwable>();var timestamps=new CopyOnWriteArrayList<Long>();
  long period=10_000_000L;
  var worker=new ServerHumanSimulationWorker(period,now->{
   maximum.accumulateAndGet(active.incrementAndGet(),Math::max);timestamps.add(now);
   try{if(calls.incrementAndGet()==1){entered.countDown();release.await();}}
   catch(InterruptedException e){throw new RuntimeException(e);}
   finally{active.decrementAndGet();ran.countDown();}
  },error::set);
  worker.start();worker.start();await(entered);
  // Deliberately hold one execution across several deadlines; none may queue or overlap.
  Thread.sleep(45);check(calls.get()==1,"blocked tick has no overlapping or queued executions");
  release.countDown();await(ran);worker.shutdown();worker.shutdown();
  check(error.get()==null&&!worker.failed(),"normal shutdown has no simulation failure");
  check(maximum.get()==1,"one tick at a time");check(worker.overruns()>=1,"overrun recorded");
  check(worker.ticks()==calls.get()&&worker.lastDurationNanos()>=0,"completed tick diagnostics");
  for(int i=1;i<timestamps.size();i++)check(timestamps.get(i)-timestamps.get(i-1)>=period,"no catch-up burst");
  int stopped=calls.get();worker.start();Thread.sleep(25);check(calls.get()==stopped,"shutdown cannot restart");
  var failed=new CountDownLatch(1);var attempts=new AtomicInteger();var expected=new IllegalStateException("fixture");
  var broken=new ServerHumanSimulationWorker(period,n->{attempts.incrementAndGet();throw expected;},t->{error.set(t);failed.countDown();});
  broken.start();await(failed);broken.shutdown();
  check(broken.failed()&&error.get()==expected&&attempts.get()==1,"failure reported once and stops scheduling");
  var never=new ServerHumanSimulationWorker(period,n->{throw new AssertionError("must not start");},error::set);
  never.shutdown();never.start();check(never.ticks()==0,"shutdown before start");
 }
 static void stateWriters()throws Exception{
  var writers=ConcurrentHashMap.<Thread>newKeySet();
  var store=new HumanStateStore(HumanState.INITIAL,()->{
   if(!writers.contains(Thread.currentThread()))throw new IllegalStateException("foreign writer");
  });
  var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
  Runnable write=()->{
   writers.add(Thread.currentThread());ready.countDown();
   try{go.await();for(int i=0;i<100;i++)store.dispatch(new HumanMutation.Adjust(HumanState.Stat.HUNGER,.001f));}
   catch(Throwable t){failure.set(t);}
  };
  var a=new Thread(write);var b=new Thread(write);a.start();b.start();await(ready);go.countDown();a.join();b.join();
  check(failure.get()==null&&store.snapshot().revision()==200,"authorized concurrent reductions are serialized without lost revisions");
  check(Math.abs(store.snapshot().vitals().hunger()-.2f)<.00001,"both authorized writers' effects retained");
  try{store.dispatch(new HumanMutation.Heal(.1f));throw new AssertionError("foreign writer accepted");}
  catch(IllegalStateException expected){checks++;}
 }
 public static void main(String[] args)throws Exception{
  clock();stateWriters();
  boolean async=Boolean.parseBoolean(args[0]);
  check(ServerHumanFactory.ASYNC==async,"default or explicit startup mode");
  var factory=ServerHumanFactory.getInstance();
  ServerHumanFactory.update();
  var status=ServerHumanFactory.simulationStatus();
  check(status.started()==async,"only async mode starts worker");
  short id=factory.spawn(new ServerHumanDefinition("async-test","__async_test","Test",100.5f,200.5f,0,IsoDirections.E,false,"Tourist"));
  int activation=factory.lifecycle("async-test").activations();
  var state=factory.state("async-test");
  var queued=factory.submitMutation("async-test",activation,state.revision(),new HumanMutation.Damage(.2f,0,0,0));
  if(async){
   // No more main-thread update calls: queued work must progress on the independent clock.
   check(queued.get(5,TimeUnit.SECONDS).status()==HumanStateStore.Status.APPLIED,"independent worker consumes command");
   waitFor(()->ServerHumanFactory.simulationStatus().ticks()>=2);
   check(!ServerHumanFactory.simulationStatus().failed(),"native adapter/state guards permit worker tick");
  }else{
   check(!queued.isDone(),"sync mode has no background progress");
   factory.tick(System.nanoTime()+ServerHumanFactory.BRAIN_UPDATE_NANOS);
   check(queued.join().status()==HumanStateStore.Status.APPLIED,"sync tick consumes command");
  }
  check(factory.state("async-test").vitals().health()<1,"worker mutation visible on server thread");
  check(ServerHumanFactory.handleDataRequest(id,null),"server callback still resolves worker-managed actor");
  var denied=new AtomicReference<Throwable>();
  var foreign=new Thread(()->{try{factory.remove("async-test");}catch(Throwable t){denied.set(t);}});
  foreign.start();foreign.join();check(denied.get() instanceof IllegalStateException,"unrelated threads still denied");
  if(async){
   var field=ServerHumanFactory.class.getDeclaredField("humans");field.setAccessible(true);
   synchronized(factory){((Map<String,ServerHuman>)field.get(factory)).put("broken",null);}
   waitFor(()->ServerHumanFactory.simulationStatus().failed());
   // Failure callback completes after the worker's failure flag becomes visible.
   waitFor(()->factory.submitMutation("async-test",activation,0,new HumanMutation.Heal(.1f)).isDone());
   check(factory.submitMutation("async-test",activation,0,new HumanMutation.Heal(.1f)).join().status()==HumanStateStore.Status.UNAVAILABLE,"failed worker rejects mutations");
   ServerHumanFactory.update();check(ServerHumanFactory.simulationStatus().failed(),"main update does not restart failed worker");
  }
  ServerHumanFactory.shutdown();ServerHumanFactory.shutdown();
  long count=ServerHumanFactory.simulationStatus().ticks();
  ServerHumanFactory.update();check(ServerHumanFactory.simulationStatus().ticks()==count,"no tick after shutdown");
  check(factory.submitMutation("async-test",activation,0,new HumanMutation.Heal(.1f)).join().status()==HumanStateStore.Status.UNAVAILABLE,"shutdown rejects mutations");
  System.out.println("Passed "+checks+" NPC worker and "+(async?"async":"sync")+" integration assertions.");
 }
}
"""


def main():
    server_map = (ROOT / '42.21.0/src/zombie/network/ServerMap.java').read_text(encoding='utf-8')
    quit_body = server_map.split('public void QueuedQuit() {', 1)[1]
    assert quit_body.index('ServerHumanFactory.shutdown()') < quit_body.index('this.QueuedSaveAll(true)')
    assert quit_body.index('ServerHumanFactory.shutdown()') < quit_body.index('PolygonalMap2.instance.stop()')
    with tempfile.TemporaryDirectory(prefix='apocbr-npc-worker-') as directory:
        work = Path(directory)
        sources = []
        files = dict(FILES)
        files['zombie/apocalipse/humans/NpcWorkerTest.java'] = HARNESS
        for relative, content in files.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            sources.append(path)
        sources.extend(sorted((ROOT / '42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
        argfile = work / 'javac.args'
        argfile.write_text('\n'.join('"' + str(p).replace('\\', '/') + '"' for p in sources))
        output = work / 'classes'
        subprocess.run([str(ROOT / 'jdk/bin/javac.exe'), '--release', '25', '-d', str(output), '@' + str(argfile)], check=True)
        for options, expected in [([], 'true'), (['-Dapocbr.npc.async=false'], 'false'), (['-Dapocbr.npc.async=true'], 'true')]:
            subprocess.run([str(ROOT / 'jdk/bin/java.exe'), *options, '-cp', str(output),
                            'zombie.apocalipse.humans.NpcWorkerTest', expected], check=True, timeout=30)


if __name__ == '__main__':
    main()
