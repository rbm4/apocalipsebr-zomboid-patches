package cloud.apocalipse.pz.agent.features.npc;

import java.net.*;
import java.lang.reflect.InvocationTargetException;

/** Test-only loader: fixture/native reference bytes are unsigned in this loader to avoid mixing
 * certificates with structural doubles. Original-JAR transformation/linkage is tested separately. */
public final class NpcFactoryFixtureMain {
    public static void main(String[] args)throws Exception {
        String[] paths=System.getProperty("java.class.path").split(java.io.File.pathSeparator);
        var urls=new URL[paths.length];for(int i=0;i<paths.length;i++)urls[i]=new java.io.File(paths[i]).toURI().toURL();
        try(var loader=new URLClassLoader(urls,NpcFactoryFixtureMain.class.getClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(!name.startsWith("zombie.")&&!name.startsWith("cloud.apocalipse.pz.agent.features.npc."))return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)){
                    var type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;
                }
            }
            @Override protected Class<?> findClass(String name)throws ClassNotFoundException{
                var resource=findResource(name.replace('.','/')+".class");if(resource==null)throw new ClassNotFoundException(name);
                try(var input=resource.openStream()){byte[] bytes=input.readAllBytes();return defineClass(name,bytes,0,bytes.length);}
                catch(java.io.IOException failure){throw new ClassNotFoundException(name,failure);}
            }
        }){
            String main=args[0].equals("hit-default")?NpcOrdinaryHitSmokeMain.class.getName():NpcHookSmokeMain.class.getName();
            try{loader.loadClass(main).getMethod("main",String[].class).invoke(null,(Object)args);}
            catch(InvocationTargetException failure){throw new AssertionError("Factory fixture failed",failure.getCause());}
        }
    }
}
