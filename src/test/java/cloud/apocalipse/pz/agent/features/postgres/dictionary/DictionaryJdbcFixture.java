// JDBC fixture adapted from tools/test_postgres_world_dictionary.py; no legacy classes are compiled.

package cloud.apocalipse.pz.agent.features.postgres.dictionary;
import java.sql.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;

import org.postgresql.*;
final class DictionaryJdbcFixture {
 static int checks;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
 static Object zero(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
 static Object proxy(Class<?> type,InvocationHandler handler){return Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);}
 static class Fake {
  int opened,closed,commits,rollbacks,notifications;boolean failNotify,failBatch;long revision;String name;String build="42.22.0";int world=251,format=1;boolean wrongOwner;
  List<String> order=new ArrayList<>();Map<String,List<Object>> registries=new LinkedHashMap<>(),entries=new LinkedHashMap<>();
  List<Object[]> foreign=List.of();List<Object> viewValues;String event;String failPrefix;SQLException injected;
  List<List<Object>> states=new ArrayList<>(),overrides=new ArrayList<>(),registryStates=new ArrayList<>();
  ResultSet rows(List<Object[]> values){int[] index={-1};return (ResultSet)proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
   case "next"->++index[0]<values.size();case "getString","getObject"->values.get(index[0])[(int)a[0]-1];case "getInt"->((Number)values.get(index[0])[(int)a[0]-1]).intValue();case "getLong"->((Number)values.get(index[0])[(int)a[0]-1]).longValue();default->zero(m.getReturnType());});}
  List<Object[]> select(String sql,Map<Integer,Object> a)throws Exception {
   if(sql.startsWith("SELECT current_revision"))return List.<Object[]>of(new Object[]{revision,name,build,world,format});
   if(sql.startsWith("SELECT v.id"))return viewValues!=null&&viewValues.get(0).equals(a.get(1))&&viewValues.get(3).equals(a.get(2))&&viewValues.get(4).equals(a.get(3))&&viewValues.get(2).equals(a.get(4))?List.<Object[]>of(new Object[]{viewValues.get(1)}):List.of();
   if(sql.contains("FROM player_rel.world_dictionary_registry_state WHERE"))return registryStates.stream().map(v->v.subList(2,5).toArray()).toList();
   if(sql.contains("FROM player_rel.world_dictionary_entry_state WHERE"))return states.stream().map(v->v.subList(2,14).toArray()).toList();
   if(sql.contains("FROM player_rel.world_dictionary_mod_override WHERE"))return overrides.stream().sorted(Comparator.comparing((List<Object> v)->v.get(2)+":"+v.get(3)+":"+v.get(4)).thenComparingInt(v->(Integer)v.get(6))).map(v->new Object[]{v.get(2),v.get(3),v.get(4),v.get(7)}).toList();
   if(sql.startsWith("SELECT 1 FROM"))return wrongOwner?List.<Object[]>of(new Object[]{1}):List.of();
   if(sql.startsWith("SELECT family"))return registries.values().stream().map(v->new Object[]{v.get(1),v.get(2),v.get(3),v.get(4)}).toList();
   if(sql.startsWith("SELECT family,register_name,native_id"))throw new AssertionError("unreachable");
   if(sql.startsWith("SELECT DISTINCT"))return foreign;
   if(sql.startsWith("SELECT pg_notify")){order.add("notify");if(failNotify)throw new SQLException("secret SQL","08006");notifications++;return List.of();}
   throw new AssertionError(sql);
  }
  int write(String sql,List<Object> a)throws Exception {
   order.add(sql);if(injected!=null&&sql.startsWith(failPrefix))throw injected;
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_runtime_view(")){check(sql.contains("server_id")&&!sql.contains("server_key"),"publication supplies integer ID and lets V7 bind canonical key");viewValues=a;states.clear();overrides.clear();registryStates.clear();}
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_registry_state("))registryStates.add(new ArrayList<>(a));
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_entry_state("))states.add(new ArrayList<>(a));
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_mod_override("))overrides.add(new ArrayList<>(a));
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_journal"))event=(String)a.get(3);
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_namespace")){name=(String)a.get(1);return 1;}
   if(sql.startsWith("UPDATE player_rel.world_dictionary_namespace")){revision=((Number)a.get(0)).longValue();return 1;}
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_registry(")){registries.put(a.get(1)+":"+a.get(2),new ArrayList<>(a));return 1;}
   if(sql.startsWith("INSERT INTO player_rel.world_dictionary_entry(")){String key=a.get(1)+":"+a.get(2)+":"+a.get(3);check(!entries.containsKey(key),"identity not rewritten");entries.put(key,new ArrayList<>(a));return 1;}
   return 1;
  }
  Connection open(){opened++;return (Connection)proxy(Connection.class,(p,m,a)->{
   switch(m.getName()){
    case "close":closed++;return null;
    case "commit":order.add("commit");commits++;return null;
    case "rollback":order.add("rollback");rollbacks++;return null;
    case "prepareStatement": {
     String sql=(String)a[0];Map<Integer,Object> values=new TreeMap<>();List<List<Object>> batch=new ArrayList<>();
     return proxy(PreparedStatement.class,(q,n,b)->{
      switch(n.getName()){
       case "setObject":values.put((Integer)b[0],b[1]);return null;
       case "setQueryTimeout":check(b[0].equals(sql.startsWith("SELECT DISTINCT")||sql.startsWith("SELECT family")&&!sql.contains("WHERE namespace_id=? AND view_id=?")?5:30),"publication/comparison statements use thirty seconds while history/discovery reads retain five");return null;
       case "addBatch":batch.add(new ArrayList<>(values.values()));return null;
       case "clearBatch":batch.clear();return null;
       case "executeBatch":{int[] result=new int[batch.size()];for(int i=0;i<batch.size();i++)result[i]=failBatch?Statement.EXECUTE_FAILED:write(sql,batch.get(i));return result;}
       case "executeUpdate":return write(sql,new ArrayList<>(values.values()));
       case "executeQuery": {
        if(injected!=null&&sql.startsWith(failPrefix))throw injected;
        if(sql.startsWith("SELECT family,register_name,native_id,kind,stable_key"))return rows(entries.values().stream().map(v->new Object[]{v.get(1),v.get(2),v.get(3),v.get(4),v.get(5),v.get(6),v.get(7)}).toList());
        return rows(select(sql,values));
       }
      }return zero(n.getReturnType());
     });
    }
   }return zero(m.getReturnType());
  });}
 }
}
