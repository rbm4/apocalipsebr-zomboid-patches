package zombie.apocalipse.humans.state;

import zombie.apocalipse.humans.movement.MovementModifiers.*;

/** Read-only projection from authoritative state to the movement capability's input contract. */
public final class HumanConditionCalculator {
    public static Conditions movement(HumanState state,Context environment,Effects effects){
        var v=state.vitals();var i=state.injuries();
        var health=new Health(v.health(),Math.min(v.endurance(),1-v.fatigue()),i.leg(),i.immobile(),i.downed(),false);
        var context=new Context(environment.sneaking(),environment.sweepSlow(),environment.posture(),
            environment.terrainScale(),Math.max(0.05F,environment.coldScale()*(1-0.3F*v.cold())),environment.equipmentScale(),environment.strideScale());
        return new Conditions(health,context,effects);
    }
    private HumanConditionCalculator(){ }
}
