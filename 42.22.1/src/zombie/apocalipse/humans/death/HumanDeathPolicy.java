package zombie.apocalipse.humans.death;

import java.util.*;
import java.util.random.RandomGenerator;

/** Detached, per-NPC authored respawn and corpse-loot configuration. */
public record HumanDeathPolicy(String respawnProfile,boolean enabled,long delayMs,LootTable loot) {
    public static final HumanDeathPolicy DEFAULT=new HumanDeathPolicy("npc-default",true,600000,LootTable.EMPTY);
    public HumanDeathPolicy {
        identifier(respawnProfile);Objects.requireNonNull(loot);
        if(delayMs<1000||delayMs>2592000000L)throw new IllegalArgumentException("Respawn delay must be 1 second to 30 days");
    }
    static void identifier(String value){if(value==null||!value.matches("[A-Za-z0-9_.-]{1,128}"))throw new IllegalArgumentException("Invalid mortality identifier");}
    public enum Rarity { COMMON, RARE, EPIC, LEGENDARY }
    public record Drop(String item,int quantity) { }
    public record Entry(Rarity rarity,String item,int weight,int minimum,int maximum) {
        public Entry {
            Objects.requireNonNull(rarity);identifier(item);
            if(!item.contains(".")||weight<1||weight>100000||minimum<1||maximum<minimum||maximum>20)throw new IllegalArgumentException("Invalid loot entry");
        }
    }
    public record LootTable(String id,int rolls,List<Integer> rarityWeights,List<Entry> entries) {
        public static final LootTable EMPTY=new LootTable("npc-default",1,List.of(6000,3000,900,100),List.of());
        public LootTable {
            identifier(id);rarityWeights=List.copyOf(rarityWeights);entries=List.copyOf(entries);
            if(rolls<0||rolls>16||rarityWeights.size()!=4||rarityWeights.stream().anyMatch(w->w<0||w>10000)
                ||rarityWeights.stream().mapToInt(Integer::intValue).sum()!=10000||entries.size()>128)throw new IllegalArgumentException("Invalid loot table");
        }
        public List<Drop> roll(RandomGenerator random){
            var drops=new ArrayList<Drop>();
            for(int n=0;n<rolls;n++){
                int pick=random.nextInt(10000),tier=0;
                while(tier<3&&pick>=rarityWeights.get(tier)){pick-=rarityWeights.get(tier);tier++;}
                Rarity rarity=Rarity.values()[tier];var eligible=entries.stream().filter(e->e.rarity()==rarity).toList();
                int total=eligible.stream().mapToInt(Entry::weight).sum();if(total==0)continue;
                pick=random.nextInt(total);
                for(var entry:eligible){if(pick<entry.weight()){drops.add(new Drop(entry.item(),entry.minimum()+random.nextInt(entry.maximum()-entry.minimum()+1)));break;}pick-=entry.weight();}
            }
            return List.copyOf(drops);
        }
    }
}
