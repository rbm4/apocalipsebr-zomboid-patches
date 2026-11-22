package zombie.apocalipse.humans;

import java.util.HashSet;
import zombie.apocalipse.humans.brain.*;

/** The profile's navigation scope and actor floor define territory, independently of actor role or key. */
final class ServerHumanTerritory {
    private final ServerHuman human;
    ServerHumanTerritory(ServerHuman human){this.human=human;}
    boolean contains(float x,float y,float z){
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)||(int)Math.floor(z)!=human.definition.z())return false;
        return human.walking.permitted(new zombie.apocalipse.humans.movement.MovementGrid.Tile((int)Math.floor(x),(int)Math.floor(y)));
    }
    HumanTerritory observe(HumanSenses.Frame frame){
        var scope=human.walking.scope("background");
        if(scope==null||!scope.available())return HumanTerritory.UNAVAILABLE;
        var inside=new HashSet<HumanSenses.Identity>();
        for(var contact:frame.contacts())if(contains(contact.x(),contact.y(),contact.z()))inside.add(contact.identity());
        return new HumanTerritory(true,contains(human.player.getX(),human.player.getY(),human.player.getZ()),inside);
    }
}
