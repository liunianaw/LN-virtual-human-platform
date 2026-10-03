package com.ruoyi.session.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.voice.VoiceBinding;
import com.ruoyi.session.business.BusinessSystemClient;
import com.ruoyi.session.runtime.mapper.TtsLifecycleMapper;
import com.ruoyi.session.runtime.mapper.VoiceTaskMapper;
import com.ruoyi.session.runtime.mapper.VoiceTaskMapper.Task;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class VoiceFailureBoundaryTest
{
    private final VoiceBinding binding=new VoiceBinding("5","TEST_TONE","6",1,"test-tone","1","test-tone-v1",
        "tone","zh-CN",Map.of(),null,null,null,false,"1","http://127.0.0.1:8003");
    private final RuntimePrincipal principal=new RuntimePrincipal(7,8,3,4,Set.of("speak:write"),
        new VoiceRuntimeBinding(5,TtsProviderKind.OFFICIAL,"tone",6L,1L,binding));
    private final RuntimeAuthorization.Grant grant=new RuntimeAuthorization.Grant(principal,10,"token","BUSINESS_KEY",1,Instant.now().plusSeconds(60));

    @Test void submissionDatabaseFailuresAlwaysCloseTheRealRuntimeTurn()
    {
        for(String phase:List.of("prepare","reserve","reserved","begin","submit","callback")) {
            var store=mock(PersistentRuntimeStore.class); var lifecycle=mock(TtsLifecycleMapper.class);
            var system=mock(BusinessSystemClient.class); var voices=mock(IVoiceOrchestrationService.class);
            var access=mock(RuntimeAuthorization.class); when(access.verify(10)).thenReturn(grant);
            var epochs=mock(RuntimeConnectionEpochs.class); when(epochs.current(principal,2)).thenReturn(true);
            var events=mock(RuntimeEventPublisher.class);
            when(store.createSpeakTurn(eq(principal),anyString(),anyList(),eq(2L))).thenReturn(11L);
            var runtime=new SpeakOnlyRuntimeService(new VoiceRuntimeProperties(),mock(TemporaryAudioCleanupQueue.class),store,events);
            var work=runtime.start(principal,phase,"Hello",2).initialWork().get(0);
            var failure=new IllegalStateException("Injected "+phase+" failure");
            when(lifecycle.prepare(anyLong(),anyLong(),anyLong(),anyInt(),anyString(),anyLong(),anyString(),any())).thenReturn(1);
            switch(phase) {
                case "prepare" -> when(lifecycle.prepare(anyLong(),anyLong(),anyLong(),anyInt(),anyString(),anyLong(),anyString(),any())).thenThrow(failure);
                case "reserve" -> when(system.reserveTts(anyLong(),anyLong(),anyString(),anyLong()))
                    .thenThrow(new RuntimeProblem(org.springframework.http.HttpStatus.FORBIDDEN,"BUSINESS_AUTH_REJECTED","Rejected"));
                case "reserved" -> doThrow(failure).when(lifecycle).reserved(anyLong(),anyInt(),anyLong());
                case "begin" -> doThrow(failure).when(store).beginTts(any(),any(),anyLong(),any());
                case "submit" -> doThrow(failure).when(voices).submit(any(),anyLong(),any(),any());
                case "callback" -> doAnswer(call->{
                    TtsCompletionSink sink=call.getArgument(3);
                    sink.onAudioFailed(principal,work,"VOICE_PROVIDER_NOT_READY"); return null;
                }).when(voices).submit(any(),anyLong(),any(),any());
            }
            if("reserve".equals(phase)) doThrow(failure).when(lifecycle).reserved(anyLong(),anyInt(),eq(0L));
            when(lifecycle.finish(anyLong(),anyInt(),anyString(),eq(false))).thenThrow(new IllegalStateException("Finalization DB unavailable"));
            doThrow(new IllegalStateException("Cancellation DB unavailable")).when(store).cancelNotSubmitted(principal,work);
            doThrow(new IllegalStateException("Failure DB unavailable")).when(store).markAudioFailed(eq(11L),eq(0),anyString());
            doThrow(new IllegalStateException("Turn DB unavailable")).when(store).fail(11L);
            assertDoesNotThrow(()->new TtsSubmissionService(access,epochs,store,system,voices,runtime,lifecycle,new VoiceRuntimeProperties())
                .submit(grant,2,List.of(work)),phase);
            assertFalse(runtime.hasTurn("11"),phase);
            String code="callback".equals(phase)?"VOICE_PROVIDER_NOT_READY":"reserve".equals(phase)?"BUSINESS_AUTH_REJECTED":"TTS_SUBMIT_FAILED";
            verify(events).audioFailed(principal,2,"11",work.segmentId(),0,code);
            verify(system,never()).finishTts(anyLong(),anyString(),anyString());
        }
    }

    @Test void registeredStartFailuresAndPollingExceptionsReleaseOnlyTheirOwnSlot() throws Exception
    {
        var store=mock(VoiceAttemptStore.class);var mapper=mock(VoiceTaskMapper.class);when(store.mapper()).thenReturn(mapper);
        var client=mock(VoiceExecutionClient.class);when(client.binding(5)).thenReturn(binding);
        var voices=voices(store,client);
        var task=task(20);
        when(store.create(anyLong(),anyString(),anyString(),anyString(),any(),any(),any(),any(),any())).thenReturn(task);
        when(store.createAttempt(20,"Hello",false)).thenThrow(new IllegalStateException("Attempt DB unavailable"));
        for(int i=0;i<129;i++) assertThrows(IllegalStateException.class,()->voices.audition(7,5,"request","Hello"));
        assertEquals(0,active(voices).size());
        assertThrows(IllegalStateException.class,()->voices.submit(grant,2,new TtsSynthesisWork("11",1,"segment",0,"Hello",principal),mock(TtsCompletionSink.class)));
        assertEquals(0,active(voices).size());
        doReturn(dispatch(task)).when(store).createAttempt(20,"Hello",false);
        when(mapper.task(20)).thenThrow(new IllegalStateException("Polling DB unavailable"));
        assertThrows(IllegalStateException.class,()->voices.audition(7,5,"request","Hello"));
        assertEquals(0,active(voices).size());
        var expired=task(20,Instant.now().minusSeconds(1));
        doReturn(expired).when(mapper).task(20);
        doThrow(new IllegalStateException("Recovery DB unavailable")).when(store).recover(expired);
        assertThrows(IllegalStateException.class,()->voices.audition(7,5,"request","Hello"));
        assertEquals(0,active(voices).size());
        doReturn(task).when(mapper).task(20);
        Thread.currentThread().interrupt();
        try { assertEquals("VOICE_OUTCOME_UNKNOWN",assertThrows(RuntimeProblem.class,()->voices.audition(7,5,"request","Hello")).code()); }
        finally { Thread.interrupted(); }
        assertEquals(0,active(voices).size());
    }

    @Test void duplicateAuditionCannotOverwriteOrReleaseTheOwnersContext() throws Exception
    {
        var store=mock(VoiceAttemptStore.class);var mapper=mock(VoiceTaskMapper.class);when(store.mapper()).thenReturn(mapper);
        var client=mock(VoiceExecutionClient.class);when(client.binding(5)).thenReturn(binding);
        var task=task(20);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(store.create(anyLong(),anyString(),anyString(),anyString(),any(),any(),any(),any(),any())).thenReturn(task);
        when(store.createAttempt(20,"Hello",false)).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return dispatch(task);});
        when(mapper.task(20)).thenThrow(new IllegalStateException("Duplicate polling failed"));
        var voices=voices(store,client);var executor=Executors.newSingleThreadExecutor();
        try {
            var owner=executor.submit(()->voices.audition(7,5,"request","Hello"));
            assertTrue(entered.await(5,TimeUnit.SECONDS));Object context=active(voices).get(20L);
            assertThrows(IllegalStateException.class,()->voices.audition(7,5,"request","Hello"));
            assertSame(context,active(voices).get(20L));
            verify(store,times(1)).createAttempt(20,"Hello",false);
            release.countDown();assertThrows(java.util.concurrent.ExecutionException.class,()->owner.get(5,TimeUnit.SECONDS));
            assertEquals(0,active(voices).size());
        } finally { release.countDown();executor.shutdownNow(); }
    }

    private VoiceOrchestrationServiceImpl voices(VoiceAttemptStore store,VoiceExecutionClient client)
    { return new VoiceOrchestrationServiceImpl(store,client,mock(TtsRuntimeAdapterRegistry.class),mock(RuntimeAuthorization.class),
        mock(TemporaryWavStorage.class),new VoiceRuntimeProperties(),mock(VoiceExecutionPool.class),new ObjectMapper()); }

    @Test void invalidReferenceNeverStartsBackupAndReleasesTheUnsuccessfulOperation() throws Exception
    {
        var mapper=mock(VoiceTaskMapper.class);
        var store=spy(new VoiceAttemptStore(mapper,new ObjectMapper()));
        var full=new VoiceBinding("5","COSYVOICE3","6",1,"model","revision","cosyvoice3-v1",
            "reference:3","zh-CN",Map.of(),"3","参考",binding,true,"1","http://127.0.0.1:8012");
        var business=new Task(20,7,"BUSINESS","11:0","hash",5,"{}","1","RUNNING",1,null,3L,11L,21L,2L,1L,10L,
            "owner",null,Instant.now().plusSeconds(60),"hash",5,null,null);
        var attempt=new VoiceTaskMapper.Attempt(30,20,1,"COSYVOICE3",6,5,"revision","cosyvoice3-v1",
            "hash","permit","DISPATCHING","worker","boot",1,business.deadlineAt(),null,null,null,null,"SELF_HOSTED");
        doReturn(business).when(store).create(anyLong(),anyString(),anyString(),anyString(),any(),any(),any(),any(),any());
        doReturn(new VoiceAttemptStore.Dispatch(business,attempt,"permit",full)).when(store).createAttempt(20,"Hello",false);
        doReturn(full).when(store).fullBinding(business);
        when(mapper.task(20)).thenReturn(business);when(mapper.attempt(30)).thenReturn(attempt);when(mapper.attemptForUpdate(30)).thenReturn(attempt);
        when(mapper.finishAttempt(eq(30L),eq("FAILED"),any(),any(),any(),any(),any())).thenReturn(1);
        var sink=mock(TtsCompletionSink.class);var voices=voices(store,mock(VoiceExecutionClient.class));
        var work=new TtsSynthesisWork("11",1,"segment",0,"Hello",principal);
        voices.submit(grant,2,work,sink);
        var event=new com.ruoyi.common.voice.VoiceProtocol.Event("reference-rejected","30",1,"hash","worker","boot",
            "FAILED","VOICE_REFERENCE_UNAVAILABLE","DEFINITIVE",null,null,"SELF_HOSTED","revision");
        voices.event(30,event);
        verify(store).failure(30,event,false,event);
        verify(store,never()).createAttempt(anyLong(),anyString(),eq(true));
        verify(mapper).finishTask(20,"FAILED","VOICE_REFERENCE_UNAVAILABLE");
        verify(mapper).settle(20,"RELEASE");
        verify(sink).onAudioFailed(principal,work,"VOICE_REFERENCE_UNAVAILABLE");
        assertEquals(0,active(voices).size());
    }
    private Task task(long id)
    { return task(id,Instant.now().plusSeconds(60)); }
    private Task task(long id,Instant deadline)
    { return new Task(id,7,"AUDITION","5:request","hash",5,"{}","1","QUEUED",1,null,null,null,null,null,null,null,
        "owner",null,deadline,"hash",5,null,null); }
    private VoiceAttemptStore.Dispatch dispatch(Task task)
    { return new VoiceAttemptStore.Dispatch(task,new VoiceTaskMapper.Attempt(30,task.id(),1,"TEST_TONE",6,5,"1","test-tone-v1",
        "hash","permit","CREATED",null,null,1,task.deadlineAt(),null,null,null,null,"TEST"),"permit",binding); }
    private static Map<?,?> active(VoiceOrchestrationServiceImpl voices) throws Exception
    { var field=VoiceOrchestrationServiceImpl.class.getDeclaredField("active");field.setAccessible(true);return (Map<?,?>)field.get(voices); }
}
