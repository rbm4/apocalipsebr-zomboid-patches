// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import zombie.CombatManager;
import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponType;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.anticheats.AntiCheat;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.fields.hit.Player;
import zombie.network.fields.hit.TracerInfo;
import zombie.network.fields.hit.Weapon;
import zombie.network.packets.INetworkPacket;
import zombie.util.Type;
import zombie.vehicles.BaseVehicle;

@PacketSetting(ordering = 0, priority = 0, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class PlayerHitPacket implements INetworkPacket {
    private static final Map<String, Float> STATISTIC = new HashMap<>();
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();
    @JSONField
    private final Weapon weapon = new Weapon();
    @JSONField
    private final List<PlayerHitPacket.Packet> packets = new ArrayList<>();
    private IsoObject closestTarget;

    private void set(IsoPlayer wielder, HandWeapon weapon, List<CombatManager.TargetHitList> targetHitLists, List<TracerInfo> tracers) {
        this.player.set(wielder);
        this.weapon.set(weapon);
        this.packets.clear();

        for (CombatManager.TargetHitList hitList : targetHitLists) {
            PacketTypes.PacketType type = getPacketType(hitList.target());
            PlayerHit packet = getPacket(type);
            packet.set(wielder, weapon, hitList, tracers);
            this.packets.add(new PlayerHitPacket.Packet(type, packet));
        }
    }

    private static PacketTypes.PacketType getPacketType(IsoObject target) {
        return switch (target) {
            case null -> PacketTypes.PacketType.PlayerHitSquare;
            case IsoAnimal var3 -> PacketTypes.PacketType.PlayerHitAnimal;
            case IsoPlayer var4 -> PacketTypes.PacketType.PlayerHitPlayer;
            case IsoZombie var5 -> PacketTypes.PacketType.PlayerHitZombie;
            case BaseVehicle var6 -> PacketTypes.PacketType.PlayerHitVehicle;
            default -> PacketTypes.PacketType.PlayerHitObject;
        };
    }

    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0], (HandWeapon)values[1], (List<CombatManager.TargetHitList>)values[2], (List<TracerInfo>)values[3]);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        if (!this.player.isConsistent(connection)) {
            DebugType.Multiplayer.error("wielder is not found");
            return false;
        } else if (!this.weapon.isConsistent(connection)) {
            DebugType.Multiplayer.error("weapon is not found");
            return false;
        } else {
            return true;
        }
    }

    private boolean isValid(PacketTypes.PacketType type, PlayerHit packet, IConnection connection) {
        if (type.anticheats == null) {
            return true;
        } else {
            for (AntiCheat anticheat : type.anticheats) {
                if (AntiCheat.None != anticheat && anticheat.isEnabled() && !anticheat.isValid((UdpConnection)connection, packet)) {
                    return false;
                }
            }

            return true;
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        float distanceToClosestTarget = 0.0F;
        this.closestTarget = null;
        this.player.parse(b, connection);
        this.weapon.parse(b, connection, this.player.getPlayer());
        this.packets.clear();
        byte size = b.getByte();

        for (int i = 0; i < size; i++) {
            short id = (short)b.getInt();
            PacketTypes.PacketType type = PacketTypes.PacketType.getById(id);
            if (type == null) {
                DebugType.Multiplayer.warn("Received unknown packet id=%d", id);
                return;
            }

            PlayerHit packet = getPacket(type);
            if (packet == null) {
                DebugType.Multiplayer.warn("Received unexpected packet type=%s", type.name());
                return;
            }

            packet.parse(b, connection);
            if (!packet.isConsistent(connection)) {
                DebugType.Multiplayer.warn("The packet %s/%s is not consistent: %s", type.name(), this.getClass().getSimpleName(), packet.getDescription());
                if (GameServer.server) {
                    packet.sync(type, connection);
                }
            } else {
                if (GameServer.server) {
                    if (!this.isValid(type, packet, connection)) {
                        DebugType.Multiplayer.warn("The packet %s/%s is not valid: %s", type.name(), this.getClass().getSimpleName(), packet.getDescription());
                        packet.sync(type, connection);
                        continue;
                    }

                    if (this.weapon.getID() != packet.getHandWeapon().getID()) {
                        DebugType.Multiplayer
                            .warn(
                                "The packet %s/%s weapon is not valid: %s",
                                type.name(),
                                this.getClass().getSimpleName(),
                                this.weapon.getWeapon().getDisplayName()
                            );
                        continue;
                    }
                }

                this.packets.add(new PlayerHitPacket.Packet(type, packet));
                if (packet.getTarget() instanceof IsoGameCharacter target) {
                    float distance = packet.getDistance();
                    if (this.closestTarget == null || distance < distanceToClosestTarget) {
                        this.closestTarget = target;
                        distanceToClosestTarget = distance;
                    }
                }
            }
        }
    }

    private static PlayerHit getPacket(PacketTypes.PacketType type) {
        return (PlayerHit)(switch (type) {
            case PlayerHitSquare -> new PlayerHitSquarePacket();
            case PlayerHitObject -> new PlayerHitObjectPacket();
            case PlayerHitAnimal -> new PlayerHitAnimalPacket();
            case PlayerHitPlayer -> new PlayerHitPlayerPacket();
            case PlayerHitZombie -> new PlayerHitZombiePacket();
            case PlayerHitVehicle -> new PlayerHitVehiclePacket();
            default -> null;
        });
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        this.weapon.write(b);
        b.putByte(this.packets.size());

        for (PlayerHitPacket.Packet packet : this.packets) {
            b.putInt(packet.packetType().ordinal());
            packet.playerHitPacket().write(b);
        }
    }

    /** Keep rejected NPC claims out of both relay and common attack-state calculations. */
    private void apocbrFilterNpcHits(UdpConnection connection) {
        java.util.Set<IsoPlayer> targets = null;
        boolean removed = false;
        for (var iterator = this.packets.iterator(); iterator.hasNext();) {
            Packet packet = iterator.next();
            if (packet.playerHitPacket() instanceof PlayerHitPlayerPacket hit && hit.apocbrNpcTarget()) {
                if (targets == null) targets = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                if (!targets.add(hit.target.getPlayer()) || !hit.apocbrAccept(connection, this.player.getPlayer())) {
                    iterator.remove();
                    removed = true;
                }
            }
        }
        if (removed) {
            this.closestTarget = null;
            float distanceToClosestTarget = 0.0F;
            for (Packet packet : this.packets) {
                if (packet.playerHitPacket().getTarget() instanceof IsoGameCharacter target) {
                    float distance = packet.playerHitPacket().getDistance();
                    if (this.closestTarget == null || distance < distanceToClosestTarget) {
                        this.closestTarget = target;
                        distanceToClosestTarget = distance;
                    }
                }
            }
        }
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        this.apocbrFilterNpcHits(connection);
        IsoPlayer isoPlayer = this.player.getPlayer();
        STATISTIC.clear();
        if (!this.packets.isEmpty()) {
            this.packets.getFirst().playerHitPacket().processWielder();
            IsoGameCharacter closestHitTarget = Type.tryCastTo(this.closestTarget, IsoGameCharacter.class);
            boolean isAttackFromBehind = false;
            boolean isCloseKill = false;
            boolean isCriticalHit = false;
            if (closestHitTarget != null) {
                WeaponType weaponType = isoPlayer.isDoShove() ? WeaponType.UNARMED : WeaponType.getWeaponType(isoPlayer);
                float closestDist = IsoUtils.DistanceTo(closestHitTarget.getX(), closestHitTarget.getY(), isoPlayer.getX(), isoPlayer.getY());
                isAttackFromBehind = isoPlayer.isBehind(closestHitTarget);
                isCloseKill = CombatManager.getInstance().isCloseKill(isoPlayer, this.weapon.getWeapon(), closestHitTarget, closestDist);
                isoPlayer.setAttackFromBehind(isAttackFromBehind);
                isoPlayer.getAttackVars().closeKill = isCloseKill;
                isoPlayer.getAttackVars().aimAtFloor = isoPlayer.isAimAtFloor();
                isCriticalHit = CombatManager.isHitRolledByClient(closestHitTarget)
                    ? this.packets.getFirst().playerHitPacket().isCriticalHit()
                    : CombatManager.getInstance().isCriticalHit(isoPlayer, weaponType, closestHitTarget, closestDist);
            }

            isoPlayer.setCriticalHit(isCriticalHit);

            for (PlayerHitPacket.Packet packet : this.packets) {
                Player player = packet.playerHitPacket();
                player.set(isCriticalHit);
                player.setAttackFromBehind(isAttackFromBehind);
                player.getAttackVars().closeKill = isCloseKill;
            }
        }

        if (!this.packets.isEmpty()) {
            HandWeapon handWeapon = this.weapon.getWeapon();
            if (handWeapon != null && handWeapon.isAimedFirearm()) {
                LuaEventManager.triggerEvent("OnWeaponSwingHitPoint", isoPlayer, handWeapon);
            }
        }

        PlayerHit.resetAttackState();
        CombatManager.getInstance().clearDotList();

        for (PlayerHitPacket.Packet packet : this.packets) {
            packet.playerHitPacket().processServer(packet.packetType(), connection);
        }

        this.packets.removeIf(packet -> packet.playerHitPacket() instanceof PlayerHitPlayerPacket hit
            && !hit.apocbrRelayAllowed());
        this.sendToRelativeClients(packetType, connection, this.player.getX(), this.player.getY());
        if (!STATISTIC.isEmpty()) {
            INetworkPacket.send(
                connection,
                PacketTypes.PacketType.HitStatistic,
                STATISTIC.getOrDefault("Bullets Chance Hit", 0.0F),
                STATISTIC.getOrDefault("Bullets Chance Missed", 0.0F),
                STATISTIC.getOrDefault("Bullets Damage Ignored", 0.0F)
            );
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        PlayerHit.resetAttackState();
        CombatManager.getInstance().clearDotList();

        for (PlayerHitPacket.Packet packet : this.packets) {
            packet.playerHitPacket().processClient(connection);
        }
    }

    public static void incrementStatistic(String statisticName) {
        STATISTIC.put(statisticName, STATISTIC.getOrDefault(statisticName, 0.0F) + 1.0F);
    }

    private record Packet(PacketTypes.PacketType packetType, PlayerHit playerHitPacket) {
    }
}
