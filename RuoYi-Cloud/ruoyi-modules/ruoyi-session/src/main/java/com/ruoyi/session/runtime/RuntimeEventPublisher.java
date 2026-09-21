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
    private final Map<Long, Connection> connections = new ConcurrentHashMap<>();

    public RuntimeEventPublisher(ObjectMapper json) { this.json = json; }

    public void register(RuntimePrincipal principal, long epoch, WebSocketSession session)
    {
        Connection prior = connections.put(principal.sessionId(), new Connection(epoch, session));
        if (prior != null && prior.session().isOpen() && !prior.session().getId().equals(session.getId()))
        {
            try { prior.session().close(new org.springframework.web.socket.CloseStatus(4009, "replaced")); }
            catch (IOException ignored) { }
        }
    }

    public void unregister(long sessionId, WebSocketSession session)
    {
        connections.computeIfPresent(sessionId, (ignored, current) -> current.session().getId().equals(session.getId()) ? null : current);
    }

    public void audioSegment(RuntimePrincipal principal, AudioSegmentEvent event)
    {
        Connection connection = connections.get(principal.sessionId());
        if (connection == null) return;
        send(connection, Map.of("v", 1, "type", "audio.segment", "sessionId", Long.toString(principal.sessionId()),
            "connectionEpoch", Long.toString(connection.epoch()), "turnId", event.turnId(), "occurredAt", Instant.now().toString(),
            "data", Map.of("segmentId", event.segmentId(), "ordinal", event.ordinal(), "mediaId", event.mediaId(),
                "mimeType", event.mimeType(), "durationMs", event.durationMs(), "expiresAt", event.expiresAt().toString())));
    }

    public void failed(RuntimePrincipal principal, String turnId, String code)
    {
        Connection connection = connections.get(principal.sessionId());
        if (connection != null) send(connection, Map.of("v", 1, "type", "turn.failed", "sessionId", Long.toString(principal.sessionId()),
            "connectionEpoch", Long.toString(connection.epoch()), "turnId", turnId, "data", Map.of("code", code)));
    }

    private void send(Connection connection, Map<String, Object> message)
    {
        try
        {
            synchronized (connection.session())
            {
                if (connection.session().isOpen()) connection.session().sendMessage(new TextMessage(json.writeValueAsString(message)));
            }
        }
        catch (IOException ignored) { connections.remove(messageSessionId(message), connection); }
    }
    private static long messageSessionId(Map<String, Object> message) { return Long.parseLong((String) message.get("sessionId")); }
    private record Connection(long epoch, WebSocketSession session) { }
}
