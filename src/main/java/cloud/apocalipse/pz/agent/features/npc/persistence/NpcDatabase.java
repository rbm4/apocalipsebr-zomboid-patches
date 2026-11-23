package cloud.apocalipse.pz.agent.features.npc.persistence;

/** The store already owns its worker; no live-world capabilities enter this boundary. */
final class NpcDatabase {
    static void background(Runnable work){work.run();}
}
