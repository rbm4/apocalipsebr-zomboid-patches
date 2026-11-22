package zombie.apocalipse.humans.brain;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

/** Typed commands form the boundary between decisions and engine execution. */
public sealed interface HumanAction {
    enum Type { FACE_VISITOR, HOLD_ATTENTION, EMOTE, SAY, SEQUENCE, PARALLEL, WAIT, RETURN_HOME, MOVE }
    Type type();

    record Move(zombie.apocalipse.humans.walking.MovementRequest request) implements HumanAction {
        public Move { Objects.requireNonNull(request); }
        public Type type() { return Type.MOVE; }
    }

    record FaceVisitor(HumanObservation.Identity visitor) implements HumanAction {
        public FaceVisitor { Objects.requireNonNull(visitor); }
        public Type type() { return Type.FACE_VISITOR; }
    }

    record HoldAttention(HumanObservation.Identity visitor, long durationNanos) implements HumanAction {
        public HoldAttention {
            Objects.requireNonNull(visitor);
            if (durationNanos < 0) throw new IllegalArgumentException("Negative attention duration");
        }
        public Type type() { return Type.HOLD_ATTENTION; }
    }

    record Emote(String name, long durationNanos) implements HumanAction {
        public Emote {
            if (name == null || name.isBlank() || durationNanos <= 0) throw new IllegalArgumentException("Invalid emote");
        }
        public Type type() { return Type.EMOTE; }
    }

    /** Concurrent presentation lanes only: one speech and one animation, no competing motion. */
    record Parallel(List<HumanAction> actions) implements HumanAction {
        public Parallel {
            actions = List.copyOf(actions);
            int speech = 0, animation = 0;
            for (HumanAction action : actions) {
                if (action instanceof Say) speech++;
                else if (action instanceof Emote) animation++;
                else throw new IllegalArgumentException("Unsupported concurrent capability");
            }
            if (speech > 1 || animation > 1) throw new IllegalArgumentException("Conflicting presentation lanes");
        }
        public Type type() { return Type.PARALLEL; }
    }

    record Wait(long durationNanos) implements HumanAction {
        public Wait {
            if (durationNanos < 0) throw new IllegalArgumentException("Negative wait duration");
        }
        public Type type() { return Type.WAIT; }
    }

    /** Immutable composite; bounded nesting and work even for user-authored plans. */
    record Sequence(List<HumanAction> actions) implements HumanAction {
        public Sequence {
            actions = List.copyOf(actions);
            if (nodes(actions, 1) > 64) throw new IllegalArgumentException("Action sequence exceeds 64 nodes");
        }
        private static int nodes(List<HumanAction> actions, int depth) {
            if (depth > 8 || actions.size() > 64) throw new IllegalArgumentException("Action sequence too large/deep");
            int nodes = 0;
            for (HumanAction action : actions) {
                nodes += 1 + (action instanceof Sequence sequence ? nodes(sequence.actions(), depth + 1)
                    : action instanceof Parallel parallel ? nodes(parallel.actions(), depth + 1) : 0);
                if (nodes > 64) throw new IllegalArgumentException("Action sequence exceeds 64 nodes");
            }
            return nodes;
        }
        public static Sequence speech(List<String> lines, long pauseNanos) {
            Objects.requireNonNull(lines);
            if (lines.size() > 32 || pauseNanos < 0) throw new IllegalArgumentException("Invalid speech plan");
            List<HumanAction> actions = new ArrayList<>();
            for (String line : lines) {
                if (!actions.isEmpty()) actions.add(new Wait(pauseNanos));
                actions.add(new Say(line));
            }
            return new Sequence(actions);
        }
        public Type type() { return Type.SEQUENCE; }
    }

    record Say(String text) implements HumanAction {
        public Say {
            if (text == null || text.isBlank() || text.length() > 256) throw new IllegalArgumentException("Invalid speech");
        }
        public Type type() { return Type.SAY; }
    }

    record ReturnHome() implements HumanAction {
        public Type type() { return Type.RETURN_HOME; }
    }
}
