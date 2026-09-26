package com.ruoyi.session.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class RuntimeEventPublisherTest
{
    @Test
    void assignsOrderedTurnEventsAndNeverSendsOldTurnEventsToNewSocket() throws Exception
    {
        RuntimeConnectionEpochs epochs = mock(RuntimeConnectionEpochs.class);
        when(epochs.current(any(), anyLong())).thenReturn(true);
        RuntimeEventPublisher publisher = new RuntimeEventPublisher(new ObjectMapper(), epochs, mock(PersistentRuntimeStore.class));
        RuntimePrincipal principal = new RuntimePrincipal(1, 2, 3, 4,
            java.util.Set.of("session:read", "avatar:read", "speak:write"),
            new VoiceRuntimeBinding(5, TtsProviderKind.OFFICIAL, "voice", null, 6L, 1L));
        WebSocketSession old = socket("old");
        publisher.register(principal, 1, old);
        Map<String, Object> ack = new HashMap<>(Map.of("v", 1, "type", "request.ack", "sessionId", "3",
            "connectionEpoch", "1", "turnId", "10", "requestId", "speak-1", "data", Map.of()));
        publisher.sendDirect(old, ack);
        publisher.audioSegment(principal, 1, new AudioSegmentEvent("10", "11", 0, "12", "audio/wav", 100,
            Instant.now().plusSeconds(60)));
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(old, times(2)).sendMessage(sent.capture());
        ObjectMapper json = new ObjectMapper();
        assertEquals("1", json.readTree(sent.getAllValues().get(0).getPayload()).path("seq").asText());
        assertEquals("2", json.readTree(sent.getAllValues().get(1).getPayload()).path("seq").asText());

        WebSocketSession newer = socket("new");
        publisher.register(principal, 2, newer);
        publisher.sendDirect(old, ack);
        verify(old, times(3)).sendMessage(any(TextMessage.class)); // replacement notice, never late ack
        publisher.audioSegment(principal, 1, new AudioSegmentEvent("10", "11", 0, "12", "audio/wav", 100,
            Instant.now().plusSeconds(60)));
        publisher.audioFailed(principal, 1, "10", "11", 0, "TTS_FAILED");
        publisher.completed(principal, 1, "10", Map.of("status", "COMPLETED"));
        verify(newer, times(0)).sendMessage(any(TextMessage.class));
        publisher.audioSegment(principal, 2, new AudioSegmentEvent("20", "21", 0, "22", "audio/wav", 100,
            Instant.now().plusSeconds(60)));
        verify(newer, times(1)).sendMessage(any(TextMessage.class));
        when(epochs.current(any(), anyLong())).thenReturn(false);
        publisher.sendDirect(newer, ack);
        verify(newer, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void replacementWinsOverIdleTimeoutSoOldPageDoesNotReconnect() throws Exception
    {
        RuntimeConnectionEpochs epochs = mock(RuntimeConnectionEpochs.class);
        when(epochs.current(any(), anyLong())).thenReturn(true);
        PersistentRuntimeStore store = mock(PersistentRuntimeStore.class);
        when(store.activeSession(any())).thenReturn(true);
        RuntimeEventPublisher publisher = new RuntimeEventPublisher(new ObjectMapper(), epochs, store);
        RuntimePrincipal principal = new RuntimePrincipal(1, 2, 3, 4,
            java.util.Set.of("session:read"),
            new VoiceRuntimeBinding(5, TtsProviderKind.OFFICIAL, "voice", null, 6L, 1L));
        WebSocketSession old = socket("old");
        old.getAttributes().put("lastFrameAt", Instant.now().minusSeconds(61));
        publisher.register(principal, 1, old);
        when(epochs.current(any(), anyLong())).thenReturn(false);

        publisher.closeReplaced();

        verify(old).close(argThat(status -> status.getCode() == 4009));
    }
    @Test
    void lateOlderRegistrationCannotReplaceNewerSocket() throws Exception
    {
        RuntimeConnectionEpochs epochs = mock(RuntimeConnectionEpochs.class);
        when(epochs.current(any(), anyLong())).thenReturn(true);
        RuntimeEventPublisher publisher = new RuntimeEventPublisher(new ObjectMapper(), epochs, mock(PersistentRuntimeStore.class));
        RuntimePrincipal principal = new RuntimePrincipal(1, 2, 3, 4,
            java.util.Set.of("session:read"),
            new VoiceRuntimeBinding(5, TtsProviderKind.OFFICIAL, "voice", null, 6L, 1L));
        WebSocketSession newer = socket("newer");
        WebSocketSession older = socket("older");

        assertTrue(publisher.register(principal, 2, newer));
        assertFalse(publisher.register(principal, 1, older));
        verify(newer, never()).close(any());
        publisher.sendDirect(newer, Map.of("v", 1, "type", "pong", "sessionId", "3",
            "connectionEpoch", "2", "turnId", "", "requestId", "ping", "data", Map.of()));
        verify(newer).sendMessage(any(TextMessage.class));
        verify(older, never()).sendMessage(any(TextMessage.class));
    }
    private static WebSocketSession socket(String id)
    {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        return session;
    }
}
