package cloud.apocalipse.pz.agent.features.postgres.player;

import cloud.apocalipse.pz.agent.AgentModules;
import java.nio.file.*;
import java.util.*;

/** Packaged production agent plus read-only observer; no server main, native initialization or JDBC. */
public final class PlayerPackagingSmokeMain {
    public static void main(String[] arguments)throws Exception {
        Path evidence=Path.of(arguments[0]),original=Path.of(arguments[1]).toRealPath();int verified=0;
        for(var module:AgentModules.all()) {
            if(!Set.of("postgresql-players","postgresql-dictionary-decode").contains(module.name()))continue;
            if(module.disabledReason()!=null)throw new AssertionError("Expected enabled packaged module: "+module.name());
            for(var hook:module.hookChecks()) {
                Class.forName(hook.targetClass(),false,ClassLoader.getSystemClassLoader());
                var properties=new Properties();try(var input=Files.newInputStream(evidence.resolve(hook.targetClass()+".properties"))){properties.load(input);}
                if(!original.equals(Path.of(java.net.URI.create(properties.getProperty("source"))).toRealPath()))throw new AssertionError("Target did not originate in the original archive: "+hook.targetClass());
                if(!properties.containsKey(hook.method()+"|"+hook.helperClass().replace('.','/')+"|"+hook.helperMethod()))throw new AssertionError("Missing packaged hook: "+hook.id());
                verified++;
            }
        }
        if(verified==0||Files.exists(evidence.resolve("probe-error.txt")))throw new AssertionError("Packaged hook observation failed");
        System.out.println("Packaged original-JAR player/decode hook contracts verified: "+verified);
    }
}
