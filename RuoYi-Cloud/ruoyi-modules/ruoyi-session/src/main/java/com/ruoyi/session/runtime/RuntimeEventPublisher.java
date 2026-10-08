package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Delivers persisted runtime facts only to the currently authenticated connection for that session. */
@Component
public class RuntimeEventPublisher
{
    private final ObjectMapper json;
    private final RuntimeConnectionEpochs epochs;
    private final PersistentRuntimeStore store;
    private final Map<Long, Connection> connections = new ConcurrentHashMap<>();

    public RuntimeEventPublisher(ObjectMapper json, RuntimeConnectionEpochs epochs, PersistentRuntimeStore store)
    { this.json = json; this.epochs = epochs; this.store = store; }

    public boolean register(RuntimePrincipal principal, long epoch, WebSocketSession session)
    {
        if (!epochs.current(principal, epoch)) return false;
        Connection incoming = new Connection(principal, epoch, session);
        java.util.concurrent.atomic.AtomicReference<Connection> displaced = new java.util.concurrent.atomic.AtomicReference<>();
        Connection registered = connections.compute(principal.sessionId(), (ignored, prior) -> {
            if (prior != null && prior.epoch() >= epoch) return prior;
            displaced.set(prior);
            return incoming;
        });
        if (registered != incoming) return false;
        Connection prior = displaced.get();
        if (prior != null && prior.session().isOpen() && !prior.session().getId().equals(session.getId()))
        {
            try
            {
                synchronized (prior.session())
                {
                    if (prior.session().isOpen()) prior.session().sendMessage(new TextMessage(json.writeValueAsString(
                        controlEvent(prior, "connection.replaced", "REPLACED"))));
                    prior.session().close(new org.springframework.web.socket.CloseStatus(4009, "replaced"));
                }
            }
            catch (IOException ignored) { }
        }
        try
        {
            if (!epochs.current(principal, epoch))
            {
                connections.remove(principal.sessionId(), incoming);
                return false;
            }
        }
        catch (RuntimeException error)
        {
            connections.remove(principal.sessionId(), incoming);
            throw error;
        }
        return true;
    }

    public void revokeSession(long sessionId)
    {
        Connection connection = connections.remove(sessionId);
        if (connection == null) return;
        try
        {
            sendControl(connection, "connection.revoked", "REVOKED");
            connection.session().close(new org.springframework.web.socket.CloseStatus(4003, "revoked"));
        }
        catch (IOException ignored) { }
    }

    private void sendControl(Connection connection, String type, String reason) throws IOException
    {
        synchronized (connection.session())
        {
            if (!connection.session().isOpen()) return;
            connection.session().sendMessage(new TextMessage(json.writeValueAsString(
                controlEvent(connection, type, reason))));
        }
    }

    private Map<String, Object> controlEvent(Connection connection, String type, String reason)
    {
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("v", 1); envelope.put("type", type); envelope.put("requestId", "runtime");
        envelope.put("sessionId", Long.toString(connection.principal().sessionId()));
        envelope.put("connectionEpoch", Long.toString(connection.epoch()));
        envelope.put("turnId", null); envelope.put("seq", sequence(connection, null));
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("data", Map.of("reason", reason));
        return envelope;
    }

    public void unregister(long sessionId, WebSocketSession session)
    {
        connections.computeIfPresent(sessionId, (ignored, current) -> current.session().getId().equals(session.getId()) ? null : current);
    }

