package zombie.apocalipse.humans;

import zombie.apocalipse.humans.state.*;
import zombie.characters.CharacterStat;

/** Simulation-thread adapter. Native body observations are commands; native stats are projections. */
final class ServerHumanPhysiology {
    private final ServerHuman human;
    private float projectedHealth;
    private long lastTick=Long.MIN_VALUE;
    ServerHumanPhysiology(ServerHuman human){this.human=human;project();}

    void update(long now){
        float nativeHealth=human.player.getHealth();
        if(human.player.getBodyDamage()!=null)nativeHealth=Math.min(nativeHealth,human.player.getBodyDamage().getOverallBodyHealth()/100F);
        // Import new native loss once. Native regeneration/god-mode resets cannot silently revive/heal logical state.
        if(Float.isFinite(nativeHealth)&&nativeHealth<projectedHealth-0.000001F){
            float loss=Math.clamp(projectedHealth-nativeHealth,0,1);
            human.state.dispatch(new HumanMutation.Damage(loss,0,0,0));
            human.speech.damage(null,loss,null,now);
        }
        float seconds=lastTick==Long.MIN_VALUE?0:(float)Math.max(0,Math.min(.15,(now-lastTick)/1_000_000_000.0));
        lastTick=Math.max(lastTick,now);
        float enduranceRate=switch(human.movement.snapshot().gait()){
            case WALK -> -.25F/140;case RUN -> -.4F/140;case SPRINT -> -1F/140;case IDLE -> 9F/140;
        };
        // Hunger/thirst/fatigue are tracked but autonomous metabolism is not enabled by invented rates.
        human.state.dispatch(new HumanMutation.Advance(seconds,enduranceRate,0,0,0));
        project();
    }
    void project(){
        var v=human.state.snapshot().vitals();
        projectedHealth=v.health();
        human.player.setHealth(v.health());
        if(human.player.getBodyDamage()!=null)human.player.getBodyDamage().setOverallBodyHealth(v.health()*100);
        var stats=human.player.getStats();
        stats.set(CharacterStat.ENDURANCE,v.endurance());stats.set(CharacterStat.HUNGER,v.hunger());
        stats.set(CharacterStat.THIRST,v.thirst());stats.set(CharacterStat.FATIGUE,v.fatigue());
        stats.set(CharacterStat.PAIN,v.pain()*100);stats.set(CharacterStat.PANIC,v.fear()*100);
        stats.set(CharacterStat.SICKNESS,v.sickness());stats.set(CharacterStat.ZOMBIE_INFECTION,v.infection()*100);
    }
}
