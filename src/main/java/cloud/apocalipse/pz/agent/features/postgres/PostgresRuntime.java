package cloud.apocalipse.pz.agent.features.postgres;

import cloud.apocalipse.pz.agent.config.AgentConfiguration.PostgresSettings;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.IOException;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public linkage boundary used by advice in signed native classes. All extension state belongs to the agent. */
public final class PostgresRuntime {
    private static volatile PostgresSettings settings;
    private static PostgresConnections pool;
    private static AccountConnections accounts;
    private static boolean closed;
    private static final Set<ClassLoader> TRANSFORMED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final AtomicBoolean SHUTDOWN_INSTALLED = new AtomicBoolean();
    private PostgresRuntime() { }

    static synchronized void initialize(PostgresSettings configuration) {
        settings = configuration;
        if (configuration.enabled() && configuration.accountsEnabled() && SHUTDOWN_INSTALLED.compareAndSet(false,true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(PostgresRuntime::shutdown,"ApocBR-Postgres-Shutdown"));
        }
    }

    static synchronized void accountTransformed(ClassLoader loader) { TRANSFORMED.add(loader); }

    public static void verifyAccountHooks(ClassLoader loader) {
        try { Class.forName(PostgresModule.ACCOUNT_TYPE,false,loader); }
        catch (ClassNotFoundException failure) { throw new IllegalStateException("PostgreSQL account class is unavailable"); }
        synchronized (PostgresRuntime.class) {
            if (!TRANSFORMED.contains(loader)) {
                throw new IllegalStateException("PostgreSQL account transformation failed; server startup aborted before SQLite fallback");
            }
        }
    }

    private static synchronized AccountConnections accounts() {
        if (closed) throw new IllegalStateException("PostgreSQL runtime is closed");
        if (settings == null || !settings.enabled() || !settings.accountsEnabled()) {
            throw new IllegalStateException("PostgreSQL account hooks are disabled");
        }
        if (accounts == null) {
            try { pool = new PostgresConnections(settings); }
            catch (RuntimeException | LinkageError failure) {
                throw new IllegalStateException("PostgreSQL pool initialization failed");
            }
            accounts = new AccountConnections(pool);
        }
        return accounts;
    }

    public static Connection connection(Object ignoredNativeOwner) { return accounts().connection(); }

    public static final class Scope {
        private final AccountConnections.Call call;
        private final ForegroundDatabase login;
        private Scope(AccountConnections.Call call, ForegroundDatabase login) { this.call=call; this.login=login; }
        private void close() throws SQLException {
            try { call.close(); } finally { if (login != null) login.close(); }
        }
    }

    public static Scope enter() {
        try { return new Scope(accounts().open(),null); }
        catch (SQLException failure) { throw unavailable(failure); }
    }

    public static Scope enterAuthentication() {
        ForegroundDatabase login = ForegroundDatabase.beginLogin(settings);
        try { return new Scope(accounts().open(),login); }
        catch (SQLException | RuntimeException failure) { login.close(); return null; }
        catch (Error failure) { login.close(); throw failure; }
    }

    public static Throwable finish(Scope scope, Throwable original) {
        if (scope == null) return original;
        SQLException failure = null;
        try { ForegroundDatabase.verifySuccess(); }
        catch (SQLException failed) { failure=failed; }
        finally {
            try { scope.close(); }
            catch (SQLException failed) { if (failure == null) failure=failed; }
        }
        return original != null ? original : failure == null ? null : unavailable(failure);
    }

    private static IllegalStateException unavailable(SQLException failure) {
        return new IllegalStateException("PostgreSQL account operation failed; SQLState="
            + PostgresErrors.sanitized("account operation",failure).getSQLState());
    }

    public static Object deny(Object owner, Object result) {
        try {
            if (result == null) {
                Class<?> type = Class.forName(owner.getClass().getName()+"$LogonResult",true,owner.getClass().getClassLoader());
                result = type.getConstructor(owner.getClass()).newInstance(owner);
            }
            result.getClass().getField("authorized").setBoolean(result,false);
            result.getClass().getField("needSecondFactor").setBoolean(result,false);
            result.getClass().getField("dcReason").set(result,"AccessDenied");
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("PostgreSQL login denial contract is incompatible");
        }
    }

    public static void connect() {
        Scope scope=enter();
        Throwable failure=finish(scope,null);
        if (failure != null) throw (RuntimeException)failure;
    }

    public static void bootstrap(Object owner) {
        requireDrainedJournal(owner.getClass().getClassLoader());
        Scope scope=enterBootstrap();
        Throwable failure=null;
        try { AccountBootstrap.create(owner,connection(owner)); }
        catch (SQLException sql) { failure=unavailable(sql); }
        catch (RuntimeException | Error error) { failure=error; }
        finally { failure=finish(scope,failure); }
        if (failure != null) {
            if (failure instanceof Error error) throw error;
            throw (RuntimeException)failure;
        }
        System.out.println("[ApocBR-Agent][Postgres] Account database ready; backend=PostgreSQL.");
    }

    /** Bootstrap must expose safe failure diagnostics; login deliberately converts unavailability to denial. */
    private static Scope enterBootstrap() {
        ForegroundDatabase login=ForegroundDatabase.beginLogin(settings);
        try { return new Scope(accounts().open(),login); }
        catch (SQLException failure) {
            login.close();
            throw new IllegalStateException(PostgresErrors.sanitized("account bootstrap connection acquisition",failure)
                .getMessage()+"; SQLite fallback is disabled");
        } catch (RuntimeException failure) {
            login.close();
            throw new IllegalStateException("PostgreSQL account bootstrap unavailable during runtime/connection initialization; "
                +"SQLite fallback is disabled");
        } catch (Error failure) { login.close(); throw failure; }
    }

