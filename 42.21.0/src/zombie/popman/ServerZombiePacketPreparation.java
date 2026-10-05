package zombie.popman;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import zombie.ApocBRServerTelemetryLite;
import zombie.characters.IsoZombie;

/** Main-thread auth-frame lifetime; connection workers never mutate packet fields. */
public final class ServerZombiePacketPreparation {
    private final Set<IsoZombie> bookkeeping = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<IsoZombie> prepared = Collections.newSetFromMap(new IdentityHashMap<>());
    public void beginFrame() { bookkeeping.clear(); prepared.clear(); }
    public void bookkeep(IsoZombie zombie) {
        zombie.zombiePacket.prepareFrame(zombie);
        bookkeeping.add(zombie);
    }
    public void received(IsoZombie zombie) { prepared.remove(zombie); }
    public void prepare(IsoZombie zombie) {
        if (prepared.contains(zombie) && zombie.zombiePacket.id == zombie.onlineId) {
            ApocBRServerTelemetryLite.count("zombies.packet.preparationReused", 1);
            return;
        }
        long started = System.nanoTime();
        if (!bookkeeping.contains(zombie) || zombie.zombiePacket.id != zombie.onlineId) bookkeep(zombie);
        zombie.zombiePacket.setPrepared(zombie);
        prepared.add(zombie);
        ApocBRServerTelemetryLite.recordPhase("network.zombies.packetPrepare", System.nanoTime() - started);
        ApocBRServerTelemetryLite.count("zombies.packet.prepared", 1);
    }
}
