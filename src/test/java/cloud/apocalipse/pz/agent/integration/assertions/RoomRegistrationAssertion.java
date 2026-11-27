package cloud.apocalipse.pz.agent.integration.assertions;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.building.RoomIdValidationModule;
import cloud.apocalipse.pz.agent.features.optimization.building.ScopedRoomCommit;
import cloud.apocalipse.pz.agent.integration.ServerAssertion;
import cloud.apocalipse.pz.agent.integration.ServerTestContext;
import cloud.apocalipse.pz.agent.integration.probe.RoomRegistrationProbe;
import java.nio.file.Files;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class RoomRegistrationAssertion implements ServerAssertion {
    @Override public String name() { return "Native room registration replaces the main room and preserves its sibling"; }

    @Override public void verify(ServerTestContext server) throws Exception {
        assumeTrue(Boolean.getBoolean(RoomRegistrationProbe.PROPERTY), "Enable with -D" + RoomRegistrationProbe.PROPERTY);
        var directory = server.directory();
        var complete = directory.resolve(RoomRegistrationProbe.COMPLETE);
        var error = directory.resolve(RoomRegistrationProbe.ERROR);
        Files.createFile(directory.resolve(RoomRegistrationProbe.REQUEST));
        server.await("original room create, growth and cleanup", Duration.ofSeconds(60),
            () -> Files.isRegularFile(complete) || Files.isRegularFile(error));
        if (Files.isRegularFile(error)) fail(Files.readString(error));

        var rows = Files.readAllLines(directory.resolve(RoomRegistrationProbe.CSV));
        boolean enabled = AgentConfiguration.scopedRoomCommitEnabled();
        assertEquals(RoomRegistrationProbe.CYCLES * 4 + 2, rows.size());
        for (int i = 1; i < rows.size(); i++) {
            String[] values = rows.get(i).split(",");
            boolean growth = "grow".equals(values[1]);
            boolean move = "move-owner".equals(values[11]);
            boolean scoped = enabled && growth;
            assertEquals(scoped, Boolean.parseBoolean(values[12]), "Lua entry point used expected path");
            assertTrue(Long.parseLong(values[2]) > 0, "Editor call was measured");
            assertEquals(scoped ? 0 : 3, Integer.parseInt(values[4]), "World-wide checks");
            assertEquals(scoped ? 0 : 1, Integer.parseInt(values[6]), "World-wide rebuild");
            assertEquals(move ? 2 : growth ? 1 : 0, Integer.parseInt(values[8]), "Expected native removals");
            assertEquals(scoped ? (move ? 4 : 3) : 0, Integer.parseInt(values[13]), "Cell-local checks");
            assertEquals(scoped ? (move ? 4 : 2) : (move ? 2 : growth ? 1 : 0),
                Integer.parseInt(values[14]), "Cell-local rebuilds outside a global pass");
            assertEquals(scoped && move ? 2 : growth ? 1 : 0, Integer.parseInt(values[15]), "ID-owner cells touched");
        }
        if (enabled) {
            for (String helper : java.util.List.of("enter", "trackOwner", "check", "rebuild")) {
                server.assertHook(RoomRegistrationProbe.EDITOR, "applyChanges", ScopedRoomCommit.class.getName(), helper);
            }
        }
        if (AgentConfiguration.roomIdValidationOptimizationEnabled()) {
            server.assertHook(RoomRegistrationProbe.EDITOR, "checkBuildingAndRoomIDs",
                RoomIdValidationModule.class.getName(), "roomIndex");
        }
    }
}
