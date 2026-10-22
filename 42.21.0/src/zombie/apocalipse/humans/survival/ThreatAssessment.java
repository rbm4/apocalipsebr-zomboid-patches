package zombie.apocalipse.humans.survival;

import java.util.List;
import zombie.apocalipse.humans.brain.*;
import static zombie.apocalipse.humans.brain.HumanSenses.*;

/** Bounded sensor interpretation. Threats outside patrol territory still count toward personal danger. */
public record ThreatAssessment(List<Contact> threats, boolean reliable, int nearby, double nearest, String pressure) {
    public ThreatAssessment { threats=List.copyOf(threats); }
    public static ThreatAssessment assess(HumanObservation observation,SurvivalSettings settings){
        var frame=observation.senses();long now=observation.nowNanos(),age=now-frame.sampledAtNanos();
        boolean reliable=frame.sampled()&&age>=0&&age<=500_000_000L&&frame.zombies()!=Coverage.UNAVAILABLE&&!frame.contactsTruncated();
        var threats=frame.contacts().stream().filter(c->c.identity().kind()==Kind.ZOMBIE
            &&now>=c.lastSeenNanos()&&now-c.lastSeenNanos()<=2_000_000_000L).limit(48).toList();
        int nearby=(int)threats.stream().filter(c->c.distanceSquared()<=settings.crowdRadius()*settings.crowdRadius()).count();
        double nearest=threats.stream().mapToDouble(Contact::distanceSquared).min().orElse(Double.POSITIVE_INFINITY);
        var vitals=observation.state().vitals();
        String reason=nearby>=settings.crowdCount()?"outnumbered":vitals.health()<=settings.retreatHealth()?"low_health"
            :vitals.endurance()<=settings.retreatEndurance()?"exhausted":"none";
        return new ThreatAssessment(threats,reliable,nearby,nearest,reason);
    }
    public boolean pressured(){return !pressure.equals("none");}
}
