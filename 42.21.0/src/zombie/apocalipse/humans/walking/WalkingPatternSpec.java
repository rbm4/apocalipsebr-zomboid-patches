package zombie.apocalipse.humans.walking;

import java.util.List;
import zombie.apocalipse.humans.profiles.HumanProfile;

/** Immutable profile configuration. Slots are semantic names, never actor identities or Java class names. */
public record WalkingPatternSpec(String slot, String patternId, int radiusTiles, List<Point> points, Area area) {
    public WalkingPatternSpec(String slot,String patternId,int radiusTiles,List<Point> points) { this(slot,patternId,radiusTiles,points,null); }
    public record Area(int x1,int y1,int x2,int y2) {
        public Area { if(x1<0||y1<0||x2<x1||y2<y1||x2==Integer.MAX_VALUE||y2==Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid walking area"); }
        public boolean contains(int x,int y) { return x>=x1&&x<=x2&&y>=y1&&y<=y2; }
    }
    public record Point(int x, int y, int z) {
        public Point { if (x < 0 || y < 0 || x == Integer.MAX_VALUE || y == Integer.MAX_VALUE || z < -32 || z > 31) throw new IllegalArgumentException("Invalid destination"); }
    }
    public WalkingPatternSpec {
        HumanProfile.identifier(slot); HumanProfile.identifier(patternId);
        points = List.copyOf(points);
        if (radiusTiles < 1 || radiusTiles > 64 || points.size() > 32) throw new IllegalArgumentException("Invalid walking bounds");
    }
    public static WalkingPatternSpec indoor() { return new WalkingPatternSpec("background", "indoor-wander", 16, List.of()); }
}
