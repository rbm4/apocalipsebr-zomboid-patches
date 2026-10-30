// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.ai.states;

import zombie.UpdateSchedulerSimulationLevel;
import zombie.UsedFromLua;
import zombie.ai.State;
import zombie.audio.parameters.ParameterZombieState;
import zombie.characters.HitReactionNetworkAI;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.core.math.PZMath;
import zombie.core.skinnedmodel.advancedanimation.AnimEvent;
import zombie.core.skinnedmodel.advancedanimation.AnimLayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.iso.Vector3;
import zombie.network.GameClient;

@UsedFromLua
public final class StaggerBackState extends State {
    private static final StaggerBackState INSTANCE = new StaggerBackState();
    private static final Vector3 staggerDestinationPos = new Vector3();

    public static StaggerBackState instance() {
        return INSTANCE;
    }

    private StaggerBackState() {
        super(false, false, false, false);
    }

    @Override
    public void enter(IsoGameCharacter owner) {
        owner.setStateEventDelayTimer(this.getMaxStaggerTime(owner));
        if (GameClient.client && HitReactionNetworkAI.isEnabled(owner)) {
            if (owner.isRemote()) {
                owner.setDeferredMovementEnabled(false);
            } else if (owner instanceof IsoZombie ownerZombie) {
                owner.getCurrentAnimationTranslationTarget(staggerDestinationPos);
                GameClient.sendZombieStagger(
                    ownerZombie,
                    staggerDestinationPos.x,
                    staggerDestinationPos.y,
                    (byte)PZMath.fastfloor(staggerDestinationPos.z),
                    owner.getDirectionAngleRadians()
                );
            }
        }
    }

    @Override
    public void execute(IsoGameCharacter owner) {
        if (owner.hasAnimationPlayer()) {
            owner.getAnimationPlayer().setTargetToAngle();
        }
    }

    @Override
    public void exit(IsoGameCharacter owner) {
        if (owner.isZombie()) {
            ((IsoZombie)owner).setStaggerBack(false);
        }

        owner.setShootable(true);
        owner.setDeferredMovementEnabled(true);
    }

    public float getMaxStaggerTime(IsoGameCharacter owner) {
        float time = 35.0F * owner.getHitForce() * owner.getStaggerTimeMod();
        if (time < 20.0F) {
            time = 20.0F;
        } else if (time > 30.0F) {
            time = 30.0F;
        }

        return time;
    }

    @Override
    public void animEvent(IsoGameCharacter owner, AnimLayer layer, AnimationTrack track, AnimEvent event) {
        if (event.eventName.equalsIgnoreCase("FallOnFront")) {
            owner.setFallOnFront(Boolean.parseBoolean(event.parameterValue));
        }

        if (event.eventName.equalsIgnoreCase("SetState")) {
            IsoZombie zombie = (IsoZombie)owner;
            zombie.parameterZombieState.setState(ParameterZombieState.State.Pushed);
        }
    }

    @Override
    public UpdateSchedulerSimulationLevel getMinimumSimulationLevel() {
        return UpdateSchedulerSimulationLevel.HALF;
    }
}
