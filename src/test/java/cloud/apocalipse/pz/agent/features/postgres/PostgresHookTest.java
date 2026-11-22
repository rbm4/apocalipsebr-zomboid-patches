package cloud.apocalipse.pz.agent.features.postgres;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.config.AgentConfiguration.PostgresSettings;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static net.bytebuddy.matcher.ElementMatchers.*;

class PostgresHookTest {
    static final PostgresSettings SETTINGS=new PostgresSettings(true,true,"jdbc:postgresql://localhost/fixture",2,5000,3000);
    private final Map<String,String> previous=new HashMap<>();

    void property(String key,String value) {
        previous.putIfAbsent(key,System.getProperty(key));
        System.setProperty(key,value);
    }

    @AfterEach void cleanup() {
        PostgresRuntime.shutdown();
        zombie.characters.Roles.role.priority=false;
        zombie.characters.Roles.initialized=0;
        previous.forEach((key,value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key,value); });
    }

    static final class Database {
        int borrowed,returned,shutdowns;
        int networkTimeoutMillis,queryTimeoutSeconds;
        boolean failQuery,failBorrow,failBorrowRuntime;
        boolean adminExists=true;
        int countValue;
        final java.util.ArrayList<String> writes=new java.util.ArrayList<>();
        final java.util.ArrayList<Map<Integer,Object>> boundWrites=new java.util.ArrayList<>();
        final Map<Integer,Object> parameters=new HashMap<>();
        Object zero(Class<?> type) {
            if (type==boolean.class) return false;
            if (type==int.class) return 0;
            return null;
        }
        DataSource source() {
            return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a) -> {
                if (m.getName().equals("getConnection")) {
                    if (failBorrowRuntime) throw new IllegalStateException("PRIVATE_DRIVER_CONFIG");
                    if (failBorrow) throw new SQLException("PRIVATE_CREDENTIAL","08006");
                    borrowed++; return connection();
                }
                return zero(m.getReturnType());
            });
        }
        Connection connection() {
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a) -> {
                switch (m.getName()) {
                    case "close": returned++; return null;
                    case "prepareStatement": return statement((String)a[0]);
                    case "setNetworkTimeout": networkTimeoutMillis=(Integer)a[1]; return null;
                    default: return zero(m.getReturnType());
                }
            });
        }
        PreparedStatement statement(String sql) {
            var local=new HashMap<Integer,Object>();
            return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a) -> {
                if (m.getName().equals("setQueryTimeout")) { queryTimeoutSeconds=(Integer)a[0]; return null; }
                if (m.getName().equals("setInt") || m.getName().equals("setString")) {
                    parameters.put((Integer)a[0],a[1]); local.put((Integer)a[0],a[1]); return null;
                }
                if (m.getName().equals("executeUpdate")) {
                    if (failQuery) throw new SQLException("PRIVATE_ROW","23505");
                    writes.add(sql); boundWrites.add(new HashMap<>(local));
                    if (sql.startsWith("INSERT INTO whitelist")) adminExists=true;
                    return 1;
                }
                if (m.getName().equals("executeQuery")) {
                    if (failQuery) throw new SQLException("PRIVATE_ROW","08006");
                    boolean[] first={ !sql.equals("SELECT id FROM whitelist WHERE username=?") || adminExists };
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(rp,rm,ra) -> {
                        if (rm.getName().equals("next")) { boolean value=first[0]; first[0]=false; return value; }
                        if (rm.getName().equals("getInt")) return countValue;
                        return zero(rm.getReturnType());
                    });
                }
                return zero(m.getReturnType());
            });
        }
        PostgresConnections pool() { return new PostgresConnections(source(),() -> shutdowns++); }
    }

    static final class Loader extends ClassLoader {
        final Map<String,byte[]> definitions=new HashMap<>();
        Loader() { super(PostgresHookTest.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (!definitions.containsKey(name)) return super.loadClass(name,resolve);
                Class<?> type=findLoadedClass(name);
                if (type==null) { byte[] bytes=definitions.get(name); type=defineClass(name,bytes,0,bytes.length); }
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }

    public static final class GracefulQuitFixture {
        public static Object owner;
        public void QueuedQuit() {
            try {
                owner.getClass().getMethod("saveRole").invoke(owner);
            } catch (InvocationTargetException failure) {
                throw new IllegalStateException(failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    Object instrument(Database database) throws Exception {
        property(AgentConfiguration.POSTGRES_ENABLED_PROPERTY,"true");
        property(AgentConfiguration.POSTGRES_ACCOUNTS_ENABLED_PROPERTY,"true");
        property(AgentConfiguration.POSTGRES_URL_PROPERTY,SETTINGS.jdbcUrl());
        property(AgentConfiguration.POSTGRES_JOURNAL_DIRECTORY_PROPERTY,
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"apocbr-hook-"+java.util.UUID.randomUUID()).toString());
        PostgresModule module=new PostgresModule();
        module.initialize();
        PostgresRuntime.installForTest(SETTINGS,database.pool());
        Loader loader=new Loader();
        try (var locator=ClassFileLocator.ForClassLoader.of(PostgresHookTest.class.getClassLoader())) {
            byte[] original=locator.locate(PostgresModule.ACCOUNT_TYPE).resolve();
            loader.definitions.put(PostgresModule.ACCOUNT_TYPE,original);
            loader.definitions.put(PostgresModule.ACCOUNT_TYPE+"$LogonResult",locator.locate(PostgresModule.ACCOUNT_TYPE+"$LogonResult").resolve());
            var transformer=module.configure(new AgentBuilder.Default().disableClassFormatChanges()).makeRaw();
            byte[] transformed=transformer.transform(loader,PostgresModule.ACCOUNT_TYPE.replace('.','/'),null,null,original);
            assertNotNull(transformed,"Registered module must transform the account class");
            loader.definitions.put(PostgresModule.ACCOUNT_TYPE,transformed);
        }
        Object owner=loader.loadClass(PostgresModule.ACCOUNT_TYPE).getConstructor().newInstance();
        PostgresRuntime.verifyAccountHooks(loader);
        return owner;
    }

    @Test void registeredHooksRouteNestedReadsWithoutAssigningNativeConnection() throws Exception {
        Database database=new Database();
        Object owner=instrument(database);
        assertEquals(true,owner.getClass().getMethod("containsUser",String.class).invoke(owner,"fixture-user"));
        assertEquals(1,database.borrowed);
        assertEquals(1,database.returned);
        assertEquals(1,owner.getClass().getField("originalCalls").getInt(owner));
        var field=owner.getClass().getDeclaredField("conn"); field.setAccessible(true);
        assertNull(field.get(owner));
        assertEquals("native-pure-method",owner.getClass().getMethod("getTOTPCode",String.class).invoke(owner,"fixture"));
        assertEquals(1,database.borrowed);
    }

    @Test void registeredBootstrapConnectAndCloseBypassSqliteAndPoolClosesAtShutdown() throws Exception {
        Database database=new Database();
        Object owner=instrument(database);
        owner.getClass().getMethod("create").invoke(owner);
        owner.getClass().getMethod("connect").invoke(owner);
        owner.getClass().getMethod("close").invoke(owner);
        assertEquals(2,database.borrowed);
        assertEquals(2,database.returned);
        assertEquals(0,database.shutdowns);
        PostgresRuntime.shutdown();
        assertEquals(1,database.shutdowns);
    }

    @Test void authenticationDeniesCaughtSqlFailureAndAcquisitionFailure() throws Exception {
        Database database=new Database();
        Object owner=instrument(database);
        var authenticate=owner.getClass().getMethod("authClient",String.class,String.class,String.class,long.class,int.class);
        Object success=authenticate.invoke(owner,"fixture","hash","127.0.0.1",0L,1);
        assertTrue(success.getClass().getField("authorized").getBoolean(success));
        database.failQuery=true;
        Object denied=authenticate.invoke(owner,"fixture","hash","127.0.0.1",0L,1);
        assertFalse(denied.getClass().getField("authorized").getBoolean(denied));
        assertEquals("AccessDenied",denied.getClass().getField("dcReason").get(denied));
        database.failBorrow=true;
        Object unavailable=authenticate.invoke(owner,"fixture","hash","127.0.0.1",0L,1);
        assertFalse(unavailable.getClass().getField("authorized").getBoolean(unavailable));
        assertEquals(2,owner.getClass().getField("originalCalls").getInt(owner));
        assertEquals(database.borrowed,database.returned);
    }

    @Test void writesKeepNativeBodiesAndUsePostgresqlNumericBindingsWithoutFalseSuccess() throws Exception {
        Database database=new Database();
        Object owner=instrument(database);
        var change=owner.getClass().getMethod("changeUsername",String.class,String.class);
        assertEquals("native-body-completed",change.invoke(owner,"before","after"));
        assertEquals(17,database.parameters.get(2));
        assertEquals("native-body-completed",owner.getClass().getMethod("addUser",String.class,String.class).invoke(owner,"user","hash"));
        assertEquals(3,database.parameters.get(5));
        database.failQuery=true;
        var failure=assertThrows(InvocationTargetException.class,() -> change.invoke(owner,"before","after"));
        assertInstanceOf(IllegalStateException.class,failure.getCause());
        assertFalse(failure.getCause().toString().contains("PRIVATE"));
        assertEquals(database.borrowed,database.returned);
    }

    @Test void quitHookGivesRolePersistenceBoundedGraceAndRestoresOrdinaryBudgets() throws Exception {
        Database database=new Database();
        Object owner=instrument(database);
        var saveRole=owner.getClass().getMethod("saveRole");
        saveRole.invoke(owner);
        assertEquals(250,database.networkTimeoutMillis);
        assertEquals(2,database.queryTimeoutSeconds);

        var transformed=PostgresModule.transformGracefulQuit(
            new ByteBuddy().redefine(GracefulQuitFixture.class)).make();
        Loader loader=new Loader();
        loader.definitions.put(GracefulQuitFixture.class.getName(),transformed.getBytes());
        Class<?> fixture=loader.loadClass(GracefulQuitFixture.class.getName());
        fixture.getField("owner").set(null,owner);
        Object quit=fixture.getConstructor().newInstance();
        fixture.getMethod("QueuedQuit").invoke(quit);
        assertEquals(30_000,database.networkTimeoutMillis);
        assertEquals(30,database.queryTimeoutSeconds);

        database.failQuery=true;
        assertThrows(InvocationTargetException.class,() -> fixture.getMethod("QueuedQuit").invoke(quit));
        database.failQuery=false;
        saveRole.invoke(owner);
        assertEquals(250,database.networkTimeoutMillis);
        assertEquals(2,database.queryTimeoutSeconds);
        assertEquals(database.borrowed,database.returned);
    }

    @Test void moduleIsInGlobalRegistryAndDisabledModeDoesNotTransform() throws Exception {
        assertTrue(cloud.apocalipse.pz.agent.AgentModules.all().stream().anyMatch(PostgresModule.class::isInstance));
        property(AgentConfiguration.POSTGRES_ENABLED_PROPERTY,"false");
        PostgresModule module=new PostgresModule(); module.initialize();
        try (var locator=ClassFileLocator.ForClassLoader.of(getClass().getClassLoader())) {
            byte[] bytes=locator.locate(PostgresModule.ACCOUNT_TYPE).resolve();
            assertNull(module.configure(new AgentBuilder.Default().disableClassFormatChanges()).makeRaw()
                .transform(new Loader(),PostgresModule.ACCOUNT_TYPE.replace('.','/'),null,null,bytes));
        }
        assertThrows(IllegalStateException.class,() -> PostgresRuntime.verifyAccountHooks(new Loader()));
    }

    @Test void bootstrapConnectionFailureReportsSafeStateAndReleasesWorkflowForRetry() throws Exception {
        Database database=new Database();
        Object owner=instrument(database);
        var create=owner.getClass().getMethod("create");
        database.failBorrow=true;
        var failure=assertThrows(InvocationTargetException.class,() -> create.invoke(owner)).getCause();
        assertInstanceOf(IllegalStateException.class,failure);
        assertTrue(failure.getMessage().contains("bootstrap connection acquisition"));
        assertTrue(failure.getMessage().contains("SQLState=08006"));
        assertTrue(failure.getMessage().contains("SQLite fallback is disabled"));
        assertFalse(failure.toString().contains("PRIVATE"));
        assertNull(failure.getCause());
        database.failBorrow=false;
        create.invoke(owner);
        assertEquals(database.borrowed,database.returned);
    }

    @Test void bootstrapRuntimeFailureDoesNotExposeDriverConfigurationAndReleasesWorkflow() throws Exception {
        Database database=new Database();
        Object owner=instrument(database);
        var create=owner.getClass().getMethod("create");
        database.failBorrowRuntime=true;
        var failure=assertThrows(InvocationTargetException.class,() -> create.invoke(owner)).getCause();
        assertTrue(failure.getMessage().contains("runtime/connection initialization"));
        assertFalse(failure.toString().contains("PRIVATE"));
        assertNull(failure.getCause());
        database.failBorrowRuntime=false;
        create.invoke(owner);
        assertEquals(database.borrowed,database.returned);
    }

    @Test void administratorCreationUsesNativeRoleAndHashContractsWithoutSqliteDdl() throws Exception {
        Database database=new Database(); database.adminExists=false;
        Object owner=instrument(database);
        owner.getClass().getField("commandLineAdminPassword").set(owner,"fixture-password");
        owner.getClass().getMethod("create").invoke(owner);
        assertTrue(zombie.characters.Roles.initialized>0);
        assertEquals(2,database.writes.size());
        assertTrue(database.writes.get(0).startsWith("INSERT INTO whitelist"));
        assertEquals("fixture-hash:fixture-encoded:fixture-password",database.boundWrites.get(0).get(3));
        assertEquals(42,database.boundWrites.get(0).get(4));
        assertEquals("UPDATE whitelist SET password=? WHERE id=?",database.writes.get(1));
        assertEquals(database.borrowed,database.returned);
    }

    @Test void newAccountLimitUsesPostgresCountsAndPreservesPriorityException() throws Exception {
        Database database=new Database();
        Object owner=instrument(database);
        var allowed=owner.getClass().getMethod("canCreateForTest",String.class,long.class);
        assertEquals(true,allowed.invoke(owner,"fixture",765L));
        database.countValue=2;
        assertEquals(false,allowed.invoke(owner,"fixture",765L));
        zombie.characters.Roles.role.priority=true;
        assertEquals(true,allowed.invoke(owner,"fixture",765L));
        database.failQuery=true;
        assertThrows(InvocationTargetException.class,() -> allowed.invoke(owner,"fixture",765L));
        assertEquals(database.borrowed,database.returned);
    }

    @Test void pendingJournalStopsInstrumentedBootstrapBeforeBorrowing() throws Exception {
        Database database=new Database(); Object owner=instrument(database);
        java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("apocbr-hook-journal-");
        try {
            property(AgentConfiguration.POSTGRES_JOURNAL_DIRECTORY_PROPERTY,directory.toString());
            try (var journal=new PersistenceJournal(directory,4096,message -> {})) {
                journal.submitDurable(new PersistenceOperations.Account(List.of(new PersistenceOperations.Sql("UPDATE whitelist SET role=?",List.of(1)))));
            }
            byte[] checkpoint=java.nio.file.Files.readAllBytes(directory.resolve("checkpoint"));
            assertThrows(InvocationTargetException.class,() -> owner.getClass().getMethod("create").invoke(owner));
            assertEquals(0,database.borrowed);
            assertArrayEquals(checkpoint,java.nio.file.Files.readAllBytes(directory.resolve("checkpoint")));
        } finally {
            try (var paths=java.nio.file.Files.list(directory)) { for (var path:paths.toList()) java.nio.file.Files.delete(path); }
            java.nio.file.Files.delete(directory);
        }
    }

    @Test void transformsOriginalJarWithoutAddingGameMembers() throws Exception {
        String path=System.getProperty("pz.game.jar");
        assumeTrue(path!=null&&!path.isBlank(),"Set -Dpz.game.jar for original-JAR account transformation");
        try (var locator=new ClassFileLocator.Compound(ClassFileLocator.ForJarFile.of(new File(path)),
                ClassFileLocator.ForClassLoader.ofPlatformLoader(),ClassFileLocator.ForClassLoader.ofSystemLoader())) {
            var original=TypePool.Default.of(locator).describe(PostgresModule.ACCOUNT_TYPE).resolve();
            var transformed=PostgresModule.transformAccounts(new ByteBuddy().redefine(original,locator)).make();
            assertTrue(transformed.getBytes().length>0);
            assertEquals(original.getDeclaredFields().size(),transformed.getTypeDescription().getDeclaredFields().size());
            assertEquals(original.getDeclaredMethods().size(),transformed.getTypeDescription().getDeclaredMethods().size());
            assertEquals(members(locator.locate(PostgresModule.ACCOUNT_TYPE).resolve()),members(transformed.getBytes()));
        }
    }

    @Test void originalServerLifecycleHooksPreserveClassStructure() throws Exception {
        String path = System.getProperty("pz.game.jar", "Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar");
        try (var locator=new ClassFileLocator.Compound(ClassFileLocator.ForJarFile.of(new File(path)),
                ClassFileLocator.ForClassLoader.ofPlatformLoader(),ClassFileLocator.ForClassLoader.ofSystemLoader())) {
            for (String name:List.of("zombie.network.GameServer","zombie.network.GameServer$1","zombie.network.ServerMap")) {
                var original=TypePool.Default.of(locator).describe(name).resolve();
                var builder=new ByteBuddy().redefine(original,locator);
                var transformed=(switch (name) {
                    case "zombie.network.GameServer" -> builder.visit(Advice.to(PostgresAdvice.ServerLifecycle.class)
                        .on(named("main").and(isStatic()).and(takesArguments(String[].class))));
                    case "zombie.network.ServerMap" -> PostgresModule.transformGracefulQuit(builder);
                    default -> ServerShutdownModule.transform(builder);
                }).make();
                assertEquals(members(locator.locate(name).resolve()),members(transformed.getBytes()));
            }
        }
    }

    public static class FailedVanillaShutdown {
        public void run() { throw new IllegalArgumentException("native save failed"); }
    }

    @Test void shutdownAdviceClosesPoolAndPreservesNativeFailure() throws Exception {
        var database = new Database();
        PostgresRuntime.installForTest(SETTINGS, new PostgresConnections(database.source(), () -> {
            database.shutdowns++;
            throw new IllegalStateException("fixture pool close failure");
        }));
        var fixture = FailedVanillaShutdown.class;
        var loader = new Loader();
        loader.definitions.put(fixture.getName(), ServerShutdownModule.transform(new ByteBuddy().redefine(fixture)).make().getBytes());
        var hook = loader.loadClass(fixture.getName()).getConstructor().newInstance();
        var failure = assertThrows(InvocationTargetException.class, () -> hook.getClass().getMethod("run").invoke(hook));
        assertEquals("native save failed", failure.getCause().getMessage());
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertEquals("PostgreSQL pool shutdown failed", failure.getCause().getSuppressed()[0].getMessage());
        PostgresRuntime.shutdown();
        assertEquals(1, database.shutdowns);
    }

    private static java.util.Set<String> members(byte[] bytes) {
        java.util.Set<String> result=new java.util.HashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int access,String name,String desc,String signature,Object value) {
                result.add("F:"+access+":"+name+":"+desc); return null;
            }
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                result.add("M:"+access+":"+name+":"+desc); return null;
            }
        },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        return result;
    }
}
