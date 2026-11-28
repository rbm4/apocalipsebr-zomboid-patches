package cloud.apocalipse.pz.agent.features.optimization.animal.perception;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.los.perception.PerceptionBuckets;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry.Counter;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry.Phase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.behavior.BaseAnimalBehavior;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoUtils;
import zombie.network.GameServer;

import static cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry.count;

/** Shares one ordered cell scan across the animal perception calls in a scheduler pass. */
public final class ServerAnimalPerception {
    private static final int INTERVAL = AgentConfiguration.animalLosIntervalTicks();
    private static final int BUCKET_SIZE = 16;
    private static final float ZOMBIE_RADIUS = 10.0F;
    private static final long UNINDEXED = Long.MIN_VALUE;
    private static final long UNPOSITIONED = Long.MAX_VALUE;
    private static final Comparator<Candidate> CELL_ORDER = Comparator.comparingInt(candidate -> candidate.position);

    // Only accessed by the server simulation owner.
    private static Frame current;

    private ServerAnimalPerception() {
    }

    public static Frame beginFrame(long tick) {
        if (!GameServer.server || Thread.currentThread() != GameServer.mainThread) return null;
        current = new Frame(tick, current);
        return current;
    }

    public static void endFrame(Frame frame) {
        if (frame != null) current = frame.previous;
    }

    public static void updateLos(IsoAnimal animal) {
        Frame frame = activeFrame();
        if (frame == null) {
            animal.updateLOS();
            return;
        }

        BaseAnimalBehavior behavior = animal.getBehavior();
        // The legacy optimization ages the alert cooldown once per update, independent of candidate count.
        if (behavior.lastAlerted > 0.0F) {
            behavior.lastAlerted = Math.max(0.0F, behavior.lastAlerted - GameTime.getInstance().getMultiplier());
        } else if (behavior.lastAlerted < 0.0F) {
            behavior.lastAlerted = 0.0F;
        }
        if (Math.floorMod(frame.tick, INTERVAL) != Math.floorMod(animal.getID(), INTERVAL)) {
            count(Counter.ANIMAL_LOS_SKIPPED);
            return;
        }

        IsoAnimal previous = frame.animal;
        frame.animal = animal;
        animal.spottedChr = null;
        long started = System.nanoTime();
        try {
            animal.updateLOS();
        } finally {
            frame.animal = previous;
            Phase.ANIMAL_PERCEPTION.recordSince(started);
        }
    }

    public static Iterator<IsoMovingObject> candidates(Set<IsoMovingObject> objects, IsoAnimal animal) {
        Frame frame = activeFrame();
        if (frame == null || frame.animal != animal || !(objects instanceof TrackedObjects tracked)
                || !indexable(animal.getX()) || !indexable(animal.getY())) {
            return objects.iterator();
        }

        if (tracked.index == null) tracked.index = new Index(tracked);
        Index index = tracked.index;

        float x = animal.getX();
        float y = animal.getY();
        List<Candidate> nearby = new ArrayList<>(index.humans);
        Candidate self = index.animals.get(animal);
        if (self != null) nearby.add(self);
        if (animal.adef.fleeZombies) {
            if (frame.refreshed != index) {
                index.refreshZombies();
                frame.refreshed = index;
            }
            int humanAndSelfCount = nearby.size();
            for (int column = bucket(x - ZOMBIE_RADIUS); column <= bucket(x + ZOMBIE_RADIUS); column++) {
                for (int row = bucket(y - ZOMBIE_RADIUS); row <= bucket(y + ZOMBIE_RADIUS); row++) {
                    index.buckets.append(key(column, row), nearby);
                }
            }
            nearby.addAll(index.unusualZombies);
            List<Candidate> zombies = nearby.subList(humanAndSelfCount, nearby.size());
            count(Counter.ANIMAL_PERCEPTION_BUCKET_CANDIDATES, zombies.size());
            zombies.removeIf(candidate -> IsoUtils.DistanceTo(x, y, candidate.object.getX(),
                candidate.object.getY()) > ZOMBIE_RADIUS);
        }

        nearby.sort(CELL_ORDER);
        count(Counter.ANIMAL_PERCEPTION_CANDIDATES, nearby.size());
        Iterator<Candidate> ordered = nearby.iterator();
        return new Iterator<>() {
            @Override public boolean hasNext() { return ordered.hasNext(); }
            @Override public IsoMovingObject next() { return ordered.next().object; }
        };
    }

