// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.ai.states;

import java.util.Objects;
import zombie.UsedFromLua;
import zombie.Lua.LuaEventManager;
import zombie.ai.State;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.advancedanimation.AnimEvent;
import zombie.core.skinnedmodel.advancedanimation.AnimLayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.iso.objects.IForceablePortal;

@UsedFromLua
public class ForceOpenState extends State {
    private static final ForceOpenState INSTANCE = new ForceOpenState();
    public static final State.Param<IForceablePortal> OBJECT = State.Param.of("object", IForceablePortal.class);
    public static final State.Param<ForceOpenState.ForcingType> FORCING_TYPE = State.Param.of("forcingType", ForceOpenState.ForcingType.class);
    public static final State.Param<Boolean> FORCING_VAR_VALUE = State.Param.of("forcingVar", Boolean.class);
    public static final State.Param<String> OUTCOME_VAR_VALUE = State.Param.of("outcomeVar", String.class);
    public static final State.Param<String> STOP_AFTER_VAR_VALUE = State.Param.of("stopAfterVar", String.class);
    public static final int BURGLAR_SAVE_CHANCE = 50;

    public static ForceOpenState instance() {
        return INSTANCE;
    }

    public ForceOpenState() {
        super(true, true, true, true);
    }

    @Override
    public void enter(IsoGameCharacter owner) {
        owner.setIgnoreMovement(true);
        owner.setHideWeaponModel(false);
        IForceablePortal object = owner.get(OBJECT);
        if (!object.isNotValidForForcing()) {
            ForceOpenState.ForcingType forcingType = Objects.requireNonNull(owner.get(FORCING_TYPE));
            clearVariables(owner, forcingType);
            owner.faceThisEdgeElement(object);
            owner.setVariable(forcingType.forcingVar, true);
            this.setParams(owner, State.Stage.Enter);
        }
    }

    @Override
    public void execute(IsoGameCharacter owner) {
        ForceOpenState.ForcingType forcingType = owner.get(FORCING_TYPE);
        if (owner.getVariableBoolean(forcingType.forcingVar)) {
            IsoPlayer player = (IsoPlayer)owner;
            IForceablePortal object = owner.get(OBJECT);
            if (object != null && !object.isLocked() && "struggle".equals(owner.getVariableString(forcingType.outcomeVar))) {
                owner.setVariable(forcingType.outcomeVar, "success");
            }

            if (!player.pressedMovement(false)
                && !player.pressedCancelAction()
                && object != null
                && (!object.isNotValidForForcing() || "success".equals(owner.getVariableString(forcingType.outcomeVar)))) {
                if (!owner.isRemote()) {
                    player.setCollidable(true);
                    player.updateLOS();
                    owner.faceThisEdgeElement(object);
                }
            } else {
                owner.clearVariable(forcingType.forcingVar);
            }
        }
    }

    @Override
    public void exit(IsoGameCharacter owner) {
        super.exit(owner);
        ForceOpenState.ForcingType forcingType = owner.get(FORCING_TYPE);
        if (forcingType != null) {
            clearVariables(owner, forcingType);
        }

        owner.setIgnoreMovement(false);
        this.setParams(owner, State.Stage.Exit);
    }

    @Override
    public void animEvent(IsoGameCharacter owner, AnimLayer layer, AnimationTrack track, AnimEvent event) {
        ForceOpenState.ForcingType forcingType = owner.get(FORCING_TYPE);
        if (owner.getVariableBoolean(forcingType.forcingVar)) {
            IForceablePortal object = owner.get(OBJECT);
            if (object.isNotValidForForcing() && !"success".equals(owner.getVariableString(forcingType.outcomeVar))) {
                owner.clearVariable(forcingType.forcingVar);
            } else {
                if (event.eventName.equalsIgnoreCase(forcingType.animLoopedEvent)) {
                    if ("start".equalsIgnoreCase(event.parameterValue)) {
                        if (!"success".equals(owner.getVariableString(forcingType.outcomeVar))) {
                            owner.setVariable(forcingType.outcomeVar, !object.isLocked() ? "success" : "struggle");
                        }

                        return;
                    }

                    if (event.parameterValue.equalsIgnoreCase(owner.getVariableString(forcingType.stopAfterVar))) {
                        owner.clearVariable(forcingType.forcingVar);
                    }
                }

                if (event.eventName.equalsIgnoreCase(forcingType.successEvent)) {
                    this.onSuccess(owner, object);
                } else if (event.eventName.equalsIgnoreCase(forcingType.struggleSoundEvent)
                    && "struggle".equals(owner.getVariableString(forcingType.outcomeVar))) {
                    object.forceStruggleEffects(owner);
                }
            }
        }
    }

