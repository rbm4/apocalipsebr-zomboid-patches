// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import zombie.core.logger.ExceptionLogger;

public final class IntegrityReport {
    private static final String HI_THERE = "If you are messing with this code, you give us permission to perma ban you from the workshop <3";
    private static final Set<IntegrityReport.Problem> JAR_PROBLEMS = EnumSet.of(
        IntegrityReport.Problem.JAR_UNREADABLE,
        IntegrityReport.Problem.JAR_MANIFEST_MISSING,
        IntegrityReport.Problem.JAR_ENTRY_MODIFIED,
        IntegrityReport.Problem.JAR_ENTRY_MISSING,
        IntegrityReport.Problem.JAR_ENTRY_UNSIGNED,
        IntegrityReport.Problem.JAR_ENTRY_FOREIGN_SIGNER
    );
    private static final String PINNED_CERTIFICATE_SHA256 = "8f:5a:fa:34:10:48:c0:b8:b6:ac:28:2a:81:b4:e0:79:5c:98:27:aa:d4:df:4f:7e:07:2f:21:66:54:9b:27:a8";
    private static final String ATTACH_LISTENER_THREAD = "Attach Listener";
    private static final HexFormat FINGERPRINT_FORMAT = HexFormat.ofDelimiter(":");
    private static volatile IntegrityReport current;
    private final Path jar;
    private final Map<IntegrityReport.Problem, List<String>> problems = new EnumMap<>(IntegrityReport.Problem.class);

    private IntegrityReport(Path jar) {
        this.jar = jar;
        if (jar != null) {
            this.verifyJar();
            this.scanClasspath();
        }

        this.findAgents();
        this.findAttached();
    }

    private IntegrityReport(RuntimeException cause) {
        this.jar = null;
        this.report(IntegrityReport.Problem.ERROR, cause.toString());
        this.findAgents();
        this.findAttached();
    }

    public static IntegrityReport current() {
        return current;
    }

    public static void collectInBackground(Runnable onCollected) {
        Thread thread = new Thread(() -> {
            IntegrityReport report;
            try {
                report = new IntegrityReport(locateJar());
            } catch (RuntimeException var3) {
                ExceptionLogger.logException(var3, "IntegrityReport failed to collect");
                report = new IntegrityReport(var3);
            }

            current = report;
            onCollected.run();
        }, "IntegrityReport");
        thread.setDaemon(true);
        thread.start();
    }

    public static String currentSummary() {
        IntegrityReport report = current;
        return report == null ? "integrity=pending" : report.summary();
    }

    public boolean isClean() {
        return this.jar != null && this.problems.isEmpty();
    }

    public IntegrityReport.JarStatus getStatus() {
        if (this.problems.containsKey(IntegrityReport.Problem.ERROR)) {
            return IntegrityReport.JarStatus.UNVERIFIED;
        } else if (this.jar == null) {
            return IntegrityReport.JarStatus.NOT_A_JAR;
        } else {
            return Collections.disjoint(this.problems.keySet(), JAR_PROBLEMS) ? IntegrityReport.JarStatus.ORIGINAL : IntegrityReport.JarStatus.TAMPERED;
        }
    }

    public List<String> getProblem(IntegrityReport.Problem problem) {
        return Collections.unmodifiableList(this.problems.getOrDefault(problem, Collections.emptyList()));
    }

    public String summary() {
        IntegrityReport.JarStatus status = this.getStatus();
        if (Core.IS_DEV && status == IntegrityReport.JarStatus.NOT_A_JAR) {
            return "";
        } else {
            List<String> findings = new ArrayList<>();
            if (status != IntegrityReport.JarStatus.ORIGINAL) {
                findings.add("jar=" + status.name().toLowerCase());
            }

            int classpathCount = this.getProblem(IntegrityReport.Problem.CLASSPATH_EXTRA).size();
            if (classpathCount > 0) {
                findings.add("classpath=" + classpathCount);
            }

            int agentCount = this.getProblem(IntegrityReport.Problem.AGENT).size();
            if (agentCount > 0) {
                findings.add("agents=" + agentCount);
            }

            if (this.problems.containsKey(IntegrityReport.Problem.ATTACHED)) {
                findings.add("attached");
            }

            return String.join(",", findings);
        }
    }

    private void report(IntegrityReport.Problem kind, String detail) {
        this.problems.computeIfAbsent(kind, var0 -> new ArrayList<>()).add(detail);
    }

