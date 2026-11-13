package zombie.apocalipse.humans.rest;

import java.util.*;
import java.util.random.RandomGenerator;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.furniture.*;
import zombie.apocalipse.humans.furniture.FurnitureAffordances.*;
import zombie.apocalipse.humans.healing.HealingModule;
import zombie.apocalipse.humans.seating.HumanSeating;

/** Optional seated routine. Policy owns intent; the native service owns physical cleanup. */
public final class RestModule implements HumanModule {
    private final RestSettings settings;
    private final RandomGenerator random;
    private final Set<Token> refused=new HashSet<>();
    private HumanSeating.Execution execution;
    private Token target;
    private long nextAttempt,forgetRefused;
    private boolean scheduled,suspended,stopped,safe;
    public RestModule(RestSettings settings){this(settings,new Random());}
    public RestModule(RestSettings settings,RandomGenerator random){this.settings=Objects.requireNonNull(settings);this.random=Objects.requireNonNull(random);}
    public FurnitureDemand furnitureDemand(){return new FurnitureDemand(Set.of(Kind.SIT),settings.radius(),1000);}
    public boolean available(){return !stopped;}
    public void monitor(HumanObservation o,HumanActuator actuator){
        safe=HealingModule.safe(o,settings.clearTiles());
        if(!refused.isEmpty()&&o.nowNanos()>=forgetRefused)refused.clear();
    }
    private FurnitureSelector.Choice choice(HumanObservation o){
        return o.position()==null?null:FurnitureSelector.choose(o.furniture(),Kind.SIT,o.position(),o.nowNanos(),refused);
    }
    public boolean eligible(HumanObservation o){
        // Keep ownership for one update to cancel an unsafe active execution.
        return !stopped&&!suspended&&(execution!=null||(!scheduled||o.nowNanos()>=nextAttempt)
            &&o.movementAvailable()&&HealingModule.safe(o,settings.clearTiles())&&choice(o)!=null);
    }
    public void update(HumanObservation o,HumanActuator actuator){
        if(stopped||suspended)return;
        long now=o.nowNanos();
        if(!HealingModule.safe(o,settings.clearTiles())){cancel();defer(now,settings.retryMs());return;}
        if(execution==null){
            var selected=choice(o);if(selected==null)return;
            target=selected.candidate().token();
            long duration=random.nextLong(settings.minSeatMs(),settings.maxSeatMs()+1);
            execution=actuator.seating().begin(new HumanSeating.Request(target,selected.approach().side(),duration),now);
            if(execution==null){failed(now);return;}
        }
        var result=execution.update(now);
        if(result!=HumanSeating.Status.RUNNING){
            execution=null;
            if(result==HumanSeating.Status.SUCCEEDED){refused.clear();defer(now,settings.walkMs());}
            else failed(now);
        }
    }
    private void defer(long now,long ms){scheduled=true;nextAttempt=now+ms*1_000_000L;}
    private void failed(long now){
        if(refused.size()==32)refused.clear();
        refused.add(target);forgetRefused=now+settings.retryMs()*1_000_000L;
        // Try another observed seat before yielding the whole retry interval.
        defer(now,1000);
    }
    private void cancel(){if(execution!=null)execution.cancel();execution=null;target=null;}
    public void interrupt(long now,HumanActuator actuator){
        // A stable pose can serve greetings/dialogue/trade; approach and transitions cannot.
        if(execution!=null&&(!safe||!execution.seated())){cancel();defer(now,1000);}
    }
    public void suspend(long now,HumanActuator actuator){suspended=true;interrupt(now,actuator);}
    public void resume(HumanObservation.Identity visitor,long now,HumanActuator actuator){suspended=false;}
    public void stop(long now,HumanActuator actuator){stopped=true;cancel();}
}
