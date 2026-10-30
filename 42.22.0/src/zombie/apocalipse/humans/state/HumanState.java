package zombie.apocalipse.humans.state;

import java.util.Objects;

/** Durable logical state. Fractions are 0..1; no actor references or live deadlines. */
public record HumanState(long revision, Vitals vitals, Injuries injuries) {
    public static final int VERSION = 1;
    public static final HumanState INITIAL = new HumanState(0, Vitals.HEALTHY, Injuries.NONE);
    public enum Stat { ENDURANCE, HUNGER, THIRST, FATIGUE, PAIN, COLD, FEAR, SICKNESS, DIRTINESS, INFECTION }
    public record Vitals(float health, float endurance, float hunger, float thirst, float fatigue,
                         float pain, float cold, float fear, float sickness, float dirtiness, float infection) {
        public static final Vitals HEALTHY = new Vitals(1,1,0,0,0,0,0,0,0,0,0);
        public Vitals { for (float value : new float[]{health,endurance,hunger,thirst,fatigue,pain,cold,fear,sickness,dirtiness,infection}) fraction(value); }
        float[] values() { return new float[]{health,endurance,hunger,thirst,fatigue,pain,cold,fear,sickness,dirtiness,infection}; }
        static Vitals from(float[] v) { return new Vitals(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10]); }
    }
    public record Injuries(float leg, float arms, float bleedingPerSecond, boolean immobile, boolean downed) {
        public static final Injuries NONE = new Injuries(0,0,0,false,false);
        public Injuries {
            if (!Float.isFinite(leg) || Math.abs(leg)>1) throw new IllegalArgumentException("Invalid signed leg injury");
            fraction(arms); fraction(bleedingPerSecond);
        }
    }
    public HumanState {
        if(revision<0) throw new IllegalArgumentException("Invalid state revision");
        Objects.requireNonNull(vitals); Objects.requireNonNull(injuries);
    }
    public boolean dead() { return vitals.health()==0; }
    static void fraction(float v) {
        if(!Float.isFinite(v)||v<0||v>1) throw new IllegalArgumentException("Invalid state fraction");
    }
}
