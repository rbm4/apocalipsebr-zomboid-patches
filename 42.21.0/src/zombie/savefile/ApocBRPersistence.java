package zombie.savefile;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;
import zombie.ZomboidFileSystem;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

/** Connector-owned lifecycle. The journal writer never waits for a database lease. */
final class ApocBRPersistence {
    enum State { STARTING, AVAILABLE, UNAVAILABLE, RECOVERING, DRAINING, CLOSED }
    private static volatile ApocBRPersistence instance;
    private static final int REPLAY_MAX_ATTEMPTS=5;
    private static final ThreadLocal<UUID> operation=new ThreadLocal<>();
    private static final ThreadLocal<Boolean> background=ThreadLocal.withInitial(()->false);
    private final ApocBRPersistenceJournal journal;
    private volatile State state=State.STARTING;
    private volatile boolean startup=true,draining,closed,inFlight;
    private volatile long lastProgress;
    private volatile UUID replayOperation;
    private volatile String replayKind="NONE",lastIssue="NONE";
    private final Thread writer,replay;
    private final Object replayPause=new Object();
    // Owned by the replay worker. Normal runtime failures do not consume the startup/shutdown budget.
    private UUID retryOperation;
    private int replayFailures;
    private int runtimeFailures;
    private boolean reportedEntryFailure;
    private String discardIssue,discardSqlState,discardDetails;

