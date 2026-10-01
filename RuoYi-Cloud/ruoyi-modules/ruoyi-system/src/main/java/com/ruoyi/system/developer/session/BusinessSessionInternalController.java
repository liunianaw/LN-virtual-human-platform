package com.ruoyi.system.developer.session;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.voice.InternalBearerGuard;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/business-sessions")
public class BusinessSessionInternalController
{
    private final InternalBearerGuard guard;
    private final BusinessSessionAuthorization authorization;
    public BusinessSessionInternalController(InternalBearerGuard guard, BusinessSessionAuthorization authorization)
    { this.guard = guard; this.authorization = authorization; }

    @PostMapping("/authenticate")
    public Map<String, Object> authenticate(@RequestHeader(HttpHeaders.AUTHORIZATION) String bearer,
        @RequestBody AuthenticateBody body)
    { guard.requireSession(bearer); return authorization.authenticate(body.secret(), body.scope()); }

    @PostMapping("/check")
    public Map<String, Object> check(@RequestHeader(HttpHeaders.AUTHORIZATION) String bearer,
        @RequestBody ReferenceBody body)
    { guard.requireSession(bearer); return authorization.checkSession(body.accountId(), body.applicationId(), body.sessionId()); }

    @PostMapping("/recording-limit")
    public Map<String, Long> recordingLimit(@RequestHeader(HttpHeaders.AUTHORIZATION) String bearer,
        @RequestBody ReferenceBody body)
    { guard.requireSession(bearer); return Map.of("maxRecordingBytes",
        authorization.recordingLimit(body.accountId(), body.applicationId())); }

    @PostMapping("/references/reserve")
    public Map<String, Object> reserve(@RequestHeader(HttpHeaders.AUTHORIZATION) String bearer,
        @RequestBody ReferenceBody body)
    { guard.requireSession(bearer); return authorization.reserve(body.accountId(), body.applicationId(),
        body.applicationRevision(), body.sessionId(), body.operationId()); }

    @PostMapping("/references/confirm")
    public void confirm(@RequestHeader(HttpHeaders.AUTHORIZATION) String bearer, @RequestBody ReferenceBody body)
    { guard.requireSession(bearer); authorization.confirm(body.accountId(), body.sessionId(), body.operationId()); }

    @PostMapping("/references/release")
    public void release(@RequestHeader(HttpHeaders.AUTHORIZATION) String bearer, @RequestBody ReferenceBody body)
    { guard.requireSession(bearer); authorization.release(body.accountId(), body.sessionId(), body.operationId()); }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Map<String, String>> error(ServiceException problem)
    {
        int status = problem.getCode() == null ? 500 : problem.getCode();
        return ResponseEntity.status(status).body(Map.of("code", Integer.toString(status), "message", problem.getMessage()));
    }
    public record AuthenticateBody(String secret, String scope) { }
    public record ReferenceBody(long accountId, long applicationId, long applicationRevision,
        long sessionId, String operationId) { }
}
