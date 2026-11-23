package cloud.apocalipse.pz.agent.integration;

import cloud.apocalipse.pz.agent.integration.probe.StreamingWorkload;
import cloud.apocalipse.pz.agent.integration.probe.MovingObjectLoad;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import static org.junit.jupiter.api.Assertions.*;

/** Test infrastructure only. Never puts unit-test doubles on the game process classpath. */
public final class ServerTestContext implements AutoCloseable {
    private static final String PROBE = "cloud.apocalipse.pz.agent.integration.probe.HookProbe";
    private final Path directory;
    private final Path gameJar;
    private final Path home;
    private final List<String> telemetry = new CopyOnWriteArrayList<>();
    private HttpServer receiver;
    private Process process;
    private Thread logPump;
    private volatile boolean ready;
    private volatile boolean transformationFailed;
    private volatile IOException logFailure;
    private final Properties settings;

    private ServerTestContext(Path directory, Path gameJar, Path home, Properties settings) {
        this.directory = directory;
        this.gameJar = gameJar;
        this.home = home;
        this.settings = settings;
    }

    static ServerTestContext start() throws Exception {
        return start(System.getProperties());
    }

    static ServerTestContext start(Properties settings) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path jar = Path.of(settings.getProperty("pz.game.jar", windows
            ? "Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar"
            : "/opt/pzserver/java/projectzomboid.jar")).toRealPath();
        Path home = Path.of(settings.getProperty("pz.it.home", windows
            ? jar.getParent().toString() : "/opt/pzserver")).toRealPath();
        if (!Files.isDirectory(home.resolve("media"))) {
            throw new IllegalArgumentException("Missing game resources at " + home + "; set -Dpz.it.home");
        }
        Path output = Path.of("target/server-integration").toAbsolutePath();
        Files.createDirectories(output);
        var context = new ServerTestContext(Files.createTempDirectory(output, "run-"), jar, home, settings);
        System.out.println("[Server-IT] Artifacts: " + context.directory);
        try {
            context.launch(windows);
            return context;
        } catch (Throwable failure) {
            try { context.close(false); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void launch(boolean windows) throws Exception {
        Path agent = Path.of("target/apocbr-agent.jar").toRealPath();
        Path probe = createProbeJar();
        Files.createDirectories(directory.resolve("evidence"));
        Path cache = Files.createDirectories(directory.resolve("cache"));
        ChunkMigrationFixture.seed(directory, settings);
        Path config = Files.createDirectories(cache.resolve("Server"));
        int port = availablePort();
        int udpPort;
        do { udpPort = availablePort(); } while (Math.abs(udpPort - port) < 2);
        String streamingSeed = Boolean.parseBoolean(settings.getProperty("pz.it.streaming")) ? "ApocBRStreamingFixture42" : "";
        Files.writeString(config.resolve("agent-integration.ini"), """
            Public=false
            UPnP=false
            AnnounceDeath=false
            Open=false
            PauseEmpty=false
            Mods=
            WorkshopItems=
            RCONPort=0
            RCONPassword=
            Map=Muldraugh, KY
            MaxPlayers=2
            """ + "DefaultPort=" + port + "\nUDPPort=" + udpPort + "\nSeed=" + streamingSeed + "\n");

        if (!streamingSeed.isEmpty() && StreamingWorkload.from(settings).vehicleStress()) {
            Files.writeString(config.resolve("agent-integration_SandboxVars.lua"),
                "SandboxVars = { VERSION = 6, EnableVehicles = true, CarSpawnRate = 5 }\n");
        }

        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/api/v1/telemetry", exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readNBytes(65_537);
                if (!exchange.getRequestMethod().equals("POST") || body.length > 65_536) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                String payload = new String(body, StandardCharsets.UTF_8);
                telemetry.add(payload);
                Files.writeString(directory.resolve("telemetry-" + telemetry.size() + ".json"), payload);
                byte[] response = "{\"snapshotId\":1,\"serverId\":1,\"serverKey\":\"integration\"}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        receiver.start();
        String endpoint = "http://127.0.0.1:" + receiver.getAddress().getPort();
        String nativePath = settings.getProperty("pz.it.nativePath", String.join(File.pathSeparator,
            home.toString(), home.resolve(windows ? "win64" : "linux64").toString(),
            home.resolve("java").toString()));
        String javaExecutable = settings.getProperty("pz.it.java", Path.of(System.getProperty("java.home"), "bin",
            windows ? "java.exe" : "java").toString());
        List<String> command = new ArrayList<>(List.of(javaExecutable,
            "-Xms256m", "-Xmx" + settings.getProperty("pz.it.heap", "2g"),
            "--enable-native-access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
            "-Djava.awt.headless=true", "-Dpz.server.gui.textures=false", "-Dzomboid.steam=0",
            "-Djava.library.path=" + nativePath));
        var childSettings = IntegrationLaunchSettings.properties(settings, directory, endpoint);
        childSettings.forEach((key, value) -> command.add("-D" + key + "=" + value));
        boolean movingLoad = childSettings.containsKey(MovingObjectLoad.PROPERTY);
        String youngHeap = settings.getProperty(MovingObjectLoad.YOUNG_HEAP_PROPERTY);
        if (movingLoad) {
            if (youngHeap != null) {
                int mib = Integer.parseInt(youngHeap);
                if (mib <= 0) throw new IllegalArgumentException(MovingObjectLoad.YOUNG_HEAP_PROPERTY + " must be positive");
                command.add("-XX:+UseG1GC");
                command.add("-Xmn" + mib + "m");
            }
            command.add("-Xlog:gc,safepoint:stderr:timemillis,level,tags");
            if (Boolean.parseBoolean(settings.getProperty(MovingObjectLoad.JFR_PROPERTY))) {
                command.add("-XX:StartFlightRecording=settings=profile,dumponexit=true,maxsize=128m,filename="
                    + directory.resolve("moving-object-load.jfr").toAbsolutePath());
            }
        }
        command.addAll(List.of(
            "-javaagent:" + agent, "-javaagent:" + probe + "=" + directory.resolve("evidence"),
            "-cp", jarClasspath(probe), PROBE,
            "-cachedir=" + cache, "-servername", "agent-integration", "-nosteam",
            "-adminusername", "integration-admin", "-adminpassword", "integration-only-password"));
        Properties manifest = new Properties();
        manifest.setProperty("game.jar", gameJar.toString());
        manifest.setProperty("game.sha256", sha256(gameJar));
        manifest.setProperty("agent.sha256", sha256(agent));
        manifest.setProperty("java", javaExecutable);
        manifest.setProperty("java.testRunnerVersion", System.getProperty("java.version"));
        manifest.setProperty("game.home", home.toString());
        manifest.setProperty("native.path", nativePath);
        manifest.setProperty("server.port", Integer.toString(port));
        manifest.setProperty("server.udpPort", Integer.toString(udpPort));
        if (movingLoad) manifest.setProperty(MovingObjectLoad.JFR_PROPERTY,
            settings.getProperty(MovingObjectLoad.JFR_PROPERTY, "false"));
        if (movingLoad && youngHeap != null) manifest.setProperty(MovingObjectLoad.YOUNG_HEAP_PROPERTY, youngHeap);
        if (!streamingSeed.isEmpty()) {
            var workload = StreamingWorkload.from(settings);
            manifest.setProperty("streaming.fixture", workload.id());
            manifest.setProperty("streaming.positions", Integer.toString(workload.positions().size()));
            manifest.setProperty("streaming.cells", Integer.toString(workload.cells().size()));
            manifest.setProperty("streaming.heap", settings.getProperty("pz.it.heap", "2g"));
            manifest.setProperty("streaming.seed", streamingSeed);
            if (workload.vehicleStress()) {
                manifest.setProperty("streaming.vehicleSeed", workload.vehicleSeed().toString());
                manifest.setProperty("streaming.carSpawnRate", "5");
            }
            childSettings.forEach((key, value) -> {
                if (IntegrationLaunchSettings.isOptimizationSwitch(key) || key.equals(MovingObjectLoad.PROPERTY)
                        || key.startsWith(MovingObjectLoad.PROPERTY + ".")) manifest.setProperty(key, value);
            });
        }
        try (var out = Files.newOutputStream(directory.resolve("runtime.properties"))) {
            manifest.store(out, "Isolated original-JAR server integration runtime");
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(home.toFile())
            .redirectErrorStream(true);
        IntegrationLaunchSettings.configureEnvironment(builder.environment(), System.getenv(), settings);
        process = builder.start();
        IntegrationLog.info("Starting dedicated server PID " + process.pid() + " from " + gameJar);
        logPump = Thread.ofPlatform().daemon().name("server-integration-console").start(() -> {
            try (var input = process.inputReader(StandardCharsets.UTF_8);
                 var output = Files.newBufferedWriter(consoleLog(), StandardCharsets.UTF_8)) {
                for (String line; (line = input.readLine()) != null;) {
                    output.write(line);
                    output.newLine();
                    output.flush();
                    if (Boolean.parseBoolean(settings.getProperty("pz.it.serverLogs", "true"))) {
                        System.out.println("[PZ] " + line);
                    }
                    transformationFailed |= line.contains("[Byte Buddy] ERROR");
                    ready |= line.contains("*** SERVER STARTED ****");
                }
            } catch (IOException failure) {
                logFailure = failure;
                IntegrationLog.fail("Server log capture failed: " + failure);
            }
        });
        Files.writeString(directory.resolve("pid.txt"), Long.toString(process.pid()));
        await("dedicated server readiness", Duration.ofSeconds(
            Long.parseLong(settings.getProperty("pz.it.startupSeconds", "240"))), () -> ready);
        assertHealthy();
        IntegrationLog.pass("Dedicated server ready; running registered assertions");
    }

    private String jarClasspath(Path probe) {
        return gameJar + File.pathSeparator + probe;
    }

    private Path createProbeJar() throws Exception {
        Path testClasses = Path.of(ServerTestContext.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path probeClasses = testClasses.resolve("cloud/apocalipse/pz/agent/integration/probe");
        var packages = new java.util.ArrayList<>(java.util.List.of(probeClasses));
        if (Boolean.parseBoolean(settings.getProperty("pz.it.experimentalWorldSimulation"))) {
            packages.add(testClasses.resolve("cloud/apocalipse/pz/agent/experimental"));
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", PROBE);
        Path jar = directory.resolve("hook-probe.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (Path directory : packages) {
                try (var files = Files.walk(directory)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".class")
                            && !path.getFileName().toString().endsWith("Assertion.class")).toList()) {
                        out.putNextEntry(new JarEntry(testClasses.relativize(file).toString().replace('\\', '/')));
                        Files.copy(file, out);
                        out.closeEntry();
                    }
                }
            }
        }
        return jar;
    }

    /** No destructive cleanup: cache/world/logs stay under target for diagnosis. */
    public Path directory() { return directory; }
    public Path consoleLog() { return directory.resolve("console.log"); }
    public List<String> telemetrySnapshots() { return List.copyOf(telemetry); }
    public String console() throws IOException { return Files.exists(consoleLog()) ? Files.readString(consoleLog()) : ""; }

    public void assertAlive() {
        assertTrue(process != null && process.isAlive(), "Server exited; inspect " + consoleLog());
    }

    public void assertHealthy() throws IOException {
        assertAlive();
        assertFalse(Files.exists(directory.resolve("evidence/probe-error.txt")), "Probe failed: " + directory);
        assertNull(logFailure, "Server log capture failed: " + consoleLog());
        assertFalse(transformationFailed, "Transformation failure: " + consoleLog());
    }

    public record RuntimeHook(String module, String id, String target, String method, String helper, String invocation) {
        public String name() { return "Startup hook " + module + "/" + id + " -> " + target + "." + method; }
    }

    public Properties hookInventory() throws IOException {
        Properties inventory = new Properties();
        try (var input = Files.newInputStream(directory.resolve("evidence/inventory.properties"))) {
            inventory.load(input);
        }
        return inventory;
    }

    public List<RuntimeHook> hooks() throws IOException {
        Properties inventory = hookInventory();
        List<RuntimeHook> hooks = new ArrayList<>();
        for (int i = 0; i < Integer.parseInt(inventory.getProperty("count")); i++) {
            String p = "hook." + i + ".";
            hooks.add(new RuntimeHook(inventory.getProperty(p + "module"), inventory.getProperty(p + "id"),
                inventory.getProperty(p + "target"), inventory.getProperty(p + "method"),
                inventory.getProperty(p + "helper"), inventory.getProperty(p + "invocation")));
        }
        return List.copyOf(hooks);
    }

    /** Detects new transformed target classes that were omitted from the declared checks. */
    public void assertInventoryCoverage() throws Exception {
        var inventory = hookInventory();
        String log = console();
        for (String key : inventory.stringPropertyNames()) {
            if (key.startsWith("module.")) {
                String name = key.substring("module.".length());
                assertTrue(log.contains("Registered feature module: " + name), "Module was not registered: " + name);
            }
        }
        var targets = hooks().stream().filter(h -> "ENABLED".equals(inventory.getProperty("module." + h.module())))
            .map(RuntimeHook::target).collect(java.util.stream.Collectors.toSet());
        assertFalse(targets.isEmpty(), "No active hook checks declared");
        try (var files = Files.list(directory.resolve("evidence"))) {
            for (Path path : files.filter(p -> p.getFileName().toString().startsWith("zombie.")).toList()) {
                String target = path.getFileName().toString().replaceFirst("\\.properties$", "");
                assertTrue(targets.contains(target), "Transformed target has no active declared hook check: " + target);
            }
        }
    }

    /** Startup inventory only: absent evidence is unverified, while observed hooks remain strict. */
    public void assertStartupHook(String className, String method, String helper, String invocation) throws Exception {
        assertHealthy();
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.isRegularFile(directory.resolve("evidence").resolve(className + ".properties")),
            "Not observed at startup; hook installation unverified (target may load later): " + className);
        assertHook(className, method, helper, invocation);
    }

