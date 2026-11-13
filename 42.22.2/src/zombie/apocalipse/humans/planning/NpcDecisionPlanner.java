package zombie.apocalipse.humans.planning;

import java.util.*;
import java.util.concurrent.atomic.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.movement.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;

/** Bounded value-only planning. No factory, native adapters, callbacks or Lua on the consumer. */
public final class NpcDecisionPlanner {
    public static final int MAX_SLOTS = 128, MAX_JOBS_PER_TICK = 64;
    public sealed interface Work permits Select, Route { }
    public record Select(HumanObservation observation, HumanSenses.Contact held,
                         HumanEngagement.Settings settings) implements Work {
        public Select {
            Objects.requireNonNull(observation); Objects.requireNonNull(settings);
            if (observation.senses().contacts().size() > 128 || observation.awareness().contacts().size() > 128
                || observation.visitors().size() > 128 || observation.territory().contactsInside().size() > 128)
                throw new IllegalArgumentException("Unbounded NPC observation");
        }
    }
    public record Edge(Tile from, Tile to, int cost) {
        public Edge {
            Objects.requireNonNull(from); Objects.requireNonNull(to);
            if (Math.abs((long)from.x()-to.x())+Math.abs((long)from.y()-to.y()) != 1 || cost <= 0)
                throw new IllegalArgumentException("Invalid navigation edge");
        }
    }
    /** Deeply immutable navigation values. Uncaptured cells/edges are unavailable. */
    public static final class Grid implements MovementGrid {
        private final Set<Tile> tiles;
        private final Map<Tile,Map<Tile,Integer>> edges;
        public Grid(Set<Tile> tiles, List<Edge> edges) {
            if (tiles.size() > 256 || edges.size() > 1024) throw new IllegalArgumentException("Unbounded navigation");
            this.tiles = Set.copyOf(tiles);
            Map<Tile,Map<Tile,Integer>> copy = new HashMap<>();
            for (var edge : edges) {
                if (!this.tiles.contains(edge.from()) || !this.tiles.contains(edge.to()))
                    throw new IllegalArgumentException("Edge outside snapshot");
                copy.computeIfAbsent(edge.from(), ignored -> new HashMap<>()).put(edge.to(),edge.cost());
            }
            Map<Tile,Map<Tile,Integer>> frozen = new HashMap<>();
            copy.forEach((key,value) -> frozen.put(key,Map.copyOf(value)));
            this.edges = Map.copyOf(frozen);
        }
        public boolean walkable(Tile tile) { return tiles.contains(tile); }
        public boolean connected(Tile from,Tile to) { return traversalCost(from,to)>0; }
        public int traversalCost(Tile from,Tile to) { return edges.getOrDefault(from,Map.of()).getOrDefault(to,0); }
    }
    public record Route(Grid grid, Tile start, Tile goal, boolean partial) implements Work {
        public Route { Objects.requireNonNull(grid); Objects.requireNonNull(start); Objects.requireNonNull(goal); }
    }
    public sealed interface Answer permits Selected, Planned { }
    public record Selected(HumanEngagement.Selection selection) implements Answer { }
    public record Planned(List<Tile> route) implements Answer {
        public Planned { route = List.copyOf(route); }
    }
    public record Result(long ticket, long submittedAt, Answer answer) { }
    private record Request(long ticket,long submittedAt,Work work) { }

    /** One reserved result cell per admission; only replaceable planning, never native effects. */
    public final class Slot implements AutoCloseable {
        private final int index;
        private final AtomicReference<Request> input = new AtomicReference<>();
        private final AtomicReference<Result> output = new AtomicReference<>();
        private volatile boolean closed;
        private long ticket;
        private Slot(int index) { this.index=index; }
        public long submit(Work work,long now) {
            requireOwner(); Objects.requireNonNull(work);
            if (closed || stopping || problem!=null) return -1;
            long id=++ticket;
            output.set(null);
            var request=new Request(id,now,work);
            if (asynchronous) input.set(request); else output.set(solve(request));
            return id;
        }
        public Result take(long expected) {
            requireOwner();
            if (closed || stopping || problem!=null || expected!=ticket) return null;
            var result=output.get();
            return result!=null && result.ticket()==expected && output.compareAndSet(result,null)?result:null;
        }
        public void cancel() { requireOwner(); ticket++; input.set(null); output.set(null); }
        public void close() {
            requireOwner(); if (closed) return;
            closed=true; cancel(); slots.compareAndSet(index,this,null);
        }
    }
    private final boolean asynchronous;
    private final AtomicReferenceArray<Slot> slots = new AtomicReferenceArray<>(MAX_SLOTS);
    private final AtomicBoolean consuming = new AtomicBoolean();
    private volatile Thread owner;
    private volatile boolean stopping;
    private volatile Throwable problem;
    private int cursor, captureBudget;
    public NpcDecisionPlanner(boolean asynchronous) { this.asynchronous=asynchronous; }
    public void bindOwner() {
        if (owner==null) owner=Thread.currentThread();
        requireOwner();
    }
    public void requireOwner() {
        if (owner==null || Thread.currentThread()!=owner) throw new IllegalStateException("NPC authority requires server owner");
    }
    public Slot open() {
        requireOwner();
        if (stopping || problem!=null) throw new IllegalStateException("NPC planner unavailable");
        for (int i=0;i<MAX_SLOTS;i++) if (slots.get(i)==null) {
            var slot=new Slot(i); slots.set(i,slot); return slot;
        }
        throw new IllegalStateException("NPC planning capacity exhausted");
    }
    public void beginServerTick() { requireOwner(); captureBudget=4; }
    public boolean admitCapture() {
        requireOwner(); if (stopping || problem!=null || captureBudget==0) return false;
        captureBudget--; return true;
    }
    public void tick(long ignored) {
        if (stopping || problem!=null) return;
        if (asynchronous && Thread.currentThread()==owner) throw new IllegalStateException("Async planner ran on authority thread");
        if(!consuming.compareAndSet(false,true))throw new IllegalStateException("Overlapping NPC planning consumers");
        try {
        for (int visited=0,completed=0;visited<MAX_SLOTS && completed<MAX_JOBS_PER_TICK;visited++) {
            int index=cursor;cursor=(cursor+1)%MAX_SLOTS;
            var slot=slots.get(index); if(slot==null || slot.closed) continue;
            var request=slot.input.getAndSet(null); if(request==null) continue;
            var result=solve(request);completed++;
            if(!stopping && !slot.closed) slot.output.set(result);
        }
        } finally {consuming.set(false);}
    }
    private static Result solve(Request request) {
        Answer answer;
        if (request.work() instanceof Select select) {
            var selector=new HumanEngagement.Selector();
            if(select.held()!=null) selector.retain(new HumanEngagement.Selection(select.held(),HumanEngagement.Choice.WATCH));
            answer=new Selected(selector.preview(select.observation(),HumanEngagement.GUARD_ZOMBIES,select.settings()));
        } else {
            var route=(Route)request.work();
            answer=new Planned(route.partial()?DestinationRoutePlanner.planLeg(route.grid(),route.start(),route.goal())
                :DestinationRoutePlanner.plan(route.grid(),route.start(),route.goal()));
        }
        return new Result(request.ticket(),request.submittedAt(),answer);
    }
    /** Worker failure publication never calls back into factory/Lua/native services. */
    public void fail(Throwable failure) { problem=Objects.requireNonNull(failure); stopping=true; }
    public Throwable failure() { return problem; }
    /** Signals admission/consumer stop without acquiring a factory or slot-owner lock. */
    public void stop() { stopping=true; }
}
