package zombie.apocalipse.zombies;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import zombie.apocalipse.zombies.ZombieVictimPort.Victim;
import zombie.apocalipse.humans.HumanLog;
import zombie.characters.IsoZombie;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.network.NetworkVariables.ZombieState;
import zombie.network.packets.character.ZombiePacket;
import zombie.popman.NetworkZombieManager;

/** Main simulation thread only. A null connection plus a live session means server authority. */
public final class ServerZombieController {
    private static final ServerZombieController INSTANCE=new ServerZombieController();
    private static final boolean ENABLED=Boolean.parseBoolean(System.getProperty("apocbr.npc.zombieControl","true"));
    private static final int MAX_SESSIONS=128, MAX_OBSERVATIONS=512;
    private final IdentityHashMap<IsoZombie,ServerZombieSession> sessions=new IdentityHashMap<>();
    private final IdentityHashMap<IsoZombie,Motion> observations=new IdentityHashMap<>();
    private ZombieVictimPort victims;
    private List<Victim> targets=List.of();
    private long now, nextTargets;
    private boolean releasing;
    private static final class Motion {
        int instance;short id;float x,y,speed;long at,first,last,blockedUntil,nextDiagnostic;
        final ServerZombiePace pace=new ServerZombiePace();
    }
    private ServerZombieController() { }
    public static ServerZombieController getInstance(){return INSTANCE;}
    public void install(ZombieVictimPort port){victims=java.util.Objects.requireNonNull(port);}
    public boolean controls(IsoZombie zombie){
        var session=sessions.get(zombie);
        return GameServer.server && session!=null && session.nativeBody.onlineId==zombie.onlineId
            && session.nativeBody.instanceId==zombie.getID();
    }
    /** Once per packer auth frame, before per-zombie ownership reassessment. */
    public void beginFrame(){
        now=System.nanoTime();
        if(victims==null || !ENABLED)return;
        ServerZombiePath.update();
        if(now>=nextTargets){targets=List.copyOf(victims.snapshot());nextTargets=now+100_000_000L;}
        for(var entry:new ArrayList<>(sessions.entrySet())){
            var session=entry.getValue();
            String reason=session.update(victims.resolve(session.identity.id()),victims,now);
            if(reason!=null)release(entry.getKey(),reason,now);
        }
        observations.entrySet().removeIf(e->now-e.getValue().last>10_000_000_000L);
    }
    /** Called inside the existing native ownership traversal; never scans the population again. */
    public boolean retainOrAcquire(IsoZombie zombie){
        return acquire(zombie,targets,false,now);
    }
    /** Validated NPC combat intent is direct evidence of mutual hostility. */
    public boolean engage(IsoZombie zombie,short victimId){
        if(!GameServer.server || victims==null)return false;
        var victim=victims.resolve(victimId);
        long stamp=System.nanoTime();
        return victim==null?rejected(zombie,"npc_ineligible",stamp):acquire(zombie,List.of(victim),true,stamp);
    }
    private boolean acquire(IsoZombie zombie,List<Victim> candidates,boolean engaged,long stamp){
        if(releasing)return false;
        if(controls(zombie)){
            var session=sessions.get(zombie);
            if(engaged && candidates.stream().anyMatch(v->v.body()==session.identity.body()))session.engaged=true;
            return true;
        }
        if(!GameServer.server || !ENABLED || stamp==0 || victims==null || candidates.isEmpty()
            || zombie.isDead() || zombie.onlineId==-1)return false;
        boolean nearby=false;
        for(var candidate:candidates)if(candidate.z()==zombie.getZi()
            && Math.hypot(candidate.x()-zombie.getX(),candidate.y()-zombie.getY())<12){nearby=true;break;}
        if(!nearby)return false;
        if(sessions.size()>=MAX_SESSIONS)return rejected(zombie,"session_limit",stamp);
        var motion=observations.get(zombie);
        if(motion!=null && motion.instance==zombie.getID() && motion.id==zombie.onlineId && stamp<motion.blockedUntil)
            return rejected(zombie,"reaction_or_release_cooldown",stamp);
        // Take the best existing sample or native fallback; sampling must not add
        // another delay after the native two-second ownership interval.
        float pace=speed(zombie,motion);
        var adapter=new ServerZombieNativeAdapter(zombie,pace);
        if(!adapter.valid())return rejected(zombie,"invalid_body",stamp);
        if(adapter.needsNativeTraversal())return rejected(zombie,"native_stairs",stamp);
        if(adapter.interrupted())return rejected(zombie,"native_reaction_or_state",stamp);
        Victim choice=null;double nearest=12;
        String refusal="no_sight_or_proximity";
        for(var candidate:candidates){
            double distance=adapter.distance(candidate);
            if(candidate.z()!=adapter.z() || distance>=nearest)continue;
            if(!engaged && adapter.playerTakesPriority(candidate)){refusal="closer_player";continue;}
            // Close proximity can trigger a native route request even behind furniture.
            // It does not authorize an attack or movement through a blocked segment.
            if(!adapter.visible(candidate) && distance>=6)continue;
            choice=candidate;nearest=distance;
        }
        if(choice==null)return rejected(zombie,refusal,stamp);
        // Normal roaming ownership cooldown cannot suppress an immediate local
        // threat, or an NPC which has already committed to fighting this zombie.
        boolean immediate=adapter.immediate(choice);
        if(!engaged && !immediate && zombie.getOwner()!=null && System.currentTimeMillis()-zombie.lastChangeOwner<2000L)
            return rejected(zombie,"owner_cooldown",stamp);
        var current=victims.resolve(choice.id());
        if(current==null || current.body()!=choice.body())return rejected(zombie,"victim_changed",stamp);
        choice=current;
        sessions.put(zombie,new ServerZombieSession(adapter,choice,stamp,engaged));
        NetworkZombieManager.getInstance().moveZombie(zombie,null,null);
        adapter.target(choice);adapter.present(false,false);
        adapter.diagnostic("acquired",(engaged?"npc_combat":immediate?"npc_proximity":"npc_target")+"; target="+choice.id()+"; speed="+pace
            +"; speedSource="+(ServerZombiePace.measured(zombie)?"captured_walk_2"
                :motion!=null && motion.pace.usable(stamp)?"filtered_owner_sample":"native_walk_fallback"));
        return true;
    }
    private boolean rejected(IsoZombie zombie,String reason,long stamp){
        var sample=observations.get(zombie);
        if(sample==null || sample.instance!=zombie.getID() || sample.id!=zombie.onlineId){
            if(sample==null && observations.size()>=MAX_OBSERVATIONS)return false;
            sample=new Motion();sample.instance=zombie.getID();sample.id=zombie.onlineId;
            sample.first=sample.at=stamp;sample.x=zombie.getX();sample.y=zombie.getY();observations.put(zombie,sample);
        }
        sample.last=stamp;
        if(stamp>=sample.nextDiagnostic){
            sample.nextDiagnostic=stamp+2_000_000_000L;
            HumanLog.log(HumanLog.Level.DEBUG,()->"zombie acquisition id="+zombie.onlineId
                +"; instance="+zombie.getID()+"; rejected="+reason+"; state="
                +(zombie.getCurrentState()==null?"null":zombie.getCurrentState().getClass().getSimpleName())
                +"; position="+zombie.getX()+","+zombie.getY());
        }
        return false;
    }
    /** Observe accepted owner packets only; use real positions rather than predicted goals. */
    public void received(IsoZombie zombie,ZombiePacket packet){
        if(controls(zombie))return;
        boolean nearby=false;
        for(var candidate:targets)if(candidate.z()==packet.realZ
            && Math.hypot(candidate.x()-packet.realX,candidate.y()-packet.realY)<24){nearby=true;break;}
        if(!nearby)return;
        long stamp=System.nanoTime();
        var sample=observations.get(zombie);
        if(sample==null){
            if(observations.size()>=MAX_OBSERVATIONS)return;
            sample=new Motion();sample.id=zombie.onlineId;sample.instance=zombie.getID();sample.x=packet.realX;sample.y=packet.realY;sample.at=sample.first=stamp;
            observations.put(zombie,sample);
        }
        if(sample.id!=zombie.onlineId || sample.instance!=zombie.getID()){
            observations.remove(zombie);received(zombie,packet);return;
        }
        long elapsed=stamp-sample.at;
        if(elapsed>=200_000_000L){
            float measured=(float)(Math.hypot(packet.realX-sample.x,packet.realY-sample.y)/(elapsed/1_000_000_000.0));
            if(elapsed<=1_500_000_000L && measured>=.1F && measured<=4F
                && (packet.realState==ZombieState.WalkToward || packet.realState==ZombieState.PathFind || packet.realState==ZombieState.Lunge
                    || packet.realState==ZombieState.WalkTowardNetwork || packet.realState==ZombieState.LungeNetwork)){
                sample.pace.sample(measured,ServerZombiePace.nativeSpeed(zombie),stamp);
                sample.speed=sample.pace.usable(stamp)?measured:0;
            }
            sample.x=packet.realX;sample.y=packet.realY;sample.at=stamp;
        }
        sample.last=stamp;
        // Native reaction packets keep authority with their existing simulator until they settle.
        String state=packet.realState.toString().toLowerCase(java.util.Locale.ROOT);
        if(!(state.equals("idle") || state.equals("turnalerted") || state.equals("facetarget") || state.equals("face-target")
            || state.equals("walktoward") || state.equals("walktowardnetwork")
            || state.equals("walktoward-network") || state.equals("lunge") || state.equals("lungenetwork")
            || state.equals("lunge-network") || state.equals("pathfind")))
            sample.blockedUntil=Math.max(sample.blockedUntil,stamp+750_000_000L);
    }
    private float speed(IsoZombie zombie,Motion sample){
        if(sample!=null && sample.instance==zombie.getID() && sample.id==zombie.onlineId)return sample.pace.speed(zombie,System.nanoTime());
        return ServerZombiePace.nativeSpeed(zombie);
    }
    /** Native weapon damage owns reactions. Cancel our strike before native consequences run. */
    public void hit(IsoZombie zombie){if(GameServer.server && controls(zombie))release(zombie,"weapon_hit",System.nanoTime());}
    private void release(IsoZombie zombie,String reason,long stamp){
        var session=sessions.remove(zombie);if(session==null)return;
        session.nativeBody.close();
        session.nativeBody.diagnostic("released",reason);
        if(zombie.getID()!=session.nativeBody.instanceId || zombie.onlineId!=session.nativeBody.onlineId)return;
        var sample=observations.get(zombie);
        if(sample==null && observations.size()<MAX_OBSERVATIONS){sample=new Motion();sample.instance=zombie.getID();sample.id=zombie.onlineId;observations.put(zombie,sample);}
        if(sample!=null){sample.blockedUntil=stamp+2_000_000_000L;sample.last=stamp;}
        if(!reason.equals("native_stairs") && zombie.target==session.identity.body())zombie.setTarget(null);
        zombie.getNetworkCharacterAI().resetSpeedLimiter();
        zombie.lastChangeOwner=0; // A release must not wait for the ordinary transfer cooldown.
        if(ServerMap.instance.zombieMap.get(zombie.onlineId)==zombie){
            releasing=true;
            try{NetworkZombieManager.getInstance().updateAuth(zombie);}
            finally{releasing=false;}
        }
    }
    public void prepare(IsoZombie zombie,ZombiePacket packet){
        if(controls(zombie))sessions.get(zombie).nativeBody.packet(packet);
    }
    public void addRelays(java.util.Collection<IsoZombie> output){
        for(var entry:sessions.entrySet())if(controls(entry.getKey()) && entry.getValue().nativeBody.relayDue(now))output.add(entry.getKey());
    }
    public void reset(){
        for(var zombie:new ArrayList<>(sessions.keySet()))release(zombie,"shutdown",System.nanoTime());
        observations.clear();targets=List.of();nextTargets=0;victims=null;
    }
}
