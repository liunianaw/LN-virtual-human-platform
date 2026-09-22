package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
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
    private final PersistentRuntimeStore store;
    private final SpeakOnlyRuntimeService runtime;
    private final TtsRuntimeAdapterRegistry adapters;
    private final RuntimeEventPublisher events;
    private final ObjectMapper json;

    public RuntimeWebSocketConfiguration(RuntimeConnectionTicketService tickets, PersistentRuntimeStore store,
        SpeakOnlyRuntimeService runtime, TtsRuntimeAdapterRegistry adapters, RuntimeEventPublisher events, ObjectMapper json)
    {
        this.tickets = tickets; this.store = store; this.runtime = runtime; this.adapters = adapters; this.events = events; this.json = json;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry)
    {
        registry.addHandler(new RuntimeHandler(), "/api/v1/realtime").setAllowedOriginPatterns("*");
        // Retained while callers migrate; it shares the same first-frame ticket rules.
        registry.addHandler(new RuntimeHandler(), "/api/v1/runtime/ws").setAllowedOriginPatterns("*");
    }

    private final class RuntimeHandler extends TextWebSocketHandler
    {
        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException
        {
            try
            {
                JsonNode node = json.readTree(message.getPayload());
                RuntimePrincipal principal = (RuntimePrincipal) session.getAttributes().get("runtimePrincipal");
                if (principal == null) { authenticate(session, node); return; }
                long epoch = session.getAttributes().get("connectionEpoch") instanceof Long value ? value : -1;
                if (!store.currentConnection(principal, epoch)) throw new RuntimeProblem(org.springframework.http.HttpStatus.FORBIDDEN, "CONNECTION_REPLACED", "Connection was replaced.");
                String type = node.path("type").asText();
                if (!Long.toString(epoch).equals(node.path("connectionEpoch").asText()))
                    throw new RuntimeProblem(org.springframework.http.HttpStatus.CONFLICT, "EPOCH_MISMATCH", "Connection epoch does not match.");
                switch (type)
                {
                    case "speech.create" -> speech(session, principal, epoch, node);
                    case "turn.stop" -> stop(session, principal, epoch, node);
                    case "playback.report" -> playback(session, principal, epoch, node);
                    default -> throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "PROTOCOL_ERROR", "Unsupported runtime command.");
                }
            }
            catch (RuntimeProblem problem) { send(session, Map.of("v", 1, "type", "request.error", "data", Map.of("code", problem.code()))); }
            catch (Exception exception) { send(session, Map.of("v", 1, "type", "request.error", "data", Map.of("code", "PROTOCOL_ERROR"))); }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
        {
            Object principal = session.getAttributes().get("runtimePrincipal");
            if (principal instanceof RuntimePrincipal runtimePrincipal)
            {
                if (store.currentConnection(runtimePrincipal, session.getAttributes().get("connectionEpoch") instanceof Long value ? value : -1)) runtime.disconnect(runtimePrincipal);
                events.unregister(runtimePrincipal.sessionId(), session);
            }
        }

        private void authenticate(WebSocketSession session, JsonNode node) throws IOException
        {
            if (!"connection.auth".equals(node.path("type").asText())) throw new RuntimeProblem(org.springframework.http.HttpStatus.UNAUTHORIZED, "TICKET_REQUIRED", "First frame must authenticate a connection ticket.");
            String ticket = node.path("data").path("ticket").asText();
            RuntimePrincipal principal = tickets.consume(ticket);
            // A replacement connection cannot receive or revive the previous round's late audio.
            runtime.revokeSession(principal.sessionId());
            long epoch = store.openConnection(principal);
            session.getAttributes().put("runtimePrincipal", principal);
            session.getAttributes().put("connectionEpoch", epoch);
            events.register(principal, epoch, session);
            send(session, Map.of("v", 1, "type", "connection.ready", "sessionId", Long.toString(principal.sessionId()),
                "connectionEpoch", Long.toString(epoch), "occurredAt", Instant.now().toString(), "data", Map.of("capabilities", java.util.List.of("speech.create", "turn.stop", "playback.report"))));
        }

        private void speech(WebSocketSession session, RuntimePrincipal principal, long epoch, JsonNode node) throws IOException
        {
            String requestId = node.path("requestId").asText();
            String text = node.path("data").path("text").asText();
            SpeakOnlyRuntimeService.SpeechStarted started = runtime.start(principal, requestId, text);
            dispatch(principal, started.initialWork());
            send(session, event("request.ack", principal, epoch, started.turnId(), Map.of("requestId", requestId, "status", "ACCEPTED")));
            send(session, event("turn.started", principal, epoch, started.turnId(), Map.of("segmentCount", started.segmentCount())));
        }

        private void stop(WebSocketSession session, RuntimePrincipal principal, long epoch, JsonNode node) throws IOException
        {
            String turnId = node.path("turnId").asText();
            SpeakOnlyRuntimeService.StopResult stopped = runtime.stop(principal, turnId);
            send(session, event("turn.stopped", principal, epoch, stopped.turnId(), Map.of("alreadyStopped", stopped.alreadyStopped())));
        }

        private void playback(WebSocketSession session, RuntimePrincipal principal, long epoch, JsonNode node) throws IOException
        {
            String turnId = node.path("turnId").asText();
            String segmentId = node.path("data").path("segmentId").asText();
            PlaybackState state;
            try { state = PlaybackState.valueOf(node.path("data").path("state").asText()); }
            catch (IllegalArgumentException ex) { throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid playback state."); }
            SpeakOnlyRuntimeService.PlaybackUpdated updated = runtime.reportPlayback(principal, turnId, segmentId, state);
            if (updated.accepted()) dispatch(principal, updated.nextWork());
            send(session, event("playback.ack", principal, epoch, turnId, Map.of("accepted", updated.accepted())));
        }

        private void dispatch(RuntimePrincipal principal, Iterable<TtsSynthesisWork> work)
        {
            TtsRuntimeAdapter adapter = adapters.requireAdapter(principal.voice());
            for (TtsSynthesisWork item : work) adapter.submit(item, runtime);
        }

        private Map<String, Object> event(String type, RuntimePrincipal principal, long epoch, String turnId, Map<String, Object> data)
        { return Map.of("v", 1, "type", type, "sessionId", Long.toString(principal.sessionId()), "connectionEpoch", Long.toString(epoch), "turnId", turnId, "occurredAt", Instant.now().toString(), "data", data); }
        private void send(WebSocketSession session, Map<String, Object> message) throws IOException
        { synchronized (session) { if (session.isOpen()) session.sendMessage(new TextMessage(json.writeValueAsString(message))); } }
    }
}
