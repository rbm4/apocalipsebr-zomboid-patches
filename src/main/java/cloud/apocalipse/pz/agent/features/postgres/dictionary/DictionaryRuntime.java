package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.postgresql.PGConnection;
import zombie.ZomboidFileSystem;
import zombie.core.Core;

import zombie.network.GameServer;
import cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime;
import cloud.apocalipse.pz.agent.config.AgentConfiguration.DictionarySettings;



/** Publish vanilla allocation history, then prepare exact foreign views without changing native allocation. */
public final class DictionaryRuntime {
    private static final ThreadLocal<Listener> READER=new ThreadLocal<>();
    private static final DictionaryDatabase DATABASE=new DictionaryDatabase();
    private static volatile Listener listener;
    private static volatile DictionaryDatabase.Published local;
    private static volatile List<DictionaryDatabase.Published> foreign=List.of();
    private static volatile DictionarySettings settings;
    static void initialize(DictionarySettings configuration) {
        settings=configuration;
        if(configuration.enabled()) PostgresRuntime.beforeShutdown(DictionaryRuntime::shutdown);
    }
    static boolean enabled(){return settings!=null&&settings.enabled()&&GameServer.server&&!Core.getInstance().isNoSave();}
    public static void afterRebuild(Object data)throws Exception {
        if(!enabled())return;
        int server=settings.serverId();
        if(server<1)throw new IllegalArgumentException("Set apocbr.postgres.serverId to a registered positive player_rel.server.id");
        String save=key(settings.saveKey()==null?Core.gameSaveWorld:settings.saveKey());
        UUID namespace=settings.namespace()==null?UUID.nameUUIDFromBytes(("vanilla-dictionary:server-id:"+server+":"+save).getBytes(StandardCharsets.UTF_8)):settings.namespace();
        // Namespace name is independent of mutable display/server labels; the repository checks ownership.
        var origin=new DictionaryDatabase.Origin(namespace,"vanilla:"+namespace,server,save);
        var snapshot=DictionarySnapshot.capture(data);
        local=publish(DATABASE,origin,snapshot,Path.of(ZomboidFileSystem.instance.getFileNameInCurrentSave("ApocBRWorldDictionaryNamespace.txt")));
        DictionaryCache.install(new DictionaryOrigin(server,local.namespace(),local.view()),snapshot);

        log("PUBLISHED; serverId="+server+"; namespace="+local.namespace()+"; view="+local.view()+"; revision="+local.revision()+"; entries="+snapshot.entries().size());
        start(server);
    }
    static DictionaryDatabase.Published publish(DictionaryDatabase database,
            DictionaryDatabase.Origin configured,DictionarySnapshot snapshot,Path marker)throws SQLException,IOException {
        UUID namespace=configured.namespace();
        if(Files.exists(marker)) {
            List<String> saved=Files.readAllLines(marker,StandardCharsets.UTF_8);
            // A changed configured namespace deliberately starts a separate history.
            if(saved.size()==2&&saved.get(0).equals(configured.namespace().toString()))namespace=UUID.fromString(saved.get(1));
        }
        var origin=new DictionaryDatabase.Origin(namespace,"vanilla:"+namespace,configured.serverId(),configured.save());
        try{return database.publish(origin,snapshot);}
        catch(DictionaryDatabase.HistoryChangedException|DictionaryDatabase.BuildChangedException changed) {
            UUID replacement=UUID.randomUUID();
            // Persist before publication so a failed DB attempt/restart reuses this incarnation.
            Path temporary=Files.createTempFile(marker.toAbsolutePath().getParent(),"dictionary-namespace-",".tmp");
            try {
                Files.writeString(temporary,configured.namespace()+"\n"+replacement+"\n",StandardCharsets.UTF_8);
                try{Files.move(temporary,marker,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,marker,StandardCopyOption.REPLACE_EXISTING);}
            }finally{Files.deleteIfExists(temporary);}
            log((changed instanceof DictionaryDatabase.BuildChangedException?"BUILD_REPLACED":"HISTORY_REPLACED")
                +"; serverId="+configured.serverId()+"; previousNamespace="+namespace+"; namespace="+replacement);
            return database.publish(new DictionaryDatabase.Origin(replacement,"vanilla:"+replacement,configured.serverId(),configured.save()),snapshot);
        }
    }
    private static String key(String key){if(key==null||key.isBlank()||key.length()>128)throw new IllegalArgumentException("Dictionary save key must have 1..128 characters");return key;}
    public static DictionaryDatabase.Published localView(){return local;}
    public static List<DictionaryDatabase.Published> foreignViews(){return foreign;}
    private static void start(int server) {
        if(listener!=null&&listener.server==server&&listener.thread.isAlive())return;
        stopListener();listener=new Listener(server);listener.thread.start();
    }
    private static void stopListener() {
        Listener current;
        synchronized(DictionaryRuntime.class){current=listener;listener=null;}
        if(current!=null) {
            current.stopping.set(true);current.thread.interrupt();
            Connection connection=current.connection;
            if(connection!=null)try{connection.close();}catch(SQLException ignored){}
            Connection query=current.query;
            if(query!=null)try{query.close();}catch(SQLException ignored){}
            if(Thread.currentThread()!=current.thread) {
                try{current.thread.join(6000);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}
                if(current.thread.isAlive())throw new IllegalStateException("Dictionary listener did not stop");
            }
        }
    }
    public static void shutdown() {
        try{stopListener();}finally{local=null;foreign=List.of();DictionaryCache.clear();PayloadProvenance.clear();}
    }
    public static DictionaryOrigin localEncoding() {
        var published=local;return published==null?null:new DictionaryOrigin(published.serverId(),published.namespace(),published.view());
    }
    public static int publicationTimeoutSeconds(){return settings==null?30:settings.publicationTimeoutSeconds();}
    public static Connection borrow()throws SQLException {
        Connection connection=PostgresRuntime.borrowBackground();
        try{
            Listener reader=READER.get();
            if(reader!=null) {
                reader.query=connection;
                if(reader.stopping.get())throw new SQLException("Dictionary reader stopped","08003");
            }
            connection.setNetworkTimeout(Runnable::run,(publicationTimeoutSeconds()+15)*1000);return connection;
        }
        catch(SQLException|RuntimeException failure){try{connection.close();}catch(SQLException ignored){}throw failure;}
    }
    static void log(String value){System.out.println("[ApocBR-Agent][Dictionary] "+value);}
    private static final class Listener implements Runnable {
        final int server;final AtomicBoolean stopping=new AtomicBoolean();final Thread thread;
        volatile Connection connection,query;long lastFailure;
        Listener(int server){this.server=server;thread=new Thread(this,"ApocBR-dictionary-listener");thread.setDaemon(true);}
        public void run() {
            READER.set(this);
            try {
            while(!stopping.get()) {
                try(Connection session=PostgresRuntime.openDictionaryListener()) {
                    connection=session;
                    try(Statement statement=session.createStatement()){statement.setQueryTimeout(5);statement.execute("LISTEN "+DictionaryDatabase.CHANNEL);}
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
            } finally {READER.remove();}
        }
        private void reconcile()throws SQLException {
            var discovered=DATABASE.foreignHeads(server);
            for(var view:discovered) {
                if(stopping.get())return;
                DictionaryCache.prepare(new DictionaryOrigin(view.serverId(),view.namespace(),view.view()),DATABASE);
            }
            synchronized(DictionaryRuntime.class) {
                if(stopping.get()||listener!=this)return;
                if(!discovered.equals(foreign)) {
                    foreign=discovered;
                    log("FOREIGN_VIEWS_CHANGED; views="+discovered.size()+"; exactViewsPrepared=true");
                }
            }
        }
    }
    private DictionaryRuntime(){}
}
