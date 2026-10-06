package com.ruoyi.session.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.session.business.BusinessSystemClient;
import com.ruoyi.session.runtime.mapper.TtsLifecycleMapper;

class VoiceResourceSafetyTest
{
    @TempDir Path directory;

    @Test void capacityRejectsBeforeExecutionAndQueueDeadlineExpires() throws Exception
    {
        var properties=new VoiceRuntimeProperties();
        properties.getOfficial().setConcurrency(1); properties.getOfficial().setQueueCapacity(1);
        properties.getOfficial().setQueueTimeout(Duration.ofMillis(5));
        var pool=new VoiceExecutionPool(properties);
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);
        try {
            var active=pool.submit(()->{started.countDown();release.await(2,TimeUnit.SECONDS);return true;});
            assertTrue(started.await(1,TimeUnit.SECONDS));
            var queued=pool.submit(()->fail("Expired work must not execute"));
            assertEquals("VOICE_QUEUE_FULL",assertThrows(RuntimeProblem.class,()->pool.submit(()->true)).code());
            Thread.sleep(15); release.countDown(); active.get(1,TimeUnit.SECONDS);
            assertThrows(java.util.concurrent.ExecutionException.class,()->queued.get(1,TimeUnit.SECONDS));
        } finally { release.countDown();pool.close(); }
    }

    @Test void failedRegistrationAndOrphansAreCleanedAndBadAudioRejected() throws Exception
    {
        var properties=new VoiceRuntimeProperties(); properties.setTemporaryAudioDirectory(directory.toString());
        var storage=new TemporaryWavStorage(properties);
        var principal=new RuntimePrincipal(1,2,3,4,Set.of("speak:write"),new VoiceRuntimeBinding(5,TtsProviderKind.OFFICIAL,"voice",6L,1L));
        var work=new TtsSynthesisWork("11",1,"segment",0,"Hello",principal);
        var sink=mock(TtsCompletionSink.class);
        when(sink.onAudioReady(any(),any())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThrows(IllegalStateException.class,()->new TtsAdapterSupport(storage).complete(work,sink,wav()));
        try(var files=Files.list(directory)) { assertEquals(0,files.count()); }
        byte[] invalid=wav(); invalid[4]=0;
        assertThrows(IllegalArgumentException.class,()->storage.store(invalid));
        var stored=storage.store(wav());
        Files.setLastModifiedTime(directory.resolve(stored.reference().objectKey()),FileTime.from(Instant.now().minusSeconds(1800)));
        Files.writeString(directory.resolve("unrelated.wav"),"preserve");
        storage.cleanupExpiredFiles();
        assertFalse(Files.exists(directory.resolve(stored.reference().objectKey())));
        assertTrue(Files.exists(directory.resolve("unrelated.wav")));
        properties.setMaxTemporaryAudioBytes(20);
        assertThrows(IllegalStateException.class,()->storage.store(wav()));
    }

    @Test void failedAudioRegistrationEndsTheTurnAndRemovesTheUnregisteredFile() throws Exception
    {
        var properties=new VoiceRuntimeProperties();properties.setTemporaryAudioDirectory(directory.toString());
        var storage=new TemporaryWavStorage(properties);var store=mock(PersistentRuntimeStore.class);
        var cleanup=mock(TemporaryAudioCleanupQueue.class);var events=mock(RuntimeEventPublisher.class);
        var principal=new RuntimePrincipal(1,2,3,4,Set.of("speak:write"),new VoiceRuntimeBinding(5,TtsProviderKind.OFFICIAL,"voice",6L,1L));
        when(store.createSpeakTurn(eq(principal),eq("request"),anyList(),eq(2L))).thenReturn(new PersistentRuntimeStore.SpeakTurnCreated(11L, true));
        when(store.currentConnection(principal,2L)).thenReturn(true);
        when(store.markAudioReady(eq(11L),eq(0),eq(principal),any(),anyLong(),anyLong()))
            .thenThrow(new IllegalStateException("DB unavailable"));
        doThrow(new IllegalStateException("DB still unavailable")).when(store).markAudioFailed(11L,0,"OFFICIAL_TTS_FAILED");
        when(store.turnState(principal,"11")).thenReturn(java.util.Map.of("status","FAILED"));
        var runtime=new SpeakOnlyRuntimeService(properties,cleanup,store,events);
        var work=runtime.start(principal,"request","Hello",2).initialWork().get(0);
        var support=new TtsAdapterSupport(storage);

        assertThrows(IllegalStateException.class,()->support.complete(work,runtime,wav()));
        support.fail(work,runtime,"OFFICIAL_TTS_FAILED");

        assertFalse(runtime.hasTurn("11"));
        verify(store).markAudioFailed(11L,0,"OFFICIAL_TTS_FAILED");
        verify(events).audioFailed(principal,2L,"11",work.segmentId(),0,"OFFICIAL_TTS_FAILED");
        verify(events).failed(eq(principal),eq(2L),eq("11"),any());
        try(var files=Files.list(directory)) { assertEquals(0,files.count()); }
    }

    @Test void registeredAudioSurvivesImmediateCleanupWhenNotificationFails() throws Exception
    {
        var properties=new VoiceRuntimeProperties();properties.setTemporaryAudioDirectory(directory.toString());
        var storage=new TemporaryWavStorage(properties);var store=mock(PersistentRuntimeStore.class);
        var cleanup=mock(TemporaryAudioCleanupQueue.class);var events=mock(RuntimeEventPublisher.class);
        var principal=new RuntimePrincipal(1,2,3,4,Set.of("speak:write"),new VoiceRuntimeBinding(5,TtsProviderKind.OFFICIAL,"voice",6L,1L));
        when(store.createSpeakTurn(eq(principal),eq("request"),anyList(),eq(2L))).thenReturn(new PersistentRuntimeStore.SpeakTurnCreated(11L, true));
        when(store.currentConnection(principal,2L)).thenReturn(true);
        when(store.markAudioReady(eq(11L),eq(0),eq(principal),any(),anyLong(),anyLong())).thenReturn(true);
        when(store.turnState(principal,"11")).thenReturn(java.util.Map.of("status","FAILED"));
        doThrow(new IllegalStateException("socket unavailable")).when(events).audioSegment(eq(principal),eq(2L),any());
        var runtime=new SpeakOnlyRuntimeService(properties,cleanup,store,events);
        var work=runtime.start(principal,"request","Hello",2).initialWork().get(0);

        new TtsAdapterSupport(storage).complete(work,runtime,wav());

        assertFalse(runtime.hasTurn("11"));
        verify(store).fail(11L);
        verify(cleanup).schedule(any());
        try(var files=Files.list(directory)) { assertEquals(1,files.count()); }
    }

    @Test void finalizationRetriesOnlyBillingAfterRemoteFailure()
    {
        var mapper=mock(TtsLifecycleMapper.class); var system=mock(BusinessSystemClient.class);
        var submissions=mock(TtsSubmissionService.class);when(submissions.owner()).thenReturn("boot");
        var item=new TtsLifecycleMapper.Finalization(21,7,8,11,0,10,51L,true,"SETTLE",0);
        when(mapper.pending(8)).thenReturn(java.util.List.of(item));
        doThrow(new IllegalStateException("unavailable")).doNothing().when(system).finishTts(7,"11:0","SETTLE");
        var worker=new TtsFinalizationWorker(mapper,system,submissions,mock(PersistentRuntimeStore.class));
        worker.sweep();verify(mapper).retry(21,"SETTLE","TTS_FINALIZATION_UNAVAILABLE");
        worker.sweep();verify(mapper).done(21,"SETTLE");
        verify(system,never()).reserveTts(anyLong(),anyLong(),anyString(),anyLong());
    }

    @Test void commonArbiterKeepsSuccessfulSettlementWhenPlaybackRegistrationFails() throws Exception
    {
        var properties=new VoiceRuntimeProperties();properties.setTemporaryAudioDirectory(directory.toString());
        var storage=new TemporaryWavStorage(properties);var mapper=mock(com.ruoyi.session.runtime.mapper.VoiceTaskMapper.class);
        var store=mock(VoiceAttemptStore.class);when(store.mapper()).thenReturn(mapper);
        var binding=new com.ruoyi.common.voice.VoiceBinding("5","DASHSCOPE_QWEN_TTS","6",1,"qwen3-tts-flash-realtime","qwen3-tts-flash-realtime","qwen-bridge-v1","Cherry","zh-CN",java.util.Map.of(),null,null,null,false,"1","wss://dashscope.aliyuncs.com/api-ws/v1/realtime");
        var principal=new RuntimePrincipal(7,8,3,4,Set.of("speak:write"),new VoiceRuntimeBinding(5,TtsProviderKind.OFFICIAL,"Cherry",6L,1L,binding));
        var grant=new RuntimeAuthorization.Grant(principal,10,"token","BUSINESS_KEY",1,Instant.now().plusSeconds(60));
        var work=new TtsSynthesisWork("11",1,"segment",0,"Hello",principal);
        var task=new com.ruoyi.session.runtime.mapper.VoiceTaskMapper.Task(20,7,"BUSINESS","11:0","hash",5,"{}","1","RUNNING",1,null,3L,11L,21L,2L,1L,10L,"owner",null,Instant.now().plusSeconds(60),"hash",5,null,null);
        var attempt=new com.ruoyi.session.runtime.mapper.VoiceTaskMapper.Attempt(30,20,1,"DASHSCOPE_QWEN_TTS",6,5,binding.modelRevision(),binding.capabilityVersion(),"hash","token","DISPATCHING","worker","boot",1,task.deadlineAt(),null,null,null,null,"UNKNOWN");
        when(store.create(anyLong(),anyString(),anyString(),anyString(),any(),any(),any(),any(),any())).thenReturn(task);
        when(store.createAttempt(20,"Hello",false)).thenReturn(new VoiceAttemptStore.Dispatch(task,attempt,"permit",binding));
        when(mapper.task(20)).thenReturn(task);when(mapper.attempt(30)).thenReturn(attempt);when(store.binding(task,1)).thenReturn(binding);
        when(store.success(eq(30L),any(),any())).thenReturn(true);
        var client=mock(VoiceExecutionClient.class);when(client.audio(30,(int)properties.getMaxAudioBytes())).thenReturn(wav());
        var sink=mock(TtsCompletionSink.class);when(sink.onAudioReady(any(),any())).thenThrow(new IllegalStateException("Registration unavailable"));
        var voices=new VoiceOrchestrationServiceImpl(store,client,mock(TtsRuntimeAdapterRegistry.class),mock(RuntimeAuthorization.class),storage,properties,mock(VoiceExecutionPool.class),new com.fasterxml.jackson.databind.ObjectMapper());
        voices.submit(grant,2,work,sink);
        var metadata=new com.ruoyi.common.voice.VoiceProtocol.Audio("audio/wav","PCM_S16LE",24000,1,16,48,1,com.ruoyi.common.voice.VoiceProtocol.hash(wav()));
        var event=new com.ruoyi.common.voice.VoiceProtocol.Event("completed","30",1,"hash","worker","boot","SUCCEEDED",null,"DEFINITIVE",metadata,null,"UNKNOWN",binding.modelRevision());
        voices.event(30,event);
        when(mapper.eventHash(30,"completed")).thenReturn(com.ruoyi.common.voice.VoiceProtocol.hash("event"));when(store.encode(event)).thenReturn("event");
        voices.event(30,event);
        verify(store,times(1)).success(eq(30L),eq(event),any());
        verify(store,never()).failure(anyLong(),any(),anyBoolean(),any());
        verify(sink,times(1)).onAudioReady(eq(principal),any());
        verify(sink).onAudioFailed(principal,work,"VOICE_AUDIO_DELIVERY_FAILED");
        byte[] malformed=wav();malformed[4]=0;when(client.audio(30,(int)properties.getMaxAudioBytes())).thenReturn(malformed);
        when(store.fullBinding(task)).thenReturn(binding);
        var invalidEvent=new com.ruoyi.common.voice.VoiceProtocol.Event("invalid","30",1,"hash","worker","boot","SUCCEEDED",null,"DEFINITIVE",metadata,null,"UNKNOWN",binding.modelRevision());
        voices.event(30,invalidEvent);
        verify(store).failure(eq(30L),argThat(e->"VOICE_AUDIO_INVALID".equals(e.errorCode())),eq(false),eq(invalidEvent));
        try(var files=Files.list(directory)) { assertEquals(0,files.count()); }
    }

    private static byte[] wav()
    {
        var buffer=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16)
            .putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16)
            .put("data".getBytes()).putInt(4).putInt(0);
        return buffer.array();
    }

    @Test void deterministicProviderCannotDeliverDuplicateCompletion()
    {
        var principal=new RuntimePrincipal(7,8,3,4,Set.of("speak:write"),new VoiceRuntimeBinding(5,TtsProviderKind.OFFICIAL,"voice",6L,1L));
        var grant=new RuntimeAuthorization.Grant(principal,10,"token","BUSINESS_KEY",1,Instant.now().plusSeconds(60));
        var access=mock(RuntimeAuthorization.class);when(access.verify(10)).thenReturn(grant);
        var epochs=mock(RuntimeConnectionEpochs.class);when(epochs.current(principal,2)).thenReturn(true);
        var store=mock(PersistentRuntimeStore.class);var system=mock(BusinessSystemClient.class);
        when(system.reserveTts(7,8,"11:0",5)).thenReturn(51L);
        var lifecycle=mock(TtsLifecycleMapper.class);
        when(lifecycle.prepare(eq(7L),eq(3L),eq(11L),eq(0),eq("segment"),eq(2L),anyString(),any())).thenReturn(1);
        when(lifecycle.dispatch(eq(11L),eq(0),anyString(),eq(2L))).thenReturn(1);
        when(lifecycle.finish(eq(11L),eq(0),anyString(),eq(true))).thenReturn(1,0);
        var runtime=mock(SpeakOnlyRuntimeService.class);when(runtime.onAudioReady(any(),any())).thenReturn(AudioReadyResult.ignored());
        var voices=mock(IVoiceOrchestrationService.class);
        doAnswer(call->{ TtsCompletionSink sink=call.getArgument(3); sink.onAudioReady(principal,new AudioReadyInput("11",1,"segment",0,"audio/wav",10,48,
            new TemporaryAudioReference("media","LOCAL_TEMP","session-audio","fixture.wav",Instant.now().plusSeconds(60))));return null;
        }).when(voices).submit(any(),eq(2L),any(),any());
        new TtsSubmissionService(access,epochs,store,system,voices,runtime,lifecycle,new VoiceRuntimeProperties())
            .submit(grant,2,java.util.List.of(new TtsSynthesisWork("11",1,"segment",0,"Hello",principal)));
        verify(voices,times(1)).submit(any(),eq(2L),any(),any());
        verify(runtime,times(1)).onAudioReady(eq(principal),any());
        verify(system,never()).finishTts(anyLong(),anyString(),anyString());
    }
}
