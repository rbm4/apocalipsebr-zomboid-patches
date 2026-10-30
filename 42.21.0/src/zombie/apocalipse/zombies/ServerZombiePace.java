package zombie.apocalipse.zombies;

import zombie.characters.IsoZombie;
import zombie.iso.Vector2;

/** Measured animation gait where available; bounded owner calibration otherwise. */
final class ServerZombiePace {
    // Unobstructed root/requested motion in the 2026-10-24 single-owner capture.
    // These are tiles per real second, not animation frames or packet intervals.
    static final float WALK_2_SPEED=1.0475F, LUNGE_2_SPEED=1.5227F;
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
    float speed(IsoZombie zombie,long now){return nativeSpeed(zombie)*(measured(zombie)||!usable(now)?1:scale);}
    static boolean measured(IsoZombie zombie){
        // Do not extrapolate this single capture to other speed mods or variants.
        return zombie.getSpeedType()==2 && "2".equals(zombie.getWalkType())
            && Math.abs(zombie.getSpeedMod()-1F)<.0001F && !zombie.inactive
            && !zombie.isCrawling() && !fallbackMotion(zombie).running();
    }
    static Motion motion(IsoZombie zombie,boolean lunge){
        return measured(zombie)?new Motion(lunge?LUNGE_2_SPEED:WALK_2_SPEED,false):fallbackMotion(zombie);
    }
    static float nativeSpeed(IsoZombie zombie){
        return nativeMotion(zombie).speed();
    }
    record Motion(float speed,boolean running) { }
    static Motion nativeMotion(IsoZombie zombie){
        return motion(zombie,false);
    }
    private static Motion fallbackMotion(IsoZombie zombie){
        boolean previous=zombie.running;
        Vector2 step=new Vector2(1,0);
        try{zombie.running=false;zombie.getZombieWalkTowardSpeed(.025F,1,step);return new Motion(Math.clamp(step.getLength()*30F,.1F,4F),zombie.running);}
        finally{zombie.running=previous;}
    }
}
