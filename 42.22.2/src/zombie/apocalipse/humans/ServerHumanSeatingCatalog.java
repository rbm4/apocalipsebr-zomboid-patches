package zombie.apocalipse.humans;

import java.util.*;
import zombie.iso.sprite.IsoSprite;
import zombie.seating.SeatingManager;
import zombie.apocalipse.humans.furniture.FurnitureAffordances.Seat;

/** Server metadata only. Never asks an animation player/model for approach geometry. */
final class ServerHumanSeatingCatalog implements ServerHumanFurnitureWorld.Catalog {
    private final Thread owner=Thread.currentThread();
    private record Key(IsoSprite sprite,String name,String tileset,int index) { }
    private final LinkedHashMap<Key,ServerHumanFurnitureWorld.Descriptor> cache=new LinkedHashMap<>();
    private List<String> mods;
    private void check(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Seating metadata requires server owner");}
    public ServerHumanFurnitureWorld.Descriptor describe(IsoSprite sprite){
        check();
        if(sprite==null||sprite.tilesetName==null||sprite.tileSheetIndex<0)return null;
        var key=new Key(sprite,sprite.getName(),sprite.tilesetName,sprite.tileSheetIndex);
        if(cache.containsKey(key))return cache.get(key);
        var result=read(sprite);
        if(cache.size()==512)cache.remove(cache.keySet().iterator().next());
        cache.put(key,result);return result;
    }
    private ServerHumanFurnitureWorld.Descriptor read(IsoSprite sprite){
        var manager=SeatingManager.getInstance();
        int count=manager.getTilePositionCount(sprite.tilesetName,sprite.tileSheetIndex%8,sprite.tileSheetIndex/8);
        if(count<1||count>8)return null;
        String facing=manager.getFacingDirection(sprite);
        if(!Set.of("N","S","W","E").contains(facing))return null;
        if(mods==null)mods=List.copyOf(manager.getModIDs());
        if(mods.size()>512)return null;
        int col=sprite.tileSheetIndex%8,row=sprite.tileSheetIndex/8;
        // Native Tileset.merge keeps the first nonempty tile, not the last mod.
        for(String mod:mods){
            int positions=manager.getTilePositionCount(mod,sprite.tilesetName,col,row);
            if(positions==0)continue;
            if(positions!=count)return null;
            for(int i=0;i<positions;i++)if(facing.equals(manager.getTilePositionID(mod,sprite.tilesetName,col,row,i))){
                var translation=manager.getTilePositionTranslate(mod,sprite.tilesetName,col,row,i);
                if(translation==null||!Float.isFinite(translation.x)||!Float.isFinite(translation.y)||!Float.isFinite(translation.z))return null;
                var sides=new HashSet<String>();sides.add("Front");
                if(manager.getTilePositionProperty(mod,sprite.tilesetName,col,row,i,"BlockLeft")==null)sides.add("Left");
                if(manager.getTilePositionProperty(mod,sprite.tilesetName,col,row,i,"BlockRight")==null)sides.add("Right");
                // SeatingManager.getTranslation maps stored (x,y,z) into world (x,z,y).
                return new ServerHumanFurnitureWorld.Descriptor(facing,new Seat(translation.x,translation.z,translation.y,sides));
            }
            return null;
        }
        return null;
    }
    public void clear(){check();cache.clear();mods=null;}
}
