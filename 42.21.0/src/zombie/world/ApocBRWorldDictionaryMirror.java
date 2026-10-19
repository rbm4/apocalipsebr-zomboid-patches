package zombie.world;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.postgresql.PGConnection;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.debug.DebugLog;
import zombie.network.GameServer;
import zombie.savefile.ApocBRPostgresConnector;
import zombie.savefile.ApocBRPayloadProvenance;
import zombie.savefile.ApocBRWorldDictionaryDatabase;

/** Publish the completed local vanilla dictionary; observe foreign publication metadata only. */
public final class ApocBRWorldDictionaryMirror {
    private static final ApocBRWorldDictionaryDatabase DATABASE=new ApocBRWorldDictionaryDatabase();
    private static volatile Listener listener;
    private static volatile ApocBRWorldDictionaryDatabase.Published local;
    private static volatile List<ApocBRWorldDictionaryDatabase.Published> foreign=List.of();
    static boolean enabled(){return GameServer.server&&Boolean.getBoolean("apocbr.postgres.enabled")
            &&Boolean.parseBoolean(System.getProperty("apocbr.postgres.dictionary.enabled","true"))&&!Core.getInstance().isNoSave();}
    static void afterRebuild(DictionaryData data)throws Exception {
        if(!enabled())return;
        int server=ApocBRPayloadProvenance.configuredServerId();
        String save=key(System.getProperty("apocbr.postgres.dictionary.saveKey",Core.gameSaveWorld));
        String configured=System.getProperty("apocbr.postgres.dictionary.namespaceId");
        UUID namespace=configured==null?UUID.nameUUIDFromBytes(("vanilla-dictionary:server-id:"+server+":"+save).getBytes(StandardCharsets.UTF_8)):UUID.fromString(configured);
        // Namespace name is independent of mutable display/server labels; the repository checks ownership.
        var origin=new ApocBRWorldDictionaryDatabase.Origin(namespace,"vanilla:"+namespace,server,save);
        var snapshot=ApocBRWorldDictionarySnapshot.capture(data);
        local=publish(DATABASE,origin,snapshot,Path.of(ZomboidFileSystem.instance.getFileNameInCurrentSave("ApocBRWorldDictionaryNamespace.txt")));
        ApocBRWorldDictionaryDecode.install(new ApocBRPayloadProvenance(server,local.namespace(),local.view()),snapshot);
        ApocBRPayloadProvenance.published(new ApocBRPayloadProvenance(server,local.namespace(),local.view()));
        log("PUBLISHED; serverId="+server+"; namespace="+local.namespace()+"; view="+local.view()+"; revision="+local.revision()+"; entries="+snapshot.entries().size());
        start(server);
    }
    static ApocBRWorldDictionaryDatabase.Published publish(ApocBRWorldDictionaryDatabase database,
            ApocBRWorldDictionaryDatabase.Origin configured,ApocBRWorldDictionarySnapshot snapshot,Path marker)throws SQLException,IOException {
        UUID namespace=configured.namespace();
        if(Files.exists(marker)) {
            List<String> saved=Files.readAllLines(marker,StandardCharsets.UTF_8);
            // A changed configured namespace deliberately starts a separate history.
            if(saved.size()==2&&saved.get(0).equals(configured.namespace().toString()))namespace=UUID.fromString(saved.get(1));
        }
        var origin=new ApocBRWorldDictionaryDatabase.Origin(namespace,"vanilla:"+namespace,configured.serverId(),configured.save());
        try{return database.publish(origin,snapshot);}
        catch(ApocBRWorldDictionaryDatabase.HistoryChangedException changed) {
            UUID replacement=UUID.randomUUID();
            // Persist before publication so a failed DB attempt/restart reuses this incarnation.
            Path temporary=Files.createTempFile(marker.toAbsolutePath().getParent(),"dictionary-namespace-",".tmp");
            try {
                Files.writeString(temporary,configured.namespace()+"\n"+replacement+"\n",StandardCharsets.UTF_8);
                try{Files.move(temporary,marker,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,marker,StandardCopyOption.REPLACE_EXISTING);}
            }finally{Files.deleteIfExists(temporary);}
            log("HISTORY_REPLACED; serverId="+configured.serverId()+"; previousNamespace="+namespace+"; namespace="+replacement);
            return database.publish(new ApocBRWorldDictionaryDatabase.Origin(replacement,"vanilla:"+replacement,configured.serverId(),configured.save()),snapshot);
        }
    }
    private static String key(String key){if(key==null||key.isBlank()||key.length()>128)throw new IllegalArgumentException("Dictionary save key must have 1..128 characters");return key;}
    public static ApocBRWorldDictionaryDatabase.Published localView(){return local;}
    public static List<ApocBRWorldDictionaryDatabase.Published> foreignViews(){return foreign;}
    private static synchronized void start(int server) {
        if(listener!=null&&listener.server==server&&listener.thread.isAlive())return;
        shutdown();listener=new Listener(server);ApocBRPostgresConnector.setDictionaryShutdown(ApocBRWorldDictionaryMirror::shutdown);listener.thread.start();
    }
    public static synchronized void shutdown() {
        Listener current=listener;listener=null;
        if(current!=null){current.stopping.set(true);current.thread.interrupt();Connection connection=current.connection;if(connection!=null)try{connection.close();}catch(SQLException ignored){} }
    }
    private static void log(String value){DebugLog.log("[ApocBR][Dictionary] "+value);}
    private static final class Listener implements Runnable {
        final int server;final AtomicBoolean stopping=new AtomicBoolean();final Thread thread;
        volatile Connection connection;long lastFailure;
        Listener(int server){this.server=server;thread=new Thread(this,"ApocBR-dictionary-listener");thread.setDaemon(true);}
        public void run() {
            while(!stopping.get()) {
                try(Connection session=ApocBRPostgresConnector.openDictionaryListenerConnection()) {
                    connection=session;
                    try(Statement statement=session.createStatement()){statement.setQueryTimeout(5);statement.execute("LISTEN "+ApocBRWorldDictionaryDatabase.CHANNEL);}
                    // LISTEN is committed first; reconcile afterwards, including every reconnect gap.
                    reconcile();long next=System.nanoTime()+60_000_000_000L;
                    PGConnection pg=session.unwrap(PGConnection.class);
                    while(!stopping.get()) {
                        var notifications=pg.getNotifications(1000);
                        if(stopping.get())break;
                        if((notifications!=null&&notifications.length>0)||System.nanoTime()>=next){reconcile();next=System.nanoTime()+60_000_000_000L;}
                    }
                }catch(SQLException|RuntimeException failure) {
                    if(stopping.get())break;
                    long now=System.nanoTime();if(lastFailure==0||now-lastFailure>30_000_000_000L){lastFailure=now;log("LISTENER_RETRY; code="+(failure instanceof SQLException sql?sql.getSQLState():"INVALID_STATE"));}
                    try{Thread.sleep(1000);}catch(InterruptedException interrupted){if(stopping.get())break;}
                }finally{connection=null;}
            }
        }
        private void reconcile()throws SQLException {
            var discovered=DATABASE.foreignHeads(server);
            for(var view:discovered)ApocBRWorldDictionaryDecode.prepare(new ApocBRPayloadProvenance(view.serverId(),view.namespace(),view.view()));
            synchronized(ApocBRWorldDictionaryMirror.class) {
                if(stopping.get()||listener!=this)return;
                if(!discovered.equals(foreign)) {
                    foreign=discovered;
                    log("FOREIGN_VIEWS_CHANGED; views="+discovered.size()+"; decodeInstallation=true");
                }
            }
        }
    }
    private ApocBRWorldDictionaryMirror(){}
}
