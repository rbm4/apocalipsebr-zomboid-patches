package zombie.apocalipse.humans.speech;

/** Stable event vocabulary. Unwired situations remain callable through the same engine. */
public enum SpeechEvent {
    ATTACK_PLAYER, ATTACKED_BY_PLAYER, ZOMBIE_KILL, DAMAGE_TAKEN,
    FIRST_HIT, REPEATED_HIT, NEW_AGGRESSOR, MULTIPLE_AGGRESSORS,
    CHASE_STARTED, CHASING, TARGET_LOST, TARGET_REACQUIRED, TARGET_ESCAPED,
    PLAYER_HIT_LANDED, PLAYER_ATTACK_MISSED, ZOMBIE_SPOTTED, ZOMBIE_ATTACK,
    RELOAD, OUT_OF_AMMO, WEAPON_JAM, LOW_HEALTH, BLEEDING, RETREAT, SURRENDER,
    ALLY_ATTACKED, ALLY_DOWN, COMBAT_ENDED;

    public SpeechEvent pool() {
        return switch(this) {
            case FIRST_HIT, REPEATED_HIT, NEW_AGGRESSOR, MULTIPLE_AGGRESSORS -> ATTACKED_BY_PLAYER;
            default -> this;
        };
    }
}
