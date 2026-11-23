package cloud.apocalipse.pz.agent.features.postgres.player;

/** Suppresses simulation/Lua publication while detached relational state is constructed. */
public final class PlayerConstruction implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();
    private PlayerConstruction() { DEPTH.set(DEPTH.get() == null ? 1 : DEPTH.get() + 1); }
    static PlayerConstruction enter() { return new PlayerConstruction(); }
    public static boolean active() { return DEPTH.get() != null; }
    @Override public void close() { int depth = DEPTH.get(); if (depth == 1) DEPTH.remove(); else DEPTH.set(depth - 1); }
}
