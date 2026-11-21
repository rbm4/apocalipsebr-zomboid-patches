package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import cloud.apocalipse.pz.agent.AgentModules;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryHookTest {
    @TempDir Path directory;
    @Test void registeredModuleTransformsOriginalOwnersWithoutChangingGameStructure() throws Exception {
        String game=System.getProperty("pz.game.jar");
        if(game==null)game=Path.of(Class.forName("zombie.world.DictionaryData",false,getClass().getClassLoader())
            .getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertTrue(AgentModules.all().stream().anyMatch(DictionaryModule.class::isInstance));
        var saved=new HashMap<String,String>();
        for(String key:List.of(AgentConfiguration.POSTGRES_ENABLED_PROPERTY,AgentConfiguration.DICTIONARY_ENABLED_PROPERTY,
                AgentConfiguration.APOCBR_POSTGRES_SERVER_ID_PROPERTY))saved.put(key,System.getProperty(key));
        try {
            System.setProperty(AgentConfiguration.POSTGRES_ENABLED_PROPERTY,"true");
            System.setProperty(AgentConfiguration.DICTIONARY_ENABLED_PROPERTY,"true");
            System.setProperty(AgentConfiguration.APOCBR_POSTGRES_SERVER_ID_PROPERTY,"1");
            var module=new DictionaryModule();module.initialize();assertNull(module.disabledReason());assertEquals(3,module.hookChecks().size());
            var transformer=module.configure(new AgentBuilder.Default().disableClassFormatChanges()).makeRaw();
            try(var locator=new ClassFileLocator.ForJarFile(new java.util.jar.JarFile(game))) {
                for(String name:List.of(DictionaryModule.TARGET,"zombie.network.GameServer","zombie.network.ServerMap")) {
                    byte[] original=locator.locate(name).resolve();
                    byte[] transformed=transformer.transform(getClass().getClassLoader(),name.replace('.','/'),null,null,original);
                    assertNotNull(transformed,"registered matcher must transform "+name);
                    var before=TypePool.Default.of(locator).describe(name).resolve();
                    var after=TypePool.Default.of(new ClassFileLocator.Compound(
                        ClassFileLocator.Simple.of(name,transformed),locator)).describe(name).resolve();
                    assertEquals(before.getDeclaredFields().size(),after.getDeclaredFields().size());
                    assertEquals(before.getDeclaredMethods().size(),after.getDeclaredMethods().size());
                    if(name.equals(DictionaryModule.TARGET)) {
                        var calls=new ArrayList<String>();
                        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
                            @Override public MethodVisitor visitMethod(int access,String method,String desc,String sig,String[] errors) {
                                if(!method.equals("init"))return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override public void visitMethodInsn(int opcode,String owner,String called,String descriptor,boolean face) {
                                        if(List.of("save","afterRebuild","deleteBackupCurrentDataSet").contains(called))calls.add(called);
                                    }
                                };
                            }
                        },0);
                        assertEquals(List.of("save","afterRebuild","deleteBackupCurrentDataSet"),calls);
                    }
                }
                System.setProperty(AgentConfiguration.DICTIONARY_ENABLED_PROPERTY,"false");module=new DictionaryModule();module.initialize();
                assertNotNull(module.disabledReason());
                var disabled=module.configure(new AgentBuilder.Default().disableClassFormatChanges()).makeRaw();
                assertNull(disabled.transform(getClass().getClassLoader(),DictionaryModule.TARGET.replace('.','/'),null,null,
                    locator.locate(DictionaryModule.TARGET).resolve()));
            }
        } finally {
            saved.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
            DictionaryRuntime.shutdown();
        }
    }

    @Test void originalArchiveCaptureAndExecutableProductionSetup() throws Exception {
        Path isolated=directory.resolve("classes");String pkg="cloud/apocalipse/pz/agent/features/postgres/dictionary";
        Path source=Path.of("target/test-classes",pkg),destination=isolated.resolve(pkg);Files.createDirectories(destination);
        try(var files=Files.list(source)) {
            for(Path file:files.filter(path->path.getFileName().toString().startsWith("DictionaryOriginalSmokeMain")
                ||path.getFileName().toString().startsWith("DictionaryJdbcFixture")
                ||path.getFileName().toString().startsWith("DictionaryReadFixture")).toList())
                Files.copy(file,destination.resolve(file.getFileName()));
        }
        // Exclude all loose zombie test doubles. Only the original archive supplies game classes.
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var entries=new ArrayList<String>();entries.add(isolated.toString());
        for(String entry:classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
            if(!Path.of(entry).toAbsolutePath().normalize().equals(Path.of("target/test-classes").toAbsolutePath().normalize()))entries.add(entry);
        Path output=directory.resolve("smoke.log");
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
            String.join(File.pathSeparator,entries),DictionaryOriginalSmokeMain.class.getName(),directory.resolve("save").toString())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(30,TimeUnit.SECONDS),"dictionary fixture timed out");
            assertEquals(0,child.exitValue(),()->{try{return Files.readString(output);}catch(Exception error){return error.toString();}});
        } finally {if(child.isAlive())child.destroyForcibly();}
    }
}
