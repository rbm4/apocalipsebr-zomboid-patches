// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.ai.states;

import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.skills.PerkFactory;
import zombie.scripting.objects.MoodleType;

public final class ClimbSystem {
    private static final String EVENT_CLIMB = "EventClimb";
    private static final String EVENT_CLIMB_DOWN = "EventClimbDown";
    public static final String EVENT_CLIMB_ROPE = "EventClimbRope";
    public static final String EVENT_CLIMB_DOWN_ROPE = "EventClimbDownRope";
    public static final String EVENT_CLIMB_LADDER = "EventClimbLadder";
    public static final String EVENT_CLIMB_DOWN_LADDER = "EventClimbDownLadder";

    private ClimbSystem() {
    }

    public static boolean isDirectionChanged(IsoGameCharacter isoGameCharacter) {
        if (isoGameCharacter.isRemote()) {
            return false;
        } else {
            boolean isInClimbState = isoGameCharacter.isCurrentState(ClimbState.instance());
            boolean isClimbingLadder = isInClimbState && isoGameCharacter.isClimbingLadder();
            boolean isInClimbDownState = isoGameCharacter.isCurrentState(ClimbDownState.instance());
            boolean isClimbingDownLadder = isInClimbDownState && isoGameCharacter.isClimbingDownLadder();
            if ((isClimbingLadder || isClimbingDownLadder) && !isoGameCharacter.getVariableBoolean("climbLadderLoop")) {
                return false;
            } else {
                boolean isPressedUp = false;
                boolean isPressedDown = false;
                if (isoGameCharacter instanceof IsoPlayer player) {
                    isPressedUp = player.isForwardButtonDown();
                    isPressedDown = player.isBackwardButtonDown();
                }

                if (!isPressedUp && !isPressedDown) {
                    return false;
                } else if (isInClimbState && isPressedDown) {
                    isoGameCharacter.reportEvent("EventClimbDown");
                    return true;
                } else if (isInClimbDownState && isPressedUp) {
                    isoGameCharacter.reportEvent("EventClimb");
                    return true;
                } else {
                    return false;
                }
            }
        }
    }

    public static float getClimbingMuscleStrain(IsoGameCharacter isoGameCharacter, float factor) {
        int maxSkillFactor = 15;
        float strengthWeight = 2.0F;
        int skillDivisor = 3;
        float skillScaling = 10.0F;
        float timeMultiplierBase = 0.3F;
        int nimblePerkLevel = isoGameCharacter.getPerkLevel(PerkFactory.Perks.Nimble);
        int strengthPerkLevel = isoGameCharacter.getPerkLevel(PerkFactory.Perks.Strength);
        int fitnessPerkLevel = isoGameCharacter.getPerkLevel(PerkFactory.Perks.Fitness);
        float skillFactor = (15.0F - (nimblePerkLevel + Math.max(strengthPerkLevel, fitnessPerkLevel) * 2.0F) / 3.0F) / 10.0F;
        int heavyLoadMuddleLevel = isoGameCharacter.getMoodles().getMoodleLevel(MoodleType.HEAVY_LOAD);
        return factor * (heavyLoadMuddleLevel + 1) * skillFactor * GameTime.instance.getMultiplier() * 0.3F;
    }
}
