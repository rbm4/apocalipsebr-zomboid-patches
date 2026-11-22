// Explicit engine doubles for production reconciliation/geometry differential tests.
class TShortArrayList {
    final ArrayList<Short> a=new ArrayList<>();TShortArrayList(){}TShortArrayList(int n){}
    void add(short s){a.add(s);}short get(int i){return a.get(i);}int size(){return a.size();}boolean isEmpty(){return a.isEmpty();}
    void resetQuick(){a.clear();}boolean contains(short s){return a.contains(s);}void remove(short s){a.remove(Short.valueOf(s));}void trimToSize(){}
}
class PZMath {static int fastfloor(float f){return (int)Math.floor(f);}static int coorddivision(int x,int d){return Math.floorDiv(x,d);}}
class PZArrayUtil {static <T>void addAll(Collection<T>a,Collection<T>b){a.addAll(b);}}
class IsoGameCharacter {static class Location {int x,y,z;Location(){}Location(int x,int y,int z){set(x,y,z);}void set(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
    public boolean equals(Object o){return o instanceof Location p&&x==p.x&&y==p.y&&z==p.z;}public int hashCode(){return Objects.hash(x,y,z);}}}
enum IsoDirections {N(0,-1),NW(-1,-1),W(-1,0),SW(-1,1),S(0,1),SE(1,1),E(1,0),NE(1,-1);final int x,y;IsoDirections(int x,int y){this.x=x;this.y=y;}int dx(){return x;}int dy(){return y;}}
class IsoChunkRegion {final DataChunk chunk;final int z;IsoWorldRegion region;IsoChunkRegion(DataChunk c,int z,IsoWorldRegion r){chunk=c;this.z=z;region=r;}int getzLayer(){return z;}DataChunk getDataChunk(){return chunk;}IsoWorldRegion getIsoWorldRegion(){return region;}}
class IsoWorldRegion {
    int id;boolean enclosed=true;float roof=1f;BuildingDef building;
    final ArrayList<IsoWorldRegion>neighbors=new ArrayList<>();final ArrayList<IsoChunkRegion>chunks=new ArrayList<>();
    IsoWorldRegion(int id){this.id=id;}int getID(){return id;}boolean isEnclosed(){return enclosed;}float getRoofedPercentage(){return roof;}
    ArrayList<IsoWorldRegion>getNeighbors(){return neighbors;}ArrayList<IsoChunkRegion>getDebugIsoChunkRegionCopy(){return new ArrayList<>(chunks);}
    List<IsoChunkRegion>getChunkRegions(){return chunks;}void setBuildingDef(BuildingDef b){building=b;}BuildingDef getBuildingDef(){return building;}
}
class DataChunk {
    final int x,y;final HashMap<Integer,IsoChunkRegion[]>grid=new HashMap<>();final HashMap<Integer,ArrayList<IsoChunkRegion>>layers=new HashMap<>();
    DataChunk(int x,int y){this.x=x;this.y=y;}int getChunkX(){return x;}int getChunkY(){return y;}int getCellX(){return Math.floorDiv(x,32);}int getCellY(){return Math.floorDiv(y,32);}
    ArrayList<IsoChunkRegion>getChunkRegions(int z){return layers.computeIfAbsent(z,k->new ArrayList<>());}
    byte getSquare(int x,int y,int z){return getIsoChunkRegion(x,y,z)==null?(byte)0:(byte)1;}
    IsoChunkRegion getIsoChunkRegion(int x,int y,int z){IsoChunkRegion[]a=grid.get(z);return a==null?null:a[x+y*8];}
    void add(IsoWorldRegion r,int z,long mask){IsoChunkRegion c=new IsoChunkRegion(this,z,r);r.chunks.add(c);getChunkRegions(z).add(c);IsoChunkRegion[]g=grid.computeIfAbsent(z,k->new IsoChunkRegion[64]);for(int i=0;i<64;i++)if((mask&(1L<<i))!=0)g[i]=c;}
}
class DataCell {final ArrayList<DataChunk>chunks=new ArrayList<>();void getAllChunks(List<DataChunk>out){out.addAll(chunks);}}
class DataRoot {
    final HashMap<Integer,DataCell>cellMap=new HashMap<>();int collects;
    DataCell getCell(int key){return cellMap.get(key);}
    COLLECT_METHOD
    ORIGINAL_COLLECT_METHOD
    ApocBRBuildingPlan apocBuildingPlan;long apocBuildingGeneration;final ArrayList<IsoGameCharacter.Location>changedCells=new ArrayList<>();
    static NewReconciler wrtmg;
    PREPARE_METHOD
    CONSUME_METHOD
    void add(DataChunk c){cellMap.computeIfAbsent(IsoRegions.hash(c.getCellX(),c.getCellY()),k->new DataCell()).chunks.add(c);}
}
class IsoRegions {static DataRoot root;static int hash(int x,int y){return (x<<16)^(y&65535);}static DataRoot apocBuildingRoot(){return root;}
    static Object getIsoWorldRegion(int x,int y,int z){return null;}
    static void getIsoWorldRegionsInCell(int x,int y,ArrayList<IsoWorldRegion>out){if(BuildingHarness.originalGeometry)root.originalCollect(x,y,out);else root.getIsoWorldRegionsInCell(x,y,out);}}
class RoomDef {
    RoomDef(){if(Thread.currentThread()!=BuildingHarness.owner)throw new AssertionError("Room construction on worker");}
    long id,metaId;String name;boolean explored,userDefined,lightsActive;int level,x,y,x2,y2,area;BuildingDef building;final ArrayList<RoomRect>rects=new ArrayList<>();
    static class RoomRect {int x,y,w,h;RoomRect(){}RoomRect(int x,int y,int w,int h){set(x,y,w,h);}RoomRect set(int x,int y,int w,int h){this.x=x;this.y=y;this.w=w;this.h=h;return this;}
        int getX(){return x;}int getY(){return y;}int getW(){return w;}int getH(){return h;}int getX2(){return x+w;}int getY2(){return y+h;}}
    void setName(String s){name=s;}ArrayList<RoomRect>getRects(){return rects;}
    ROOM_BOUNDS
    ROOM_META
    boolean isAdjacent(RoomDef b){for(RoomRect r:rects)for(RoomRect q:b.rects)if(r.x<q.x+q.w+1&&r.x+r.w>q.x-1&&r.y<q.y+q.h+1&&r.y+r.h>q.y-1)return true;return false;}
    boolean overlaps(RoomDef b){for(RoomRect r:rects)for(RoomRect q:b.rects)if(r.x<q.x+q.w&&r.x+r.w>q.x&&r.y<q.y+q.h&&r.y+r.h>q.y)return true;return false;}
}
class BuildingDef {
    long id,metaId;int x,y,x2,y2;boolean user;final ArrayList<RoomDef>rooms=new ArrayList<>(),emptyoutside=new ArrayList<>();TShortArrayList overlappedChunks;int roofInvalidations;
    BuildingDef(){if(Thread.currentThread()!=BuildingHarness.owner)throw new AssertionError("Engine construction on worker");}
    int getX(){return x;}int getY(){return y;}int getW(){return x2-x;}int getH(){return y2-y;}int getCellX(){return Math.floorDiv(x,256);}int getCellY(){return Math.floorDiv(y,256);}
    void setUserDefined(boolean b){user=b;}boolean isUserDefined(){return user;}ArrayList<RoomDef>getRooms(){return rooms;}ArrayList<RoomDef>getEmptyOutside(){return emptyoutside;}
    boolean isBasement(){return !rooms.isEmpty()&&rooms.get(0).level<0;}int getMinLevel(){int z=100;for(RoomDef r:rooms)z=Math.min(z,r.level);return z;}int getMaxLevel(){int z=-100;for(RoomDef r:rooms)z=Math.max(z,r.level);return z;}void resetMinMaxLevel(){}
    boolean isAdjacent(BuildingDef b,boolean ignore){for(RoomDef r:rooms)for(RoomDef q:b.rooms)if((ignore||r.level==q.level)&&r.isAdjacent(q))return true;return false;}
    boolean overlaps(BuildingDef b,boolean ignore){for(RoomDef r:rooms)for(RoomDef q:b.rooms)if((ignore||r.level==q.level)&&r.overlaps(q))return true;return false;}
    BUILDING_BOUNDS
    ORIGINAL_BUILDING_BOUNDS
    BUILDING_OVERLAPS_CHUNK
    BUILDING_META
    BUILDING_ADD_ROOMS
    final HashMap<Integer,Long>roofRoomId=new HashMap<>();
    BUILDING_ROOF_GET
    BUILDING_ROOF_INVALIDATE
}
class RoomID {static long makeID(int x,int y,int index){return ((long)(x&65535)<<48)|((long)(y&65535)<<32)|(index&0xffffffffL);}}
class BuildingID {static long makeID(int x,int y,int index){return RoomID.makeID(x,y,index);}}
class Core {static boolean debug;}
class DebugType {static final DebugType General=new DebugType();void error(String s,Object...values){BuildingHarness.diagnostics.add(s);}}
class GameServer {static boolean server;}
class IsoPlayer {static int numPlayers=1;}
class IsoRoomLight {}
class IsoObject {}
class IsoGenerator extends IsoObject {boolean isActivated(){return true;}}
class IsoLightSwitch extends IsoObject {}
enum IsoFlagType {exterior}
class Properties {boolean outside=true;boolean has(IsoFlagType f){return outside;}void unset(IsoFlagType f){outside=false;}}
class IsoBuilding {BuildingDef def;boolean toxic;void setToxic(boolean b){toxic=b;}}
class IsoRoom {RoomDef def;IsoBuilding building;final ArrayList<IsoRoomLight>roomLights=new ArrayList<>();final ArrayList<IsoLightSwitch>lightSwitches=new ArrayList<>();
    void clear(boolean b){def=null;building=null;roomLights.clear();lightSwitches.clear();}RoomDef getRoomDef(){return def;}IsoBuilding getBuilding(){return building;}
    void createLights(boolean b){roomLights.add(new IsoRoomLight());}}
class IsoGridSquare {
    final int x,y,z;final IsoChunk chunk;long roomID=-1;IsoRoom room;IsoBuilding roofHideBuilding;BuildingDef associatedBuilding;Properties properties=new Properties();ArrayList<IsoObject>objects=new ArrayList<>();
    IsoGridSquare(IsoChunk c,int x,int y,int z){chunk=c;this.x=x;this.y=y;this.z=z;}
    void setRoomID(long id){roomID=id;if(id!=-1){properties.outside=false;room=chunk.getRoom(id);}}IsoRoom getRoom(){return roomID==-1?null:room;}IsoChunk getChunk(){return chunk;}Properties getProperties(){return properties;}ArrayList<IsoObject>getObjects(){return objects;}
    void RecalcProperties(){properties.outside=roomID==-1;}void invalidateVispolyChunkLevel(){}
}
class Cutaway {void invalidateAll(){}}
class IsoChunk {
    int wx,wy;final HashMap<Integer,IsoGridSquare[]>squares=new HashMap<>();IsoChunk(int x,int y){wx=x;wy=y;}
    int getMinLevel(){return 0;}int getMaxLevel(){return 2;}IsoGridSquare[]getSquaresForLevel(int z){return squares.computeIfAbsent(z,k->new IsoGridSquare[64]);}
    IsoRoom getRoom(long id){return IsoWorld.instance.meta.getRoomByID(id);}void invalidateRenderChunkLevels(long f){}Cutaway getCutawayData(){return new Cutaway();}void checkLightingLater_AllPlayers_AllLevels(){}
}
class IsoChunkMap {static int chunkGridWidth=3;boolean ignore;final IsoChunk[][]chunks=new IsoChunk[3][3];IsoChunk getChunk(int x,int y){return chunks[x][y];}}
class IsoCell {final IsoChunkMap map=new IsoChunkMap();final ArrayList<IsoRoomLight>roomLights=new ArrayList<>();IsoChunkMap getChunkMap(int i){return map;}}
class CountingRooms extends HashMap<Long,RoomDef> {int clears;public void clear(){clears++;super.clear();}}
class IsoMetaCell {
    final int x,y;final CountingRooms rooms=new CountingRooms();final HashMap<Long,RoomDef>roomByMetaId=new HashMap<>();final ArrayList<RoomDef>roomList=new ArrayList<>();
    final ArrayList<BuildingDef>buildings=new ArrayList<>();final HashMap<Long,BuildingDef>buildingByMetaId=new HashMap<>();final HashMap<Long,IsoRoom>isoRooms=new HashMap<>();final HashMap<Long,IsoBuilding>isoBuildings=new HashMap<>();
    IsoMetaCell(int x,int y){this.x=x;this.y=y;}int getX(){return x;}int getY(){return y;}void removeRooms(ArrayList<RoomDef>r){}void addRooms(ArrayList<RoomDef>r,int x,int y){}
}
class IsoMetaGrid {
    final HashMap<Long,IsoMetaCell>cells=new HashMap<>();final ArrayList<BuildingDef>buildings=new ArrayList<>();
    IsoMetaCell getCellData(int x,int y){return cells.computeIfAbsent(ApocBRBuildingPlan.key(x,y),k->new IsoMetaCell(x,y));}
    void removeRoomsFromAdjacentCells(BuildingDef b){}void addRoomsToAdjacentCells(BuildingDef b,ArrayList<RoomDef>r){}
    void getBuildingsIntersecting(int x,int y,int w,int h,ArrayList<BuildingDef>out){for(IsoMetaCell c:cells.values())for(BuildingDef b:c.buildings)if(x<b.x2&&x+w>b.x&&y<b.y2&&y+h>b.y)out.add(b);}
    RoomDef getRoomAt(int x,int y,int z){for(IsoMetaCell c:cells.values())for(RoomDef r:c.roomList)if(r.level==z)for(RoomDef.RoomRect q:r.rects)if(x>=q.x&&x<q.x+q.w&&y>=q.y&&y<q.y+q.h)return r;return null;}
    RoomDef getEmptyOutsideAt(int x,int y,int z){return null;}BuildingDef getAssociatedBuildingAt(int x,int y){RoomDef r=getRoomAt(x,y,0);return r==null?null:r.building;}
    IsoRoom getRoomByID(long id){for(IsoMetaCell c:cells.values()){RoomDef r=c.rooms.get(id);if(r==null)continue;IsoRoom old=c.isoRooms.get(id);if(old!=null)return old;IsoRoom room=new IsoRoom();room.def=r;
        room.building=c.isoBuildings.computeIfAbsent(r.building.id,k->{IsoBuilding b=new IsoBuilding();b.def=r.building;return b;});c.isoRooms.put(id,room);return room;}return null;}
}
class IsoWorld {static IsoWorld instance;final IsoMetaGrid meta=new IsoMetaGrid();final IsoCell currentCell=new IsoCell();IsoMetaGrid getMetaGrid(){if(Thread.currentThread()!=BuildingHarness.owner)throw new AssertionError("Live meta grid read on worker");return meta;}}
class LightingJNI {static int changes;static void buildingsChanged(){changes++;}}
class IsoGridOcclusionData {static void SquareChanged(){}}
class FBORenderCutaways {static FBORenderCutaways getInstance(){return new FBORenderCutaways();}void squareChanged(Object o){}}
class PolygonalMap2 {static final PolygonalMap2 instance=new PolygonalMap2();void squareChanged(IsoGridSquare s){}}
class Pool<T> {final java.util.function.Supplier<T>supplier;final ArrayDeque<T>free=new ArrayDeque<>();Pool(java.util.function.Supplier<T>s){supplier=s;}T alloc(){return free.isEmpty()?supplier.get():free.pop();}
    void release(T value){BuildingHarness.retireCheck(value);free.push(value);}void releaseAll(Collection<T>values){for(T v:values)release(v);}}
class Pools {static Pool<BuildingDef>buildingDef;static Pool<RoomDef>roomDef;static Pool<RoomDef.RoomRect>roomRect;static void reset(){buildingDef=new Pool<>(BuildingDef::new);roomDef=new Pool<>(RoomDef::new);roomRect=new Pool<>(RoomDef.RoomRect::new);}}
