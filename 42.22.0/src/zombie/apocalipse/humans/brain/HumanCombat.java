package zombie.apocalipse.humans.brain;

import java.util.Objects;

/** Policy-to-combat port. Equipment, route planning, timing and damage belong behind this boundary. */
public final class HumanCombat {
    private HumanCombat() { }
    public static final long MAX_SAMPLE_AGE_NANOS=500_000_000L;
    public static final long MAX_EXECUTION_NANOS=60_000_000_000L;
    public enum Template { GUARD_BASIC }
    public enum Status { RUNNING, COMPLETED, REJECTED, FAILED, CANCELLED, UNSUPPORTED }
    public enum Reason { NONE, NOT_IMPLEMENTED, INVALID_TARGET, STALE_OBSERVATION, OUTSIDE_TERRITORY,
        INCAPACITATED, BUSY, TARGET_LOST, TARGET_CHANGED, PREEMPTED, SUSPENDED, STOPPED, TIMEOUT, BACKEND_FAILURE,
        NO_WEAPON,NO_AMMO,UNREACHABLE }
    public record Request(HumanSenses.Identity target,Template template,boolean externalMovement) {
        public Request(HumanSenses.Identity target,Template template){this(target,template,false);}
        public Request { Objects.requireNonNull(target);Objects.requireNonNull(template); }
    }
    public record Result(Status status,Reason reason) {
        public Result { Objects.requireNonNull(status);Objects.requireNonNull(reason); }
        public boolean terminal(){return status!=Status.RUNNING;}
    }
    public interface Execution {
        Result update(long now);
        void cancel(Reason reason);
    }
    @FunctionalInterface public interface Port { Execution begin(Request request,long now); }
    public static Execution finished(Result result){
        if(!result.terminal())throw new IllegalArgumentException("Finished execution must be terminal");
        return new Execution(){public Result update(long now){return result;}public void cancel(Reason reason){} };
    }
    public static final Port UNSUPPORTED=(request,now)->finished(new Result(Status.UNSUPPORTED,Reason.NOT_IMPLEMENTED));
}
