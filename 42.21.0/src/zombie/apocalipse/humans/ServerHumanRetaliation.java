package zombie.apocalipse.humans;

import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.encounters.*;
import zombie.characters.IsoPlayer;
import zombie.characters.Faction;
import zombie.iso.areas.NonPvpZone;
import zombie.network.GameServer;
import zombie.network.ServerOptions;

/** Live target authority and composition of the shared weapon/movement engines. */
final class ServerHumanRetaliation implements RetaliationPort {
    private final ServerHuman human;
    ServerHumanRetaliation(ServerHuman human){this.human=human;}
    static RetaliatePlayerModule module(ServerHuman human){return human.activity.behavior() instanceof HumanModuleRuntime runtime?runtime.module(RetaliatePlayerModule.class):null;}
    static void bind(ServerHuman human){var module=module(human);if(module!=null)module.bind(new ServerHumanRetaliation(human));}
    static boolean pvp(IsoPlayer actor,IsoPlayer target,boolean ranged){
        if(!GameServer.server||actor==null||target==null||target.isGodMod()||!ServerOptions.instance.pvp.getValue()
            ||NonPvpZone.isInNonPvpZone(actor)||NonPvpZone.isInNonPvpZone(target))return false;
        if(ServerOptions.instance.safetySystem.getValue()&&actor.getSafety().isEnabled()&&target.getSafety().isEnabled())return false;
        return !Faction.isInSameFaction(actor,target)||!ranged&&(actor.isFactionPvp()||target.isFactionPvp());
    }
    private IsoPlayer resolve(HumanObservation.Identity identity){
        var target=GameServer.IDToPlayerMap.get(identity.onlineId());
        if(target==null||target.getID()!=identity.instanceId()||!human.perception.eligible(target)||target.isGodMod()
            ||human.state.snapshot().dead()||human.state.snapshot().injuries().downed()||human.state.snapshot().injuries().immobile()
            ||!human.territory.contains(human.player.getX(),human.player.getY(),human.player.getZ())
            ||!human.territory.contains(target.getX(),target.getY(),target.getZ())
            ||Math.hypot(target.getX()-human.player.getX(),target.getY()-human.player.getY())>human.profile.perceptionRadius()
            ||!pvp(human.player,target,false))return null;
        return target;
    }
    public Contact observe(HumanObservation.Identity identity){var target=resolve(identity);return new Contact(target!=null,target!=null&&human.perception.visible(target));}
    public HumanCombat.Execution begin(HumanObservation.Identity identity,long now){
        var target=resolve(identity);
        if(target==null)return HumanCombat.finished(new HumanCombat.Result(HumanCombat.Status.REJECTED,HumanCombat.Reason.INVALID_TARGET));
        var approach=new ServerHumanCombatApproach(human,target);
        return new HumanWeaponCombat(human.inventory,new HumanWeaponCombat.Operations(){
            public double distance(){return approach.distance();}
            public boolean defeated(){return resolve(identity)!=target;}
            public HumanActuator.MotionStatus approach(HumanCombatInventory.Weapon weapon,long at){
                if(resolve(identity)!=target)return HumanActuator.MotionStatus.FAILED;
                return approach.update(weapon,at);
            }
            public boolean clearAttackLine(HumanCombatInventory.Weapon weapon){return resolve(identity)==target&&human.perception.visible(target)
                &&pvp(human.player,target,weapon.ranged())&&!human.movement.traversing()&&approach.clear(human.player.getX(),human.player.getY(),weapon.ranged());}
            public void face(){
                if(!human.movement.traversing())human.player.setDirectionAngle((float)Math.toDegrees(Math.atan2(target.getY()-human.player.getY(),target.getX()-human.player.getX())));
            }
            public void stop(){human.movement.stop();}
            public void aim(boolean enabled){ServerHumanTransport.aim(human,enabled);}
            public boolean commit(HumanCombatInventory.Weapon weapon,long strike,long at){
                if(distance()>weapon.reach()||!clearAttackLine(weapon))return false;
                return ServerHumanCombatNetwork.commit(human,target,weapon.itemId(),0,strike,at);
            }
        },HumanWeaponCombat.Mode.AUTO);
    }
}
