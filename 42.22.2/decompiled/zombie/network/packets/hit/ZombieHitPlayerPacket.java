// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PVPLogTool;
import zombie.network.PacketSetting;
import zombie.network.anticheats.AntiCheat;
import zombie.network.anticheats.AntiCheatHitShortDistance;
import zombie.network.anticheats.AntiCheatTarget;
import zombie.network.fields.hit.Bite;
import zombie.network.fields.hit.Player;
import zombie.network.fields.hit.Zombie;

@PacketSetting(
    ordering = 0,
    priority = 0,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 3,
    anticheats = {AntiCheat.HitShortDistance, AntiCheat.Target}
)
public class ZombieHitPlayerPacket extends Zombie implements HitCharacter, AntiCheatHitShortDistance.IAntiCheat, AntiCheatTarget.IAntiCheat {
    public final Player target = new Player();
    protected final Bite bite = new Bite();

    public void set(IsoZombie wielder, IsoPlayer target, boolean didDamage, String hitReaction, int bodyPart) {
        this.set(wielder);
        this.target.set(target);
        this.bite.set(wielder, didDamage, hitReaction, bodyPart);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.target.parse(b, connection);
        this.bite.parse(b, connection);
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        this.target.write(b);
        this.bite.write(b);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        if (!super.isConsistent(connection) || !this.target.isConsistent(connection)) {
            return false;
        } else {
            return GameServer.server ? this.getZombie().getOwner() == connection : true;
        }
    }

    @Override
    public void preProcess() {
        super.process();
        this.target.process();
    }

    @Override
    public void process() {
        this.bite.process(this.getZombie(), this.target.getCharacter());
    }

    @Override
    public void postProcess() {
        super.process();
        this.target.process();
    }

    @Override
    public void log(UdpConnection connection) {
        PVPLogTool.logCombat(this.getCharacter().getOwnerPlayer(), this.target.getPlayer(), "zombie", -1.0F);
    }

    @Override
    public IsoObject getTarget() {
        return this.target.getPlayer();
    }

    @Override
    public void react() {
        super.react();
        this.target.react();
    }

    @Override
    public float getFurthestHitDistance() {
        return IsoUtils.DistanceTo(this.target.getX(), this.target.getY(), this.getX(), this.getY());
    }

    @Override
    public short getPlayerId() {
        return this.target.getPlayer().getOnlineID();
    }
}
