package cloud.apocalipse.pz.agent.features.hotfixes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import cloud.apocalipse.pz.agent.AgentModules;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlCaseResolverTest {
    @TempDir Path directory;

    @Test
    void resolvesUniqueCaseMismatchWithoutChangingExactPaths() throws Exception {
        Path mixed = directory.resolve("Mods").resolve("TsarsLib").resolve("media").resolve("AnimSets");
        Files.createDirectories(mixed);
        Path xml = mixed.resolve("Ata2_Aim_Default_Vanilla.xml");
        Files.writeString(xml, "<anim/>");

        Path wrong = directory.resolve("mods").resolve("tsarslib").resolve("media")
            .resolve("animsets").resolve("ata2_aim_default_vanilla.xml");
        String resolved = XmlCaseResolver.resolve(wrong.toString());
        if (System.getProperty("os.name").toLowerCase().startsWith("linux")) {
            assertEquals(xml.toAbsolutePath().normalize().toString(), resolved);
        } else {
            assertEquals(wrong.toString(), resolved);
        }
        assertEquals(xml.toAbsolutePath().normalize().toString(),
            XmlCaseResolver.resolve(xml.toString()));
    }

    @Test
    void preservesMissingOrAmbiguousPaths() throws Exception {
        Path first = directory.resolve("Mods").resolve("Alpha");
        Path second = directory.resolve("mods").resolve("Beta");
        Files.createDirectories(first);
        Files.createDirectories(second);

        String missing = directory.resolve("mods").resolve("Missing").resolve("File.xml").toString();
        assertEquals(missing, XmlCaseResolver.resolve(missing));

        Path duplicateParent = directory.resolve("Case");
        Files.createDirectories(duplicateParent);
        Files.createDirectories(directory.resolve("case"));
        String ambiguous = directory.resolve("CASE").resolve("File.xml").toString();
        assertEquals(ambiguous, XmlCaseResolver.resolve(ambiguous));
    }

    @Test
    void preserveMissingFileWithExistingFolderComponents() {
        Path wrong = directory.resolve("mods").resolve("Missing.xml");
        assertEquals(wrong.toString(), XmlCaseResolver.resolve(wrong.toString()));
    }

    @Test
    void registeredModuleTransformsTheOriginalPzXmlUtilMethod() throws Exception {
        assertTrue(AgentModules.all().stream().anyMatch(XmlCaseModule.class::isInstance));

        String previous = System.getProperty(AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY);
        try {
            System.clearProperty(AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY);
            XmlCaseModule module = new XmlCaseModule();
            assertNull(module.disabledReason());
            assertEquals(1, module.hookChecks().size());

            String game = System.getProperty("pz.game.jar");
            if (game == null) {
                game = Path.of(Class.forName(XmlCaseModule.TARGET, false, getClass().getClassLoader())
                    .getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            }
            try (JarFile jar = new JarFile(game);
                 ClassFileLocator jarLocator = new ClassFileLocator.ForJarFile(jar)) {
                var transformer = module.configure(new AgentBuilder.Default().disableClassFormatChanges()).makeRaw();
                byte[] original = jarLocator.locate(XmlCaseModule.TARGET).resolve();
                byte[] transformed = transformer.transform(getClass().getClassLoader(),
                    XmlCaseModule.TARGET.replace('.', '/'), null, null, original);
                assertNotNull(transformed, "registered matcher must transform " + XmlCaseModule.TARGET);

                final boolean[] helperCall = {false};
                new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                     String signature, String[] exceptions) {
                        if (!XmlCaseModule.METHOD.equals(method)) {
                            return null;
                        }
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String name,
                                                        String descriptor, boolean isInterface) {
                                if (owner.equals("cloud/apocalipse/pz/agent/features/hotfixes/XmlCaseResolver")
                                    && name.equals("resolve")) {
                                    helperCall[0] = true;
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                assertTrue(helperCall[0], "transformed parseXmlInternal must call XmlCaseResolver.resolve");
            }

            System.setProperty(AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY, "false");
            XmlCaseModule disabled = new XmlCaseModule();
            assertNotNull(disabled.disabledReason());
        } finally {
            if (previous == null) System.clearProperty(AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY);
            else System.setProperty(AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY, previous);
        }
    }
}