    public static void spotted(BaseAnimalBehavior behavior, IsoMovingObject other, boolean forced, float distance,
                               IsoAnimal animal) {
        Frame frame = activeFrame();
        if (frame == null || frame.animal != animal) {
            behavior.spotted(other, forced, distance);
            return;
        }
        if (!animal.getCell().getObjectList().contains(other)) return;

        // The marker belongs to this one native invocation. Any nested direct spotting has vanilla bookkeeping.
        BaseAnimalBehavior previous = frame.prepared;
        frame.prepared = behavior;
        try {
            behavior.spotted(other, forced, distance);
        } finally {
            frame.prepared = previous;
        }
    }

    public static boolean alreadyPrepared(BaseAnimalBehavior behavior) {
        Frame frame = activeFrame();
        if (frame == null || frame.prepared != behavior) return false;
        frame.prepared = null;
        return true;
    }

    public static Set<IsoMovingObject> track(Set<IsoMovingObject> objects) {
        return GameServer.server ? new TrackedObjects(objects) : objects;
    }

    public static void moved(Object object, float before, float after) {
        if (!(object instanceof IsoZombie)) return;
        Frame frame = activeFrame();
        if (frame == null || (indexable(before) == indexable(after) && bucket(before) == bucket(after))) return;
        for (Frame scope = frame; scope != null; scope = scope.previous) scope.refreshed = null;
    }

    private static Frame activeFrame() {
        return Thread.currentThread() == GameServer.mainThread ? current : null;
    }

    private static boolean indexable(float coordinate) {
        return Math.abs(coordinate) <= 8_000_000.0F;
    }

    private static int bucket(float coordinate) {
        return (int) Math.floor(coordinate / BUCKET_SIZE);
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | Integer.toUnsignedLong(y);
    }

    public static final class Frame {
        private final long tick;
        private final Frame previous;
        private Index refreshed;
        private IsoAnimal animal;
        private BaseAnimalBehavior prepared;

        private Frame(long tick, Frame previous) {
            this.tick = tick;
            this.previous = previous;
        }
    }

    private static final class Candidate {
        private final int position;
        private final IsoMovingObject object;
        private long key = UNINDEXED;

        private Candidate(int position, IsoMovingObject object) {
            this.position = position;
            this.object = object;
        }
    }

    private static final class Index {
        private final PerceptionBuckets<Candidate> buckets = new PerceptionBuckets<>();
        private final List<Candidate> zombies = new ArrayList<>();
        private final List<Candidate> unusualZombies = new ArrayList<>();
        private final List<Candidate> humans = new ArrayList<>();
        private final IdentityHashMap<IsoAnimal, Candidate> animals = new IdentityHashMap<>();

        private Index(TrackedObjects source) {
            long started = System.nanoTime();
            int position = 0;
            for (IsoMovingObject object : source) {
                if (object instanceof IsoAnimal animal) {
                    animals.put(animal, new Candidate(position, animal));
                } else if (object instanceof IsoPlayer) {
                    humans.add(new Candidate(position, object));
                } else if (object instanceof IsoZombie) {
                    Candidate candidate = new Candidate(position, object);
                    zombies.add(candidate);
                }
                position++;
            }
            count(Counter.ANIMAL_PERCEPTION_MEMBERSHIP_CHECKED, position);
            Phase.ANIMAL_PERCEPTION_GRID.recordSince(started);
        }

        private void refreshZombies() {
            long started = System.nanoTime();
            for (Candidate zombie : zombies) {
                float x = zombie.object.getX();
                float y = zombie.object.getY();
                long key = indexable(x) && indexable(y) ? key(bucket(x), bucket(y)) : UNPOSITIONED;
                if (key == zombie.key) continue;
                if (zombie.key == UNPOSITIONED) unusualZombies.remove(zombie);
                else if (zombie.key != UNINDEXED) buckets.remove(zombie.key, zombie);
                if (key == UNPOSITIONED) unusualZombies.add(zombie);
                else buckets.add(key, zombie);
                zombie.key = key;
            }
            count(Counter.ANIMAL_PERCEPTION_GRID_CHECKED, zombies.size());
            Phase.ANIMAL_PERCEPTION_GRID.recordSince(started);
        }
    }

    /** Preserves native HashSet membership and order while exposing structural changes. */
    private static final class TrackedObjects extends HashSet<IsoMovingObject> {
        private Index index;

        private TrackedObjects(Set<IsoMovingObject> original) {
            super();
            addAll(original);
        }

        @Override public boolean add(IsoMovingObject object) {
            if (!super.add(object)) return false;
            index = null;
            return true;
        }

        @Override public boolean remove(Object object) {
            if (!super.remove(object)) return false;
            index = null;
            return true;
        }

        @Override public void clear() {
            if (!isEmpty()) index = null;
            super.clear();
        }

        @Override public Iterator<IsoMovingObject> iterator() {
            Iterator<IsoMovingObject> iterator = super.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public IsoMovingObject next() { return iterator.next(); }
                @Override public void remove() {
                    iterator.remove();
                    index = null;
                }
            };
        }
    }
}
