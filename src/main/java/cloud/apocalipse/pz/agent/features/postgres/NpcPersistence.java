package cloud.apocalipse.pz.agent.features.postgres;

import cloud.apocalipse.pz.agent.features.npc.persistence.ApocBRHumanSales;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;

/** Durable NPC SQL/sales coordinator. Captures never acknowledge unapplied work. */
public final class NpcPersistence {
    private static volatile NpcPersistence instance;
    private final PersistenceJournal journal;
    private final Thread worker;
    private volatile boolean stopping,reachable;
    private NpcPersistence(Path directory)throws java.io.IOException {
        journal=new PersistenceJournal(directory,4*1024*1024,s->System.out.println("[ApocBR-Agent][NPC-Journal] "+s));
        worker=new Thread(this::run,"ApocBR-NPC-Journal");worker.setDaemon(true);worker.start();
    }
    public static synchronized void start(Path directory){
        if(instance!=null)throw new IllegalStateException("NPC journal already started");
        try{instance=new NpcPersistence(directory);}
        catch(java.io.IOException failure){throw new IllegalStateException("NPC journal unavailable; pending records preserved",failure);}
    }
    public static boolean enabled(){return instance!=null&&!instance.stopping;}
    public static boolean journaling(){return enabled();}
    public static boolean reachable(){var current=instance;return current!=null&&!current.stopping&&current.reachable;}
    public static UUID submit(PersistenceJournal.Operation operation){return submitDurable(operation);}
    public static UUID submitDurable(PersistenceJournal.Operation operation){
        var current=instance;if(current==null||current.stopping)throw new IllegalStateException("NPC journal unavailable");
        try{return current.journal.submitDurable(operation);}
        catch(java.io.IOException failure){current.reachable=false;throw new IllegalStateException("NPC durable capture failed",failure);}
    }
    private void run(){
        while(!stopping){
            try{
                var entry=journal.head();
                if(entry==null){
                    try(var c=PostgresRuntime.borrowBackground()){
                        if(!c.isValid(2))throw new SQLException("NPC database unavailable","08006");
                        verifySchema(c);
                        c.rollback();
                    }
                    reachable=true;Thread.sleep(500);continue;
                }
                replay(entry);journal.acknowledgeCommitted(entry.id());
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
            catch(Exception failure){
                reachable=false;
                System.err.println("[ApocBR-Agent][NPC-Journal] Replay unavailable; preserving pending work; issue="
                    +(failure instanceof SQLException sql?sql.getSQLState():failure.getClass().getSimpleName()));
                try{Thread.sleep(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
            }
        }
        reachable=false;
    }
    static void verifySchema(Connection connection)throws SQLException {
        try(var statement=connection.prepareStatement("SELECT operation_id FROM public.apocbr_persistence_receipt WHERE false")){
            statement.setQueryTimeout(2);try(var ignored=statement.executeQuery()){}
        }
        ApocBRHumanSales.validate(connection);
    }
    private static void replay(PersistenceJournal.Entry entry)throws SQLException {
        var operation=entry.operation() instanceof PersistenceOperations.Account account?account
            :entry.operation() instanceof ApocBRHumanSales.Sale sale?ApocBRHumanSales.operation(sale):null;
        if(operation==null)throw new SQLException("Unregistered NPC journal operation","0A000");
        try(var c=PostgresRuntime.borrowBackground()){
            try{apply(c,entry.id(),operation);c.commit();}
            catch(SQLException|RuntimeException failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }
    }
    static void apply(Connection c,UUID id,PersistenceOperations.Account operation)throws SQLException {
        try(var receipt=c.prepareStatement("INSERT INTO public.apocbr_persistence_receipt(operation_id) VALUES (?) ON CONFLICT(operation_id) DO NOTHING")){
            receipt.setQueryTimeout(2);receipt.setObject(1,id);if(receipt.executeUpdate()==0)return;
        }
        for(var sql:operation.statements())try(var statement=c.prepareStatement(sql.sql())){
            statement.setQueryTimeout(2);
            for(int i=0;i<sql.parameters().size();i++)statement.setObject(i+1,sql.parameters().get(i));
            statement.executeUpdate();
        }
    }
    public static synchronized void shutdown(){
        var current=instance;if(current==null)return;
        current.stopping=true;current.worker.interrupt();
        try{
            current.worker.join(10000);
            if(current.worker.isAlive())throw new IllegalStateException("NPC journal worker did not stop");
            current.journal.close();instance=null;
        }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("NPC journal shutdown interrupted",failure);}
        catch(java.io.IOException failure){throw new IllegalStateException("NPC journal close failed",failure);}
    }
}
