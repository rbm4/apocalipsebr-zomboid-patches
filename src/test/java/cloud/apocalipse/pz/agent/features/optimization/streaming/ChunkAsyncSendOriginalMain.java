package cloud.apocalipse.pz.agent.features.optimization.streaming;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import zombie.core.raknet.RakNetPeerInterface;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.random.RandStandard;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoMetaGrid;
import zombie.network.GameServer;
import zombie.network.PacketTypes.PacketType;
import zombie.network.PlayerDownloadServer;
import zombie.network.ServerMap;

/** Runs the original download loop and worker with a controlled RakNet transport. */
public final class ChunkAsyncSendOriginalMain {
    public static void main(String[] args) throws Exception {
        Path original = Path.of(args[0]).toRealPath();
        for (Class<?> type : new Class<?>[]{PlayerDownloadServer.class, PlayerDownloadServer.WorkerThread.class,
                IsoChunk.class, UdpEngine.class}) {
            check(original.equals(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()),
                "Wrong game class origin: " + type);
        }
        RandStandard.INSTANCE.init();
        DebugType.Multiplayer.setLogSeverity(LogSeverity.Error);
        GameServer.server = true;
        var cell = new ServerMap.ServerCell();
        cell.isLoaded = true;
        var chunk = new IsoChunk((IsoCell) null);
        chunk.loaded = true;
        cell.chunks[0][0] = chunk;
        var grid = new IsoMetaGrid();
        grid.minX = 0;
        grid.minY = 0;
        field(ServerMap.class, "grid").set(ServerMap.instance, grid);
        field(ServerMap.class, "width").setInt(ServerMap.instance, 1);
        field(ServerMap.class, "height").setInt(ServerMap.instance, 1);
        field(ServerMap.class, "cellMap").set(ServerMap.instance, new ServerMap.ServerCell[]{cell});

        if (Boolean.parseBoolean(args[1])) {
            preservesOrderAndIsolation();
            boundsStatusBacklog();
            disconnectAndPeerShutdown();
        } else {
            vanillaFallback();
        }
        System.out.println("ORIGINAL_CHUNK_ASYNC_SEND_PASS");
    }

    private static void preservesOrderAndIsolation() throws Exception {
        var peer = new RecordingPeer(1, -1);
        var slow = connection(peer, 1);
        var fast = connection(peer, 2);
        withDuplicate(slow, 10);
        enqueue(fast, 20);
        try {
            slow.update();
            await(peer.blocked, "Worker never entered the controlled status send");
            fast.update();
            requirePacket(peer, 2, PacketType.SentChunk, 20, fast.workerThread);
            peer.unblock.countDown();
            requirePacket(peer, 1, PacketType.NotRequiredInZip, 10, slow.workerThread);
            requirePacket(peer, 1, PacketType.SentChunk, 11, slow.workerThread);
        } finally {
            peer.unblock.countDown();
            slow.destroy();
            fast.destroy();
        }
    }

    private static void boundsStatusBacklog() throws Exception {
        var peer = new RecordingPeer(3, 0);
        var download = connection(peer, 3);
        var pending = field(PlayerDownloadServer.class, "pendingChunks");
        @SuppressWarnings("unchecked")
        Map<Integer, Object> requests = (Map<Integer, Object>) pending.get(download);
        Class<?> type = Class.forName("zombie.network.PlayerDownloadServer$PendingChunk");
        var constructor = type.getDeclaredConstructor(int.class, int.class, long.class);
        constructor.setAccessible(true);
        Field numbers = field(type, "requestNumbers");
        for (int i = 0; i < 300; i++) {
            Object request = constructor.newInstance(1000 + i, 1000, 0L);
            @SuppressWarnings("unchecked")
            ArrayList<Integer> ids = (ArrayList<Integer>) numbers.get(request);
            ids.add(i);
            requests.put(i, request);
        }
        try {
            download.update();
            await(peer.blocked, "Timed-out reply was not sent on the worker");
            int remaining = requests.size();
            check(remaining > 0 && remaining < 300, "Expected a bounded first batch");
            download.update();
            check(requests.size() == remaining, "A blocked worker must leave the next update pending");
            peer.unblock.countDown();
            for (int i = 0; i < 300 - remaining; i++) {
                requirePacket(peer, 3, PacketType.ChunkNotReady, i, download.workerThread);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!requests.isEmpty() && System.nanoTime() < deadline) {
                download.update();
                Thread.sleep(1);
            }
            check(requests.isEmpty(), "Pending requests did not resume after the status batch finished");
            for (int i = 300 - remaining; i < 300; i++) {
                requirePacket(peer, 3, PacketType.ChunkNotReady, i, download.workerThread);
            }
        } finally {
            peer.unblock.countDown();
            download.destroy();
        }
    }

