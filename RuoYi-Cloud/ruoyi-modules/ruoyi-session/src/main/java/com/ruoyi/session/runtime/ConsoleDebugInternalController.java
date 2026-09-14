package com.ruoyi.session.runtime;

import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private platform API. It deliberately has no endpoint that accepts a console/browser token. */
@RestController
@RequestMapping("/internal/v1/console-debug-sessions")
public class ConsoleDebugInternalController
{
    private final InternalBearerGuard guard;
    private final ConsoleDebugGrantService grants;

    public ConsoleDebugInternalController(InternalBearerGuard guard, ConsoleDebugGrantService grants)
    {
        this.guard = guard;
        this.grants = grants;
    }

    @PostMapping
    public ConsoleDebugGrantService.DebugSession create(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody CreateBody body)
    {
        guard.requireSystem(authorization);
        return grants.create(new ConsoleDebugGrantService.CreateRequest(body.accountId(), body.applicationId(), body.configVersionId(), body.requestId()));
    }

    @PostMapping("/tokens")
    public ConsoleDebugGrantService.IssuedToken mint(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody MintBody body)
    {
        guard.requireSystem(authorization);
        VoiceRuntimeBinding voice = new VoiceRuntimeBinding(body.voiceVersionId(), TtsProviderKind.valueOf(body.providerKind()),
                body.providerVoiceRef(), body.relayVersionRef());
        return grants.mint(new ConsoleDebugGrantService.MintRequest(body.accountId(), body.applicationId(), body.sessionId(),
                body.configVersionId(), body.issuerConsoleRef(), Instant.ofEpochMilli(body.expiresAtEpochMs()), voice));
    }

    public record CreateBody(long accountId, long applicationId, long configVersionId, String requestId) { }
    public record MintBody(long accountId, long applicationId, long sessionId, long configVersionId, String issuerConsoleRef,
            long expiresAtEpochMs, long voiceVersionId, String providerKind, String providerVoiceRef, String relayVersionRef) { }
}
