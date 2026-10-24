package zombie.apocalipse.humans;

import java.util.*;
import zombie.AttackType;
import zombie.CombatManager;
import zombie.characters.CharacterStat;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.core.random.Rand;
import zombie.inventory.types.HandWeapon;
import zombie.network.PacketTypes;
import zombie.network.fields.hit.HitInfo;
import zombie.network.fields.hit.WeaponHit;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.hit.HitCharacter;
import zombie.apocalipse.humans.state.*;

/** Native packet construction and local native processing, never a fake client receive/ownership path. */
final class ServerHumanCombatNetwork {
    private ServerHumanCombatNetwork() { }
    static boolean clearBodies(ServerHuman human,IsoZombie target){return clearBodies(human,target,human.player.getX(),human.player.getY());}
    static boolean clearBodies(ServerHuman human,IsoZombie target,float fromX,float fromY){
        double dx=target.getX()-fromX,dy=target.getY()-fromY,length=dx*dx+dy*dy;
        if(length<.0001)return true;
        int steps=(int)Math.ceil(Math.sqrt(length)*2);if(steps>64)return false;
        int inspected=0;var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        for(int n=0;n<=steps;n++){
            var square=human.player.getCell().getGridSquare((int)Math.floor(fromX+dx*n/steps),(int)Math.floor(fromY+dy*n/steps),human.player.getZi());
            if(square==null)return false;
            for(var object:square.getMovingObjects()){
                if(++inspected>256)return false;
                if(!(object instanceof IsoGameCharacter body)||body==target||body==human.player||!seen.add(body))continue;
                if(body.isDead()||body.getHealth()<=0)continue;
                double t=((body.getX()-fromX)*dx+(body.getY()-fromY)*dy)/length;
                if(t<=0||t>=1)continue;
                double ox=body.getX()-(fromX+t*dx),oy=body.getY()-(fromY+t*dy);
                if(ox*ox+oy*oy<.35*.35)return false;
            }
        }
        return true;
    }
    static boolean commit(ServerHuman human,IsoZombie target,int itemId,long execution,long strike,long now){
        HandWeapon weapon=human.inventory.item(itemId);
        if(weapon==null||human.player.getPrimaryHandItem()!=weapon||weapon.getCondition()<=0||target.isDead()||human.movement.traversing())return false;
        var actor=human.player;
        if(!ServerHumanAttackLine.clear(human,target,actor.getX(),actor.getY(),weapon.isRanged()))return false;
        actor.setUseHandWeapon(weapon);actor.setDoShove(false);actor.setAimAtFloor(target.isOnFloor());
        actor.setAttackType(weapon.isRanged()?AttackType.SHOT:target.isOnFloor()?AttackType.MELEE_TO_FLOOR:AttackType.MELEE_SWING);
        actor.useChargeDelta=2;
        var vars=actor.getAttackVars();vars.clear();vars.setWeapon(weapon);vars.aimAtFloor=target.isOnFloor();vars.useChargeDelta=2;
        vars.targetDistance=(float)Math.hypot(actor.getX()-target.getX(),actor.getY()-target.getY());
        if(target.isOnFloor())vars.targetOnGround.set(target);else vars.targetStanding.set(target);
        actor.getHitInfoList().clear();
        actor.getHitInfoList().add(new HitInfo().init(target,1,vars.targetDistance*vars.targetDistance,target.getX(),target.getY(),target.getZ()));
        actor.getNetworkCharacterAI().setShotID((byte)(actor.getNetworkCharacterAI().getShotID()+1));
        boolean hit=!weapon.isRanged()||Rand.Next(100)<Math.clamp(weapon.getHitChance(),5,95);
        // Native ranged calculateCritChance reads client lighting using playerIndex.
        // Detached server actors have index -1: use the authored firearm chance instead.
        int criticalChance=weapon.isRanged()?Math.round(weapon.getCriticalChance()):actor.calculateCritChance(target);
        boolean critical=hit&&Rand.Next(100)<Math.clamp(criticalChance,0,100);
        actor.setCriticalHit(critical);
        float before=target.getHealth();
        float committed=0;
        var hits=new ArrayList<WeaponHit>();
        if(hit){
            actor.setVariable("ZombieHitReaction",weapon.isRanged()?"Shot":"HeadLeft");
            // "Shot" is the attacker's unresolved marker, not a playable victim
            // clip. Vanilla CombatManager.processHit resolves it before packing.
            // Use a concrete non-fall reaction here without rerolling criticals
            // through the client-only lighting/ballistics path on this NPC.
            target.setHitReaction(weapon.isRanged()?(target.isOnFloor()?"Floor":"ShotChestL"):"HeadLeft");
            float rolled=Rand.Next(weapon.getMinDamage(),weapon.getMaxDamage());
            if(!weapon.isRanged())rolled*=weapon.getDamageMod(actor)*actor.getHittingMod();
            // Let native code resolve hit force/modifiers first; calibration must not inflate knockback.
            float damage=zombie.apocalipse.humans.brain.HumanCombatDamage.packetDamage(
                target.processHitDamage(weapon,actor,rolled,false,1),weapon.isRanged());
            committed=damage;
            double dx=target.getX()-actor.getX(),dy=target.getY()-actor.getY(),distance=Math.max(.001,Math.hypot(dx,dy));
            var wound=new WeaponHit();wound.set(damage,1,target.getHitForce(),(float)(dx/distance),(float)(dy/distance),false,false,false,false);hits.add(wound);
        }
        actor.setAttackTargetSquare(target.getCurrentSquare());
        HitCharacter packet=hit?new ServerHumanHitPacket(actor,weapon,target,hits):new ServerHumanMissPacket(actor,weapon);
        var packetType=hit?PacketTypes.PacketType.PlayerHitZombie:PacketTypes.PacketType.PlayerHitSquare;
        ServerHumanTransport.equipment(human);
        // Native client attack presentation is driven by this event, independently of the 500ms movement heartbeat.
        for(var viewer:human.viewers)if(human.isRelevant(viewer))packet.sendToClient(packetType,viewer);
        float endurance=actor.getStats().get(CharacterStat.ENDURANCE);
        CombatManager.getInstance().processWeaponEndurance(actor,weapon);
        // HitCharacter's native local path applies WeaponHit -> target.Hit, maintenance, XP hooks and death.
        // It is not sufficient to send the packet: the server must process the same result locally once.
        packet.processClient(null);
        float delta=actor.getStats().get(CharacterStat.ENDURANCE)-endurance;
        if(Float.isFinite(delta)&&delta<0)human.state.dispatch(new HumanMutation.Adjust(HumanState.Stat.ENDURANCE,delta));
        actor.getStats().set(CharacterStat.ENDURANCE,human.state.snapshot().vitals().endurance());
        ServerHumanTransport.equipment(human);
        if(weapon.isRanged()){
            actor.addWorldSoundUnlessInvisible(Math.max(1,weapon.getSoundRadius()),Math.max(1,weapon.getSoundVolume()),false);
            for(var viewer:human.viewers)if(human.isRelevant(viewer))
                INetworkPacket.send(viewer,PacketTypes.PacketType.RangedWeaponSound,weapon.getSwingSound(),actor.getX(),actor.getY(),actor.getZ(),actor.getOnlineID(),0F,0F);
        }
        float after=target.getHealth(),packetDamage=committed;
        HumanLog.log(HumanLog.Level.DEBUG,()->"combat commit key="+human.definition.key()+"; execution="+execution+"; strike="+strike+"; weapon="+weapon.getFullType()
            +"; target="+target.getOnlineID()+"; hit="+hit+"; critical="+critical+"; packetDamage="+packetDamage+"; health="+before+" -> "+after);
        return true;
    }
}
