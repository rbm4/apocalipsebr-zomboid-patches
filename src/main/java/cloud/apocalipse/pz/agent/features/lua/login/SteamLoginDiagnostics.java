package cloud.apocalipse.pz.agent.features.lua.login;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;

/** APBR diagnostics use fixed stage names, connection GUIDs, and sanitized failure codes. */
final class SteamLoginDiagnostics {
    private SteamLoginDiagnostics() { }

    static void log(UdpConnection connection, String stage, String result) {
        DebugLog.log("[ApocBR][SteamLogin] connection=" + Long.toUnsignedString(connection.getConnectedGUID())
            + " stage=" + stage + " result=" + result);
    }

    static void phase(UdpConnection connection, String phase, String result) {
        if (phase.startsWith("steam-")) log(connection, phase, result);
    }

    static String failureType(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        return failure.getClass().getSimpleName();
    }
}
