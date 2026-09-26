package com.ruoyi.session.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.session.runtime.RuntimeProblem;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Fixed service identity. Application Secrets are checked only by system and never persisted in session_db. */
@Component
public class BusinessSystemClient
{
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    public BusinessSystemClient(ObjectMapper json) { this.json = json; }

    public Snapshot authenticate(String authorization, String scope, Long configId)
    {
        if (authorization == null || !authorization.startsWith("Bearer lna_")) throw new RuntimeProblem(HttpStatus.UNAUTHORIZED, "APPLICATION_SECRET_INVALID", "Application Secret is invalid.");
        String secret = authorization.substring(7);
        return snapshot(post("authenticate", new Authenticate(secret, scope, configId)));
    }

    public Snapshot check(long accountId, long applicationId, long configId)
    { return snapshot(post("check", new Reference(accountId, applicationId, configId, 0, ""))); }

    public Snapshot reserve(long accountId, long applicationId, long configId, long sessionId, String operationId)
    { return snapshot(post("references/reserve", new Reference(accountId, applicationId, configId, sessionId, operationId))); }

    public void confirm(long accountId, long applicationId, long configId, long sessionId, String operationId)
    { post("references/confirm", new Reference(accountId, applicationId, configId, sessionId, operationId)); }

    public void release(long accountId, long applicationId, long configId, long sessionId, String operationId)
    { post("references/release", new Reference(accountId, applicationId, configId, sessionId, operationId)); }

    private JsonNode post(String path, Object body)
    {
        String base = System.getenv("LN_SESSION_TO_SYSTEM_URL");
        String bearer = System.getenv("LN_SESSION_TO_SYSTEM_INTERNAL_BEARER");
        if (base == null || base.isBlank() || bearer == null || bearer.isBlank())
            throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "BUSINESS_AUTH_NOT_CONFIGURED", "Business Session authorization is unavailable.");
        try
        {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/internal/v1/business-sessions/" + path))
                .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2)
            {
                HttpStatus status = HttpStatus.resolve(response.statusCode());
                throw new RuntimeProblem(status == null ? HttpStatus.BAD_GATEWAY : status, "BUSINESS_AUTH_REJECTED", "Business Session authorization was rejected.");
            }
            return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        }
        catch (InterruptedException error)
        {
            Thread.currentThread().interrupt();
            throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "BUSINESS_AUTH_UNAVAILABLE", "Business Session authorization was interrupted.");
        }
        catch (IOException | IllegalArgumentException error)
        { throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "BUSINESS_AUTH_UNAVAILABLE", "Business Session authorization is unavailable."); }
    }

    private Snapshot snapshot(JsonNode node)
    {
        if (node == null || !node.hasNonNull("accountId") || !node.hasNonNull("applicationId") || !node.hasNonNull("configId"))
            throw new RuntimeProblem(HttpStatus.BAD_GATEWAY, "BUSINESS_AUTH_INVALID", "Business Session authorization response is invalid.");
        if (number(node, "accountId") <= 0 || number(node, "applicationId") <= 0 || number(node, "configId") <= 0
            || !java.util.Set.of("CHAT", "SPEAK_ONLY").contains(node.path("mode").asText()))
            throw new RuntimeProblem(HttpStatus.BAD_GATEWAY, "BUSINESS_AUTH_INVALID", "Business Session authorization response is invalid.");
        return new Snapshot(number(node, "accountId"), number(node, "applicationId"), number(node, "configId"),
            number(node, "applicationEpoch"), number(node, "keyId"), number(node, "keyEpoch"), node.path("mode").asText(),
            node.hasNonNull("asrRelayVersionId"), contextEnabled(node.path("contextPolicy")),
            number(node, "voiceVersionId"), node.path("providerVoiceRef").asText(),
            number(node, "officialServiceId"), number(node, "officialServiceRevision"));
    }
    private boolean contextEnabled(JsonNode policy)
    {
        try
        {
            JsonNode value = policy.isTextual() ? json.readTree(policy.asText()) : policy;
            return value != null && value.path("enabled").asBoolean(false);
        }
        catch (IOException error)
        { throw new RuntimeProblem(HttpStatus.BAD_GATEWAY, "BUSINESS_AUTH_INVALID", "Invalid Context policy."); }
    }

    private static long number(JsonNode node, String key)
    {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return 0;
        try { return Long.parseLong(value.asText()); }
        catch (NumberFormatException error) { throw new RuntimeProblem(HttpStatus.BAD_GATEWAY, "BUSINESS_AUTH_INVALID", "Business Session authorization response is invalid."); }
    }

    public record Snapshot(long accountId, long applicationId, long configId, long applicationEpoch, long keyId,
        long keyEpoch, String mode, boolean asr, boolean contextEnabled, long voiceVersionId,
        String providerVoiceRef, long officialServiceId, long officialServiceRevision)
    {
        public List<String> allowedScopes()
        {
            List<String> scopes = new ArrayList<>(List.of("session:read", "avatar:read", "speak:write"));
            if ("CHAT".equals(mode))
            {
                scopes.add("chat:write");
                if (asr) scopes.add("asr:write");
                if (contextEnabled) scopes.add("context:capture");
            }
            return scopes;
        }
    }
    private record Authenticate(String secret, String scope, Long configId) { }
    private record Reference(long accountId, long applicationId, long configId, long sessionId, String operationId) { }
}