    private static String sequence(Connection connection, String turnId)
    {
        return Long.toString(connection.sequences().computeIfAbsent(turnId == null ? "connection" : turnId,
            ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet());
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${LN_RUNTIME_CONNECTION_SWEEP_MS:5000}")
    public void closeReplaced()
    {
        for (Connection connection : connections.values())
        {
            try
            {
                if (!store.activeSession(connection.principal()))
                {
                    sendControl(connection, "connection.revoked", "REVOKED");
                    connection.session().close(new org.springframework.web.socket.CloseStatus(4003, "revoked"));
                    unregister(connection.principal().sessionId(), connection.session());
                }
                else if (!epochs.current(connection.principal(), connection.epoch()))
                {
                    sendControl(connection, "connection.replaced", "REPLACED");
                    connection.session().close(new org.springframework.web.socket.CloseStatus(4009, "replaced"));
                    unregister(connection.principal().sessionId(), connection.session());
                }
                else
                {
                    Object lastFrame = connection.session().getAttributes().get("lastFrameAt");
                    if (lastFrame instanceof Instant last && last.plusSeconds(60).isBefore(Instant.now()))
                    {
                        connection.session().close(new org.springframework.web.socket.CloseStatus(4001, "heartbeat timeout"));
                        unregister(connection.principal().sessionId(), connection.session());
                    }
                }
            }
            catch (Exception ignored) { /* next sweep retries; new commands still fail closed */ }
        }
    }

    public void audioSegment(RuntimePrincipal principal, long turnEpoch, AudioSegmentEvent event)
    {
        Connection connection = connections.get(principal.sessionId());
        if (connection == null || connection.epoch() != turnEpoch) return;
        send(connection, Map.of("v", 1, "type", "audio.segment", "sessionId", Long.toString(principal.sessionId()),
            "connectionEpoch", Long.toString(connection.epoch()), "turnId", event.turnId(),
            "requestId", "runtime", "occurredAt", Instant.now().toString(),
            "data", Map.of("segmentId", event.segmentId(), "ordinal", event.ordinal(), "mediaId", event.mediaId(),
                "mimeType", event.mimeType(), "durationMs", event.durationMs(), "expiresAt", event.expiresAt().toString(),
                "degraded",event.degraded(),"actualVoiceDisplayName",event.actualVoiceDisplayName(),"reasonCode",event.reasonCode())));
    }

    public void audioFailed(RuntimePrincipal principal, long turnEpoch, String turnId, String segmentId, int ordinal, String code)
    {
        Connection connection = connections.get(principal.sessionId());
        if (connection == null || connection.epoch() != turnEpoch) return;
        send(connection, Map.of("v", 1, "type", "audio.failed", "sessionId", Long.toString(principal.sessionId()),
            "connectionEpoch", Long.toString(connection.epoch()), "turnId", turnId, "requestId", "runtime",
            "occurredAt", Instant.now().toString(), "data", Map.of("segmentId", segmentId, "ordinal", ordinal,
                "error", Map.of("code", code, "message", "Audio synthesis failed.", "retryable", false))));
    }

    public void completed(RuntimePrincipal principal, long turnEpoch, String turnId, Map<String, Object> state)
    { terminal(principal, turnEpoch, turnId, state, "turn.completed"); }

    public void failed(RuntimePrincipal principal, long turnEpoch, String turnId, Map<String, Object> state)
    { terminal(principal, turnEpoch, turnId, state, "turn.failed"); }

    private void terminal(RuntimePrincipal principal, long turnEpoch, String turnId, Map<String, Object> state, String type)
    {
        Connection connection = connections.get(principal.sessionId());
        if (connection == null || connection.epoch() != turnEpoch) return;
        send(connection, Map.of("v", 1, "type", type, "sessionId", Long.toString(principal.sessionId()),
            "connectionEpoch", Long.toString(connection.epoch()), "turnId", turnId, "requestId", "runtime",
            "occurredAt", Instant.now().toString(), "data", state));
    }

    public void sendDirect(WebSocketSession session, Map<String, Object> message) throws IOException
    {
        long sessionId = messageSessionId(message);
        Connection connection = connections.get(sessionId);
        if (connection == null || !connection.session().getId().equals(session.getId())) return;
        synchronized (session)
        {
            if (!session.isOpen() || !epochs.current(connection.principal(), connection.epoch())) return;
            Map<String, Object> envelope = new java.util.LinkedHashMap<>(message);
            envelope.put("seq", sequence(connection, (String) message.get("turnId")));
            session.sendMessage(new TextMessage(json.writeValueAsString(envelope)));
        }
    }

    private void send(Connection connection, Map<String, Object> message)
    {
        try
        {
            if (!epochs.current(connection.principal(), connection.epoch())) return;
            sendDirect(connection.session(), message);
        }
        catch (IOException ignored) { connections.remove(messageSessionId(message), connection); }
    }
    private static long messageSessionId(Map<String, Object> message) { return Long.parseLong((String) message.get("sessionId")); }
    private record Connection(RuntimePrincipal principal, long epoch, WebSocketSession session,
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> sequences)
    {
        private Connection(RuntimePrincipal principal, long epoch, WebSocketSession session)
        { this(principal, epoch, session, new java.util.concurrent.ConcurrentHashMap<>()); }
    }
}
