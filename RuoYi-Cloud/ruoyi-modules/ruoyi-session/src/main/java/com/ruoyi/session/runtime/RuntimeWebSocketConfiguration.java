package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.session.business.BusinessSessionService;
import java.util.List;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** C4 runtime endpoint: browsers authenticate with a one-time first frame, never an Authorization handshake header. */
@Configuration
@EnableWebSocket
public class RuntimeWebSocketConfiguration implements WebSocketConfigurer
{
    private final RuntimeConnectionTicketService tickets;
    private final RuntimeAuthorization access;
    private final RuntimeConnectionEpochs epochs;
    private final PersistentRuntimeStore store;
    private final SpeakOnlyRuntimeService runtime;
    private final BusinessSessionService business;
    private final TtsRuntimeAdapterRegistry adapters;
    private final RuntimeEventPublisher events;
    private final ObjectMapper json;
    private final RuntimeLimits limits;

    public RuntimeWebSocketConfiguration(RuntimeConnectionTicketService tickets, RuntimeAuthorization access,
        RuntimeConnectionEpochs epochs, PersistentRuntimeStore store, SpeakOnlyRuntimeService runtime,
        BusinessSessionService business, TtsRuntimeAdapterRegistry adapters, RuntimeEventPublisher events,
        ObjectMapper json, RuntimeLimits limits)
    {
        this.tickets = tickets; this.access = access; this.epochs = epochs; this.store = store;
        this.runtime = runtime; this.business = business; this.adapters = adapters; this.events = events; this.json = json; this.limits = limits;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry)
    {
        registry.addHandler(new RuntimeHandler(), "/api/v1/realtime").setAllowedOrigins(allowedOrigins());
        // Retained while callers migrate; it shares the same first-frame ticket rules.
        registry.addHandler(new RuntimeHandler(), "/api/v1/runtime/ws").setAllowedOrigins(allowedOrigins());
    }

    private static String[] allowedOrigins()
    {
        String configured = System.getenv("LN_RUNTIME_ALLOWED_ORIGINS");
        if (configured == null || configured.isBlank()) return new String[0];
        String[] origins = configured.split(",");
        for (int index = 0; index < origins.length; index++)
        {
            origins[index] = origins[index].trim();
            if (!origins[index].matches("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?")
                && !origins[index].matches("http://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]{1,5})?"))
                throw new IllegalArgumentException("LN_RUNTIME_ALLOWED_ORIGINS must contain explicit HTTPS origins");
        }
        return origins;
    }

    private final class RuntimeHandler extends TextWebSocketHandler implements org.springframework.web.socket.SubProtocolCapable
    {
        @Override
        public List<String> getSubProtocols() { return List.of("ln-avatar.v1"); }

