package zombie.apocalipse.humans.brain;

import java.util.List;

/** Inventory controller boundary. The first adapter supplies a fixed, finite test loadout. */
public interface HumanCombatInventory {
    record Weapon(int itemId,String fullType,boolean ranged,boolean usable,boolean jammed,
                  float reach,int rounds,int reserve,long windupNanos,long recoveryNanos) { }
    List<Weapon> weapons();
    boolean equip(int itemId);
    boolean spendRound(int itemId);
    boolean reload(int itemId);
}
