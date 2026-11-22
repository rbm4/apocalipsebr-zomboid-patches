package zombie.apocalipse.humans.seating;

import java.util.*;
import zombie.apocalipse.humans.furniture.FurnitureAffordances.*;

/** Value-only reproduction of native seating translation/rotation and solid-seat approach clamping. */
public final class SeatingGeometry {
    private SeatingGeometry() { }
    public static final long TRANSITION_NANOS=1_250_000_000L;
    public record Plan(Token target,String facing,String side,Position approach,Position entry,Position seat,Position exit) { }
    public static Plan plan(Candidate candidate,String side,boolean solid){
        if(!candidate.seat().sides().contains(side))return null;
        var token=candidate.token();var data=candidate.seat();
        double angle=switch(candidate.facing()){case "N"->Math.PI;case "W"->Math.PI/2;case "E"->Math.PI*1.5;default->0;};
        double sideAngle=switch(side){case "Left"->Math.PI/2;case "Right"->-Math.PI/2;default->0;};
        // Exact Bip01 XY displacement of target .x clips, before SeatingManager's 1.5 scale.
        // FileTask_LoadAnimation.loadX uses scale=1; MAKE_LEFT_HANDED changes Z, not deferred XY.
        double sx=-.003,sy=side.equals("Front")?-.263279:.287944;
        double[] sit=rotate(sx*1.5,sy*1.5,angle+sideAngle);
        double ex=side.equals("Left")?.287795:side.equals("Right")?-.287795:.003;
        double ey=side.equals("Left")?-.003150:side.equals("Right")?.003150:.286901;
        double[] stand=rotate(ex*1.5,ey*1.5,angle);
        float x=token.x()+.5f+data.offsetX(),y=token.y()+.5f+data.offsetY();
        if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||y<0||Math.abs(data.offsetX())>1.5||Math.abs(data.offsetY())>1.5)return null;
        var seat=new Position(x,y,token.z());
        Position entry=position(x-sit[0],y-sit[1],token.z());
        Position rawExit=position(x+stand[0],y+stand[1],token.z());
        if(entry==null||rawExit==null)return null;
        Position approach=solid?outside(entry,token,candidate.facing(),side):entry;
        Position exit=solid?outside(rawExit,token,candidate.facing(),side):rawExit;
        if(approach==null||exit==null||distance(approach,seat)>1.6||distance(exit,seat)>1.6)return null;
        return new Plan(token,candidate.facing(),side,approach,entry,seat,exit);
    }
    private static Position outside(Position p,Token t,String facing,String side){
        if(p.x()<t.x()||p.x()>=t.x()+1||p.y()<t.y()||p.y()>=t.y()+1)return p;
        int dx=switch(facing){case "E"->1;case "W"->-1;default->0;};
        int dy=switch(facing){case "S"->1;case "N"->-1;default->0;};
        if(side.equals("Left")){int old=dx;dx=dy;dy=-old;}
        else if(side.equals("Right")){int old=dx;dx=-dy;dy=old;}
        return position(dx<0?t.x()-.3:dx>0?t.x()+1.3:p.x(),dy<0?t.y()-.3:dy>0?t.y()+1.3:p.y(),t.z());
    }
    private static double[] rotate(double x,double y,double angle){return new double[]{x*Math.cos(angle)-y*Math.sin(angle),x*Math.sin(angle)+y*Math.cos(angle)};}
    private static Position position(double x,double y,int z){return x<0||y<0||!Double.isFinite(x)||!Double.isFinite(y)?null:new Position((float)x,(float)y,z);}
    public static double distance(Position a,Position b){return Math.hypot(a.x()-b.x(),a.y()-b.y());}
}
