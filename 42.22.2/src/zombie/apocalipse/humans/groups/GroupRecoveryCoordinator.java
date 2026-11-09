package zombie.apocalipse.humans.groups;

import java.util.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;

/** Detached recovery camp. Preserves one patient's identity and a small, stable roaming anchor. */
public final class GroupRecoveryCoordinator {
    public record Member(GroupPatrolCoordinator.Member pose,boolean recovering,boolean available) { }
    public record Goal(Point center,Point destination,boolean available,boolean dwelling,long revision) { }
    private String patient,incarnation;
    private Point center;
    private long started,revision,step;
    private boolean available;
    private static final int[][] RING={{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1}};
    public boolean active(){return patient!=null;}
    public String patient(){return patient;}
    public long revision(){return revision;}
    public void pause(){if(available){available=false;revision++;}}
    public void update(long now,List<Member> members){
        if(members.size()>8)throw new IllegalArgumentException("Invalid recovery roster");
        var chosen=members.stream().filter(m->m.recovering()&&Objects.equals(m.pose().key(),patient)&&Objects.equals(m.pose().incarnation(),incarnation)).findFirst()
            .orElseGet(()->members.stream().filter(Member::recovering).min(Comparator.comparingInt(m->m.pose().slot())).orElse(null));
        if(chosen==null){if(active()){patient=incarnation=null;center=null;revision++;}return;}
        var pose=chosen.pose();
        if(!Objects.equals(patient,pose.key())||!Objects.equals(incarnation,pose.incarnation())||center.z()!=pose.z()
            ||Math.hypot(pose.x()-(center.x()+.5),pose.y()-(center.y()+.5))>4){
            patient=pose.key();incarnation=pose.incarnation();center=new Point(Math.max(0,(int)Math.floor(pose.x())),Math.max(0,(int)Math.floor(pose.y())),pose.z());
            started=now;step=0;revision++;
        }
        boolean ready=members.stream().allMatch(m->m.available()&&m.pose().z()==center.z());
        long beat=Math.max(0,now-started)/10_000_000_000L;
        if(ready!=available||beat!=step){available=ready;step=beat;revision++;}
    }
    public Goal goal(String key,int slot,long now){
        if(!active())return null;
        int[] direction=RING[(int)Math.floorMod(step+slot*3L,8)];
        int radius=Objects.equals(patient,key)?1:3;
        var target=new Point(Math.max(0,center.x()+direction[0]*radius),Math.max(0,center.y()+direction[1]*radius),center.z());
        return new Goal(center,target,available,Math.max(0,now-started)%10_000_000_000L>=8_000_000_000L,revision);
    }
}
