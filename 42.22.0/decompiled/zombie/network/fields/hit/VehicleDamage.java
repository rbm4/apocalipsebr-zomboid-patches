// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.fields.hit;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PVPLogTool;
import zombie.network.fields.INetworkPacketField;
import zombie.vehicles.BaseVehicle;

public class VehicleDamage extends Hit implements INetworkPacketField {
    @JSONField
    public boolean isTargetHitFromBehind;
    @JSONField
    public boolean isStaggerBack;
    @JSONField
    public boolean isKnockedDown;

    public void set(float hitForce, float hitDirectionX, float hitDirectionY, boolean isTargetHitFromBehind, boolean isStaggerBack, boolean isKnockedDown) {
        this.set(hitForce, hitDirectionX, hitDirectionY);
        this.isTargetHitFromBehind = isTargetHitFromBehind;
        this.isStaggerBack = isStaggerBack;
        this.isKnockedDown = isKnockedDown;
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.isTargetHitFromBehind = b.getBoolean();
        this.isStaggerBack = b.getBoolean();
        this.isKnockedDown = b.getBoolean();
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        b.putBoolean(this.isTargetHitFromBehind);
        b.putBoolean(this.isStaggerBack);
        b.putBoolean(this.isKnockedDown);
    }

    public void process(IsoPlayer wielder, IsoGameCharacter target, BaseVehicle vehicle) {
        this.process(wielder, target);
        if (GameServer.server) {
            float impactSpeed = vehicle.getImpactVelocity();
            if (target instanceof IsoAnimal isoAnimal) {
                isoAnimal.setHealth(0.0F);
            } else if (target instanceof IsoZombie isoZombie) {
                float damage = target.onHitByVehicleApplyDamage(vehicle, impactSpeed);
                isoZombie.setKnockedDown(this.isKnockedDown);
                isoZombie.setStaggerBack(this.isStaggerBack);
                isoZombie.addAggro(wielder, damage);
            } else if (target instanceof IsoPlayer isoPlayer) {
                float damage = target.onHitByVehicleApplyDamage(vehicle, impactSpeed);
                isoPlayer.setKnockedDown(this.isKnockedDown);
                PVPLogTool.logCombat(wielder, isoPlayer, "vehicle", damage);
            }
        } else if (GameClient.client && target instanceof IsoPlayer) {
            target.getActionContext().reportEvent("washit");
            target.setVariable("hitpvp", false);
        }
    }
}
