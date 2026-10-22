package zombie.apocalipse.humans;

import java.util.List;
import zombie.apocalipse.humans.brain.HumanSenses.Contact;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.humans.movement.MovementModifiers.Pace;
import zombie.apocalipse.humans.survival.*;

/** Simulation-thread adapter. Profile territory and shared physical traversal apply to emergency routes too. */
final class ServerHumanSurvival implements SurvivalPort {
    private final ServerHuman human;
    ServerHumanSurvival(ServerHuman human){this.human=human;}
    public boolean escape(List<Contact> threats,SurvivalSettings settings,long now){
        human.walking.stop();
        var scope=human.walking.scope("background");if(scope==null||!scope.available())return false;
        var positions=threats.stream().filter(c->(int)Math.floor(c.z())==human.player.getZi())
            .map(c->new RetreatPlanner.Threat(c.x(),c.y())).toList();
        var route=RetreatPlanner.plan(scope,new Tile(human.player.getXi(),human.player.getYi()),positions,settings.retreatDistance());
        return !route.isEmpty()&&human.movement.start(route,scope,Pace.RUN,now);
    }
    public void transition(String phase,String reason,int nearby){
        HumanLog.log(HumanLog.Level.DEBUG,()->"survival key="+human.definition.key()+"; phase="+phase+"; reason="+reason
            +"; nearby="+nearby+"; health="+human.state.snapshot().vitals().health()+"; endurance="+human.state.snapshot().vitals().endurance());
    }
}
