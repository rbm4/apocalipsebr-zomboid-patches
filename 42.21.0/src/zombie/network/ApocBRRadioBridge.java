package zombie.network;

/** Narrow server-Lua API. Exposes no delivery configuration or arbitrary JSON ingestion. */
public final class ApocBRRadioBridge {
    private ApocBRRadioBridge() {}

    public static boolean recordMusicState(String stationId, String contentId, String kind,
            String phase, double sequence, String caption) {
        ApocBRBackendBridge bridge = ApocBRBackendBridgeHooks.current();
        if (bridge == null) return false;
        try {
            if (!Double.isFinite(sequence) || sequence < 0 || sequence > 9_007_199_254_740_991d
                    || sequence != Math.rint(sequence)) return false;
            return bridge.publishRadioContent(ApocBRBackendBridgeHooks.context(300_000L), stationId,
                    contentId, kind, phase, (long)sequence, caption);
        } catch (RuntimeException invalidObservation) {
            return false;
        }
    }

    public static boolean recordListenerRequest(String stationId, String characterName,
            String songIds, String message, double songCount, double queueDepth) {
        ApocBRBackendBridge bridge = ApocBRBackendBridgeHooks.current();
        if (bridge == null) return false;
        try {
            if (!Double.isFinite(songCount) || songCount != Math.rint(songCount)
                    || songCount < 1 || songCount > 25 || !Double.isFinite(queueDepth)
                    || queueDepth != Math.rint(queueDepth) || queueDepth < 0 || queueDepth > 100) return false;
            return bridge.publishRadioRequest(ApocBRBackendBridgeHooks.context(300_000L), stationId,
                    characterName, songIds, message, (int)songCount, (int)queueDepth);
        } catch (RuntimeException invalidObservation) {
            return false;
        }
    }

    public static boolean recordAiredLine(String stationId, String kind, String line) {
        ApocBRBackendBridge bridge = ApocBRBackendBridgeHooks.current();
        if (bridge == null) return false;
        try {
            return bridge.publishRadioLine(ApocBRBackendBridgeHooks.context(300_000L), stationId, kind, line);
        } catch (RuntimeException invalidObservation) {
            return false;
        }
    }
}
