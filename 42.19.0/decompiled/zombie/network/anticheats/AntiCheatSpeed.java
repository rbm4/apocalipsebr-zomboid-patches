// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.anticheats;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.NetworkCharacterAI;
import zombie.core.raknet.UdpConnection;
import zombie.network.ServerOptions;
import zombie.network.fields.IMovable;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.character.PlayerPacket;
import zombie.network.packets.vehicle.VehiclePhysicsPacket;
import zombie.vehicles.BaseVehicle;

public class AntiCheatSpeed extends AbstractAntiCheat {
    private static final int MAX_SPEED = 10;

    @Override
    public String validate(UdpConnection connection, INetworkPacket packet) {
        String result = super.validate(connection, packet);
        AntiCheatSpeed.IAntiCheat field = (AntiCheatSpeed.IAntiCheat)packet;
        int movableCount = field.getMovableCount();

        for (int i = 0; i < movableCount; i++) {
            IMovable movable = field.getMovable(i);
            if (movable != null) {
                if (packet instanceof PlayerPacket playerPacket) {
                    if (!playerPacket.getPlayer().isDead()) {
                        ((NetworkCharacterAI.SpeedChecker)movable)
                            .set(playerPacket.prediction.position.x, playerPacket.prediction.position.y, playerPacket.getPlayer().isSeatedInVehicle());
                    }
                } else if (packet instanceof VehiclePhysicsPacket vehiclePacket) {
                    BaseVehicle.Passenger passenger = vehiclePacket.getVehicle().getPassenger(i);
                    if (passenger != null && passenger.character instanceof IsoPlayer player && !player.isDead()) {
                        ((NetworkCharacterAI.SpeedChecker)movable).set(vehiclePacket.getX(), vehiclePacket.getY(), true);
                    }
                }

                if (!connection.getRole().hasCapability(Capability.TeleportToPlayer)
                    && !connection.getRole().hasCapability(Capability.TeleportToCoordinates)
                    && !connection.getRole().hasCapability(Capability.TeleportPlayerToAnotherPlayer)
                    && !connection.getRole().hasCapability(Capability.UseFastMoveCheat)) {
                    float limit = movable.isVehicle() ? (float)ServerOptions.instance.speedLimit.getValue() : 10.0F;
                    if (movable.getSpeed() > limit) {
                        return String.format("speed=%f > limit=%f", movable.getSpeed(), limit);
                    }
                }
            }
        }

        return result;
    }

    public interface IAntiCheat {
        IMovable getMovable(int var1);

        int getMovableCount();
    }
}
