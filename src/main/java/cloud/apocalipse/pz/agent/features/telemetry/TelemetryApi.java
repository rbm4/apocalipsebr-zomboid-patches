package cloud.apocalipse.pz.agent.features.telemetry;

import feign.Body;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import feign.Response;
import java.io.IOException;

public interface TelemetryApi {
    @RequestLine("POST /api/v1/telemetry")
    @Headers({
        "X-API-Key: {apiKey}",
        "Content-Type: application/json; charset=utf-8",
        "Accept: application/json"
    })
    @Body("{payload}")
    Response post(@Param("apiKey") String apiKey, @Param("payload") String payload) throws IOException;
}
