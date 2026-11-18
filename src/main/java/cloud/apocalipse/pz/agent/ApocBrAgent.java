package cloud.apocalipse.pz.agent;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.ClassFileVersion;

/** JVM entry point; feature modules are assembled by {@link AgentRuntime}. */
public final class ApocBrAgent {
    private ApocBrAgent() {
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) {
        int javaVersion = ClassFileVersion.ofThisVm().getJavaVersion();
        AgentRuntime.install(instrumentation);
        System.out.println("[ApocBR-Agent] Loaded on Java " + javaVersion
            + "; feature modules installed.");
    }
}
