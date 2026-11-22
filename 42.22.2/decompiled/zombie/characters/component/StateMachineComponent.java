// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.characters.component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.ai.State;
import zombie.ai.StateMachine;
import zombie.characters.IsoGameCharacter;
import zombie.characters.action.ActionContext;
import zombie.characters.action.ActionState;
import zombie.characters.ecs.ECSComponent;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.core.skinnedmodel.advancedanimation.IAnimatable;
import zombie.core.skinnedmodel.advancedanimation.events.GlobalAnimEvent;
import zombie.debug.DebugType;
import zombie.util.lambda.PZOptional;

public class StateMachineComponent extends ECSComponent {
    private final StateMachine stateMachine;
    private State defaultState;
    private final HashMap<String, State> aiStateMap = new HashMap<>();
    private final Map<Class<?>, Map<State.Param<?>, Object>> stateMachineParams = new IdentityHashMap<>();
    private final AdvancedAnimator advancedAnimator;
    private final ActionContext actionContext;

    public StateMachineComponent(IsoGameCharacter owner) {
        this.stateMachine = new StateMachine(owner);
        this.advancedAnimator = new AdvancedAnimator();
        this.actionContext = new ActionContext(owner);
    }

    public StateMachine getStateMachine() {
        return this.stateMachine;
    }

    public Map<State.Param<?>, Object> getStateMachineParams(Class<?> clazz) {
        return this.stateMachineParams.computeIfAbsent(clazz, k -> new IdentityHashMap<>());
    }

    public AdvancedAnimator getAdvancedAnimator() {
        return this.advancedAnimator;
    }

    public ActionContext getActionContext() {
        return this.actionContext;
    }

    public void invokeGlobalAnimEvent(GlobalAnimEvent animEvent) {
        this.advancedAnimator.invokeGlobalAnimEvent(animEvent);
    }

    public void init(IsoGameCharacter owner) {
        this.advancedAnimator.init(owner, owner.GetAnimSetName());
        this.actionContext.addOnStateChanged(this::actionStateChanged);
        this.actionContext.addOnStateChanged(owner);
    }

    private void actionStateChanged(ActionContext sender) {
        this.getAdvancedAnimator()
            .setState(sender.getCurrentStateName(), sender.getChildStates(StateMachineComponent.L_actionStateChanged.stateNames, ActionState::getName));

        try {
            this.getStateMachine().activeStateChanged++;
            State rootAiState = PZOptional.ifPresent(this.tryGetAIState(sender.getCurrentStateName()), this.defaultState);
            List<State> childAiStates = sender.getChildStates(
                StateMachineComponent.L_actionStateChanged.states, this.aiStateMap, StateMachineComponent::getAiStateFromActionState
            );
            this.getStateMachine().changeState(rootAiState, childAiStates, false);
        } finally {
            this.getStateMachine().activeStateChanged--;
        }

        DebugType.AnimationRecorder.debugln(this::getCurrentStateStackDebugString);
    }

    private @NonNull String getCurrentStateStackDebugString() {
        StringBuilder logLine = new StringBuilder();
        logLine.append(PZOptional.ifPresent(this.tryGetECSOwnerEntityAs(IAnimatable.class), "Not-IAnimatable", IAnimatable::getUID));
        logLine.append(" { ");
        logLine.append("ActionState { ").append(this.getActionContext().getCurrentStateName());
        this.getActionContext().visitChildStates(state -> logLine.append(", ").append(state.getName()));
        logLine.append(" }");
        logLine.append(" AIState { ");
        logLine.append(PZOptional.ifPresent(this.getStateMachine().getCurrent(), this.defaultState).getName());
        this.getStateMachine().visitSubStates(state -> logLine.append(", ").append(PZOptional.ifPresent(state, "null", State::getName)));
        logLine.append(" }");
        logLine.append(" }");
        return logLine.toString();
    }

    public void setDefaultState() {
        DebugType.AnimationRecorder.getLogStream().debuglnWithTraceOffset(2, "setDefaultState() called.");
        this.getActionContext().setDefaultState();
        this.ensureActionContextSyncedToAIState();
    }

    private void ensureActionContextSyncedToAIState() {
        if (!this.isStateMachineSyncedToActionContext()) {
            DebugType.AnimationRecorder.debugln("ActionState -> AIState mismatch detected.");
            this.actionStateChanged(this.getActionContext());
        }
    }

    private boolean isStateMachineSyncedToActionContext() {
        ActionContext actionContext = this.getActionContext();
        return this.getStateMachine()
            .statesMatch(
                PZOptional.ifPresent(this.tryGetAIState(actionContext.getCurrentStateName()), this.defaultState),
                actionContext.getChildStates(
                    StateMachineComponent.L_actionStateChanged.states, this.aiStateMap, StateMachineComponent::getAiStateFromActionState
                )
            );
    }

    private static State getAiStateFromActionState(ActionState actionState, HashMap<String, State> aiStateMap) {
        return aiStateMap.get(actionState.getName().toLowerCase());
    }

    public void setDefaultState(State defaultState) {
        this.defaultState = defaultState;
    }

    public State tryGetAIState(String stateName) {
        return this.aiStateMap.get(stateName.toLowerCase(Locale.ENGLISH));
    }

    public void clearAIStateMap() {
        this.aiStateMap.clear();
    }

    public void registerAIState(String name, State aiState) {
        this.aiStateMap.put(name.toLowerCase(Locale.ENGLISH), aiState);
    }

    public State getDefaultState() {
        return this.defaultState;
    }

    public UpdateSchedulerSimulationLevel getMinimumSimulationLevel() {
        return this.stateMachine.getMinimumSimulationLevel();
    }

    private static final class L_actionStateChanged {
        private static final List<String> stateNames = new ArrayList<>();
        private static final List<State> states = new ArrayList<>();
    }
}
