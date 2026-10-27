// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.vehicle;

import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.physics.Bullet;
import zombie.core.physics.WorldSimulation;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketTypes;
import zombie.network.anticheats.AntiCheatSpeed;
import zombie.network.fields.IMovable;
import zombie.network.fields.vehicle.VehicleID;
import zombie.network.packets.INetworkPacket;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.TransmissionNumber;
import zombie.vehicles.VehicleInterpolationData;
import zombie.vehicles.VehicleManager;

public class VehiclePhysicsPacket extends VehicleInterpolationData implements INetworkPacket, AntiCheatSpeed.IAntiCheat {
    private static final long TIME_STAMP_MAX_DIVERGENCE_MS = 1000L;
    private static final float[] buffer = new float[27];
    @JSONField
    protected final VehicleID vehicleId = new VehicleID();
    @JSONField
    protected float force;

    @Override
    public void set(BaseVehicle vehicle) {
        if (Bullet.getOwnVehiclePhysics(vehicle.vehicleId, buffer) == 0) {
            this.vehicleId.set(vehicle);
            this.time = WorldSimulation.instance.time;
            this.force = vehicle.getForce();
            int i = 0;
            this.x = buffer[i++];
            this.y = buffer[i++];
            this.z = buffer[i++];
            this.qx = buffer[i++];
            this.qy = buffer[i++];
            this.qz = buffer[i++];
            this.qw = buffer[i++];
            this.vx = buffer[i++];
            this.vy = buffer[i++];
            this.vz = buffer[i++];
            this.engineSpeed = (float)vehicle.getEngineSpeed();
            this.throttle = vehicle.throttle;
            this.transmissionNumber = vehicle.getTransmissionNumberEnum();
            this.setNumWheels((short)buffer[i++]);

            for (int w = 0; w < this.wheelsCount; w++) {
                this.wheelSteering[w] = buffer[i++];
                this.wheelRotation[w] = buffer[i++];
                this.wheelSkidInfo[w] = buffer[i++];
                this.wheelSuspensionLength[w] = buffer[i++];
            }
        }
    }

    @Override
    public void setData(Object... values) {
        this.set((BaseVehicle)values[0]);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.vehicleId.parse(b, connection);
        this.time = b.getLong();
        this.force = b.getFloat();
        this.x = b.getFloat();
        this.y = b.getFloat();
        this.z = b.getFloat();
        this.qx = b.getFloat();
        this.qy = b.getFloat();
        this.qz = b.getFloat();
        this.qw = b.getFloat();
        this.vx = b.getFloat();
        this.vy = b.getFloat();
        this.vz = b.getFloat();
        this.engineSpeed = b.getFloat();
        this.throttle = b.getFloat();
        this.transmissionNumber = b.getEnum(TransmissionNumber.class);
        this.setNumWheels(b.getShort());

        for (int i = 0; i < this.wheelsCount; i++) {
            this.wheelSteering[i] = b.getFloat();
            this.wheelRotation[i] = b.getFloat();
            this.wheelSkidInfo[i] = b.getFloat();
            this.wheelSuspensionLength[i] = b.getFloat();
        }

        if (GameServer.server && this.vehicleId.isConsistent(connection)) {
            this.vehicleId.getVehicle().setSpeedKmHour(b.getFloat());
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.vehicleId.write(b);
        b.putLong(this.time);
        b.putFloat(this.force);
        b.putFloat(this.x);
        b.putFloat(this.y);
        b.putFloat(this.z);
        b.putFloat(this.qx);
        b.putFloat(this.qy);
        b.putFloat(this.qz);
        b.putFloat(this.qw);
        b.putFloat(this.vx);
        b.putFloat(this.vy);
        b.putFloat(this.vz);
        b.putFloat(this.engineSpeed);
        b.putFloat(this.throttle);
        b.putEnum(this.transmissionNumber);
        b.putShort(this.wheelsCount);

        for (int i = 0; i < this.wheelsCount; i++) {
            b.putFloat(this.wheelSteering[i]);
            b.putFloat(this.wheelRotation[i]);
            b.putFloat(this.wheelSkidInfo[i]);
            b.putFloat(this.wheelSuspensionLength[i]);
        }

        b.putFloat(this.vehicleId.getVehicle().getCurrentSpeedKmHour());
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.vehicleId.isConsistent(connection) || GameClient.client;
    }

    @Override
    public void processClient(UdpConnection connection) {
        if (this.vehicleId.isConsistent(connection)) {
            if (!this.vehicleId.getVehicle().hasAuthorization(connection)) {
                this.vehicleId.getVehicle().interpolation.interpolationDataAdd(this.vehicleId.getVehicle(), this, GameTime.getServerTimeMills());
            }
        } else {
            VehicleManager.instance.sendVehicleRequest(this.vehicleId.getID(), (short)1);
        }
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        BaseVehicle vehicle = this.vehicleId.getVehicle();
        if (vehicle != null
            && vehicle.hasAuthorization(connection)
            && vehicle.networkUpdateTime < this.time
            && Math.abs(this.time - GameTime.getServerTimeMills()) < 1000L) {
            vehicle.setClientForce(this.force);
            vehicle.setX(this.x);
            vehicle.setY(this.y);
            vehicle.setZ(this.z);
            vehicle.savedRot.x = this.qx;
            vehicle.savedRot.y = this.qy;
            vehicle.savedRot.z = this.qz;
            vehicle.savedRot.w = this.qw;
            vehicle.jniTransform
                .origin
                .set(vehicle.getX() - WorldSimulation.instance.offsetX, vehicle.getZ(), vehicle.getY() - WorldSimulation.instance.offsetY);
            vehicle.jniTransform.setRotation(vehicle.savedRot);
            vehicle.jniLinearVelocity.x = this.vx;
            vehicle.jniLinearVelocity.y = this.vy;
            vehicle.jniLinearVelocity.z = this.vz;
            vehicle.setEngineSpeed(this.engineSpeed);
            vehicle.throttle = this.throttle;
            vehicle.transmissionNumber = this.transmissionNumber;
            this.setNumWheels(this.wheelsCount);

            for (int i = 0; i < this.wheelsCount; i++) {
                vehicle.wheelInfo[i].steering = this.wheelSteering[i];
                vehicle.wheelInfo[i].rotation = this.wheelRotation[i];
                vehicle.wheelInfo[i].skidInfo = this.wheelSkidInfo[i];
                vehicle.wheelInfo[i].suspensionLength = this.wheelSuspensionLength[i];
            }

            vehicle.networkUpdateTime = this.time;
            this.sendToRelativeClients(packetType, connection, this.x, this.y);
        }
    }

    public BaseVehicle getVehicle() {
        return this.vehicleId.getVehicle();
    }

    public float getX() {
        return this.x;
    }

    public float getY() {
        return this.y;
    }

    @Override
    public IMovable getMovable(int index) {
        BaseVehicle.Passenger passenger = this.vehicleId.getVehicle().getPassenger(index);
        return passenger != null && passenger.character instanceof IsoPlayer player ? player.getNetworkCharacterAI().speedChecker : null;
    }

    @Override
    public int getMovableCount() {
        return this.vehicleId.getVehicle().getMaxPassengers();
    }

    @Override
    public void resetMovable() {
        for (int i = 0; i < this.getVehicle().getMaxPassengers(); i++) {
            BaseVehicle.Passenger passenger = this.getVehicle().getPassenger(i);
            if (passenger != null && passenger.character instanceof IsoPlayer player && !player.isDead()) {
                player.getNetworkCharacterAI().speedChecker.set(this.getX(), this.getY(), true, null);
            }
        }
    }
}
