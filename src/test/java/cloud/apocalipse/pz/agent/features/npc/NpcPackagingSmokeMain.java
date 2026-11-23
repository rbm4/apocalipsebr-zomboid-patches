package cloud.apocalipse.pz.agent.features.npc;

import java.nio.file.*;
import java.util.jar.JarFile;

/** Run with the shaded artifact first; never starts the game or opens external services. */
public final class NpcPackagingSmokeMain {
    public static void main(String[] args) throws Exception {
        Path artifact = Path.of(args[0]).toAbsolutePath();
        try (var jar = new JarFile(artifact.toFile())) {
            for (var entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                String name = entry.getName();
                if (name.startsWith("zombie/")) throw new AssertionError("Game class bundled: " + name);
                if (!name.startsWith("cloud/apocalipse/pz/agent/features/npc/")) continue;
                var type = Class.forName(name.substring(0,name.length()-6).replace('/','.'), false,
                    NpcPackagingSmokeMain.class.getClassLoader());
                type.getDeclaredMethods(); type.getDeclaredConstructors(); type.getDeclaredFields();
                if (!Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).equals(artifact))
                    throw new AssertionError("NPC helper did not resolve from shaded artifact: " + name);
            }
        }
        try (var engine = new NpcEngine(new cloud.apocalipse.pz.agent.config.AgentConfiguration.NpcSettings(true,false))) {
            engine.update(new Object(),0);
            var slot = engine.planner().open();
            var start = new cloud.apocalipse.pz.agent.features.npc.movement.MovementGrid.Tile(1,1);
            var route = new cloud.apocalipse.pz.agent.features.npc.planning.NpcDecisionPlanner.Route(
                new cloud.apocalipse.pz.agent.features.npc.planning.NpcDecisionPlanner.Grid(java.util.Set.of(start),java.util.List.of()),start,start,false);
            if (slot.take(slot.submit(route,0)) == null) throw new AssertionError("Packaged planner did not execute");
        }
        System.out.println("Shaded NPC helpers and planner linkage passed; no game or external service started.");
    }
}
