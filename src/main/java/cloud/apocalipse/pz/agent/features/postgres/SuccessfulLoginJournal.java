package cloud.apocalipse.pz.agent.features.postgres;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.lua.login.ApocBRPlayerLogin;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Login audit has its own journal generation; never consumes an unported legacy operation. */
public final class SuccessfulLoginJournal {
    public record Login(UUID event,long loggedAt,int server,String world,String identity,boolean coop,
                        int slot,String username,String steam,UUID character) implements PersistenceJournal.Operation {
        public Login {
            if(event==null||loggedAt<=0||server<1||world==null||world.isBlank()||identity==null||identity.isBlank()
                    ||slot<0||slot>3||username==null||username.isBlank()) throw new IllegalArgumentException("Invalid login audit");
        }
        public String kind(){return "successful-login-v1";}
        public long estimatedBytes(){return 1024L+2L*(world.length()+identity.length()+username.length()+(steam==null?0:steam.length()));}
    }
    private static SuccessfulLoginJournal instance;
    private final PersistenceJournal journal;
    private final Thread worker;
    private volatile boolean stopping;
    private SuccessfulLoginJournal(Path directory) throws java.io.IOException {
        journal=new PersistenceJournal(directory,1_048_576,code->System.out.println("[ApocBR-Agent][LoginHistory] "+code));
        worker=Thread.ofPlatform().daemon().name("ApocBR-Login-History").unstarted(this::run);
        worker.start();
    }
    public static synchronized void start(Path directory) {
        if(instance!=null) return;
        try { instance=new SuccessfulLoginJournal(directory); }
        catch(java.io.IOException failure){throw new IllegalStateException("Login journal initialization failed",failure);}
    }
    public static synchronized void submit(Login login) {
        if(instance==null||instance.stopping) throw new IllegalStateException("Login audit unavailable");
        try { instance.journal.submit(login); }
        catch(java.io.IOException failure){throw new IllegalStateException("Login audit capture failed",failure);}
    }
    private void run() {
        while(!stopping) {
            try {
                journal.flush();
                var head=journal.head();
                if(head==null){TimeUnit.MILLISECONDS.sleep(100);continue;}
                if(!(head.operation() instanceof Login login))throw new IllegalStateException("Unexpected login journal operation");
                int previous;
                try(Connection c=PostgresRuntime.borrowBackground()) {
                    try { previous=record(c,login);c.commit(); }
                    catch(SQLException|RuntimeException failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
                }
                journal.acknowledgeCommitted(head.id());
                ApocBRPlayerLogin.recorded(login.event(),previous);
            } catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
            catch(Exception failure) {
                if(stopping)return;
                System.err.println("[ApocBR-Agent][LoginHistory] Replay failed; preserving pending audit");
                try{TimeUnit.SECONDS.sleep(5);}catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
            }
        }
    }
    static int record(Connection c,Login login)throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT player_rel.record_successful_login(?,?,?,?,?,?,?,?,?,?)")) {
            s.setQueryTimeout(2);s.setObject(1,login.event());s.setTimestamp(2,new Timestamp(login.loggedAt()));
            s.setInt(3,login.server());s.setString(4,login.world());s.setString(5,login.coop()?"steam_coop":"username");
            s.setString(6,login.identity());s.setInt(7,login.slot());s.setString(8,login.username());
            s.setString(9,login.steam());s.setObject(10,login.character());
            try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Missing login decision","02000");return r.getInt(1);}
        }
    }
    public static synchronized void shutdown() {
        var current=instance;if(current==null)return;
        current.stopping=true;current.worker.interrupt();
        try {
            if(!current.worker.join(java.time.Duration.ofSeconds(12)))throw new IllegalStateException("Login audit worker failed to stop");
            current.journal.close();instance=null;
        }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted login audit shutdown");}
        catch(java.io.IOException failure){throw new IllegalStateException("Login journal close failed",failure);}
    }
    private SuccessfulLoginJournal(){throw new AssertionError();}
}
