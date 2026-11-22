package zombie.apocalipse.humans.speech;

import java.util.Objects;

/** Detached facts only. Never infer a wound type from weapon presence alone. */
public record SpeechContext(Player player, float healthRatio, float damage, Temperament temperament,
                            AttackKind attack, int aggressors, boolean npcStartedFight) {
    public enum Temperament { STEADY, FRESH, VETERAN, PREDATOR }
    public enum AttackKind { ANY, MELEE, FIREARM, ZOMBIE, OTHER }
    public record Player(String kind,String account,String username) {
        public Player {
            if(!("steam".equals(kind)||"username".equals(kind))||account==null||account.isBlank()||account.length()>128
                ||username==null||username.isBlank()||username.length()>128)throw new IllegalArgumentException("Invalid speech player");
        }
    }
    public SpeechContext {
        Objects.requireNonNull(temperament);Objects.requireNonNull(attack);
        if(!Float.isFinite(healthRatio)||healthRatio<0||healthRatio>1||!Float.isFinite(damage)||damage<0||aggressors<0||aggressors>128)
            throw new IllegalArgumentException("Invalid speech context");
    }
    public static SpeechContext neutral(float health){return new SpeechContext(null,health,0,Temperament.STEADY,AttackKind.ANY,0,false);}
    public String safeUsername(){
        if(player==null)return "";
        // PZ rich-text delimiters/control characters must not become chat markup.
        return player.username().replaceAll("[\\p{Cntrl}<>\\[\\]{}]", "").trim();
    }
}
