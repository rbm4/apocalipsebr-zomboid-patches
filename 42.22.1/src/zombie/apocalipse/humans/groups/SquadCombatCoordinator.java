package zombie.apocalipse.humans.groups;

import java.util.*;
import zombie.apocalipse.humans.brain.HumanSenses.Identity;

/** Detached squad threat board. One report per member; sensor generations remain reporter-local. */
public final class SquadCombatCoordinator {
    public static final long MEMORY_NANOS=8_000_000_000L;
    public static final double ASSIST_RANGE=20;
    public static final int DISTRESS_RANGE=256;
    public record Threat(Identity identity,float x,float y,int z,long observedAt) { }
    public record Report(String ally,String incarnation,Threat threat,boolean distress) {
        public Report(String ally,String incarnation,Threat threat){this(ally,incarnation,threat,false);}
    }
    public record Member(String key,String incarnation,float x,float y,int z,boolean helper,Threat threat,boolean distress) {
        public Member(String key,String incarnation,float x,float y,int z,boolean helper,Threat threat){this(key,incarnation,x,y,z,helper,threat,false);}
    }
    private final Map<String,Report> reports=new HashMap<>();
    public List<Report> reports(){return List.copyOf(reports.values());}
    public Map<String,Report> update(long now,List<Member> members,Set<Report> invalid){
        if(members.size()>8||members.stream().map(Member::key).distinct().count()!=members.size())
            throw new IllegalArgumentException("Invalid squad roster");
        reports.values().removeIf(r->invalid.contains(r)||now<r.threat().observedAt()||now-r.threat().observedAt()>MEMORY_NANOS
            ||members.stream().noneMatch(m->m.key().equals(r.ally())&&m.incarnation().equals(r.incarnation())));
        for(var m:members){var t=m.threat();
            if(t!=null&&t.identity().kind()==zombie.apocalipse.humans.brain.HumanSenses.Kind.ZOMBIE
                &&now>=t.observedAt()&&now-t.observedAt()<=500_000_000L)
                reports.put(m.key(),new Report(m.key(),m.incarnation(),t,m.distress()));
        }
        var orders=new HashMap<String,Report>();
        for(var m:members)if(m.helper()){
            reports.values().stream().filter(r->!r.ally().equals(m.key())&&r.threat().z()==m.z()
                &&distance(m,r)<=(r.distress()?DISTRESS_RANGE*(double)DISTRESS_RANGE:ASSIST_RANGE*ASSIST_RANGE))
                .min(Comparator.comparing((Report r)->!r.distress()).thenComparingDouble(r->distance(m,r)).thenComparing(Report::ally))
                .ifPresent(r->orders.put(m.key(),r));
        }
        return Map.copyOf(orders);
    }
    private static double distance(Member m,Report r){double dx=m.x()-r.threat().x(),dy=m.y()-r.threat().y();return dx*dx+dy*dy;}
}
