package com.ruoyi.session.runtime;

import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final SpeakOnlyRuntimeService runtime;
    private final RuntimeEventPublisher events;
    private final IChatRuntimeService chat;

    public ConsoleDebugInternalController(InternalBearerGuard guard, ConsoleDebugGrantService grants,
        SpeakOnlyRuntimeService runtime, RuntimeEventPublisher events, IChatRuntimeService chat)
    {
        this.guard = guard;
        this.grants = grants;
        this.runtime = runtime;
        this.events = events;
        this.chat = chat;
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
                body.providerVoiceRef(), body.relayVersionRef(), body.officialServiceId(), body.officialServiceRevision());
        return grants.mint(new ConsoleDebugGrantService.MintRequest(body.accountId(), body.applicationId(), body.sessionId(),
                body.configVersionId(), body.issuerConsoleRef(), Instant.ofEpochMilli(body.expiresAtEpochMs()), voice,
                body.mode(), body.asrEnabled()));
    }

    @DeleteMapping("/{sessionId}")
    public void close(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @org.springframework.web.bind.annotation.PathVariable long sessionId,
        @RequestBody CloseBody body)
    {
        guard.requireSystem(authorization);
        grants.close(body.accountId(), sessionId);
        runtime.revokeSession(sessionId);
        chat.cancelPrior(sessionId, Long.MAX_VALUE);
        events.revokeSession(sessionId);
    }

    public record CreateBody(long accountId, long applicationId, long configVersionId, String requestId) { }
    public record MintBody(long accountId, long applicationId, long sessionId, long configVersionId, String issuerConsoleRef,
            long expiresAtEpochMs, long voiceVersionId, String providerKind, String providerVoiceRef, String relayVersionRef,
            Long officialServiceId, Long officialServiceRevision, String mode, boolean asrEnabled) { }
    public record CloseBody(long accountId) { }
}
