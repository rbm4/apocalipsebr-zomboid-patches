package zombie.apocalipse.humans.rest;

import java.util.Map;
import java.util.Set;

/** Real-time durations; fresh runtime policy is composed separately for each actor. */
public record RestSettings(long minSeatMs,long maxSeatMs,long walkMs,long retryMs,int radius,int clearTiles) {
    public RestSettings {
        if(minSeatMs<1000||maxSeatMs<minSeatMs||maxSeatMs>86_400_000L||walkMs<1000||walkMs>86_400_000L
            ||retryMs<1000||retryMs>60000||radius<1||radius>12||clearTiles<1||clearTiles>24)
            throw new IllegalArgumentException("Invalid rest settings");
    }
    public static RestSettings from(Map<String,Long> p){
        if(!Set.of("minSeatMs","maxSeatMs","walkMs","retryMs","radius","clearTiles").containsAll(p.keySet()))
            throw new IllegalArgumentException("Unknown rest setting");
        return new RestSettings(p.getOrDefault("minSeatMs",600000L),p.getOrDefault("maxSeatMs",1200000L),
            p.getOrDefault("walkMs",30000L),p.getOrDefault("retryMs",30000L),
            Math.toIntExact(p.getOrDefault("radius",8L)),Math.toIntExact(p.getOrDefault("clearTiles",8L)));
    }
}
