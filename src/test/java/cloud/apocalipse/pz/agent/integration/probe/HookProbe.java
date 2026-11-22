package cloud.apocalipse.pz.agent.integration.probe;

import cloud.apocalipse.pz.agent.AgentModules;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Properties;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** Test-only observer with an optional, isolated streaming scenario. */
public final class HookProbe {
    public static void premain(String evidenceDirectory, Instrumentation instrumentation) {
        Path directory = Path.of(evidenceDirectory);
        writeInventory(directory);
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String className, Class<?> redefining,
                    ProtectionDomain domain, byte[] bytes) {
                if (!isGameClass(className)) return null;
                try {
                    Properties evidence = new Properties();
                    evidence.setProperty("source", domain.getCodeSource().getLocation().toExternalForm());
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override public void visitMethodInsn(int opcode, String owner, String name,
                                        String desc, boolean itf) {
                                    if (owner.startsWith("cloud/apocalipse/pz/agent/")) {
                                        evidence.setProperty(method + "|" + owner + "|" + name, descriptor + " -> " + desc);
                                    }
                                }
                            };
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    if (evidence.size() > 1) {
                        synchronized (HookProbe.class) {
                            try (var out = Files.newOutputStream(directory.resolve(className.replace('/', '.') + ".properties"))) {
                                evidence.store(out, "Post-production-transform bytes observed in the server JVM");
                            }
                        }
                    }
                } catch (Throwable failure) {
                    try {
                        Files.writeString(directory.resolve("probe-error.txt"), failure.toString());
                    } catch (Exception ignored) { }
                    System.err.println("[Server-IT] Hook probe failed: " + failure);
                }
                return null;
            }
        });
        if (Boolean.getBoolean("pz.it.streaming")) StreamingProbe.install(directory, instrumentation);
    }

    public static boolean isGameClass(String internalName) {
        return internalName != null
            && (internalName.startsWith("zombie/") || internalName.startsWith("se/krka/kahlua/"));
    }

    private static void writeInventory(Path directory) {
        Properties inventory = new Properties();
        int index = 0;
        var moduleNames = new java.util.HashSet<String>();
        for (var module : AgentModules.all()) {
            if (!moduleNames.add(module.name())) throw new IllegalStateException("Duplicate module: " + module.name());
            var checks = module.hookChecks();
            if (checks.isEmpty()) throw new IllegalStateException("Module has no hook checks: " + module.name());
            String reason = module.disabledReason();
            inventory.setProperty("module." + module.name(), reason == null ? "ENABLED" : reason);
            var ids = new java.util.HashSet<String>();
            for (var check : checks) {
                if (!ids.add(check.id())) throw new IllegalStateException("Duplicate hook id: " + module.name() + "/" + check.id());
                String prefix = "hook." + index++ + ".";
                inventory.setProperty(prefix + "module", module.name());
                inventory.setProperty(prefix + "id", check.id());
                inventory.setProperty(prefix + "target", check.targetClass());
                inventory.setProperty(prefix + "method", check.method());
                inventory.setProperty(prefix + "helper", check.helperClass());
                inventory.setProperty(prefix + "invocation", check.helperMethod());
            }
        }
        inventory.setProperty("count", Integer.toString(index));
        try (var out = Files.newOutputStream(directory.resolve("inventory.properties"))) {
            inventory.store(out, "Actual initialized production module registry and declared hook checks");
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to write hook inventory", failure);
        }
    }

    public static void main(String[] arguments) throws Exception {
        // No Steam calls or game object construction: make this lazy target eligible for transformation.
        for (var module : AgentModules.all()) {
            if (module.disabledReason() == null) {
                for (var check : module.hookChecks()) {
                    if (check.loadBeforeStartup()) {
                        Class.forName(check.targetClass(), false, ClassLoader.getSystemClassLoader());
                    }
                }
            }
        }
        Class.forName("zombie.network.GameServer").getMethod("main", String[].class)
            .invoke(null, (Object) arguments);
    }
}
