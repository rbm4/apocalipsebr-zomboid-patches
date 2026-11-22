package cloud.apocalipse.pz.agent.features.optimization.zombie.transmission;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import zombie.characters.IsoZombie;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.utils.UpdateLimit;
import zombie.network.GameServer;
import zombie.network.PacketTypes.PacketType;
import zombie.network.ServerMap;
import zombie.network.packets.character.ZombieListPacket;
import zombie.network.statistics.data.NetworkStatistic;
import zombie.popman.NetworkZombieManager;
import zombie.popman.NetworkZombiePacker;
import zombie.popman.NetworkZombiePacker.DeletedZombie;

final class ZombieSendRuntime {
    private static final int MAX_ZOMBIES = 300;
    private static final int MAX_SESSIONS = 256;
    private final UdpEngine engine;
    private final ZombieSendWorkers workers = new ZombieSendWorkers(
        setting(AgentConfiguration.ZOMBIE_SEND_WORKERS_PROPERTY, 6, 32),
        setting(AgentConfiguration.ZOMBIE_SEND_CAPACITY_PROPERTY, 64, MAX_SESSIONS));
    private final Map<UdpConnection, Session> sessions = new IdentityHashMap<>();
    private final Map<ZombieSendWorkers.Lane, Session> byLane = new IdentityHashMap<>();
    private final ConcurrentHashMap<Long, UdpConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ZombieSendWorkers.Lane> transports = new ConcurrentHashMap<>();
    private final ByteBuffer capture = ByteBuffer.allocate(ZombieSendWorkers.MAX_BYTES);
    private final ByteBufferWriter writer = new ByteBufferWriter(capture);
    private int nextStart;
    private volatile boolean closed;

    ZombieSendRuntime(UdpEngine engine) {
        this.engine = engine;
    }

