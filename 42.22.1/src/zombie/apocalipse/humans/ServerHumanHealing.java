package zombie.apocalipse.humans;

import java.util.List;
import zombie.GameTime;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.healing.*;
import zombie.apocalipse.humans.state.HumanMutation;
import zombie.characters.BodyDamage.BodyPart;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;

/** Native work is restricted to Factory.serverMedicalTick on the registered server thread.
 * Server-owned policy renews dressing leases; passive recovery owns no movement execution. */
final class ServerHumanHealing implements HumanHealingPort {
    private record Demand(long token,long renewed,boolean cancelled) { }
    private record Result(long token,Status status) { }
    private static final List<String> DRESSINGS=List.of("Base.AlcoholBandage","Base.Bandage","Base.AlcoholRippedSheets","Base.RippedSheets");
    private final ServerHuman human;
    final HealingSettings settings;
    private volatile Snapshot snapshot=new Snapshot(false,0,false,false,false);
    private volatile Demand demand;
    private volatile Result result=new Result(0,Status.CANCELLED);
    private volatile long hurtAt=Long.MIN_VALUE;
    private volatile long combatAt=Long.MIN_VALUE;
    private volatile boolean closed;
    private long sequence,processing,nextBlood,serverNow,lastRecovery=Long.MIN_VALUE;
    private final java.util.Map<UdpConnection,Boolean> presented=new java.util.IdentityHashMap<>();
    private final java.util.concurrent.ArrayBlockingQueue<UdpConnection> announcements=new java.util.concurrent.ArrayBlockingQueue<>(256);
    private MedicalExecution execution;
    private String region;
    private boolean seeded,nativeWounds;
    private final HumanRecoveryState recovery=new HumanRecoveryState();
    boolean recovering(){return recovery.recovering();}
    ServerHumanHealing(ServerHuman human,HealingSettings settings){this.human=human;this.settings=settings;}
    public Snapshot snapshot(){return snapshot;}
    void hurt(long now){hurtAt=now;}
    void combat(long now){combatAt=now;}
    /** Transport identity only; no connection/world access or packet construction on the caller. */
    void requestAnnouncement(UdpConnection viewer){announcements.offer(viewer);}
    public Execution begin(long now){
        if(closed||!snapshot.available()||!snapshot.wounded()||!snapshot.supply()
            ||demand!=null&&result.token()==demand.token()&&result.status()==Status.RUNNING)return null;
        long token=++sequence;demand=new Demand(token,now,false);result=new Result(token,Status.RUNNING);
        return new Execution(){
            public Status update(long at){
                var observed=result;if(observed.token()!=token)return Status.CANCELLED;
                if(observed.status()!=Status.RUNNING)return observed.status();
                var current=demand;if(closed||current==null||current.token()!=token||current.cancelled())return Status.CANCELLED;
                demand=new Demand(token,at,false);return Status.RUNNING;
            }
            public void cancel(){var current=demand;if(current!=null&&current.token()==token)demand=new Demand(token,current.renewed(),true);}
        };
    }
    /** No native operation here; the server medical phase finalizes cancellation. */
    void close(){closed=true;var current=demand;if(current!=null)demand=new Demand(current.token(),current.renewed(),true);}
    private boolean ready(long now){
        var state=human.state.snapshot();
        var retaliate=ServerHumanRetaliation.module(human);
        return !closed&&!state.dead()&&!human.player.isDead()&&!state.injuries().downed()&&!state.injuries().immobile()
            &&human.activity.mode()==HumanActivityController.Mode.AUTONOMOUS
            &&(hurtAt==Long.MIN_VALUE||now>=hurtAt&&now-hurtAt>=settings.quietMs()*1_000_000L)
            &&(combatAt==Long.MIN_VALUE||now>=combatAt&&now-combatAt>=settings.quietMs()*1_000_000L)
            &&(retaliate==null||retaliate.aggressor()==null);
    }
    private boolean leased(long now){var d=demand;return d!=null&&!d.cancelled()&&d.token()==processing&&now>=d.renewed()&&now-d.renewed()<=500_000_000L;}
    private InventoryItem dressing(){
        var items=human.player.getInventory().getItems();InventoryItem best=null;int rank=DRESSINGS.size();
        for(int i=0;i<Math.min(items.size(),256);i++){
            var item=items.get(i);int candidate=DRESSINGS.indexOf(item.getFullType());
            if(candidate>=0&&candidate<rank&&!item.isInfected()){best=item;rank=candidate;}
        }
        return best;
    }
    private static boolean wound(BodyPart part){return !part.bandaged()&&(part.getBleedingTime()>0||part.getScratchTime()>0
        ||part.getCutTime()>0||part.getBiteTime()>0||part.getDeepWoundTime()>0||part.getBurnTime()>0);}
    private BodyPart target(){
        if(human.player.getBodyDamage()==null)return null;
        BodyPart best=null;
        for(var part:human.player.getBodyDamage().getBodyParts())if(wound(part)
            &&(best==null||part.getBleedingTime()>best.getBleedingTime()))best=part;
        return best;
    }
    private boolean logicalWound(){var i=human.state.snapshot().injuries();return i.bleedingPerSecond()>0||Math.abs(i.leg())>0||i.arms()>0;}
    static String region(BodyPartType type){
        return switch(type){
            case Head,Neck -> "Head";
            case Torso_Lower,Groin -> "LowerBody";
            case Hand_L,ForeArm_L,UpperArm_L -> "LeftArm";
            case Hand_R,ForeArm_R,UpperArm_R -> "RightArm";
            case UpperLeg_L,LowerLeg_L,Foot_L -> "LeftLeg";
            case UpperLeg_R,LowerLeg_R,Foot_R -> "RightLeg";
            default -> "UpperBody";
        };
    }
    private void presentation(UdpConnection viewer,boolean playing){
        new ServerHumanMedicalPacket(human,region==null?"UpperBody":region,playing).sendToClient(PacketTypes.PacketType.State,viewer);
        if(playing)presented.put(viewer,Boolean.TRUE);else presented.remove(viewer);
    }
    private void stopPresentation(){
        if(region==null)return;
        human.player.setPerformingAnAction(false);
        try{for(var viewer:human.viewers)if(human.isRelevant(viewer))presentation(viewer,false);}
        finally{region=null;}
    }
    /** Called under the factory monitor on the registered server thread, in both startup modes. */
    void serverTick(long now){
        serverNow=now;
        float seconds=lastRecovery==Long.MIN_VALUE?0:(float)Math.clamp((now-lastRecovery)/1_000_000_000.0,0,.15);
        lastRecovery=lastRecovery==Long.MIN_VALUE?now:Math.max(lastRecovery,now);
        if(closed){if(execution!=null)execution.cancel();stopPresentation();snapshot=new Snapshot(false,now,false,false,false);return;}
        if(!seeded){
            seeded=true;
            for(int i=0;i<settings.bandages();i++){
                var item=InventoryItemFactory.CreateItem("Base.Bandage");
                if(item==null)throw new IllegalStateException("NPC dressing item unavailable");
                human.player.getInventory().AddItem(item);
            }
        }
        // Native NPCs are detached from vanilla player Update. Observe wounds here; the logical
        // reducer remains the sole owner of ongoing health loss (never call BodyDamage.Update too).
        float rate=0;boolean any=false;
        if(human.player.getBodyDamage()!=null)for(var part:human.player.getBodyDamage().getBodyParts()){
            any|=part.getBleedingTime()>0||part.getScratchTime()>0||part.getCutTime()>0||part.getBiteTime()>0||part.getDeepWoundTime()>0||part.getBurnTime()>0;
            if(!part.bandaged()&&part.getBleedingTime()>0)rate+=.2857143F*part.getDamageScaler()*48F*(part.getBleedingTime()/10F)/100F;
        }
        if(any)nativeWounds=true;
        if(nativeWounds)human.state.dispatch(new HumanMutation.Bleeding(Math.clamp(rate*GameTime.getInstance().getTrueMultiplier()/(float)human.profile.healthMultiplier(),0,1)));
        if(human.profile.cheats().godMode()&&human.state.snapshot().injuries().bleedingPerSecond()>0)
            human.state.dispatch(new HumanMutation.Bleeding(0));
        if(human.state.snapshot().injuries().bleedingPerSecond()>0&&now>=nextBlood&&!human.profile.cheats().godMode()){
            nextBlood=now+2_000_000_000L;human.player.splatBloodFloor();
        }
        for(int count=0;count<256;count++){
            var viewer=announcements.poll();if(viewer==null)break;
            if(region!=null||presented.containsKey(viewer))presented.put(viewer,Boolean.FALSE);
        }
        var d=demand;
        if(d!=null&&d.token()!=processing){
            if(execution!=null)execution.cancel();processing=d.token();
            execution=d.cancelled()?null:create();
            if(execution==null)result=new Result(processing,Status.CANCELLED);
        }
        if(execution!=null){
            if(!leased(now)||!ready(now))execution.cancel();
            result=new Result(processing,execution.update(now));
        }
        presented.keySet().removeIf(viewer->!human.viewers.contains(viewer));
        for(var viewer:human.viewers){
            if(!human.isRelevant(viewer)){if(presented.containsKey(viewer))presented.put(viewer,Boolean.FALSE);continue;}
            if(region!=null&&!Boolean.TRUE.equals(presented.get(viewer)))presentation(viewer,true);
            else if(region==null&&presented.containsKey(viewer))presentation(viewer,false);
        }
        boolean wounded=target()!=null||!nativeWounds&&logicalWound();
        boolean before=recovery.recovering();recovery.update(human.state.snapshot(),wounded);
        if(before!=recovery.recovering())HumanLog.log(HumanLog.Level.DEBUG,()->"recovery key="+human.definition.key()+"; active="+recovery.recovering()+"; health="+human.state.snapshot().vitals().health());
        if(!wounded&&seconds>0&&ready(now)&&settings.recoveryPerSecond()>0
            &&(human.state.snapshot().vitals().health()<1||logicalWound())
            &&human.state.snapshot().injuries().bleedingPerSecond()==0
            &&HealingModule.safe(human.actuator.observe(now),settings.clearTiles()))recover(seconds);
        snapshot=new Snapshot(ready(now),now,wounded,true,
            !wounded&&(human.state.snapshot().vitals().health()<1||logicalWound())&&settings.recoveryPerSecond()>0);
    }
    private MedicalExecution create(){
        final BodyPart part=target();final boolean dressingNeeded=part!=null||!nativeWounds&&logicalWound();
        if(!dressingNeeded)return null;
        // Until inventory management is available, missing supplies use a clean field wrap.
        // It is a transaction-local fallback, never an item inserted into loot/inventory.
        final InventoryItem item=dressing();
        final String dressingType=item==null?"Base.RippedSheets":item.getFullType();
        final long injuryAt=hurtAt;
        final float x=human.player.getX(),y=human.player.getY();final int z=human.player.getZi();
        return new MedicalExecution(new MedicalExecution.World(){
            private boolean committed;
            private void trace(String phase){HumanLog.log(HumanLog.Level.DEBUG,()->"medical key="+human.definition.key()
                +"; token="+processing+"; phase="+phase+"; dressing="+dressingType+"; source="+(item==null?"fallback":"inventory"));}
            public boolean valid(){
                return leased(serverNow)&&ready(serverNow)&&hurtAt==injuryAt
                    &&human.player.getX()==x&&human.player.getY()==y&&human.player.getZi()==z
                    &&!human.movement.traversing()&&!human.movement.snapshot().moving()
                    &&(item==null||human.player.getInventory().getItemWithID(item.getID())==item&&!item.isInfected())
                    &&(part==null?logicalWound():wound(part));
            }
            public long durationNanos(){return !dressingNeeded?1_000_000_000L:
                (dressingType.contains("RippedSheets")?settings.sheetsMs():settings.bandageMs())*1_000_000L;}
            public boolean start(){
                if(dressingNeeded){
                    var injury=human.state.snapshot().injuries();
                    human.player.setPerformingAnAction(true);
                    region=part!=null?region(part.getType()):Math.abs(injury.leg())>0?(injury.leg()<0?"LeftLeg":"RightLeg"):injury.arms()>0?"LeftArm":"UpperBody";
                    for(var viewer:human.viewers)if(human.isRelevant(viewer))presentation(viewer,true);
                    trace("started");
                    human.speech.state(zombie.apocalipse.humans.speech.SpeechEvent.HEALING,serverNow);
                }
                return true;
            }
            public boolean commit(){
                if(dressingNeeded){
                    // Real supplies are consumed before network delivery; fallback wraps own no item.
                    if(item!=null)human.player.getInventory().Remove(item);
                    if(part!=null){
                        part.setBandaged(true,item==null?1:Math.max(1,item.getBandagePower()+1),item!=null&&item.isAlcoholic(),dressingType);
                        // Recompute from all remaining wounds immediately; a second wound continues bleeding.
                        float remaining=0;
                        for(var other:human.player.getBodyDamage().getBodyParts())if(!other.bandaged()&&other.getBleedingTime()>0)
                            remaining+=.2857143F*other.getDamageScaler()*48F*(other.getBleedingTime()/10F)/100F;
                        human.state.dispatch(new HumanMutation.Bleeding(Math.clamp(remaining*GameTime.getInstance().getTrueMultiplier()/(float)human.profile.healthMultiplier(),0,1)));
                    }else human.state.dispatch(new HumanMutation.Treat(1,1,1));
                    // Record native commitment before delivery: a send failure cannot undo treatment.
                    committed=true;trace("committed");
                    if(part!=null)for(var viewer:human.viewers)if(human.isRelevant(viewer))
                        INetworkPacket.send(viewer,PacketTypes.PacketType.BodyPartSync,part,0xc001966b8eL);
                }
                return true;
            }
            public void stop(){if(!committed)trace("cancelled");stopPresentation();}
        });
    }
    /** Passive recovery has no movement lease, stationary requirement or animation. */
    private void recover(float seconds){
        float before=human.state.snapshot().vitals().health();
        float amount=settings.recoveryPerSecond()*seconds;
        human.state.dispatch(new HumanMutation.Heal(amount/(float)human.profile.healthMultiplier()));
        float after=human.state.snapshot().vitals().health();
        if(after>before&&before<1&&human.player.getBodyDamage()!=null){
            float remaining=(1-after)/(1-before);
            for(var part:human.player.getBodyDamage().getBodyParts())part.SetHealth(100-(100-part.getHealth())*remaining);
        }
        human.state.dispatch(new HumanMutation.Treat(amount,amount,0));
        human.physiology.project();
    }
}
