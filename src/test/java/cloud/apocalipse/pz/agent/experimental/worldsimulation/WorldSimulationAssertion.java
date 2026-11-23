package cloud.apocalipse.pz.agent.experimental.worldsimulation;

import cloud.apocalipse.pz.agent.integration.ServerAssertion;
import cloud.apocalipse.pz.agent.integration.ServerTestContext;
import cloud.apocalipse.pz.agent.integration.assertions.StreamingLifecycleAssertion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class WorldSimulationAssertion implements ServerAssertion {
    @Override public String name() { return "Experimental in-place vehicle part worker"; }

    @Override public void verify(ServerTestContext server) throws Exception {
        assumeTrue(WorldSimulation.enabled(), "Enable with -D" + WorldSimulation.PROPERTY + "=true");
        StreamingLifecycleAssertion.drive(server);
        var directory = server.directory();
        try (var production = new JarFile(Path.of("target/apocbr-agent.jar").toFile());
             var observer = new JarFile(directory.resolve("hook-probe.jar").toFile())) {
            String experiment = "cloud/apocalipse/pz/agent/experimental/";
            assertFalse(production.stream().anyMatch(entry -> entry.getName().startsWith(experiment)));
            assertNotNull(observer.getJarEntry(experiment + "worldsimulation/features/EngineDoorSimulation.class"));
        }
        assertEquals("busy across server frame; native timestamp published; consumed once",
            Files.readString(directory.resolve("experimental-world-simulation-correctness.txt")).strip());

        List<String> report = Files.readAllLines(directory.resolve(WorldSimulation.REPORT));
        assertEquals(13, report.size(), "Three warm four-window benchmark cycles");
        for (int cycle = 1; cycle <= 3; cycle++) {
            int inline = 0;
            int worker = 0;
            for (String row : report.subList(1 + (cycle - 1) * 4, 1 + cycle * 4)) {
                String[] columns = row.split(",");
                assertEquals(cycle, Integer.parseInt(columns[0]));
                assertEquals(16, Integer.parseInt(columns[3]), row);
                assertTrue(Integer.parseInt(columns[4]) > 0, row);
                assertTrue(Integer.parseInt(columns[5]) > 0, row);
                assertEquals(Integer.parseInt(columns[5]),
                    Integer.parseInt(columns[6]) + Integer.parseInt(columns[12]), row);
                assertTrue(Long.parseLong(columns[9]) >= Long.parseLong(columns[8]), row);
                assertEquals(16, Integer.parseInt(columns[10]), row);
                assertTrue(Long.parseLong(columns[11]) >= Long.parseLong(columns[9]), row);
                switch (columns[2]) {
                    case "inline" -> inline++;
                    case "worker" -> worker++;
                    default -> fail("Unknown benchmark arm: " + row);
                }
            }
            assertEquals(2, inline);
            assertEquals(2, worker);
        }
    }
}
