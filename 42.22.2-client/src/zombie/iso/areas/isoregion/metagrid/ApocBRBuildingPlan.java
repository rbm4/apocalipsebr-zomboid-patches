package zombie.iso.areas.isoregion.metagrid;

import java.util.*;
import java.util.function.BooleanSupplier;
import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoDirections;
import zombie.iso.areas.isoregion.data.DataChunk;
import zombie.iso.areas.isoregion.data.DataRoot;
import zombie.iso.areas.isoregion.regions.IsoChunkRegion;
import zombie.iso.areas.isoregion.regions.IsoWorldRegion;

/** Geometry only. Region references are scoped to the pinned source root and never outlive its swap acknowledgment. */
public final class ApocBRBuildingPlan {
    public static final boolean ASYNC = !"false".equalsIgnoreCase(System.getProperty("apocbr.clientBuildings.async", "true"));
    public record Rect(int x,int y,int w,int h) { }
    public record Cell(int x,int y,List<IsoWorldRegion> regions) { }
    public record Room(IsoWorldRegion region,int regionId,int level,List<Rect> rects,int x,int y,int x2,int y2,int area) { }
    public record Shape(List<Room> rooms,int x,int y,int x2,int y2,List<Long> chunks) { }
    public final DataRoot source;
    public final long generation;
    public final List<Cell> cells;
    public final List<Shape> shapes;
    public final long nanos,collectNanos,geometryNanos;
    public final int regionCount,rectangleCount;
    private ApocBRBuildingPlan(DataRoot source,long generation,List<Cell> cells,List<Shape> shapes,long nanos,long collectNanos,long geometryNanos,int regions,int rects) {
        this.source=source;this.generation=generation;this.cells=List.copyOf(cells);this.shapes=List.copyOf(shapes);
        this.nanos=nanos;this.regionCount=regions;this.rectangleCount=rects;
        this.collectNanos=collectNanos;this.geometryNanos=geometryNanos;
    }
    public void validate(DataRoot root,long expectedGeneration) {
        if(root!=source || generation!=expectedGeneration) throw new IllegalStateException("Building plan root/generation mismatch");
        for(Shape shape:shapes) for(Room room:shape.rooms)
            if(room.region.getID()!=room.regionId) throw new IllegalStateException("Building plan region was recycled");
    }
    public static long key(int x,int y) { return ((long)x<<32)|(y&0xffffffffL); }
    public static ApocBRBuildingPlan build(DataRoot root,List<IsoGameCharacter.Location> changed,long generation,BooleanSupplier cancelled) {
        long started=System.nanoTime();
        LinkedHashSet<Long> coordinates=new LinkedHashSet<>();
        for(IsoGameCharacter.Location cell:changed) {
            coordinates.add(key(cell.x,cell.y));
            for(IsoDirections d:IsoDirections.values()) coordinates.add(key(cell.x+d.dx(),cell.y+d.dy()));
        }
        ArrayList<Cell> cells=new ArrayList<>();ArrayList<IsoWorldRegion> collected=new ArrayList<>();
        for(long coordinate:coordinates) {
            check(cancelled);int x=(int)(coordinate>>32),y=(int)coordinate;
            root.getIsoWorldRegionsInCell(x,y,collected);
            cells.add(new Cell(x,y,List.copyOf(collected)));
        }
        long collectedAt=System.nanoTime();
        ArrayList<Shape> shapes=new ArrayList<>();HashSet<IsoWorldRegion> done=new HashSet<>();
        int regions=0,rectangles=0;
        for(Cell cell:cells) for(IsoWorldRegion seed:cell.regions) {
            check(cancelled);
            if(!seed.isEnclosed() || seed.getRoofedPercentage()<.5f || done.contains(seed)) continue;
            ArrayList<IsoWorldRegion> component=connected(seed,cancelled);
            ArrayList<Room> rooms=new ArrayList<>();
            for(IsoWorldRegion region:component) if(done.add(region) && region.isEnclosed() && !(region.getRoofedPercentage()<.5f)) {
                Room room=room(region,cancelled);rooms.add(room);regions++;rectangles+=room.rects.size();
            }
            shapes.add(shape(rooms,cancelled));
        }
        long finishedAt=System.nanoTime();
        return new ApocBRBuildingPlan(root,generation,cells,shapes,finishedAt-started,collectedAt-started,finishedAt-collectedAt,regions,rectangles);
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Building preparation stopped");
    }
    private static ArrayList<IsoWorldRegion> connected(IsoWorldRegion seed,BooleanSupplier cancelled) {
        ArrayList<IsoWorldRegion> result=new ArrayList<>();HashSet<IsoWorldRegion> seen=new HashSet<>();
        ArrayDeque<Iterator<IsoWorldRegion>> stack=new ArrayDeque<>();seen.add(seed);result.add(seed);stack.push(seed.getNeighbors().iterator());
        while(!stack.isEmpty()) {
            check(cancelled);Iterator<IsoWorldRegion> iterator=stack.peek();
            if(!iterator.hasNext()) { stack.pop();continue; }
            IsoWorldRegion next=iterator.next();
            if(next.isEnclosed() && next.getRoofedPercentage()>=.5f && seen.add(next)) {
                result.add(next);stack.push(next.getNeighbors().iterator());
            }
        }
        return result;
    }
    private static Room room(IsoWorldRegion region,BooleanSupplier cancelled) {
        ArrayList<Rect> rects=new ArrayList<>();int level=Integer.MAX_VALUE;
        for(IsoChunkRegion chunkRegion:region.getDebugIsoChunkRegionCopy()) {
            check(cancelled);if(level==Integer.MAX_VALUE) level=chunkRegion.getzLayer();
            DataChunk chunk=chunkRegion.getDataChunk();long free=0L;
            for(int i=63;i>=0;i--) if(chunk.getSquare(i%8,i/8,level)>0 && chunk.getIsoChunkRegion(i%8,i/8,level)==chunkRegion) free|=1L<<i;
            rects.addAll(rectangles(free,chunk.getChunkX()*8,chunk.getChunkY()*8));
        }
        int x=10000000,y=10000000,x2=-1000000,y2=-1000000,area=0;
        for(Rect r:rects) { x=Math.min(x,r.x);y=Math.min(y,r.y);x2=Math.max(x2,r.x+r.w);y2=Math.max(y2,r.y+r.h);area+=r.w*r.h; }
        return new Room(region,region.getID(),level,List.copyOf(rects),x,y,x2,y2,area);
    }
    /** Same greedy rectangle order as vanilla's descending free-square list, without repeated linear removal. */
    public static List<Rect> rectangles(long free,int chunkX,int chunkY) {
        ArrayList<Rect> result=new ArrayList<>();
        while(free!=0L) {
            int index=Long.numberOfTrailingZeros(free),x=index%8,y=index/8,w=span(free,x,y),h=1;
            while(y+h<8 && span(free,x,y+h)>=w) h++;
            result.add(new Rect(chunkX+x,chunkY+y,w,h));
            long row=(1L<<w)-1;
            for(int yy=y;yy<y+h;yy++) free&=~(row<<(x+yy*8));
        }
        return result;
    }
    private static int span(long free,int x,int y) {
        int width=0;while(x+width<8 && (free&(1L<<(x+width+y*8)))!=0L) width++;return width;
    }
    private static Shape shape(List<Room> rooms,BooleanSupplier cancelled) {
        int x=Integer.MAX_VALUE,y=Integer.MAX_VALUE,x2=Integer.MIN_VALUE,y2=Integer.MIN_VALUE;
        ArrayList<Long> chunks=new ArrayList<>();HashSet<Long> seenChunks=new HashSet<>();
        for(Room room:rooms) for(Rect r:room.rects) {
            check(cancelled);
            x=Math.min(x,r.x);y=Math.min(y,r.y);x2=Math.max(x2,r.x+r.w);y2=Math.max(y2,r.y+r.h);
            // Preserve vanilla truncating division and short storage. Out-of-short coordinates retain duplicate semantics.
            for(int cy=(r.y-1)/8;cy<=(r.y+r.h)/8;cy++) for(int cx=(r.x-1)/8;cx<=(r.x+r.w)/8;cx++) {
                long stored=key((short)cx,(short)cy);
                if(cx!=(short)cx || cy!=(short)cy || !seenChunks.contains(key(cx,cy))) chunks.add(stored);
                seenChunks.add(stored);
            }
        }
        return new Shape(List.copyOf(rooms),x,y,x2,y2,List.copyOf(chunks));
    }
}
