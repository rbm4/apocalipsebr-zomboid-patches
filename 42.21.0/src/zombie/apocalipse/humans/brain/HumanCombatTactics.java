package zombie.apocalipse.humans.brain;

import java.util.Objects;
import java.util.function.DoubleSupplier;

/** Local spacing policy. Each engagement owns reaction sampling and retreat cooldowns. */
public final class HumanCombatTactics {
    public enum Maneuver { HOLD, CLOSE, WITHDRAW }
    public record Order(Maneuver maneuver,float minimum,float maximum) { }
    private final DoubleSupplier random;
    private long nextDecision,nextRetreat;
    private boolean sampled;
    private int weaponId;
    private Order order;
    public HumanCombatTactics(DoubleSupplier random){this.random=Objects.requireNonNull(random);}
    public static float minimum(HumanCombatInventory.Weapon weapon){
        return weapon.ranged()?Math.min(4.5F,weapon.reach()*.45F):weapon.reach()*.55F;
    }
    public static float maximum(HumanCombatInventory.Weapon weapon){
        return weapon.ranged()?Math.min(7F,weapon.reach()*.8F):weapon.reach()*.9F;
    }
    private long delay(long minimum,long spread){return minimum+(long)(Math.clamp(random.getAsDouble(),0,1)*spread);}
    public Order update(HumanCombatInventory.Weapon weapon,double distance,boolean clear,boolean recovering,long now){
        float min=minimum(weapon),max=maximum(weapon);
        if(!sampled||weaponId!=weapon.itemId()){
            weaponId=weapon.itemId();
            sampled=true;nextDecision=now+delay(300_000_000L,350_000_000L);
            // A surprised actor does not instantly dodge a target already in its personal space.
            order=new Order(distance>max||!clear?Maneuver.CLOSE:Maneuver.HOLD,min,max);
            return order;
        }
        if(now<nextDecision)return order;
        nextDecision=now+delay(300_000_000L,350_000_000L);
        float pressure=recovering&&!weapon.ranged()?weapon.reach()*.85F:min;
        Maneuver maneuver;
        if(distance<pressure&&now>=nextRetreat){
            maneuver=Maneuver.WITHDRAW;nextRetreat=now+delay(1_600_000_000L,800_000_000L);
        }else maneuver=distance>max||!clear?Maneuver.CLOSE:Maneuver.HOLD;
        order=new Order(maneuver,min,max);return order;
    }
}
