// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import java.util.List;
import zombie.CombatManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.logger.LoggerManager;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.objects.IsoBarricade;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.anticheats.AntiCheat;
import zombie.network.anticheats.AntiCheatHitLongDistance;
import zombie.network.anticheats.AntiCheatHitWeapon;
import zombie.network.fields.hit.Thumpable;
import zombie.network.fields.hit.TracerInfo;

@PacketSetting(
    ordering = 0,
    priority = 0,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 0,
    anticheats = {AntiCheat.HitLongDistance, AntiCheat.HitWeapon}
)
public class PlayerHitObjectPacket extends PlayerHit implements AntiCheatHitLongDistance.IAntiCheat, AntiCheatHitWeapon.IAntiCheat {
    @JSONField
    protected final Thumpable thumpable = new Thumpable();

    public PlayerHitObjectPacket() {
    }

    public PlayerHitObjectPacket(IsoPlayer wielder, HandWeapon weapon, CombatManager.TargetHitList hitList, List<TracerInfo> tracers) {
        this.set(wielder, weapon, hitList, tracers);
    }

    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0], (HandWeapon)values[1], (CombatManager.TargetHitList)values[2], (List<TracerInfo>)values[3]);
    }

    @Override
    public void set(IsoPlayer wielder, HandWeapon weapon, CombatManager.TargetHitList hitList, List<TracerInfo> tracers) {
        this.set(wielder, weapon, tracers, hitList.hits());
        this.thumpable.set(hitList.target());
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        this.thumpable.write(b);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.thumpable.parse(b, connection);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return super.isConsistent(connection) && this.thumpable.isConsistent(connection);
    }

    private boolean doWeaponHit() {
        return this.thumpable.getIsoObject() instanceof IsoDoor
            || this.thumpable.getIsoObject() instanceof IsoTree
            || this.thumpable.getIsoObject() instanceof IsoWindow
            || this.thumpable.getIsoObject() instanceof IsoBarricade
            || this.thumpable.getIsoObject() instanceof IsoThumpable
            || this.thumpable.getIsoObject() instanceof IsoWorldInventoryObject;
    }

    @Override
    public IsoObject getTarget() {
        return this.thumpable.getIsoObject();
    }

    @Override
    public void process() {
        if (GameServer.server) {
            for (int i = 0; i < this.getHitCount(); i++) {
                CombatManager.getInstance().processMaintenanceCheck(this.getCharacter(), this.getHandWeapon(), this.thumpable.getIsoObject());
            }
        }

        if (this.doWeaponHit()) {
            for (int i = 0; i < this.getHitCount(); i++) {
                this.thumpable.getIsoObject().WeaponHit(this.getPlayer(), this.weapon.getWeapon());
            }
        }
    }

    @Override
    public float getDistance() {
        return IsoUtils.DistanceTo(this.thumpable.getX(), this.thumpable.getY(), this.getX(), this.getY());
    }

    @Override
    public void log(UdpConnection connection) {
        if (this.thumpable.getIsoObject().getObjectIndex() == -1) {
            LoggerManager.getLogger("map")
                .write(
                    String.format(
                        "%s \"%s\" destroyed %s with %s at %f,%f,%f",
                        connection.getIDStr(),
                        connection.getUserName(),
                        this.thumpable.getName(),
                        this.weapon.getWeapon().getName(),
                        this.thumpable.getIsoObject().getX(),
                        this.thumpable.getIsoObject().getY(),
                        this.thumpable.getIsoObject().getZ()
                    )
                );
        }
    }
}
