package zombie.apocalipse.humans.groups;

/** Server-facing port over immutable observations and shared logical coordinators. */
public interface GroupPatrolPort {
    record Observation(long sampledAt,GroupPatrolCoordinator.Goal goal,float x,float y,int z,boolean inside,
                       long revision,boolean regroup,boolean hold,
                       java.util.List<zombie.apocalipse.humans.walking.WalkingPatternSpec.Point> companions) {
        public Observation(long at,GroupPatrolCoordinator.Goal goal,float x,float y,int z,boolean inside){this(at,goal,x,y,z,inside,0,false,false,java.util.List.of());}
        public Observation {companions=java.util.List.copyOf(companions);if(companions.size()>7)throw new IllegalArgumentException("Too many companions");}
    }
    Observation observe();
}
