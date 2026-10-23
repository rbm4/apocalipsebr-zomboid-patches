package zombie.apocalipse.zombies;

import zombie.characters.IsoZombie;
import zombie.iso.Vector2;

/** Bounded owner-motion calibration; never learns speed from a single correction.
 * Native client paths use animation root motion, unavailable without an animation player. */
final class ServerZombiePace {
    private float candidate, scale=1;
    private int consistent;
    private long last;
    void sample(float measured,float reference,long now){
        if(!Float.isFinite(measured)||!Float.isFinite(reference)||reference<=0)return;
        float ratio=measured/reference;
        // Reject obstruction creep and correction bursts instead of capturing
        // either as a permanent gait. Calibrate normal root-motion variation only.
        if(ratio<.5F || ratio>1.25F){consistent=0;return;}
        if(now-last>1_500_000_000L || consistent==0 || Math.abs(ratio-candidate)>.2F){candidate=ratio;consistent=1;}
        else {candidate=candidate*.5F+ratio*.5F;consistent=Math.min(4,consistent+1);}
        last=now;
        if(consistent>=3)scale=consistent==3?candidate:scale*.75F+candidate*.25F;
    }
    boolean usable(long now){return consistent>=3 && now-last<=2_000_000_000L;}
    float speed(IsoZombie zombie,long now){return nativeSpeed(zombie)*(usable(now)?scale:1);}
    static float nativeSpeed(IsoZombie zombie){
        return nativeMotion(zombie).speed();
    }
    record Motion(float speed,boolean running) { }
    static Motion nativeMotion(IsoZombie zombie){
        boolean previous=zombie.running;
        Vector2 step=new Vector2(1,0);
        try{zombie.running=false;zombie.getZombieWalkTowardSpeed(.025F,1,step);return new Motion(Math.clamp(step.getLength()*30F,.1F,4F),zombie.running);}
        finally{zombie.running=previous;}
    }
}
