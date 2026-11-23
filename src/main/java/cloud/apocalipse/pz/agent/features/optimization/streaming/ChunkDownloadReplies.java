package cloud.apocalipse.pz.agent.features.optimization.streaming;

import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry.Counter;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry.Phase;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.RakNetPeerInterface;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.IConnection;
import zombie.network.packets.INetworkPacket;
import zombie.network.statistics.data.NetworkStatistic;
import zombie.network.PacketTypes.PacketType;

/** Uses the vanilla per-download worker and command order for control replies and chunk payloads. */
public final class ChunkDownloadReplies {
    private static final int MAX_REPLIES = 256;
    // Vanilla normal batches contain up to 20 chunks, each of which can fail serialization.
    private static final int BOOKKEEPING_LIMIT = MAX_REPLIES - 20;
    private static final int MAX_PACKET_BYTES = 1_000_000;
    private static final ConcurrentHashMap<Thread, Lane> LANES = new ConcurrentHashMap<>();
    private static final ThreadLocal<Lane> CURRENT = new ThreadLocal<>();
    private static final Object STOP = new Object();

    private ChunkDownloadReplies() {}

    public static void register(Thread worker, LinkedBlockingQueue<Object> commands) {
        LANES.put(worker, new Lane(worker, commands));
    }

    public static void attach(Thread worker, UdpConnection connection) {
        Lane lane = LANES.get(worker);
        if (lane != null) {
            lane.peer = connection.getPeer();
            lane.guid = connection.getConnectedGUID();
            lane.connection = connection;
        }
    }

    public static boolean begin(Thread worker) {
        Lane lane = worker == null ? null : LANES.get(worker);
        if (lane == null || lane.closed) return true;
        if (lane.replyBatchInFlight) {
            OptimizationTelemetry.count(Counter.CHUNK_REPLY_BACKPRESSURE);
            return true;
        }
        CURRENT.set(lane);
        return false;
    }

    public static boolean hasBudget() {
        Lane lane = CURRENT.get();
        return lane == null || (!lane.closed && lane.replies.size() < BOOKKEEPING_LIMIT);
    }

    public static Object pollPending(ConcurrentLinkedQueue<?> queue) {
        return hasBudget() ? queue.poll() : null;
    }

    public static boolean hasPending(Iterator<?> iterator) {
        return hasBudget() && iterator.hasNext();
    }

    public static int outOfRangeSize(List<?> requests) {
        return hasBudget() ? requests.size() : 0;
    }

    public static void checkDuplicateBudget() {
        if (!hasBudget()) {
            // The caller has left the next request in its original list.
            throw YieldUpdate.INSTANCE;
        }
    }

    public static boolean notRequired(int request, boolean same) {
        Lane lane = CURRENT.get();
        if (lane == null) return false; // Vanilla worker call.
        lane.add(new Reply(PacketType.NotRequiredInZip, new Object[]{request}, same));
        return true;
    }

    public static void notReady(IConnection connection, PacketType type, Object[] requests) {
        Lane lane = CURRENT.get();
        if (lane == null) {
            INetworkPacket.send(connection, type, requests);
        } else {
            // The original call supplies a fresh varargs array or List.toArray().
            int length = 7 + requests.length * Integer.BYTES;
            try {
                if (length <= MAX_PACKET_BYTES) {
                    NetworkStatistic.getInstance().addOutcomePacket(type.getId(), length, (UdpConnection) connection);
                }
            } catch (Exception failure) {
                System.err.println("[ApocBR-Agent][ChunkSend] Status accounting failed: " + failure);
            }
            lane.add(new Reply(type, requests, false));
        }
    }

    public static void beforePayload() {
        Lane lane = CURRENT.get();
        if (lane != null) lane.publish();
    }

    public static Throwable finish(Throwable fault) {
        Lane lane = CURRENT.get();
        if (lane != null) {
            CURRENT.remove();
            lane.publish();
        }
        return fault == YieldUpdate.INSTANCE ? null : fault;
    }

    public static Object takeCommand(LinkedBlockingQueue<?> queue) throws InterruptedException {
        Lane lane = LANES.get(Thread.currentThread());
        while (true) {
            Object command = queue.take();
            if (command == STOP || (lane != null && lane.closed)) throw StopWorker.INSTANCE;
            if (!(command instanceof ReplyBatch batch)) return command;
            if (lane == null) throw StopWorker.INSTANCE;
            try {
                for (Reply reply : batch.replies) {
                    if (lane.closed) break;
                    lane.send(reply);
                }
            } finally {
                lane.replyBatchInFlight = false;
            }
        }
    }

