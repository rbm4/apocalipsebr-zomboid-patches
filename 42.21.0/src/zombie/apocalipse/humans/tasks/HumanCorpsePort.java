package zombie.apocalipse.humans.tasks;

import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;

/** Simulation-thread corpse mechanics. Tokens are activation-local, never dialogue/client identifiers. */
public interface HumanCorpsePort {
    record Body(long token,Point position) { }
    record Search(Body body,boolean complete,boolean loaded) { }
    default boolean supported(){return true;}
    boolean permitted(Point point);
    String movementSlot();
    Search find(Point center,int radius,Point destination);
    Body resolve(long token);
    boolean claim(long token);
    boolean move(long token,Point point);
    void release(long token);
    HumanCorpsePort UNSUPPORTED=new HumanCorpsePort(){
        public boolean supported(){return false;}
        public boolean permitted(Point p){return false;}
        public String movementSlot(){return "background";}
        public Search find(Point c,int r,Point d){return new Search(null,true,false);}
        public Body resolve(long t){return null;}
        public boolean claim(long t){return false;}
        public boolean move(long t,Point p){return false;}
        public void release(long t){}
    };
}
