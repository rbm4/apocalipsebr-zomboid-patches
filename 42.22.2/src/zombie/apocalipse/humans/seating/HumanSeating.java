package zombie.apocalipse.humans.seating;

import java.util.*;
import zombie.apocalipse.humans.furniture.FurnitureAffordances.Token;

/** Immutable intent and execution handles. No actor, object, transport or behavioral policy. */
public interface HumanSeating {
    enum Status { RUNNING, SUCCEEDED, FAILED, CANCELLED }
    enum Phase { APPROACH, SIT_DOWN, SEATED, WAIT_EXIT, STAND_UP, FINISHED }
    record Request(Token target,String side,long durationMs) {
        public Request {Objects.requireNonNull(target);
            if(!Set.of("Front","Left","Right").contains(side)||durationMs<1||durationMs>86_400_000L)throw new IllegalArgumentException("Invalid seating request");}
    }
    interface Execution { Status update(long now); void cancel(); default boolean seated(){return false;} }
    Execution begin(Request request,long now);
    HumanSeating UNSUPPORTED=(request,now)->null;
}
