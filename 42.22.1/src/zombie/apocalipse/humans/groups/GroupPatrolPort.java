package zombie.apocalipse.humans.groups;

/** Worker-side port over immutable server-thread observations and a shared logical coordinator. */
public interface GroupPatrolPort {
    record Observation(long sampledAt,GroupPatrolCoordinator.Goal goal,float x,float y,int z,boolean inside) { }
    Observation observe();
}
