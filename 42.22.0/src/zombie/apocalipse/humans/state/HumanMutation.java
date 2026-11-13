package zombie.apocalipse.humans.state;

import java.util.Objects;

/** Trusted commands, not client packet payloads. Adapters validate authority before submission. */
public sealed interface HumanMutation {
    record Damage(float amount, float legInjury, float armInjury, float bleedingPerSecond) implements HumanMutation {
        public Damage {
            HumanState.fraction(amount); new HumanState.Injuries(legInjury,armInjury,bleedingPerSecond,false,false);
        }
    }
    record Heal(float amount) implements HumanMutation { public Heal { HumanState.fraction(amount); } }
    record Adjust(HumanState.Stat stat, float delta) implements HumanMutation {
        public Adjust {
            Objects.requireNonNull(stat);
            if(!Float.isFinite(delta)||Math.abs(delta)>1) throw new IllegalArgumentException("Invalid stat adjustment");
        }
    }
    record Treat(float legRecovery, float armRecovery, float bleedingReduction) implements HumanMutation {
        public Treat { HumanState.fraction(legRecovery);HumanState.fraction(armRecovery);HumanState.fraction(bleedingReduction); }
    }
    record Mobility(boolean immobile, boolean downed) implements HumanMutation { }
    record Kill() implements HumanMutation { }
    /** Explicit simulation deltas; reducer never reads a clock or samples native objects. */
    record Advance(float seconds, float endurancePerSecond, float hungerPerSecond,
                   float thirstPerSecond, float fatiguePerSecond) implements HumanMutation {
        public Advance {
            if(!Float.isFinite(seconds)||seconds<0||seconds>0.15F) throw new IllegalArgumentException("Invalid state time step");
            for(float rate:new float[]{endurancePerSecond,hungerPerSecond,thirstPerSecond,fatiguePerSecond})
                if(!Float.isFinite(rate)||Math.abs(rate)>1)throw new IllegalArgumentException("Invalid physiology rate");
        }
    }
}
