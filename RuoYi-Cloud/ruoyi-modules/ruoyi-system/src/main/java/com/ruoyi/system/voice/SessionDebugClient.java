package com.ruoyi.system.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.exception.ServiceException;

/** Only sends already-derived platform facts across the private system-to-session channel. */
@Component
public class SessionDebugClient
{
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper objectMapper;

    public SessionDebugClient(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public CreatedSession create(long accountId, long applicationId, long configVersionId, String requestId)
    {
        JsonNode body = request("/internal/v1/console-debug-sessions", new CreateBody(accountId, applicationId, configVersionId, requestId));
        return new CreatedSession(body.path("sessionId").asLong(), body.path("applicationId").asLong(), body.path("configVersionId").asLong());
    }

    public IssuedToken mint(MintBody body)
    {
        JsonNode response = request("/internal/v1/console-debug-sessions/tokens", body);
        return new IssuedToken(response.path("token").asText(), response.path("expiresAt").asText());
    }

    public void close(long accountId, long sessionId)
    { request("/internal/v1/console-debug-sessions/" + sessionId, new CloseBody(accountId), "DELETE"); }

    private JsonNode request(String path, Object body)
    { return request(path, body, "POST"); }

    private JsonNode request(String path, Object body, String method)
    {
        String bearer = System.getenv("LN_SYSTEM_TO_SESSION_INTERNAL_BEARER");
        String baseUrl = System.getenv("LN_SYSTEM_TO_SESSION_URL");
        if (bearer == null || bearer.isBlank() || baseUrl == null || baseUrl.isBlank())
            throw new ServiceException("会话内部身份未配置", HttpStatus.SERVICE_UNAVAILABLE.value());
        try
        {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new ServiceException("会话服务拒绝 DEBUG 授权", HttpStatus.BAD_GATEWAY.value());
            return response.body() == null || response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("会话服务调用被中断", HttpStatus.SERVICE_UNAVAILABLE.value());
        }
        catch (IOException | IllegalArgumentException e) { throw new ServiceException("会话服务不可用", HttpStatus.SERVICE_UNAVAILABLE.value()); }
    }

    public record CreateBody(long accountId, long applicationId, long configVersionId, String requestId) { }
    public record MintBody(long accountId, long applicationId, long sessionId, long configVersionId, String issuerConsoleRef,
            long expiresAtEpochMs, long voiceVersionId, String providerKind, String providerVoiceRef, String relayVersionRef,
            Long officialServiceId, Long officialServiceRevision, String mode) { }
    public record CloseBody(long accountId) { }
    public record CreatedSession(long sessionId, long applicationId, long configVersionId) { }
    public record IssuedToken(String token, String expiresAt) { }
}
