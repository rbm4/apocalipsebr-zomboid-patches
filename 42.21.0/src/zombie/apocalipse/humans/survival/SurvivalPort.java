package zombie.apocalipse.humans.survival;

import java.util.List;
import zombie.apocalipse.humans.brain.HumanSenses.Contact;

/** Simulation-thread route capability. Success means a validated route started, never that escape completed. */
public interface SurvivalPort {
    SurvivalPort UNSUPPORTED=(threats,settings,now)->false;
    boolean escape(List<Contact> threats,SurvivalSettings settings,long now);
    default boolean escape(List<Contact> threats,SurvivalSettings settings,long now,boolean elevatedExposure){return escape(threats,settings,now);}
    default Contact openingTarget(List<Contact> candidates){return null;}
    default void failed(boolean timedOut,long now){ }
    default String routeProblem(long now){return null;}
    default void transition(String phase,String reason,int nearby){ }
}
