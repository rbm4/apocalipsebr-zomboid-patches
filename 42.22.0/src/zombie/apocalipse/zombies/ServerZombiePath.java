package zombie.apocalipse.zombies;

import java.util.ArrayList;
import java.util.List;
import zombie.ai.astar.Mover;
import zombie.characters.IsoZombie;
import zombie.apocalipse.humans.HumanLog;
import zombie.pathfind.IPathfinder;
import zombie.pathfind.Path;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.nativeCode.PathfindNative;

/** Main-thread request owner. A separate mover keeps requests out of native AI's slot.
 * Walk-only defaults deliberately exclude thumping/crawling/climbing execution. */
final class ServerZombiePath implements Mover {
    record Point(float x,float y) { }
    private final IsoZombie body;
    private final int instance;
    private final short onlineId;
    private long generation;
    private boolean pending, nativeBackend;
    private List<Point> points=List.of();
    private float targetX,targetY;
    private boolean nativeTraversal;
    ServerZombiePath(IsoZombie body){this.body=body;instance=body.getID();onlineId=body.onlineId;}
    public int getID(){return instance;}
    public int getPathFindIndex(){return -1;}
    boolean pending(){return pending;}
    List<Point> points(){return points;}
    boolean needsNativeTraversal(){return nativeTraversal;}
    void request(float x,float y){
        if(pending || !points.isEmpty() && Math.hypot(x-targetX,y-targetY)<1)return;
        targetX=x;targetY=y;
        pending=true;nativeBackend=PathfindNative.useNativeCode;
        long ticket=++generation;
        var callback=new IPathfinder(){
            private boolean current(Mover mover){return mover==ServerZombiePath.this && ticket==generation
                && body.getID()==instance && body.onlineId==onlineId && !body.isDead();}
            public void Succeeded(Path path,Mover mover){
                if(!current(mover))return;
                pending=false;
                var copy=new ArrayList<Point>();
                if(path.size()>256){points=List.of();diagnostic("node_limit");return;}
                for(int n=0;n<path.size();n++){
                    var p=path.getNode(n);
                    if(!Float.isFinite(p.x)||!Float.isFinite(p.y)||!Float.isFinite(p.z)){points=List.of();diagnostic("invalid_coordinate");return;}
                    var square=body.getCell().getGridSquare((int)Math.floor(p.x),(int)Math.floor(p.y),(int)Math.floor(p.z));
                    if((int)Math.floor(p.z)!=body.getZi() || square!=null && square.HasStairs()){
                        nativeTraversal=true;points=List.of();diagnostic("native_stairs");return;
                    }
                    copy.add(new Point(p.x,p.y));
                }
                // Native result objects are pooled immediately after this callback.
                points=List.copyOf(copy);
                diagnostic("ready; nodes="+copy.size());
            }
            public void Failed(Mover mover){if(current(mover)){pending=false;points=List.of();diagnostic("no_path");}}
        };
        if(nativeBackend)PathfindNative.instance.addRequest(callback,this,body.getX(),body.getY(),body.getZ(),x,y,body.getZ());
        else PolygonalMap2.instance.addRequest(callback,this,body.getX(),body.getY(),body.getZ(),x,y,body.getZ());
    }
    private void diagnostic(String result){HumanLog.log(HumanLog.Level.DEBUG,()->"zombie route id="+onlineId
        +"; instance="+instance+"; result="+result+"; backend="+(nativeBackend?"native":"java"));}
    void cancel(){
        ++generation;
        if(pending){
            if(nativeBackend)PathfindNative.instance.cancelRequest(this);
            else PolygonalMap2.instance.cancelRequest(this);
        }
        pending=false;points=List.of();nativeTraversal=false;
    }
    /** Dedicated server does not run IngameState's path-result pump. */
    static void update(){
        if(PathfindNative.useNativeCode)PathfindNative.instance.updateMain();
        else PolygonalMap2.instance.updateMain();
    }
}
