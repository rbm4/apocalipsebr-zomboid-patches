package zombie.apocalipse.humans.brain;

import java.util.Objects;

/** Typed commands form the boundary between decisions and engine execution. */
public sealed interface HumanAction {
    enum Type { FACE_VISITOR, HOLD_ATTENTION, EMOTE, SAY, RETURN_HOME, ROAM, WAIT_FOR_TRADE }
    Type type();

    record WaitForTrade(HumanObservation.Identity visitor) implements HumanAction {
        public WaitForTrade { Objects.requireNonNull(visitor); }
        public Type type() { return Type.WAIT_FOR_TRADE; }
    }

    record Roam() implements HumanAction {
        public Type type() { return Type.ROAM; }
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
