package zombie.apocalipse.humans.tasks;

import zombie.apocalipse.humans.brain.HumanObservation.Identity;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;

/** Native reads are injected; a missing target is terminal, never rebound by display name. */
public interface HumanTaskWorld {
    Point position();
    Point locate(Identity target);
    boolean permitted(Point destination);
    default void changed(AssignedTaskModule.Snapshot snapshot) { }
    /** Pick a permitted stopping tile near a player, rather than routing onto their occupied tile. */
    default Point approach(Point target,Point here,int distance){
        Point best=null;long score=Long.MAX_VALUE;
        for(int dx=-distance;dx<=distance;dx++)for(int dy=-distance;dy<=distance;dy++){
            long x=(long)target.x()+dx,y=(long)target.y()+dy;
            if(dx==0&&dy==0||dx*dx+dy*dy>distance*distance||x<0||y<0||x>=Integer.MAX_VALUE||y>=Integer.MAX_VALUE)continue;
            var point=new Point((int)x,(int)y,target.z());
            long hx=(long)here.x()-x,hy=(long)here.y()-y,candidate=hx*hx+hy*hy;
            if(candidate<score&&permitted(point)){best=point;score=candidate;}
        }
        return best;
    }
    HumanTaskWorld UNSUPPORTED=new HumanTaskWorld(){
        public Point position(){return null;}
        public Point locate(Identity target){return null;}
        public boolean permitted(Point destination){return false;}
    };
}