    private static Path locateJar() {
        try {
            URL location = IntegrityReport.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Path.of(location.toURI());
            if (Files.isRegularFile(path) && path.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return path;
            }
        } catch (IllegalArgumentException | SecurityException | URISyntaxException var2) {
        }

        return null;
    }

    private void verifyJar() {
        byte[] buffer = new byte[65536];

        try {
            try (JarFile jarFile = new JarFile(this.jar.toFile(), true)) {
                Manifest manifest = jarFile.getManifest();
                if (manifest != null) {
                    Enumeration<JarEntry> entries = jarFile.entries();

                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (!entry.isDirectory() && !isSignatureFile(name)) {
                            try (InputStream in = jarFile.getInputStream(entry)) {
                                while (in.read(buffer) != -1) {
                                }
                            } catch (SecurityException var16) {
                                this.report(IntegrityReport.Problem.JAR_ENTRY_MODIFIED, name);
                                continue;
                            }

                            CodeSigner[] signers = entry.getCodeSigners();
                            if (signers == null) {
                                this.report(IntegrityReport.Problem.JAR_ENTRY_UNSIGNED, name);
                            } else {
                                for (CodeSigner signer : signers) {
                                    String fingerprint = fingerprint(signer.getSignerCertPath().getCertificates().getFirst());
                                    if (!"8f:5a:fa:34:10:48:c0:b8:b6:ac:28:2a:81:b4:e0:79:5c:98:27:aa:d4:df:4f:7e:07:2f:21:66:54:9b:27:a8".equals(fingerprint)) {
                                        this.report(IntegrityReport.Problem.JAR_ENTRY_FOREIGN_SIGNER, name + " by " + fingerprint);
                                    }
                                }
                            }
                        }
                    }

                    for (String name : manifest.getEntries().keySet()) {
                        if (jarFile.getEntry(name) == null) {
                            this.report(IntegrityReport.Problem.JAR_ENTRY_MISSING, name);
                        }
                    }

                    return;
                }

                this.report(IntegrityReport.Problem.JAR_MANIFEST_MISSING, this.jar.toString());
            }
        } catch (SecurityException | UncheckedIOException | IOException var18) {
            this.report(IntegrityReport.Problem.JAR_UNREADABLE, var18.toString());
        }
    }

    private static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/")) {
            return false;
        } else {
            String upper = name.toUpperCase(Locale.ROOT);
            return upper.equals("META-INF/MANIFEST.MF") || upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC");
        }
    }

    private static String fingerprint(Certificate certificate) {
        try {
            return FINGERPRINT_FORMAT.formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (CertificateEncodingException | NoSuchAlgorithmException var2) {
            return "unavailable: " + var2;
        }
    }

    private void scanClasspath() {
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                Path path = Path.of(entry).toAbsolutePath().normalize();
                if (!path.equals(this.jar.toAbsolutePath().normalize())) {
                    if (Files.isDirectory(path)) {
                        try (Stream<Path> files = Files.walk(path)) {
                            files.filter(file -> file.toString().endsWith(".class"))
                                .map(file -> path.relativize(file).toString().replace(File.separatorChar, '/'))
                                .forEach(f -> this.report(IntegrityReport.Problem.CLASSPATH_EXTRA, f));
                        } catch (UncheckedIOException | SecurityException | IOException var11) {
                        }
                    } else {
                        this.report(IntegrityReport.Problem.CLASSPATH_EXTRA, entry);
                    }
                }
            }
        }
    }

    private void findAttached() {
        if (isAttached()) {
            this.report(IntegrityReport.Problem.ATTACHED, "Attach Listener");
        }
    }

    private static boolean isAttached() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();

        for (ThreadInfo thread : threads.getThreadInfo(threads.getAllThreadIds(), 0)) {
            if (thread != null && "Attach Listener".equals(thread.getThreadName())) {
                return true;
            }
        }

        return false;
    }

    private void findAgents() {
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.startsWith("-javaagent:") || argument.startsWith("-agentpath:") || argument.startsWith("-agentlib:")) {
                this.report(IntegrityReport.Problem.AGENT, argument);
            }
        }
    }

    public static enum JarStatus {
        NOT_A_JAR,
        ORIGINAL,
        UNVERIFIED,
        TAMPERED;
    }

    public static enum Problem {
        JAR_UNREADABLE,
        JAR_MANIFEST_MISSING,
        JAR_ENTRY_MODIFIED,
        JAR_ENTRY_MISSING,
        JAR_ENTRY_UNSIGNED,
        JAR_ENTRY_FOREIGN_SIGNER,
        CLASSPATH_EXTRA,
        AGENT,
        ATTACHED,
        ERROR;
    }
}
