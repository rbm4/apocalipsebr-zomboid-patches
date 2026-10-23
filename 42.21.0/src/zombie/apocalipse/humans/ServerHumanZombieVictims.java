package zombie.apocalipse.humans;

import java.util.List;
import zombie.apocalipse.zombies.ZombieVictimPort;
import zombie.characters.IsoZombie;

/** All NPC vulnerability policy stays with the human owner, independently of zombie transport. */
final class ServerHumanZombieVictims implements ZombieVictimPort {
    public List<Victim> snapshot(){
        return ServerHumanFactory.activeZombieVictims().stream().map(this::snapshot).filter(java.util.Objects::nonNull).toList();
    }
    private Victim snapshot(ServerHuman human){
        return human==null || human.state.snapshot().dead() || human.player.isDead()
            || human.profile.cheats().ghostMode() || human.profile.cheats().invisible() ? null
            : new Victim(human.player.getOnlineID(),human.player,human.player.getX(),human.player.getY(),human.player.getZi());
    }
    public Victim resolve(short id){
        return snapshot(ServerHumanFactory.activeZombieVictim(id));
    }
    public String strike(Victim expected,IsoZombie attacker,long execution){
        var current=resolve(expected.id());
        if(current==null || current.body()!=expected.body())return "victim_lost";
        var human=ServerHumanFactory.activeZombieVictim(expected.id());
        if(human==null)return "victim_lost";
        if(human.profile.cheats().godMode())return "would_hit_god_mode";
        var body=human.player.getBodyDamage();
        if(body==null)return "body_unavailable";
        human.player.setAttackedBy(attacker);
        body.calculateOverallHealth();
        float before=body.getOverallBodyHealth();
        boolean hit=body.AddRandomDamageFromZombie(attacker,"Bite",-1);
        body.calculateOverallHealth();
        float loss=Math.clamp((before-body.getOverallBodyHealth())/100F,0,1);
        if(loss>0)human.state.dispatch(new zombie.apocalipse.humans.state.HumanMutation.Damage(loss,0,0,0));
        if(human.player.isDeathDragDown())human.state.dispatch(new zombie.apocalipse.humans.state.HumanMutation.Kill());
        human.physiology.project();
        return hit?"native_damage="+loss:"defended";
    }
}
