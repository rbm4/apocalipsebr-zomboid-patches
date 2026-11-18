package cloud.apocalipse.pz.agent.features.telemetry;

import java.util.ArrayList;
import java.util.HashMap;
import net.bytebuddy.asm.Advice;

/** Periodic snapshot pulse; field values are read on the server thread. */
public final class TelemetrySnapshotAdvice {
    private TelemetrySnapshotAdvice() {
    }

    @Advice.OnMethodExit
    public static void exit(
            @Advice.FieldValue("Players") ArrayList<?> players,
            @Advice.FieldValue("IDToPlayerMap") HashMap<?, ?> playersById) {
        TelemetrySnapshotReporter.onServerLoop(Math.max(players.size(), playersById.size()));
    }
}