    @Override
    public boolean isDoingActionThatCanBeCancelled() {
        return true;
    }

    public void forceSucceeded(IsoGameCharacter owner) {
        ForceOpenState.ForcingType forcingType = owner.get(FORCING_TYPE);
        if (forcingType != null) {
            owner.setVariable(forcingType.outcomeVar, "success");
        }
    }

    public void forceStopped(IsoGameCharacter owner) {
        ForceOpenState.ForcingType forcingType = owner.get(FORCING_TYPE);
        if (forcingType != null && !"success".equals(owner.getVariableString(forcingType.outcomeVar))) {
            clearVariables(owner, forcingType);
        }
    }

    private void onSuccess(IsoGameCharacter owner, IForceablePortal object) {
        ForceOpenState.ForcingType forcingType = owner.get(FORCING_TYPE);
        owner.setVariable(forcingType.stopAfterVar, "success");
        if (!object.isNotValidForForcing() && ((IsoPlayer)owner).isLocalPlayer()) {
            object.forceOpen(owner);
            LuaEventManager.triggerEvent("OnContainerUpdate");
        }
    }

    private static void clearVariables(IsoGameCharacter owner, ForceOpenState.ForcingType forcingType) {
        owner.clearVariable(forcingType.forcingVar);
        owner.clearVariable(forcingType.outcomeVar);
        owner.clearVariable(forcingType.stopAfterVar);
    }

    public void setParams(IsoGameCharacter owner, IForceablePortal object, ForceOpenState.ForcingType forcingType) {
        owner.clear(this);
        owner.set(OBJECT, object);
        owner.set(FORCING_TYPE, forcingType);
    }

    @Override
    public void setParams(IsoGameCharacter owner, State.Stage stage) {
        ForceOpenState.ForcingType forcingType = owner.get(FORCING_TYPE);
        if (owner.isLocal()) {
            owner.set(FORCING_VAR_VALUE, owner.getVariableBoolean(forcingType.forcingVar));
            owner.set(OUTCOME_VAR_VALUE, owner.getVariableString(forcingType.outcomeVar));
            owner.set(STOP_AFTER_VAR_VALUE, owner.getVariableString(forcingType.stopAfterVar));
        } else if (State.Stage.Exit == stage) {
            clearVariables(owner, forcingType);
        } else {
            owner.setVariable(forcingType.forcingVar, owner.get(FORCING_VAR_VALUE));
            owner.setVariable(forcingType.outcomeVar, owner.get(OUTCOME_VAR_VALUE));
            owner.setVariable(forcingType.stopAfterVar, owner.get(STOP_AFTER_VAR_VALUE));
        }

        super.setParams(owner, stage);
    }

    @UsedFromLua
    public static enum ForcingType {
        DOOR_LEFT(
            "bForceOpenDoorLeft",
            "ForceOpenDoorLeftOutcome",
            "StopAfterForceDoorLeftAnimLooped",
            "ForceOpenDoorLeftAnimLooped",
            "ForceOpenDoorLeftSuccess",
            "ForceOpenDoorLeftStruggleSound"
        ),
        DOOR_RIGHT(
            "bForceOpenDoorRight",
            "ForceOpenDoorRightOutcome",
            "StopAfterForceDoorRightAnimLooped",
            "ForceOpenDoorRightAnimLooped",
            "ForceOpenDoorRightSuccess",
            "ForceOpenDoorRightStruggleSound"
        ),
        GARAGE_DOOR(
            "bForceOpenGarageDoor",
            "ForceOpenGarageDoorOutcome",
            "StopAfterForceGarageDoorAnimLooped",
            "ForceOpenGarageDoorAnimLooped",
            "ForceOpenGarageDoorSuccess",
            "ForceOpenGarageDoorStruggleSound"
        ),
        WINDOW(
            "bForceOpenWindow",
            "ForceOpenWindowOutcome",
            "StopAfterForceWindowAnimLooped",
            "ForceOpenWindowAnimLooped",
            "ForceOpenWindowSuccess",
            "ForceOpenWindowStruggleSound"
        );

        public final String forcingVar;
        public final String outcomeVar;
        public final String stopAfterVar;
        public final String animLoopedEvent;
        public final String successEvent;
        public final String struggleSoundEvent;

        private ForcingType(
            final String forcingVar,
            final String outcomeVar,
            final String stopAfterVar,
            final String animLoopedEvent,
            final String successEvent,
            final String struggleSoundEvent
        ) {
            this.forcingVar = forcingVar;
            this.outcomeVar = outcomeVar;
            this.stopAfterVar = stopAfterVar;
            this.animLoopedEvent = animLoopedEvent;
            this.successEvent = successEvent;
            this.struggleSoundEvent = struggleSoundEvent;
        }
    }
}
