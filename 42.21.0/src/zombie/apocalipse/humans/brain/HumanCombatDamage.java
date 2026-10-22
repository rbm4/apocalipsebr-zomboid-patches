package zombie.apocalipse.humans.brain;

/** NPC balance calibration, independent of inventory, native damage side effects and packet delivery. */
public final class HumanCombatDamage {
    private HumanCombatDamage(){ }
    // 42.21.0 native defaults: zero weapon skill applies .3, then melee consequences apply .15.
    // Restore base-weapon lethality for synthetic actors while retaining relative native skill,
    // condition, critical and server combat-setting effects. This is an NPC balance choice.
    public static float packetDamage(float nativeDamage,boolean ranged){
        if(!Float.isFinite(nativeDamage)||nativeDamage<0)throw new IllegalArgumentException("Invalid weapon damage");
        return nativeDamage/(ranged?.3F:.3F*.15F);
    }
}
