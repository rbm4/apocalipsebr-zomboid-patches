package zombie.apocalipse.humans.groups;

import java.util.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;

/** Shared, hysteretic regroup/re-entry intent. No actors, world queries or native references. */
public final class GroupCohesionCoordinator {
    public enum Mode { PATROL, HOLD, RETURN, REGROUP }
    public record Member(GroupPatrolCoordinator.Member pose,boolean inside) { }
    private Mode mode=Mode.PATROL;
    private Point rally;
    private long revision;
    public Mode mode(){return mode;}
    public Point rally(){return rally;}
    public long revision(){return revision;}
    public static double separationLimit(int maxSlot){return Math.max(12,4+4*((maxSlot+1)/2));}
    private void select(Mode value,Point point){
        if(mode!=value||!Objects.equals(rally,point)){mode=value;rally=point;revision++;}
    }
    public void update(List<Member> members,Point usableRally){
        if(members.isEmpty()||members.size()>8)throw new IllegalArgumentException("Invalid cohesion roster");
        if(members.stream().anyMatch(m->!m.pose().participating())){select(Mode.HOLD,null);return;}
        boolean outside=members.stream().anyMatch(m->!m.inside());
        int maxSlot=members.stream().mapToInt(m->m.pose().slot()).max().orElse(0);
        if(outside){select(Mode.RETURN,usableRally);return;}
        // Finish one common rally before resuming; do not release each member separately.
        if((mode==Mode.RETURN||mode==Mode.REGROUP)&&rally!=null&&!near(members,rally,Math.max(4,2+2*((maxSlot+1)/2)))){select(mode,usableRally);return;}
        double spread=0;
        for(var a:members)for(var b:members)spread=Math.max(spread,Math.hypot(a.pose().x()-b.pose().x(),a.pose().y()-b.pose().y()));
        if(spread>separationLimit(maxSlot)){select(Mode.REGROUP,usableRally);return;}
        select(Mode.PATROL,null);
    }
    private static boolean near(List<Member> members,Point point,double distance){
        return members.stream().allMatch(m->m.pose().z()==point.z()
            &&Math.hypot(m.pose().x()-(point.x()+.5),m.pose().y()-(point.y()+.5))<=distance);
    }
}