    static boolean enabled(){return Boolean.getBoolean("apocbr.postgres.enabled")
        &&(ApocBRServerDatabase.enabled()||ApocBRPlayerDatabase.enabled()||ApocBRPlayerRelationalSync.enabled());}
    static synchronized ApocBRPersistence get() {
        if(instance==null)try{instance=new ApocBRPersistence();}
        catch(IOException error){throw new IllegalStateException("PostgreSQL journal unavailable",error);}
        return instance;
    }
    static boolean journaling(){return instance!=null&&enabled();}
    static boolean background(){return background.get();}
    static void background(boolean value){background.set(value);}
    static boolean admitting(){ApocBRPersistence current=instance;return current==null||current.state==State.AVAILABLE&&!current.draining;}
    static boolean pendingPlayer(String key){ApocBRPersistence current=instance;return current!=null&&current.journal.pendingPlayer(key);}
    static boolean reachable(){ApocBRPersistence current=instance;return current==null||current.state==State.AVAILABLE&&!current.draining;}
    static String state(){return instance==null?"STARTING":instance.state.name();}
    static ApocBRPostgresConnector.PersistenceStatus status() {
        ApocBRPersistence current=instance;
        if(current==null)return new ApocBRPostgresConnector.PersistenceStatus(enabled()?"STARTING":"DISABLED",0,0,0,0,0,false);
        var pending=current.journal.status();return new ApocBRPostgresConnector.PersistenceStatus(current.state.name(),pending.staged(),pending.stagedBytes(),pending.durable(),pending.bytes(),pending.oldestAgeMs(),current.inFlight);
    }
    static void failed(SQLException error) {
        if(connectionFailure(error)) {
            ApocBRPersistence current=instance;if(current!=null)current.transition(current.draining?State.DRAINING:State.UNAVAILABLE);
        }
    }
    private static boolean connectionFailure(Throwable error) {
        for(int depth=0;error!=null&&depth<8;depth++,error=error.getCause()) {
            if(error instanceof SQLTransientConnectionException||error instanceof SQLRecoverableException)return true;
            if(error instanceof SQLException sql){String state=sql.getSQLState();if(state!=null&&(state.startsWith("08")||java.util.Set.of("57P01","57P02","57P03").contains(state)))return true;}
            if(error instanceof java.net.SocketException||error instanceof java.net.SocketTimeoutException||error instanceof java.io.EOFException)return true;
        }
        return false;
    }
    private static boolean rejectedData(Throwable error) {
        for(int depth=0;error!=null&&depth<8;depth++,error=error.getCause())if(error instanceof SQLException sql) {
            String state=sql.getSQLState();if(state!=null&&(state.startsWith("22")||state.startsWith("23")))return true;
        }
        return false;
    }
    static UUID submit(ApocBRPersistenceJournal.Operation value) {
        try{return get().journal.submit(value);}
        catch(IOException error){throw new IllegalStateException("PostgreSQL journal write failed; operation not acknowledged",error);}
    }
    static void startup() {
        if(!enabled())return;
        ApocBRPersistence current=get();
        // The startup caller is allowed bootstrap queries; admission remains closed.
        background.set(true);
        current.awaitDrain();current.startup=false;
        current.transition(State.AVAILABLE);
    }
    static void finishStartup(){background.remove();}
    static void drain() {
        if(!enabled())return;ApocBRPersistence current=get();
        synchronized(current.replayPause){current.draining=true;current.replayPause.notifyAll();}
        current.transition(State.DRAINING);current.awaitDrain();
    }
    static void shutdownWork(Runnable work) {
        if(!enabled()){work.run();return;}
        ApocBRPersistence current=get();
        for(int attempts=1;;attempts++) {
            drain();
            try{work.run();drain();return;}
            catch(RuntimeException failure) {
                boolean databaseFailure=false;Throwable cause=failure;
                for(int depth=0;cause!=null&&depth<8;depth++,cause=cause.getCause())if(cause instanceof SQLException
                    ||cause instanceof IllegalStateException&&cause.getMessage()!=null&&cause.getMessage().startsWith("PostgreSQL ")){databaseFailure=true;break;}
                if(!databaseFailure)throw failure;
                if(attempts>=REPLAY_MAX_ATTEMPTS){
                    log("DRAIN_DATABASE_WORK_DISCARDED; attempts="+attempts+"; issue="+failure.getClass().getName());
                    return;
                }
                current.lastIssue=failure.getClass().getName();current.report("DRAIN_DATABASE_WORK_DEFERRED",null);sleep(250);
            }
        }
    }
    static void close() {
        ApocBRPersistence current=instance;if(current==null)return;
        drain();current.closed=true;
        join(current.writer);join(current.replay);
        try{current.journal.close();}catch(IOException error){throw new IllegalStateException("Journal close failed",error);}
        current.transition(State.CLOSED);
    }
    private static void join(Thread thread){boolean interrupted=false;while(thread.isAlive())try{thread.join(1000);}catch(InterruptedException error){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
    private ApocBRPersistence()throws IOException {
        String configured=System.getProperty("apocbr.postgres.journalDirectory");
        Path directory=configured==null?Path.of(ZomboidFileSystem.instance.getSaveDir(),"Multiplayer",GameServer.serverName,"postgres-journal"):Path.of(configured);
        journal=new ApocBRPersistenceJournal(directory,64L*1024*1024,ApocBRPersistence::log);
        writer=new Thread(this::writeLoop,"ApocBR-postgres-journal");writer.setDaemon(true);
        replay=new Thread(this::replayLoop,"ApocBR-postgres-replay");replay.setDaemon(true);
        writer.start();replay.start();
    }
    private void writeLoop() {
        while(!closed) {
            try{journal.flush();}catch(IOException|RuntimeException error){lastIssue=error.getClass().getName();report("JOURNAL_WRITE_FAILED",null);}
            sleep(25);
        }
    }
    private void replayLoop() {
        background.set(true);long delay=250;
        while(!closed) {
            try {
                // During shutdown, connection failures belong to the selected operation's
                // attempt budget, rather than an unbounded probe before it can run.
                if(!draining&&(state!=State.AVAILABLE||startup))probe();
                if(!startup&&!draining&&state!=State.AVAILABLE)transition(State.RECOVERING);
                ApocBRPersistenceJournal.Entry entry=journal.head();
                if(entry!=null) {
                    inFlight=true;operation.set(entry.id());replayOperation=entry.id();replayKind=entry.operation().kind();
                    try{replayEntry(entry);}
                    finally{operation.remove();inFlight=false;}
                } else {
                    if(!startup&&!draining&&journal.empty())transition(State.AVAILABLE);
                    pauseReplay(50);
                }
                delay=250;
            }catch(SQLException error){lastIssue=error.getClass().getName();failed(error);if(state!=State.UNAVAILABLE&&!draining)transition(State.RECOVERING);report("REPLAY_DEFERRED",error.getSQLState());pauseReplay(delay);delay=Math.min(30_000,delay*2);}
            catch(IOException|RuntimeException|LinkageError error){lastIssue=error.getClass().getName();if(!draining&&state!=State.UNAVAILABLE)transition(State.RECOVERING);report("REPLAY_BLOCKED",null);pauseReplay(1000);}
        }
    }
    private void pauseReplay(long milliseconds) {
        synchronized(replayPause){try{replayPause.wait(startup||draining?Math.min(milliseconds,250):milliseconds);}catch(InterruptedException ignored){}}
    }
    private void replayEntry(ApocBRPersistenceJournal.Entry entry)throws SQLException,IOException {
        if(!entry.id().equals(retryOperation)){retryOperation=entry.id();replayFailures=0;runtimeFailures=0;reportedEntryFailure=false;}
        // A crash/checkpoint failure after durable quarantine must not execute that old
        // operation again, especially after a newer full player capture has committed.
        if(journal.quarantined(entry.id())){journal.quarantine(entry.id());return;}
        // A failed checkpoint must be retried without executing an exhausted operation again.
        if((startup||draining)&&replayFailures>=REPLAY_MAX_ATTEMPTS){discard(entry);return;}
        try{ApocBRPersistenceOperations.apply(entry.operation());}
        catch(SQLException|RuntimeException|LinkageError error){
            if(!reportedEntryFailure){
                reportedEntryFailure=true;
                log("REPLAY_FAILED; operation="+entry.id()+"; kind="+entry.operation().kind()
                    +"; phase="+(draining?"shutdown":startup?"startup":"runtime")+"; "+ApocBRPlayerRelationalMutations.writeFailure(error));
            }
            if(!startup&&!draining) {
                if(connectionFailure(error)) {
                    failed(new SQLException("PostgreSQL transport unavailable","08006"));throw error;
                }
                runtimeFailures++;
                if(rejectedData(error)||runtimeFailures>=REPLAY_MAX_ATTEMPTS) {
                    journal.quarantine(entry.id());
                    log("REPLAY_QUARANTINED; operation="+entry.id()+"; kind="+entry.operation().kind()+"; attempts="+runtimeFailures
                        +"; reloadBlocked="+(entry.operation().playerKey()!=null)+"; "+ApocBRPlayerRelationalMutations.writeFailure(error));
                } else {
                    report("REPLAY_OPERATION_RETRY",error instanceof SQLException sql?sql.getSQLState():null);pauseReplay(250);
                }
                return;
            }
            replayFailures++;
            discardIssue=error.getClass().getName();
            discardDetails=ApocBRPlayerRelationalMutations.failureDetails(error);
            String sqlState=error instanceof SQLException sql?sql.getSQLState():null;
            discardSqlState=sqlState!=null&&sqlState.matches("[A-Z0-9]{5}")?sqlState:"LOCAL";
            if(replayFailures<REPLAY_MAX_ATTEMPTS)throw error;
            discard(entry);return;
        }
        journal.acknowledgeCommitted(entry.id());lastIssue="NONE";report("REPLAY_PROGRESS",null);
    }
    private void discard(ApocBRPersistenceJournal.Entry entry)throws IOException {
        journal.acknowledge(entry.id());
        // Never rate limit discards: every deliberately lost operation must be identifiable.
        log("REPLAY_DISCARDED; operation="+entry.id()+"; kind="+entry.operation().kind()
            +"; attempts="+replayFailures+"; issue="+discardIssue+"; SQLState="+discardSqlState+discardDetails);
    }
    private void probe()throws SQLException {
        try(Connection connection=ApocBRPostgresConnector.borrowPlayerConnection();
            PreparedStatement statement=connection.prepareStatement("SELECT operation_id FROM public.apocbr_persistence_receipt WHERE false")) {
            statement.setQueryTimeout(2);try(ResultSet ignored=statement.executeQuery()){}
            connection.rollback();
        }
    }
    private void awaitDrain() {
        // Startup still requires a reachable, compatible database. Shutdown can finish
        // after successful replay or bounded discard, even if the database stays offline.
        boolean interrupted=false;
        for(;;) {
            try {
                background.set(true);if(!draining)probe();
                if(journal.empty()&&!inFlight)break;
            }catch(SQLException error){
                if(startup&&error.getSQLState()!=null&&(error.getSQLState().startsWith("42")||error.getSQLState().startsWith("28")))
                    throw new IllegalStateException("PostgreSQL startup requires compatible migrations and credentials");
                failed(error);report("DRAIN_WAITING",error.getSQLState());
            }
            report("DRAIN_WAITING",null);
            try{Thread.sleep(250);}catch(InterruptedException error){interrupted=true;}
        }
        if(interrupted)Thread.currentThread().interrupt();
    }
    private synchronized void transition(State next){if(state!=next){state=next;log("AVAILABILITY; state="+next);}}
    private void report(String code,String sqlState) {
        long now=System.nanoTime();if(now-lastProgress<30_000_000_000L)return;lastProgress=now;
        var pending=journal.status();log(code+"; state="+state+"; staged="+pending.staged()+"; stagedBytes="+pending.stagedBytes()
            +"; durable="+pending.durable()+"; durableBytes="+pending.bytes()+"; oldestPendingMs="+pending.oldestAgeMs()
            +"; inFlight="+inFlight+"; blockedPlayers="+journal.blockedPlayers()+"; operation="+replayOperation+"; kind="+replayKind+"; issue="+lastIssue
            +"; SQLState="+(sqlState!=null&&sqlState.matches("[A-Z0-9]{5}")?sqlState:"LOCAL"));
    }
    /** Receipt and mutation must share the caller's transaction. Existing fixtures/direct
     * non-journal calls intentionally have no operation context. */
    static boolean claim(Connection connection)throws SQLException {
        UUID id=operation.get();if(id==null)return true;
        try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.apocbr_persistence_receipt(operation_id) VALUES (?) ON CONFLICT (operation_id) DO NOTHING")) {
            statement.setQueryTimeout(2);statement.setObject(1,id);return statement.executeUpdate()==1;
        }
    }
    private static void sleep(long milliseconds){try{Thread.sleep(milliseconds);}catch(InterruptedException ignored){}}
    private static void log(String text){DebugLog.log("[ApocBR][Persistence] "+text);}
}
