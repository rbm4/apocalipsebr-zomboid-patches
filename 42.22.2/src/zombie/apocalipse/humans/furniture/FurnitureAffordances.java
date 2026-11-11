package zombie.apocalipse.humans.furniture;

import java.util.*;

/** Detached world observations. Tokens are transient, server-local and generation-qualified. */
public final class FurnitureAffordances {
    private FurnitureAffordances() { }
    public enum Kind { SIT }
    public enum Coverage { COMPLETE, PARTIAL, UNAVAILABLE }
    public record Token(long generation,int x,int y,int z,String slot) {
        public Token {if(generation<=0||x<0||y<0||z< -32||z>31)throw new IllegalArgumentException("Invalid furniture token");
            Objects.requireNonNull(slot);if(slot.isEmpty()||slot.length()>64)throw new IllegalArgumentException("Invalid slot");}
    }
    public record Position(float x,float y,int z) {
        public Position {if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||y<0||z< -32||z>31)throw new IllegalArgumentException("Invalid furniture position");}
    }
    public record Approach(Position position,String side) {
        public Approach {Objects.requireNonNull(position);
            if(!Set.of("Front","Left","Right").contains(side))throw new IllegalArgumentException("Invalid approach side");}
    }
    public record Candidate(Token token,Kind kind,List<Approach> approaches,String facing,boolean occupied,long sampledAt) {
        public Candidate {Objects.requireNonNull(token);Objects.requireNonNull(kind);approaches=List.copyOf(approaches);
            if(approaches.isEmpty()||approaches.size()>3||!Set.of("N","S","W","E").contains(facing))throw new IllegalArgumentException("Invalid seating orientation");
            if(approaches.stream().anyMatch(a->a.position().z()!=token.z()))throw new IllegalArgumentException("Approach floor differs");}
        public boolean fresh(long now){return now>=sampledAt&&now-sampledAt<=2_000_000_000L;}
    }
    public record Work(int squares,int objects) {
        public Work {if(squares<0||objects<0)throw new IllegalArgumentException("Invalid discovery work");}
    }
    public record Frame(boolean sampled,long sampledAt,List<Candidate> candidates,Coverage coverage,boolean truncated,Work work) {
        public static final Frame EMPTY=new Frame(false,0,List.of(),Coverage.UNAVAILABLE,false,new Work(0,0));
        public Frame {candidates=List.copyOf(candidates);Objects.requireNonNull(coverage);Objects.requireNonNull(work);
            if(candidates.size()>32)throw new IllegalArgumentException("Too many furniture candidates");}
        public boolean fresh(long now,long maxAge){return sampled&&now>=sampledAt&&now-sampledAt<=maxAge&&coverage!=Coverage.UNAVAILABLE;}
    }
}
