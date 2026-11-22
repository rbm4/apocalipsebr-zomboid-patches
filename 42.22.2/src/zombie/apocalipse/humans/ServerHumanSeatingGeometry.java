package zombie.apocalipse.humans;

import java.util.*;
import zombie.apocalipse.humans.furniture.FurnitureAffordances.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.humans.seating.SeatingGeometry;
import zombie.apocalipse.humans.seating.SeatingGeometry.Plan;
import zombie.iso.*;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.network.GameServer;
import zombie.pathfind.PolygonalMap2;

/** Native validation only: scope, occupancy and bounded collision queries. No rest policy. */
final class ServerHumanSeatingGeometry {
    private final ServerHuman human;private final ServerHumanFurniture furniture;
    ServerHumanSeatingGeometry(ServerHuman human,ServerHumanFurniture furniture){this.human=human;this.furniture=furniture;}
    Plan resolve(Token token,String side,long now){
        human.requireAuthority();var object=furniture.resolve(token);var candidate=furniture.candidate(token,now);
        if(object==null||candidate==null||object.getSquare().getObjects().size()>128||!sameRoom(object))return null;
        var square=object.getSquare();var plan=SeatingGeometry.plan(candidate,side,square.isSolid()||square.isSolidTrans());
        if(plan==null||!free(plan.approach())||!free(plan.exit())||!passage(plan.approach(),plan.seat())||!passage(plan.seat(),plan.exit()))return null;
        // Only the owned seat may account for solid furniture on the mechanical transition tile.
        for(var other:square.getObjects())if(other!=object&&other.getProperties()!=null
            &&(other.getProperties().has(IsoFlagType.solid)||other.getProperties().has(IsoFlagType.solidtrans)))return null;
        return plan;
    }
    boolean sameRoom(IsoObject object){
        var origin=human.player.getCell().getGridSquare(human.player.getXi(),human.player.getYi(),human.player.getZi());
        return origin!=null&&origin.getRoom()!=null&&object.getSquare()!=null&&origin.getRoom()==object.getSquare().getRoom()
            &&object.getSquare().z==human.definition.z();
    }
    boolean occupied(IsoObject object){
        if(GameServer.Players.size()>128)return true;
        for(var player:GameServer.Players)if(player!=null&&player!=human.player&&!player.isDead()&&player.getSitOnFurnitureObject()==object)return true;
        for(var player:furniture.bodies())if(player!=human.player&&!player.isDead()&&player.getSitOnFurnitureObject()==object)return true;
        return false;
    }
    boolean free(Position point){
        var tile=new Tile((int)Math.floor(point.x()),(int)Math.floor(point.y()));
        var square=human.movement.square(tile.x(),tile.y());
        return point.z()==human.definition.z()&&square!=null&&square.getRoom()!=null&&human.walking.permitted(tile)
            &&PolygonalMap2.instance.canStandAt(point.x(),point.y(),point.z(),human.player,false,true)
            &&bodies(point,point);
    }
    boolean passage(Position from,Position to){
        if(from.z()!=to.z()||SeatingGeometry.distance(from,to)>1.7)return false;
        var result=LosUtil.lineClear(human.player.getCell(),(int)Math.floor(from.x()),(int)Math.floor(from.y()),from.z(),
            (int)Math.floor(to.x()),(int)Math.floor(to.y()),to.z(),false);
        return result!=LosUtil.TestResults.Blocked&&result!=LosUtil.TestResults.ClearThroughClosedDoor&&result!=LosUtil.TestResults.ClearThroughWindow;
    }
    boolean bodies(Position from,Position to){
        if(!human.movement.bodyClear(from.x(),from.y(),to.x(),to.y()))return false;
        double dx=to.x()-from.x(),dy=to.y()-from.y(),length=dx*dx+dy*dy;
        for(var other:furniture.bodies())if(other!=human.player&&!other.isDead()&&other.getZi()==from.z()){
            double t=length==0?0:Math.clamp(((other.getX()-from.x())*dx+(other.getY()-from.y())*dy)/length,0,1);
            if(Math.hypot(from.x()+t*dx-other.getX(),from.y()+t*dy-other.getY())<.65)return false;
        }
        return true;
    }
}
