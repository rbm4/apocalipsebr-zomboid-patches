package cloud.apocalipse.pz.agent.integration.probe;

/** The same streaming fixture runs each initial save/backend combination in an isolated world. */
public enum ChunkStorageArm {
    DISK_FRESH(false, false),
    POSTGRES_FRESH(true, false),
    DISK_SEEDED(false, true),
    POSTGRES_SEEDED(true, true);

    public static final String PROPERTY = "pz.it.chunkStorageBenchmarkArm";

    private final boolean postgres;
    private final boolean seeded;

    ChunkStorageArm(boolean postgres, boolean seeded) {
        this.postgres = postgres;
        this.seeded = seeded;
    }

    public boolean postgres() { return postgres; }
    public boolean seeded() { return seeded; }

    public int cycles(StreamingWorkload workload) {
        return seeded ? workload.cycles() : 1;
    }

    public static ChunkStorageArm parse(String value) {
        return value == null ? null : valueOf(value);
    }
}
