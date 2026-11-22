package zombie.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.PriorityQueue;
import zombie.ApocBRServerTelemetryLite;
import zombie.entity.util.Array;
import zombie.entity.util.ImmutableArray;

/** Main-thread index. Engine operations are applied between systems, as before. */
final class ServerEntitySimulationIndex {
    private static final IdentityHashMap<GameEntity, ArrayList<ServerEntitySimulationIndex>> owners = new IdentityHashMap<>();
    private final IdentityHashMap<GameEntity, Member> members = new IdentityHashMap<>();
    private final Group always = new Group();
    private final HashMap<Integer, Group> phases = new HashMap<>();
    private final PriorityQueue<Cursor> merge = new PriorityQueue<>(Comparator.comparingInt(c -> c.group.entries.get(c.index).position));
    private final Array<GameEntity> result = new Array<>(true, 16);
    private final ImmutableArray<GameEntity> view = new ImmutableArray<>(this.result);
    private static final Comparator<Member> ORDER = Comparator.comparingInt(m -> m.position);

    void add(GameEntity entity, int position) {
        Member member = new Member(entity, position);
        this.members.put(entity, member);
        owners.computeIfAbsent(entity, key -> new ArrayList<>()).add(this);
        this.classify(member);
    }

    int remove(GameEntity entity, GameEntity last) {
        Member member = this.members.remove(entity);
        if (member == null) return -1;
        this.unlink(member);
        Member swapped = this.members.get(last);
        if (swapped != null) { swapped.position = member.position; swapped.group.ordered = false; }
        ArrayList<ServerEntitySimulationIndex> indexes = owners.get(entity);
        indexes.remove(this);
        if (indexes.isEmpty()) owners.remove(entity);
        return member.position;
    }

    void refresh(GameEntity entity) {
        Member member = this.members.get(entity);
        if (member != null) {
            this.unlink(member);
            this.classify(member);
        }
    }

    static void identityChanged(GameEntity entity) {
        ArrayList<ServerEntitySimulationIndex> indexes = owners.get(entity);
        if (indexes != null) {
            for (int i = 0; i < indexes.size(); i++) indexes.get(i).refresh(entity);
        }
    }

    private void classify(Member member) {
        GameEntity entity = member.entity;
        // Unknown subclasses may implement mutable isMeta/IDs. Keep their original checks.
        // Drying detachment cleanup must still run before the throttle on every pass.
        long id = entity.getClass() == MetaEntity.class ? entity.getEntityNetID() : 0;
        if (entity.getClass() != MetaEntity.class || entity.hasComponent(ComponentType.DryingCraftLogic)
            || id > Long.MAX_VALUE - Integer.MAX_VALUE) {
            member.group = this.always;
        } else {
            int phase = Math.floorMod(id, MetaSimulationThrottle.getInterval());
            member.phase = phase;
            member.group = this.phases.computeIfAbsent(phase, key -> new Group());
        }
        member.slot = member.group.entries.size();
        member.group.entries.add(member);
        member.group.ordered = false;
    }

    private void unlink(Member member) {
        Group group = member.group;
        ArrayList<Member> list = group.entries;
        Member last = list.remove(list.size() - 1);
        if (last != member) {
            list.set(member.slot, last);
            last.slot = member.slot;
        }
        group.ordered = false;
        if (group != this.always && list.isEmpty()) this.phases.remove(member.phase);
    }

    ImmutableArray<GameEntity> select() {
        this.merge.clear();
        this.result.clear();
        this.include(this.always);
        int ticks = EntitySimulation.getSimulationTicksThisFrame();
        long end = EntitySimulation.getTotalSimulationTicks();
        int interval = MetaSimulationThrottle.getInterval();
        if (ticks >= interval) {
            for (Group group : this.phases.values()) this.include(group);
        } else {
            // At most interval-1 crossed ticks; absent phases allocate no buckets.
            for (int offset = 0; offset < ticks; offset++) {
                int phase = (int)Math.floorMod(-(end - offset), (long)interval);
                Group group = this.phases.get(phase);
                if (group != null) this.include(group);
            }
        }
        while (!this.merge.isEmpty()) {
            Cursor cursor = this.merge.poll();
            this.result.add(cursor.group.entries.get(cursor.index++).entity);
            if (cursor.index < cursor.group.entries.size()) this.merge.add(cursor);
        }
        ApocBRServerTelemetryLite.count("entities.simulationCandidates", this.result.size);
        ApocBRServerTelemetryLite.count("entities.simulationSkippedByIndex", this.members.size() - this.result.size);
        return this.view;
    }

    private void include(Group group) {
        if (group.entries.isEmpty()) return;
        if (!group.ordered) {
            group.entries.sort(ORDER);
            for (int i = 0; i < group.entries.size(); i++) group.entries.get(i).slot = i;
            group.ordered = true;
            ApocBRServerTelemetryLite.count("entities.simulationIndexSorted", group.entries.size());
        }
        group.cursor.index = 0;
        this.merge.add(group.cursor);
    }

    private static final class Group {
        final ArrayList<Member> entries = new ArrayList<>();
        final Cursor cursor = new Cursor(this);
        boolean ordered;
    }

    private static final class Cursor {
        final Group group;
        int index;
        Cursor(Group group) { this.group = group; }
    }

    private static final class Member {
        final GameEntity entity;
        int position;
        int slot;
        int phase;
        Group group;
        Member(GameEntity entity, int position) { this.entity = entity; this.position = position; }
    }
}
