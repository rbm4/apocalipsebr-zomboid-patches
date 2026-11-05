package zombie.apocalipse.humans.groups;

import java.util.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;

/** One bounded, transient shared PATROL intent. Inputs and outputs are detached values only. */
public final class GroupPatrolCoordinator {
    public enum Intent { PATROL }
    public Intent intent(){return Intent.PATROL;}
    public enum Phase { MOVING, REGROUPING, STALLED, DWELLING }
    public record Member(String key,String incarnation,int slot,float x,float y,int z,boolean participating) { }
    public record Goal(int waypoint,Point center,Point destination,boolean available,boolean dwelling) {
        public Goal(int waypoint,Point center,Point destination,boolean available){this(waypoint,center,destination,available,false);}
    }
    public static long randomDwellMillis(){return java.util.concurrent.ThreadLocalRandom.current().nextLong(15_000,45_001);}
    private final List<Point> route;
    private final int spacing,arrival;
    private final java.util.function.LongSupplier dwellMillis;
    private int cursor;
    private long waiting=Long.MIN_VALUE;
    private long remainingDwell=Long.MIN_VALUE,lastDwellUpdate=Long.MIN_VALUE;
    private Phase phase=Phase.MOVING;
    public GroupPatrolCoordinator(List<Point> route,int spacing,int arrival){
        this(route,spacing,arrival,GroupPatrolCoordinator::randomDwellMillis);
    }
    public GroupPatrolCoordinator(List<Point> route,int spacing,int arrival,java.util.function.LongSupplier dwellMillis){
        this.route=List.copyOf(route);this.spacing=spacing;this.arrival=arrival;
        this.dwellMillis=Objects.requireNonNull(dwellMillis);
        if(route.isEmpty()||route.size()>32||route.stream().map(Point::z).distinct().count()!=1||spacing<1||spacing>4||arrival<1||arrival>4)
            throw new IllegalArgumentException("Invalid group patrol");
    }
    public List<Point> route(){return route;}
    public Phase phase(){return phase;}
    public int waypoint(){return cursor;}
    public Point destination(int slot){
        var center=route.get(cursor);var previous=route.get((cursor+route.size()-1)%route.size());
        double dx=center.x()-previous.x(),dy=center.y()-previous.y(),length=Math.max(1,Math.hypot(dx,dy));
        int rank=(slot+1)/2,side=slot==0?0:slot%2==1?-1:1;
        return new Point(Math.max(0,center.x()+(int)Math.round(-dy/length*spacing*rank*side)),
            Math.max(0,center.y()+(int)Math.round(dx/length*spacing*rank*side)),center.z());
    }
    public boolean arrived(Member member){var point=destination(member.slot());var center=route.get(cursor);return member.z()==point.z()
        &&Math.min(Math.hypot(member.x()-(point.x()+.5),member.y()-(point.y()+.5)),
            Math.hypot(member.x()-(center.x()+.5),member.y()-(center.y()+.5)))<=arrival;}
    public void update(long now,List<Member> members){
        if(members.size()>8||members.stream().map(Member::slot).distinct().count()!=members.size())throw new IllegalArgumentException("Duplicate/oversized patrol roster");
        var active=members.stream().filter(Member::participating).toList();
        if(active.isEmpty()){waiting=Long.MIN_VALUE;pauseDwell();return;}
        boolean any=active.stream().anyMatch(this::arrived),all=members.stream().allMatch(this::arrived);
        if(all){
            waiting=Long.MIN_VALUE;phase=Phase.DWELLING;
            if(remainingDwell==Long.MIN_VALUE){
                long duration=dwellMillis.getAsLong();
                if(duration<15_000||duration>45_000)throw new IllegalArgumentException("Patrol dwell must be 15-45 seconds");
                remainingDwell=duration*1_000_000L;
            }else if(active.size()==members.size()&&lastDwellUpdate!=Long.MIN_VALUE)
                remainingDwell-=Math.max(0,now-lastDwellUpdate);
            lastDwellUpdate=active.size()==members.size()?now:Long.MIN_VALUE;
            if(remainingDwell<=0){cursor=(cursor+1)%route.size();remainingDwell=Long.MIN_VALUE;pauseDwell();phase=Phase.MOVING;}
        }else {
            remainingDwell=Long.MIN_VALUE;pauseDwell();
            if(any){if(waiting==Long.MIN_VALUE)waiting=now;phase=now-waiting>=20_000_000_000L?Phase.STALLED:Phase.REGROUPING;}
            else {waiting=Long.MIN_VALUE;phase=Phase.MOVING;}
        }
    }
    public void pauseDwell(){lastDwellUpdate=Long.MIN_VALUE;}
    public Goal goal(int slot,boolean available){return new Goal(cursor,route.get(cursor),destination(slot),available,phase==Phase.DWELLING);}
}
