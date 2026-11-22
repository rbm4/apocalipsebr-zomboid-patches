package zombie.savefile;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import zombie.core.Core;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

/** Bounded JDBC work; admission, continuations and connection state belong to the server thread. */
public final class ApocBRAsyncLogin {
    private static final int LIMIT=32;
    private static final ThreadLocal<Boolean> worker=ThreadLocal.withInitial(()->false);
    private static final ThreadPoolExecutor io=new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(LIMIT),r->{Thread t=new Thread(r,"ApocBR-Login-JDBC");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final Map<UdpConnection,Pending<?>> pending=new IdentityHashMap<>();
    private static boolean closed;
    private static volatile boolean simulationStarted;
    private record SecondFactor(String user,long expires) {}
    private static final Map<UdpConnection,SecondFactor> secondFactors=new IdentityHashMap<>();
    public static boolean onSimulationThread(){return simulationStarted&&Thread.currentThread()==GameServer.mainThread;}
    public static void expectSecondFactor(UdpConnection c,String user) {
        if(secondFactors.size()>=128){ApocBRLoginGuard.deny(c);return;}
        secondFactors.put(c,new SecondFactor(user,System.nanoTime()+TimeUnit.MINUTES.toNanos(5)));
    }
    public static boolean takeSecondFactor(UdpConnection c,String user) {
        SecondFactor proof=secondFactors.remove(c);
        return proof!=null&&proof.expires()>System.nanoTime()&&Objects.equals(proof.user(),user)&&c.googleAuth;
    }

    private static final class Pending<T> {
        final UdpConnection connection;
        final String phase,world,user,id;
        final long guid,steam,owner,expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        final CompletableFuture<T> result=new CompletableFuture<>();
        final CountDownLatch consumed=new CountDownLatch(1);
        final Consumer<T> resume;
        Pending(UdpConnection c,String phase,Consumer<T> resume) {
            connection=c;this.phase=phase;this.resume=resume;world=Core.gameSaveWorld;
            guid=c.getConnectedGUID();user=c.getUserName();id=c.getIDStr();steam=c.getSteamId();owner=c.getOwnerId();
        }
        boolean current() {
            return GameServer.udpEngine!=null&&GameServer.udpEngine.getActiveConnection(guid)==connection
                &&!GameServer.isDelayedDisconnect(connection)&&Objects.equals(world,Core.gameSaveWorld)
                &&Objects.equals(user,connection.getUserName())&&Objects.equals(id,connection.getIDStr())
                &&steam==connection.getSteamId()&&owner==connection.getOwnerId();
        }
        void finish() { resume.accept(result.getNow(null)); }
    }
    public static boolean isWorker(){return worker.get();}
    public static boolean enabled(){return ApocBRServerDatabase.enabled()||ApocBRPlayerDatabase.enabled();}
    /** One in-flight phase per connection. Same-phase retransmissions are coalesced. */
    public static <T> void submit(UdpConnection c,String phase,Callable<T> work,Consumer<T> resume) {
        Pending<?> previous=pending.get(c);
        if(previous!=null) {
            if(!previous.phase.equals(phase)) {pending.remove(c);previous.result.cancel(false);previous.consumed.countDown();ApocBRLoginGuard.deny(c);}
            return;
        }
        if(closed||pending.size()>=LIMIT){ApocBRLoginGuard.deny(c);return;}
        if(!ApocBRLoginGuard.ready(c))return;
        Pending<T> entry=new Pending<>(c,phase,resume);pending.put(c,entry);
        zombie.network.ServerWorldDatabase.LoginRoles roles=ApocBRServerDatabase.enabled()?new zombie.network.ServerWorldDatabase.LoginRoles():null;
        try {
            io.execute(()->{
                if(entry.result.isDone())return;
                worker.set(true);
                zombie.network.ServerWorldDatabase.useLoginRoles(roles);
                try(var budget=ApocBRForegroundDatabase.beginLogin()) {
                    if(System.nanoTime()>=entry.expires)throw new TimeoutException();
                    T value=work.call();ApocBRForegroundDatabase.verifySuccess();entry.result.complete(value);
                }catch(Throwable failure){entry.result.completeExceptionally(failure);}
                finally{worker.remove();zombie.network.ServerWorldDatabase.useLoginRoles(null);}
                // Hold this worker's result capacity until the simulation consumes/discards it.
                // JDBC leases are closed. This bounds retained graphs to two, even during a slow tick.
                try{entry.consumed.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}
            });
        }catch(RejectedExecutionException full){pending.remove(c);ApocBRLoginGuard.deny(c);}
    }
    /** Called every server tick. No waiting, and at most two continuations / 2ms per tick. */
    public static void update() {
        simulationStarted=true;
        secondFactors.entrySet().removeIf(e->e.getValue().expires()<System.nanoTime()
            ||GameServer.udpEngine.getActiveConnection(e.getKey().getConnectedGUID())!=e.getKey());
        long started=System.nanoTime();int completed=0;
        for(Pending<?> entry:new ArrayList<>(pending.values())) {
            if(pending.get(entry.connection)!=entry)continue;
            if(!entry.current()){pending.remove(entry.connection);entry.result.cancel(false);entry.consumed.countDown();continue;}
            if(System.nanoTime()>=entry.expires){pending.remove(entry.connection);entry.result.cancel(false);entry.consumed.countDown();ApocBRLoginGuard.deny(entry.connection);continue;}
            if(!entry.result.isDone())continue;
            pending.remove(entry.connection);entry.consumed.countDown();
            try {if(ApocBRLoginGuard.ready(entry.connection))entry.finish();}
            catch(RuntimeException|LinkageError failure){ApocBRLoginGuard.deny(entry.connection);}
            if(++completed>=2||System.nanoTime()-started>=2_000_000L)break;
        }
    }
    public static void shutdown(){
        closed=true;simulationStarted=false;
        for(var p:pending.values()){p.result.cancel(false);p.consumed.countDown();}
        pending.clear();secondFactors.clear();io.shutdownNow();
    }
    private ApocBRAsyncLogin(){}
}
