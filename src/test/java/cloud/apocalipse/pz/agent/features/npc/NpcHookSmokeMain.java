package cloud.apocalipse.pz.agent.features.npc;

import cloud.apocalipse.pz.agent.config.AgentConfiguration.NpcSettings;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;

/** Isolates static runtime state; no real game startup, database or network access. */
public final class NpcHookSmokeMain {
    public static void main(String[] args) throws Exception {
        System.setProperty(cloud.apocalipse.pz.agent.config.AgentConfiguration.NPC_ACTORS_ENABLED_PROPERTY,Boolean.toString(args[0].equals("factory")));
        System.setProperty(cloud.apocalipse.pz.agent.config.AgentConfiguration.NPC_ARG_APOCBR_NPC_PLAYERHITTRACE,"false");
        zombie.network.GameServer.server = true;
        zombie.network.GameServer.udpEngine = new Object();
        zombie.iso.IsoWorld.instance.currentCell = new zombie.iso.IsoWorld.Cell();
        NpcRuntime.initialize(new NpcSettings(true, true));
        var definitions = new HashMap<String, byte[]>();
        definitions.put(NpcHookFixture.class.getName(), NpcModule.transform("zombie.network.GameServer",
            new ByteBuddy().redefine(NpcHookFixture.class)).make().getBytes());
        definitions.put(NpcQuitFixture.class.getName(), NpcModule.transform("zombie.network.ServerMap",
            new ByteBuddy().redefine(NpcQuitFixture.class)).make().getBytes());
        var loader = new ByteArrayClassLoader.ChildFirst(NpcHookSmokeMain.class.getClassLoader(), definitions);
        {
            var fixture = loader.loadClass(NpcHookFixture.class.getName());
            try {
                fixture.getMethod("main", String[].class).invoke(null, (Object)args);
                if (args[0].equals("exception")) throw new AssertionError("Native exception was swallowed");
            } catch (InvocationTargetException failure) {
                if (!args[0].equals("exception") || !(failure.getCause() instanceof IllegalArgumentException)) throw failure;
            }
            if (fixture.getField("iterations").getInt(null) != 5 || fixture.getField("simulations").getInt(null) != 3
                    || zombie.network.LoginQueue.calls != 3) throw new AssertionError("Native simulation/packet iterations changed");
            if (fixture.getField("quits").getInt(null) != (args[0].equals("quit")||args[0].equals("factory") ? 1 : 0)) throw new AssertionError("Native quit continuation changed");
        }
        if (!NpcRuntime.status().stopped() || NpcRuntime.status().generation() != (args[0].equals("factory")?2:1)) throw new AssertionError("Production runtime lifecycle failed");
        NpcRuntime.shutdown();
        if (Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive() && t.getName().equals("ApocBR-NPC-Simulation")))
            throw new AssertionError("NPC worker leaked");
        System.out.println("NPC transformed lifecycle passed: " + args[0]);
    }
}