    private static int setting(String name, int fallback, int max) {
        try {
            return Math.clamp(Integer.parseInt(AgentConfiguration.systemProperty(name, Integer.toString(fallback))), 1, max);
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    UdpEngine engine() {
        return engine;
    }

    void connect(UdpConnection connection) {
        if (!closed) {
            connections.put(connection.getConnectedGUID(), connection);
        }
    }

    void disconnect(long guid) {
        connections.remove(guid);
        var transport = transports.remove(guid);
        if (transport != null) {
            transport.close();
        }
    }

    synchronized void dispatch(NetworkZombiePacker packer, List<IsoZombie> relays, List<DeletedZombie> deletions,
            Set<zombie.network.IConnection> extra, UpdateLimit reliableLimiter) {
        if (closed) {
            return;
        }
        List<UdpConnection> eligible = new ArrayList<>();
        for (UdpConnection connection : engine.connections) {
            if (connection != null && connection.isFullyConnected() && !GameServer.isDelayedDisconnect(connection)
                    && connections.get(connection.getConnectedGUID()) == connection) {
                eligible.add(connection);
            }
        }
        Set<UdpConnection> active = Collections.newSetFromMap(new IdentityHashMap<>());
        active.addAll(eligible);
        var old = sessions.entrySet().iterator();
        while (old.hasNext()) {
            var entry = old.next();
            if (!active.contains(entry.getKey())) {
                Session session = entry.getValue();
                transports.remove(session.connection.getConnectedGUID(), session.lane);
                session.lane.close();
                byLane.remove(session.lane);
                packer.zombiesToSend.remove(session.connection);
                old.remove();
            }
        }
        drain(packer);
        if (eligible.isEmpty()) {
            nextStart = 0;
            return;
        }
        int start = nextStart % eligible.size();
        for (int offset = 0; offset < eligible.size(); offset++) {
            int index = (start + offset) % eligible.size();
            UdpConnection connection = eligible.get(index);
            Session session = sessions.get(connection);
            if (session == null) {
                session = addSession(connection);
            }
            if (session == null) {
                continue;
            }
            for (DeletedZombie deletion : deletions) {
                if (connection.isRelevantTo(deletion.x, deletion.y)) {
                    session.deletions.add(deletion.onlineId);
                }
            }
            session.relays.removeIf(zombie -> !relevant(connection, zombie));
            for (IsoZombie zombie : relays) {
                if (session.relays.size() == MAX_ZOMBIES) {
                    break;
                }
                if (relevant(connection, zombie)) {
                    session.relays.add(zombie);
                }
            }
            if (!workers.reserve(session.lane)) {
                OptimizationTelemetry.count(OptimizationTelemetry.Counter.ZOMBIE_SEND_BACKPRESSURE);
                continue;
            }
            long started = System.nanoTime();
            try {
                if (capture(packer, session, extra, reliableLimiter)) {
                    nextStart = index + 1;
                } else {
                    workers.release(session.lane);
                }
            } catch (RuntimeException failure) {
                restore(packer, session);
                workers.release(session.lane);
                System.err.println("[ApocBR-Agent][ZombieSend] Snapshot failed: " + failure);
            } finally {
                OptimizationTelemetry.Phase.ZOMBIE_SEND_CAPTURE.recordSince(started);
            }
        }
    }

    private Session addSession(UdpConnection connection) {
        if (sessions.size() >= MAX_SESSIONS) {
            return null;
        }
        var peer = connection.getPeer();
        long guid = connection.getConnectedGUID();
        var lane = new ZombieSendWorkers.Lane((data, priority, reliability, ordering) ->
            peer.SendRaw(data, priority, reliability, ordering, guid, false));
        transports.put(guid, lane);
        if (connections.get(guid) != connection) {
            transports.remove(guid, lane);
            lane.close();
            return null;
        }
        Session session = new Session(connection, lane);
        sessions.put(connection, session);
        byLane.put(lane, session);
        return session;
    }

    private void drain(NetworkZombiePacker packer) {
        ZombieSendWorkers.Completion result;
        while ((result = workers.poll()) != null) {
            try {
                Session session = byLane.get(result.lane());
                if (session == null) {
                    continue;
                }
                for (int i = 0; i < result.attempted(); i++) {
                    var message = result.messages().get(i);
                    NetworkStatistic.getInstance().addOutcomePacket(message.id(), message.bytes().length, session.connection);
                }
                if (!result.success()) {
                    restore(packer, session);
                }
                session.inFlightDeletes = List.of();
                session.inFlightRequests = List.of();
            } finally {
                workers.release(result.lane());
            }
        }
    }

    private void restore(NetworkZombiePacker packer, Session session) {
        session.deletions.addAll(session.inFlightDeletes);
        var requests = packer.zombiesRequest.getNetworkZombie(session.connection).zombies;
        synchronized (requests) {
            List<Request> inFlight = session.inFlightRequests;
            for (int i = inFlight.size() - 1; i >= 0; i--) {
                Request request = inFlight.get(i);
                if (request.current() && !requests.contains(request.zombie)) {
                    requests.addFirst(request.zombie);
                }
            }
        }
        session.inFlightDeletes = List.of();
        session.inFlightRequests = List.of();
        session.forceAuth = true;
        session.forceSync = true;
    }

    private boolean capture(NetworkZombiePacker packer, Session session,
            Set<zombie.network.IConnection> extra, UpdateLimit reliableLimiter) {
        UdpConnection connection = session.connection;
        List<ZombieSendWorkers.Message> messages = new ArrayList<>();
        List<Short> deletes = List.copyOf(session.deletions);
        for (int start = 0; start < deletes.size(); start += MAX_ZOMBIES) {
            List<Short> group = deletes.subList(start, Math.min(deletes.size(), start + MAX_ZOMBIES));
            begin(PacketType.ZombieDeleteOnClient);
            writer.putShort((short) group.size());
            for (short id : group) {
                writer.putShort(id);
            }
            messages.add(finish(PacketType.ZombieDeleteOnClient));
        }

        ZombieListPacket auth = (ZombieListPacket) connection.getPacket(PacketType.ZombieList);
        int hash = NetworkZombieManager.getInstance().getZombieAuth(connection, auth);
        boolean hashChanged = session.forceAuth || connection.getZombieListHash() != hash;
        boolean sendAuth = hashChanged || !auth.zombiesAuth.isEmpty() && connection.zombieListRefresh.Check();
        if (sendAuth) {
            begin(PacketType.ZombieList);
            auth.write(writer);
            messages.add(finish(PacketType.ZombieList));
        }

        var pending = packer.zombiesRequest.getNetworkZombie(connection).zombies;
        LinkedHashSet<IsoZombie> selected = new LinkedHashSet<>();
        List<Request> retry = new ArrayList<>();
        session.inFlightRequests = retry;
        synchronized (pending) {
            while (retry.size() < MAX_ZOMBIES && !pending.isEmpty()) {
                IsoZombie zombie = pending.poll();
                retry.add(new Request(zombie, zombie.onlineId));
                if (zombie.onlineId != -1) {
                    selected.add(zombie);
                }
            }
            for (IsoZombie relay : session.relays) {
                if (selected.size() == MAX_ZOMBIES) {
                    break;
                }
                if (relevant(connection, relay) && selected.add(relay)) {
                    retry.add(new Request(relay, relay.onlineId));
                }
            }
            if (hashChanged) {
                Set<IsoZombie> deferred = Collections.newSetFromMap(new IdentityHashMap<>());
                deferred.addAll(pending);
                for (short id : auth.zombiesAuth) {
                    IsoZombie zombie = ServerMap.instance.zombieMap.get(id);
                    if (zombie != null && zombie.onlineId == id && !selected.contains(zombie)) {
                        if (selected.size() == MAX_ZOMBIES) {
                            if (deferred.add(zombie)) pending.add(zombie);
                        } else {
                            selected.add(zombie);
                            retry.add(new Request(zombie, id));
                        }
                    }
                }
            }
        }

        List<Short> sent = new ArrayList<>(selected.size());
        capture.clear();
        capture.position(3);
        writer.putBoolean(connection.isNeighborPlayer());
        int countPosition = capture.position();
        writer.putShort((short) 0);
        for (IsoZombie zombie : selected) {
            if (zombie.onlineId != -1 && ServerMap.instance.zombieMap.get(zombie.onlineId) == zombie) {
                zombie.zombiePacket.set(zombie);
                zombie.zombiePacket.write(writer);
                sent.add(zombie.onlineId);
            }
        }
        boolean sendSync = !sent.isEmpty() || connection.timerSendZombie.check() || session.forceSync || extra.contains(connection);
        if (sendSync) {
            PacketType type = reliableLimiter.Check() ? PacketType.ZombieSynchronizationReliable
                : PacketType.ZombieSynchronizationUnreliable;
            capture.putShort(countPosition, (short) sent.size());
            int end = capture.position();
            capture.position(0);
            type.doPacket(writer);
            capture.position(end);
            messages.add(finish(type));
        }
        if (messages.isEmpty()) {
            session.inFlightRequests = List.of();
            return false;
        }

        session.inFlightDeletes = deletes;
        workers.submit(session.lane, messages);
        session.deletions.clear();
        session.relays.removeAll(selected);
        session.forceAuth = false;
        if (sendAuth) {
            connection.setZombieListHash(hash);
            connection.zombieListRefresh.Reset();
        }
        if (sendSync) {
            extra.remove(connection);
            session.forceSync = false;
            connection.timerSendZombie.reset(3800L);
        }
        packer.zombiesToSend.put(connection, sent);
        OptimizationTelemetry.count(OptimizationTelemetry.Counter.ZOMBIE_SEND_SUBMITTED);
        OptimizationTelemetry.count(OptimizationTelemetry.Counter.ZOMBIE_SEND_BYTES,
            messages.stream().mapToInt(message -> message.bytes().length).sum());
        return true;
    }

    private void begin(PacketType type) {
        capture.clear();
        type.doPacket(writer);
    }

    private ZombieSendWorkers.Message finish(PacketType type) {
        return new ZombieSendWorkers.Message(type.getId(), Arrays.copyOf(capture.array(), capture.position()),
            type.packetPriority, type.packetReliability, type.orderingChannel);
    }

    private static boolean relevant(UdpConnection connection, IsoZombie zombie) {
        return zombie.onlineId != -1 && ServerMap.instance.zombieMap.get(zombie.onlineId) == zombie
            && zombie.getOwner() != null && zombie.getOwner() != connection
            && connection.RelevantTo(zombie.getX(), zombie.getY(), (connection.getRelevantRange() - 2) * 10);
    }

    synchronized void shutdown() {
        if (!closed) {
            closed = true;
            transports.values().forEach(ZombieSendWorkers.Lane::close);
            workers.shutdown();
            transports.clear();
            connections.clear();
            byLane.clear();
            sessions.clear();
            while (workers.poll() != null) {
                // Discard detached completions after the peer has stopped accepting work.
            }
        }
    }

    private record Request(IsoZombie zombie, short id) {
        boolean current() {
            return id != -1 && zombie.onlineId == id && ServerMap.instance.zombieMap.get(id) == zombie;
        }
    }

    private static final class Session {
        final UdpConnection connection;
        final ZombieSendWorkers.Lane lane;
        final LinkedHashSet<Short> deletions = new LinkedHashSet<>();
        final LinkedHashSet<IsoZombie> relays = new LinkedHashSet<>();
        List<Request> inFlightRequests = List.of();
        List<Short> inFlightDeletes = List.of();
        boolean forceAuth;
        boolean forceSync;

        Session(UdpConnection connection, ZombieSendWorkers.Lane lane) {
            this.connection = connection;
            this.lane = lane;
        }
    }
}
