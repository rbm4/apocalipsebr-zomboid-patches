package zombie.apocalipse.humans;

import java.util.HashSet;
import zombie.apocalipse.humans.brain.*;

/** First territory adapter: the loaded origin building and its origin floor. */
final class ServerHumanTerritory {
    private final ServerHuman human;
    ServerHumanTerritory(ServerHuman human){this.human=human;}
    boolean contains(float x,float y,float z){
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)||(int)Math.floor(z)!=human.definition.z())return false;
        var cell=human.player.getCell();
        var home=cell.getGridSquare((int)Math.floor(human.definition.x()),(int)Math.floor(human.definition.y()),human.definition.z());
        var square=cell.getGridSquare((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));
        return home!=null&&home.getBuildingDef()!=null&&square!=null&&square.getBuildingDef()==home.getBuildingDef();
    }
    HumanTerritory observe(HumanSenses.Frame frame){
        var home=human.player.getCell().getGridSquare((int)Math.floor(human.definition.x()),(int)Math.floor(human.definition.y()),human.definition.z());
        if(home==null||home.getBuildingDef()==null)return HumanTerritory.UNAVAILABLE;
        var inside=new HashSet<HumanSenses.Identity>();
        for(var contact:frame.contacts())if(contains(contact.x(),contact.y(),contact.z()))inside.add(contact.identity());
        return new HumanTerritory(true,contains(human.player.getX(),human.player.getY(),human.player.getZ()),inside);
    }
}
