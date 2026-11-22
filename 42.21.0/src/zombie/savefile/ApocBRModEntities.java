package zombie.savefile;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.network.GameServer;

/** Server Lua entity boundary. Only detached scalar requests reach the existing replay worker. */
public final class ApocBRModEntities {
    private static final int LIMIT=256;
    private static final Map<String,Model> models=new LinkedHashMap<>(); // server thread only
    private static final ConcurrentLinkedQueue<Map<String,Object>> completed=new ConcurrentLinkedQueue<>();
    private static final AtomicInteger outstanding=new AtomicInteger();
    private static Thread owner;
    private static long sequence;
    private record Field(String name,String type,boolean key) {}
    private record Model(String name,String table,List<Field> fields) {
        String target(){return "mod_data.\""+table+"\"";}
        List<Field> keys(){return fields.stream().filter(Field::key).toList();}
        String columns(){return String.join(",",fields.stream().map(f->quote(f.name())).toList());}
        String where(){return String.join(" AND ",keys().stream().map(f->quote(f.name())+"=?").toList());}
    }
    private ApocBRModEntities() {}
    public static void install() {
        if(!GameServer.server)return;
        // Reinitialization is only supported before requests have been admitted.
        if(outstanding.get()!=0)throw new IllegalStateException("Mod entity requests still active");
        owner=Thread.currentThread();models.clear();completed.clear();sequence=0;
        KahluaTable api=LuaManager.platform.newTable();
        bind(api,"define",2,(f)->define(text(f.get(0)),table(f.get(1))));
        bind(api,"submit",5,(f)->submit(text(f.get(0)),text(f.get(1)),table(f.get(2)),table(f.get(3)),text(f.get(4))));
        bind(api,"poll",0,(f)->poll());
        LuaManager.env.rawset("ApocBRModEntities",api);
    }
    private interface Call {Object run(se.krka.kahlua.vm.LuaCallFrame frame);}
    private static void bind(KahluaTable api,String name,int argc,Call call) {
        api.rawset(name,(JavaFunction)(frame,count)->{
            guard();if(count!=argc)throw new IllegalArgumentException("Invalid entity argument count");
            frame.push(call.run(frame));return 1;
        });
    }
    private static void guard(){if(!GameServer.server||Thread.currentThread()!=owner)throw new IllegalStateException("Server Lua thread required");}
    private static String text(Object value){if(!(value instanceof String s))throw new IllegalArgumentException("String required");return s;}
    private static KahluaTable table(Object value){if(!(value instanceof KahluaTable t))throw new IllegalArgumentException("Table required");return t;}
    private static String identifier(String value){if(!value.matches("[a-z][a-z0-9_]{0,47}")||value.equals("revision"))throw new IllegalArgumentException("Invalid mapping identifier");return value;}
    private static String quote(String value){return "\""+value+"\"";}
    private static Object define(String name,KahluaTable declaration) {
        if(name.length()>128||name.isBlank())throw new IllegalArgumentException("Invalid entity name");
        String physical=identifier(text(declaration.rawget("table")));
        List<Field> fields=new ArrayList<>();
        var iterator=table(declaration.rawget("fields")).iterator();
        while(iterator.advance()) {
            if(fields.size()>=32)throw new IllegalArgumentException("Too many fields");
            String field=identifier(text(iterator.getKey()));
            KahluaTable spec=table(iterator.getValue());String type=text(spec.rawget("type"));
            if(!Set.of("string","uuid","number","integer","boolean").contains(type))throw new IllegalArgumentException("Unsupported field type");
            fields.add(new Field(field,type,Boolean.TRUE.equals(spec.rawget("id"))));
        }
        fields.sort(Comparator.comparing(Field::name));
        Model model=new Model(name,physical,List.copyOf(fields));
        if(model.keys().isEmpty()||model.keys().size()>4)throw new IllegalArgumentException("One to four ID fields required");
        Model existing=models.get(name);
        if(existing!=null){if(!existing.equals(model))throw new IllegalArgumentException("Mapping already defined differently");return true;}
        if(models.size()>=16||models.values().stream().anyMatch(m->m.table().equals(physical)))throw new IllegalArgumentException("Mapping capacity or duplicate table");
        models.put(name,model);return true;
    }
    private static Object scalar(Field field,Object value) {
        if(value==null)throw new IllegalArgumentException("Missing field: "+field.name());
        return switch(field.type()) {
            case "string" -> {String s=text(value);if(s.length()>4096)throw new IllegalArgumentException("String too long");yield s;}
            case "uuid" -> UUID.fromString(text(value));
            case "boolean" -> {if(!(value instanceof Boolean))throw new IllegalArgumentException("Boolean required");yield value;}
            case "number","integer" -> {
                if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Finite number required");
                double d=((Number)value).doubleValue();
                if(field.type().equals("integer")){if(Math.abs(d)>9007199254740991d||d!=Math.rint(d))throw new IllegalArgumentException("Exact integer required");yield (long)d;}
                yield d;
            }
            default -> throw new IllegalArgumentException("Unsupported type");
        };
    }
    private static Map<String,Object> copy(Model model,KahluaTable input,boolean keysOnly) {
        Map<String,Object> result=new LinkedHashMap<>();var iterator=input.iterator();
        while(iterator.advance()) {
            String name=text(iterator.getKey());
            Field field=model.fields().stream().filter(f->f.name().equals(name)&&(!keysOnly||f.key())).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown field"));
            result.put(name,scalar(field,iterator.getValue()));
        }
        for(Field f:keysOnly?model.keys():model.fields())if(!result.containsKey(f.name()))throw new IllegalArgumentException("Missing field: "+f.name());
        return Collections.unmodifiableMap(result);
    }
    private static Object submit(String name,String action,KahluaTable keyTable,KahluaTable valuesTable,String revision) {
        Model model=models.get(name);if(model==null)throw new IllegalArgumentException("Unknown entity");
        if(!Set.of("validate","find","save","delete").contains(action))throw new IllegalArgumentException("Unknown operation");
        long expected=Long.parseLong(revision);if(expected<0||expected==Long.MAX_VALUE)throw new IllegalArgumentException("Invalid revision");
        Map<String,Object> keys=action.equals("validate")?Map.of():copy(model,keyTable,true);
        Map<String,Object> values=action.equals("save")?copy(model,valuesTable,false):Map.of();
        if(action.equals("save"))for(Field key:model.keys())if(!keys.get(key.name()).equals(values.get(key.name())))throw new IllegalArgumentException("Entity ID changed");
        if(outstanding.get()>=LIMIT)return admission(false,"BUSY");
        String id=Long.toString(++sequence);outstanding.incrementAndGet();
        if(!ApocBRPersistence.request(()->execute(id,model,action,keys,values,expected))) {
            outstanding.decrementAndGet();return admission(false,"UNAVAILABLE");
        }
        return admission(true,id);
    }
    private static KahluaTable admission(boolean accepted,String value) {
        KahluaTable result=LuaManager.platform.newTable();result.rawset("accepted",accepted);result.rawset(accepted?"id":"status",value);return result;
    }
    private static void execute(String id,Model model,String action,Map<String,Object> keys,Map<String,Object> values,long expected) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);
        boolean committing=false;
        try(Connection connection=ApocBRPostgresConnector.borrowPlayerConnection()) {
            try {
                validate(connection,model);
                if(action.equals("validate"))result.put("status","OK");
                else if(action.equals("find")) {
                    try(PreparedStatement statement=prepare(connection,"SELECT "+model.columns()+",revision FROM "+model.target()+" WHERE "+model.where())) {
                        bindKeys(statement,1,model,keys);
                        try(ResultSet rows=statement.executeQuery()) {
                            if(!rows.next())result.put("status","NOT_FOUND");
                            else {
                                long revision=rows.getLong("revision");
                                if(revision<=0)throw new SQLException("Invalid revision","22003");
                                result.put("status","OK");result.put("values",read(rows,model));result.put("revision",Long.toString(revision));
                            }
                        }
                    }
                } else {
                    String sql;
                    if(action.equals("delete"))sql="DELETE FROM "+model.target()+" WHERE "+model.where()+" AND revision=?";
                    else if(expected==0)sql="INSERT INTO "+model.target()+" ("+model.columns()+",revision) VALUES ("+String.join(",",Collections.nCopies(model.fields().size(),"?"))+",1) ON CONFLICT DO NOTHING";
                    else sql="UPDATE "+model.target()+" SET "+String.join(",",model.fields().stream().filter(f->!f.key()).map(f->quote(f.name())+"=?").toList())+(model.fields().size()==model.keys().size()?"":",")+"revision=revision+1 WHERE "+model.where()+" AND revision=?";
                    try(PreparedStatement statement=prepare(connection,sql)) {
                        int index=1;
                        if(action.equals("save"))for(Field field:model.fields())if(expected==0||!field.key())statement.setObject(index++,values.get(field.name()));
                        if(action.equals("delete")||expected!=0){index=bindKeys(statement,index,model,keys);statement.setLong(index,expected);}
                        int changed=statement.executeUpdate();result.put("status",changed==1?"OK":"CONFLICT");
                        if(changed==1&&!action.equals("delete"))result.put("revision",Long.toString(expected+1));
                    }
                }
                committing=true;connection.commit();
            }catch(SQLException|RuntimeException failure){try{connection.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(SQLException failure) {
            ApocBRPersistence.failed(failure);result.clear();result.put("id",id);
            result.put("status",committing&&!action.equals("find")&&!action.equals("validate")?"OUTCOME_UNKNOWN":failure.getSQLState()!=null&&failure.getSQLState().startsWith("42")?"SCHEMA_ERROR":"DATABASE_ERROR");
        }catch(RuntimeException failure){result.clear();result.put("id",id);result.put("status","INVALID_DATA");}
        completed.add(Collections.unmodifiableMap(result));
    }
    private static PreparedStatement prepare(Connection connection,String sql)throws SQLException {
        PreparedStatement statement=connection.prepareStatement(sql);statement.setQueryTimeout(2);return statement;
    }
    private static int bindKeys(PreparedStatement statement,int index,Model model,Map<String,Object> keys)throws SQLException {
        for(Field field:model.keys())statement.setObject(index++,keys.get(field.name()));return index;
    }
    private static void validate(Connection connection,Model model)throws SQLException {
        // No automatic DDL. Validate names and JDBC types on the database worker.
        try(PreparedStatement statement=prepare(connection,"SELECT "+model.columns()+",revision FROM "+model.target()+" WHERE false");ResultSet rows=statement.executeQuery()) {
            ResultSetMetaData metadata=rows.getMetaData();int index=1;
            for(Field field:model.fields()) {
                int actual=metadata.getColumnType(index++);
                boolean valid=switch(field.type()) {
                    case "uuid" -> metadata.getColumnTypeName(index-1).equals("uuid");
                    case "string" -> actual==Types.VARCHAR||actual==Types.LONGVARCHAR;
                    case "number" -> actual==Types.DOUBLE;
                    case "integer" -> actual==Types.BIGINT;
                    case "boolean" -> actual==Types.BOOLEAN||actual==Types.BIT;
                    default -> false;
                };
                if(!valid)throw new SQLException("Entity schema mismatch","42804");
            }
            if(metadata.getColumnType(index)!=Types.BIGINT)throw new SQLException("Revision schema mismatch","42804");
        }
        Set<String> primary=new HashSet<>();
        try(ResultSet keys=connection.getMetaData().getPrimaryKeys(null,"mod_data",model.table())) {
            while(keys.next())primary.add(keys.getString("COLUMN_NAME"));
        }
        if(!primary.equals(new HashSet<>(model.keys().stream().map(Field::name).toList())))throw new SQLException("Entity primary key mismatch","42804");
    }
    private static Map<String,Object> read(ResultSet rows,Model model)throws SQLException {
        Map<String,Object> values=new LinkedHashMap<>();
        for(Field field:model.fields()) {
            Object value=rows.getObject(field.name());
            if(value==null)throw new SQLException("Null entity field","22004");
            if(value instanceof UUID)value=value.toString();
            // Reuse input validation, including finite values and Lua-exact integer bounds.
            Object checked=scalar(field,value);values.put(field.name(),checked instanceof UUID?checked.toString():checked);
        }
        return Collections.unmodifiableMap(values);
    }
    private static Object poll() {
        KahluaTable batch=LuaManager.platform.newTable();
        for(int i=1;i<=32;i++) {Map<String,Object> result=completed.poll();if(result==null)break;outstanding.decrementAndGet();batch.rawset((double)i,lua(result));}
        return batch;
    }
    private static Object lua(Object value) {
        if(value instanceof Map<?,?> map){KahluaTable table=LuaManager.platform.newTable();map.forEach((k,v)->table.rawset(k,lua(v)));return table;}
        return value instanceof Number n?n.doubleValue():value;
    }
}
