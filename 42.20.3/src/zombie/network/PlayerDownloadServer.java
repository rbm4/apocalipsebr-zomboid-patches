// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.lwjglx.BufferUtils;
import zombie.ChunkMapFilenames;
import zombie.core.logger.LoggerManager;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.random.Rand;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoChunk;
import zombie.iso.IsoWorld;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.SentChunkPacket;

public final class PlayerDownloadServer {
    private static final long CHUNK_GENERATION_TIMEOUT_MS = 30000L;
    private static final long OUT_OF_RANGE_GRACE_MS = 5000L;
    private static final int OUT_OF_RANGE_GRID_FACTOR = 2;
    private static final int MAX_PENDING_CHUNKS = 4096;
    private static final int MAX_OUT_OF_RANGE_REQUESTS = 1024;
    public PlayerDownloadServer.WorkerThread workerThread;
    private final UdpConnection connection;
    private boolean networkFileDebug;
    private final CRC32 crc32 = new CRC32();
    private final ByteBuffer bb = ByteBuffer.allocate(1000000);
    private final ByteBuffer sb = BufferUtils.createByteBuffer(1000000);
    private final ByteBufferWriter bbw = new ByteBufferWriter(this.bb);
    private final ConcurrentLinkedQueue<PlayerDownloadServer.QueuedRequest> queuedByWorker = new ConcurrentLinkedQueue<>();
    private final List<PlayerDownloadServer.OutOfRangeRequest> outOfRangeRequests = new ArrayList<>();
    private final LinkedHashMap<Integer, PlayerDownloadServer.PendingChunk> pendingChunks = new LinkedHashMap<>();
    public final List<ClientChunkRequest> ccrWaiting = new ArrayList<>();

    public PlayerDownloadServer(UdpConnection connection) {
        this.connection = connection;
        this.workerThread = new PlayerDownloadServer.WorkerThread();
        this.workerThread.setDaemon(true);
        this.workerThread.setName("PlayerDownloadServer" + Rand.Next(Integer.MAX_VALUE));
        this.workerThread.start();
    }

