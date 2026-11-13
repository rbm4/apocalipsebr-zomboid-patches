package zombie.apocalipse.humans;

import java.util.*;
import zombie.apocalipse.humans.furniture.*;
import static zombie.apocalipse.humans.furniture.FurnitureAffordances.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Area;
import zombie.characters.IsoPlayer;
import zombie.iso.*;
import zombie.iso.sprite.IsoSprite;
import zombie.util.list.MutationTrackedArrayList;

/** Factory-shared native identity/catalog/occupancy adapter. Server thread only. */
final class ServerHumanFurnitureWorld {
    record Descriptor(String facing,Seat seat) { }
    interface Catalog { Descriptor describe(IsoSprite sprite); void clear(); }
    private static final class Stamp {
        final IsoGridSquare square;final List<IsoObject> objects;final int index;
        final IsoSprite sprite;final String name,tileset;final int tile;final Descriptor descriptor;final long lifetime;
        Stamp(IsoGridSquare square,List<IsoObject> objects,int index,IsoObject object,Descriptor descriptor){
            var sprite=object.getSprite();lifetime=object.getApocbrLifetimeRevision();
            this.square=square;this.objects=objects;this.index=index;this.sprite=sprite;
            name=sprite.getName();tileset=sprite.tilesetName;tile=sprite.tileSheetIndex;this.descriptor=descriptor;
        }
        @Override public boolean equals(Object value){return value instanceof Stamp s&&square==s.square&&objects==s.objects&&index==s.index
            &&sprite==s.sprite&&Objects.equals(name,s.name)&&Objects.equals(tileset,s.tileset)&&tile==s.tile&&lifetime==s.lifetime&&descriptor.equals(s.descriptor);}
        @Override public int hashCode(){return System.identityHashCode(square)^System.identityHashCode(objects)^index;}
    }
    private final Thread owner=Thread.currentThread();
    private final FurnitureBindings<IsoObject> bindings=new FurnitureBindings<>(2048);
    private final Catalog catalog;
    private final Set<IsoObject> occupied=Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean occupancyComplete;
    private IsoCell cell;
    ServerHumanFurnitureWorld(){this(new ServerHumanSeatingCatalog());}
    ServerHumanFurnitureWorld(Catalog catalog){this.catalog=Objects.requireNonNull(catalog);}
    private void check(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native furniture requires server owner");}
    void beginPass(IsoCell current,List<IsoPlayer> players,List<IsoPlayer> actors){
        check();if(cell!=current){clear();cell=current;}
        occupied.clear();occupancyComplete=players.size()<=128&&actors.size()<=64;
        for(int i=0;i<Math.min(players.size(),128);i++)occupant(players.get(i));
        for(int i=0;i<Math.min(actors.size(),64);i++)occupant(actors.get(i));
    }
    private void occupant(IsoPlayer player){
        if(player!=null&&!player.isDead()&&player.getCell()==cell&&player.getSitOnFurnitureObject()!=null)occupied.add(player.getSitOnFurnitureObject());
    }
    void clear(){check();bindings.clear();catalog.clear();occupied.clear();occupancyComplete=false;cell=null;}
    View view(IsoCell actorCell,int assignedZ,Area area){check();return new View(actorCell,assignedZ,area);}
    /** Resolve only on the server, then revalidate occupancy and approach at the actual effect boundary. */
    IsoObject resolve(Token token){
        check();var object=bindings.resolve(token);
        if(object==null)return null;
        var stamp=(Stamp)bindings.revision(token);
        if(cell==null||stamp==null||cell.getGridSquare(token.x(),token.y(),token.z())!=stamp.square
            ||object.getSquare()!=stamp.square||stamp.square.getObjects()!=stamp.objects||stamp.index>=stamp.objects.size()
            ||stamp.objects.get(stamp.index)!=object||object.getSprite()!=stamp.sprite){bindings.remove(object);return null;}
        var current=catalog.describe(object.getSprite());
        if(current==null||!stamp.equals(new Stamp(stamp.square,stamp.objects,stamp.index,object,current))){
            bindings.remove(object);return null;
        }
        return token.slot().equals(current.facing())?object:null;
    }
    Candidate candidate(Token token,long now){
        check();var object=resolve(token);if(object==null)return null;
        var stamp=(Stamp)bindings.revision(token);
        return new Candidate(token,Kind.SIT,List.of(),stamp.descriptor.facing(),!occupancyComplete||occupied.contains(object),now,stamp.descriptor.seat());
    }
    final class View implements FurnitureScanner.World {
        private final IsoCell actorCell;private final int assignedZ;private final Area area;
        private Object room;
        private IsoGridSquare square;
        private List<IsoObject> objects;
        View(IsoCell actorCell,int assignedZ,Area area){this.actorCell=actorCell;this.assignedZ=assignedZ;this.area=area;}
        void clear(){check();room=null;square=null;objects=null;}
        Object begin(int x,int y,int z){
            clear();
            var origin=actorCell==cell&&cell!=null&&z==assignedZ?cell.getGridSquare(x,y,z):null;
            room=origin==null?null:origin.getRoom();return room;
        }
        public int objectCount(int x,int y,int z){
            check();square=null;objects=null;
            if(cell==null||actorCell!=cell||z!=assignedZ||room==null)return -1;
            square=cell.getGridSquare(x,y,z);
            if(square==null)return -1;
            if(square.getRoom()!=room||area!=null&&!area.contains(x,y))return 0;
            objects=square.getObjects();return objects.size();
        }
        public long revision(int x,int y,int z){check();return objects instanceof MutationTrackedArrayList<?> tracked?tracked.mutationVersion():UNTRACKED_REVISION;}
        public Candidate inspect(int x,int y,int z,int index,Set<Kind> kinds,long now){
            check();if(!kinds.contains(Kind.SIT)||square==null||objects==null||index>=objects.size())return null;
            var object=objects.get(index);
            if(object==null||object.getSquare()!=square||object.getSprite()==null)return null;
            var description=catalog.describe(object.getSprite());
            if(description==null)return null;
            var token=bindings.bind(object,new Stamp(square,objects,index,object,description),x,y,z,description.facing());
            // Discovery exposes native entry-side metadata, not guessed animation/collision geometry.
            return new Candidate(token,Kind.SIT,List.of(),description.facing(),!occupancyComplete||occupied.contains(object),now,description.seat());
        }
    }
}
