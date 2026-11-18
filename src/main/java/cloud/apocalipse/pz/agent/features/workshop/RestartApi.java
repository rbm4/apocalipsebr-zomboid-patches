package cloud.apocalipse.pz.agent.features.workshop;

import feign.Body;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import feign.Response;
import java.io.IOException;

public interface RestartApi {
    @RequestLine("POST /api/server/mod-update/restart")
    @Headers({
        "X-API-Key: {apiKey}",
        "Content-Type: application/json",
        "Accept: application/json"
    })
    @Body("{payload}")
    Response post(@Param("apiKey") String apiKey, @Param("payload") String payload) throws IOException;
}
