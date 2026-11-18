package cloud.apocalipse.pz.agent.features.telemetry;

import java.io.IOException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetrySnapshotReporterTest {
    @Test
    void transportFailureIncludesSanitizedBoundedCauseDetails() {
        IOException failure = new IOException(
            "request failed",
            new ConnectException("Connection refused\napiKey=secret https://localhost:800/private")
        );

        String detail = TelemetrySnapshotReporter.formatDeliveryFailure(failure);

        assertTrue(detail.startsWith("TRANSPORT_ERROR (ConnectException:"));
        assertTrue(detail.contains("Connection refused"));
        assertFalse(detail.contains("secret"));
        assertFalse(detail.contains("localhost"));
        assertFalse(detail.contains("\n"));
        assertTrue(detail.length() <= 210);
    }

    @Test
    void deliveryCodesRemainConcise() {
        assertEquals(
            "INVALID_ACKNOWLEDGMENT",
            TelemetrySnapshotReporter.formatDeliveryFailure(
                new TelemetryHttpClient.DeliveryException("INVALID_ACKNOWLEDGMENT")
            )
        );
    }
}
