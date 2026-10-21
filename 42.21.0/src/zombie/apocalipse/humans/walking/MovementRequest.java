package zombie.apocalipse.humans.walking;

import java.util.Objects;
import zombie.apocalipse.humans.profiles.HumanProfile;

/** Intent from an objective/module to navigation. Execution progress stays in the actor's movement service. */
public sealed interface MovementRequest {
    record Pattern(String slot) implements MovementRequest {
        public Pattern { HumanProfile.identifier(slot); }
    }
    record To(WalkingPatternSpec.Point destination) implements MovementRequest {
        public To { Objects.requireNonNull(destination); }
    }
}
