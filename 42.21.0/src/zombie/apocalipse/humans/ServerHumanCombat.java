package zombie.apocalipse.humans;

import java.util.Objects;
import zombie.apocalipse.humans.brain.*;
import static zombie.apocalipse.humans.brain.HumanCombat.*;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;

/** Authoritative handoff/lease. Weapon and attack implementation is an injected backend. */
final class ServerHumanCombat implements Port {
    interface Backend { Execution begin(IsoPlayer actor,IsoZombie target,long executionId,Request request,long now); }
    private final ServerHuman human;
    private final Backend backend;
    private Session active;
    private long sequence;
    ServerHumanCombat(ServerHuman human){this(human,new ServerHumanWeaponBackend(human));}
    ServerHumanCombat(ServerHuman human,Backend backend){this.human=human;this.backend=Objects.requireNonNull(backend);}
    public Execution begin(Request request,long now){
        Objects.requireNonNull(request);
        if(active!=null)return finished(new Result(Status.REJECTED,Reason.BUSY));
        Reason refusal=validate(request,now);
        if(refusal!=Reason.NONE)return finished(new Result(Status.REJECTED,refusal));
        var target=human.perception.resolveZombie(request.target());
        long id=++sequence;
        final Execution delegate;
        try{delegate=Objects.requireNonNull(backend.begin(human.player,target,id,request,now));}
        catch(RuntimeException problem){return finished(new Result(Status.FAILED,Reason.BACKEND_FAILURE));}
        zombie.apocalipse.zombies.ServerZombieController.getInstance().engage(target,human.player.getOnlineID());
        active=new Session(id,request,delegate,now);
        HumanLog.log(HumanLog.Level.DEBUG,()->"combat begin key="+human.definition.key()+"; execution="+id+"; target="+request.target()+"; template="+request.template());
        return active;
    }
    private Reason validate(Request request,long now){
        var state=human.state.snapshot();
        if(state.dead()||state.injuries().immobile()||state.injuries().downed())return Reason.INCAPACITATED;
        var frame=human.perception.snapshot();
        if(!frame.sampled()||now<frame.sampledAtNanos()||now-frame.sampledAtNanos()>HumanCombat.MAX_SAMPLE_AGE_NANOS)return Reason.STALE_OBSERVATION;
        var target=human.perception.resolveZombie(request.target());
        if(target==null)return Reason.INVALID_TARGET;
        if(!human.territory.contains(human.player.getX(),human.player.getY(),human.player.getZ())
            ||!human.territory.contains(target.getX(),target.getY(),target.getZ()))return Reason.OUTSIDE_TERRITORY;
        return Reason.NONE;
    }
    private final class Session implements Execution {
        private final long id;
        private final Request request;
        private final Execution delegate;
        private final long startedAt;
        private Result result=new Result(Status.RUNNING,Reason.NONE);
        Session(long id,Request request,Execution delegate,long now){this.id=id;this.request=request;this.delegate=delegate;this.startedAt=now;}
        public Result update(long now){
            if(result.terminal())return result;
            if(now-startedAt>=HumanCombat.MAX_EXECUTION_NANOS){cancel(Reason.TIMEOUT);return result;}
            Reason refusal=validate(request,now);
            if(refusal!=Reason.NONE){cancel(refusal);return result;}
            // Retry after native reaction protection ends without restarting the
            // weapon execution or suppressing the legitimate hit reaction.
            var target=human.perception.resolveZombie(request.target());
            if(target!=null)zombie.apocalipse.zombies.ServerZombieController.getInstance().engage(target,human.player.getOnlineID());
            try{result=Objects.requireNonNull(delegate.update(now));}
            catch(RuntimeException problem){
                result=new Result(Status.FAILED,Reason.BACKEND_FAILURE);
                try{delegate.cancel(Reason.BACKEND_FAILURE);}catch(RuntimeException ignored){}
            }
            if(result.terminal())release();
            return result;
        }
        public void cancel(Reason reason){
            Objects.requireNonNull(reason);
            if(result.terminal())return;
            result=new Result(Status.CANCELLED,reason);
            try{delegate.cancel(reason);}catch(RuntimeException problem){result=new Result(Status.FAILED,Reason.BACKEND_FAILURE);}finally{release();}
        }
        private void release(){
            if(active!=this)return;
            active=null;
            HumanLog.log(HumanLog.Level.DEBUG,()->"combat end key="+human.definition.key()+"; execution="+id+"; result="+result);
        }
    }
    void stop(){if(active!=null)active.cancel(Reason.STOPPED);}
}