        @Override
        public void afterConnectionEstablished(WebSocketSession session)
        {
            session.getAttributes().put("openedAt", Instant.now());
            java.util.concurrent.CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                if (session.isOpen() && session.getAttributes().get("runtimeGrant") == null)
                {
                    try { session.close(new CloseStatus(4401, "ticket required")); }
                    catch (IOException ignored) { }
                }
            });
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException
        {
            String requestId = "";
            String turnId = null;
            try
            {
                if (message.getPayloadLength() > 16384) throw problem("PROTOCOL_ERROR");
                JsonNode node = json.readTree(message.getPayload());
                if (node == null || node.path("v").asInt(-1) != 1 || !node.path("data").isObject()) throw problem("PROTOCOL_ERROR");
                requestId = node.path("requestId").asText();
                String suppliedTurn = node.path("turnId").asText();
                if (suppliedTurn.matches("[1-9][0-9]{0,18}")) turnId = suppliedTurn;
                if (!requestId.matches("[\\x21-\\x7e]{1,64}")) throw problem("PROTOCOL_ERROR");
                session.getAttributes().put("lastFrameAt", Instant.now());
                RuntimeAuthorization.Grant current = (RuntimeAuthorization.Grant) session.getAttributes().get("runtimeGrant");
                if (current == null) { authenticate(session, node); return; }
                RuntimePrincipal principal = current.principal();
                long epoch = (long) session.getAttributes().get("connectionEpoch");
                if (!epochs.current(principal, epoch)) throw problem("CONNECTION_REPLACED");
                if (!Long.toString(epoch).equals(node.path("connectionEpoch").asText())) throw problem("EPOCH_MISMATCH");
                if ("connection.reauthorize".equals(node.path("type").asText()))
                { reauthorize(session, current, epoch, node); return; }
                if ("ping".equals(node.path("type").asText()))
                {
                    send(session, event("pong", principal, epoch, null, requestId,
                        Map.of("clientTime", node.path("data").path("clientTime").asText(), "serverTime", Instant.now().toString())));
                    return;
                }
                RuntimeAuthorization.Grant fresh = access.verify(current.id());
                if (fresh.principalId() != current.principalId() || !fresh.tokenId().equals(current.tokenId())) throw problem("TOKEN_REVOKED");
                principal = fresh.principal();
                session.getAttributes().put("runtimeGrant", fresh);
                switch (node.path("type").asText())
                {
                    case "speech.create" -> speech(session, fresh, epoch, node);
                    case "turn.stop" -> stop(session, fresh, epoch, node);
                    case "playback.report" -> playback(session, fresh, epoch, node);
                    default -> throw problem("CAPABILITY_NOT_ALLOWED");
                }
            }
            catch (RuntimeProblem error)
            {
                RuntimeAuthorization.Grant grant = (RuntimeAuthorization.Grant) session.getAttributes().get("runtimeGrant");
                if (grant == null) { session.close(new CloseStatus(4400, "authentication failed")); return; }
                long epoch = (long) session.getAttributes().get("connectionEpoch");
                send(session, event("request.error", grant.principal(), epoch, turnId, requestId,
                    Map.of("code", error.code(), "message", error.getMessage(), "retryable", false)));
                if ("CONNECTION_REPLACED".equals(error.code())) session.close(new CloseStatus(4009, "replaced"));
            }
            catch (Exception error) { session.close(new CloseStatus(4400, "invalid frame")); }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
        {
            RuntimeAuthorization.Grant grant = (RuntimeAuthorization.Grant) session.getAttributes().get("runtimeGrant");
            if (grant == null) return;
            RuntimePrincipal principal = grant.principal();
            long epoch = (long) session.getAttributes().get("connectionEpoch");
            try { runtime.disconnect(principal, epoch); }
            finally { events.unregister(principal.sessionId(), session); }
        }

        private void authenticate(WebSocketSession session, JsonNode node) throws IOException
        {
            Instant opened = (Instant) session.getAttributes().get("openedAt");
            if (!"ln-avatar.v1".equals(session.getAcceptedProtocol())) throw problem("PROTOCOL_ERROR");
            if (opened == null || opened.plusSeconds(5).isBefore(Instant.now())
                || !"connection.auth".equals(node.path("type").asText())) throw problem("TICKET_REQUIRED");
            RuntimeConnectionTicketService.ConsumedTicket consumed = tickets.consume(node.path("data").path("ticket").asText());
            if (!"CONNECT".equals(consumed.purpose()) || consumed.expectedEpoch() != null) throw problem("TICKET_INVALID");
            RuntimeAuthorization.Grant grant = consumed.grant();
            RuntimePrincipal principal = grant.principal();
            long epoch = store.openConnection(principal);
            epochs.advance(principal, epoch);
            runtime.replaceConnection(principal, epoch);
            session.getAttributes().put("runtimeGrant", grant);
            session.getAttributes().put("connectionEpoch", epoch);
            if (!events.register(principal, epoch, session)) { session.close(new CloseStatus(4009, "replaced")); return; }
            send(session, event("connection.ready", principal, epoch, null, node.path("requestId").asText(),
                Map.of("connectionEpoch", Long.toString(epoch), "effectiveScopes", principal.scopes(),
                    "capabilities", "CONSOLE_DEBUG".equals(grant.source())
                        ? List.of("speech.create", "turn.stop", "playback.report") : List.of("turn.stop"),
                    "effectiveLimits", limits.current(), "expiresAt", grant.expiresAt().toString())));
        }

        private void reauthorize(WebSocketSession session, RuntimeAuthorization.Grant current, long epoch, JsonNode node) throws IOException
        {
            RuntimeConnectionTicketService.ConsumedTicket consumed = tickets.consume(node.path("data").path("ticket").asText());
            RuntimeAuthorization.Grant next = consumed.grant();
            if (!"REAUTHORIZE".equals(consumed.purpose()) || consumed.expectedEpoch() == null
                || consumed.expectedEpoch() != epoch || next.principalId() != current.principalId()
                || next.principal().accountId() != current.principal().accountId()
                || next.principal().applicationId() != current.principal().applicationId()
                || next.principal().sessionId() != current.principal().sessionId()
                || next.principal().configVersionId() != current.principal().configVersionId()
                || !next.source().equals(current.source())) throw problem("TICKET_INVALID");
            session.getAttributes().put("runtimeGrant", next);
            send(session, event("connection.reauthorized", next.principal(), epoch, null, node.path("requestId").asText(),
                Map.of("expiresAt", next.expiresAt().toString(), "effectiveScopes", next.principal().scopes())));
        }

        private void speech(WebSocketSession session, RuntimeAuthorization.Grant grant, long epoch, JsonNode node) throws IOException
        {
            RuntimePrincipal principal = grant.principal();
            if ("BUSINESS_KEY".equals(grant.source())) throw problem("CAPABILITY_NOT_ALLOWED");
            principal.requireSpeakScope();
            String requestId = node.path("requestId").asText();
            SpeakOnlyRuntimeService.SpeechStarted started = runtime.start(principal, requestId, node.path("data").path("text").asText(), epoch);
            if ("BUSINESS_KEY".equals(grant.source())) business.successfulActivity(principal.sessionId());
            send(session, event("request.ack", principal, epoch, started.turnId(), requestId,
                Map.of("requestId", requestId, "turnId", started.turnId(), "status", "ACCEPTED")));
            send(session, event("turn.started", principal, epoch, started.turnId(), requestId, Map.of("mode", "SPEAK")));
            dispatch(principal, started.initialWork());
        }

        private void stop(WebSocketSession session, RuntimeAuthorization.Grant grant, long epoch, JsonNode node) throws IOException
        {
            RuntimePrincipal principal = grant.principal();
            principal.requireSpeakScope();
            String turnId = node.path("turnId").asText();
            String reason = node.path("data").path("reason").asText();
            if (!reason.matches("[\\x20-\\x7e]{1,100}") || !turnId.matches("[1-9][0-9]*"))
                throw problem("INVALID_ARGUMENT");
            SpeakOnlyRuntimeService.StopResult stopped = runtime.stop(principal, turnId);
            if ("BUSINESS_KEY".equals(grant.source())) business.successfulActivity(principal.sessionId());
            Map<String, Object> state = new java.util.LinkedHashMap<>(store.turnState(principal, stopped.turnId()));
            state.put("alreadyStopped", stopped.alreadyStopped());
            state.put("reason", reason);
            send(session, event("turn.stopped", principal, epoch, stopped.turnId(), node.path("requestId").asText(), state));
        }

        private void playback(WebSocketSession session, RuntimeAuthorization.Grant grant, long epoch, JsonNode node) throws IOException
        {
            RuntimePrincipal principal = grant.principal();
            principal.requireSpeakScope();
            String turnId = node.path("turnId").asText();
            String segmentId = node.path("data").path("segmentId").asText();
            PlaybackState state;
            try { state = PlaybackState.valueOf(node.path("data").path("state").asText()); }
            catch (IllegalArgumentException error) { throw problem("INVALID_ARGUMENT"); }
            SpeakOnlyRuntimeService.PlaybackUpdated updated = runtime.reportPlayback(principal, turnId, segmentId, state);
            if (updated.accepted())
            {
                if ("BUSINESS_KEY".equals(grant.source())) business.successfulActivity(principal.sessionId());
                dispatch(principal, updated.nextWork());
            }
            send(session, event("playback.ack", principal, epoch, turnId, node.path("requestId").asText(),
                Map.of("accepted", updated.accepted())));
        }

        private void dispatch(RuntimePrincipal principal, Iterable<TtsSynthesisWork> work)
        {
            TtsRuntimeAdapter adapter = adapters.requireAdapter(principal.voice());
            for (TtsSynthesisWork item : work) adapter.submit(item, runtime);
        }
        private Map<String, Object> event(String type, RuntimePrincipal principal, long epoch, String turnId,
            String requestId, Map<String, Object> data)
        {
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("v", 1); envelope.put("type", type); envelope.put("requestId", requestId);
            envelope.put("sessionId", Long.toString(principal.sessionId()));
            envelope.put("connectionEpoch", Long.toString(epoch)); envelope.put("turnId", turnId);
            envelope.put("occurredAt", Instant.now().toString()); envelope.put("data", data);
            return envelope;
        }
        private RuntimeProblem problem(String code)
        { return new RuntimeProblem(HttpStatus.BAD_REQUEST, code, code); }
        private void send(WebSocketSession session, Map<String, Object> envelope) throws IOException
        { events.sendDirect(session, envelope); }
    }
}
