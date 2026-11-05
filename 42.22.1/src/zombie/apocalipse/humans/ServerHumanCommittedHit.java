package zombie.apocalipse.humans;

import zombie.CombatManager;
import zombie.Lua.LuaEventManager;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.types.HandWeapon;
import zombie.network.fields.hit.Hit;

/** Authored server NPC roll, serialized as native Hit; clients decode an ordinary Hit. */
final class ServerHumanCommittedHit extends Hit {
    ServerHumanCommittedHit(float damage,float force,float dx,float dy){
        set(force,dx,dy,new CombatManager.HitData((byte)0,false,false,false,true),false,damage);
    }
    // Do not reroll the authored attack through native client lighting / critical paths.
    @Override public boolean roll(IsoGameCharacter actor,IsoGameCharacter target,HandWeapon weapon){return true;}
    @Override public void process(IsoGameCharacter actor,IsoGameCharacter target,HandWeapon weapon){
        target.Hit(weapon,actor,this.hitDamage,false,1,true);
        target.getHitDir().set(this.hitDirectionX,this.hitDirectionY);
        target.setHitForce(this.hitForce);target.setAttackedBy(actor);
        if(actor.isAimAtFloor()&&!weapon.isRanged()&&actor.isNpc())CombatManager.getInstance().splash(target,weapon,actor);
        if(!target.isDead()&&!(target instanceof zombie.characters.IsoPlayer))CombatManager.getInstance().splash(target,weapon,actor);
        LuaEventManager.triggerEvent("OnWeaponHitXp",actor,weapon,target,this.hitDamage,1);
        CombatManager.getInstance().processMaintenanceCheck(actor,weapon,target);
        target.helmetFall(false);
    }
}
