package zombie.apocalipse.humans.brain;

import java.util.Objects;

/** Transient policy output, not physical state. The selected policy owns its lifecycle. */
public record TacticalIntent(Objective objective, Posture posture, HumanSenses.Identity target, boolean elevatedExposure) {
    public enum Objective { NONE, ENGAGE, ESCAPE, CREATE_OPENING, RECOVER }
    public enum Posture { RELAXED, GUARDED, FLEEING }
    public static final TacticalIntent NONE=new TacticalIntent(Objective.NONE,Posture.RELAXED,null,false);
    public TacticalIntent {Objects.requireNonNull(objective);Objects.requireNonNull(posture);}
    public boolean guarded(){return posture==Posture.GUARDED&&target!=null;}
}
