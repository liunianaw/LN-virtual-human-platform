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

/** Current runtime HTTP state, tickets, package and media for BUSINESS grants. */
@RestController
@RequestMapping("/api/v1/runtime")
public class RuntimeVoiceController
{
    private final RuntimeAuthorization access;
    private final SpeakOnlyRuntimeService runtime;
    private final RuntimeConnectionTicketService tickets;
    private final PersistentRuntimeStore store;
    private final RuntimeConnectionEpochs epochs;
    private final TemporaryWavStorage audioStorage;
    private final SystemRuntimeClient system;
    private final RuntimeLimits limits;
    private final AsrRuntimeService asr;

    public RuntimeVoiceController(RuntimeAuthorization access, SpeakOnlyRuntimeService runtime,
            RuntimeConnectionTicketService tickets, PersistentRuntimeStore store,
            TemporaryWavStorage audioStorage, SystemRuntimeClient system, RuntimeLimits limits,
            RuntimeConnectionEpochs epochs, AsrRuntimeService asr)
    {
        this.access = access;
        this.runtime = runtime;
        this.tickets = tickets;
        this.store = store;
        this.audioStorage = audioStorage;
        this.system = system;
        this.limits = limits;
        this.epochs = epochs;
        this.asr = asr;
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
        java.util.List<String> capabilities = new java.util.ArrayList<>(
            java.util.List.of("speech.create", "turn.stop", "playback.report"));
        state.put("capabilities", capabilities);
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

    @PostMapping(value = "/asr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RuntimeEnvelope<AsrRuntimeService.Result>> asr(
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader("Idempotency-Key") String requestId,
        @org.springframework.web.bind.annotation.RequestPart("audio") org.springframework.web.multipart.MultipartFile audio,
        @org.springframework.web.bind.annotation.RequestParam(required = false) String language) throws java.io.IOException
    {
        RuntimeAuthorization.Grant grant = access.authenticate(authorization);
        if (audio.getSize() <= 0 || audio.getSize() > 1_900_000)
            throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "RECORDING_SIZE_INVALID", "Recording size is invalid.");
        AsrRuntimeService.Result result = asr.transcribe(grant, requestId, audio.getContentType(), audio.getBytes(), language);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(RuntimeEnvelope.ok(result));
    }

    @PostMapping("/stop")
    public RuntimeEnvelope<StopResponse> stop(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("X-Connection-Epoch") String connectionEpoch, @RequestBody StopRequest request)
    {
        RuntimePrincipal principal = access.authenticate(authorization).principal();
        principal.requireSpeakScope();
        requireCurrentConnection(principal, connectionEpoch);
        if (request == null || request.turnId() == null || !request.turnId().matches("[1-9][0-9]{0,18}"))
            throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Turn ID is invalid.");
        long turnId;
        try { turnId = Long.parseLong(request.turnId()); }
        catch (NumberFormatException error)
        { throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Turn ID is invalid."); }
        SpeakOnlyRuntimeService.StopResult stopped = runtime.stop(principal, request.turnId());
        return RuntimeEnvelope.ok(new StopResponse(stopped.turnId(), stopped.alreadyStopped()));
    }

    private static long epoch(String value)
    {
        if (value == null || !value.matches("[1-9][0-9]*"))
            throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "EPOCH_MISMATCH", "Connection epoch is invalid.");
        try { return Long.parseLong(value); }
        catch (NumberFormatException error)
        { throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "EPOCH_MISMATCH", "Connection epoch is invalid."); }
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

    public record TicketRequest(String purpose, String connectionEpoch) { }
    public record TicketResponse(String ticket, String expiresAt, String webSocketUrl, String protocol) { }

    public record StopRequest(String turnId, String reason)
    {
    }

    public record StopResponse(String turnId, boolean alreadyStopped)
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
            return new RuntimeEnvelope<>(code, null,
                new RuntimeError(code, message, false, java.util.UUID.randomUUID().toString()));
        }
    }

    public record RuntimeError(String code, String message, boolean retryable, String requestId)
    {
    }
}
