package cloud.apocalipse.pz.agent.features.clientmenu;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import zombie.GameWindow;
import zombie.core.fonts.AngelCodeFont;
import zombie.gameStates.GameState;
import zombie.gameStates.GameStateMachine;
import zombie.gameStates.TISLogoState;
import zombie.network.GameServer;
import zombie.ui.TextManager;

import static net.bytebuddy.matcher.ElementMatchers.*;

/** Forked fixture: run against the original game JAR with the client-menu hooks installed. */
public final class LogoSkipOriginalSmokeMain {
    private static final String VANILLA_WARNING_REACHED = "Vanilla warning reached font lookup";

    public static void premain(String ignored, Instrumentation instrumentation) {
        var module = new ClientMenuModule();
        module.initialize();
        module.configure(new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly()))
            .type(named("zombie.ui.TextManager"))
            .transform((dynamic, type, loader, javaModule, domain) -> dynamic.visit(Advice.to(WarningFontProbe.class)
                .on(named("getFontFromEnum").and(hasDescriptor("(Lzombie/ui/UIFont;)Lzombie/core/fonts/AngelCodeFont;")))))
            .installOn(instrumentation);
    }

    public static void main(String[] args) throws Exception {
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
        checkWarning();
    }

    private static void checkWarning() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        AngelCodeFont previousFont = TextManager.instance.font;
        String property = AgentConfiguration.CLIENT_MENU_SKIP_PHOTOSENSITIVITY_WARNING_PROPERTY;
        String previousSetting = System.getProperty(property);
        boolean previousServer = GameServer.server;
        try {
            // A non-null font enters vanilla rendering. The probe stops it before any native graphics call.
            TextManager.instance.font = (AngelCodeFont) unsafe.allocateInstance(AngelCodeFont.class);
            System.clearProperty(property);
            GameWindow.doEpilepsyWarningText();

            System.setProperty(property, "false");
            expectVanillaWarning();

            System.setProperty(property, "true");
            GameServer.server = true;
            expectVanillaWarning();
        } finally {
            TextManager.instance.font = previousFont;
            GameServer.server = previousServer;
            if (previousSetting == null) System.clearProperty(property);
            else System.setProperty(property, previousSetting);
        }
        System.out.println("Client-menu startup warning hook passed");
    }

    private static void expectVanillaWarning() {
        try {
            GameWindow.doEpilepsyWarningText();
            throw new AssertionError("Expected vanilla warning rendering");
        } catch (IllegalStateException failure) {
            if (!VANILLA_WARNING_REACHED.equals(failure.getMessage())) throw failure;
        }
    }

    public static final class WarningFontProbe {
        @Advice.OnMethodEnter
        public static void enter() {
            throw new IllegalStateException(VANILLA_WARNING_REACHED);
        }
    }
}
