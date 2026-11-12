package zombie.savefile;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import zombie.world.*;

/** Production JDBC reader + real snapshot/cache, with an ordered immutable JDBC fixture. */
public final class DictionaryPaginationTest {
    static int checks;
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    static Object zero(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    static Object proxy(Class<?> type,InvocationHandler handler){return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static ResultSet rows(List<Object[]> data){int[] index={-1};return (ResultSet)proxy(ResultSet.class,(p,m,a)->switch(m.getName()) {
        case "next" -> ++index[0]<data.size();
        case "getString","getObject" -> data.get(index[0])[(int)a[0]-1];
        case "getInt" -> ((Number)data.get(index[0])[(int)a[0]-1]).intValue();
        case "getLong" -> ((Number)data.get(index[0])[(int)a[0]-1]).longValue();
        default -> zero(m.getReturnType());
    });}
    static final Comparator<Object[]> KEYS=(a,b)->{
        int c=((String)a[0]).compareTo((String)b[0]);if(c!=0)return c;
        c=((String)a[1]).compareTo((String)b[1]);return c!=0?c:Integer.compare(((Number)a[2]).intValue(),((Number)b[2]).intValue());
    };
    static final class Fake {
        final ApocBRPayloadProvenance origin=new ApocBRPayloadProvenance(2,UUID.randomUUID(),UUID.randomUUID());
        final List<Object[]> entries=new ArrayList<>(),overrides=new ArrayList<>();
        int entryQueries,overrideQueries,commits,rollbacks,closed;long claimedEntries=-1,claimedOverrides=-1;
        int failPage,failOverridePage;boolean missingState,truncate,failCommit,failAcquire,failClose,failCount,failRollback;
        SQLException failure;
        Fake(int count,int overrideCount) {
            // Cross family/register boundaries, including IDs decreasing at register boundaries.
            for(int i=0;i<count;i++) {
                String family=i<count/3?"info":i<2*count/3?"script":"string";
                String register=family.equals("info")?"":i%2==0?"A":"Z";
                String key="Key"+i;
                entries.add(new Object[]{family,register,i,family.equals("info")?"item":family,key,
                    family.equals("info")?"Base":null,family.equals("info")?key:null,true,"mod",true,false,false,false,true,7L});
            }
            entries.sort(KEYS);
            for(int i=0;i<overrideCount;i++) {
                Object[] entry=entries.get(i%entries.size());
                overrides.add(new Object[]{entry[0],entry[1],entry[2],"mod"+(i/entries.size()),i/entries.size()});
            }
            overrides.sort((a,b)->{int c=KEYS.compare(a,b);return c!=0?c:Integer.compare((int)a[4],(int)b[4]);});
        }
        Connection open()throws SQLException {
            if(failAcquire)throw failure;
            return (Connection)proxy(Connection.class,(p,m,a)->switch(m.getName()) {
                case "commit" -> {if(failCommit)throw failure;commits++;yield null;}
                case "rollback" -> {rollbacks++;if(failRollback)throw new SQLException("private rollback detail","08006");yield null;}
                case "close" -> {closed++;if(failClose)throw new SQLException("private close detail","08006");yield null;}
                case "prepareStatement" -> statement((String)a[0]);
                default -> zero(m.getReturnType());
            });
        }
        Object statement(String sql) {
            check(!sql.contains("OFFSET"),"keyset never uses OFFSET");Map<Integer,Object> args=new HashMap<>();
            return proxy(PreparedStatement.class,(p,m,a)->{
                if(m.getName().equals("setObject")){args.put((int)a[0],a[1]);return null;}
                if(m.getName().equals("setQueryTimeout")){check(a[0].equals(5),"every source statement retains 5s timeout");return null;}
                if(!m.getName().equals("executeQuery"))return zero(m.getReturnType());
                if(sql.startsWith("SELECT v.identity_revision")) {
                    check(args.get(1).equals(origin.namespaceId())&&args.get(2).equals(origin.viewId())&&args.get(3).equals(2),"pins source tuple");
                    return rows(Collections.singletonList(new Object[]{12L}));
                }
                if(sql.startsWith("SELECT s.family"))return rows(List.of(
                    new Object[]{"info","",200_000,null},new Object[]{"script","A",200_000,null},new Object[]{"script","Z",200_000,null},
                    new Object[]{"string","A",200_000,64},new Object[]{"string","Z",200_000,64}));
                boolean override=sql.contains("world_dictionary_mod_override");
                if(sql.startsWith("SELECT count(*)")) {
                    if(failCount)throw failure;
                    check(args.get(1).equals(origin.namespaceId()),"count scoped to namespace");
                    check(override?args.get(2).equals(origin.viewId()):args.get(2).equals(12L),"count pins view/revision");
                    long count=override?(claimedOverrides<0?overrides.size():claimedOverrides):(claimedEntries<0?entries.size():claimedEntries);
                    return rows(Collections.singletonList(new Object[]{count}));
                }
                if(override)overrideQueries++;else entryQueries++;
                if(!override&&entryQueries==failPage)throw failure;
                if(override&&overrideQueries==failOverridePage)throw failure;
                List<Object[]> source=override?overrides:entries;
                int base=override?2:3;boolean cursor=sql.contains(")>(");
                check(args.get(1).equals(override?origin.namespaceId():origin.viewId()),"page source binding");
                if(!override)check(args.get(2).equals(origin.namespaceId())&&args.get(3).equals(12L),"every entry page pins revision");
                int keyCount=override?4:3;
                int limit=(int)args.get(base+(cursor?keyCount:0)+1);
                Object[] after=cursor?new Object[]{args.get(base+1),args.get(base+2),args.get(base+3),null,override?args.get(base+4):null}:null;
                List<Object[]> page=new ArrayList<>();
                for(Object[] row:source) {
                    if(after!=null) {
                        int c=KEYS.compare(row,after);
                        if(c<0||c==0&&(!override||(int)row[4]<=(int)after[4]))continue;
                    }
                    Object[] copy=row.clone();if(!override&&missingState)copy[7]=null;
                    page.add(copy);if(page.size()==limit)break;
                }
                if(truncate&&!page.isEmpty())page.removeLast();
                return rows(page);
            });
        }
        ApocBRWorldDictionarySnapshot load()throws SQLException{return new ApocBRWorldDictionaryDatabase(this::open).load(origin);}
    }
    static PSQLException constraint(){return new PSQLException(new ServerErrorMessage(
        "SERROR\0C23514\0Mdictionary validation failed\0splayer_rel\0tworld_dictionary_entry\0nstable_key_check\0cstable_key\0Dprivate row contents\0Wprivate context\0\0"));}
    static SQLException rejected(Fake fake)throws Exception {
        try{fake.load();throw new AssertionError("failed load accepted");}catch(SQLException error){return error;}
    }
    public static void main(String[] args)throws Exception {
        for(int count:new int[]{0,1,999,1000,1001,9999,10000,10001,200_000}) {
            Fake fake=new Fake(count,0);var result=fake.load();
            check(result.entries().size()==count,"all entries loaded: "+count);
            check(fake.entryQueries<=10&&fake.overrideQueries==0,"at most ten pages, zero override query for empty set");
            int size=Math.max(1000,(count+9)/10);
            check(fake.entryQueries==(count+size-1)/size,"dynamic page count without sentinel query");
            check(fake.commits==1&&fake.closed==1&&fake.rollbacks==0,"successful cleanup");
            for(int i=0;i<count;i++)check(result.entries().get(i).key().equals(fake.entries.get(i)[4]),"stable order, no missing/duplicate IDs");
        }
        Fake overrides=new Fake(11,10001);var snapshot=overrides.load();
        check(overrides.overrideQueries==10,"override pagination bounded to ten");
        for(var entry:snapshot.entries()) {
            List<String> expected=overrides.overrides.stream().filter(r->r[0].equals(entry.family())&&r[1].equals(entry.register())&&r[2].equals(entry.id())).map(r->(String)r[3]).toList();
            check(entry.overrides().equals(expected),"ordered overrides survive page boundary within same identity");
        }
        for(boolean entryBudget:new boolean[]{true,false}) {
            Fake fake=new Fake(1,0);if(entryBudget)fake.claimedEntries=200001;else fake.claimedOverrides=1000001;
            check(rejected(fake).getSQLState().equals("54000")&&fake.entryQueries==0&&fake.overrideQueries==0,"budget rejected before bulk reads");
        }
        for(int scenario=0;scenario<5;scenario++) {
            Fake fake=new Fake(10001,0);fake.failure=constraint();
            if(scenario==0)fake.failPage=2;if(scenario==1)fake.missingState=true;if(scenario==2)fake.truncate=true;
            if(scenario==3)fake.failAcquire=true;if(scenario==4)fake.failCommit=true;
            SQLException error=rejected(fake);String diagnostic=error.getMessage();
            check(error.getCause()!=null,"original error retained");
            check(diagnostic.contains("stage=")&&diagnostic.contains("expectedEntries=")&&diagnostic.contains("queryTimeoutSeconds=5"),"progress diagnostics");
            if(scenario==0)check(diagnostic.contains("page=2;")&&diagnostic.contains("plannedPages=10")&&diagnostic.contains("entryRows=1001")&&diagnostic.contains("constraint=stable_key_check")&&diagnostic.contains("table=world_dictionary_entry"),"page and PG fields survive wrapping");
            if(scenario==3)check(diagnostic.contains("stage=ACQUIRE_CONNECTION"),"acquisition failure logged");
            check(!diagnostic.contains("private row")&&!diagnostic.contains("private context"),"no PG DETAIL/WHERE leakage");
            check(fake.commits==0&&(fake.failAcquire||fake.rollbacks==1),"partial failure never commits");
        }
        Fake closing=new Fake(1,0);closing.failClose=true;closing.failure=new SQLException("private close detail","08006");
        check(rejected(closing).getMessage().contains("stage=RETURN_CONNECTION"),"return failure has context");
        Fake counting=new Fake(1,0);counting.failCount=true;counting.failure=new SQLException("private cancel","57014");
        check(rejected(counting).getMessage().contains("stage=COUNT_SOURCE_ENTRIES"),"count timeout identified separately");
        Fake overrideFailure=new Fake(11,10001);overrideFailure.failOverridePage=2;overrideFailure.failure=constraint();
        check(rejected(overrideFailure).getMessage().contains("stage=READ_SOURCE_OVERRIDES")&&overrideFailure.entryQueries==0,"override failure prevents entry/cache installation");
        Fake cleanup=new Fake(10001,0);cleanup.failPage=2;cleanup.failRollback=true;cleanup.failClose=true;cleanup.failure=constraint();
        String cleanupDiagnostic=rejected(cleanup).getMessage();
        check(cleanupDiagnostic.contains("cleanup={")&&cleanupDiagnostic.contains("SQLState=08006")&&!cleanupDiagnostic.contains("private"),"cleanup errors retain sanitized diagnostics");
        SQLException chain=new SQLException("private wrapper","HY000");chain.setNextException(constraint());chain.initCause(new RuntimeException("private runtime"));
        String detail=ApocBRWorldDictionaryDatabase.sourceDiagnostic(chain);
        check(detail.contains("SQLState=23514")&&detail.contains("constraint=stable_key_check")&&!detail.contains("private"),"nested errors diagnostic without raw wrapper text");
        Fake failed=new Fake(10001,0);failed.failPage=2;failed.failure=new SQLException("private timeout","57014");
        ApocBRPostgresConnector.factory=failed::open;
        try{ApocBRWorldDictionaryDecode.prepare(failed.origin);throw new AssertionError("prepare accepted partial load");}
        catch(SQLException error){check(error.getSQLState().equals("57014")&&error.getCause()!=null&&error.getCause().getMessage().contains("page=2;"),"prepare preserves SQLSTATE/cause/page context");}
        failed.failPage=0;ApocBRWorldDictionaryDecode.prepare(failed.origin);int queries=failed.entryQueries;
        ApocBRWorldDictionaryDecode.prepare(failed.origin);
        check(failed.entryQueries==queries,"failed partial load discarded, complete retry cached");
        System.out.println("Dictionary pagination checks="+checks);
    }
}
