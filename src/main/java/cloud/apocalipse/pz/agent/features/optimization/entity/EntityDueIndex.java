package cloud.apocalipse.pz.agent.features.optimization.entity;

import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.TreeMap;
import zombie.entity.ComponentType;
import zombie.entity.EntityBucket;
import zombie.entity.GameEntity;
import zombie.entity.MetaEntity;
import zombie.entity.util.Array;
import zombie.entity.util.ImmutableArray;

/** Main-thread bucket membership and phase selection, scoped to one world. */
public final class EntityDueIndex {
    private static final IdentityHashMap<EntityBucket, EntityDueIndex> BUCKETS = new IdentityHashMap<>();
    private static final IdentityHashMap<GameEntity, ArrayList<EntityDueIndex>> OWNERS = new IdentityHashMap<>();
    private final IdentityHashMap<GameEntity, Member> members = new IdentityHashMap<>();
    private final TreeMap<Integer, Member> always = new TreeMap<>();
    private final HashMap<Integer, TreeMap<Integer, Member>> phases = new HashMap<>();
    private final PriorityQueue<Cursor> merge = new PriorityQueue<>(Comparator.comparingInt(cursor -> cursor.current.position));
    private final Array<GameEntity> selected = new Array<>(true, 16);
    private final ImmutableArray<GameEntity> view = new ImmutableArray<>(selected);

    private EntityDueIndex(ImmutableArray<GameEntity> entries) {
        for (int position = 0; position < entries.size(); position++) {
            add(entries.get(position), position);
        }
    }

    public static ImmutableArray<GameEntity> select(EntityBucket bucket) {
        ImmutableArray<GameEntity> entries = bucket.getEntities();
        if (!EntitySimulationClock.active()) {
            return entries;
        }
        EntityDueIndex index = BUCKETS.computeIfAbsent(bucket, ignored -> new EntityDueIndex(entries));
        return index.dueMembers();
    }

    public static void append(Array<GameEntity> entries, GameEntity entity, EntityBucket bucket) {
        entries.add(entity);
        UsingPlayerIndex.appended(bucket, entity, entries.size - 1);
        EntityDueIndex index = BUCKETS.get(bucket);
        if (index != null) {
            index.add(entity, entries.size - 1);
        }
    }

    public static boolean remove(Array<GameEntity> entries, GameEntity entity, boolean identity, EntityBucket bucket) {
        UsingPlayerIndex.removing(bucket, entries, entity, identity);
        EntityDueIndex index = BUCKETS.get(bucket);
        if (index == null) {
            return entries.removeValue(entity, identity);
        }
        Member member = index.members.get(entity);
        if (!identity || member == null || member.position >= entries.size || entries.get(member.position) != entity) {
            index.discard();
            BUCKETS.remove(bucket);
            return entries.removeValue(entity, identity);
        }

        GameEntity tail = entries.peek();
        entries.removeIndex(member.position);
        index.members.remove(entity);
        index.unlink(member);
        index.detach(entity);
        if (tail != entity) {
            Member swapped = index.members.get(tail);
            if (swapped == null) {
                index.discard();
                BUCKETS.remove(bucket);
            } else {
                index.unlink(swapped);
                swapped.position = member.position;
                index.link(swapped);
            }
        }
        return true;
    }

    public static void refresh(EntityBucket bucket, GameEntity entity) {
        EntityDueIndex index = BUCKETS.get(bucket);
        if (index != null) {
            index.refreshMember(entity);
        }
    }

    public static void refresh(GameEntity entity) {
        var owners = OWNERS.get(entity);
        if (owners != null) {
            for (EntityDueIndex index : owners) {
                index.refreshMember(entity);
            }
        }
    }

    static void reset() {
        BUCKETS.clear();
        OWNERS.clear();
    }

    private void add(GameEntity entity, int position) {
        Member member = new Member(entity, position, phase(entity));
        members.put(entity, member);
        OWNERS.computeIfAbsent(entity, ignored -> new ArrayList<>()).add(this);
        link(member);
    }

    private void refreshMember(GameEntity entity) {
        Member member = members.get(entity);
        if (member != null) {
            int phase = phase(entity);
            if (phase != member.phase) {
                unlink(member);
                member.phase = phase;
                link(member);
            }
        }
    }

    private static int phase(GameEntity entity) {
        if (entity.getClass() != MetaEntity.class || entity.hasComponent(ComponentType.DryingCraftLogic)) {
            return -1;
        }
        long id = entity.getEntityNetID();
        // Leave extreme IDs in the unfiltered group to preserve the legacy arithmetic.
        return id > Long.MAX_VALUE / 2 ? -1 : Math.floorMod(id, EntitySimulationClock.interval());
    }

    private void link(Member member) {
        var group = member.phase < 0 ? always : phases.computeIfAbsent(member.phase, ignored -> new TreeMap<>());
        group.put(member.position, member);
    }

    private void unlink(Member member) {
        if (member.phase < 0) {
            always.remove(member.position);
            return;
        }
        var group = phases.get(member.phase);
        group.remove(member.position);
        if (group.isEmpty()) {
            phases.remove(member.phase);
        }
    }

    private void discard() {
        for (GameEntity entity : members.keySet()) {
            detach(entity);
        }
    }

    private void detach(GameEntity entity) {
        var owners = OWNERS.get(entity);
        owners.remove(this);
        if (owners.isEmpty()) {
            OWNERS.remove(entity);
        }
    }

    private ImmutableArray<GameEntity> dueMembers() {
        selected.clear();
        merge.clear();
        include(always);
        int elapsed = EntitySimulationClock.elapsedTicks();
        int interval = EntitySimulationClock.interval();
        if (elapsed >= interval) {
            phases.values().forEach(this::include);
        } else {
            long end = EntitySimulationClock.endTick();
            for (int offset = 0; offset < elapsed; offset++) {
                include(phases.get(Math.floorMod(offset - end, interval)));
            }
        }
        while (!merge.isEmpty()) {
            Cursor cursor = merge.remove();
            selected.add(cursor.current.entity);
            if (cursor.advance()) {
                merge.add(cursor);
            }
        }
        OptimizationTelemetry.count(OptimizationTelemetry.Counter.ENTITY_BUCKET_SKIPPED, members.size() - selected.size);
        return view;
    }

    private void include(TreeMap<Integer, Member> group) {
        if (group != null && !group.isEmpty()) {
            merge.add(new Cursor(group.values().iterator()));
        }
    }

    private static final class Member {
        final GameEntity entity;
        int position;
        int phase;

        Member(GameEntity entity, int position, int phase) {
            this.entity = entity;
            this.position = position;
            this.phase = phase;
        }
    }

    private static final class Cursor {
        final Iterator<Member> entries;
        Member current;

        Cursor(Iterator<Member> entries) {
            this.entries = entries;
            advance();
        }

        boolean advance() {
            if (!entries.hasNext()) {
                return false;
            }
            current = entries.next();
            return true;
        }
    }
}
