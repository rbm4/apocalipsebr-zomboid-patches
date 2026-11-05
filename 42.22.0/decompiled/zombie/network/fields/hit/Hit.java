// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.fields.hit;

import zombie.CombatManager;
import zombie.Lua.LuaEventManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoLivingCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.combat.HitReaction;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PVPLogTool;
import zombie.network.ServerOptions;
import zombie.network.fields.INetworkPacketField;
import zombie.network.packets.hit.PlayerHit;
import zombie.util.Type;

public class Hit implements INetworkPacketField {
    private static final float MIN_DAMAGE = 0.0F;
    private static final float MAX_DAMAGE = 200.0F;
    @JSONField
    protected float hitForce;
    @JSONField
    protected float hitDirectionX;
    @JSONField
    protected float hitDirectionY;
    @JSONField
    protected byte hitHead;
    @JSONField
    protected boolean isHitLegs;
    @JSONField
    protected boolean isIgnoreDamage;
    @JSONField
    protected boolean isRemoveKnife;
    @JSONField
    protected float hitDamage;
    private CombatManager.HitData hitData;

    public void set(float hitForce, float hitDirectionX, float hitDirectionY) {
        this.hitForce = hitForce;
        this.hitDirectionX = hitDirectionX;
        this.hitDirectionY = hitDirectionY;
        this.hitHead = 0;
        this.isHitLegs = false;
        this.isIgnoreDamage = false;
        this.isRemoveKnife = false;
        this.hitDamage = 0.0F;
    }

    public void set(float hitForce, float hitDirectionX, float hitDirectionY, CombatManager.HitData hitData, boolean isRemoveKnife, float hitDamage) {
        this.set(hitForce, hitDirectionX, hitDirectionY);
        this.hitHead = hitData.hitHead();
        this.isHitLegs = hitData.hitLegs();
        this.isIgnoreDamage = hitData.isIgnoreDamage();
        this.isRemoveKnife = isRemoveKnife;
        this.hitDamage = hitDamage;
    }

    void process(IsoGameCharacter wielder, IsoGameCharacter target) {
        target.getHitDir().set(this.hitDirectionX, this.hitDirectionY);
        target.setHitForce(this.hitForce);
        target.setAttackedBy(wielder);
    }

    public static CombatManager.HitData getHitData(IsoLivingCharacter wielder, IsoMovingObject target, HandWeapon weapon) {
        return CombatManager.getInstance().getHitData(wielder, weapon, target, getHitChance(wielder, target, weapon));
    }

    private static float getHitChance(IsoLivingCharacter wielder, IsoMovingObject target, HandWeapon weapon) {
        float distance = CombatManager.getInstance().getHitDistanceSquared(wielder, target);
        return CombatManager.getInstance().calculateHitChanceData(wielder, weapon, target, PZMath.sqrt(distance)).hitChance();
    }

    private CombatManager.HitData getClientRolledHitData(IsoLivingCharacter wielder, IsoMovingObject target, HandWeapon weapon) {
        return CombatManager.getInstance().calculateHitData(wielder, weapon, target, this.isIgnoreDamage);
    }

    private CombatManager.HitData getRelayedHitData() {
        return new CombatManager.HitData(this.hitHead, this.isHitLegs, this.isIgnoreDamage, false, true);
    }

    private CombatManager.HitDamageData getHitDamageData(IsoGameCharacter wielder, IsoGameCharacter target, HandWeapon weapon) {
        return GameServer.server
            ? CombatManager.getInstance().getHitDamage(wielder, weapon, target, this.hitData, PlayerHit.getHitIndex())
            : new CombatManager.HitDamageData(this.hitDamage, 1.0F, false, false);
    }

    private boolean isHitDamageValid() {
        return this.hitDamage >= 0.0F && this.hitDamage <= 200.0F;
    }

    private CombatManager.HitData getFloorHitData(CombatManager.HitData hitData, boolean isTargetProne) {
        return new CombatManager.HitData(
            isTargetProne ? this.hitHead : 0,
            isTargetProne && this.isHitLegs,
            hitData.isIgnoreDamage(),
            hitData.isPiercedTargetDamageReduction(),
            hitData.isValidHitLocation()
        );
    }

    public boolean roll(IsoGameCharacter wielder, IsoGameCharacter target, HandWeapon weapon) {
        this.hitData = null;
        IsoLivingCharacter livingWielder = Type.tryCastTo(wielder, IsoLivingCharacter.class);
        if (livingWielder == null) {
            return true;
        } else if (!GameServer.server) {
            this.hitData = this.getRelayedHitData();
            return true;
        } else {
            CombatManager.HitData rolledHitData = CombatManager.isHitRolledByClient(target)
                ? this.getClientRolledHitData(livingWielder, target, weapon)
                : getHitData(livingWielder, target, weapon);
            if (rolledHitData == null) {
                return false;
            } else {
                this.isIgnoreDamage = rolledHitData.isIgnoreDamage();
                this.hitData = rolledHitData;
                return true;
            }
        }
    }

