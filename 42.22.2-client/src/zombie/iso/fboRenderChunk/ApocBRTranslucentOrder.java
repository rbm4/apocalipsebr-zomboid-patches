package zombie.iso.fboRenderChunk;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.ToIntFunction;
import zombie.ApocBRClientTelemetryLite;
import zombie.ApocBRClientTelemetryLite.TranslucentWorker;

/** Owner captures references/coordinates; worker compares identity only, never dereferences game objects. */
public final class ApocBRTranslucentOrder<T> {
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("apocbr.clientTranslucent.async", "true"));
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(64), runnable -> { Thread t = new Thread(runnable, "ApocBR-Translucent-Order"); t.setDaemon(true); return t; });
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static final class Scratch {
        final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        final ArrayList<Item> sorted = new ArrayList<>();
    }
    private record Item(Object value, int order) { }
    private static final class Key {
        final Object chunk; final int player, level;
        Key(Object chunk, int player, int level) { this.chunk=chunk; this.player=player; this.level=level; }
        @Override public int hashCode() { return (System.identityHashCode(chunk)*31+player)*31+level; }
        @Override public boolean equals(Object other) { return other instanceof Key k && k.chunk==chunk && k.player==player && k.level==level; }
    }
    private static final class Job {
        final Object[] input; final int[] order; final int initialItems;
        volatile List<?> result;
        volatile boolean finished, abandoned;
        Job(Object[] input, int[] order, int initialItems) { this.input=input; this.order=order; this.initialItems=initialItems; }
    }
    private static final class Entry { Job job, display; long touched; }
    private final LinkedHashMap<Key, Entry> cache = new LinkedHashMap<>(32, .75f, true);
    private Object world;

    /** Owner only. Prevent retention across sessions and evict chunks no longer being rendered. */
    public void beginFrame(Object currentWorld) {
        if (world != currentWorld) { for (Entry e : cache.values()) if(e.job!=null) e.job.abandoned=true; cache.clear(); world=currentWorld; }
        long cutoff=System.nanoTime()-TimeUnit.SECONDS.toNanos(2);
        Iterator<Entry> it=cache.values().iterator();
        while (it.hasNext()) { Entry e=it.next(); if(e.touched>=cutoff) break; if(e.job!=null) e.job.abandoned=true; it.remove(); }
    }

    /** Never hide a changed pass: cached current results or owner catch-up keep drawing continuous. */
    @SuppressWarnings("unchecked")
    public List<T> get(Object chunk, int player, int level, List<T> items, List<T> outlines, List<T> objects, ToIntFunction<T> order) {
        Key key=new Key(chunk,player,level);
        Entry entry=cache.get(key);
        if(entry!=null) {
            entry.touched=System.nanoTime();
            Job job=entry.job;
            if(job!=null && job.result!=null && matches(job,items,outlines,objects,order)) {
                ApocBRClientTelemetryLite.translucentWorker(TranslucentWorker.REUSED,1);
                entry.display=job;
                return (List<T>)job.result;
            }
            if(entry.display!=null && matches(entry.display,items,outlines,objects,order)) {
                ApocBRClientTelemetryLite.translucentWorker(TranslucentWorker.CONTINUITY_REUSED,1);
                return (List<T>)entry.display.result;
            }
        } else {
            entry=new Entry(); cache.put(key,entry);
        }
        entry.touched=System.nanoTime();
        int count=items.size()+outlines.size()+objects.size();
        Object[] input=new Object[count]; int[] keys=new int[count]; int at=0;
        for(List<T> list:List.of(items,outlines,objects)) for(T value:list) { input[at]=value; keys[at++]=order.applyAsInt(value); }
        Job job=new Job(input,keys,items.size());
        if(entry.job==null || entry.job.finished) {
            try {
                WORKER.execute(()->prepare(job)); entry.job=job;
                ApocBRClientTelemetryLite.translucentWorker(TranslucentWorker.SUBMITTED,1);
            } catch(RejectedExecutionException full) {
                ApocBRClientTelemetryLite.translucentWorker(TranslucentWorker.QUEUE_FULL,1);
            }
        } else {
            // Do not accumulate requests behind an obsolete job; current rendering still gets a complete ordering.
            ApocBRClientTelemetryLite.translucentWorker(TranslucentWorker.PENDING,1);
        }
        while(cache.size()>1024) { Iterator<Entry> it=cache.values().iterator(); Entry old=it.next(); if(old.job!=null) old.job.abandoned=true; it.remove(); }
        if(job.result!=null) { entry.display=job; return (List<T>)job.result; }
        long started=System.nanoTime();
        Job display=new Job(input,keys,items.size());
        display.result=sort(display); display.finished=true; entry.display=display;
        ApocBRClientTelemetryLite.translucentWorker(TranslucentWorker.OWNER_CATCHUP,1);
        ApocBRClientTelemetryLite.translucentWorker(TranslucentWorker.OWNER_CATCHUP_NANOS,System.nanoTime()-started);
        return (List<T>)display.result;
    }
    private boolean matches(Job job,List<T> items,List<T> outlines,List<T> objects,ToIntFunction<T> order) {
        if(job.initialItems!=items.size() || job.input.length!=items.size()+outlines.size()+objects.size()) return false;
        int at=0;
        for(List<T> list:List.of(items,outlines,objects)) for(T value:list) {
            if(job.input[at]!=value || job.order[at++]!=order.applyAsInt(value)) return false;
        }
        return true;
    }
    private static void prepare(Job job) {
        long started=System.nanoTime();
        try {
            if(job.abandoned) return;
            List<?> result=sort(job);
            if(!job.abandoned) job.result=result;
            ApocBRClientTelemetryLite.translucentWorker(TranslucentWorker.COMPLETED,1);
        } catch (RuntimeException failure) {
            ApocBRClientTelemetryLite.translucentWorker(TranslucentWorker.FAILED,1);
        } finally {
            job.finished=true;
            ApocBRClientTelemetryLite.translucentWorker(TranslucentWorker.WORK_NANOS,System.nanoTime()-started);
        }
    }
    private static List<?> sort(Job job) {
        Scratch scratch=SCRATCH.get();
        try {
            for(int i=0;i<job.input.length;i++) {
                Object value=job.input[i];
                boolean fresh=scratch.seen.put(value,Boolean.TRUE)==null;
                // Vanilla preserves duplicates already in the initial item list; other lists append unique squares.
                if(i<job.initialItems || fresh) scratch.sorted.add(new Item(value,job.order[i]));
            }
            scratch.sorted.sort(Comparator.comparingInt(Item::order));
            ArrayList<Object> result=new ArrayList<>(scratch.sorted.size());
            for(Item item:scratch.sorted) result.add(item.value);
            return Collections.unmodifiableList(result);
        } finally {
            scratch.seen.clear(); scratch.sorted.clear();
        }
    }
}
