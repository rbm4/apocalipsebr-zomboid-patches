package cloud.apocalipse.pz.agent.features.npc.state;

/** Native health stays in its stock range. Increased logical capacity reduces each native loss. */
public final class HumanHealthCapacity {
    private HumanHealthCapacity(){ }
    public static float damage(float amount,double capacity){
        if(!Float.isFinite(amount)||!Double.isFinite(capacity)||capacity<1||capacity>10)
            throw new IllegalArgumentException("Invalid health capacity inputs");
        return amount/(float)capacity;
    }
}
