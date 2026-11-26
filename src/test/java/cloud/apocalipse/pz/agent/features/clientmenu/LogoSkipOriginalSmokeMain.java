package cloud.apocalipse.pz.agent.features.clientmenu;

import java.lang.instrument.Instrumentation;
import zombie.GameWindow;
import zombie.gameStates.GameState;
import zombie.gameStates.GameStateMachine;
import zombie.gameStates.TISLogoState;

/** Forked fixture: run against the original game JAR with the client-menu hook installed. */
public final class LogoSkipOriginalSmokeMain {
    public static void premain(String ignored, Instrumentation instrumentation) {
        var module = new ClientMenuModule();
        module.initialize();
        module.configure(new net.bytebuddy.agent.builder.AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(net.bytebuddy.agent.builder.AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly()))
            .installOn(instrumentation);
    }

    public static void main(String[] args) {
        GameStateMachine machine = new GameStateMachine();
        GameWindow.states = machine;
        machine.states.add(new TISLogoState());
        GameState menu = new GameState() {
            @Override public GameStateMachine.StateAction update() { return GameStateMachine.StateAction.Remain; }
        };
        machine.states.add(menu);

        machine.update();
        if (machine.current != menu || machine.states.size() != 1 || machine.firstrun)
            throw new AssertionError("logo state was not skipped into the next startup state");
        System.out.println("Client-menu startup logo hook passed");
    }
}
