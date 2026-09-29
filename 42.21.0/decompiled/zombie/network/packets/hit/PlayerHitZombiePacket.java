// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import java.util.List;
import zombie.characters.Capability;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoUtils;
import zombie.iso.objects.IsoDeadBody;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.anticheats.AntiCheat;
import zombie.network.anticheats.AntiCheatHitDamage;
import zombie.network.anticheats.AntiCheatHitLongDistance;
import zombie.network.anticheats.AntiCheatHitWeapon;
import zombie.network.fields.hit.Fall;
import zombie.network.fields.hit.TracerInfo;
import zombie.network.fields.hit.WeaponHit;
import zombie.network.fields.hit.Zombie;
import zombie.network.id.IIdentifiable;
import zombie.network.id.ObjectIDType;

@PacketSetting(
    ordering = 0,
    priority = 0,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 3,
    anticheats = {AntiCheat.HitDamage, AntiCheat.HitLongDistance, AntiCheat.HitWeapon}
)
public class PlayerHitZombiePacket
    extends PlayerHitCharacter
    implements AntiCheatHitDamage.IAntiCheat,
    AntiCheatHitLongDistance.IAntiCheat,
    AntiCheatHitWeapon.IAntiCheat {
    @JSONField
    protected final Zombie target = new Zombie();
    @JSONField
    protected final Fall fall = new Fall();

    @Override
    public void setData(Object... values) {
        this.set(
            (IsoPlayer)values[0],
            (HandWeapon)values[1],
            (Boolean)values[2],
            (List<TracerInfo>)values[3],
            (List<WeaponHit>)values[4],
            (IsoZombie)values[5],
            (Boolean)values[6]
        );
    }

    public void set(
        IsoPlayer wielder, HandWeapon weapon, boolean isCriticalHit, List<TracerInfo> tracers, List<WeaponHit> hits, IsoZombie target, boolean helmetFall
    ) {
        this.set(wielder, weapon, isCriticalHit, tracers, hits);
        this.target.set(target, helmetFall);
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
    public void logInconsistentPacket(IConnection connection, PacketTypes.PacketType packetType) {
        if (this.wielder.isConsistent(connection) && this.shotID == this.wielder.getPlayer().getNetworkCharacterAI().getShotID()) {
            for (IIdentifiable identifiable : ObjectIDType.DeadBody.getObjects()) {
                if (identifiable instanceof IsoDeadBody isoDeadBody && isoDeadBody.getCharacterOnlineID() == this.target.getID()) {
                    return;
                }
            }
        }

        super.logInconsistentPacket(connection, packetType);
    }

    @Override
    public void preProcess() {
        super.preProcess();
        this.target.process();
    }

    @Override
    public void process() {
        IsoGameCharacter targetCharacter = this.getTargetCharacter();
        this.fall.process(targetCharacter);
        this.processHits(targetCharacter);
        this.processHitHumanoid(targetCharacter);
    }

    @Override
    public void postProcess() {
        super.postProcess();
        this.target.process();
    }

    @Override
    public void react() {
        this.target.react(this.getHandWeapon());
    }

    @Override
    public float getDistance() {
        return IsoUtils.DistanceTo(this.target.getX(), this.target.getY(), this.wielder.getX(), this.wielder.getY());
    }

    @Override
    public IsoGameCharacter getTargetCharacter() {
        return this.target.getZombie();
    }
}
