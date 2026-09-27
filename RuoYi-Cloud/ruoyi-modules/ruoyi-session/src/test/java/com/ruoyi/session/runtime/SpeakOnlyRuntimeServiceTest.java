package com.ruoyi.session.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpeakOnlyRuntimeServiceTest
{
    @Test
    void chatAudioUsesCompleteSentencesAndKeepsTheTail()
    {
        assertEquals(java.util.List.of("你好。", "下一句！", "尾段"),
            SpeakOnlyRuntimeService.splitSentences("你好。下一句！尾段", 100));
        assertEquals(java.util.List.of("abcd", "ef"),
            SpeakOnlyRuntimeService.splitSentences("abcdef", 4));
    }
    @Test
    void oldConnectionCloseCannotStopNewerConnectionsTurn()
    {
        PersistentRuntimeStore store = mock(PersistentRuntimeStore.class);
        RuntimePrincipal principal = new RuntimePrincipal(1, 2, 3, 4,
            Set.of("session:read", "speak:write"),
            new VoiceRuntimeBinding(5, TtsProviderKind.OFFICIAL, "voice", null, 6L, 1L));
        when(store.createSpeakTurn(eq(principal), eq("request"), anyList(), eq(2L))).thenReturn(10L);
        SpeakOnlyRuntimeService runtime = new SpeakOnlyRuntimeService(new VoiceRuntimeProperties(),
            mock(TemporaryAudioCleanupQueue.class), store, mock(RuntimeEventPublisher.class));
        runtime.start(principal, "request", "Hello", 2);

        runtime.disconnect(principal, 1);
        verify(store, never()).stop(anyLong(), anyString());

        runtime.disconnect(principal, 2);
        verify(store).stop(10L, "DISCONNECTED");
    }
    @Test
    void delayedOlderHandshakeCannotStopNewerConnectionsTurn()
    {
        PersistentRuntimeStore store = mock(PersistentRuntimeStore.class);
        RuntimePrincipal principal = new RuntimePrincipal(1, 2, 3, 4,
            Set.of("session:read", "speak:write"),
            new VoiceRuntimeBinding(5, TtsProviderKind.OFFICIAL, "voice", null, 6L, 1L));
        when(store.createSpeakTurn(eq(principal), eq("request"), anyList(), eq(2L))).thenReturn(10L);
        SpeakOnlyRuntimeService runtime = new SpeakOnlyRuntimeService(new VoiceRuntimeProperties(),
            mock(TemporaryAudioCleanupQueue.class), store, mock(RuntimeEventPublisher.class));
        runtime.start(principal, "request", "Hello", 2);

        runtime.replaceConnection(principal, 1);
        verify(store, never()).stop(anyLong(), anyString());

        runtime.replaceConnection(principal, 3);
        verify(store).stop(10L, "REPLACED");
    }
    @Test
    void lateAudioFromReplacedConnectionIsQueuedForCleanup()
    {
        PersistentRuntimeStore store = mock(PersistentRuntimeStore.class);
        TemporaryAudioCleanupQueue cleanup = mock(TemporaryAudioCleanupQueue.class);
        RuntimePrincipal principal = new RuntimePrincipal(1, 2, 3, 4,
            Set.of("session:read", "speak:write"),
            new VoiceRuntimeBinding(5, TtsProviderKind.OFFICIAL, "voice", null, 6L, 1L));
        when(store.createSpeakTurn(eq(principal), eq("request"), anyList(), eq(2L))).thenReturn(10L);
        SpeakOnlyRuntimeService runtime = new SpeakOnlyRuntimeService(new VoiceRuntimeProperties(),
            cleanup, store, mock(RuntimeEventPublisher.class));
        TtsSynthesisWork work = runtime.start(principal, "request", "Hello", 2).initialWork().get(0);
        TemporaryAudioReference audio = new TemporaryAudioReference("media", "LOCAL", "bucket", "key",
            Instant.now().plusSeconds(60));

        AudioReadyResult result = runtime.onAudioReady(principal, new AudioReadyInput(work.turnId(),
            work.generation(), work.segmentId(), work.ordinal(), "audio/wav", 100, 128, audio));

        assertFalse(result.accepted());
        verify(cleanup).schedule(audio);
        verify(store).stop(10L, "REPLACED");
        verify(store, never()).markAudioReady(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
            eq(principal), eq(audio), anyLong(), anyLong());
    }}
