package zombie.apocalipse.zombies;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import zombie.apocalipse.zombies.ZombieVictimPort.Victim;
import zombie.characters.IsoZombie;
import zombie.iso.Vector2;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.network.NetworkVariables.ZombieState;
import zombie.network.packets.character.ZombiePacket;
import zombie.popman.NetworkZombieManager;

/** Main simulation thread only. A null connection plus a live session means server authority. */
public final class ServerZombieController {
    private static final ServerZombieController INSTANCE=new ServerZombieController();
    private static final boolean ENABLED=Boolean.parseBoolean(System.getProperty("apocbr.npc.zombieControl","true"));
    private static final int MAX_SESSIONS=128, MAX_ACQUIRES=8, MAX_OBSERVATIONS=512;
    private final IdentityHashMap<IsoZombie,ServerZombieSession> sessions=new IdentityHashMap<>();
    private final IdentityHashMap<IsoZombie,Motion> observations=new IdentityHashMap<>();
    private ZombieVictimPort victims;
    private List<Victim> targets=List.of();
    private long now, nextTargets;
    private int acquires;
    private boolean releasing;
    private static final class Motion {
        int instance;short id;float x,y,speed;long at,first,last,blockedUntil;
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
        now=System.nanoTime();acquires=0;
        if(victims==null || !ENABLED)return;
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
        if(releasing)return false;
        if(controls(zombie))return true;
        if(!ENABLED || now==0 || victims==null || targets.isEmpty() || acquires>=MAX_ACQUIRES || sessions.size()>=MAX_SESSIONS
            || zombie.isDead() || zombie.onlineId==-1)return false;
        boolean nearby=false;
        for(var candidate:targets)if(candidate.z()==zombie.getZi()
            && Math.hypot(candidate.x()-zombie.getX(),candidate.y()-zombie.getY())<12){nearby=true;break;}
        if(!nearby)return false;
        var motion=observations.get(zombie);
        if(motion!=null && motion.instance==zombie.getID() && motion.id==zombie.onlineId && now<motion.blockedUntil)return false;
        // Give a moving owner a short sampling window before taking over its usual pace.
        if(motion!=null && motion.speed==0 && now-motion.first<500_000_000L)return false;
        float pace=speed(zombie,motion);
        var adapter=new ServerZombieNativeAdapter(zombie,pace);
        if(!adapter.valid() || adapter.interrupted())return false;
        Victim choice=null;double nearest=12;
        for(var candidate:targets){
            double distance=adapter.distance(candidate);
            if(candidate.z()!=adapter.z() || distance>=nearest || adapter.playerTakesPriority(candidate) || !adapter.visible(candidate))continue;
            choice=candidate;nearest=distance;
        }
        if(choice==null)return false;
        var current=victims.resolve(choice.id());
        if(current==null || current.body()!=choice.body())return false;
        choice=current;
        sessions.put(zombie,new ServerZombieSession(adapter,choice,now));
        NetworkZombieManager.getInstance().moveZombie(zombie,null,null);
        adapter.target(choice);adapter.present(false,false);
        adapter.diagnostic("acquired","npc_target; target="+choice.id()+"; speed="+pace
            +"; speedSource="+(motion!=null && motion.speed>0?"owner_sample":"native_walk_fallback"));
        acquires++;
        return true;
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
                    || packet.realState==ZombieState.WalkTowardNetwork || packet.realState==ZombieState.LungeNetwork))sample.speed=measured;
            sample.x=packet.realX;sample.y=packet.realY;sample.at=stamp;
        }
        sample.last=stamp;
        // Native reaction packets keep authority with their existing simulator until they settle.
        String state=packet.realState.toString().toLowerCase(java.util.Locale.ROOT);
        if(!(state.equals("idle") || state.equals("walktoward") || state.equals("walktowardnetwork")
            || state.equals("walktoward-network") || state.equals("lunge") || state.equals("lungenetwork")
            || state.equals("lunge-network") || state.equals("pathfind")))
            sample.blockedUntil=Math.max(sample.blockedUntil,stamp+750_000_000L);
    }
    private float speed(IsoZombie zombie,Motion sample){
        if(sample!=null && sample.instance==zombie.getID() && sample.id==zombie.onlineId && sample.speed>0)return sample.speed;
        // Native walk-speed helper retains per-zombie speedMod, inactive and sprinter rules.
        boolean running=zombie.running;
        Vector2 step=new Vector2(1,0);
        try{zombie.getZombieWalkTowardSpeed(.025F,1,step);return Math.clamp(step.getLength()*30F,.1F,4F);}
        finally{zombie.running=running;}
    }
    /** Native weapon damage owns reactions. Cancel our strike before native consequences run. */
    public void hit(IsoZombie zombie){if(GameServer.server && controls(zombie))release(zombie,"weapon_hit",System.nanoTime());}
    private void release(IsoZombie zombie,String reason,long stamp){
        var session=sessions.remove(zombie);if(session==null)return;
        session.nativeBody.diagnostic("released",reason);
        if(zombie.getID()!=session.nativeBody.instanceId || zombie.onlineId!=session.nativeBody.onlineId)return;
        var sample=observations.get(zombie);
        if(sample==null && observations.size()<MAX_OBSERVATIONS){sample=new Motion();sample.instance=zombie.getID();sample.id=zombie.onlineId;observations.put(zombie,sample);}
        if(sample!=null){sample.blockedUntil=stamp+2_000_000_000L;sample.last=stamp;}
        if(zombie.target==session.identity.body())zombie.setTarget(null);
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
        for(var entry:sessions.entrySet())if(controls(entry.getKey()))output.add(entry.getKey());
    }
    public void reset(){
        for(var zombie:new ArrayList<>(sessions.keySet()))release(zombie,"shutdown",System.nanoTime());
        observations.clear();targets=List.of();nextTargets=0;victims=null;
    }
}