    public static boolean skipPayloadSend() {
        Lane lane = LANES.get(Thread.currentThread());
        return lane != null && lane.closed;
    }

    public static void retire(Thread worker) {
        Lane lane = worker == null ? null : LANES.get(worker);
        if (lane != null) lane.close();
    }

    public static Object removeConnection(Map<?, ?> connections, Object guid) {
        Object removed = connections.remove(guid);
        if (removed != null) {
            for (Lane lane : LANES.values()) {
                if (lane.connection == removed) lane.close();
            }
        }
        return removed;
    }

    public static void shutdownPeer(RakNetPeerInterface peer) {
        List<Lane> closing = LANES.values().stream().filter(lane -> lane.peer == peer).toList();
        closing.forEach(Lane::close);
        // Only final peer shutdown joins. Native sends must finish before the peer is freed.
        boolean interrupted = false;
        for (Lane lane : closing) {
            while (lane.worker.isAlive()) {
                try {
                    lane.worker.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        peer.Shutdown();
    }

    public static Throwable stopped(Throwable fault) {
        Lane lane = LANES.remove(Thread.currentThread());
        if (lane != null) lane.commands.clear();
        return fault == StopWorker.INSTANCE ? null : fault;
    }

    private record Reply(PacketType type, Object[] requests, boolean same) {}
    private record ReplyBatch(List<Reply> replies) {}

    private static final class Lane {
        final Thread worker;
        final LinkedBlockingQueue<Object> commands;
        final ArrayList<Reply> replies = new ArrayList<>(); // Simulation thread only.
        volatile UdpConnection connection;
        volatile RakNetPeerInterface peer;
        long guid;
        volatile boolean replyBatchInFlight;
        volatile boolean closed;
        ByteBuffer buffer;
        ByteBufferWriter writer;

        Lane(Thread worker, LinkedBlockingQueue<Object> commands) {
            this.worker = worker;
            this.commands = commands;
        }

        void close() {
            closed = true;
            commands.offer(STOP);
        }

        void add(Reply reply) {
            if (replies.size() >= MAX_REPLIES) {
                throw new IllegalStateException("Vanilla chunk reply batch exceeded its reserved capacity");
            }
            replies.add(reply);
        }

        void publish() {
            if (!replies.isEmpty()) {
                if (!closed) {
                    replyBatchInFlight = true;
                    commands.offer(new ReplyBatch(List.copyOf(replies)));
                }
                replies.clear();
            }
        }

        void send(Reply reply) {
            long started = System.nanoTime();
            try {
                int length = 7 + reply.requests.length * Integer.BYTES;
                if (reply.type == PacketType.NotRequiredInZip) length++;
                if (length > MAX_PACKET_BYTES) {
                    throw new IllegalArgumentException("Chunk status packet exceeds the vanilla send buffer");
                }
                if (buffer == null || buffer.capacity() < length) {
                    buffer = ByteBuffer.allocateDirect(Math.max(256, length));
                    writer = new ByteBufferWriter(buffer);
                }
                buffer.clear();
                reply.type.doPacket(writer);
                writer.putInt(reply.requests.length);
                for (Object request : reply.requests) writer.putInt((Integer) request);
                if (reply.type == PacketType.NotRequiredInZip) writer.putBoolean(reply.same);
                byte ordering = 0; // Vanilla PlayerDownloadServer.sendPacket uses channel zero.
                if (reply.type == PacketType.ChunkNotReady) {
                    ordering = reply.type.orderingChannel;
                }
                if (closed) return;
                int result = peer.SendRaw(buffer.flip(), reply.type.packetPriority, reply.type.packetReliability, ordering, guid, false);
                if (result <= 0) OptimizationTelemetry.count(Counter.CHUNK_REPLY_SEND_FAILED);
                if (reply.type == PacketType.ChunkNotReady && reply.type.isLogEnabled()) {
                    DebugType.Packet.debugln("%s %s", connection.getDescription(), reply.type.name());
                }
            } catch (Exception failure) {
                OptimizationTelemetry.count(Counter.CHUNK_REPLY_SEND_FAILED);
                System.err.println("[ApocBR-Agent][ChunkSend] Status send failed: " + failure);
            } finally {
                Phase.CHUNK_REPLY_TRANSPORT.recordSince(started);
            }
        }
    }

    private static final class YieldUpdate extends RuntimeException {
        static final YieldUpdate INSTANCE = new YieldUpdate();
        private YieldUpdate() { super(null, null, false, false); }
    }

    private static final class StopWorker extends Error {
        static final StopWorker INSTANCE = new StopWorker();
        private StopWorker() { super(null, null, false, false); }
    }
}
