// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import java.util.List;
import zombie.characters.Capability;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.logger.LoggerManager;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoUtils;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PVPLogTool;
import zombie.network.PacketSetting;
import zombie.network.anticheats.AntiCheat;
import zombie.network.anticheats.AntiCheatHitDamage;
import zombie.network.anticheats.AntiCheatHitLongDistance;
import zombie.network.anticheats.AntiCheatHitWeapon;
import zombie.network.anticheats.AntiCheatSafety;
import zombie.network.fields.hit.Fall;
import zombie.network.fields.hit.Player;
import zombie.network.fields.hit.TracerInfo;
import zombie.network.fields.hit.WeaponHit;

@PacketSetting(
    ordering = 0,
    priority = 0,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 3,
    anticheats = {AntiCheat.HitDamage, AntiCheat.HitLongDistance, AntiCheat.HitWeapon, AntiCheat.Safety}
)
public class PlayerHitPlayerPacket
    extends PlayerHitCharacter
    implements AntiCheatHitDamage.IAntiCheat,
    AntiCheatHitLongDistance.IAntiCheat,
    AntiCheatHitWeapon.IAntiCheat,
    AntiCheatSafety.IAntiCheat {
    @JSONField
    public final Player target = new Player();
    @JSONField
    protected final Fall fall = new Fall();

    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0], (HandWeapon)values[1], (Boolean)values[2], (List<TracerInfo>)values[3], (List<WeaponHit>)values[4], (IsoPlayer)values[5]);
    }

    public void set(IsoPlayer wielder, HandWeapon weapon, boolean isCriticalHit, List<TracerInfo> tracers, List<WeaponHit> hits, IsoPlayer target) {
        this.set(wielder, weapon, isCriticalHit, tracers, hits);
        this.target.set(target, false);
        this.fall.set(target.getHitReactionNetworkAI());
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
        return super.isConsistent(connection) && this.target.isConsistent(connection);
    }

    @Override
    public void update() {
        super.update();
        this.fall.set(this.target.getCharacter().realx, this.target.getCharacter().realy, this.target.getCharacter().realz, this.fall.dropDirection);
    }

    @Override
    public void preProcess() {
        super.preProcess();
        this.target.process();
    }

    @Override
    public void process() {
        IsoGameCharacter targetCharacter = this.getTargetCharacter();
        this.processHits(targetCharacter);
        if (targetCharacter != null && targetCharacter.isRemote()) {
            this.fall.process(targetCharacter);
        }

        this.processHitHumanoid(targetCharacter);
    }

    @Override
    public void postProcess() {
        super.postProcess();
        this.target.process();
    }

    @Override
    public void log(UdpConnection connection) {
        for (WeaponHit hit : this.hits) {
            PVPLogTool.logCombat(
                this.wielder.getPlayer().getUsername(),
                LoggerManager.getPlayerCoords(this.wielder.getPlayer()),
                this.target.getPlayer().getUsername(),
                LoggerManager.getPlayerCoords(this.target.getPlayer()),
                this.wielder.getPlayer().getX(),
                this.wielder.getPlayer().getY(),
                this.wielder.getPlayer().getZ(),
                this.getHandWeapon().getName(),
                hit.getDamage()
            );
        }
    }

    @Override
    public void attack() {
        this.wielder.attack(this.getHandWeapon(), true, this.shotID, this.hitCount);
        this.processTracers();
    }

    @Override
    public void react() {
        this.target.react();
    }

    @Override
    public float getDistance() {
        return IsoUtils.DistanceTo(this.target.getX(), this.target.getY(), this.wielder.getX(), this.wielder.getY());
    }

    @Override
    public IsoGameCharacter getTarget() {
        return this.target.getPlayer();
    }

    @Override
    public IsoGameCharacter getTargetCharacter() {
        return this.target.getPlayer();
    }

    @Override
    public boolean isMelee() {
        return this.getHandWeapon().isMelee();
    }
}
