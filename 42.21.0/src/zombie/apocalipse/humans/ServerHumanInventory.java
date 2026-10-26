package zombie.apocalipse.humans;

import java.util.List;
import zombie.apocalipse.humans.brain.HumanCombatInventory;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.types.HandWeapon;

/** Temporary fixed-loadout inventory controller. Real item instances; finite, transient ammunition. */
final class ServerHumanInventory implements HumanCombatInventory {
    static final String MELEE="Base.Crowbar",RANGED="Base.Pistol",CLIP="Base.9mmClip",BULLET="Base.Bullets9mm";
    private final ServerHuman human;
    private HandWeapon crowbar,pistol;
    private InventoryItem clip,bullets;
    private boolean initialized;
    ServerHumanInventory(ServerHuman human){this.human=human;}
    /** Combat test supplies are not an implicit loot table. Worn/native personal items remain. */
    void releaseForCorpse(){
        if(!initialized)return;
        human.player.setPrimaryHandItem(null);human.player.setSecondaryHandItem(null);
        for(var item:List.of(crowbar,pistol,clip,bullets))human.player.getInventory().Remove(item);
    }
    private void initialize(){
        if(initialized)return;
        // Validate the entire fixed fixture before adding anything to the native inventory.
        var melee=InventoryItemFactory.CreateItem(MELEE);var ranged=InventoryItemFactory.CreateItem(RANGED);
        var magazine=InventoryItemFactory.CreateItem(CLIP);var ammunition=InventoryItemFactory.CreateItem(BULLET);
        if(!(melee instanceof HandWeapon m)||!(ranged instanceof HandWeapon r)||magazine==null||ammunition==null)
            throw new IllegalStateException("Fixed NPC combat loadout unavailable");
        crowbar=m;pistol=r;clip=magazine;bullets=ammunition;
        int capacity=Math.max(1,pistol.getMaxAmmo());
        pistol.setContainsClip(true);pistol.setRoundChambered(true);pistol.setCurrentAmmoCount(capacity-1);
        pistol.setJammed(false);clip.setCurrentAmmoCount(0);bullets.setCount(30);
        for(var item:List.of(crowbar,pistol,clip,bullets))human.player.getInventory().AddItem(item);
        initialized=true;
    }
    HandWeapon item(int id){
        if(!initialized)return null;
        var item=human.player.getInventory().getItemWithID(id);
        return item==crowbar||item==pistol?(HandWeapon)item:null;
    }
    private int reserve(){return human.player.getInventory().getItemWithID(bullets.getID())==bullets?Math.max(0,bullets.getCount()):0;}
    private Weapon describe(HandWeapon item){
        boolean ranged=item.isRanged();
        float reach=Math.clamp(item.getMaxRange(human.player)+(ranged?0:.24F),.7F,ranged?15:3);
        return new Weapon(item.getID(),item.getFullType(),ranged,item(item.getID())==item&&item.getCondition()>0,
            item.isJammed(),reach,ranged?item.getCurrentAmmoCount()+(item.isRoundChambered()?1:0):0,ranged?reserve():0,
            ranged?600_000_000L:400_000_000L,ranged?700_000_000L:800_000_000L);
    }
    public List<Weapon> weapons(){initialize();return List.of(describe(crowbar),describe(pistol));}
    public boolean equip(int id){
        var weapon=item(id);if(weapon==null||weapon.getCondition()<=0)return false;
        if(human.player.getPrimaryHandItem()==weapon)return true;
        human.player.setPrimaryHandItem(weapon);
        human.player.setSecondaryHandItem(weapon.isTwoHandWeapon()?weapon:null);
        human.player.setUseHandWeapon(weapon);
        ServerHumanTransport.equipment(human);
        return true;
    }
    public boolean spendRound(int id){
        var weapon=item(id);
        if(weapon==null||!weapon.isRanged()||weapon.isJammed()||!weapon.isRoundChambered())return false;
        if(weapon.getCurrentAmmoCount()>0)weapon.setCurrentAmmoCount(weapon.getCurrentAmmoCount()-1);
        else weapon.setRoundChambered(false);
        weapon.setSpentRoundChambered(false);
        ServerHumanTransport.equipment(human);
        return true;
    }
    public boolean reload(int id){
        var weapon=item(id);if(weapon==null||!weapon.isRanged()||human.player.getInventory().getItemWithID(clip.getID())!=clip)return false;
        int current=weapon.getCurrentAmmoCount()+(weapon.isRoundChambered()?1:0),available=reserve();
        int used=Math.min(available,Math.max(0,weapon.getMaxAmmo()-current));
        if(used==0&&!weapon.isJammed())return false;
        int loaded=current+used;
        weapon.setContainsClip(true);weapon.setJammed(false);weapon.setRoundChambered(loaded>0);
        weapon.setCurrentAmmoCount(Math.max(0,loaded-1));bullets.setCount(available-used);
        if(bullets.getCount()==0)human.player.getInventory().Remove(bullets);
        ServerHumanTransport.equipment(human);
        return loaded>0;
    }
}
