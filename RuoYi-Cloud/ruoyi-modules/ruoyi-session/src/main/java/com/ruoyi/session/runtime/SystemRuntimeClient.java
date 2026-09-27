package com.ruoyi.session.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Uses the fixed session-to-system identity; browser authorization never crosses this boundary. */
@Component
public class SystemRuntimeClient
{
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    public SystemRuntimeClient(ObjectMapper json) { this.json = json; }

    public Map<String, Object> avatarPackage(RuntimePrincipal principal)
    {
        String baseUrl = System.getenv("LN_SESSION_TO_SYSTEM_URL");
        String bearer = System.getenv("LN_SESSION_TO_SYSTEM_INTERNAL_BEARER");
        if (blank(baseUrl) || blank(bearer)) throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_AUTH_NOT_CONFIGURED", "Runtime package access is unavailable.");
        try
        {
            String body = json.writeValueAsString(Map.of("accountId", principal.accountId(), "configVersionId", principal.configVersionId()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/runtime-avatar-packages"))
                .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new RuntimeProblem(HttpStatus.CONFLICT, "AVATAR_PACKAGE_UNAVAILABLE", "The configured Avatar package is unavailable.");
            Map<String, Object> result = json.readValue(response.body(), new TypeReference<Map<String, Object>>() { });
            if (!"LN_AVATAR".equals(result.get("packageType")) || !result.containsKey("baseImage") || !result.containsKey("actions"))
                throw new RuntimeProblem(HttpStatus.CONFLICT, "AVATAR_PACKAGE_UNAVAILABLE", "The configured Avatar package is unavailable.");
            return result;
        }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "AVATAR_PACKAGE_UNAVAILABLE", "Runtime package access was interrupted."); }
        catch (IOException | IllegalArgumentException error) { throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "AVATAR_PACKAGE_UNAVAILABLE", "Runtime package access is unavailable."); }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
