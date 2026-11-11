package zombie.apocalipse.humans.seating;

import java.util.Objects;
import zombie.apocalipse.humans.furniture.FurnitureReservations;
import zombie.apocalipse.humans.seating.HumanSeating.*;
import zombie.apocalipse.humans.seating.SeatingGeometry.Plan;

/** Per-request phase/lease ownership. The native service keeps ticking cancelled posture cleanup. */
public final class SeatingExecution {
    public enum Motion { RUNNING, SUCCEEDED, FAILED }
    public interface Port {
        boolean valid();
        Motion approach(long now);
        void sit();
        boolean sitting(double fraction);
        void seated();
        /** Try original side then other permitted sides, returning null while every exit is blocked. */
        Plan exit();
        void stand(Plan exit);
        boolean standing(double fraction);
        void end(boolean abort);
    }
    private final FurnitureReservations reservations;
    private final FurnitureReservations.Lease lease;
    private final Request request;
    private final Port port;
    private final long started;
    private long clock,phaseAt,nextExit;
    private Phase phase=Phase.APPROACH;
    private Status result=Status.RUNNING;
    private boolean cancelled,closed;
    private String reason="running";
    public SeatingExecution(Object owner,FurnitureReservations reservations,Request request,Port port,long now){
        this.reservations=Objects.requireNonNull(reservations);this.request=Objects.requireNonNull(request);this.port=Objects.requireNonNull(port);
        started=clock=phaseAt=now;lease=reservations.acquire(owner,request.target(),now);
        if(lease==null){phase=Phase.FINISHED;result=Status.FAILED;reason="seat_busy";closed=true;}
    }
    public Phase phase(){return phase;}
    public Status status(){return result;}
    public String reason(){return reason;}
    public boolean ownsPose(){return phase!=Phase.APPROACH&&phase!=Phase.FINISHED;}
    public void cancel(){if(result==Status.RUNNING){cancelled=true;if(phase==Phase.SEATED){phase=Phase.WAIT_EXIT;nextExit=clock;}}}
    public Status update(long now){
        if(result!=Status.RUNNING)return result;
        clock=Math.max(clock,now);
        try{
            if(!reservations.renew(lease,clock))return abort("lease_expired");
            if(!port.valid())return abort("world_or_actor_changed");
            switch(phase){
                case APPROACH->{
                    if(cancelled)return finish(Status.CANCELLED,false,"cancelled");
                    if(clock-started>=15_000_000_000L)return abort("approach_timeout");
                    var motion=port.approach(clock);
                    if(motion==Motion.FAILED)return abort("approach_failed");
                    if(motion==Motion.SUCCEEDED){phase=Phase.SIT_DOWN;phaseAt=clock;port.sit();}
                }
                case SIT_DOWN->{
                    double fraction=Math.clamp((double)(clock-phaseAt)/SeatingGeometry.TRANSITION_NANOS,0,1);boolean clear=port.sitting(fraction);
                    if(fraction>=1&&clear){port.seated();phase=cancelled?Phase.WAIT_EXIT:Phase.SEATED;phaseAt=clock;nextExit=clock;}
                }
                case SEATED->{if(cancelled||clock-phaseAt>=request.durationMs()*1_000_000L){phase=Phase.WAIT_EXIT;nextExit=clock;}}
                case WAIT_EXIT->{if(clock>=nextExit){nextExit=clock+1_000_000_000L;var exit=port.exit();if(exit!=null){phase=Phase.STAND_UP;phaseAt=clock;port.stand(exit);}}}
                case STAND_UP->{
                    double fraction=Math.clamp((double)(clock-phaseAt)/SeatingGeometry.TRANSITION_NANOS,0,1);boolean clear=port.standing(fraction);
                    if(fraction>=1&&clear)return finish(cancelled?Status.CANCELLED:Status.SUCCEEDED,false,cancelled?"cancelled":"completed");
                }
                default->{}
            }
            return result;
        }catch(RuntimeException problem){
            try{abort("native_exception");}catch(RuntimeException cleanup){problem.addSuppressed(cleanup);}
            throw problem;
        }
    }
    public Status abort(String why){return finish(Status.FAILED,true,why);}
    private Status finish(Status status,boolean abort,String why){
        if(closed)return result;closed=true;phase=Phase.FINISHED;result=status;reason=why;
        try{port.end(abort);}finally{reservations.release(lease);}
        return result;
    }
}
