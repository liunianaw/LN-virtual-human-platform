package com.ruoyi.session.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.session.runtime.PinnedHttps;
import com.ruoyi.session.runtime.RuntimeProblem;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Application-Secret-only execution of a Tool frozen in one Session snapshot. */
@Service
public class SessionToolServiceImpl implements ISessionToolService
{
    private final BusinessSystemClient system;
    private final BusinessSessionStore sessions;
    private final BusinessCredentialStore credentials;
    private final ToolInvocationStore invocations;
    private final SessionToolPolicy policy;
    private final PinnedHttps https;
    private final ObjectMapper json;

    public SessionToolServiceImpl(BusinessSystemClient system, BusinessSessionStore sessions,
        BusinessCredentialStore credentials, ToolInvocationStore invocations, SessionToolPolicy policy,
        PinnedHttps https, ObjectMapper json)
    {
        this.system = system; this.sessions = sessions; this.credentials = credentials;
        this.invocations = invocations; this.policy = policy; this.https = https; this.json = json;
    }

    public JsonNode invoke(String authorization, long sessionId, long skillId, String key,
        String externalUserId, JsonNode arguments)
    {
        if (key == null || !key.matches("[\\x21-\\x7e]{1,64}")) throw bad("IDEMPOTENCY_KEY_INVALID");
        if (externalUserId == null || externalUserId.isBlank() || externalUserId.length() > 191)
            throw bad("EXTERNAL_USER_INVALID");
        BusinessSystemClient.Snapshot current = system.authenticate(authorization, "skills:invoke");
        BusinessSessionStore.Session session = sessions.owned(current.accountId(), current.applicationId(),
            sessionId, externalUserId);
        if (!"ACTIVE".equals(session.status())) throw conflict("SESSION_NOT_ACTIVE");
        JsonNode skill = snapshotSkill(session.snapshotId(), skillId);
        if (!"HTTP_TOOL".equals(skill.path("skillType").asText())) throw missing("TOOL_NOT_BOUND");
        policy.arguments(skill.path("inputSchema"), arguments);
        java.util.SortedMap<String, JsonNode> canonical = new java.util.TreeMap<>();
        arguments.fields().forEachRemaining(entry -> canonical.put(entry.getKey(), entry.getValue()));
        byte[] requestHash = digest(externalUserId + "\n" + json.valueToTree(canonical).toString());
        int maximum = skill.path("maxCallsPerSession").asInt();
        String toolUrl = skill.path("toolUrl").asText();
        Long secretId = skill.hasNonNull("toolSecretId") ? skill.path("toolSecretId").asLong() : null;
        BusinessSystemClient.ResolvedTool resolved = system.resolveTool(current.accountId(),
            current.applicationId(), sessionId, skillId, secretId, toolUrl);
        ToolInvocationStore.Claim claim = invocations.claim(current.accountId(), sessionId, skillId,
            key, requestHash, maximum);
        if (claim.replay())
        {
            if ("SUCCEEDED".equals(claim.status())) return readResult(claim.resultJson());
            throw conflict(claim.errorCode() == null ? "TOOL_FAILED" : claim.errorCode());
        }
        java.util.concurrent.atomic.AtomicBoolean submitted = new java.util.concurrent.atomic.AtomicBoolean();
        try
        {
            Map<String, String> headers = headers(skill, session, resolved, claim.id());
            String method = skill.path("httpMethod").asText();
            URI target = URI.create(toolUrl);
            byte[] body = new byte[0];
            if ("GET".equals(method)) target = query(target, arguments);
            else
            {
                headers.put("Content-Type", "application/json");
                body = json.writeValueAsBytes(arguments);
            }
            byte[] raw;
            try (PinnedHttps.Response response = https.request(target, resolved.pinnedAddress(), method,
                headers, body, skill.path("timeoutMs").asInt(), skill.path("maxResultBytes").asLong(), () -> {
                    requireActive(session, externalUserId);
                    system.resolveTool(current.accountId(), current.applicationId(), sessionId, skillId, secretId, toolUrl);
                    submitted.set(true);
                }))
            {
                if (!response.contentType().startsWith("application/json")) throw new IOException("Invalid Tool content type");
                raw = response.body().readAllBytes();
            }
            List<String> fields = new ArrayList<>();
            for (JsonNode field : skill.path("frontendFields")) fields.add(field.asText());
            ObjectNode result = policy.publicResult(skill.path("outputSchema"), fields, raw,
                skill.path("maxResultBytes").asLong());
            requireActive(session, externalUserId);
            system.resolveTool(current.accountId(), current.applicationId(), sessionId, skillId, secretId, toolUrl);
            invocations.succeed(current.accountId(), sessionId, skillId, claim.id(), 200,
                json.writeValueAsString(result), raw.length);
            sessions.touchSuccessfulActivity(sessionId);
            return result;
        }
        catch (RuntimeProblem error)
        {
            invocations.fail(current.accountId(), sessionId, skillId, claim.id(), error.code(), submitted.get());
            throw error;
        }
        catch (Exception error)
        {
            invocations.fail(current.accountId(), sessionId, skillId, claim.id(), "TOOL_UNAVAILABLE", submitted.get());
            throw new RuntimeProblem(HttpStatus.BAD_GATEWAY, "TOOL_UNAVAILABLE", "TOOL_UNAVAILABLE");
        }
    }