    public void assertHook(String className, String method, String helper, String invocation) throws Exception {
        Path file = directory.resolve("evidence").resolve(className + ".properties");
        assertTrue(Files.isRegularFile(file), "No transformed-class evidence for " + className + "; " + consoleLog());
        Properties evidence = new Properties();
        try (var in = Files.newInputStream(file)) { evidence.load(in); }
        assertEquals(gameJar.toUri(), java.net.URI.create(evidence.getProperty("source")),
            "Hook target must originate in the selected original JAR");
        String owner = helper.startsWith("cloud.") ? helper.replace('.', '/')
            : "cloud/apocalipse/pz/agent/features/" + helper;
        String key = method + "|" + owner + "|" + invocation;
        assertTrue(evidence.containsKey(key), "Missing injected invocation " + key + " in " + file);
    }

    /** Bounded polling for scenario-specific observable state, always checking child liveness. */
    public void await(String description, Duration timeout, BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            assertAlive();
            if (ready.getAsBoolean()) return;
            Thread.sleep(250);
        } while (System.nanoTime() - deadline < 0);
        fail("Timed out waiting for " + description + "; inspect " + consoleLog());
    }

    static int availablePort() throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            try (ServerSocket tcp = new ServerSocket(0)) {
                try (DatagramSocket udp = new DatagramSocket(tcp.getLocalPort())) {
                    return tcp.getLocalPort();
                } catch (java.net.BindException unavailable) { /* Try another TCP/UDP pair. */ }
            }
        }
        throw new IOException("Unable to allocate test server port");
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65_536];
            for (int read; (read = in.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Override public void close() throws Exception {
        close(true);
    }

    private void close(boolean verifyExit) throws Exception {
        try {
            if (process == null) return;
            if (process.isAlive()) {
                var consoleInput = process.outputWriter(StandardCharsets.UTF_8);
                IntegrationLog.info("Requesting graceful server shutdown");
                consoleInput.write("quit\n");
                consoleInput.flush();
                if (!process.waitFor(Long.parseLong(settings.getProperty("pz.it.shutdownSeconds", "90")), TimeUnit.SECONDS)) {
                    fail("Server failed to shut down gracefully; inspect " + consoleLog());
                }
            }
            if (logPump != null) {
                logPump.join(5_000);
                assertFalse(logPump.isAlive(), "Server log capture did not finish");
            }
            assertNull(logFailure, "Server log capture failed");
            if (verifyExit) {
                assertEquals(0, process.exitValue(), "Server exit status; inspect " + consoleLog());
                IntegrationLog.pass("Dedicated server exited cleanly");
            } else {
                IntegrationLog.info("Server process stopped after startup failure (exit " + process.exitValue() + ")");
            }
        } finally {
            try {
                if (process != null && process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Child process did not terminate: " + process.pid());
                    }
                }
            } finally {
                if (receiver != null) receiver.stop(0);
            }
        }
    }
}
