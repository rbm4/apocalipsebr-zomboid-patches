package zombie.network;

/** Simulation-anchor double for NPC bytecode tests; never used by the real-server harness. */
public final class LoginQueue {
    public static int calls;
    public static void update() { calls++; }
}
