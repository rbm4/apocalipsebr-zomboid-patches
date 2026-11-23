package cloud.apocalipse.pz.agent.features.postgres.player;

/** Startup gate without native type linkage; helpers must remain unloaded until installation. */
final class PlayerState {
    static volatile boolean enabled;
    private PlayerState() { }
}
