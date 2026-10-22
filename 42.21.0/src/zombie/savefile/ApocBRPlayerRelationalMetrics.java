package zombie.savefile;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;

/** Counts JDBC executions without issuing monitoring queries or logging player data. */
final class ApocBRPlayerRelationalMetrics {
    private static final Set<String> EXECUTIONS=Set.of("executeQuery","executeUpdate","execute","executeBatch");
    private static final Pattern TABLE=Pattern.compile("(?:INSERT INTO|UPDATE|DELETE FROM|FROM) player_rel\\.([a-z_]+)");
    private final AtomicLong commits=new AtomicLong(),writeCommits=new AtomicLong(),rollbacks=new AtomicLong(),queries=new AtomicLong(),updates=new AtomicLong(),commands=new AtomicLong(),batches=new AtomicLong(),entries=new AtomicLong(),affected=new AtomicLong(),unknown=new AtomicLong(),failures=new AtomicLong(),sqlNanos=new AtomicLong();
    private final Map<String,Long> tables=new HashMap<>(),columns=new HashMap<>(),components=new HashMap<>(),incompleteReasons=new HashMap<>(); // Pinned catalog; worker owned.
    private final Set<UUID> players=new HashSet<>();
    private final AtomicLong leases=new AtomicLong(),borrowNanos=new AtomicLong(),maxBorrowNanos=new AtomicLong(),borrowFailures=new AtomicLong();
    private long initialCommits,initialRows,logicalRows,lastRows,lastCommits,lastWrites,lastCalls,incompleteGraphs;
    void incomplete(List<String> issues){incompleteGraphs++;for(String issue:issues){String code=issue.matches("[A-Za-z0-9_]{1,100}")?issue:"UNSUPPORTED_STATE";if(incompleteReasons.size()>=64&&!incompleteReasons.containsKey(code))code="OTHER";add(incompleteReasons,code);}}
    void borrowed(long nanos,boolean failed){if(failed)borrowFailures.incrementAndGet();else leases.incrementAndGet();borrowNanos.addAndGet(nanos);maxBorrowNanos.accumulateAndGet(nanos,Math::max);}
    private long lastReport=System.nanoTime();
    Connection connection(Connection delegate) {
        class Transaction {boolean wrote;}
        Transaction tx=new Transaction();
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("prepareStatement"))return statement((PreparedStatement)invoke(method,delegate,args),(String)args[0],()->tx.wrote=true);
            if(name.equals("commit")||name.equals("rollback")) {
                long started=System.nanoTime();
                try {
                    Object result=invoke(method,delegate,args);
                    if(name.equals("commit")){commits.incrementAndGet();if(tx.wrote)writeCommits.incrementAndGet();}else rollbacks.incrementAndGet();
                    tx.wrote=false;return result;
                }catch(Throwable failure){failures.incrementAndGet();throw failure;}
                finally {sqlNanos.addAndGet(System.nanoTime()-started);}
            }
            return invoke(method,delegate,args);
        });
    }
    private PreparedStatement statement(PreparedStatement delegate,String sql,Runnable dirty) {
        String verb=sql.startsWith("INSERT")?"insert":sql.startsWith("UPDATE")?"update":sql.startsWith("DELETE")?"delete":"other";
        Matcher matcher=TABLE.matcher(sql);String table=matcher.find()?matcher.group(1):null;
        boolean dml=table!=null&&!verb.equals("other");
        int[] pending={0};
        return (PreparedStatement)Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("addBatch")){Object result=invoke(method,delegate,args);pending[0]++;return result;}
            if(name.equals("clearBatch")){Object result=invoke(method,delegate,args);pending[0]=0;return result;}
            if(!EXECUTIONS.contains(name))return invoke(method,delegate,args);
            if(name.equals("executeQuery"))queries.incrementAndGet();
            else if(name.equals("executeUpdate"))updates.incrementAndGet();
            else if(name.equals("executeBatch")){batches.incrementAndGet();entries.addAndGet(pending[0]);}
            else commands.incrementAndGet();
            if(dml)dirty.run();
            long started=System.nanoTime();
            try {
                Object result=invoke(method,delegate,args);
                if(dml) {
                    if(result instanceof int[] counts){boolean failed=false;for(int count:counts){count(count);if(count==Statement.EXECUTE_FAILED)failed=true;}if(failed)failures.incrementAndGet();}
                    else if(result instanceof Integer count)count(count);
                }
                return result;
            }catch(Throwable failure){failures.incrementAndGet();throw failure;}
            finally {if(name.equals("executeBatch"))pending[0]=0;sqlNanos.addAndGet(System.nanoTime()-started);}
        });
    }
    private void count(int result){if(result>=0)affected.addAndGet(result);else if(result==Statement.SUCCESS_NO_INFO)unknown.incrementAndGet();}
    private static Object invoke(Method method,Object delegate,Object[] args)throws Throwable {
        try{return method.invoke(delegate,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    void imported(int rows){initialCommits++;initialRows+=rows;}
    void changed(ApocBRPlayerRelationalMutations.Batch batch,List<ApocBRPlayerRelationalDelta.Change> changes) {changed(batch,changes,null,null);}
    void changed(ApocBRPlayerRelationalMutations.Batch batch,List<ApocBRPlayerRelationalDelta.Change> changes,
                 Map<ApocBRPlayerRelationalDelta.Key,Map<String,Object>> before,Map<ApocBRPlayerRelationalDelta.Key,Map<String,Object>> after) {
        if(players.size()<ApocBRPlayerRelationalMutations.MAX_PLAYERS)players.add(batch.session());
        if(changes!=null)for(var change:changes) {
            String op=change.before()==null?"insert":change.after()==null?"delete":"update";
            add(tables,change.key().table()+"."+op);logicalRows++;
            if(op.equals("update"))for(String column:change.columns())add(columns,change.key().table()+"."+column);
            if(change.key().table().equals("value_node")) {
                var row=change.after()==null?change.before():change.after();
                var componentKey=new ApocBRPlayerRelationalDelta.Key("component",List.of(row.get("component_id")));
                Map<String,Object> component=after==null?null:after.get(componentKey);if(component==null&&before!=null)component=before.get(componentKey);
                if(component!=null)add(components,(String)component.get("kind"));
            }
        }
        Set<String> scalarRows=new HashSet<>();
        for(var entry:batch.fields().entrySet()) {
            var field=entry.getKey();add(columns,field.table()+"."+field.column());
            if(scalarRows.add(field.table()+":"+field.key())){add(tables,field.table()+".update");logicalRows++;}
        }
    }
    private static void add(Map<String,Long> map,String key){map.merge(key,1L,Long::sum);}
    private static String top(Map<String,Long> values,int limit) {
        return values.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(limit).map(e->e.getKey()+":"+e.getValue()).reduce((a,b)->a+","+b).orElse("none");
    }
    String report(long now) {
        long total=commits.get(),writes=writeCommits.get(),calls=queries.get()+updates.get()+commands.get()+batches.get();
        double seconds=Math.max(.001,(now-lastReport)/1_000_000_000.0);
        String rates=String.format(Locale.ROOT,"; windowSeconds=%.3f; txPerSecond=%.3f; writeTxPerSecond=%.3f; jdbcCallsPerSecond=%.3f; changedRowsPerSecond=%.3f",seconds,(total-lastCommits)/seconds,(writes-lastWrites)/seconds,(calls-lastCalls)/seconds,(logicalRows-lastRows)/seconds);
        lastReport=now;lastCommits=total;lastWrites=writes;lastCalls=calls;lastRows=logicalRows;
        String result="; txCommits="+total+"; writeTx="+writes+"; readTx="+(total-writes)+"; txRollbacks="+rollbacks.get()
            +"; jdbcQueries="+queries.get()+"; jdbcUpdates="+updates.get()+"; jdbcCommands="+commands.get()+"; jdbcBatches="+batches.get()+"; batchEntries="+entries.get()
            +"; jdbcAffectedRows="+affected.get()+"; jdbcUnknownRows="+unknown.get()+"; jdbcFailures="+failures.get()+"; jdbcMs="+sqlNanos.get()/1_000_000
            +"; poolLeases="+leases.get()+"; poolBorrowMs="+borrowNanos.get()/1_000_000+"; poolMaxBorrowUs="+maxBorrowNanos.get()/1000+"; poolFailures="+borrowFailures.get()
            +"; initialCommits="+initialCommits+"; initialRows="+initialRows+"; windowPlayers="+players.size()+rates
            +"; incompleteGraphs="+incompleteGraphs+"; incompleteReasons="+top(incompleteReasons,8)+"; topTables="+top(tables,10)+"; topColumns="+top(columns,12)+"; topComponents="+top(components,8);
        players.clear();tables.clear();columns.clear();components.clear();incompleteReasons.clear();return result;
    }
}
