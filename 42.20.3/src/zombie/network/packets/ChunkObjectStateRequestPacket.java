package zombie.network.packets;

import gnu.trove.list.array.TShortArrayList;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import zombie.characters.Capability;
import zombie.core.logger.ExceptionLogger;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.iso.IsoChunk;
import zombie.network.IConnection;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.ServerMap;

@PacketSetting(ordering = 3, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 1)
public class ChunkObjectStateRequestPacket implements INetworkPacket {
    private static final int CHUNK_STATE_WORKERS = 6;
    private static final ExecutorService chunkStatePool = Executors.newFixedThreadPool(CHUNK_STATE_WORKERS);
    private static final ArrayDeque<Job> queue = new ArrayDeque<>();
    private static volatile CountDownLatch pendingLatch;
    private TShortArrayList chunkObjectState;

    @Override
    public void setData(Object... values) {
        this.chunkObjectState = (TShortArrayList)values[0];
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putInt(this.chunkObjectState.size());

        for (int i = 0; i < this.chunkObjectState.size(); i += 2) {
            b.putShort(this.chunkObjectState.get(i));
            b.putShort(this.chunkObjectState.get(i + 1));
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        int size = b.getInt();
        TShortArrayList coords = new TShortArrayList(size);

        for (int i = 0; i < size; i++) {
            coords.add(b.getShort());
        }

        synchronized (queue) {
            queue.add(new Job(connection, coords));
        }
    }

    public static void processQueue() {
        IdentityHashMap<IConnection, TShortArrayList> byConnection = new IdentityHashMap<>();

        synchronized (queue) {
            while (!queue.isEmpty()) {
                Job job = queue.poll();
                TShortArrayList combined = byConnection.get(job.connection);
                if (combined == null) {
                    combined = new TShortArrayList(job.coords.size());
                    byConnection.put(job.connection, combined);
                }

                for (int i = 0; i < job.coords.size(); i++) {
                    combined.add(job.coords.get(i));
                }
            }
        }

        if (!byConnection.isEmpty()) {
            CountDownLatch latch = new CountDownLatch(byConnection.size());
            pendingLatch = latch;

            for (Map.Entry<IConnection, TShortArrayList> entry : byConnection.entrySet()) {
                chunkStatePool.execute(new ChunkStateJob(entry.getKey(), entry.getValue(), latch));
            }
        }
    }

    public static void awaitWorkers() {
        CountDownLatch latch = pendingLatch;
        if (latch != null) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pendingLatch = null;
            }
        }
    }

    private static final class Job {
        final IConnection connection;
        final TShortArrayList coords;

        Job(IConnection connection, TShortArrayList coords) {
            this.connection = connection;
            this.coords = coords;
        }
    }

    private static final class ChunkStateJob implements Runnable {
        final IConnection connection;
        final TShortArrayList coords;
        final CountDownLatch latch;

        ChunkStateJob(IConnection connection, TShortArrayList coords, CountDownLatch latch) {
            this.connection = connection;
            this.coords = coords;
            this.latch = latch;
        }

        @Override
        public void run() {
            try {
                this.process();
            } catch (Exception e) {
                ExceptionLogger.logException(e);
            } finally {
                this.latch.countDown();
            }
        }

        private void process() {
            for (int i = 0; i < this.coords.size(); i += 2) {
                short wx = this.coords.get(i);
                short wy = this.coords.get(i + 1);
                IsoChunk chunk = ServerMap.instance.getChunk(wx, wy);
                if (chunk == null) {
                    this.connection.addChunkObjectState(wx);
                    this.connection.addChunkObjectState(wy);
                } else {
                    ByteBufferWriter bbw = this.connection.startPacket();
                    // startPacket() locks UdpConnection.bufferLock; only send()/cancelPacket() release it.
                    // saveObjectState() (and object-specific save() overrides it calls into, e.g.
                    // IsoMannequin.save()) can throw unchecked exceptions, not just IOException. Catching
                    // only IOException here left bufferLock permanently held on any other exception,
                    // which then hangs every future startPacket() on this connection - including from the
                    // main thread - for the rest of the server's life. Always resolve the packet.
                    boolean packetResolved = false;

                    try {
                        PacketTypes.PacketType.ChunkObjectStateResponse.doPacket(bbw);
                        bbw.putShort(wx);
                        bbw.putShort(wy);
                        if (chunk.saveObjectState(bbw.bb)) {
                            PacketTypes.PacketType.ChunkObjectStateResponse.send(this.connection);
                        } else {
                            this.connection.cancelPacket();
                        }
                        packetResolved = true;
                    } catch (IOException var10) {
                        ExceptionLogger.logException(var10);
                        this.connection.cancelPacket();
                        packetResolved = true;
                    } finally {
                        if (!packetResolved) {
                            try {
                                this.connection.cancelPacket();
                            } catch (Throwable unlockFailure) {
                                ExceptionLogger.logException(unlockFailure);
                            }
                        }
                    }
                }
            }
        }
    }
}
