// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie;

import java.util.Comparator;

public enum UpdateSchedulerSimulationLevel {
    SIXTEENTH,
    EIGHTH,
    QUARTER,
    HALF,
    FULL;

    private static final UpdateSchedulerSimulationLevel[] VALUES = values();
    private static final Comparator<UpdateSchedulerSimulationLevel> COMPARATOR = Comparator.comparingInt(Enum::ordinal);

    public UpdateSchedulerSimulationLevel less() {
        return VALUES[Math.max(this.ordinal() - 1, 0)];
    }

    public UpdateSchedulerSimulationLevel more() {
        return VALUES[Math.min(this.ordinal() + 1, VALUES.length - 1)];
    }

    public UpdateSchedulerSimulationLevel max(UpdateSchedulerSimulationLevel rhs) {
        return COMPARATOR.compare(this, rhs) > 0 ? this : rhs;
    }
}
