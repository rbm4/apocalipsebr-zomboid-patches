// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.UsedFromLua;
import zombie.characters.Capability;
import zombie.characters.CharacterStat;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.network.IConnection;
import zombie.network.ApocBRPacketOwnershipGuard;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.fields.character.OnlinePlayer;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.CanModifyBodyStats, handlingType = 3)
@UsedFromLua
public class SyncPlayerStatsPacket implements INetworkPacket {
    @JSONField
    private final OnlinePlayer player = new OnlinePlayer();
    @JSONField
    private int syncParams;

    public static int getBitMaskForStat(CharacterStat stat) {
        for (int i = 0; i < CharacterStat.ORDERED_STATS.length; i++) {
            if (CharacterStat.ORDERED_STATS[i] == stat) {
                return 1 << i;
            }
        }

        return 0;
    }

    @Override
    public void setData(Object... values) {
        if (values[0] instanceof IsoPlayer) {
            this.player.set((IsoPlayer)values[0]);
            this.syncParams = (Integer)values[1];
        } else {
            DebugType.Multiplayer.warn(this.getClass().getSimpleName() + ".set get invalid arguments");
            DebugType.Multiplayer.printStackTrace();
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        this.syncParams = b.getInt();
        if (!ApocBRPacketOwnershipGuard.validate(
            "SyncPlayerStats", connection, this.player.getId(), (byte)-1, this.player.getPlayer()
        )) {
            return;
        }

        IsoPlayer isoPlayer = this.player.getPlayer();
        if (isoPlayer == null) {
            DebugType.Multiplayer.error("player not found");
        } else {
            if (this.syncParams == -1) {
                isoPlayer.getNutrition().load(b.bb);
            } else {
                for (byte i = 0; i < CharacterStat.ORDERED_STATS.length; i++) {
                    if ((this.syncParams >> i & 1) != 0) {
                        isoPlayer.getStats().parse(b.bb, i);
                    }
                }
            }
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        b.putInt(this.syncParams);
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (isoPlayer == null) {
            DebugType.Multiplayer.error("player not found");
        } else {
            if (this.syncParams == -1) {
                isoPlayer.getNutrition().save(b.bb);
            } else {
                for (byte i = 0; i < CharacterStat.ORDERED_STATS.length; i++) {
                    if ((this.syncParams >> i & 1) != 0) {
                        isoPlayer.getStats().write(b.bb, i);
                    }
                }
            }
        }
    }
}
