package zombie.apocalipse.humans.brain;

import java.util.Comparator;
import java.util.Objects;
import static zombie.apocalipse.humans.brain.HumanSenses.*;

/** Pure engagement policy and per-actor target retention; no engine access or attacks. */
public final class HumanEngagement {
    private HumanEngagement() { }
    public enum Choice { IGNORE, WATCH, ENGAGE }
    public record Settings(long maxSampleAgeNanos,long lostGraceNanos,long retryNanos) {
        public static final Settings DEFAULT=new Settings(500_000_000L,1_000_000_000L,2_000_000_000L);
        public Settings {
            if(maxSampleAgeNanos<0||maxSampleAgeNanos>HumanCombat.MAX_SAMPLE_AGE_NANOS||lostGraceNanos<0||lostGraceNanos>3_000_000_000L
                ||retryNanos<100_000_000L||retryNanos>60_000_000_000L)throw new IllegalArgumentException("Invalid engagement timing");
        }
        public static Settings fromMilliseconds(java.util.Map<String,Long> parameters){
            if(parameters.keySet().stream().anyMatch(k->!java.util.Set.of("maxSampleAgeMs","lostGraceMs","retryMs").contains(k)))
                throw new IllegalArgumentException("Unknown engagement parameter");
            return new Settings(Math.multiplyExact(parameters.getOrDefault("maxSampleAgeMs",500L),1_000_000L),
                Math.multiplyExact(parameters.getOrDefault("lostGraceMs",1000L),1_000_000L),
                Math.multiplyExact(parameters.getOrDefault("retryMs",2000L),1_000_000L));
        }
    }
    @FunctionalInterface public interface Policy {
        Choice evaluate(HumanObservation observation,Contact contact,Settings settings);
    }
    /** Initial doctrine: no player aggression, no fight/flight scoring, home-building zombies only. */
    public static final Policy GUARD_ZOMBIES=(observation,contact,settings)->{
        if(observation.state().dead()||observation.state().injuries().immobile()||observation.state().injuries().downed()
            ||contact.identity().kind()!=Kind.ZOMBIE||!observation.territory().actorInside()
            ||!observation.territory().contains(contact.identity()))return Choice.IGNORE;
        var frame=observation.senses();
        long age=observation.nowNanos()-frame.sampledAtNanos();
        if(frame.sampled()&&age>=0&&age<=settings.maxSampleAgeNanos()&&frame.zombies()!=Coverage.UNAVAILABLE&&contact.visible())return Choice.ENGAGE;
        return Choice.WATCH;
    };
    public record Selection(Contact target,Choice choice) {
        public static final Selection NONE=new Selection(null,Choice.IGNORE);
        public Selection { Objects.requireNonNull(choice);if((target==null)!=(choice==Choice.IGNORE))throw new IllegalArgumentException("Invalid target selection"); }
    }
    public static final class Selector {
        private Contact held;
        /** Pure preview: scheduler eligibility must never acquire a target or mutate timers. */
        public Selection preview(HumanObservation observation,Policy policy,Settings settings){
            if(held!=null){
                var current=observation.senses().contacts().stream().filter(c->c.identity().equals(held.identity())).findFirst().orElse(null);
                if(current!=null){
                    Choice choice=policy.evaluate(observation,current,settings);
                    if(choice==Choice.ENGAGE)return new Selection(current,choice);
                    if(choice==Choice.WATCH&&observation.nowNanos()-current.lastSeenNanos()<settings.lostGraceNanos())
                        return new Selection(current,Choice.WATCH);
                }
                // Absence from a partial frame can mean work truncation, not confirmed death.
                if(current==null&&observation.senses().zombies()!=Coverage.COMPLETE
                    &&observation.nowNanos()-held.lastSeenNanos()<settings.lostGraceNanos()
                    &&policy.evaluate(observation,held,settings)!=Choice.IGNORE)return new Selection(held,Choice.WATCH);
            }
            return observation.senses().contacts().stream().filter(c->policy.evaluate(observation,c,settings)==Choice.ENGAGE)
                .min(Comparator.comparingDouble(Contact::distanceSquared).thenComparingLong(c->c.identity().generation()))
                .map(c->new Selection(c,Choice.ENGAGE)).orElse(Selection.NONE);
        }
        public void retain(Selection selection){held=selection.target();}
        public void clear(){held=null;}
        public Identity target(){return held==null?null:held.identity();}
    }
}
