package zombie.entity;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;
import zombie.ApocBRServerTelemetryLite;

/** Server-thread index in the current, unordered EntityBucket's positional order. */
final class ServerUsingPlayerIndex {
    static final class Member {
        final ServerUsingPlayerIndex owner;
        final GameEntity entity;
        final boolean alwaysCheck;
        Member nextOwner;
        int position;
        boolean selected;

        Member(ServerUsingPlayerIndex owner, GameEntity entity, int position) {
            this.owner = owner;
            this.entity = entity;
            this.position = position;
            this.alwaysCheck = !plainAccessors.get(entity.getClass());
        }
    }

    private static final ClassValue<Boolean> plainAccessors = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getUsingPlayer").getDeclaringClass() == GameEntity.class
                    && type.getMethod("isValidEngineEntity").getDeclaringClass() == GameEntity.class;
            } catch (ReflectiveOperationException | SecurityException exception) {
                return false;
            }
        }
    };
    private final IdentityHashMap<GameEntity, Member> members = new IdentityHashMap<>();
    private final TreeMap<Integer, Member> selected = new TreeMap<>();

    void add(GameEntity entity, int position) {
        Member member = new Member(this, entity, position);
        this.members.put(entity, member);
        member.nextOwner = entity.usingPlayerMemberships;
        entity.usingPlayerMemberships = member;
        this.refresh(member);
    }

    int remove(GameEntity entity, GameEntity last) {
        Member member = this.members.remove(entity);
        if (member == null) return -1;
        if (member.selected) {
            this.selected.remove(member.position);
            ApocBRServerTelemetryLite.count("entities.usingPlayer.indexMembershipChanges", 1);
        }
        Member previous = null;
        for (Member link = entity.usingPlayerMemberships; link != null; link = link.nextOwner) {
            if (link == member) {
                if (previous == null) entity.usingPlayerMemberships = link.nextOwner;
                else previous.nextOwner = link.nextOwner;
                break;
            }
            previous = link;
        }
        member.nextOwner = null;
        Member moved = this.members.get(last);
        if (moved != null) {
            if (moved.selected) this.selected.remove(moved.position);
            moved.position = member.position;
            if (moved.selected) this.selected.put(moved.position, moved);
        }
        return member.position;
    }

    private void refresh(Member member) {
        boolean due = member.alwaysCheck || member.entity.getUsingPlayer() != null;
        if (due == member.selected) return;
        member.selected = due;
        if (due) this.selected.put(member.position, member);
        else this.selected.remove(member.position);
        ApocBRServerTelemetryLite.count("entities.usingPlayer.indexMembershipChanges", 1);
    }

    static void changed(GameEntity entity) {
        for (Member member = entity.usingPlayerMemberships; member != null; member = member.nextOwner) {
            member.owner.refresh(member);
        }
    }

    // Query live successors rather than snapshotting: callbacks can activate a later
    // bucket entry, clear themselves or swap-remove entries during traversal.
    Member nextAfter(int position) {
        Map.Entry<Integer, Member> entry = this.selected.higherEntry(position);
        return entry == null ? null : entry.getValue();
    }
}
