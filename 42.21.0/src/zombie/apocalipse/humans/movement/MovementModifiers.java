package zombie.apocalipse.humans.movement;

import java.util.Objects;

/** Immutable movement inputs and pure resolution. No world access, timers or health simulation. */
public final class MovementModifiers {
    public enum Pace { WALK, RUN, SPRINT, GUARD }
    public enum Posture { RELAXED, ALERT, THREAT }
    public enum BurstStyle { SPRINTER, JOGGER }
    public enum Reason { REQUESTED, IMMOBILE, RUN_DISABLED, LEG_WOUND, SWEEP, FLIGHT,
        HIT_ESCAPE, BURST, DANGER, ENDURANCE, RUN_WINDOW }

    /** Fractions are 0..1. Signed leg injury selects the native limp side. */
    public record Health(float health, float endurance, float legInjury,
                         boolean immobile, boolean downed, boolean bleedingOut) {
        public static final Health HEALTHY = new Health(1, 1, 0, false, false, false);
        public Health {
            fraction(health); fraction(endurance);
            if (!Float.isFinite(legInjury) || Math.abs(legInjury) > 1) throw new IllegalArgumentException("Invalid leg injury");
        }
        public boolean legWounded() { return legInjury != 0; }
    }

    /** Producers supply terrain/cold/equipment/stride factors; the resolver composes them. */
    public record Context(boolean sneaking, boolean sweepSlow, Posture posture,
                          float terrainScale, float coldScale, float equipmentScale, float strideScale) {
        public static final Context NORMAL = new Context(false, false, Posture.RELAXED, 1, 1, 1, 1);
        public Context {
            Objects.requireNonNull(posture);
            scale(terrainScale); scale(coldScale); scale(equipmentScale); scale(strideScale);
        }
    }

    /** Explicit enabled bit avoids treating zero as an epoch in System.nanoTime's arbitrary origin. */
    public record Window(boolean enabled, long untilNanos) {
        public static final Window NONE = new Window(false, 0);
        public boolean active(long now) { return enabled && now < untilNanos; }
    }

    /** Burst admission is decided by threat/target policy before it supplies a lease. */
    public record BurstAdmission(boolean targetEligible, boolean distanceEligible, boolean pacingEligible) {
        public static final BurstAdmission REFUSED = new BurstAdmission(false, false, false);
        public boolean allowed() { return targetEligible && distanceEligible && pacingEligible; }
    }

    public record Effects(Window flight, Pace flightPace, Window hitEscape, Window sprint,
                          BurstAdmission burst) {
        public static final Effects NONE = new Effects(Window.NONE, Pace.RUN, Window.NONE, Window.NONE, BurstAdmission.REFUSED);
        public Effects {
            Objects.requireNonNull(flight); Objects.requireNonNull(hitEscape);
            Objects.requireNonNull(sprint); Objects.requireNonNull(burst);
            if (flightPace != Pace.RUN && flightPace != Pace.SPRINT) throw new IllegalArgumentException("Invalid flight pace");
        }
    }

    public record Conditions(Health health, Context context, Effects effects) {
        public static final Conditions NORMAL = new Conditions(Health.HEALTHY, Context.NORMAL, Effects.NONE);
        public Conditions { Objects.requireNonNull(health); Objects.requireNonNull(context); Objects.requireNonNull(effects); }
    }

    public record Speeds(float walk, float run, float sprint) {
        // Native player visual tuning still needs dedicated-server calibration for RUN/SPRINT.
        public static final Speeds DEFAULT = new Speeds(2, 4, 6);
        public Speeds {
            speed(walk); speed(run); speed(sprint);
            if (run < walk || sprint < run) throw new IllegalArgumentException("Unordered movement speeds");
        }
        public float forPace(Pace pace) { return switch (pace) { case RUN -> run; case SPRINT -> sprint; default -> walk; }; }
    }

    /** Equal active/cycle values mean continuous running; optional A-life-style cadence is explicit. */
    public record Rhythm(long activeNanos, long cycleNanos) {
        public static final Rhythm CONTINUOUS = new Rhythm(1, 1);
        public static final Rhythm INTERMITTENT = new Rhythm(850_000_000L, 14_000_000_000L);
        public Rhythm {
            if (activeNanos <= 0 || cycleNanos < activeNanos || cycleNanos > 86_400_000_000_000L)
                throw new IllegalArgumentException("Invalid run rhythm");
        }
        public boolean active(long now, long origin) {
            return Math.floorMod(Math.max(0, now - origin), cycleNanos) < activeNanos;
        }
    }