    private static void requireDrainedJournal(ClassLoader loader) {
        try {
            String configured=AgentConfiguration.systemProperty(AgentConfiguration.POSTGRES_JOURNAL_DIRECTORY_PROPERTY);
            Path directory;
            if (configured != null && !configured.isBlank()) directory=Path.of(configured);
            else {
                Class<?> fs=type(loader,"zombie.ZomboidFileSystem");
                Object instance=fs.getField("instance").get(null);
                String saves=(String)fs.getMethod("getSaveDir").invoke(instance);
                String server=(String)type(loader,"zombie.network.GameServer").getField("serverName").get(null);
                directory=Path.of(saves,"Multiplayer",server,"postgres-journal");
            }
            LegacyJournalGuard.requireDrained(directory);
        } catch (IOException failure) {
            throw new IllegalStateException("PostgreSQL journal cutover guard failed; pending or invalid journal must be recovered first");
        } catch (ReflectiveOperationException incompatible) {
            throw new IllegalStateException("PostgreSQL journal location contract is incompatible");
        }
    }

    public static String networkUserSteamId(Object ignoredPlayerDatabase, String ignoredDirectory,
                                            String username, String world) throws SQLException {
        try (PreparedStatement statement=connection(null).prepareStatement(
                "SELECT steamid FROM public.networkplayers WHERE world=? AND username=?")) {
            statement.setString(1,world); statement.setString(2,username);
            try (ResultSet rows=statement.executeQuery()) { return rows.next()?rows.getString(1):null; }
        }
    }

    public static boolean newAccountAllowed(Object owner, String username, long steamId) {
        Scope scope=enter();
        Throwable failure=null;
        boolean allowed=false;
        try {
            ClassLoader loader=owner.getClass().getClassLoader();
            Object options=type(loader,"zombie.network.ServerOptions").getField("instance").get(null);
            Object option=options.getClass().getField("maxAccountsPerUser").get(options);
            int maximum=((Number)option.getClass().getMethod("getValue").invoke(option)).intValue();
            boolean steam=(boolean)invokeStatic(loader,"zombie.core.znet.SteamUtils","isSteamModeEnabled",new Class<?>[0]);
            if (maximum<=0 || !steam) allowed=true;
            else {
                String identity=(String)invokeStatic(loader,"zombie.core.znet.SteamUtils","convertSteamIDToString",
                    new Class<?>[]{long.class},steamId);
                int accountCount=count("SELECT COUNT(id) FROM whitelist WHERE steamid=? AND username<>?",identity,username);
                int playerCount=count("SELECT COUNT(id) FROM public.networkplayers WHERE steamid=? AND username<>?",identity,username);
                allowed=accountCount<maximum && playerCount<maximum;
                {
                    try (PreparedStatement statement=connection(owner).prepareStatement("SELECT role FROM whitelist WHERE steamid=?")) {
                        statement.setString(1,identity);
                        try (ResultSet rows=statement.executeQuery()) {
                            while (rows.next()) {
                                Object role=invokeStatic(loader,"zombie.characters.Roles","getRoleById",new Class<?>[]{int.class},rows.getInt(1));
                                if (role == null) throw new SQLException("Unknown persisted account role","55000");
                                Class<?> capability=type(loader,"zombie.characters.Capability");
                                Object priority=capability.getField("PriorityLogin").get(null);
                                if ((boolean)role.getClass().getMethod("hasCapability",capability).invoke(role,priority)) { allowed=true; break; }
                            }
                        }
                    }
                }
            }
        } catch (SQLException sql) { ForegroundDatabase.failed(sql); failure=unavailable(sql); }
        catch (ReflectiveOperationException incompatible) { failure=new IllegalStateException("PostgreSQL account-limit contract is incompatible"); }
        catch (RuntimeException | Error error) { failure=error; }
        finally { failure=finish(scope,failure); }
        if (failure != null) {
            if (failure instanceof Error error) throw error;
            throw (RuntimeException)failure;
        }
        return allowed;
    }

    private static int count(String sql,String identity,String username) throws SQLException {
        try (PreparedStatement statement=connection(null).prepareStatement(sql)) {
            statement.setString(1,identity); statement.setString(2,username);
            try (ResultSet rows=statement.executeQuery()) { return rows.next()?rows.getInt(1):0; }
        }
    }

    static Class<?> type(ClassLoader loader,String name) throws ClassNotFoundException { return Class.forName(name,true,loader); }
    static Object invokeStatic(ClassLoader loader,String type,String method,Class<?>[] signature,Object... arguments)
            throws ReflectiveOperationException {
        try { return type(loader,type).getMethod(method,signature).invoke(null,arguments); }
        catch (InvocationTargetException wrapper) {
            if (wrapper.getCause() instanceof RuntimeException failure) throw failure;
            if (wrapper.getCause() instanceof Error failure) throw failure;
            throw wrapper;
        }
    }

    public static synchronized void shutdown() {
        closed=true;
        if (pool != null) { pool.close(); pool=null; }
        accounts=null;
    }

    public static Throwable shutdownAfter(Throwable original) {
        try { shutdown(); }
        catch (RuntimeException failure) {
            if (original == null) return failure;
            original.addSuppressed(failure);
        }
        return original;
    }

    static synchronized void installForTest(PostgresSettings configuration,PostgresConnections testPool) {
        if (pool != null) pool.close();
        settings=configuration; pool=testPool; accounts=new AccountConnections(testPool); closed=false;
        TRANSFORMED.clear();
    }
}
