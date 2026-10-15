package zombie.savefile;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import static zombie.savefile.ApocBRPlayerRelationalAccess.*;

/** Game-thread scalar sampling and staggered detached reconciliation; worker-only relational deltas. */
public final class ApocBRPlayerRelationalMutations {
    // Dispatch/backoff unit; periodic sampling and graph copying use their own intervals below.
    static final long CADENCE=1_000_000_000L;
    private static final long SAMPLE_CADENCE=Math.max(1000,Math.min(60000,Long.getLong("apocbr.postgres.playerRelational.mutations.sampleMs",30000L)))*1_000_000L;
    static final int MAX_PLAYERS=512,MAX_FIELDS=256;
    private static final boolean RECONCILE=Boolean.parseBoolean(System.getProperty("apocbr.postgres.playerRelational.mutations.reconcile","true"));
    private static final long GRAPH_CADENCE=Math.max(1000,Math.min(60000,Long.getLong("apocbr.postgres.playerRelational.mutations.reconcileMs",30000L)))*1_000_000L;
    private static Cursor graphTurn;
    private static IsoPlayer graphPlayer;
    private static final AtomicLong graphSamples=new AtomicLong(),graphNanos=new AtomicLong(),graphMaxNanos=new AtomicLong(),graphCaptureFailures=new AtomicLong(),scalarCaptureFailures=new AtomicLong();
    private static final boolean ENABLED=Boolean.parseBoolean(System.getProperty("apocbr.postgres.playerRelational.mutations.enabled","true"));
    private static final Set<String> SKIP=Set.of("snapshot_id","character_id","inventory_container_id");
    private static final List<String> SINGLETONS=List.of("character_state","character_nutrition","character_effects",
            "character_presentation","character_descriptor","body_damage");
    private static final Map<IsoPlayer,Cursor> TRACKED=new WeakHashMap<>(); // Game-thread owned; never shared with JDBC.
    private static final DirtyQueue DIRTY=new DirtyQueue();
    private static long tickDeadline,lastLog,lastSampleFailure;
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
        long nextSample,nextGraph;
        long lastSeen=System.nanoTime();
        Cursor(ApocBRPlayerRelationalDatabase.Candidate candidate){this.candidate=candidate;}
    }
    record Value(long revision,Object value) {}
    record Batch(ApocBRPlayerRelationalDatabase.Candidate candidate,UUID session,UUID character,Map<Field,Value> fields,Instant capturedAt,
                 ApocBRPlayerRelationalSnapshot.Snapshot snapshot,long snapshotRevision) {
        Batch(ApocBRPlayerRelationalDatabase.Candidate c,UUID s,UUID ch,Map<Field,Value> f,Instant at){this(c,s,ch,f,at,null,0);}
    }
    static final class DirtyQueue {
        private static final class Pending {
            final ApocBRPlayerRelationalDatabase.Candidate candidate;
            final UUID session;
            final Map<Field,Value> fields=new LinkedHashMap<>();
            long nextAt;
            final long enqueuedAt;
            final long operation;
            long nextFailureLogAt;
            int failures;
            Instant capturedAt;
            ApocBRPlayerRelationalSnapshot.Snapshot snapshot;
            long snapshotRevision;
            boolean inFlight;
            ApocBRPlayerRelationalSnapshot.Snapshot inFlightSnapshot;
            Pending(ApocBRPlayerRelationalDatabase.Candidate c,UUID s,long now,long operation){candidate=c;session=s;nextAt=now;enqueuedAt=now;this.operation=operation;}
        }
        private final Map<String,Pending> pending=new LinkedHashMap<>();
        private record Binding(UUID session,UUID character,long nextAt) {}
        private final Map<String,Binding> bindings=new LinkedHashMap<>();
        private long revision,queuedBytes,nextOperation;
        synchronized boolean canSnapshot(String key,UUID session) {
            Pending p=pending.get(key);Binding b=bindings.get(key);
            return b!=null&&b.character!=null&&b.session.equals(session)
                    &&queuedBytes<=ApocBRPlayerRelationalLimits.QUEUE_BYTES-ApocBRPlayerRelationalLimits.SNAPSHOT_BYTES;
        }
        synchronized boolean snapshot(ApocBRPlayerRelationalDatabase.Candidate c,UUID session,ApocBRPlayerRelationalSnapshot.Snapshot snapshot,long now) {
            if(!canSnapshot(c.key(),session))return false;
            Pending p=pending.get(c.key());
            if(p==null){p=new Pending(c,session,now,++nextOperation);p.nextAt=Math.max(now,bindings.get(c.key()).nextAt);pending.put(c.key(),p);}
            if(p.snapshot!=null&&p.snapshot!=p.inFlightSnapshot)queuedBytes-=p.snapshot.bytes();
            p.snapshot=snapshot;p.snapshotRevision=++revision;queuedBytes+=snapshot.bytes();
            // This complete copy supersedes every earlier scalar mark; later scalar marks overlay it.
            p.fields.clear();p.capturedAt=snapshot.capturedAt();return true;
        }
        synchronized boolean register(String key,UUID session) {
            if(!bindings.containsKey(key)&&bindings.size()>=MAX_PLAYERS)return false;
            bindings.put(key,new Binding(session,null,0));Pending old=pending.remove(key);if(old!=null){if(old.snapshot!=null)queuedBytes-=old.snapshot.bytes();if(old.inFlightSnapshot!=null&&old.inFlightSnapshot!=old.snapshot)queuedBytes-=old.inFlightSnapshot.bytes();}return true;
        }
        synchronized void bind(String key,UUID session,UUID character) {
            Binding binding=bindings.get(key);
            if(binding!=null&&binding.session.equals(session))bindings.put(key,new Binding(session,character,binding.nextAt));
        }
        synchronized void bindCurrent(String key,UUID character) {
            Binding binding=bindings.get(key);if(binding!=null)bind(key,binding.session,character);
        }
        synchronized boolean mark(ApocBRPlayerRelationalDatabase.Candidate c,UUID session,Map<Field,Object> changes,long now,Instant capturedAt) {
            Binding current=bindings.get(c.key());if(current==null||!current.session.equals(session))return false;
            if(changes.size()>MAX_FIELDS)throw new IllegalStateException("LIVE_FIELD_LIMIT");
            for(Object value:changes.values())if(value!=null&&!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof Float||value instanceof Double||value instanceof Boolean||value instanceof String text&&text.length()<=256))throw new IllegalArgumentException("Invalid live scalar");
            Pending p=pending.get(c.key());
            if(p==null||!p.session.equals(session)) {
                if(p==null&&pending.size()>=MAX_PLAYERS)return false;
                p=new Pending(c,session,now,++nextOperation);pending.put(c.key(),p);
                Binding binding=bindings.get(c.key());if(binding!=null&&binding.session.equals(session))p.nextAt=Math.max(now,binding.nextAt);
            }
            Set<Field> combined=new HashSet<>(p.fields.keySet());combined.addAll(changes.keySet());
            if(combined.size()>MAX_FIELDS)throw new IllegalStateException("LIVE_FIELD_LIMIT");
            for(var e:changes.entrySet())p.fields.put(e.getKey(),new Value(++revision,e.getValue()));
            p.capturedAt=capturedAt;return true;
        }
        synchronized Batch poll(long now) {
            for(Pending p:pending.values())if((!p.fields.isEmpty()||p.snapshot!=null)&&!p.inFlight&&now>=p.nextAt) {
                Binding binding=bindings.get(p.candidate.key());
                if(binding==null||binding.character==null||!binding.session.equals(p.session))continue;
                p.nextAt=now+CADENCE;p.inFlight=true;p.inFlightSnapshot=p.snapshot;
                bindings.put(p.candidate.key(),new Binding(binding.session,binding.character,p.nextAt));
                return new Batch(p.candidate,p.session,binding.character,Collections.unmodifiableMap(new LinkedHashMap<>(p.fields)),p.capturedAt,p.snapshot,p.snapshotRevision);
            }
            return null;
        }
        private static String context(Pending p,Batch batch,long now) {
            return "operation="+p.operation+"; attempt="+(p.failures+1L)+"; pendingAgeMs="+Math.max(0,(now-p.enqueuedAt)/1_000_000)
                +"; graphRevision="+batch.snapshotRevision()+"; hasGraph="+(batch.snapshot()!=null)+"; fields="+batch.fields().size();
        }
        synchronized String failureContext(Batch batch,long now) {
            Pending p=pending.get(batch.candidate.key());
            if(p==null||!p.session.equals(batch.session)||now<p.nextFailureLogAt)return null;
            p.nextFailureLogAt=now+30_000_000_000L;
            return context(p,batch,now)+"; retryInMs="+(Math.min(30,1L<<Math.min(5,p.failures+1))*CADENCE/1_000_000);
        }
        synchronized String finish(Batch batch,boolean committed,long now) {
            Pending p=pending.get(batch.candidate.key());if(p==null||!p.session.equals(batch.session))return null;
            String recovery=committed&&p.failures>0?context(p,batch,now):null;
            p.inFlight=false;
            if(p.inFlightSnapshot!=null&&p.inFlightSnapshot!=p.snapshot)queuedBytes-=p.inFlightSnapshot.bytes();
            p.inFlightSnapshot=null;
            if(committed) {
                if(batch.snapshot()!=null&&p.snapshotRevision==batch.snapshotRevision()){queuedBytes-=p.snapshot.bytes();p.snapshot=null;}
                for(var e:batch.fields.entrySet())if(Objects.equals(p.fields.get(e.getKey()),e.getValue()))p.fields.remove(e.getKey());
                p.failures=0;
                p.nextFailureLogAt=0;
                if(p.fields.isEmpty()&&p.snapshot==null)pending.remove(batch.candidate.key());
            } else {
                p.nextAt=now+Math.min(30,1L<<Math.min(5,++p.failures))*CADENCE;
            }
            return recovery==null?null:recovery+"; queueDrained="+(!pending.containsKey(batch.candidate.key()));
        }
        synchronized int size(){return pending.size();}
        synchronized int unboundSize(){int count=0;for(Pending p:pending.values()){Binding b=bindings.get(p.candidate.key());if(b==null||b.character==null||!b.session.equals(p.session))count++;}return count;}
        synchronized long bytes(){return queuedBytes;}
        synchronized long oldestAgeMs(long now){long oldest=0;for(Pending p:pending.values())oldest=Math.max(oldest,now-p.enqueuedAt);return oldest/1_000_000;}
        synchronized void prune(Set<UUID> active){bindings.entrySet().removeIf(e->!active.contains(e.getValue().session)&&!pending.containsKey(e.getKey()));}
        synchronized void clear(){pending.clear();bindings.clear();queuedBytes=0;}
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
    /** Scalar samples share one millisecond; at most one additional one-shot graph copy per tick. */
    public static void beginTick() {long now=System.nanoTime();if(now-lastCleanup>=30_000_000_000L)cleanup(now);tickDeadline=System.nanoTime()+1_000_000L;samples=0;
        graphTurn=null;graphPlayer=null;
    }
    /** Pick only players observed in this traversal; offline loads cannot starve online players. */
    public static void endTick() {
        try {if(graphPlayer!=null)reconcile(graphPlayer,graphTurn,System.nanoTime());}
        finally {graphPlayer=null;graphTurn=null;}
    }
    private static void cleanup(long now) {
        lastCleanup=now;TRACKED.entrySet().removeIf(e->now-e.getValue().lastSeen>300_000_000_000L);
        Set<UUID> active=new HashSet<>();for(Cursor c:TRACKED.values())active.add(c.session);DIRTY.prune(active);
    }
    public static void observe(IsoPlayer player) {
        if(!active)return;
        long now=System.nanoTime();Cursor cursor=TRACKED.get(player);
        if(cursor==null)return;cursor.lastSeen=now;
        if(now>=cursor.nextSample&&samples<16&&now<tickDeadline)sample(player,cursor,now);
        if(RECONCILE&&now>=cursor.nextGraph&&(graphTurn==null||cursor.nextGraph<graphTurn.nextGraph)&&DIRTY.canSnapshot(cursor.candidate.key(),cursor.session)) {graphTurn=cursor;graphPlayer=player;}
    }
    /** Native saves/disconnects may request a final sample regardless of cadence. */
    public static void finalSample(IsoPlayer player) {
        if(!active)return;
        Cursor cursor=TRACKED.get(player);if(cursor!=null){long now=System.nanoTime();sample(player,cursor,now);if(RECONCILE)reconcile(player,cursor,now);}
    }
    private static void reconcile(Object player,Cursor cursor,long now) {
        if(!DIRTY.canSnapshot(cursor.candidate.key(),cursor.session))return;
        long started=System.nanoTime();
        try {
            var snapshot=ApocBRPlayerRelationalSnapshot.copyLive(player);
            if(DIRTY.snapshot(cursor.candidate,cursor.session,snapshot,now))cursor.nextGraph=now+GRAPH_CADENCE;
        }catch(RuntimeException|LinkageError failure){graphCaptureFailures.incrementAndGet();cursor.nextGraph=now+GRAPH_CADENCE;if(now-lastSampleFailure>=30_000_000_000L){lastSampleFailure=now;log("LIVE_GRAPH_CAPTURE_FAILED; code="+captureFailure(failure));}}
        finally {long elapsed=System.nanoTime()-started;graphSamples.incrementAndGet();graphNanos.addAndGet(elapsed);graphMaxNanos.accumulateAndGet(elapsed,Math::max);}
    }
    private static void sample(Object player,Cursor cursor,long now) {
        try {
            samples++;long started=System.nanoTime();Map<Field,Object> values=read(player);
            Map<Field,Object> changed=new LinkedHashMap<>();
            for(var e:values.entrySet())if(!cursor.observed.containsKey(e.getKey())||!Objects.equals(cursor.observed.get(e.getKey()),e.getValue()))changed.put(e.getKey(),e.getValue());
            if(changed.isEmpty()||DIRTY.mark(cursor.candidate,cursor.session,changed,now,Instant.now())) {
                cursor.observed=values;cursor.nextSample=now+SAMPLE_CADENCE;
            }
            long elapsed=System.nanoTime()-started;measuredSamples.incrementAndGet();measuredFields.addAndGet(changed.size());sampleNanos.addAndGet(elapsed);maxSampleNanos.accumulateAndGet(elapsed,Math::max);
        }catch(RuntimeException|LinkageError failure){scalarCaptureFailures.incrementAndGet();cursor.nextSample=now+SAMPLE_CADENCE;if(now-lastSampleFailure>=30_000_000_000L){lastSampleFailure=now;log("LIVE_SAMPLE_FAILED; code="+captureFailure(failure));}}
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
    private static long writes,writeNanos,lastMeasuredWriteNanos,failedPatches,deferredPatches;
    static boolean flushOne(ApocBRPlayerRelationalDatabase database) {
        if(!ENABLED)return false;
        long now=System.nanoTime();Batch batch=DIRTY.poll(now);if(batch==null){metrics(now,database);return false;}
        boolean committed=false;
        String stage="graph_projection";
        String graphIssues="none";
        try {var graph=batch.snapshot()==null?null:database.projectLive(batch);
            // Log incompleteness with the same operation context; it must not suppress
            // the exception diagnostic that follows when applyFields rejects the graph.
            if(graph!=null&&!graph.issues().isEmpty()) {
                stage="incomplete_graph";
                graphIssues=graph.issues().stream().map(issue->issue.matches("[A-Za-z0-9_]{1,100}")?issue:"UNSUPPORTED_STATE").distinct().limit(8).reduce((a,b)->a+","+b).orElse("none");
            }
            else stage="apply_fields";
            committed=database.applyFields(batch,graph);
            if(committed)writes++;
            else {deferredPatches++;String context=DIRTY.failureContext(batch,System.nanoTime());if(context!=null)log("LIVE_WRITE_DEFERRED; "+context+"; stage="+stage);}}
        catch(java.sql.SQLException|RuntimeException|LinkageError failure){failedPatches++;String context=DIRTY.failureContext(batch,System.nanoTime());if(context!=null)log("LIVE_WRITE_FAILED; "+context+"; stage="+stage+"; issues="+graphIssues+"; "+writeFailure(failure));}
        finally {writeNanos+=System.nanoTime()-now;String recovery=DIRTY.finish(batch,committed,System.nanoTime());if(recovery!=null)log("LIVE_WRITE_RECOVERED; "+recovery);}
        metrics(System.nanoTime(),database);
        return true;
    }
    private static void metrics(long now,ApocBRPlayerRelationalDatabase database) {
        if(now-lastLog>=30_000_000_000L){double busy=lastLog==0?0:100.0*(writeNanos-lastMeasuredWriteNanos)/(now-lastLog);lastMeasuredWriteNanos=writeNanos;lastLog=now;log("LIVE_METRICS; samples="+measuredSamples.get()+"; changedFields="+measuredFields.get()+"; sampleMs="+sampleNanos.get()/1_000_000+"; maxSampleUs="+maxSampleNanos.get()/1000+"; writes="+writes+"; failedPatches="+failedPatches+"; deferredPatches="+deferredPatches+"; writeMs="+writeNanos/1_000_000+"; scalarCaptureFailures="+scalarCaptureFailures.get()+"; graphCaptureFailures="+graphCaptureFailures.get()+"; graphSamples="+graphSamples.get()+"; graphMs="+graphNanos.get()/1_000_000+"; maxGraphUs="+graphMaxNanos.get()/1000+"; pending="+DIRTY.size()+"; queuedGraphBytes="+DIRTY.bytes()+"; oldestPendingMs="+DIRTY.oldestAgeMs(now)+"; workerBusyPercent="+String.format(Locale.ROOT,"%.2f",busy)+"; unboundPending="+DIRTY.unboundSize()+"; graphReconcileEnabled="+RECONCILE+database.mutationMetrics());}
    }
    static String writeFailure(Throwable failure) {
        String state=failure instanceof java.sql.SQLException sql?sql.getSQLState():"LOCAL";
        StringBuilder result=new StringBuilder("SQLState="+(state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN"));
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        // Throwable messages may contain player values or SQL parameters. Only emit types,
        // controlled capture codes and bounded stack locations, including wrapped causes.
        for(int depth=0;failure!=null&&depth<4&&seen.add(failure);depth++,failure=failure.getCause()) {
            result.append(depth==0?"; exception=":"; cause=").append(failure.getClass().getName());
            result.append("; code=").append(captureFailure(failure));
            StackTraceElement[] frames=failure.getStackTrace();
            result.append("; frames=");
            for(int i=0;i<Math.min(6,frames.length);i++) {
                if(i>0)result.append("|");
                StackTraceElement frame=frames[i];
                result.append(frame.getClassName()).append(".").append(frame.getMethodName()).append(":").append(frame.getLineNumber());
            }
        }
        return result.toString();
    }
    private static String captureFailure(Throwable failure) {
        if(failure instanceof ApocBRPlayerRelationalLimits.SizeExceeded size)return size.diagnostic();
        String message=failure.getMessage();
        return message!=null&&(message.matches("[A-Z_]+")||message.matches("Missing (capture|required|snapshot) [A-Za-z0-9_.$ ]+"))?message:failure.getClass().getSimpleName();
    }
    private static void log(String text){DebugLog.log("[ApocBR][PlayerRelational] "+text);}
    static void shutdown(){active=false;graphPlayer=null;graphTurn=null;DIRTY.clear();TRACKED.clear();}
    private ApocBRPlayerRelationalMutations() {}
}