    public void destroy() {
        this.workerThread.putCommand(PlayerDownloadServer.EThreadCommand.Quit, null);

        while (this.workerThread.isAlive()) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException var2) {
            }
        }

        this.workerThread = null;
    }

    private static int chunkKey(int wx, int wy) {
        return wx << 16 | wy & 65535;
    }

    void queueUntilGenerated(int requestNumber, int wx, int wy) {
        this.queuedByWorker.add(new PlayerDownloadServer.QueuedRequest(requestNumber, wx, wy));
    }

    private boolean isRequestInRange(int wx, int wy) {
        int gridWidth = this.connection.getChunkGridWidth();
        if (gridWidth <= 0) {
            return true;
        } else {
            float x = wx * 8 + 4.0F;
            float y = wy * 8 + 4.0F;
            float radius = gridWidth * 2.0F * 8.0F;
            return this.connection.RelevantTo(x, y, radius);
        }
    }

    private void addPendingChunk(PlayerDownloadServer.QueuedRequest queued) {
        if (this.pendingChunks.size() >= 4096) {
            INetworkPacket.send(this.connection, PacketTypes.PacketType.ChunkNotReady, queued.requestNumber);
        } else {
            this.pendingChunks
                    .computeIfAbsent(
                        chunkKey(queued.wx, queued.wy), key -> new PlayerDownloadServer.PendingChunk(queued.wx, queued.wy, System.currentTimeMillis())
                    )
                .requestNumbers
                .add(queued.requestNumber);
        }
    }

    private void updateOutOfRangeRequests() {
        if (!this.outOfRangeRequests.isEmpty()) {
            long now = System.currentTimeMillis();

            for (int i = 0; i < this.outOfRangeRequests.size(); i++) {
                PlayerDownloadServer.OutOfRangeRequest held = this.outOfRangeRequests.get(i);
                if (this.workerThread.isRequestCancelled(held.request.requestNumber)) {
                    this.outOfRangeRequests.remove(i--);
                } else if (this.isRequestInRange(held.request.wx, held.request.wy)) {
                    this.outOfRangeRequests.remove(i--);
                    this.addPendingChunk(held.request);
                } else if (now - held.receivedMs > 5000L) {
                    DebugType.Multiplayer
                        .warn("chunk request %d,%d stayed outside the streamed area of %s", held.request.wx, held.request.wy, this.connection.getUserName());
                    this.outOfRangeRequests.remove(i--);
                    if (GameServer.chunkGridWidthCommandline != -1) {
                        INetworkPacket.send(this.connection, PacketTypes.PacketType.ChunkNotReady, held.request.requestNumber);
                    } else {
                        this.addPendingChunk(held.request);
                    }
                }
            }
        }
    }

    private void updatePendingChunks() {
        for (PlayerDownloadServer.QueuedRequest queued = this.queuedByWorker.poll(); queued != null; queued = this.queuedByWorker.poll()) {
            int wx = queued.wx;
            int wy = queued.wy;
            if (!IsoWorld.instance.getMetaGrid().isValidChunk(wx, wy)) {
                this.workerThread.sendNotRequired(queued.requestNumber, true);
            } else if (!this.isRequestInRange(wx, wy)) {
                if (this.outOfRangeRequests.size() >= 1024) {
                    PlayerDownloadServer.OutOfRangeRequest oldest = this.outOfRangeRequests.remove(0);
                    INetworkPacket.send(this.connection, PacketTypes.PacketType.ChunkNotReady, oldest.request.requestNumber);
                }

                this.outOfRangeRequests.add(new PlayerDownloadServer.OutOfRangeRequest(queued, System.currentTimeMillis()));
            } else {
                this.addPendingChunk(queued);
            }
        }

        this.updateOutOfRangeRequests();
        if (!this.pendingChunks.isEmpty()) {
            long now = System.currentTimeMillis();
            ClientChunkRequest readyRequest = null;
            Iterator<Entry<Integer, PlayerDownloadServer.PendingChunk>> iterator = this.pendingChunks.entrySet().iterator();

            while (iterator.hasNext()) {
                PlayerDownloadServer.PendingChunk pendingChunk = iterator.next().getValue();

                for (int i = 0; i < pendingChunk.requestNumbers.size(); i++) {
                    if (this.workerThread.isRequestCancelled(pendingChunk.requestNumbers.get(i))) {
                        pendingChunk.requestNumbers.remove(i--);
                    }
                }

                if (pendingChunk.requestNumbers.isEmpty()) {
                    iterator.remove();
                } else {
                    ServerMap.instance
                        .loadOrKeepRelevent(
                            PZMath.coorddivision(pendingChunk.wx, 8) - ServerMap.instance.getMinX(),
                            PZMath.coorddivision(pendingChunk.wy, 8) - ServerMap.instance.getMinY()
                        );
                    IsoChunk serverChunk = ServerMap.instance.getChunk(pendingChunk.wx, pendingChunk.wy);
                    if (serverChunk != null && serverChunk.loaded) {
                        for (int ix = 0; ix < pendingChunk.requestNumbers.size(); ix++) {
                            if (readyRequest == null || readyRequest.isChunksFilled()) {
                                if (readyRequest != null) {
                                    this.ccrWaiting.add(readyRequest);
                                }

                                readyRequest = this.getClientChunkRequest(false);
                            }

                            ClientChunkRequest.Chunk reqChunk = readyRequest.getChunk();
                            reqChunk.requestNumber = pendingChunk.requestNumbers.get(ix);
                            reqChunk.wx = pendingChunk.wx;
                            reqChunk.wy = pendingChunk.wy;
                            reqChunk.crc = 0L;
                            readyRequest.chunks.add(reqChunk);
                        }

                        iterator.remove();
                    } else if (now - pendingChunk.firstRequestedMs > 30000L) {
                        DebugType.Multiplayer
                            .warn("the chunk %d,%d was not generated in %d ms", pendingChunk.wx, pendingChunk.wy, now - pendingChunk.firstRequestedMs);
                        INetworkPacket.send(this.connection, PacketTypes.PacketType.ChunkNotReady, pendingChunk.requestNumbers.toArray());
                        iterator.remove();
                    }
                }
            }

            if (readyRequest != null) {
                this.ccrWaiting.add(readyRequest);
            }
        }
    }

    public ClientChunkRequest getClientChunkRequest(boolean isLargeArea) {
        ClientChunkRequest ccr = this.workerThread.freeRequests.poll();
        if (ccr == null) {
            ccr = new ClientChunkRequest();
        }

        ccr.largeArea = isLargeArea;
        return ccr;
    }

    public final int getWaitingRequests() {
        return this.ccrWaiting.size();
    }

    public void update() {
        this.networkFileDebug = DebugType.NetworkFileDebug.isEnabled();
        if (this.workerThread.ready) {
            this.updatePendingChunks();
            this.removeOlderDuplicateRequests();
            if (this.ccrWaiting.isEmpty()) {
                if (this.workerThread.cancelQ.isEmpty() && !this.workerThread.cancelled.isEmpty()) {
                    this.workerThread.cancelled.clear();
                }
            } else {
                ClientChunkRequest ccr = this.ccrWaiting.remove(0);

                for (int i = 0; i < ccr.chunks.size(); i++) {
                    ClientChunkRequest.Chunk reqChunk = ccr.chunks.get(i);
                    if (this.workerThread.isRequestCancelled(reqChunk.requestNumber)) {
                        ccr.chunks.remove(i--);
                        ccr.releaseChunk(reqChunk);
                    } else {
                        IsoChunk chunk = ServerMap.instance.getChunk(reqChunk.wx, reqChunk.wy);
                        if (chunk != null && chunk.loaded) {
                            try {
                                ccr.getByteBuffer(reqChunk);
                                chunk.SaveLoadedChunk(reqChunk, this.crc32);
                            } catch (Exception var6) {
                                DebugType.General.printException(var6, LogSeverity.Error);
                                LoggerManager.getLogger("map").write(var6);
                                this.workerThread.sendNotRequired(reqChunk.requestNumber, false);
                                ccr.chunks.remove(i--);
                                ccr.releaseChunk(reqChunk);
                            }
                        }
                    }
                }

                if (ccr.chunks.isEmpty()) {
                    this.workerThread.freeRequests.add(ccr);
                } else {
                    this.workerThread.ready = false;
                    this.workerThread.putCommand(PlayerDownloadServer.EThreadCommand.RequestZipArray, ccr);
                }
            }
        }
    }

    private void removeOlderDuplicateRequests() {
        for (int i = this.ccrWaiting.size() - 1; i >= 0; i--) {
            ClientChunkRequest ccr1 = this.ccrWaiting.get(i);

            for (int j = 0; j < ccr1.chunks.size(); j++) {
                ClientChunkRequest.Chunk chunk1 = ccr1.chunks.get(j);
                if (this.workerThread.isRequestCancelled(chunk1.requestNumber)) {
                    ccr1.chunks.remove(j--);
                    ccr1.releaseChunk(chunk1);
                } else {
                    for (int k = i - 1; k >= 0; k--) {
                        ClientChunkRequest ccr2 = this.ccrWaiting.get(k);
                        if (this.cancelDuplicateChunk(ccr2, chunk1.wx, chunk1.wy)) {
                        }
                    }
                }
            }

            if (ccr1.chunks.isEmpty()) {
                this.ccrWaiting.remove(i);
                this.workerThread.freeRequests.add(ccr1);
            }
        }
    }

    private boolean cancelDuplicateChunk(ClientChunkRequest ccr, int wx, int wy) {
        for (int i = 0; i < ccr.chunks.size(); i++) {
            ClientChunkRequest.Chunk reqChunk = ccr.chunks.get(i);
            if (this.workerThread.isRequestCancelled(reqChunk.requestNumber)) {
                ccr.chunks.remove(i--);
                ccr.releaseChunk(reqChunk);
            } else if (reqChunk.wx == wx && reqChunk.wy == wy) {
                this.workerThread.sendNotRequired(reqChunk.requestNumber, false);
                ccr.chunks.remove(i);
                ccr.releaseChunk(reqChunk);
                return true;
            }
        }

        return false;
    }

    private void sendPacket(PacketTypes.PacketType packetType) {
        this.bb.flip();
        this.sb.put(this.bb);
        this.sb.flip();
        this.connection.getPeer().SendRaw(this.sb, packetType.packetPriority, packetType.packetReliability, (byte)0, this.connection.getConnectedGUID(), false);
        this.sb.clear();
    }

    private ByteBufferWriter startPacket() {
        this.bb.clear();
        return this.bbw;
    }

    private static enum EThreadCommand {
        RequestLargeArea,
        RequestZipArray,
        Quit;
    }

    private static final class OutOfRangeRequest {
        final PlayerDownloadServer.QueuedRequest request;
        final long receivedMs;

        OutOfRangeRequest(PlayerDownloadServer.QueuedRequest request, long receivedMs) {
            this.request = request;
            this.receivedMs = receivedMs;
        }
    }

    private static final class PendingChunk {
        final int wx;
        final int wy;
        final long firstRequestedMs;
        final List<Integer> requestNumbers = new ArrayList<>();

        PendingChunk(int wx, int wy, long firstRequestedMs) {
            this.wx = wx;
            this.wy = wy;
            this.firstRequestedMs = firstRequestedMs;
        }
    }

    private static final class QueuedRequest {
        final int requestNumber;
        final int wx;
        final int wy;

        QueuedRequest(int requestNumber, int wx, int wy) {
            this.requestNumber = requestNumber;
            this.wx = wx;
            this.wy = wy;
        }
    }

    public final class WorkerThread extends Thread {
        boolean quit;
        volatile boolean ready;
        final LinkedBlockingQueue<PlayerDownloadServer.WorkerThreadCommand> commandQ;
        final ConcurrentLinkedQueue<ClientChunkRequest> freeRequests;
        public final ConcurrentLinkedQueue<Integer> cancelQ;
        final HashSet<Integer> cancelled;
        final CRC32 crcMaker;
        byte[] inMemoryZip;
        final Deflater compressor;

        public WorkerThread() {
            Objects.requireNonNull(PlayerDownloadServer.this);
            super();
            this.ready = true;
            this.commandQ = new LinkedBlockingQueue<>();
            this.freeRequests = new ConcurrentLinkedQueue<>();
            this.cancelQ = new ConcurrentLinkedQueue<>();
            this.cancelled = new HashSet<>();
            this.crcMaker = new CRC32();
            this.inMemoryZip = new byte[20480];
            this.compressor = new Deflater();
        }

        @Override
        public void run() {
            while (!this.quit) {
                try {
                    this.runInner();
                } catch (Exception var2) {
                    DebugType.General.printException(var2, LogSeverity.Error);
                }
            }
        }

        private void runInner() throws InterruptedException, IOException {
            PlayerDownloadServer.WorkerThreadCommand command = this.commandQ.take();
            switch (command.e) {
                case RequestLargeArea:
                    try {
                        this.sendLargeArea(command.ccr);
                        break;
                    } finally {
                        this.ready = true;
                    }
                case RequestZipArray:
                    try {
                        this.sendArray(command.ccr);
                        break;
                    } finally {
                        this.ready = true;
                    }
                case Quit:
                    this.quit = true;
            }
        }

        void putCommand(PlayerDownloadServer.EThreadCommand e, ClientChunkRequest ccr) {
            PlayerDownloadServer.WorkerThreadCommand command = new PlayerDownloadServer.WorkerThreadCommand();
            command.e = e;
            command.ccr = ccr;

            while (true) {
                try {
                    this.commandQ.put(command);
                    return;
                } catch (InterruptedException var5) {
                }
            }
        }

        public int compressChunk(ClientChunkRequest.Chunk chunk) {
            this.compressor.reset();
            this.compressor.setInput(chunk.bb.array(), 0, chunk.bb.limit());
            this.compressor.finish();
            if (this.inMemoryZip.length < chunk.bb.limit() * 1.5) {
                this.inMemoryZip = new byte[(int)(chunk.bb.limit() * 1.5)];
            }

            return this.compressor.deflate(this.inMemoryZip, 0, this.inMemoryZip.length, 3);
        }

        private void sendChunk(ClientChunkRequest.Chunk chunk) {
            try {
                SentChunkPacket packet = new SentChunkPacket();
                int filesize = this.compressChunk(chunk);
                packet.setChunk(chunk, filesize, this.inMemoryZip);

                while (packet.hasData()) {
                    ByteBufferWriter b = PlayerDownloadServer.this.startPacket();
                    PacketTypes.PacketType.SentChunk.doPacket(b);
                    packet.write(b);
                    PlayerDownloadServer.this.sendPacket(PacketTypes.PacketType.SentChunk);
                }
            } catch (Exception var5) {
                DebugType.Multiplayer.printException(var5, "sendChunk error", LogSeverity.Error);
                this.sendNotRequired(chunk.requestNumber, false);
            }
        }

        private void sendNotRequired(int requestNumber, boolean sameOnServer) {
            ByteBufferWriter b = PlayerDownloadServer.this.startPacket();
            PacketTypes.PacketType.NotRequiredInZip.doPacket(b);
            b.putInt(1);
            b.putInt(requestNumber);
            b.putBoolean(sameOnServer);
            PlayerDownloadServer.this.sendPacket(PacketTypes.PacketType.NotRequiredInZip);
        }

        private void sendLargeArea(ClientChunkRequest ccr) throws IOException {
            for (int n = 0; n < ccr.chunks.size(); n++) {
                ClientChunkRequest.Chunk reqChunk = ccr.chunks.get(n);
                int wx = reqChunk.wx;
                int wy = reqChunk.wy;
                if (reqChunk.bb != null) {
                    reqChunk.bb.limit(reqChunk.bb.position());
                    reqChunk.bb.position(0);
                    this.sendChunk(reqChunk);
                    ccr.releaseBuffer(reqChunk);
                } else {
                    File inFile = ChunkMapFilenames.instance.getFilename(wx, wy);
                    if (inFile.exists()) {
                        ccr.getByteBuffer(reqChunk);
                        reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);
                        this.sendChunk(reqChunk);
                        ccr.releaseBuffer(reqChunk);
                    }
                }
            }

            ClientChunkRequest.freeBuffers.clear();
            ccr.chunks.clear();
        }

        private void sendArray(ClientChunkRequest ccr) throws IOException {
            for (int n = 0; n < ccr.chunks.size(); n++) {
                ClientChunkRequest.Chunk reqChunk = ccr.chunks.get(n);
                if (!this.isRequestCancelled(reqChunk.requestNumber)) {
                    int wx = reqChunk.wx;
                    int wy = reqChunk.wy;
                    if (reqChunk.bb != null) {
                        boolean add = true;
                        if (reqChunk.crc != 0L) {
                            this.crcMaker.reset();
                            this.crcMaker.update(reqChunk.bb.array(), 0, reqChunk.bb.position());
                            add = reqChunk.crc != this.crcMaker.getValue();
                            if (add && PlayerDownloadServer.this.networkFileDebug) {
                                DebugType.NetworkFileDebug.debugln(wx + "," + wy + ": crc server=" + this.crcMaker.getValue() + " client=" + reqChunk.crc);
                            }
                        }

                        if (add) {
                            if (PlayerDownloadServer.this.networkFileDebug) {
                                DebugType.NetworkFileDebug.debugln(wx + "," + wy + ": send=true loaded=true");
                            }

                            reqChunk.bb.limit(reqChunk.bb.position());
                            reqChunk.bb.position(0);
                            this.sendChunk(reqChunk);
                        } else {
                            if (PlayerDownloadServer.this.networkFileDebug) {
                                DebugType.NetworkFileDebug.debugln(wx + "," + wy + ": send=false loaded=true");
                            }

                            this.sendNotRequired(reqChunk.requestNumber, true);
                        }

                        ccr.releaseBuffer(reqChunk);
                    } else {
                        File inFile = ChunkMapFilenames.instance.getFilename(wx, wy);
                        if (inFile.exists()) {
                            long crcCached = ChunkChecksum.getChecksum(wx, wy);
                            if (crcCached != 0L && crcCached == reqChunk.crc) {
                                if (PlayerDownloadServer.this.networkFileDebug) {
                                    DebugType.NetworkFileDebug.debugln(wx + "," + wy + ": send=false loaded=false file=true");
                                }

                                this.sendNotRequired(reqChunk.requestNumber, true);
                            } else {
                                ccr.getByteBuffer(reqChunk);
                                reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);
                                boolean addx = true;
                                if (reqChunk.crc != 0L) {
                                    this.crcMaker.reset();
                                    this.crcMaker.update(reqChunk.bb.array(), 0, reqChunk.bb.limit());
                                    addx = reqChunk.crc != this.crcMaker.getValue();
                                }

                                if (addx) {
                                    if (PlayerDownloadServer.this.networkFileDebug) {
                                        DebugType.NetworkFileDebug.debugln(wx + "," + wy + ": send=true loaded=false file=true");
                                    }

                                    this.sendChunk(reqChunk);
                                } else {
                                    if (PlayerDownloadServer.this.networkFileDebug) {
                                        DebugType.NetworkFileDebug.debugln(wx + "," + wy + ": send=false loaded=false file=true");
                                    }

                                    this.sendNotRequired(reqChunk.requestNumber, true);
                                }

                                ccr.releaseBuffer(reqChunk);
                            }
                        } else {
                            if (PlayerDownloadServer.this.networkFileDebug) {
                                DebugType.NetworkFileDebug.debugln(wx + "," + wy + ": send=false loaded=false file=false");
                            }

                            PlayerDownloadServer.this.queueUntilGenerated(reqChunk.requestNumber, wx, wy);
                        }
                    }
                }
            }

            for (int nx = 0; nx < ccr.chunks.size(); nx++) {
                ccr.releaseChunk(ccr.chunks.get(nx));
            }

            ccr.chunks.clear();
            this.freeRequests.add(ccr);
        }

        private boolean isRequestCancelled(int requestNumber) {
            for (Integer cancelledNumber = this.cancelQ.poll(); cancelledNumber != null; cancelledNumber = this.cancelQ.poll()) {
                this.cancelled.add(cancelledNumber);
            }

            if (this.cancelled.remove(requestNumber)) {
                if (PlayerDownloadServer.this.networkFileDebug) {
                    DebugType.NetworkFileDebug.debugln("cancelled request #" + requestNumber);
                }

                return true;
            } else {
                return false;
            }
        }
    }

    private static final class WorkerThreadCommand {
        PlayerDownloadServer.EThreadCommand e;
        ClientChunkRequest ccr;
    }
}
