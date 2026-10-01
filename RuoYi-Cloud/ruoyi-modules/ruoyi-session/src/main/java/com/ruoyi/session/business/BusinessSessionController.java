package com.ruoyi.session.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.session.runtime.RuntimeProblem;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi/v1/sessions")
public class BusinessSessionController
{
    private final BusinessSessionService sessions;
    private final ISessionToolService tools;
    public BusinessSessionController(BusinessSessionService sessions, ISessionToolService tools)
    { this.sessions = sessions; this.tools = tools; }

    @PostMapping
    public BusinessSessionService.View create(@RequestHeader(HttpHeaders.AUTHORIZATION) String secret,
        @RequestHeader("Idempotency-Key") String key, @RequestBody CreateBody body)
    { return sessions.create(secret, requireBody(body).externalUserId(), key, requireBody(body).queryCredential()); }

    @GetMapping("/{sessionId}")
    public BusinessSessionService.View read(@RequestHeader(HttpHeaders.AUTHORIZATION) String secret,
        @PathVariable long sessionId, @RequestParam String externalUserId)
    { return sessions.read(secret, sessionId, externalUserId); }

    @DeleteMapping("/{sessionId}")
    public BusinessSessionService.View close(@RequestHeader(HttpHeaders.AUTHORIZATION) String secret,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @PathVariable long sessionId, @RequestBody UserBody body)
    { return sessions.close(secret, sessionId, requireBody(body).externalUserId(), key, ifMatch); }

    @PostMapping("/{sessionId}/tokens")
    public BusinessSessionStore.IssuedGrant mint(@RequestHeader(HttpHeaders.AUTHORIZATION) String secret,
        @RequestHeader("Idempotency-Key") String key, @PathVariable long sessionId, @RequestBody TokenBody body)
    { return sessions.mint(secret, sessionId, requireBody(body).externalUserId(), key, requireBody(body).scopes()); }

    @PostMapping("/{sessionId}/revocations")
    public Map<String, Object> revoke(@RequestHeader(HttpHeaders.AUTHORIZATION) String secret,
        @RequestHeader("Idempotency-Key") String key, @PathVariable long sessionId, @RequestBody RevokeBody body)
    { return Map.of("revokedSessions", sessions.revoke(secret, sessionId, requireBody(body).externalUserId(), key, requireBody(body).allSessions())); }

    @PostMapping("/{sessionId}/skills/{skillId}/invoke")
    public JsonNode invoke(@RequestHeader(HttpHeaders.AUTHORIZATION) String secret,
        @RequestHeader("Idempotency-Key") String key, @PathVariable long sessionId,
        @PathVariable long skillId, @RequestBody ToolBody body)
    { return tools.invoke(secret, sessionId, skillId, key, requireBody(body).externalUserId(), requireBody(body).arguments()); }

    @ExceptionHandler(RuntimeProblem.class)
    public ResponseEntity<Map<String, Object>> error(RuntimeProblem problem)
    { return ResponseEntity.status(problem.status()).body(Map.of("code", problem.code(), "message", problem.getMessage(),
        "requestId", java.util.UUID.randomUUID().toString(), "retryable", false)); }

    private static <T> T requireBody(T body)
    {
        if (body == null) throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST,
            "INVALID_BODY", "Request body is required.");
        return body;
    }

    public record CreateBody(String externalUserId, String queryCredential) { }
    public record UserBody(String externalUserId) { }
    public record TokenBody(String externalUserId, List<String> scopes) { }
    public record RevokeBody(String externalUserId, boolean allSessions) { }
    public record ToolBody(String externalUserId, com.fasterxml.jackson.databind.JsonNode arguments) { }
}
