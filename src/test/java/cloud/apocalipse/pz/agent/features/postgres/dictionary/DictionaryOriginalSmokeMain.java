package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import cloud.apocalipse.pz.agent.config.AgentConfiguration.DictionarySettings;
import cloud.apocalipse.pz.agent.config.AgentConfiguration.PostgresSettings;
import cloud.apocalipse.pz.agent.features.postgres.*;
import java.lang.reflect.*;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.network.GameServer;

/** Original game classes only, fake JDBC, disposable save directory; never runs GameServer.main. */
public final class DictionaryOriginalSmokeMain {
    static final String DATA="zombie/world/DictionaryData", WORLD="zombie/world/WorldDictionary";
    static final List<String> EVENTS=new ArrayList<>();
    static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    static void await(java.util.function.BooleanSupplier condition)throws Exception {
        long expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<expires)Thread.sleep(10);
        check(condition.getAsBoolean(),"listener continuation timed out");
    }
    static Field field(Class<?> type,String name)throws Exception {
        for(Class<?> current=type;current!=null;current=current.getSuperclass()) {
            try{var result=current.getDeclaredField(name);result.setAccessible(true);return result;}catch(NoSuchFieldException ignored){}
        }
        throw new NoSuchFieldException(name);
    }
    static void set(Object owner,String name,Object value)throws Exception {field(owner.getClass(),name).set(owner,value);}
    static Object invoke(Object owner,String name,Class<?>[] signature,Object... args)throws Exception {
        var method=owner instanceof Class<?> type?type.getDeclaredMethod(name,signature):owner.getClass().getDeclaredMethod(name,signature);
        method.setAccessible(true);return method.invoke(owner instanceof Class<?>?null:owner,args);
    }
    @SuppressWarnings("unchecked")
    static Object nativeData()throws Exception {
        var constructor=Class.forName("zombie.world.DictionaryData").getDeclaredConstructor();constructor.setAccessible(true);
        Object data=constructor.newInstance();
        invoke(data,"reset",new Class<?>[0]);
        for(String type:List.of("StringDictionary","ScriptsDictionary"))invoke(Class.forName("zombie.world."+type),"reset",new Class<?>[0]);
        Object item=NativeDictionaryAccess.create("ItemInfo");
        set(item,"registryId",(short)0);set(item,"moduleName","Base");set(item,"name","Axe");set(item,"fullType","Base.Axe");
        set(item,"modId","example");set(item,"isModded",true);set(item,"isLoaded",true);
        set(item,"modOverrides",new ArrayList<>(List.of("pz-vanilla","old-mod")));
        ((Map<Short,Object>)NativeDictionaryAccess.get(data,"itemIdToInfoMap")).put((short)0,item);
        ((Map<String,Object>)NativeDictionaryAccess.get(data,"itemTypeToInfoMap")).put("Base.Axe",item);set(data,"nextInfoId",(short)1);
        Class<?> strings=Class.forName("zombie.world.StringDictionary");Object register=strings.getField("Generic").get(null);
        Object text=NativeDictionaryAccess.create("DictionaryStringInfo");set(text,"registryId",(short)0);set(text,"string","Base.Test");set(text,"isLoaded",true);
        ((Map<Short,Object>)NativeDictionaryAccess.get(register,"idToStringMap")).put((short)0,text);
        ((Map<String,Object>)NativeDictionaryAccess.get(register,"stringToIdMap")).put("Base.Test",text);set(register,"nextId",(short)1);
        Class<?> scripts=Class.forName("zombie.world.ScriptsDictionary");Object scriptRegister=scripts.getField("spriteConfigs").get(null);
        Object script=NativeDictionaryAccess.create("DictionaryScriptInfo");set(script,"registryId",(short)0);set(script,"name","Base.Config");
        set(script,"isLoaded",true);set(script,"version",Long.MIN_VALUE+9);
        ((Map<Short,Object>)NativeDictionaryAccess.get(scriptRegister,"idMap")).put((short)0,script);
        ((Map<String,Object>)NativeDictionaryAccess.get(scriptRegister,"namedMap")).put("Base.Config",script);set(scriptRegister,"nextId",(short)1);
        ByteBuffer bytes=ByteBuffer.allocate(1024*1024);invoke(data,"saveToByteBuffer",new Class<?>[]{ByteBuffer.class},bytes);
        bytes.flip();byte[] cache=new byte[bytes.remaining()];bytes.get(cache);set(data,"serverDataCache",cache);return data;
    }

    public static void event(String event) { EVENTS.add(event); }
    static byte[] fixtureData() {
        var writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,DATA,null,"java/lang/Object",null);
        for(String name:List.of("serverDataCache","itemIdToInfoMap","entityIdToInfoMap"))
            writer.visitField(Opcodes.ACC_PUBLIC,name,name.equals("serverDataCache")?"[B":"Ljava/util/Map;",null,null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"fail","Z",null,null).visitEnd();
        var constructor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD,0);constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);
        constructor.visitInsn(Opcodes.RETURN);constructor.visitMaxs(0,0);constructor.visitEnd();
        var save=writer.visitMethod(Opcodes.ACC_PUBLIC,"save","()V",null,null);save.visitCode();save.visitLdcInsn("save");
        save.visitMethodInsn(Opcodes.INVOKESTATIC,DictionaryOriginalSmokeMain.class.getName().replace('.','/'),"event","(Ljava/lang/String;)V",false);
        save.visitFieldInsn(Opcodes.GETSTATIC,DATA,"fail","Z");var okay=new Label();save.visitJumpInsn(Opcodes.IFEQ,okay);
        save.visitTypeInsn(Opcodes.NEW,"java/lang/IllegalStateException");save.visitInsn(Opcodes.DUP);
        save.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/IllegalStateException","<init>","()V",false);save.visitInsn(Opcodes.ATHROW);
        save.visitLabel(okay);save.visitInsn(Opcodes.RETURN);save.visitMaxs(0,0);save.visitEnd();writer.visitEnd();return writer.toByteArray();
    }
    static byte[] fixtureWorld() {
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,WORLD,null,"java/lang/Object",null);
        writer.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"data","L"+DATA+";",null,null).visitEnd();
        var method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"init","()V",null,null);method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC,WORLD,"data","L"+DATA+";");method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,DATA,"save","()V",false);
        method.visitLdcInsn("backup-delete");method.visitMethodInsn(Opcodes.INVOKESTATIC,DictionaryOriginalSmokeMain.class.getName().replace('.','/'),"event","(Ljava/lang/String;)V",false);
        method.visitInsn(Opcodes.RETURN);method.visitMaxs(0,0);method.visitEnd();writer.visitEnd();return writer.toByteArray();
    }
    static final class FixtureLoader extends ClassLoader {
        final Map<String,byte[]> definitions;
        FixtureLoader(Map<String,byte[]> definitions){super(DictionaryOriginalSmokeMain.class.getClassLoader());this.definitions=definitions;}
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
            if(!definitions.containsKey(name))return super.loadClass(name,resolve);
            synchronized(getClassLoadingLock(name)) {
                var found=findLoadedClass(name);if(found==null){byte[] bytes=definitions.get(name);found=defineClass(name,bytes,0,bytes.length);}
                if(resolve)resolveClass(found);return found;
            }
        }
    }

    public static void main(String[] args)throws Exception {
        check(GameServer.class.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("projectzomboid.jar"),"game classes must originate from original archive");
        Path run=Path.of(args[0]);Files.createDirectories(run);
        invoke(Core.getInstance(),"setNoSave",new Class<?>[]{boolean.class},false);
        invoke(Core.getInstance(),"setGameMode",new Class<?>[]{String.class},"Multiplayer");
        field(Core.class,"gameSaveWorld").set(null,"dictionary-fixture");GameServer.server=true;
        ZomboidFileSystem.instance.setCacheDir(run.toString());Files.createDirectories(Path.of(ZomboidFileSystem.instance.getCurrentSaveDir()));
        Object data=nativeData();byte[] before=((byte[])NativeDictionaryAccess.get(data,"serverDataCache")).clone();
        var snapshot=DictionarySnapshot.capture(data);
        check(snapshot.entries().size()==3,"native info/string/script capture");
        check(snapshot.entries().stream().anyMatch(entry->entry.kind().equals("script")&&entry.version()==Long.MIN_VALUE+9),"signed script version preserved");
        check(Arrays.equals(before,(byte[])NativeDictionaryAccess.get(data,"serverDataCache")),"capture must not change native bytes");
        try{DictionarySnapshot.decode(Arrays.copyOf(before,before.length-1),data);throw new AssertionError("truncation accepted");}catch(java.io.IOException expected){}
        var jdbc=new DictionaryJdbcFixture.Fake();var reader=new DictionaryReadFixture.Fake(10001,10001);
        var activeReader=new AtomicReference<>(reader);var events=new LinkedBlockingQueue<String>();
        var listenerClosed=new AtomicInteger();var listening=new CountDownLatch(1);var subscriptions=new AtomicInteger();
        DataSource source=(DataSource)DictionaryJdbcFixture.proxy(DataSource.class,(p,m,a)->{
            if(!m.getName().equals("getConnection"))return DictionaryJdbcFixture.zero(m.getReturnType());
            Connection publication=jdbc.open();Connection foreign=activeReader.get().open();boolean[] notification={false},closed={false};
            return DictionaryJdbcFixture.proxy(Connection.class,(c,n,b)->{
                switch(n.getName()) {
                    case "setAutoCommit":notification[0]=(boolean)b[0];return null;
                    case "close":if(!closed[0]){closed[0]=true;if(notification[0])listenerClosed.incrementAndGet();publication.close();foreign.close();}return null;
                    case "createStatement":return DictionaryJdbcFixture.proxy(Statement.class,(s,k,v)->{if(k.getName().equals("execute")){check(v[0].equals("LISTEN "+DictionaryDatabase.CHANNEL),"listen contract");subscriptions.incrementAndGet();listening.countDown();return true;}return DictionaryJdbcFixture.zero(k.getReturnType());});
                    case "unwrap":return DictionaryJdbcFixture.proxy((Class<?>)b[0],(s,k,v)->{
                        if(k.getName().equals("getNotifications")) {
                            String event=events.poll(50,TimeUnit.MILLISECONDS);
                            if("disconnect".equals(event))throw new SQLException("fixture transport private message","08006");
                            if(event==null)return null;
                            Class<?> kind=Class.forName(((Class<?>)b[0]).getName().replace("PGConnection","PGNotification"));
                            Object notifications=Array.newInstance(kind,1);
                            Array.set(notifications,0,DictionaryJdbcFixture.proxy(kind,(x,y,z)->DictionaryJdbcFixture.zero(y.getReturnType())));
                            return notifications;
                        }
                        return DictionaryJdbcFixture.zero(k.getReturnType());
                    });
                    case "prepareStatement": {
                        String sql=(String)b[0];
                        if(sql.startsWith("SELECT DISTINCT")) {
                            check(listening.getCount()==0,"LISTEN before discovery");
                            return DictionaryJdbcFixture.proxy(PreparedStatement.class,(s,k,v)-> {
                                if(k.getName().equals("executeQuery")){var current=activeReader.get();return DictionaryReadFixture.rows(Collections.singletonList(new Object[]{current.origin.namespaceId(),current.origin.viewId(),12L,2,"foreign"}));}
                                return DictionaryJdbcFixture.zero(k.getReturnType());
                            });
                        }
                        if(sql.startsWith("SELECT v.identity_revision")||sql.startsWith("SELECT s.family")||sql.startsWith("SELECT count(*)")
                            ||sql.startsWith("SELECT e.family")||sql.startsWith("SELECT family,register_name,native_id,mod_id,position"))
                            return foreign.prepareStatement(sql);
                        return publication.prepareStatement(sql);
                    }
                    case "commit":case "rollback":if(!notification[0]){n.invoke(publication,b);return null;}return null;
                    default:return DictionaryJdbcFixture.zero(n.getReturnType());
                }
            });
        });
        var constructor=PostgresConnections.class.getDeclaredConstructor(DataSource.class,AutoCloseable.class);constructor.setAccessible(true);
        var pool=(PostgresConnections)constructor.newInstance(source,(AutoCloseable)()->event("pool-close"));
        var install=PostgresRuntime.class.getDeclaredMethod("installForTest",PostgresSettings.class,PostgresConnections.class);install.setAccessible(true);
        install.invoke(null,new PostgresSettings(true,false,"jdbc:postgresql://fixture/unused",1,5000,3000),pool);
        DictionaryRuntime.initialize(new DictionarySettings(true,1,"test",null,30));
        byte[] fixture=fixtureWorld();var definitions=new HashMap<String,byte[]>();definitions.put(DATA.replace('/','.'),fixtureData());definitions.put(WORLD.replace('/','.'),fixture);
        if(!Boolean.getBoolean("dictionary.smoke.premain"))try(var locator=new ClassFileLocator.Simple(definitions)) {
            var type=TypePool.Default.of(new ClassFileLocator.Compound(locator,ClassFileLocator.ForClassLoader.ofSystemLoader())).describe(WORLD.replace('/','.')).resolve();
            definitions.put(WORLD.replace('/','.'),DictionaryModule.transform(new ByteBuddy().redefine(type,locator)).make().getBytes());
        }
        var loader=new FixtureLoader(definitions);Class<?> world=loader.loadClass(WORLD.replace('/','.'));Object holder=loader.loadClass(DATA.replace('/','.')).getConstructor().newInstance();
        for(String name:List.of("serverDataCache","itemIdToInfoMap","entityIdToInfoMap"))set(holder,name,NativeDictionaryAccess.get(data,name));
        world.getField("data").set(null,holder);
        try {
            GameServer.server=false;world.getMethod("init").invoke(null);
            check(jdbc.commits==0&&EVENTS.equals(List.of("save","backup-delete")),"non-server execution stays vanilla");
            GameServer.server=true;invoke(Core.getInstance(),"setNoSave",new Class<?>[]{boolean.class},true);EVENTS.clear();
            world.getMethod("init").invoke(null);check(jdbc.commits==0,"no-save worlds do not publish");
            invoke(Core.getInstance(),"setNoSave",new Class<?>[]{boolean.class},false);EVENTS.clear();
            world.getMethod("init").invoke(null);
            check(EVENTS.equals(List.of("save","backup-delete")),"vanilla save and backup continuation preserved");
            check(DictionaryRuntime.localView()!=null,"transformed init reaches production publication");
            check(DictionaryCache.ready(DictionaryRuntime.localEncoding()).snapshot().equals(snapshot),"local cache installed");
            await(()->!DictionaryRuntime.foreignViews().isEmpty());
            check(DictionaryRuntime.foreignViews().size()==1,"listener prepares foreign head");
            check(DictionaryCache.ready(reader.origin).snapshot().entries().size()==10001,"foreign dictionary loaded into memory");
            check(reader.entryQueries==10&&reader.overrideQueries==10,"foreign counted pagination");
            var changed=new DictionaryReadFixture.Fake(1,0);activeReader.set(changed);events.offer("notify");
            await(()->DictionaryRuntime.foreignViews().getFirst().view().equals(changed.origin.viewId()));
            check(DictionaryCache.ready(changed.origin).snapshot().entries().size()==1,"notification prepares new foreign head");
            check(DictionaryCache.ready(reader.origin).snapshot().entries().size()==10001,"historical exact views retained");
            var missed=new DictionaryReadFixture.Fake(2,0);activeReader.set(missed);events.offer("disconnect");
            await(()->subscriptions.get()>=2&&DictionaryRuntime.foreignViews().getFirst().view().equals(missed.origin.viewId()));
            check(DictionaryCache.ready(missed.origin).snapshot().entries().size()==2,"reconnect reconciles missed publication");
            int commits=jdbc.commits;EVENTS.clear();holder.getClass().getField("fail").setBoolean(null,true);
            try{world.getMethod("init").invoke(null);throw new AssertionError("failed save accepted");}catch(InvocationTargetException expected){}
            check(EVENTS.equals(List.of("save"))&&jdbc.commits==commits,"save failure skips publication and continuation");
            holder.getClass().getField("fail").setBoolean(null,false);jdbc.failPrefix="SELECT current_revision";jdbc.injected=new SQLException("secret","42501");EVENTS.clear();
            try{world.getMethod("init").invoke(null);throw new AssertionError("publication failure accepted");}catch(InvocationTargetException expected){}
            check(EVENTS.equals(List.of("save")),"publication failure prevents backup deletion");
        } finally {PostgresRuntime.shutdown();}
        check(listenerClosed.get()==subscriptions.get(),"all dedicated listener sessions closed");check(EVENTS.contains("pool-close"),"pool shutdown completed");
        check(DictionaryRuntime.localView()==null&&DictionaryRuntime.foreignViews().isEmpty(),"runtime state cleared");
        System.out.println("Original dictionary capture, transformed setup, pagination and shutdown passed.");
    }
}
