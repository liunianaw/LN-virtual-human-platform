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
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Application Secrets are checked only by system; session_db stores the returned immutable snapshot. */
@Component
public class BusinessSystemClient
{
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    public BusinessSystemClient(ObjectMapper json) { this.json = json; }

    public Snapshot authenticate(String authorization, String scope)
    {
        if (authorization == null || !authorization.startsWith("Bearer lna_"))
            throw problem(HttpStatus.UNAUTHORIZED, "APPLICATION_SECRET_INVALID");
        return snapshot(post("authenticate", new Authenticate(authorization.substring(7), scope)));
    }
    public Snapshot check(long accountId, long applicationId)
    { return snapshot(post("check", new Reference(accountId, applicationId, 0, 0, ""))); }
    public Snapshot check(long accountId, long applicationId, long sessionId)
    { return snapshot(post("check", new Reference(accountId, applicationId, 0, sessionId, ""))); }
    public long recordingLimit(long accountId, long applicationId)
    {
        long limit = number(post("recording-limit", new Reference(accountId, applicationId, 0, 0, "")), "maxRecordingBytes");
        if (limit <= 0) throw problem(HttpStatus.BAD_GATEWAY, "BUSINESS_AUTH_INVALID");
        return limit;
    }
    public Snapshot reserve(long accountId, long applicationId, long applicationRevision,
        long sessionId, String operationId)
    { return snapshot(post("references/reserve", new Reference(accountId, applicationId,
        applicationRevision, sessionId, operationId))); }
    public void confirm(long accountId, long applicationId, long applicationRevision,
        long sessionId, String operationId)
    { post("references/confirm", new Reference(accountId, applicationId, applicationRevision, sessionId, operationId)); }
    public void release(long accountId, long applicationId, long applicationRevision,
        long sessionId, String operationId)
    { post("references/release", new Reference(accountId, applicationId, applicationRevision, sessionId, operationId)); }

    public ResolvedTool resolveTool(long accountId, long applicationId, long sessionId, long skillId,
        Long toolSecretId, String toolUrl)
    {
        JsonNode node = post("/internal/v1/skills/resolve",
            new ToolBinding(accountId, applicationId, sessionId, skillId, toolSecretId, toolUrl));
        String address = node.path("pinnedAddress").asText();
        if (number(node, "skillId") != skillId || address.isBlank())
            throw problem(HttpStatus.BAD_GATEWAY, "TOOL_BINDING_INVALID");
        return new ResolvedTool(address, node.hasNonNull("accessToken") ? node.path("accessToken").asText() : null);
    }

    public long reserveTts(long accountId, long applicationId, String businessId, long units)
    {
        JsonNode response = post("/internal/v1/tts-quota/reserve", new TtsQuota(accountId, applicationId, businessId, units, null));
        long id = number(response, "reservationId");
        if (id < 0) throw problem(HttpStatus.BAD_GATEWAY, "TTS_QUOTA_INVALID");
        return id;
    }
    public void finishTts(long accountId, String businessId, String outcome)
    { post("/internal/v1/tts-quota/finish", new TtsQuota(accountId, 0, businessId, 0, outcome)); }

    public long lookupTts(long accountId, String businessId)
    {
        JsonNode response=post("/internal/v1/tts-quota/lookup",new TtsQuota(accountId,0,businessId,0,null));
        long id=number(response,"reservationId");
        // Absence is not proof that an in-flight reserve transaction never happened.
        if(id<=0) throw problem(HttpStatus.SERVICE_UNAVAILABLE,"TTS_RESERVATION_UNCONFIRMED");
        return id;
    }

    private JsonNode post(String path, Object body)
    {
        String base = System.getenv("LN_SESSION_TO_SYSTEM_URL");
        String bearer = System.getenv("LN_SESSION_TO_SYSTEM_INTERNAL_BEARER");
        if (blank(base) || blank(bearer)) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "BUSINESS_AUTH_NOT_CONFIGURED");
        try
        {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + (path.startsWith("/")
                ? path : "/internal/v1/business-sessions/" + path))).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2)
            {
                HttpStatus status = HttpStatus.resolve(response.statusCode());
                throw problem(status == null ? HttpStatus.BAD_GATEWAY : status, "BUSINESS_AUTH_REJECTED");
            }
            return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        }
        catch (InterruptedException error)
        {
            Thread.currentThread().interrupt();
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "BUSINESS_AUTH_UNAVAILABLE");
        }
        catch (IOException | IllegalArgumentException error)
        { throw problem(HttpStatus.SERVICE_UNAVAILABLE, "BUSINESS_AUTH_UNAVAILABLE"); }
    }

    private Snapshot snapshot(JsonNode node)
    {
        long account = number(node, "accountId"), application = number(node, "applicationId");
        long revision = number(node, "applicationRevision");
        if (account <= 0 || application <= 0 || revision <= 0)
            throw problem(HttpStatus.BAD_GATEWAY, "BUSINESS_AUTH_INVALID");
        List<String> scopes;
        try
        {
            scopes = json.convertValue(node.path("allowedScopes"),
                json.getTypeFactory().constructCollectionType(List.class, String.class));
        }
        catch (IllegalArgumentException error) { throw problem(HttpStatus.BAD_GATEWAY, "BUSINESS_AUTH_INVALID"); }
        List<String> fixed = List.of("session:read", "avatar:read", "speak:write",
            "asr:write", "context:capture", "guidance:receive");
        if (!scopes.equals(fixed)) throw problem(HttpStatus.BAD_GATEWAY, "BUSINESS_AUTH_INVALID");
        return new Snapshot(account, application, revision, number(node, "applicationEpoch"),
            number(node, "keyId"), number(node, "keyEpoch"), number(node, "avatarVersionId"),
            number(node, "voiceVersionId"), node.path("providerVoiceRef").asText(),
            number(node, "officialServiceId"), number(node, "officialServiceRevision"),
            node.path("systemPrompt").asText(""), node.path("developerConfig").deepCopy(),
            node.path("internalSkills").deepCopy(), scopes);
    }
    private static long number(JsonNode node, String key)
    {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return 0;
        try { return Long.parseLong(value.asText()); }
        catch (NumberFormatException error) { throw problem(HttpStatus.BAD_GATEWAY, "BUSINESS_AUTH_INVALID"); }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static RuntimeProblem problem(HttpStatus status, String code)
    { return new RuntimeProblem(status, code, code); }

    public record Snapshot(long accountId, long applicationId, long applicationRevision,
        long applicationEpoch, long keyId, long keyEpoch, long avatarVersionId, long voiceVersionId,
        String providerVoiceRef, long officialServiceId, long officialServiceRevision,
        String systemPrompt, JsonNode developerConfig, JsonNode internalSkills, List<String> allowedScopes) { }
    private record Authenticate(String secret, String scope) { }
    private record Reference(long accountId, long applicationId, long applicationRevision,
        long sessionId, String operationId) { }
    private record TtsQuota(long accountId, long applicationId, String businessId, long units, String outcome) { }
    private record ToolBinding(long accountId, long applicationId, long sessionId, long skillId,
        Long toolSecretId, String toolUrl) { }
    public record ResolvedTool(String pinnedAddress, String accessToken) { }
}
