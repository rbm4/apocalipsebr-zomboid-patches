package zombie.apocalipse.humans.furniture;

import java.util.*;

/** Immutable capability demand. Modules declare needs; native composition aggregates them. */
public record FurnitureDemand(Set<FurnitureAffordances.Kind> kinds,int radius,int intervalMs) {
    public static final FurnitureDemand NONE=new FurnitureDemand(Set.of(),0,1000);
    public FurnitureDemand {kinds=Set.copyOf(kinds);
        if(radius<0||radius>12||(!kinds.isEmpty()&&radius==0)||intervalMs<1000||intervalMs>60000)throw new IllegalArgumentException("Invalid furniture demand");}
    public FurnitureDemand merge(FurnitureDemand other) {
        if(kinds.isEmpty())return other;if(other.kinds.isEmpty())return this;
        var both=EnumSet.copyOf(kinds);both.addAll(other.kinds);
        return new FurnitureDemand(both,Math.max(radius,other.radius),Math.min(intervalMs,other.intervalMs));
    }
}