    private void requireActive(BusinessSessionStore.Session expected, String externalUserId)
    {
        BusinessSessionStore.Session current = sessions.owned(expected.accountId(), expected.applicationId(),
            expected.id(), externalUserId);
        if (!"ACTIVE".equals(current.status()) || current.snapshotId() != expected.snapshotId())
            throw conflict("SESSION_NOT_ACTIVE");
    }

    private JsonNode snapshotSkill(long snapshotId, long skillId)
    {
        BusinessSessionStore.Snapshot snapshot = sessions.snapshot(snapshotId);
        if (snapshot == null) throw missing("SESSION_SNAPSHOT_NOT_FOUND");
        try
        {
            JsonNode list = json.readTree(snapshot.internalSkills());
            if (!list.isArray()) throw new IllegalArgumentException();
            for (JsonNode skill : list) if (skill.path("skillId").asLong() == skillId) return skill;
            throw missing("TOOL_NOT_BOUND");
        }
        catch (RuntimeProblem error) { throw error; }
        catch (Exception error) { throw conflict("SESSION_SNAPSHOT_INVALID"); }
    }

    private Map<String, String> headers(JsonNode skill, BusinessSessionStore.Session session,
        BusinessSystemClient.ResolvedTool resolved, long invocationId)
    {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("X-Request-Id", Long.toString(invocationId));
        headers.put("X-LN-External-User-Id-B64", Base64.getUrlEncoder().withoutPadding()
            .encodeToString(session.externalUserId().getBytes(StandardCharsets.UTF_8)));
        headers.put("X-LN-Application-Id", Long.toString(session.applicationId()));
        headers.put("X-LN-Session-Id", Long.toString(session.id()));
        if (resolved.accessToken() != null) headers.put("Authorization", "Bearer " + resolved.accessToken());
        if (skill.path("requiresUserCredential").asBoolean())
        {
            String credential = credentials.readForTool(session.id());
            if (credential == null) throw conflict("TOOL_CREDENTIAL_REQUIRED");
            headers.put("X-LN-Query-Credential-B64", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
        }
        return headers;
    }

    private static URI query(URI target, JsonNode arguments)
    {
        List<String> fields = new ArrayList<>();
        arguments.fields().forEachRemaining(entry -> fields.add(
            URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" +
            URLEncoder.encode(entry.getValue().asText(), StandardCharsets.UTF_8)));
        return URI.create(target + "?" + String.join("&", fields));
    }
    private JsonNode readResult(String result)
    {
        try { return json.readTree(result); }
        catch (Exception error) { throw conflict("TOOL_RESULT_UNAVAILABLE"); }
    }
    private static byte[] digest(String value)
    {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static RuntimeProblem bad(String code) { return new RuntimeProblem(HttpStatus.BAD_REQUEST, code, code); }
    private static RuntimeProblem missing(String code) { return new RuntimeProblem(HttpStatus.NOT_FOUND, code, code); }
    private static RuntimeProblem conflict(String code) { return new RuntimeProblem(HttpStatus.CONFLICT, code, code); }
}
