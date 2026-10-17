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
    private static final ThreadLocal<UUID> operation=new ThreadLocal<>();
    private static final ThreadLocal<Boolean> background=ThreadLocal.withInitial(()->false);
    private final ApocBRPersistenceJournal journal;
    private volatile State state=State.STARTING;
    private volatile boolean startup=true,draining,closed,inFlight;
    private volatile long lastProgress;
    private volatile UUID replayOperation;
    private volatile String replayKind="NONE",lastIssue="NONE";
    private final Thread writer,replay;

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
        String sqlState=error.getSQLState();
        if(sqlState!=null&&(sqlState.startsWith("08")||sqlState.equals("57P01")||sqlState.equals("57P02")||sqlState.equals("57P03"))
                ||error instanceof SQLTransientConnectionException||error instanceof SQLRecoverableException) {
            ApocBRPersistence current=instance;if(current!=null)current.transition(current.draining?State.DRAINING:State.UNAVAILABLE);
        }
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
    static void drain() {if(!enabled())return;ApocBRPersistence current=get();current.draining=true;current.transition(State.DRAINING);current.awaitDrain();}
    static void shutdownWork(Runnable work) {
        if(!enabled()){work.run();return;}
        ApocBRPersistence current=get();
        for(;;) {
            drain();
            try{work.run();drain();return;}
            catch(RuntimeException failure) {
                boolean databaseFailure=false;Throwable cause=failure;
                for(int depth=0;cause!=null&&depth<8;depth++,cause=cause.getCause())if(cause instanceof SQLException
                    ||cause instanceof IllegalStateException&&cause.getMessage()!=null&&cause.getMessage().startsWith("PostgreSQL ")){databaseFailure=true;break;}
                if(!databaseFailure)throw failure;
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
                if(state!=State.AVAILABLE||startup||draining)probe();
                if(!startup&&!draining&&state!=State.AVAILABLE)transition(State.RECOVERING);
                ApocBRPersistenceJournal.Entry entry=journal.head();
                if(entry!=null) {
                    inFlight=true;operation.set(entry.id());replayOperation=entry.id();replayKind=entry.operation().kind();
                    try{ApocBRPersistenceOperations.apply(entry.operation());journal.acknowledge(entry.id());report("REPLAY_PROGRESS",null);}
                    finally{operation.remove();inFlight=false;}
                } else {
                    if(!startup&&!draining&&journal.empty())transition(State.AVAILABLE);
                    sleep(50);
                }
                delay=250;
            }catch(SQLException error){lastIssue=error.getClass().getName();failed(error);if(state!=State.UNAVAILABLE&&!draining)transition(State.RECOVERING);report("REPLAY_DEFERRED",error.getSQLState());sleep(delay);delay=Math.min(30_000,delay*2);}
            catch(IOException|RuntimeException|LinkageError error){lastIssue=error.getClass().getName();if(!draining)transition(State.RECOVERING);report("REPLAY_BLOCKED",null);sleep(1000);}
        }
    }
    private void probe()throws SQLException {
        try(Connection connection=ApocBRPostgresConnector.borrowPlayerConnection();
            PreparedStatement statement=connection.prepareStatement("SELECT operation_id FROM public.apocbr_persistence_receipt WHERE false")) {
            statement.setQueryTimeout(2);try(ResultSet ignored=statement.executeQuery()){}
            connection.rollback();
        }
    }
    private void awaitDrain() {
        // Also probe with an empty journal: reachable PostgreSQL plus a valid receipt schema
        // is required before startup/admission or clean shutdown can complete.
        boolean interrupted=false;
        for(;;) {
            try {
                background.set(true);probe();
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
            +"; inFlight="+inFlight+"; operation="+replayOperation+"; kind="+replayKind+"; issue="+lastIssue
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
