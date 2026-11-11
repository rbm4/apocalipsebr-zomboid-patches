package zombie.apocalipse.humans.furniture;

import java.util.*;
import static zombie.apocalipse.humans.furniture.FurnitureAffordances.*;

/** Per-observer resumable discovery; every native query is injected and budgeted. */
public final class FurnitureScanner {
    private final Thread owner=Thread.currentThread();
    public interface World {
        /** -1 for unavailable; count must not require an object-list scan. */
        int objectCount(int x,int y,int z);
        /** Changes whenever square object membership/order changes, including same-size replacement. */
        long revision(int x,int y,int z);
        /** At most one candidate per inspected object for the initial seat capability. */
        Candidate inspect(int x,int y,int z,int index,Set<Kind> kinds,long now);
    }
    private record Offset(int x,int y) { }
    private final FurnitureDemand demand;
    private final List<Offset> offsets;
    private final LinkedHashMap<Token,Candidate> collected=new LinkedHashMap<>();
    private Frame frame=Frame.EMPTY;
    private int cursor,object,originX,originY,originZ;
    private boolean scanning,loaded,truncated;
    private int available;
    private long squareRevision;
    private boolean stale;
    private long next;
    public FurnitureScanner(FurnitureDemand demand) {
        this.demand=Objects.requireNonNull(demand);var cells=new ArrayList<Offset>();
        if(!demand.kinds().isEmpty())for(int x=-demand.radius();x<=demand.radius();x++)for(int y=-demand.radius();y<=demand.radius();y++)
            if(x*x+y*y<=demand.radius()*demand.radius())cells.add(new Offset(x,y));
        cells.sort(Comparator.comparingInt((Offset o)->o.x*o.x+o.y*o.y).thenComparingInt(Offset::x).thenComparingInt(Offset::y));offsets=List.copyOf(cells);
    }
    private void check(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Furniture scanner requires server owner");}
    public Frame snapshot(){check();return frame;}
    public void clear(){check();frame=Frame.EMPTY;collected.clear();scanning=false;next=0;}
    public Frame update(int x,int y,int z,long now,World world,FurnitureScanBudget budget) {
        check();
        if(offsets.isEmpty())return Frame.EMPTY;
        if(frame.sampled()&&(x!=originX||y!=originY||z!=originZ))clear();
        if(!scanning&&frame.sampled()&&now<next)return frame;
        if(!scanning){scanning=true;cursor=object=0;originX=x;originY=y;originZ=z;loaded=true;truncated=stale=false;available=0;collected.clear();}
        stale|=collected.values().removeIf(c->!c.fresh(now));
        int squares=0,objects=0;
        while(cursor<offsets.size()&&squares<64&&objects<128) {
            if(!budget.square())break;squares++;
            var cell=offsets.get(cursor);int cx=originX+cell.x,cy=originY+cell.y;
            int count=cx<0||cy<0?-1:world.objectCount(cx,cy,z);
            if(count<0){loaded=false;cursor++;object=0;continue;}
            available++;
            long revision=world.revision(cx,cy,z);
            if(object>0&&revision!=squareRevision)object=0;
            squareRevision=revision;
            while(object<count&&objects<128) {
                if(!budget.object())break;
                objects++;var candidate=world.inspect(cx,cy,z,object++,demand.kinds(),now);
                if(candidate!=null) {
                    if(!demand.kinds().contains(candidate.kind())||!candidate.fresh(now)||candidate.token().x()!=cx||candidate.token().y()!=cy||candidate.token().z()!=z)
                        throw new IllegalStateException("Furniture adapter returned mismatched candidate");
                    if(collected.containsKey(candidate.token())||collected.size()<32)collected.put(candidate.token(),candidate);
                    else truncated=true;
                }
            }
            if(object<count)break;cursor++;object=0;
        }
        boolean complete=cursor==offsets.size();
        frame=new Frame(true,now,List.copyOf(collected.values()),available==0?Coverage.UNAVAILABLE:complete&&loaded&&!truncated&&!stale?Coverage.COMPLETE:Coverage.PARTIAL,truncated,new Work(squares,objects));
        if(complete){scanning=false;next=now+demand.intervalMs()*1_000_000L;}
        return frame;
    }
}
