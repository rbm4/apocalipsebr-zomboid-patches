package cloud.apocalipse.pz.agent.features.networkdiagnostics;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.random.RandStandard;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketTypes.PacketType;
import zombie.network.ZomboidNetData;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.packets.EquipPacket;
import zombie.network.packets.INetworkPacket;

/** Runs with the shaded agent and original game JAR in a disposable JVM without a server or socket. */
public final class NetworkDiagnosticsOriginalMain {
    private static final Error PARSE_FAILURE = new AssertionError("fixture parse failure");

    public static void main(String[] args) throws Exception {
        Path original = Path.of(args[0]).toRealPath();
        boolean enabled = Boolean.parseBoolean(args[1]);
        for (Class<?> type : new Class<?>[]{GameServer.class, ConnectionPlayer.class, INetworkPacket.class, EquipPacket.class}) {
            check(original.equals(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()),
                "Expected original game class: " + type);
        }

        RandStandard.INSTANCE.init();
        DebugType.Multiplayer.setLogSeverity(LogSeverity.General);
        GameServer.server = true;
        var equip = new RecordingEquip();
        var connection = new UdpConnection(null, 27L, 0) {
            @Override public INetworkPacket getPacket(PacketType type) { return equip; }
            @Override public boolean isLimitExceeded(PacketType type) { return false; }
            @Override public String getUserName() { return "network-diagnostics-fixture"; }
        };
        connection.isCoopHost = true;
        connection.setIDStr("fixture");
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var engine = (UdpEngine) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(UdpEngine.class);
        var connectionMap = UdpEngine.class.getDeclaredField("connectionMap");
        connectionMap.setAccessible(true);
        connectionMap.set(engine, Map.of(connection.getConnectedGUID(), connection));
        var udpEngine = GameServer.class.getDeclaredField("udpEngine");
        udpEngine.setAccessible(true);
        udpEngine.set(null, engine);
        Method dispatch = GameServer.class.getDeclaredMethod("mainLoopDealWithNetData", ZomboidNetData.class);
        dispatch.setAccessible(true);
        var output = new ByteArrayOutputStream();
        DebugLog log = DebugLog.getInstance();
        log.setStdOut(output);
        log.setStdErr(output);
        try {
            dispatch.invoke(null, equipment(connection));
            dispatch.invoke(null, equipment(connection));
            check(equip.syncs == 2, "Original inconsistent packet path did not call sync for every packet");
            check(equip.descriptions == (enabled ? 1 : 2), "Repeated warnings formatted the wrong number of details");

            equip.fail = true;
            try {
                dispatch.invoke(null, equipment(connection));
                throw new AssertionError("Vanilla packet failure was swallowed");
            } catch (InvocationTargetException failure) {
                check(failure.getCause() == PARSE_FAILURE, "Vanilla packet failure changed");
            }
            // This call follows the failed dispatch on the same thread and must have no packet context.
            var field = new ConnectionPlayer();
            field.parse(slot((short) 16), connection);
            check(field.getId() == 16 && field.getPlayer() == null, "Invalid index handling changed");
            field.parse(slot((short) 0), connection);
            check(field.getId() == -1 && field.getPlayer() == null, "Unoccupied valid index handling changed");

            var brokenContext = new UdpConnection(null, 28L, 1) {
                @Override public long getConnectedGUID() { throw new IllegalStateException("fixture diagnostics failure"); }
            };
            field.parse(slot((short) 16), brokenContext);
            equip.logInconsistentPacket(brokenContext, PacketType.Equip);
            check(equip.descriptions == (enabled ? 2 : 3), "Diagnostics failure prevented vanilla warning");
            check(field.getId() == 16, "Diagnostics failure interrupted vanilla parsing");

            GameServer.server = false;
            field.parse(slot((short) 16), connection);
            equip.logInconsistentPacket(connection, PacketType.Equip);
            check(equip.descriptions == (enabled ? 3 : 4), "Non-server warning did not run vanilla formatting");
        } finally {
            udpEngine.set(null, null);
            GameServer.server = false;
            log.setStdOut(System.out);
            log.setStdErr(System.err);
        }
        String logs = output.toString(StandardCharsets.UTF_8);
        System.out.print(logs);
        check(count(logs, "packet=Equip event=invalid-player-slot") == (enabled ? 1 : 0),
            "Wrong number of aggregated Equip errors");
        check(count(logs, "packet=Equip event=inconsistent-packet") == (enabled ? 1 : 0),
            "Wrong number of aggregated Equip warnings");
        check(count(logs, "packet=unknown event=invalid-player-slot") == (enabled ? 1 : 0),
            "Receive context leaked after dispatch threw");
        check(count(logs, "wrong player connection index (16)") == (enabled ? 4 : 5),
            "Wrong number of total invalid index errors");
        check(count(logs, "at ConnectionPlayer.parse") == (enabled ? 2 : 5),
            "Vanilla invalid-index logs lost their caller attribution");
        check(count(logs, "The packet Equip is not consistent") == (enabled ? 2 : 4),
            "Wrong number of vanilla inconsistent-packet warnings");
        System.out.println("ORIGINAL_NETWORK_DIAGNOSTICS_PASS enabled=" + enabled);
    }

    private static ZomboidNetData equipment(UdpConnection connection) {
        var data = new ZomboidNetData();
        data.connection = connection.getConnectedGUID();
        data.type = PacketType.Equip;
        data.buffer.bb.putShort((short) 16).putInt(1461111964).putInt(1461111964).flip();
        return data;
    }

    private static ByteBufferReader slot(short value) {
        return new ByteBufferReader(ByteBuffer.allocate(2).putShort(value).flip());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static long count(String logs, String text) {
        return logs.lines().filter(line -> line.contains(text)).count();
    }

    private static final class RecordingEquip extends EquipPacket {
        int syncs;
        int descriptions;
        boolean fail;

        @Override public void parse(ByteBufferReader buffer, IConnection connection) {
            if (fail) throw PARSE_FAILURE;
            super.parse(buffer, connection);
        }

        @Override public String getDescription() {
            descriptions++;
            return super.getDescription();
        }

        @Override public void sync(PacketType type, IConnection connection) { syncs++; }

        @Override public void processServer(PacketType type, UdpConnection connection) {
            throw new AssertionError("Inconsistent equipment reached game state processing");
        }
    }
}
