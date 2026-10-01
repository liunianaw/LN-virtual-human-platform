package com.ruoyi.session.business;

import com.ruoyi.session.runtime.InternalBearerGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Trusted emergency revocation used after an Application or referenced asset is disabled. */
@RestController
@RequestMapping("/internal/v1/sessions")
public class InternalSessionLifecycleController
{
    private final InternalBearerGuard guard;
    private final BusinessSessionService sessions;

    public InternalSessionLifecycleController(InternalBearerGuard guard, BusinessSessionService sessions)
    { this.guard = guard; this.sessions = sessions; }

    @PostMapping("/{sessionId}/close")
    public ResponseEntity<Void> close(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @PathVariable long sessionId, @RequestBody CloseBody body)
    {
        guard.requireSystem(authorization);
        sessions.forceClose(body.accountId(), sessionId);
        return ResponseEntity.noContent().build();
    }

    public record CloseBody(long accountId) { }
}
