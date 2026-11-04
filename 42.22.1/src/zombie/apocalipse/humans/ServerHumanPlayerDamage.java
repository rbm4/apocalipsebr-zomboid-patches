package zombie.apocalipse.humans;

import java.util.List;
import java.nio.ByteBuffer;
import zombie.apocalipse.humans.state.HumanMutation;
import zombie.characters.IsoPlayer;
import zombie.inventory.types.HandWeapon;
import zombie.network.fields.hit.Hit;
import zombie.core.network.ByteBufferWriter;

/** Import native body loss once; never roll another DamageFromWeapon here. */
public final class ServerHumanPlayerDamage {
    private ServerHumanPlayerDamage(){ }
    public static boolean valid(List<Hit> hits,HandWeapon weapon){
        if(weapon==null||hits.isEmpty())return false;
        long limit=Math.min(32L,(long)Math.max(1,weapon.getProjectileCount())*Math.max(1,weapon.getMaxHitCount()));
        if(hits.size()>limit)return false;
        var bytes=ByteBuffer.allocate(20);var writer=new ByteBufferWriter(bytes);
        for(var hit:hits){
            if(hit==null)return false;
            bytes.clear();hit.write(writer);bytes.flip();
            for(int i=0;i<3;i++)if(!Float.isFinite(bytes.getFloat()))return false;
            bytes.position(16);float damage=bytes.getFloat();
            if(!Float.isFinite(damage)||damage<0||damage>200)return false;
        }
        return true;
    }
    public static float health(IsoPlayer victim){
        victim.getBodyDamage().calculateOverallHealth();return victim.getBodyDamage().getOverallBodyHealth();
    }
    public static void importLoss(IsoPlayer attacker,IsoPlayer victim,HandWeapon weapon,float before){
        var human=ServerHumanFactory.tradingHuman(victim.getOnlineID());
        if(human==null||human.player!=victim)return;
        float after=health(victim);
        if(!Float.isFinite(before)||!Float.isFinite(after))return;
        float loss=Math.clamp((before-after)/100F,0,1);
        if(loss>0)human.state.dispatch(new HumanMutation.Damage(loss,0,0,0));
        if(human.state.snapshot().dead()&&human.profile.encounter().enabled())human.deathKiller=ServerHumanEncounters.key(human,attacker);
        human.physiology.project();
        if(loss>0)human.speech.damage(attacker,loss,weapon,System.nanoTime());
        ServerHumanPlayerAttacks.trace(victim,"native damage imported; loss="+loss);
    }
}
