package zombie.apocalipse.humans.traversal;

import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoZombieGiblets;

/** Shared native occupancy filter for routes, prediction and traversal landings. */
public final class BodyOccupancy {
    private BodyOccupancy() { }
    public static boolean blocks(IsoMovingObject body,IsoMovingObject self){
        if(body==self||body instanceof IsoDeadBody||body instanceof IsoZombieGiblets)return false;
        if(body instanceof zombie.characters.IsoZombie z&&z.isReanimatedForGrappleOnly()
            &&self instanceof IsoGameCharacter actor&&actor.getGrapplingTarget()==z&&z.getGrappledBy()==actor)return false;
        return !(body instanceof IsoGameCharacter c)||!c.isDead()&&c.getHealth()>0;
    }
    public static boolean free(IsoGridSquare square,IsoMovingObject self){
        if(!square.isFree(false))return false;
        if(square.isFree(true))return true;
        var bodies=square.getMovingObjects();
        if(bodies.isEmpty()||bodies.size()>64)return false;
        for(var body:bodies)if(blocks(body,self))return false;
        return true;
    }
}
