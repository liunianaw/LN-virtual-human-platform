package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.session.business.BusinessSessionService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** One explicit recording request against the administrator-configured default official ASR. */
@Service
public class AsrRuntimeService
{
    private final RuntimeOperationStore store;
    private final OfficialServiceResolver official;
    private final BusinessSessionService business;
    private final PinnedHttps https;
    private final RuntimeAuthorization access;
    private final ObjectMapper json;
    private final com.ruoyi.session.business.BusinessSystemClient system;

    public AsrRuntimeService(RuntimeOperationStore store, OfficialServiceResolver official,
        BusinessSessionService business, PinnedHttps https, RuntimeAuthorization access, ObjectMapper json,
        com.ruoyi.session.business.BusinessSystemClient system)
    { this.store = store; this.official = official; this.business = business; this.https = https;
      this.access = access; this.json = json; this.system = system; }

    public Result transcribe(RuntimeAuthorization.Grant grant, String requestId,
        String mimeType, byte[] audio, String language)
    {
        RuntimePrincipal principal = grant.principal();
        if (!principal.scopes().contains("asr:write")) throw problem(HttpStatus.FORBIDDEN, "SCOPE_DENIED");
        mimeType = mimeType == null ? "" : mimeType.split(";", 2)[0].trim();
        if (requestId == null || !requestId.matches("[\\x21-\\x7e]{1,64}") || audio == null || audio.length == 0
            || audio.length > 1_900_000 || !java.util.Set.of("audio/webm", "audio/mp4", "audio/ogg", "audio/wav").contains(mimeType)
            || language != null && !language.matches("[A-Za-z]{2,8}(?:-[A-Za-z]{2,8})?"))
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        if (audio.length > system.recordingLimit(principal.accountId(), principal.applicationId()))
            throw problem(HttpStatus.PAYLOAD_TOO_LARGE, "RECORDING_LIMIT_EXCEEDED");
        OfficialServiceResolver.DefaultResolved service = official.resolveDefaultAsr();
        long operationId = store.beginAsr(principal, requestId, service.serviceId(), audio);
        java.util.concurrent.atomic.AtomicBoolean submitted = new java.util.concurrent.atomic.AtomicBoolean();
        byte[] body = payload(audio, mimeType, service.model(), language);
        URI endpoint = URI.create(service.endpoint());
        try (PinnedHttps.Response response = https.request(endpoint, publicAddress(endpoint), "POST",
            Map.of("Authorization", "Bearer " + service.credential(),
                "Content-Type", "application/json"),
            body, 30000, 65536, () -> { access.verify(grant.id()); submitted.set(true); }))
        {
            if (!response.contentType().startsWith("application/json")) throw new IOException("Invalid ASR content type");
            JsonNode value = json.readTree(response.body());
            String text = value.path("choices").path(0).path("message").path("content").asText();
            if (text.isBlank() || text.length() > 4096) throw new IOException("Invalid ASR text");
            String providerRequestId = value.path("id").asText(null);
            Long durationMs = value.path("usage").path("seconds").isNumber()
                ? Math.round(value.path("usage").path("seconds").asDouble() * 1000) : null;
            access.verify(grant.id());
            store.endAsr(principal, operationId, "SUCCEEDED", null, providerRequestId, durationMs);
            if ("BUSINESS_KEY".equals(grant.source())) business.successfulActivity(principal.sessionId());
            return new Result(Long.toString(operationId), text, value.path("language").asText(language));
        }
        catch (Exception error)
        {
            store.endAsr(principal, operationId, submitted.get() ? "UNKNOWN" : "FAILED",
                "ASR_UPSTREAM_FAILED", null, null);
            throw problem(HttpStatus.BAD_GATEWAY, "ASR_UPSTREAM_FAILED");
        }
    }

    private byte[] payload(byte[] audio, String mimeType, String model, String language)
    {
        try
        {
            Map<String, Object> options = new java.util.LinkedHashMap<>();
            options.put("enable_itn", true);
            if (language != null) options.put("language", language.split("-")[0]);
            return json.writeValueAsBytes(Map.of("model", model, "stream", false, "asr_options", options,
                "messages", java.util.List.of(Map.of("role", "user", "content", java.util.List.of(
                    Map.of("type", "input_audio", "input_audio", Map.of("data",
                        "data:" + mimeType + ";base64," + java.util.Base64.getEncoder().encodeToString(audio))))))));
        }
        catch (Exception error) { throw problem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT"); }
    }
    private static String publicAddress(URI endpoint) throws IOException
    {
        java.net.InetAddress address = java.net.InetAddress.getByName(endpoint.getHost());
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress())
            throw new IOException("Official ASR resolved to a non-public address");
        return address.getHostAddress();
    }
    private static RuntimeProblem problem(HttpStatus status, String code)
    { return new RuntimeProblem(status, code, code); }
    public record Result(String operationId, String text, String language) { }
}
