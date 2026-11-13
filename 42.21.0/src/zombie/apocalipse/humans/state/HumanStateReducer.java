package zombie.apocalipse.humans.state;

import java.util.Objects;

/** Pure transition function: (previous state, mutation) -> new immutable state. */
public final class HumanStateReducer {
    public static HumanState reduce(HumanState previous, HumanMutation mutation) {
        Objects.requireNonNull(previous); Objects.requireNonNull(mutation);
        // Death is terminal. Revival requires an explicitly designed lifecycle, not a heal command.
        if(previous.dead())return previous;
        float[] v=previous.vitals().values();var injury=previous.injuries();
        switch(mutation) {
            case HumanMutation.Damage d -> {
                v[0]=clamp(v[0]-d.amount());v[5]=clamp(v[5]+d.amount());
                float leg=Math.abs(d.legInjury())>Math.abs(injury.leg())?d.legInjury():injury.leg();
                injury=new HumanState.Injuries(leg,Math.max(injury.arms(),d.armInjury()),
                    Math.max(injury.bleedingPerSecond(),d.bleedingPerSecond()),injury.immobile(),injury.downed());
            }
            case HumanMutation.Heal h -> v[0]=clamp(v[0]+h.amount());
            case HumanMutation.Adjust a -> {int i=a.stat().ordinal()+1;v[i]=clamp(v[i]+a.delta());}
            case HumanMutation.Treat t -> injury=new HumanState.Injuries(
                Math.copySign(Math.max(0,Math.abs(injury.leg())-t.legRecovery()),injury.leg()),
                Math.max(0,injury.arms()-t.armRecovery()),Math.max(0,injury.bleedingPerSecond()-t.bleedingReduction()),
                injury.immobile(),injury.downed());
            case HumanMutation.Mobility m -> injury=new HumanState.Injuries(injury.leg(),injury.arms(),injury.bleedingPerSecond(),m.immobile(),m.downed());
            case HumanMutation.Kill ignored -> v[0]=0;
            case HumanMutation.Advance a -> {
                v[0]=clamp(v[0]-injury.bleedingPerSecond()*a.seconds());
                v[1]=clamp(v[1]+a.endurancePerSecond()*a.seconds());
                v[2]=clamp(v[2]+a.hungerPerSecond()*a.seconds());
                v[3]=clamp(v[3]+a.thirstPerSecond()*a.seconds());
                v[4]=clamp(v[4]+a.fatiguePerSecond()*a.seconds());
            }
        }
        var vitals=HumanState.Vitals.from(v);
        if(vitals.equals(previous.vitals())&&injury.equals(previous.injuries()))return previous;
        return new HumanState(Math.incrementExact(previous.revision()),vitals,injury);
    }
    private static float clamp(float v){return Math.clamp(v,0,1);}
    private HumanStateReducer(){ }
}