    public void process(IsoGameCharacter wielder, IsoGameCharacter target, HandWeapon weapon) {
        IsoLivingCharacter livingWielder = Type.tryCastTo(wielder, IsoLivingCharacter.class);
        if (livingWielder != null && this.hitData != null) {
            if (GameServer.server) {
                if (wielder.isAimAtFloor() && !weapon.isRanged()) {
                    this.hitData = this.getFloorHitData(this.hitData, target.isProne());
                }

                this.hitHead = this.hitData.hitHead();
                this.isHitLegs = this.hitData.hitLegs();
            }

            CombatManager.HitDamageData hitDamageData = this.getHitDamageData(wielder, target, weapon);
            boolean isRemoteDamage = !GameServer.server || CombatManager.isHitRolledByClient(target) && this.isHitDamageValid();
            float damageSplit = isRemoteDamage ? this.hitDamage : hitDamageData.damageSplit();
            if (!GameServer.server && !weapon.isMelee() && weapon.isRanged()) {
                CombatManager.getInstance().processHit(weapon, wielder, target);
            }

            IsoPlayer wielderPlayer = Type.tryCastTo(wielder, IsoPlayer.class);
            IsoZombie targetZombie = Type.tryCastTo(target, IsoZombie.class);
            if (this.isRemoveKnife
                && targetZombie != null
                && wielder.getPrimaryHandItem() == weapon
                && HitReaction.KNIFE_DEATH.getValue().equals(wielder.getVariableString("ZombieHitReaction"))) {
                CombatManager.getInstance().attachKnife(wielder, targetZombie);
            } else if (GameServer.server) {
                this.isRemoveKnife = false;
            }

            float damage = target.Hit(weapon, wielder, damageSplit, this.hitData.isIgnoreDamage(), hitDamageData.rangeDel(), isRemoteDamage);
            this.process(wielder, target);
            if (wielder.isAimAtFloor() && !weapon.isRanged() && wielder.isNpc()) {
                CombatManager.getInstance().splash(target, weapon, wielder);
            }

            IsoPlayer targetPlayer = Type.tryCastTo(target, IsoPlayer.class);
            if (this.hitData.hitHead() > 0) {
                CombatManager.getInstance().splash(target, weapon, wielder);
                CombatManager.getInstance().splash(target, weapon, wielder);
                if (GameServer.server && targetPlayer != null) {
                    targetPlayer.syncVisuals();
                }
            }

            if ((!livingWielder.isDoShove() || wielder.isAimAtFloor())
                && wielder.DistToSquared(target) < 2.0F
                && Math.abs(wielder.getZ() - target.getZ()) < 0.5F
                && wielderPlayer == null) {
                wielder.addBlood(null, false, false, false);
            }

            if (!target.isDead() && targetPlayer == null && (!livingWielder.isDoShove() || wielder.isAimAtFloor())) {
                CombatManager.getInstance().splash(target, weapon, wielder);
            }

            if (GameServer.server) {
                this.hitDamage = damage;
                CombatManager.getInstance().applyMeleeEnduranceLoss(wielder, target, weapon, damage);
                if (targetPlayer != null && !targetPlayer.isAnimal() && GameServer.getConnectionFromPlayer(targetPlayer) == null) {
                    if (target.isDead()) {
                        target.die();
                        GameServer.doDelayedDisconnect(targetPlayer);
                    } else {
                        GameServer.addDelayToDelayedConnection(targetPlayer.getUsername(), ServerOptions.getInstance().safetyCooldownTimer.getValue());
                    }
                }

                if (!this.hitData.isIgnoreDamage() && (!weapon.isRangeFalloff() || !PlayerHit.isShotgunXpAwarded())) {
                    LuaEventManager.triggerEvent("OnWeaponHitXp", wielder, weapon, target, hitDamageData.damageSplit(), 1);
                    if (weapon.isRangeFalloff()) {
                        PlayerHit.setShotgunXpAwarded();
                    }
                }

                CombatManager.getInstance().processMaintenanceCheck(wielder, weapon, target);
                if (wielderPlayer != null) {
                    if (targetZombie != null) {
                        targetZombie.addAggro(wielder, damage);
                    }

                    if (targetPlayer != null && !targetPlayer.isAnimal()) {
                        PVPLogTool.logCombat(wielderPlayer, targetPlayer, weapon.getName(), damage);
                    }
                }
            }
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.hitForce = b.getFloat();
        this.hitDirectionX = b.getFloat();
        this.hitDirectionY = b.getFloat();
        this.hitHead = b.getByte();
        this.isHitLegs = b.getBoolean();
        this.isIgnoreDamage = b.getBoolean();
        this.isRemoveKnife = b.getBoolean();
        this.hitDamage = b.getFloat();
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putFloat(this.hitForce);
        b.putFloat(this.hitDirectionX);
        b.putFloat(this.hitDirectionY);
        b.putByte(this.hitHead);
        b.putBoolean(this.isHitLegs);
        b.putBoolean(this.isIgnoreDamage);
        b.putBoolean(this.isRemoveKnife);
        b.putFloat(this.hitDamage);
    }
}
