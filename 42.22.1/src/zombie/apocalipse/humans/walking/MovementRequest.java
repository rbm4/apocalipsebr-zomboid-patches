package zombie.apocalipse.humans.walking;

import java.util.Objects;
import zombie.apocalipse.humans.profiles.HumanProfile;

/** Intent from an objective/module to navigation. Execution progress stays in the actor's movement service. */
public sealed interface MovementRequest {
    /** Local movement may leave patrol territory, but is bounded around an explicit center. */
    record Nearby(WalkingPatternSpec.Point destination,WalkingPatternSpec.Point center,int radius) implements MovementRequest {
        public Nearby {Objects.requireNonNull(destination);Objects.requireNonNull(center);
            if(radius<1||radius>8||destination.z()!=center.z()||Math.abs((long)destination.x()-center.x())>radius
                ||Math.abs((long)destination.y()-center.y())>radius)throw new IllegalArgumentException("Invalid local destination");}
    }
    record Pattern(String slot) implements MovementRequest {
        public Pattern { HumanProfile.identifier(slot); }
    }
    record To(WalkingPatternSpec.Point destination, String slot,boolean partialLeg,
              boolean returnToArea,java.util.List<WalkingPatternSpec.Point> companions) implements MovementRequest {
        public To(WalkingPatternSpec.Point destination,String slot,boolean partialLeg){this(destination,slot,partialLeg,false,java.util.List.of());}
        public To(WalkingPatternSpec.Point destination,String slot){this(destination,slot,false);}
        public To(WalkingPatternSpec.Point destination){this(destination,"background");}
        public To { Objects.requireNonNull(destination); HumanProfile.identifier(slot);companions=java.util.List.copyOf(companions);
            if(companions.size()>7||companions.stream().anyMatch(p->p.z()!=destination.z()))throw new IllegalArgumentException("Invalid companion bounds"); }
    }
}
