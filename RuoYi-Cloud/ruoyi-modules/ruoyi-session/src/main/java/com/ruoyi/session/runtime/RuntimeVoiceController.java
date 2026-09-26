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

/** Current runtime HTTP state, tickets, package and media for DEBUG and BUSINESS grants. */
@RestController
@RequestMapping("/api/v1/runtime")
public class RuntimeVoiceController
{
    private final RuntimeAuthorization access;
    private final TtsRuntimeAdapterRegistry adapters;
    private final SpeakOnlyRuntimeService runtime;
    private final RuntimeConnectionTicketService tickets;
    private final PersistentRuntimeStore store;
    private final RuntimeConnectionEpochs epochs;
    private final TemporaryWavStorage audioStorage;
    private final SystemRuntimeClient system;
    private final RuntimeLimits limits;

    public RuntimeVoiceController(RuntimeAuthorization access, TtsRuntimeAdapterRegistry adapters,
            SpeakOnlyRuntimeService runtime, RuntimeConnectionTicketService tickets, PersistentRuntimeStore store,
            TemporaryWavStorage audioStorage, SystemRuntimeClient system, RuntimeLimits limits,
            RuntimeConnectionEpochs epochs)
    {
        this.access = access;
        this.adapters = adapters;
        this.runtime = runtime;
        this.tickets = tickets;
        this.store = store;
        this.audioStorage = audioStorage;
        this.system = system;
        this.limits = limits;
        this.epochs = epochs;
    }

    @PostMapping("/connection-tickets")
    public ResponseEntity<RuntimeEnvelope<TicketResponse>> ticket(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody TicketRequest request)
    {
        RuntimeAuthorization.Grant grant = access.authenticate(authorization);
        String websocketUrl = System.getenv("LN_PUBLIC_RUNTIME_WS_URL");
        if (websocketUrl == null || !(websocketUrl.matches("wss://[^?#]+/api/v1/realtime")
            || websocketUrl.matches("ws://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}/api/v1/realtime")))
            throw new RuntimeProblem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_WS_NOT_CONFIGURED", "Runtime WSS URL is unavailable.");
        RuntimeConnectionTicketService.IssuedTicket issued = tickets.issue(grant, request == null ? null : request.purpose(),
            request == null || request.connectionEpoch() == null ? null : epoch(request.connectionEpoch()));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(RuntimeEnvelope.ok(new TicketResponse(issued.ticket(), issued.expiresAt().toString(), websocketUrl, "ln-avatar.v1")));
    }

    @org.springframework.web.bind.annotation.GetMapping("/session")
    public ResponseEntity<RuntimeEnvelope<java.util.Map<String, Object>>> session(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization)
    {
        RuntimeAuthorization.Grant grant = access.authenticate(authorization);
        java.util.Map<String, Object> state = store.sessionState(grant.principal());
        state.put("effectiveLimits", limits.current());
        state.put("capabilities", "CONSOLE_DEBUG".equals(grant.source())
            ? java.util.List.of("speech.create", "turn.stop", "playback.report") : java.util.List.of("turn.stop"));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(RuntimeEnvelope.ok(state));
    }

    @org.springframework.web.bind.annotation.GetMapping("/avatar-package")
    public ResponseEntity<RuntimeEnvelope<java.util.Map<String, Object>>> avatarPackage(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization)
    {
        RuntimePrincipal principal = access.authenticate(authorization).principal();
        principal.requireAvatarScope();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(RuntimeEnvelope.ok(system.avatarPackage(principal)));
    }

    @org.springframework.web.bind.annotation.GetMapping(value = "/media/{mediaId}", produces = "audio/wav")
    public ResponseEntity<byte[]> media(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @org.springframework.web.bind.annotation.PathVariable String mediaId)
    {
        RuntimePrincipal principal = access.authenticate(authorization).principal();
        principal.requireSpeakScope();
        return ResponseEntity.ok().contentType(MediaType.valueOf("audio/wav")).header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(audioStorage.read(store.readableAudio(principal, mediaId)));
    }

    @PostMapping("/debug/speech")
    public RuntimeEnvelope<SpeechResponse> speech(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("X-Connection-Epoch") String connectionEpoch, @RequestBody SpeechRequest request)
    {
        RuntimePrincipal principal = debugOnly(authorization, connectionEpoch);
        principal.requireSpeakScope();
        TtsRuntimeAdapter adapter = adapters.requireAdapter(principal.voice());
        SpeakOnlyRuntimeService.SpeechStarted started = runtime.start(principal, request.requestId(), request.text(), epoch(connectionEpoch));
        dispatch(adapter, started.initialWork());
        return RuntimeEnvelope.ok(new SpeechResponse(started.turnId(), started.generation(), started.segmentCount()));
    }

    @PostMapping("/stop")
    public RuntimeEnvelope<StopResponse> stop(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("X-Connection-Epoch") String connectionEpoch, @RequestBody StopRequest request)
    {
        RuntimePrincipal principal = access.authenticate(authorization).principal();
        principal.requireSpeakScope();
        requireCurrentConnection(principal, connectionEpoch);
        SpeakOnlyRuntimeService.StopResult stopped = runtime.stop(principal, request.turnId());
        return RuntimeEnvelope.ok(new StopResponse(stopped.turnId(), stopped.alreadyStopped()));
    }

    @PostMapping("/debug/playback-report")
    public RuntimeEnvelope<PlaybackResponse> playback(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("X-Connection-Epoch") String connectionEpoch, @RequestBody PlaybackRequest request)
    {
        RuntimePrincipal principal = debugOnly(authorization, connectionEpoch);
        SpeakOnlyRuntimeService.PlaybackUpdated updated = runtime.reportPlayback(principal, request.turnId(), request.segmentId(),
                request.state());
        if (updated.accepted() && !updated.nextWork().isEmpty())
        {
            dispatch(adapters.requireAdapter(principal.voice()), updated.nextWork());
        }
        return RuntimeEnvelope.ok(new PlaybackResponse(updated.accepted()));
    }

    private static long epoch(String value)
    {
        if (value == null || !value.matches("[1-9][0-9]*"))
            throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "EPOCH_MISMATCH", "Connection epoch is invalid.");
        try { return Long.parseLong(value); }
        catch (NumberFormatException error)
        { throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "EPOCH_MISMATCH", "Connection epoch is invalid."); }
    }

    private RuntimePrincipal debugOnly(String authorization, String connectionEpoch)
    {
        RuntimeAuthorization.Grant grant = access.authenticate(authorization);
        if (!"CONSOLE_DEBUG".equals(grant.source()))
            throw new RuntimeProblem(org.springframework.http.HttpStatus.FORBIDDEN, "TOKEN_SOURCE_INVALID", "DEBUG endpoint requires a DEBUG grant.");
        requireCurrentConnection(grant.principal(), connectionEpoch);
        return grant.principal();
    }

    private void requireCurrentConnection(RuntimePrincipal principal, String connectionEpoch)
    {
        if (!epochs.current(principal, epoch(connectionEpoch)))
            throw new RuntimeProblem(org.springframework.http.HttpStatus.CONFLICT, "CONNECTION_REPLACED", "Connection was replaced.");
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

    public record TicketRequest(String purpose, String connectionEpoch) { }
    public record TicketResponse(String ticket, String expiresAt, String webSocketUrl, String protocol) { }

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
