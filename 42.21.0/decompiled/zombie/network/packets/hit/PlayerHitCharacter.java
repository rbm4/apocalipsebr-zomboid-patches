// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import java.util.ArrayList;
import java.util.List;
import zombie.CombatManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.random.Rand;
import zombie.inventory.types.HandWeapon;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.fields.hit.Hit;
import zombie.network.fields.hit.TracerInfo;
import zombie.network.fields.hit.WeaponHit;

public abstract class PlayerHitCharacter extends PlayerHit {
    @JSONField
    protected final List<WeaponHit> hits = new ArrayList<>();

    public void set(IsoPlayer wielder, HandWeapon weapon, boolean isCriticalHit, List<TracerInfo> tracers, List<WeaponHit> hits) {
        super.set(wielder, weapon, isCriticalHit, tracers, hits.size());
        this.hits.clear();
        this.hits.addAll(hits);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.hits.clear();

        for (int i = 0; i < this.hitCount; i++) {
            WeaponHit hit = new WeaponHit();
            hit.parse(b, connection);
            this.hits.add(hit);
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);

        for (WeaponHit hit : this.hits) {
            hit.write(b);
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        if (!super.isConsistent(connection)) {
            return false;
        } else {
            for (WeaponHit hit : this.hits) {
                if (!hit.isConsistent(connection)) {
                    return false;
                }
            }

            return true;
        }
    }

    public abstract IsoGameCharacter getTargetCharacter();

    public void processHits(IsoGameCharacter target) {
        IsoGameCharacter wielderCharacter = this.wielder.getCharacter();
        HandWeapon handWeapon = this.getHandWeapon();

        for (WeaponHit hit : this.hits) {
            hit.process(wielderCharacter, target, handWeapon);
        }
    }

    public void processHitHumanoid(IsoGameCharacter targetCharacter) {
        if (GameServer.server) {
            HandWeapon handWeapon = this.getHandWeapon();
            IsoGameCharacter wielderCharacter = this.getWielder();

            for (WeaponHit hit : this.hits) {
                CombatManager.getInstance().applyMeleeEnduranceLoss(wielderCharacter, targetCharacter, handWeapon, hit.getDamage());
            }

            if (handWeapon.isMelee()) {
                this.resolveSpikedArmorDamage(targetCharacter);
            }
        }
    }

    public void resolveSpikedArmorDamage(IsoGameCharacter targetCharacter) {
        for (WeaponHit hit : this.hits) {
            int partHit;
            if (hit.isHitHead()) {
                partHit = Rand.Next(BodyPartType.ToIndex(BodyPartType.Head), BodyPartType.ToIndex(BodyPartType.Neck) + 1);
            } else if (hit.isHitLegs()) {
                partHit = Rand.Next(BodyPartType.ToIndex(BodyPartType.Groin), BodyPartType.ToIndex(BodyPartType.Foot_R) + 1);
            } else {
                partHit = Rand.Next(BodyPartType.ToIndex(BodyPartType.Hand_L), BodyPartType.ToIndex(BodyPartType.Neck) + 1);
            }

            CombatManager.getInstance().resolveSpikedArmorDamage(this.wielder.getCharacter(), this.getHandWeapon(), targetCharacter, partHit);
        }
    }

    public Hit getHit(int index) {
        return this.hits.get(index);
    }
}
