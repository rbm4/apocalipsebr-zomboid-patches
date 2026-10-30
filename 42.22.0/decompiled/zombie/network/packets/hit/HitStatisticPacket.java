// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import zombie.characters.Capability;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.packets.INetworkPacket;
import zombie.statistics.StatisticCategory;
import zombie.statistics.StatisticType;
import zombie.statistics.StatisticsManager;

@PacketSetting(ordering = 0, priority = 2, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 2)
public class HitStatisticPacket implements INetworkPacket {
    @JSONField
    private float bulletsChanceHit;
    @JSONField
    private float bulletsChanceMissed;
    @JSONField
    private float bulletsDamageIgnored;

    @Override
    public void setData(Object... values) {
        this.bulletsChanceHit = (Float)values[0];
        this.bulletsChanceMissed = (Float)values[1];
        this.bulletsDamageIgnored = (Float)values[2];
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.bulletsChanceHit = b.getFloat();
        this.bulletsChanceMissed = b.getFloat();
        this.bulletsDamageIgnored = b.getFloat();
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putFloat(this.bulletsChanceHit);
        b.putFloat(this.bulletsChanceMissed);
        b.putFloat(this.bulletsDamageIgnored);
    }

    @Override
    public void processClient(UdpConnection connection) {
        incrementStatistic("Bullets Chance Hit", this.bulletsChanceHit);
        incrementStatistic("Bullets Chance Missed", this.bulletsChanceMissed);
        incrementStatistic("Bullets Damage Ignored", this.bulletsDamageIgnored);
    }

    private static void incrementStatistic(String statisticName, float amount) {
        if (amount > 0.0F) {
            StatisticsManager.getInstance().incrementStatistic(StatisticType.Player, StatisticCategory.Combat, statisticName, amount);
        }
    }
}
