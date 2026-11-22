"""Compile actual 42.21.0 mirror against bundled JDBC/pool and game dependencies.
Exercise transactional failure and FIFO recovery without contacting a database.
"""
from postgres_test_support import inactive_persistence_stubs

from pathlib import Path
import subprocess
import tempfile
import os

ROOT = Path(__file__).resolve().parents[1]
GAME = Path(os.environ.get("APOCBR_TEST_GAME_JAR", r"Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar"))
LIB = ROOT / "42.21.0/lib"
JDK = ROOT / "jdk/bin"
SOURCE = ROOT / "42.21.0/src/zombie/savefile/ApocBRPostgresConnector.java"

HARNESS = r'''
package zombie.savefile;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import com.zaxxer.hikari.*;
import org.postgresql.util.*;

public class PostgresConnectorTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
    static class Fake implements InvocationHandler {
        int commits, rollbacks, writes, acquired, closed; int networkTimeout=3000;
        boolean failWrite, failCommit;
        boolean duplicate;
        Integer existingId;
        Map<Integer,Object> lookupParams;
        String lookupSql;
        List<String> prepared=new ArrayList<>();
        Map<Integer,Object> params = new HashMap<>();
        String sql;
        DataSource source() {
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class}, (p,m,a) -> {
                    if (m.getName().equals("getConnection")) {
                        acquired++;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, this);
                    }
                    return defaultValue(m.getReturnType());
                });
        }
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch(method.getName()) {
                case "prepareStatement": sql=(String)args[0]; params.clear(); prepared.add(sql);
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, this);
                case "setString", "setInt", "setFloat", "setBoolean", "setBytes", "setTimestamp", "setObject":
                    params.put((Integer)args[0], args[1]); return null;
                case "executeQuery":
                    lookupSql=sql; lookupParams=new HashMap<>(params);
                    final int[] row={0};
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class}, (p,m,a) -> {
                        if(m.getName().equals("next")) return existingId!=null && row[0]++ < (duplicate ? 2 : 1);
                        if(m.getName().equals("getInt")) return existingId;
                        return defaultValue(m.getReturnType());
                    });
                case "executeUpdate": writes++; if (failWrite) throw new SQLException("SECRET MUST NOT BE LOGGED", "08006"); return 1;
                case "commit": if(failCommit) throw new SQLException("lost commit acknowledgement", "08006"); commits++; existingId=42; return null;
                case "rollback": rollbacks++; return null;
                case "close": closed++; return null;
                case "isValid": return true;
                case "getAutoCommit": return false;
                case "getNetworkTimeout": return networkTimeout;
                case "setNetworkTimeout": networkTimeout=(Integer)args[1]; return null;
            }
            return defaultValue(method.getReturnType());
        }
    }
    static ApocBRPostgresConnector.Snapshot snapshot(int n, boolean coop, byte[] bytes) {
        return new ApocBRPostgresConnector.Snapshot("world", "alice", "7656119", 0,
            "Alice", 1, 2, 3, 249, false, "alice", coop, 1700000000000L+n, bytes);
    }
    static void allowRetry(ApocBRPostgresConnector connector) throws Exception {
        Field f=ApocBRPostgresConnector.class.getDeclaredField("nextAttemptNanos");
        f.setAccessible(true); f.setLong(connector, 0);
    }
    public static void main(String[] args) throws Exception {
        Fake fake=new Fake(); List<String> logs=new ArrayList<>();
        ApocBRPostgresConnector connector=new ApocBRPostgresConnector(fake.source(), logs::add);
        byte[] bytes={1,2,3}; var origin=new ApocBRPayloadProvenance(2,UUID.randomUUID(),UUID.randomUUID());
        var first=new ApocBRPostgresConnector.Snapshot("world","alice","7656119",0,"Alice",1,2,3,249,false,"alice",false,1,bytes,origin); bytes[0]=99;
        byte[] exposed=first.data(); exposed[0]=88;
        connector.offer(first); connector.drain();
        check(fake.commits==1 && connector.status().pending()==0, "successful commit retires snapshot");
        check(((byte[])fake.params.get(12))[0]==1, "immutable captured bytes");
        check(fake.lookupSql.contains("username=?") && fake.lookupParams.get(1).equals("alice"), "dedicated identity");
        check(fake.params.get(1).equals("world") && fake.params.get(9).equals(249), "world and format");
        check(fake.sql.stripLeading().startsWith("INSERT INTO"), "empty table inserts player with DB generated id");
        check(fake.prepared.get(0).contains("SHARE ROW EXCLUSIVE") && fake.prepared.get(1).startsWith("SELECT id"),
            "transaction locks writers before missing-player lookup");
        check(fake.params.size()==15 && !fake.sql.contains("server_key") && !fake.sql.contains("ON CONFLICT"), "V7 provenance columns without a server string or new identity index");
        check(fake.params.get(13).equals(2)&&fake.params.get(14).equals(origin.namespaceId())&&fake.params.get(15).equals(origin.viewId()),"native player bytes and original encoder tuple bound together");
        PSQLException missingTable = new PSQLException(new ServerErrorMessage(
            "SERROR\0C42P01\0Mrelation \"public.networkplayers\" does not exist\0DPRIVATE_ROW_DATA\0\0"));
        String diagnostic = ApocBRPostgresConnector.sqlDiagnostic(missingTable);
        check(diagnostic.contains("public.networkplayers") && diagnostic.contains("does not exist"), "actual server error message exposed");
        check(diagnostic.contains("undefined relation") && !diagnostic.contains("PRIVATE_ROW_DATA"), "missing table hint without row detail");
        PSQLException unsafeMessage = new PSQLException(new ServerErrorMessage(
            "SERROR\0C42P01\0Mbad jdbc:postgresql://host/db?password=PRIVATE_CREDENTIAL password=PRIVATE_PASSWORD\0\0"));
        check(!ApocBRPostgresConnector.sqlDiagnostic(unsafeMessage).contains("PRIVATE_"), "credentials redacted in server messages");
        check(ApocBRPostgresConnector.sqlDiagnostic(new SQLException("private", "42703")).contains("vanilla columns"), "column mismatch diagnostic");

        fake.failWrite=true; connector.offer(snapshot(2, true, new byte[]{2}));
        connector.offer(snapshot(3, true, new byte[]{3})); connector.drain();
        check(fake.rollbacks==1 && connector.status().pending()==2, "failure retains head and successors");
        int attempts=fake.writes; connector.drain(); check(fake.writes==attempts, "backoff prevents tight retry loop");
        check(fake.lookupSql.contains("steamid=?") && fake.lookupParams.get(1).equals("7656119"), "steam coop identity");
        fake.failWrite=false; allowRetry(connector); connector.drain();
        check(connector.status().pending()==0 && connector.status().committed()==3, "ordered retry recovers queue");
        check(((byte[])fake.params.get(12))[0]==3, "newest snapshot applied last");
        check(fake.params.get(13)==null&&fake.params.get(14)==null&&fake.params.get(15)==null,"unknown replacement bytes clear old provenance atomically");
        check(fake.sql.stripLeading().startsWith("UPDATE") && fake.params.get(16).equals(42), "existing row updated using PostgreSQL id");
        check(logs.stream().noneMatch(s -> s.contains("SECRET")), "no exception message credentials");

        fake.failCommit=true; connector.offer(snapshot(4,false,new byte[]{4})); connector.drain();
        check(connector.status().pending()==1 && fake.rollbacks==2, "uncertain commit retains identical upsert");
        fake.failCommit=false; allowRetry(connector); connector.drain();
        check(((byte[])fake.params.get(12))[0]==4 && fake.params.get(16).equals(42), "retry preserves payload and selected id");
        check(connector.status().committed()==4, "committed count advances only after acknowledged commit");

        for(int i=0;i<64;i++) check(connector.offer(snapshot(i,false,new byte[]{1})), "queue capacity");
        check(!connector.offer(snapshot(65,false,new byte[]{1})), "bounded queue rejects excess");
        connector.close(); check(!connector.offer(snapshot(66,false,new byte[]{1})), "closed connector rejects writes");
        Fake largeFake=new Fake(); var large=new ApocBRPostgresConnector(largeFake.source(), logs::add);
        for(int i=0;i<16;i++) check(large.offer(snapshot(i,false,new byte[1048576])), "byte capacity");
        check(!large.offer(snapshot(17,false,new byte[]{1})), "memory byte limit");
        check(large.status().pendingBytes()==16777216L, "exact memory accounting"); large.close();
        try { snapshot(1,false,null); throw new AssertionError("null payload accepted"); }
        catch(IllegalArgumentException expected) {}

        Fake ambiguous=new Fake(); ambiguous.existingId=7; ambiguous.duplicate=true;
        var duplicateConnector=new ApocBRPostgresConnector(ambiguous.source(),logs::add);
        duplicateConnector.offer(snapshot(1,false,new byte[]{1})); duplicateConnector.drain();
        check(ambiguous.writes==0 && ambiguous.rollbacks==1 && duplicateConnector.status().pending()==1,
            "duplicate legacy identities are retained as an error without overwriting arbitrary rows");
        duplicateConnector.close();
        try { snapshot(1,false,new byte[0]); throw new AssertionError("empty payload accepted"); }
        catch(IllegalArgumentException expected) {}

        SQLException ioFailure=new PSQLException("private JDBC statement",PSQLState.CONNECTION_FAILURE,new java.net.SocketTimeoutException("password=PRIVATE_SOCKET_MESSAGE"));
        String ioDiagnostic=ApocBRPostgresConnector.sqlDiagnostic(ioFailure,false);
        check(ioDiagnostic.contains("cause=SocketTimeoutException")&&ioDiagnostic.contains("transport=SOCKET_READ_TIMEOUT")&&!ioDiagnostic.contains("PRIVATE_"),"transport root cause classified without raw exception messages");
        check(ApocBRPostgresConnector.sqlDiagnostic(new SQLException("private","08006",new java.io.EOFException("PRIVATE_EOF")),false).contains("transport=UNEXPECTED_EOF"),"EOF classified separately from timeout");
        // Real pool links against the game's SLF4J API; no external DB connection.
        HikariConfig config=new HikariConfig(); config.setDataSource(new Fake().source());
        config.setMaximumPoolSize(8); config.setMinimumIdle(0); config.setInitializationFailTimeout(-1);
        try(HikariDataSource pool=new HikariDataSource(config)) {
            check(pool.getMaximumPoolSize()==8, "real Hikari configuration links");
            Field singleton=ApocBRPostgresConnector.class.getDeclaredField("instance");singleton.setAccessible(true);
            Field configured=ApocBRPostgresConnector.class.getDeclaredField("configured");configured.setAccessible(true);
            Object previous=singleton.get(null);boolean wasConfigured=configured.getBoolean(null);
            singleton.set(null,new ApocBRPostgresConnector(pool,logs::add));configured.setBoolean(null,true);
            try {
                check(ApocBRPostgresConnector.dictionaryPublicationTimeoutSeconds()==30,"bounded publication timeout defaults to thirty seconds");
                try(Connection lease=ApocBRPostgresConnector.borrowDictionaryConnection()){check(lease.getNetworkTimeout()==45000,"dictionary lease outlives publication query plus cancellation margin");}
                try(Connection lease=ApocBRPostgresConnector.borrowPlayerConnection()){check(lease.getNetworkTimeout()==3000,"real Hikari resets dictionary network timeout before ordinary player lease");}
                System.setProperty("apocbr.postgres.dictionary.publicationTimeoutSeconds","60");
                try(Connection lease=ApocBRPostgresConnector.borrowDictionaryConnection()){check(lease.getNetworkTimeout()==75000,"configured publication budget also controls socket margin");}
                System.setProperty("apocbr.postgres.dictionary.publicationTimeoutSeconds","0");
                try{ApocBRPostgresConnector.borrowDictionaryConnection();throw new AssertionError("unbounded timeout accepted");}catch(IllegalArgumentException expected){}
                check(pool.getHikariPoolMXBean().getActiveConnections()==0,"invalid timeout configuration returns borrowed lease");
            } finally {System.clearProperty("apocbr.postgres.dictionary.publicationTimeoutSeconds");singleton.set(null,previous);configured.setBoolean(null,wasConfigured);}
        }
        System.setProperty("apocbr.postgres.enabled", "false");
        ApocBRPostgresConnector.process();
        check(ApocBRPostgresConnector.mirrorStatus().pending()==0,"disabled mode has no queue");
        System.out.println("PostgreSQL mirror: transaction, FIFO retry, bounds, immutability, identity and pool tests passed");
    }
}
'''

