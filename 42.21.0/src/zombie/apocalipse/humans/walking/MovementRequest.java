package zombie.apocalipse.humans.walking;

import java.util.Objects;
import zombie.apocalipse.humans.profiles.HumanProfile;

/** Intent from an objective/module to navigation. Execution progress stays in the actor's movement service. */
public sealed interface MovementRequest {
    record Pattern(String slot) implements MovementRequest {
        public Pattern { HumanProfile.identifier(slot); }
    }
    record To(WalkingPatternSpec.Point destination, String slot) implements MovementRequest {
        public To(WalkingPatternSpec.Point destination){this(destination,"background");}
        public To { Objects.requireNonNull(destination); HumanProfile.identifier(slot); }
    }
}
