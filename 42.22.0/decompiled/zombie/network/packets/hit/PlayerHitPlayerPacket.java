// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import java.util.List;
import zombie.CombatManager;
import zombie.characters.Capability;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.anticheats.AntiCheat;
import zombie.network.anticheats.AntiCheatHitLongDistance;
import zombie.network.anticheats.AntiCheatHitWeapon;
import zombie.network.anticheats.AntiCheatSafety;
import zombie.network.fields.hit.Fall;
import zombie.network.fields.hit.Player;
import zombie.network.fields.hit.TracerInfo;

@PacketSetting(
    ordering = 0,
    priority = 0,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 0,
    anticheats = {AntiCheat.HitLongDistance, AntiCheat.HitWeapon, AntiCheat.Safety}
)
public class PlayerHitPlayerPacket extends PlayerHit implements AntiCheatHitLongDistance.IAntiCheat, AntiCheatHitWeapon.IAntiCheat, AntiCheatSafety.IAntiCheat {
    @JSONField
    public final Player target = new Player();
    @JSONField
    protected final Fall fall = new Fall();

    public PlayerHitPlayerPacket() {
    }

    public PlayerHitPlayerPacket(IsoPlayer wielder, HandWeapon weapon, CombatManager.TargetHitList hitList, List<TracerInfo> tracers) {
        this.set(wielder, weapon, hitList, tracers);
    }

    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0], (HandWeapon)values[1], (CombatManager.TargetHitList)values[2], (List<TracerInfo>)values[3]);
    }

    @Override
    public void set(IsoPlayer wielder, HandWeapon weapon, CombatManager.TargetHitList hitList, List<TracerInfo> tracers) {
        this.set(wielder, weapon, tracers, hitList.hits());
        this.target.set((IsoPlayer)hitList.target());
        this.fall.set(((IsoPlayer)hitList.target()).getHitReactionNetworkAI());
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.target.parse(b, connection);
        this.fall.parse(b, connection);
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        this.target.write(b);
        this.fall.write(b);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return super.isConsistent(connection) && this.target.isConsistent(connection) && this.target.getPlayer().isAlive();
    }

    @Override
    public void update() {
        super.update();
        this.fall
            .set(
                this.target.getCharacter().realx,
                this.target.getCharacter().realy,
                (byte)PZMath.fastfloor(this.target.getCharacter().realz),
                this.fall.dropDirection
            );
    }

    @Override
    protected void processTarget() {
        this.target.process();
    }

    @Override
    public void process() {
        IsoGameCharacter targetCharacter = this.target.getCharacter();
        this.processHits(targetCharacter);
        this.processFall(this.fall, targetCharacter);
    }

    @Override
    public void attack() {
        this.attack(this.getHandWeapon(), true);
        this.processTracers();
    }

    @Override
    protected void reactTarget() {
        this.target.react();
    }

    @Override
    public float getDistance() {
        return IsoUtils.DistanceTo(this.target.getX(), this.target.getY(), this.getX(), this.getY());
    }

    @Override
    public IsoObject getTarget() {
        return this.target.getPlayer();
    }

    @Override
    public boolean isMelee() {
        return this.getHandWeapon().isMelee();
    }
}
