package cloud.apocalipse.pz.agent.features.npc;

public class NpcQuitFixture {
    public void QueuedQuit() {
        if (!NpcRuntime.status().stopped()) throw new AssertionError("NPC cleanup must precede native teardown");
        NpcHookFixture.quits++;
    }
}