    private static void disconnectAndPeerShutdown() throws Exception {
        var peer = new RecordingPeer(4, -1);
        var connection = recipient(peer, 4);
        var download = new PlayerDownloadServer(connection);
        withDuplicate(download, 40);
        Thread worker = download.workerThread;
        try {
            download.update();
            await(peer.blocked, "Status send did not block before retirement");
            var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            var engine = (UdpEngine) unsafe.allocateInstance(UdpEngine.class);
            field(UdpEngine.class, "peer").set(engine, peer);
            field(UdpEngine.class, "thread").set(engine, new Thread());
            var connections = new HashMap<>(Map.of(4L, connection));
            UdpConnection[] indexed = {connection};
            field(UdpEngine.class, "connectionMap").set(engine, connections);
            field(UdpEngine.class, "connectionArray").set(engine, indexed);
            GameServer.server = false; // The fixture has no login/safety subsystems to notify.
            try {
                engine.removeConnection(4L);
            } finally {
                GameServer.server = true;
            }
            check(connections.isEmpty() && indexed[0] == null, "Vanilla connection removal did not run");
            download.destroy();
            check(download.workerThread == null && worker.isAlive(), "Destroy waited for the blocked worker");
            Thread shutdown = Thread.ofPlatform().daemon().start(engine::Shutdown);
            shutdown.join(100);
            check(shutdown.isAlive() && peer.shutdown.getCount() == 1, "Native peer freed during a send");
            peer.unblock.countDown();
            await(peer.shutdown, "Native peer was not shut down after the send returned");
            shutdown.join(5000);
            worker.join(5000);
            check(!shutdown.isAlive() && !worker.isAlive(), "Worker or shutdown did not exit");
            requirePacket(peer, 4, PacketType.NotRequiredInZip, 40, worker);
            check(peer.sent.isEmpty(), "A queued payload was sent after disconnect");
        } finally {
            peer.unblock.countDown();
            download.destroy();
        }
    }

    private static void vanillaFallback() throws Exception {
        var peer = new RecordingPeer(-1, -1);
        var download = connection(peer, 5);
        withDuplicate(download, 50);
        try {
            download.update();
            requirePacket(peer, 5, PacketType.NotRequiredInZip, 50, Thread.currentThread());
            requirePacket(peer, 5, PacketType.SentChunk, 51, download.workerThread);
        } finally {
            download.destroy();
        }
    }

    private static PlayerDownloadServer connection(RecordingPeer peer, long guid) {
        return new PlayerDownloadServer(recipient(peer, guid));
    }

    private static UdpConnection recipient(RecordingPeer peer, long guid) {
        return new UdpConnection(null, guid, 0) {
            @Override public RakNetPeerInterface getPeer() { return peer; }
        };
    }

    private static void withDuplicate(PlayerDownloadServer download, int request) {
        enqueue(download, request);
        enqueue(download, request + 1);
    }

    private static void enqueue(PlayerDownloadServer download, int request) {
        var batch = download.getClientChunkRequest(false);
        var chunk = batch.getChunk();
        chunk.wx = 0;
        chunk.wy = 0;
        chunk.crc = 0;
        chunk.requestNumber = request;
        batch.chunks.add(chunk);
        download.ccrWaiting.add(batch);
    }

    private static void requirePacket(RecordingPeer peer, long guid, PacketType type, int request, Thread sender)
            throws Exception {
        Packet packet = peer.sent.poll(5, TimeUnit.SECONDS);
        check(packet != null && packet.guid == guid && packet.type == type && packet.request == request
            && packet.sender == sender, "Unexpected packet, waiting for " + type + "/" + request + ": " + packet);
        byte ordering = type == PacketType.ChunkNotReady ? type.orderingChannel : 0;
        check(packet.ordering == ordering && packet.priority == type.packetPriority
            && packet.reliability == type.packetReliability, "Vanilla transport parameters changed");
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        check(latch.await(5, TimeUnit.SECONDS), message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Packet(long guid, PacketType type, int request, int priority, int reliability, byte ordering, Thread sender) {}

    private static final class RecordingPeer extends RakNetPeerInterface {
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch unblock = new CountDownLatch(1);
        final CountDownLatch shutdown = new CountDownLatch(1);
        final LinkedBlockingQueue<Packet> sent = new LinkedBlockingQueue<>();
        final long pausedGuid;
        final int failRequest;

        RecordingPeer(long pausedGuid, int failRequest) {
            this.pausedGuid = pausedGuid;
            this.failRequest = failRequest;
        }

        @Override public int SendRaw(ByteBuffer data, int priority, int reliability, byte ordering, long guid, boolean broadcast) {
            data = data.duplicate().order(ByteOrder.BIG_ENDIAN);
            check(data.isDirect() && !broadcast && Byte.toUnsignedInt(data.get()) == 134, "Invalid native send envelope");
            PacketType type = PacketType.getById(data.getShort());
            if (type != PacketType.SentChunk) check(data.getInt() == 1, "Malformed control reply");
            int request = data.getInt();
            if (type == PacketType.NotRequiredInZip) {
                check(data.remaining() == 1 && data.get() == 0, "Wrong duplicate resolution flag");
            }
            if (guid == pausedGuid && blocked.getCount() != 0) {
                blocked.countDown();
                try {
                    await(unblock, "Controlled worker send was never released");
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
            }
            sent.add(new Packet(guid, type, request, priority, reliability, ordering, Thread.currentThread()));
            if (request == failRequest && type == PacketType.ChunkNotReady) {
                throw new IllegalStateException("Controlled transport failure");
            }
            return 1;
        }

        @Override public void Shutdown() { shutdown.countDown(); }
    }
}
