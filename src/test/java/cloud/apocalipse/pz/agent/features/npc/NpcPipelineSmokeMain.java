package cloud.apocalipse.pz.agent.features.npc;

/** Run with -javaagent and test doubles first, separately from original-JAR validation. */
public final class NpcPipelineSmokeMain {
    public static void main(String[] args) {
        zombie.network.GameServer.server = true;
        zombie.network.GameServer.udpEngine = new Object();
        zombie.iso.IsoWorld.instance.currentCell = new zombie.iso.IsoWorld.Cell();
        zombie.network.GameServer.main(new String[0]);
        var status = NpcRuntime.status();
        if (status == null || !status.stopped() || status.generation() != 1 || status.serverTicks() < 1)
            throw new AssertionError("Installed agent pipeline did not execute the NPC lifecycle: " + status);
        if (zombie.network.LoginQueue.calls != 3) throw new AssertionError("Simulation execution changed");
        System.out.println("Installed agent NPC pipeline fixture passed.");
    }
}
