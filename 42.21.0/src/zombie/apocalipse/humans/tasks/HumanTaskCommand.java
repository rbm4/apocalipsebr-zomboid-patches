package zombie.apocalipse.humans.tasks;

import java.util.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;

/** Authored, immutable command. Persist names, never enum ordinals or Java class names. */
public record HumanTaskCommand(Kind kind, Map<String,String> parameters) {
    public enum Kind { HOLD_POSITION, MOVE_TO, FOLLOW_PLAYER, RESUME_ROUTINE, CLEAR_CORPSES }
    public HumanTaskCommand {
        Objects.requireNonNull(kind); parameters=Map.copyOf(parameters);
        Set<String> allowed=switch(kind) {
            case HOLD_POSITION -> Set.of("durationMs");
            case MOVE_TO -> Set.of("x","y","z");
            case FOLLOW_PLAYER -> Set.of("distanceTiles");
            case RESUME_ROUTINE -> Set.of();
            case CLEAR_CORPSES -> Set.of("x","y","z","radiusTiles","sourceX","sourceY","sourceZ");
        };
        if(!allowed.containsAll(parameters.keySet()))throw new IllegalArgumentException("Unknown task parameter");
        switch(kind) {
            case HOLD_POSITION -> integer(parameters,"durationMs",0,86_400_000,0);
            case MOVE_TO -> new Point(integer(parameters,"x",0,Integer.MAX_VALUE-1,-1),integer(parameters,"y",0,Integer.MAX_VALUE-1,-1),integer(parameters,"z",-32,31,-33));
            case FOLLOW_PLAYER -> integer(parameters,"distanceTiles",1,8,2);
            case RESUME_ROUTINE -> { }
            case CLEAR_CORPSES -> {
                new Point(integer(parameters,"x",0,Integer.MAX_VALUE-1,-1),integer(parameters,"y",0,Integer.MAX_VALUE-1,-1),integer(parameters,"z",-32,31,-33));
                integer(parameters,"radiusTiles",0,2,2);
                if(parameters.keySet().stream().anyMatch(k->k.startsWith("source")))
                    new Point(integer(parameters,"sourceX",0,Integer.MAX_VALUE-1,-1),integer(parameters,"sourceY",0,Integer.MAX_VALUE-1,-1),integer(parameters,"sourceZ",-32,31,-33));
            }
        }
    }
    private static int integer(Map<String,String> parameters,String key,int min,int max,int fallback) {
        String value=parameters.get(key);
        int result=value==null?fallback:Integer.parseInt(value);
        if(result<min||result>max)throw new IllegalArgumentException("Invalid task parameter: "+key);
        return result;
    }
    public int durationMs(){return integer(parameters,"durationMs",0,86_400_000,0);}
    public int distanceTiles(){return integer(parameters,"distanceTiles",1,8,2);}
    public int radiusTiles(){return integer(parameters,"radiusTiles",0,2,2);}
    public Point source(){return parameters.containsKey("sourceX")?new Point(Integer.parseInt(parameters.get("sourceX")),Integer.parseInt(parameters.get("sourceY")),Integer.parseInt(parameters.get("sourceZ"))):null;}
    public Point destination(){return new Point(Integer.parseInt(parameters.get("x")),Integer.parseInt(parameters.get("y")),Integer.parseInt(parameters.get("z")));}
}
