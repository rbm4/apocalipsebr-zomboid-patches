package zombie.savefile;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import static zombie.savefile.ApocBRPlayerRelationalAccess.*;

/** Bounded character scalar sampling on the game thread, versioned dirty fields on the exporter. */
public final class ApocBRPlayerRelationalMutations {
    static final long CADENCE=1_000_000_000L;
    static final int MAX_PLAYERS=512,MAX_FIELDS=256;
    private static final boolean ENABLED=Boolean.parseBoolean(System.getProperty("apocbr.postgres.playerRelational.mutations.enabled","true"));
    private static final Set<String> SKIP=Set.of("snapshot_id","character_id","inventory_container_id","source_offset_x","source_offset_y");
    private static final List<String> SINGLETONS=List.of("character_state","character_nutrition","character_effects",
            "character_presentation","character_descriptor","body_damage");
    private static final Map<IsoPlayer,Cursor> TRACKED=new WeakHashMap<>(); // Game-thread owned; never shared with JDBC.
    private static final DirtyQueue DIRTY=new DirtyQueue();
    private static long tickDeadline,lastLog,lastSampleFailure,lastWriteFailure;
    private static long lastCleanup,lastCapacityLog;
    private static int samples;
    private static volatile boolean active;
    record Field(String table,String column,String key) {
        Field {
            boolean valid=SINGLETONS.contains(table)
                    ?key==null&&!SKIP.contains(column)&&ApocBRPlayerRelationalSchema.TABLES.get(table).stream().anyMatch(c->c.name().equals(column))
                    :table.equals("character_stat")&&column.equals("value")&&key!=null&&key.length()<=256;
            if(!valid)throw new IllegalArgumentException("Invalid tracked field");
        }
    }
    private static final class Cursor {
        final ApocBRPlayerRelationalDatabase.Candidate candidate;
        final UUID session=UUID.randomUUID();
        Map<Field,Object> observed=Map.of();
        long nextSample;
        long lastSeen=System.nanoTime();
        Cursor(ApocBRPlayerRelationalDatabase.Candidate candidate){this.candidate=candidate;}
    }
    record Value(long revision,Object value) {}
    record Batch(ApocBRPlayerRelationalDatabase.Candidate candidate,UUID session,UUID character,Map<Field,Value> fields,Instant capturedAt) {}
    static final class DirtyQueue {
        private static final class Pending {
            final ApocBRPlayerRelationalDatabase.Candidate candidate;
            final UUID session;
            final Map<Field,Value> fields=new LinkedHashMap<>();
            long nextAt;
            int failures;
            Instant capturedAt;
            Pending(ApocBRPlayerRelationalDatabase.Candidate c,UUID s,long now){candidate=c;session=s;nextAt=now;}
        }
        private final Map<String,Pending> pending=new LinkedHashMap<>();
        private record Binding(UUID session,UUID character,long nextAt) {}
        private final Map<String,Binding> bindings=new LinkedHashMap<>();
        private long revision;
        synchronized boolean register(String key,UUID session) {
            if(!bindings.containsKey(key)&&bindings.size()>=MAX_PLAYERS)return false;
            bindings.put(key,new Binding(session,null,0));pending.remove(key);return true;
        }
        synchronized void bind(String key,UUID session,UUID character) {
            Binding binding=bindings.get(key);
            if(binding!=null&&binding.session.equals(session))bindings.put(key,new Binding(session,character,binding.nextAt));
        }
        synchronized void bindCurrent(String key,UUID character) {
            Binding binding=bindings.get(key);if(binding!=null)bind(key,binding.session,character);
        }
        synchronized boolean mark(ApocBRPlayerRelationalDatabase.Candidate c,UUID session,Map<Field,Object> changes,long now,Instant capturedAt) {
            if(changes.size()>MAX_FIELDS)throw new IllegalStateException("LIVE_FIELD_LIMIT");
            for(Object value:changes.values())if(value!=null&&!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof Float||value instanceof Double||value instanceof Boolean||value instanceof String text&&text.length()<=256))throw new IllegalArgumentException("Invalid live scalar");
            Pending p=pending.get(c.key());
            if(p==null||!p.session.equals(session)) {
                if(p==null&&pending.size()>=MAX_PLAYERS)return false;
                p=new Pending(c,session,now);pending.put(c.key(),p);
                Binding binding=bindings.get(c.key());if(binding!=null&&binding.session.equals(session))p.nextAt=Math.max(now,binding.nextAt);
            }
            Set<Field> combined=new HashSet<>(p.fields.keySet());combined.addAll(changes.keySet());
            if(combined.size()>MAX_FIELDS)throw new IllegalStateException("LIVE_FIELD_LIMIT");
            for(var e:changes.entrySet())p.fields.put(e.getKey(),new Value(++revision,e.getValue()));
            p.capturedAt=capturedAt;return true;
        }
        synchronized Batch poll(long now) {
            for(Pending p:pending.values())if(!p.fields.isEmpty()&&now>=p.nextAt) {
                Binding binding=bindings.get(p.candidate.key());
                if(binding==null||binding.character==null||!binding.session.equals(p.session))continue;
                p.nextAt=now+CADENCE;
                bindings.put(p.candidate.key(),new Binding(binding.session,binding.character,p.nextAt));
                return new Batch(p.candidate,p.session,binding.character,Collections.unmodifiableMap(new LinkedHashMap<>(p.fields)),p.capturedAt);
            }
            return null;
        }
        synchronized void finish(Batch batch,boolean committed,long now) {
            Pending p=pending.get(batch.candidate.key());if(p==null||!p.session.equals(batch.session))return;
            if(committed) {
                for(var e:batch.fields.entrySet())if(Objects.equals(p.fields.get(e.getKey()),e.getValue()))p.fields.remove(e.getKey());
                p.failures=0;
                if(p.fields.isEmpty())pending.remove(batch.candidate.key());
            } else {
                p.nextAt=now+Math.min(30,1L<<Math.min(5,++p.failures))*CADENCE;
            }
        }
        synchronized int size(){return pending.size();}
        synchronized void prune(Set<UUID> active){bindings.entrySet().removeIf(e->!active.contains(e.getValue().session)&&!pending.containsKey(e.getKey()));}
        synchronized void clear(){pending.clear();bindings.clear();}
    }
    static UUID register(IsoPlayer player,ApocBRPlayerRelationalDatabase.Candidate candidate) {
        if(!ENABLED)return null;
        Cursor old=TRACKED.get(player);if(old!=null)return old.session;
        cleanup(System.nanoTime());
        if(TRACKED.size()>=MAX_PLAYERS){capacityLog();return null;}
        Cursor cursor=new Cursor(candidate);
        if(!DIRTY.register(candidate.key(),cursor.session)){capacityLog();return null;}
        TRACKED.put(player,cursor);active=true;return cursor.session;
    }
    private static void capacityLog(){long now=System.nanoTime();if(now-lastCapacityLog>=30_000_000_000L){lastCapacityLog=now;log("LIVE_TRACK_LIMIT; retryOnNextLoad=true");}}
    static void bind(ApocBRPlayerRelationalDatabase.Candidate candidate,UUID session,UUID character){DIRTY.bind(candidate.key(),session,character);}
    static void bindCurrent(ApocBRPlayerRelationalDatabase.Candidate candidate,UUID character){DIRTY.bindCurrent(candidate.key(),character);}
    /** Budget all players together, not one millisecond per player. */
    public static void beginTick() {long now=System.nanoTime();if(now-lastCleanup>=30_000_000_000L)cleanup(now);tickDeadline=System.nanoTime()+1_000_000L;samples=0;}
    private static void cleanup(long now) {
        lastCleanup=now;TRACKED.entrySet().removeIf(e->now-e.getValue().lastSeen>300_000_000_000L);
        Set<UUID> active=new HashSet<>();for(Cursor c:TRACKED.values())active.add(c.session);DIRTY.prune(active);
    }
    public static void observe(IsoPlayer player) {
        if(!active)return;
        long now=System.nanoTime();Cursor cursor=TRACKED.get(player);
        if(cursor==null)return;cursor.lastSeen=now;
        if(now<cursor.nextSample||samples>=16||now>=tickDeadline)return;
        sample(player,cursor,now);
    }
    /** Native saves/disconnects may request a final sample regardless of cadence. */
    public static void finalSample(IsoPlayer player) {
        if(!active)return;
        Cursor cursor=TRACKED.get(player);if(cursor!=null)sample(player,cursor,System.nanoTime());
    }
    private static void sample(Object player,Cursor cursor,long now) {
        try {
            samples++;long started=System.nanoTime();Map<Field,Object> values=read(player);
            Map<Field,Object> changed=new LinkedHashMap<>();
            for(var e:values.entrySet())if(!cursor.observed.containsKey(e.getKey())||!Objects.equals(cursor.observed.get(e.getKey()),e.getValue()))changed.put(e.getKey(),e.getValue());
            if(changed.isEmpty()||DIRTY.mark(cursor.candidate,cursor.session,changed,now,Instant.now())) {
                cursor.observed=values;cursor.nextSample=now+CADENCE;
            }
            long elapsed=System.nanoTime()-started;measuredSamples.incrementAndGet();measuredFields.addAndGet(changed.size());sampleNanos.addAndGet(elapsed);maxSampleNanos.accumulateAndGet(elapsed,Math::max);
        }catch(RuntimeException|LinkageError failure){cursor.nextSample=now+CADENCE;if(now-lastSampleFailure>=30_000_000_000L){lastSampleFailure=now;log("LIVE_SAMPLE_FAILED; exception="+failure.getClass().getSimpleName());}}
    }
    private record Plan(Field field,String path,String type,boolean required) {}
    private record Group(String table,String source,List<Plan> plans) {}
    private static final List<Group> GROUPS;
    static {
        ArrayList<Group> groups=new ArrayList<>();
        for(String table:SINGLETONS) {
            ArrayList<Plan> plans=new ArrayList<>();
            for(var column:ApocBRPlayerRelationalSchema.TABLES.get(table))if(!SKIP.contains(column.name()))
                plans.add(new Plan(new Field(table,column.name(),null),ApocBRPlayerRelationalCapture.path(table,column.name()),column.type(),column.required()));
            String source=switch(table){case "character_nutrition"->"nutrition";case "character_descriptor"->"descriptor";case "body_damage"->"bodyDamage";default->"";};
            groups.add(new Group(table,source,List.copyOf(plans)));
        }
        GROUPS=List.copyOf(groups);
    }
    static Map<Field,Object> read(Object player) {
        Map<Field,Object> values=new LinkedHashMap<>();
        for(Group group:GROUPS) {
            Object source=group.source.isEmpty()?player:get(player,group.source);
            // Descriptor rows are optional in the initial codec; absence is not a deletion mark.
            if(source==null&&group.table.equals("character_descriptor"))continue;
            if(source==null)throw new IllegalStateException("Missing live scalar source");
            for(Plan plan:group.plans) {
                Object value=get(source,plan.path);
                if(value==null&&plan.required)throw new IllegalStateException("Missing live scalar");
                if(value!=null)value=switch(plan.type) {
                    case "text" -> key(value);
                    case "real" -> ((Number)value).floatValue();
                    case "double precision" -> ((Number)value).doubleValue();
                    case "integer" -> ((Number)value).intValue();
                    case "bigint" -> ((Number)value).longValue();
                    default -> value;
                };
                values.put(plan.field,value);
            }
        }
        for(var stat:ApocBRPlayerRelationalSnapshot.stats(player))values.put(new Field("character_stat","value",stat.key()),((Number)stat.value()).floatValue());
        if(values.size()>MAX_FIELDS)throw new IllegalStateException("LIVE_FIELD_LIMIT");return values;
    }
    private static final AtomicLong measuredSamples=new AtomicLong(),measuredFields=new AtomicLong(),sampleNanos=new AtomicLong(),maxSampleNanos=new AtomicLong();
    private static long writes,writeNanos;
    static boolean flushOne(ApocBRPlayerRelationalDatabase database) {
        if(!ENABLED)return false;
        long now=System.nanoTime();Batch batch=DIRTY.poll(now);if(batch==null){metrics(now);return false;}
        boolean committed=false;
        try {committed=database.applyFields(batch);if(committed)writes++;}
        catch(java.sql.SQLException|RuntimeException|LinkageError failure){if(now-lastWriteFailure>=30_000_000_000L){lastWriteFailure=now;log("LIVE_WRITE_FAILED; SQLState="+(failure instanceof java.sql.SQLException sql?sql.getSQLState():"LOCAL"));}}
        finally {writeNanos+=System.nanoTime()-now;DIRTY.finish(batch,committed,System.nanoTime());}
        metrics(now);
        return true;
    }
    private static void metrics(long now) {
        if(now-lastLog>=30_000_000_000L){lastLog=now;log("LIVE_METRICS; samples="+measuredSamples.get()+"; changedFields="+measuredFields.get()+"; sampleMs="+sampleNanos.get()/1_000_000+"; maxSampleUs="+maxSampleNanos.get()/1000+"; writes="+writes+"; writeMs="+writeNanos/1_000_000+"; pending="+DIRTY.size());}
    }
    private static void log(String text){DebugLog.log("[ApocBR][PlayerRelational] "+text);}
    static void shutdown(){active=false;DIRTY.clear();TRACKED.clear();}
    private ApocBRPlayerRelationalMutations() {}
}
