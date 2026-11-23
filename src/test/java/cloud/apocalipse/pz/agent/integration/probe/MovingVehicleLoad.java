package cloud.apocalipse.pz.agent.integration.probe;

import java.nio.ByteBuffer;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoGridSquare;
import zombie.network.PacketTypes.PacketType;
import zombie.network.packets.vehicle.VehiclePhysicsUnreliablePacket;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.TransmissionNumber;

/** Feeds a valid vehicle physics packet through the original server packet dispatcher. */
final class MovingVehicleLoad {
    private final BaseVehicle vehicle;
    private final IsoGridSquare square;
    private final UdpConnection sender = new UdpConnection(null, 0, 0);
    private final DrivePacket input = new DrivePacket();
    private final ByteBuffer bytes = ByteBuffer.allocate(256);
    private final ByteBufferWriter writer = new ByteBufferWriter(bytes);
    private final ByteBufferReader reader = new ByteBufferReader(bytes);
    private long timestamp;
    private float requestedX;
    private float previousX;

    MovingVehicleLoad(IsoGridSquare square, IsoPlayer player) {
        this.square = square;
        vehicle = new BaseVehicle(square.getCell());
        sender.setPlayerAt(0, player);
        sender.setRole(player.getRole());
    }

    void start() {
        vehicle.setScriptName("Base.CarNormal");
        vehicle.setX(square.getX() + 0.5f);
        vehicle.setY(square.getY() + 0.5f);
        vehicle.setZ(square.getZ());
        vehicle.setSquare(square);
        vehicle.setCurrent(square);
        vehicle.chunk = square.getChunk();
        vehicle.chunk.vehicles.add(vehicle);
        vehicle.setPreviouslyEntered(true);
        vehicle.addToWorld();
        square.getCell().addMovingObject(vehicle);
        vehicle.setNetPlayerAuthorization(BaseVehicle.Authorization.Local, sender.players[0].getOnlineID());
    }

    long drive(int frame) throws Exception {
        previousX = vehicle.getX();
        requestedX = square.getX() + 0.2f + 0.6f * Math.abs(frame % 20 - 10) / 10f;
        timestamp = Math.max(timestamp + 1, GameTime.getServerTimeMills());
        input.fill(vehicle, timestamp, requestedX, (requestedX - previousX) * 10);
        bytes.clear();
        input.write(writer);
        bytes.flip();
        long started = System.nanoTime();
        PacketType.VehiclePhysicsUnreliable.onServerPacket(reader, sender);
        long elapsed = System.nanoTime() - started;
        if (vehicle.networkUpdateTime != timestamp) {
            throw new IllegalStateException("The original server rejected the fixture's vehicle physics packet");
        }
        return elapsed;
    }

    boolean moved() { return vehicle.getX() == requestedX && vehicle.getX() != previousX; }

    boolean matches(Object candidate) { return candidate == vehicle; }

    void cleanup() {
        if (vehicle.chunk != null) vehicle.permanentlyRemove();
    }

    private static final class DrivePacket extends VehiclePhysicsUnreliablePacket {
        void fill(BaseVehicle vehicle, long timestamp, float x, float velocity) {
            vehicleId.set(vehicle);
            time = timestamp;
            this.x = x;
            y = vehicle.getY();
            z = vehicle.getZ();
            qx = vehicle.savedRot.x;
            qy = vehicle.savedRot.y;
            qz = vehicle.savedRot.z;
            qw = vehicle.savedRot.w;
            vx = velocity;
            engineSpeed = 1200;
            throttle = 0.2f;
            transmissionNumber = TransmissionNumber.Speed1;
            vehicle.setSpeedKmHour(Math.abs(velocity) * 3.6f);
        }
    }
}

