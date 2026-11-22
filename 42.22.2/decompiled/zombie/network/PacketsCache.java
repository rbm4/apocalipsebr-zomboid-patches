// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import zombie.core.Core;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.packets.INetworkPacket;

public abstract class PacketsCache {
    private final HashMap<PacketTypes.PacketType, INetworkPacket> packets = new HashMap<>();
    private final HashMap<PacketTypes.PacketType, Integer> hashes = new HashMap<>();
    private final HashMap<PacketTypes.PacketType, List<Long>> limits = new HashMap<>();

    protected PacketsCache() {
        StringBuilder packetTypesWithoutValidation = new StringBuilder();
        StringBuilder packetTypesWithoutHandler = new StringBuilder();

        for (PacketTypes.PacketType packetType : PacketTypes.PacketType.values()) {
            if (packetType.isServerTrustedPacket()) {
                if (!packetTypesWithoutValidation.isEmpty()) {
                    packetTypesWithoutValidation.append(", ");
                }

                packetTypesWithoutValidation.append(packetType.name());
            }

            if (packetType.handler == null) {
                if (!packetTypesWithoutHandler.isEmpty()) {
                    packetTypesWithoutHandler.append(", ");
                }

                packetTypesWithoutHandler.append(packetType.name());
            } else {
                try {
                    INetworkPacket packetForReuse = packetType.handler.getDeclaredConstructor().newInstance();
                    INetworkPacket previousPacket = this.packets.put(packetType, packetForReuse);
                    if (previousPacket != null) {
                        DebugType.Multiplayer
                            .error(
                                "Packet cache type duplicate: previous=\"%s\" new=\"%s\" id=\"%s\"",
                                previousPacket.getClass().getSimpleName(),
                                packetForReuse.getClass().getSimpleName(),
                                packetType.name()
                            );
                    }
                } catch (Exception var9) {
                    DebugType.Multiplayer.printException(var9, LogSeverity.Warning, "Error creating packet type: \"%s\"", packetType.name());
                }
            }
        }

        if (Core.IS_DEV) {
            if (!packetTypesWithoutHandler.isEmpty()) {
                packetTypesWithoutHandler.insert(0, "No packet handler for type: ");
                DebugType.Multiplayer.warn(packetTypesWithoutHandler.toString());
            }

            if (!packetTypesWithoutValidation.isEmpty()) {
                packetTypesWithoutValidation.insert(0, "No packet validation for type: ");
                DebugType.Multiplayer.warn(packetTypesWithoutValidation.toString());
            }
        }
    }

    public INetworkPacket getPacket(PacketTypes.PacketType packetType) {
        return this.packets.get(packetType);
    }

    public boolean isHashEquals(PacketTypes.PacketType packetType, Integer hash) {
        return hash.equals(this.hashes.put(packetType, hash));
    }

    public boolean isLimitExceeded(PacketTypes.PacketType packetType) {
        long currentTime = System.currentTimeMillis();

        for (List<Long> limit : this.limits.values()) {
            limit.removeIf(timestamp -> currentTime > timestamp + 1000L);
            limit.removeIf(timestamp -> currentTime < timestamp);
        }

        if (!this.limits.containsKey(packetType)) {
            this.limits.put(packetType, new LinkedList<>());
        }

        List<Long> limit = this.limits.get(packetType);
        if (GameClient.client) {
            limit.add(currentTime);
        }

        if (limit.size() <= ServerOptions.getInstance().maxPacketsPerSecond.getValue()) {
            if (GameServer.server) {
                limit.add(currentTime);
            }

            return false;
        } else {
            DebugType.Multiplayer.warn("Packets limit has exceeded for %s", packetType.name());
            if (Core.debug) {
                StringBuilder stringBuilder = new StringBuilder();

                for (Entry<PacketTypes.PacketType, List<Long>> temp : this.limits.entrySet()) {
                    if (!temp.getValue().isEmpty()) {
                        stringBuilder.append(temp.getKey()).append("=").append(temp.getValue().size()).append(", ");
                    }
                }

                DebugType.Multiplayer.debugln(stringBuilder.toString());
            }

            return true;
        }
    }
}
