// Ordered JDBC fixture adapted from tools/fixtures/DictionaryPaginationTest.java.
package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;


/** Production JDBC reader + real snapshot/cache, with an ordered immutable JDBC fixture. */
final class DictionaryReadFixture {
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
        final DictionaryOrigin origin=new DictionaryOrigin(2,UUID.randomUUID(),UUID.randomUUID());
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
        DictionarySnapshot load()throws SQLException{return new DictionaryDatabase(this::open).load(origin);}
    }
}
