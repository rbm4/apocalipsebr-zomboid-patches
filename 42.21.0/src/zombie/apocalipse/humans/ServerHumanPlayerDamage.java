package zombie.apocalipse.humans;

import java.util.List;
import zombie.apocalipse.humans.state.HumanMutation;
import zombie.characters.IsoPlayer;
import zombie.inventory.types.HandWeapon;
import zombie.network.PacketTypes;
import zombie.network.fields.hit.WeaponHit;

/** NPC vulnerability adapter. Call only after authenticated, deduplicated attack intake. */
public final class ServerHumanPlayerDamage {
    private ServerHumanPlayerDamage(){ }
    public static boolean valid(List<WeaponHit> hits,HandWeapon weapon){
        if(weapon==null||hits.isEmpty()||hits.size()>Math.min(32,Math.max(1,weapon.getProjectileCount())*Math.max(1,weapon.getMaxHitCount())))return false;
        return hits.stream().allMatch(hit->Float.isFinite(hit.getDamage())&&hit.getDamage()>=0&&hit.getDamage()<=100);
    }
    public static void apply(IsoPlayer attacker,IsoPlayer victim,HandWeapon weapon,List<WeaponHit> hits){
        var human=ServerHumanFactory.tradingHuman(victim.getOnlineID());
        if(human==null||human.player!=victim||victim.isGodMod()||human.state.snapshot().dead())return;
        var body=victim.getBodyDamage();
        body.calculateOverallHealth();float before=body.getOverallBodyHealth();
        victim.setAttackedBy(attacker);
        // Use the same server-side body/armor/PvP damage roll as native IsoPlayer
        // hits. Client damage, knockdown, reaction and fall coordinates are not authority.
        for(var hit:hits)if(hit.getDamage()>0&&!hit.isDamageIgnored())body.DamageFromWeapon(weapon,-1);
        body.calculateOverallHealth();
        float loss=Math.clamp((before-body.getOverallBodyHealth())/100F,0,1);
        if(loss>0)human.state.dispatch(new HumanMutation.Damage(loss,0,0,0));
        if(human.state.snapshot().dead()&&human.profile.encounter().enabled())human.deathKiller=ServerHumanEncounters.key(human,attacker);
        human.physiology.project();
        // Rebuild presentation from server state; stock clients only display PvP
        // hit effects. Never call processServer/PlayerHit.attack a second time.
        double dx=victim.getX()-attacker.getX(),dy=victim.getY()-attacker.getY(),distance=Math.max(.001,Math.hypot(dx,dy));
        var effect=new WeaponHit();effect.set(loss,1,.2F,(float)(dx/distance),(float)(dy/distance),false,false,false,loss==0);
        var presentation=new ServerHumanPlayerHitPacket(attacker,weapon,victim,List.of(effect));
        for(var viewer:human.viewers)if(human.isRelevant(viewer)&&viewer!=zombie.network.GameServer.getConnectionFromPlayer(attacker))
            presentation.sendToClient(PacketTypes.PacketType.PlayerHitPlayer,viewer);
        float health=human.state.snapshot().vitals().health();
        HumanLog.log(HumanLog.Level.INFO,()->"player damage key="+human.definition.key()+"; loss="+loss+"; health="+health);
        ServerHumanPlayerAttacks.trace(victim,"damage committed; loss="+loss+"; health="+health);
    }
}
