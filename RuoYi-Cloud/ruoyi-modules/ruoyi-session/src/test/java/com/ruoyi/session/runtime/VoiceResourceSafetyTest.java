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
        TtsRuntimeAdapter fake=new TtsRuntimeAdapter() {
            public TtsProviderKind providerKind(){return TtsProviderKind.OFFICIAL;}
            public void submit(TtsSynthesisWork work,TtsCompletionSink sink){
                sink.beforeExternal(work);
                var input=new AudioReadyInput("11",1,"segment",0,"audio/wav",10,48,
                    new TemporaryAudioReference("media","LOCAL_TEMP","session-audio","fixture.wav",Instant.now().plusSeconds(60)));
                sink.onAudioReady(principal,input);sink.onAudioReady(principal,input);
            }
        };
        new TtsSubmissionService(access,epochs,store,system,new TtsRuntimeAdapterRegistry(java.util.List.of(fake)),runtime,lifecycle,
            new VoiceRuntimeProperties()).submit(grant,2,java.util.List.of(new TtsSynthesisWork("11",1,"segment",0,"Hello",principal)));
        verify(runtime,times(1)).onAudioReady(eq(principal),any());
        verify(system,never()).finishTts(anyLong(),anyString(),anyString());
    }
}
