package cloud.apocalipse.pz.agent.features.lua.login;

import cloud.apocalipse.pz.agent.config.AgentConfiguration.PostgresSettings;
import cloud.apocalipse.pz.agent.features.postgres.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import zombie.core.raknet.*;
import zombie.core.network.ByteBufferWriter;

/** Real transformed packet/account classes, fake JDBC and connection transport, no server/network/database startup. */
public final class LoginOriginalSmokeMain {
    public static final class ConnectionFixture extends UdpConnection {
        int sent,denied;
        final ByteBufferWriter output=new ByteBufferWriter(ByteBuffer.allocate(65536));
        public ConnectionFixture(){super(null,71L,0);setUserName("fixture");setIDStr("fixture");}
        @Override public String getDescription(){return "fixture";}
        @Override public java.net.InetSocketAddress getInetSocketAddress(){return new java.net.InetSocketAddress("127.0.0.1",12345);}
        @Override public ByteBufferWriter startPacket(){output.bb.clear();return output;}
        @Override public void endPacket(int priority,int reliability,byte ordering){sent++;}
        @Override public void cancelPacket(){}
        @Override public void forceDisconnect(String reason){denied++;}
    }
    private static void set(Object object,String name,Object value)throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static void staticSet(Class<?> type,String name,Object value)throws Exception {
        Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(null,value);
    }
    private static Object zero(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    static final AtomicInteger borrowed=new AtomicInteger(),returned=new AtomicInteger(),queries=new AtomicInteger();
    static volatile boolean queryOnOwner;
    static volatile boolean failQuery;
    static Thread owner;
    private static Object proxy(Class<?> type,InvocationHandler handler){return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    private static java.sql.Connection connection(){
        borrowed.incrementAndGet();
        return (java.sql.Connection)proxy(java.sql.Connection.class,(p,m,a)->{
            if(m.getName().equals("close")){returned.incrementAndGet();return null;}
            if(m.getName().equals("prepareStatement"))return statement((String)a[0]);
            return zero(m.getReturnType());
        });
    }
    private static PreparedStatement statement(String sql){
        return (PreparedStatement)proxy(PreparedStatement.class,(p,m,a)->{
            if(m.getName().equals("executeQuery")) {
                queries.incrementAndGet();queryOnOwner|=Thread.currentThread()==owner;
                if(failQuery)throw new SQLException("PRIVATE_DETAILS","08006");
                boolean auth=sql.contains("SELECT role, password");
                AtomicInteger row=new AtomicInteger();
                return proxy(ResultSet.class,(r,method,args)->switch(method.getName()){
                    case "next" -> auth&&row.getAndIncrement()==0;
                    case "getInt" -> 1;
                    case "getString" -> args[0].equals("password")?"different-password":"";
                    default -> zero(method.getReturnType());
                });
            }
            return zero(m.getReturnType());
        });
    }
    private static void awaitPending(Class<?> ownerType,String fieldName)throws Exception {
        Field map=ownerType.getDeclaredField(fieldName);map.setAccessible(true);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline) {
            Map<?,?> entries=(Map<?,?>)map.get(null);
            if(!entries.isEmpty()) {
                boolean done=true;
                for(Object pending:entries.values()){
                    Field future=pending.getClass().getDeclaredField("result");future.setAccessible(true);
                    done&=((CompletableFuture<?>)future.get(pending)).isDone();
                }
                if(done)return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("Worker did not publish its result");
    }
    public static void main(String[] arguments)throws Exception {
        owner=Thread.currentThread();
        for(String random:new String[]{"RandStandard","RandLua"}) { Class<?> r=Class.forName("zombie.core.random."+random);r.getMethod("init").invoke(r.getField("INSTANCE").get(null)); }
        Class<?> fs=Class.forName("zombie.ZomboidFileSystem");Object fileSystem=fs.getField("instance").get(null);
        fs.getMethod("setCacheDir",String.class).invoke(fileSystem,java.nio.file.Files.createTempDirectory("apocbr-login-smoke-").toString());
        Class<?> server=Class.forName("zombie.network.GameServer");
        if(!server.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("projectzomboid.jar"))throw new AssertionError("Wrong game archive");
        staticSet(server,"server",true);staticSet(server,"mainThread",owner);
        Class<?> core=Class.forName("zombie.core.Core");staticSet(core,"gameSaveWorld","login-fixture");
        Class<?> roles=Class.forName("zombie.characters.Roles"),role=Class.forName("zombie.characters.Role"),cap=Class.forName("zombie.characters.Capability");
        Object current=role.getConstructor(String.class).newInstance("fixture");role.getMethod("setId",int.class).invoke(current,1);
        role.getMethod("addCapability",cap).invoke(current,cap.getField("LoginOnServer").get(null));
        role.getMethod("addCapability",cap).invoke(current,cap.getField("ReadUserLog").get(null));
        @SuppressWarnings("unchecked") List<Object> registry=(List<Object>)roles.getMethod("getRoles").invoke(null);registry.add(current);
        for(String field:new String[]{"defaultForBanned","defaultForUser","defaultForNewUser"})staticSet(roles,field,current);
        DataSource source=(DataSource)proxy(DataSource.class,(p,m,a)->m.getName().equals("getConnection")?connection():zero(m.getReturnType()));
        var constructor=PostgresConnections.class.getDeclaredConstructor(DataSource.class,AutoCloseable.class);constructor.setAccessible(true);
        var pool=constructor.newInstance(source,(AutoCloseable)()->{});
        Method install=PostgresRuntime.class.getDeclaredMethod("installForTest",PostgresSettings.class,PostgresConnections.class);install.setAccessible(true);
        install.invoke(null,new PostgresSettings(true,true,"jdbc:postgresql://localhost/fixture",2,5000,3000),pool);
        ConnectionFixture connection=new ConnectionFixture();connection.setRole((zombie.characters.Role)current);
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field singleton=unsafeType.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        Object unsafe=singleton.get(null);UdpEngine engine=(UdpEngine)unsafeType.getMethod("allocateInstance",Class.class).invoke(unsafe,UdpEngine.class);
        Map<Long,UdpConnection> active=new HashMap<>();active.put(71L,connection);
        set(engine,"connectionMap",active);set(engine,"connections",new ArrayList<>(List.of(connection)));staticSet(server,"udpEngine",engine);
        AsyncRuntime.initialize(true);
        try {
            AtomicInteger callbacks=new AtomicInteger();
            ApocBRAsyncLogin.submit(connection,"snapshot","fixture",()->{
                if(Thread.currentThread()==owner||!WorkerContext.active())throw new AssertionError("Wrong worker ownership");
                if(WorkerContext.getRoleById(1)==current)throw new AssertionError("Shared mutable native role");
                if(!"login-fixture".equals(WorkerContext.world()))throw new AssertionError("Missing captured world");
                return "ok";
            },result->{if(Thread.currentThread()!=owner||!"ok".equals(result))throw new AssertionError("Wrong continuation");callbacks.incrementAndGet();});
            awaitPending(ApocBRAsyncLogin.class,"pending");ApocBRAsyncLogin.update();
            if(callbacks.get()!=1)throw new AssertionError("Login continuation not drained");
            ApocBRAsyncLogin.submitRemote(connection,"steam-token",()->{
                if (!Thread.currentThread().isVirtual() || Thread.currentThread()==owner || WorkerContext.active())
                    throw new AssertionError("HTTP work did not run on an isolated virtual thread");
                return true;
            },result->{
                if (Thread.currentThread()!=owner || !result)
                    throw new AssertionError("HTTP completion did not resume on the simulation thread");
                callbacks.incrementAndGet();
            });
            awaitPending(ApocBRAsyncLogin.class,"pending");ApocBRAsyncLogin.update();
            if(callbacks.get()!=2)throw new AssertionError("HTTP continuation not drained");
            ApocBRAsyncLogin.submit(connection,"stale","fixture",()->"discard",result->callbacks.incrementAndGet());
            awaitPending(ApocBRAsyncLogin.class,"pending");active.clear();ApocBRAsyncLogin.update();
            if(callbacks.get()!=2)throw new AssertionError("Stale connection continuation ran");active.put(71L,connection);
            Object packet=Class.forName("zombie.network.packets.service.RequestUserLogPacket").getConstructor().newInstance();set(packet,"username","fixture");
            Method process=packet.getClass().getMethod("processServer",Class.forName("zombie.network.PacketTypes$PacketType"),UdpConnection.class);
            process.invoke(packet,null,connection);awaitPending(ApocBRAsyncServerDatabase.class,"pending");ApocBRAsyncLogin.update();
            if(connection.sent!=1||queries.get()!=1||queryOnOwner)throw new AssertionError("Admin query/response did not cross the worker boundary exactly once");
            failQuery=true;process.invoke(packet,null,connection);awaitPending(ApocBRAsyncServerDatabase.class,"pending");ApocBRAsyncLogin.update();failQuery=false;
            if(connection.sent!=1)throw new AssertionError("Failed administrative read sent a response");
            Object login=Class.forName("zombie.network.packets.connection.LoginPacket").getConstructor().newInstance();
            set(login,"username","fixture");set(login,"password","fixture-password");set(login,"authType",1);
            set(login,"clientVersion",core.getMethod("getGameAndBuildVersion").invoke(core.getMethod("getInstance").invoke(null)));
            login.getClass().getMethod("processServer",Class.forName("zombie.network.PacketTypes$PacketType"),UdpConnection.class).invoke(login,null,connection);
            awaitPending(ApocBRAsyncLogin.class,"pending");ApocBRAsyncLogin.update();
            if(connection.denied!=1||queryOnOwner)throw new AssertionError("Rejected authentication did not return through the server continuation");
            set(login,"authType",cloud.apocalipse.pz.agent.features.clientmenu.SteamLoginPacketPayload.AUTH_TYPE);
            set(login,"password","00000000-0000-4000-8000-000000000001");
            int previousQueries = queries.get();
            login.getClass().getMethod("processServer",Class.forName("zombie.network.PacketTypes$PacketType"),UdpConnection.class).invoke(login,null,connection);
            if (connection.denied != 2 || queries.get() != previousQueries)
                throw new AssertionError("APBR without a Steam connection reached password authentication");
            CountDownLatch entered=new CountDownLatch(1);
            ApocBRAsyncLogin.submit(connection,"shutdown","fixture",()->{entered.countDown();new CountDownLatch(1).await();return null;},ignored->{throw new AssertionError("Cancelled callback ran");});
            if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("Shutdown fixture not started");
        } finally {AsyncRuntime.shutdown();PostgresRuntime.shutdown();}
        if(borrowed.get()!=returned.get())throw new AssertionError("JDBC leases leaked");
        System.out.println("ORIGINAL_LOGIN_SMOKE_PASS queries="+queries.get());
    }
}