with tempfile.TemporaryDirectory(prefix="apocbr_pg_test_") as directory:
    target = Path(directory)
    fixture = target / "PostgresConnectorTest.java"
    fixture.write_text(HARNESS, encoding="utf-8")
    boundary = inactive_persistence_stubs(target, mutations=True)
    dependencies = os.pathsep.join(map(str, [GAME, LIB / "postgresql-42.7.14.jar", LIB / "HikariCP-7.1.0.jar"]))
    subprocess.run([str(JDK / "javac.exe"), "--release", "25", "-cp", dependencies,
                    "-d", directory, *map(str,boundary), str(SOURCE.parent / "ApocBRPayloadProvenance.java"), str(SOURCE), str(fixture)], check=True)
    subprocess.run([str(JDK / "java.exe"), "-cp", directory + os.pathsep + dependencies,
                    "zombie.savefile.PostgresConnectorTest"], check=True)
    disabled_fixture = target / "DisabledCheck.java"
    disabled_fixture.write_text('''
public class DisabledCheck {
    public static void main(String[] args) {
        System.setProperty("apocbr.postgres.enabled", "false");
        zombie.savefile.ApocBRPostgresConnector.process();
        zombie.savefile.ApocBRPostgresConnector.shutdown();
        System.out.println("Disabled mirror loads without JDBC or pool JARs");
    }
}
''', encoding="utf-8")
    subprocess.run([str(JDK / "javac.exe"), "-cp", directory + os.pathsep + dependencies,
                    "-d", directory, str(disabled_fixture)], check=True)
    subprocess.run([str(JDK / "java.exe"), "-cp", directory + os.pathsep + str(GAME),
                    "DisabledCheck"], check=True)

patch = (ROOT / "42.21.0/src/zombie/savefile/ServerPlayerDB.java").read_text(encoding="utf-8")
worker = (ROOT / "42.21.0/src/zombie/network/ServerPlayersVehicles.java").read_text(encoding="utf-8")
assert "ApocBRPostgresConnector.enqueuePlayerSnapshot" in patch
assert "ApocBRPostgresConnector.savePlayerSnapshot" in patch
assert "ApocBRPostgresConnector.process();" not in patch
assert "ApocBRPostgresConnector.process();" in worker
assert "finally" in worker and "ApocBRPostgresConnector.shutdown();" in worker
print("Vanilla worker integration checks passed")
