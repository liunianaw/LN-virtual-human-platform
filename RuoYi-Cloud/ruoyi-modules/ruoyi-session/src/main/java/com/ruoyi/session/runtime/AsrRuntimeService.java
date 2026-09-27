package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.session.business.BusinessSessionService;
import com.ruoyi.session.business.BusinessSystemClient;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** A single manual recording request; the audio and recognized text are never persisted here. */
@Service
public class AsrRuntimeService
{
    private final ChatTurnStore store;
    private final BusinessSystemClient system;
    private final BusinessSessionService business;
    private final PinnedHttps https;
    private final RuntimeAuthorization access;
    private final ObjectMapper json;

    public AsrRuntimeService(ChatTurnStore store, BusinessSystemClient system, BusinessSessionService business,
        PinnedHttps https, RuntimeAuthorization access, ObjectMapper json)
    { this.store = store; this.system = system; this.business = business; this.https = https; this.access = access; this.json = json; }

    public Result transcribe(RuntimeAuthorization.Grant grant, String requestId, String mimeType, byte[] audio, String language)
    {
        RuntimePrincipal principal = grant.principal();
        if (!principal.scopes().contains("asr:write")) throw problem(HttpStatus.FORBIDDEN, "SCOPE_DENIED");
        mimeType = mimeType == null ? "" : mimeType.split(";", 2)[0].trim();
        if (requestId == null || !requestId.matches("[\\x21-\\x7e]{1,64}") || audio == null || audio.length == 0
            || audio.length > 1_900_000 || !java.util.Set.of("audio/webm", "audio/mp4", "audio/ogg", "audio/wav").contains(mimeType)
            || language != null && !language.matches("[A-Za-z]{2,8}(?:-[A-Za-z]{2,8})?"))
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        JsonNode config = system.chatConfig(principal.accountId(), principal.applicationId(),
            principal.configVersionId(), principal.sessionId());
        long relayVersionId = config.path("asrRelayVersionId").asLong();
        if (relayVersionId <= 0) throw problem(HttpStatus.FORBIDDEN, "ASR_NOT_CONFIGURED");
        String externalUserId = "CONSOLE_DEBUG".equals(grant.source())
            ? "__ln_debug__:" + principal.sessionId() : store.externalUserId(principal);
        if (externalUserId == null || externalUserId.isBlank()) throw problem(HttpStatus.CONFLICT, "SESSION_NOT_READY");
        JsonNode relay = system.resolveAsrRelay(principal.accountId(), principal.applicationId(),
            principal.sessionId(), relayVersionId, externalUserId);
        long operationId = store.beginAsr(principal, requestId, relayVersionId, audio);
        java.util.concurrent.atomic.AtomicBoolean submitted = new java.util.concurrent.atomic.AtomicBoolean();
        String boundary = "ln-" + operationId;
        byte[] body = multipart(boundary, audio, mimeType, operationId, principal, externalUserId, language);
        Result recognized;
        String providerRequestId;
        Long durationMs;
        try (PinnedHttps.Response response = https.request(
            URI.create(relay.path("baseUrl").asText() + "/audio/transcriptions"),
            relay.path("pinnedAddress").asText(), "POST",
            Map.of("Authorization", "Bearer " + relay.path("accessToken").asText(),
                "X-LN-Protocol-Version", "1", "X-Request-Id", Long.toString(operationId),
                "Content-Type", "multipart/form-data; boundary=" + boundary),
            body, relay.path("timeoutMs").asInt(30000), Math.min(65536, relay.path("maxResponseBytes").asLong(65536)),
            () -> { access.verify(grant.id()); submitted.set(true); }))
        {
            if (!response.contentType().startsWith("application/json")) throw new IOException("Invalid ASR content type");
            JsonNode result = json.readTree(response.body());
            String text = result.path("text").asText();
            if (text.isBlank() || text.length() > 4096) throw new IOException("Invalid ASR text");
            providerRequestId = result.path("providerRequestId").asText(null);
            if (providerRequestId != null && !providerRequestId.matches("[A-Za-z0-9._:-]{1,128}"))
                throw new IOException("Invalid ASR request ID");
            durationMs = result.path("durationMs").canConvertToLong() ? result.path("durationMs").asLong() : null;
            if (durationMs != null && (durationMs < 0 || durationMs > 600000)) throw new IOException("Invalid ASR duration");
            recognized = new Result(Long.toString(operationId), text, result.path("language").asText(null));
        }
        catch (Exception error)
        {
            store.endAsr(principal, operationId, submitted.get() ? "UNKNOWN" : "FAILED", "ASR_UPSTREAM_FAILED", null, null);
            throw problem(HttpStatus.BAD_GATEWAY, "ASR_UPSTREAM_FAILED");
        }
        store.endAsr(principal, operationId, "SUCCEEDED", null, providerRequestId, durationMs);
        if ("BUSINESS_KEY".equals(grant.source())) business.successfulActivity(principal.sessionId());
        return recognized;
    }

    private static byte[] multipart(String boundary, byte[] audio, String mimeType, long operationId,
        RuntimePrincipal principal, String externalUserId, String language)
    {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        field(out, boundary, "requestId", Long.toString(operationId));
        field(out, boundary, "applicationId", Long.toString(principal.applicationId()));
        field(out, boundary, "sessionId", Long.toString(principal.sessionId()));
        field(out, boundary, "externalUserId", externalUserId);
        if (language != null) field(out, boundary, "language", language);
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"recording\"\r\n" +
            "Content-Type: " + mimeType + "\r\n\r\n");
        out.writeBytes(audio);
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void field(java.io.ByteArrayOutputStream out, String boundary, String name, String value)
    {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }
    private static void write(java.io.ByteArrayOutputStream out, String value)
    { out.writeBytes(value.getBytes(StandardCharsets.UTF_8)); }
    private static RuntimeProblem problem(HttpStatus status, String code)
    { return new RuntimeProblem(status, code, code); }
    public record Result(String operationId, String text, String language) { }
}