    public record Settings(boolean allowRun, boolean allowSprint, BurstStyle burstStyle,
                           Speeds speeds, Rhythm rhythm, float jogMinimum, float sprintMinimum) {
        // Thresholds normalize the inspected A-life settings (18/140 and 34/140).
        public static final Settings DEFAULT = new Settings(true, true, BurstStyle.SPRINTER,
            Speeds.DEFAULT, Rhythm.CONTINUOUS, 18F / 140, 34F / 140);
        public Settings {
            Objects.requireNonNull(burstStyle); Objects.requireNonNull(speeds); Objects.requireNonNull(rhythm);
            fraction(jogMinimum); fraction(sprintMinimum);
            if (sprintMinimum < jogMinimum) throw new IllegalArgumentException("Unordered endurance thresholds");
        }
    }

    public record Animation(float walkInjury, float walkSpeed, float runSpeed, boolean sneaking) { }
    public record Resolved(Pace pace, float speed, Animation animation, Reason reason) { }

    public static Resolved resolve(Pace requested, Conditions conditions, Settings settings, long now, long rhythmOrigin) {
        Objects.requireNonNull(requested); Objects.requireNonNull(conditions); Objects.requireNonNull(settings);
        Health health = conditions.health(); Context context = conditions.context(); Effects effects = conditions.effects();
        Pace pace = requested == Pace.GUARD ? Pace.WALK : requested;
        Reason reason = Reason.REQUESTED;
        boolean blocked = health.immobile() || health.downed() || health.bleedingOut() || health.health() == 0;
        if (blocked) { pace = Pace.WALK; reason = Reason.IMMOBILE; }
        else if (health.legWounded()) { pace = Pace.WALK; reason = Reason.LEG_WOUND; }
        else if (!settings.allowRun()) { pace = Pace.WALK; reason = Reason.RUN_DISABLED; }
        else if (effects.flight().active(now)) {
            pace = health.endurance() > settings.jogMinimum() ? effects.flightPace() : Pace.WALK;
            reason = Reason.FLIGHT;
        } else if (context.sweepSlow() && requested != Pace.SPRINT) { pace = Pace.WALK; reason = Reason.SWEEP; }
        else if (effects.hitEscape().active(now) && requested != Pace.GUARD && health.endurance() > settings.jogMinimum()) {
            pace = Pace.RUN; reason = Reason.HIT_ESCAPE;
        } else if (requested == Pace.RUN || requested == Pace.SPRINT) {
            if (settings.allowSprint() && effects.sprint().active(now) && effects.burst().allowed()
                && health.endurance() >= settings.sprintMinimum()) {
                pace = settings.burstStyle() == BurstStyle.SPRINTER && !context.sneaking() ? Pace.SPRINT : Pace.RUN;
                reason = Reason.BURST;
            } else if (context.posture() != Posture.RELAXED) {
                pace = health.endurance() > settings.jogMinimum() ? Pace.RUN : Pace.WALK;
                reason = Reason.DANGER;
            } else if (health.endurance() < settings.sprintMinimum()) { pace = Pace.WALK; reason = Reason.ENDURANCE; }
            else {
                // Sprint requests require an admitted burst; ordinary travel runs instead.
                pace = settings.rhythm().active(now, rhythmOrigin) ? Pace.RUN : Pace.WALK;
                reason = Reason.RUN_WINDOW;
            }
        }
        // A flight override cannot bypass physical sprint permission/endurance/stealth limits.
        if (pace == Pace.SPRINT && (!settings.allowSprint() || context.sneaking()
            || health.endurance() < settings.sprintMinimum() || settings.burstStyle() == BurstStyle.JOGGER)) pace = Pace.RUN;

        float common = context.terrainScale() * context.coldScale() * context.equipmentScale() * context.strideScale();
        boolean hurt = health.health() < 0.65F;
        float walk = common * (hurt ? 0.78F / 1.02F : 1);
        float run = common * (hurt ? 0.60F / 0.78F : 1);
        float sprint = common * (hurt ? 0.66F / 0.84F : 1);
        if (context.sweepSlow()) walk *= 0.72F;
        if (health.legWounded()) walk *= 0.66F;
        else if (context.sneaking()) walk *= 0.85F;
        // Bound the physical speed and apply the same cap to native animation modifiers.
        walk = Math.min(walk, 8 / settings.speeds().walk());
        run = Math.min(run, 8 / settings.speeds().run());
        sprint = Math.min(sprint, 8 / settings.speeds().sprint());
        float factor = switch (pace) { case RUN -> run; case SPRINT -> sprint; default -> walk; };
        return new Resolved(pace, blocked ? 0 : settings.speeds().forPace(pace) * factor,
            new Animation(health.legInjury(), walk, pace == Pace.SPRINT ? sprint : run,
                context.sneaking() && !health.legWounded()), reason);
    }

    private static void fraction(float value) {
        if (!Float.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Invalid movement fraction");
    }
    private static void scale(float value) {
        if (!Float.isFinite(value) || value < 0.05F || value > 1.5F) throw new IllegalArgumentException("Invalid movement scale");
    }
    private static void speed(float value) {
        if (!Float.isFinite(value) || value <= 0 || value > 8) throw new IllegalArgumentException("Invalid movement speed");
    }

    private MovementModifiers() { }
}
