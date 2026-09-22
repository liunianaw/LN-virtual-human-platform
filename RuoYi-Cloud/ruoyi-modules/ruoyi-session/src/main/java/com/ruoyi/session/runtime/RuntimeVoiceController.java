package com.ruoyi.session.runtime;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Temporary HTTP DEBUG harness; WSS delivery and provider execution remain later integration work. */
@RestController
@RequestMapping("/api/v1/runtime")
public class RuntimeVoiceController
{
    private final ConsoleDebugSessionAuthenticator authenticator;
    private final TtsRuntimeAdapterRegistry adapters;
    private final SpeakOnlyRuntimeService runtime;
    private final RuntimeConnectionTicketService tickets;
    private final PersistentRuntimeStore store;
    private final TemporaryWavStorage audioStorage;
    private final SystemRuntimeClient system;

    public RuntimeVoiceController(ConsoleDebugSessionAuthenticator authenticator, TtsRuntimeAdapterRegistry adapters,
            SpeakOnlyRuntimeService runtime, RuntimeConnectionTicketService tickets, PersistentRuntimeStore store,
            TemporaryWavStorage audioStorage, SystemRuntimeClient system)
    {
        this.authenticator = authenticator;
        this.adapters = adapters;
        this.runtime = runtime;
        this.tickets = tickets;
        this.store = store;
        this.audioStorage = audioStorage;
        this.system = system;
    }

    @PostMapping("/connection-tickets")
    public RuntimeEnvelope<TicketResponse> ticket(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody TicketRequest request)
    {
        RuntimePrincipal principal = authenticator.authenticate(authorization);
        RuntimeConnectionTicketService.IssuedTicket issued = tickets.issue(principal, request == null ? null : request.purpose());
        return RuntimeEnvelope.ok(new TicketResponse(issued.ticket(), issued.expiresAt().toString(), "ln-avatar.v1"));
    }

    @org.springframework.web.bind.annotation.GetMapping("/avatar-package")
    public RuntimeEnvelope<java.util.Map<String, Object>> avatarPackage(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization)
    {
        RuntimePrincipal principal = authenticator.authenticate(authorization);
        principal.requireAvatarScope();
        return RuntimeEnvelope.ok(system.avatarPackage(principal));
    }

    @org.springframework.web.bind.annotation.GetMapping(value = "/media/{mediaId}", produces = "audio/wav")
    public ResponseEntity<byte[]> media(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @org.springframework.web.bind.annotation.PathVariable String mediaId)
    {
        RuntimePrincipal principal = authenticator.authenticate(authorization);
        return ResponseEntity.ok().contentType(MediaType.valueOf("audio/wav")).header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(audioStorage.read(store.readableAudio(principal, mediaId)));
    }

    @PostMapping("/debug/speech")
    public RuntimeEnvelope<SpeechResponse> speech(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody SpeechRequest request)
    {
        RuntimePrincipal principal = authenticator.authenticate(authorization);
        principal.requireSpeakScope();
        TtsRuntimeAdapter adapter = adapters.requireAdapter(principal.voice());
        SpeakOnlyRuntimeService.SpeechStarted started = runtime.start(principal, request.requestId(), request.text());
        dispatch(adapter, started.initialWork());
        return RuntimeEnvelope.ok(new SpeechResponse(started.turnId(), started.generation(), started.segmentCount()));
    }

    @PostMapping("/stop")
    public RuntimeEnvelope<StopResponse> stop(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody StopRequest request)
    {
        RuntimePrincipal principal = authenticator.authenticate(authorization);
        SpeakOnlyRuntimeService.StopResult stopped = runtime.stop(principal, request.turnId());
        return RuntimeEnvelope.ok(new StopResponse(stopped.turnId(), stopped.alreadyStopped()));
    }

    @PostMapping("/debug/playback-report")
    public RuntimeEnvelope<PlaybackResponse> playback(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody PlaybackRequest request)
    {
        RuntimePrincipal principal = authenticator.authenticate(authorization);
        SpeakOnlyRuntimeService.PlaybackUpdated updated = runtime.reportPlayback(principal, request.turnId(), request.segmentId(),
                request.state());
        if (updated.accepted() && !updated.nextWork().isEmpty())
        {
            dispatch(adapters.requireAdapter(principal.voice()), updated.nextWork());
        }
        return RuntimeEnvelope.ok(new PlaybackResponse(updated.accepted()));
    }

    @ExceptionHandler(RuntimeProblem.class)
    ResponseEntity<RuntimeEnvelope<Void>> runtimeProblem(RuntimeProblem problem)
    {
        return ResponseEntity.status(problem.status()).body(RuntimeEnvelope.error(problem.code(), problem.getMessage()));
    }

    private void dispatch(TtsRuntimeAdapter adapter, Iterable<TtsSynthesisWork> work)
    {
        for (TtsSynthesisWork item : work)
        {
            adapter.submit(item, runtime);
        }
    }

    public record SpeechRequest(String requestId, String text)
    {
    }

    public record TicketRequest(String purpose) { }
    public record TicketResponse(String ticket, String expiresAt, String protocol) { }

    public record StopRequest(String turnId, String reason)
    {
    }

    public record PlaybackRequest(String turnId, String segmentId, PlaybackState state)
    {
    }

    public record SpeechResponse(String turnId, long generation, int segmentCount)
    {
    }

    public record StopResponse(String turnId, boolean alreadyStopped)
    {
    }

    public record PlaybackResponse(boolean accepted)
    {
    }

    public record RuntimeEnvelope<T>(String code, T data, RuntimeError error)
    {
        static <T> RuntimeEnvelope<T> ok(T data)
        {
            return new RuntimeEnvelope<>("OK", data, null);
        }

        static RuntimeEnvelope<Void> error(String code, String message)
        {
            return new RuntimeEnvelope<>(code, null, new RuntimeError(code, message));
        }
    }

    public record RuntimeError(String code, String message)
    {
    }
}
