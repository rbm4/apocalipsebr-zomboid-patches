package cloud.apocalipse.pz.agent.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Validates configured task settings in a child JVM without loading the game or opening a connection. */
public final class IntegrationConfigurationSmokeMain {
    private IntegrationConfigurationSmokeMain() { }

    public static void main(String[] args) throws Exception {
        String url = System.getenv("APOCBR_POSTGRES_URL");
        if (args.length == 0 || url == null || !url.startsWith("jdbc:postgresql://")
                || !sha256(url).equals(args[0])) {
            throw new AssertionError("JDBC environment value did not arrive intact");
        }
        if (!Boolean.getBoolean(IntegrationLaunchSettings.TASK_CONFIGURATION)
                || !Boolean.getBoolean("apocbr.postgres.enabled")) {
            throw new AssertionError("Configured PostgreSQL mode did not reach the JVM");
        }
        if (args.length > 1 && args[1].equals("child")) {
            System.out.println("Configured task properties and full JDBC environment reached child JVM intact; no connection opened.");
            return;
        }
        String executable = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(executable,
            "-D" + IntegrationLaunchSettings.TASK_CONFIGURATION + "=true"));
        IntegrationLaunchSettings.properties(System.getProperties(), Path.of("target/configuration-smoke"),
            "http://127.0.0.1:1").forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.addAll(List.of("-cp", System.getProperty("java.class.path"),
            IntegrationConfigurationSmokeMain.class.getName(), args[0], "child"));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        IntegrationLaunchSettings.configureEnvironment(builder.environment(), System.getenv(), System.getProperties());
        Process child = builder.start();
        try {
            if (!child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("Configuration child timed out");
            }
            if (child.exitValue() != 0) throw new AssertionError("Configuration child validation failed");
            System.out.print(new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
