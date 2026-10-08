package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.voice.*;
import com.ruoyi.session.runtime.mapper.VoiceTaskMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="VOICE_TEST_MYSQL_URL", matches=".+")
class UnifiedVoiceMysqlTest
{
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path audioDirectory;
    @Test void realPermitsAdoptionFallbackRecoveryAndAuditionIsolation() throws Exception
    {
        String schema="ln_voice_protocol_test_"+UUID.randomUUID().toString().replace("-","");
        var admin=new DriverManagerDataSource(System.getenv("VOICE_TEST_MYSQL_URL"),System.getenv("VOICE_TEST_MYSQL_USER"),System.getenv("VOICE_TEST_MYSQL_PASSWORD"));
        var server=new JdbcTemplate(admin);server.execute("create database `"+schema+"`");
        try {
            var ds=new DriverManagerDataSource(System.getenv("VOICE_TEST_MYSQL_URL")+schema+"?serverTimezone=UTC&forceConnectionTimeZoneToSession=true",System.getenv("VOICE_TEST_MYSQL_USER"),System.getenv("VOICE_TEST_MYSQL_PASSWORD"));
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(ds).load();assertEquals(9,flyway.migrate().migrationsExecuted);flyway.validate();
            var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            var mapper=new SqlSessionTemplate(new TtsPersistenceConfiguration().ttsSqlSessionFactory(ds)).getMapper(VoiceTaskMapper.class);
            var factory=new org.springframework.aop.framework.ProxyFactory(new VoiceAttemptStore(mapper,new ObjectMapper().findAndRegisterModules()));
            factory.setProxyTargetClass(true);
            factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new DataSourceTransactionManager(ds),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            var store=(VoiceAttemptStore)factory.getProxy();assertNotNull(store.owner());
            var binding=binding("3",null);String text="你好🙂";
            // Java-bound deadlines must be selected by the SQL recovery clock without an eight-hour delay.
            var expired=store.create(7,"AUDITION","3:utc-expired",text,binding,null,null,null,Instant.now().minusSeconds(1));
            assertTrue(mapper.recoverable(store.owner()).stream().anyMatch(t->t.id()==expired.id()));
            assertEquals("VOICE_CANCELLED",assertThrows(RuntimeProblem.class,()->store.createAttempt(expired.id(),text,false)).code());
            // A fresh process must recover a persisted task even while its deadline is still in the future.
            var orphan=store.create(7,"AUDITION","3:utc-orphan",text,binding,null,null,null,Instant.now().plusSeconds(60));
            assertTrue(mapper.recoverable("another-boot").stream().anyMatch(t->t.id()==orphan.id()));
            store.recover(expired);store.recover(orphan);
            var task=tx.execute(s->store.create(7,"AUDITION","3:Request",text,binding,null,null,null,Instant.now().plusSeconds(60)));
            assertNull(task.sessionId());assertNull(task.operationId());
            assertEquals(task.id(),tx.execute(s->store.create(7,"AUDITION","3:Request",text,binding,null,null,null,Instant.now().plusSeconds(60))).id());
            assertThrows(RuntimeProblem.class,()->tx.execute(s->store.create(7,"AUDITION","3:Request","different",binding,null,null,null,Instant.now().plusSeconds(60))));
            assertNotEquals(task.id(),tx.execute(s->store.create(7,"AUDITION","3:request",text,binding,null,null,null,Instant.now().plusSeconds(60))).id());
            var d=tx.execute(s->store.createAttempt(task.id(),text,false));
            long leaseSeconds=jdbc.queryForObject("select timestampdiff(second,utc_timestamp(3),lease_expires_at) from s_voice_attempt where id=?",Long.class,d.attempt().id());
            assertTrue(leaseSeconds>0 && leaseSeconds<=60,"Java-bound lease must share the SQL UTC clock");
            assertFalse(tx.<Boolean>execute(s->store.authorize(d.attempt().id(),new VoiceProtocol.Permit(1,"worker","boot","wrong"))));
            var permit=new VoiceProtocol.Permit(1,"worker","boot",d.token());
            var gate=new java.util.concurrent.CountDownLatch(1);
            var threads=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var permits=List.of(threads.submit(()->{gate.await();return store.authorize(d.attempt().id(),permit);}),threads.submit(()->{gate.await();return store.authorize(d.attempt().id(),permit);}));gate.countDown();
                assertEquals(1,(permits.get(0).get(5,java.util.concurrent.TimeUnit.SECONDS)?1:0)+(permits.get(1).get(5,java.util.concurrent.TimeUnit.SECONDS)?1:0));
            } finally {threads.shutdownNow();}
            assertFalse(tx.<Boolean>execute(s->store.authorize(d.attempt().id(),new VoiceProtocol.Permit(1,"worker","boot",d.token()))));
            var event=event(d,"SUCCEEDED",null,"DEFINITIVE");
            var audio=new TemporaryAudioReference("media","LOCAL_TEMP","session-audio",UUID.randomUUID()+".wav",Instant.now().plusSeconds(60));
            assertTrue(tx.<Boolean>execute(s->store.success(d.attempt().id(),event,audio)));
            assertFalse(tx.<Boolean>execute(s->store.success(d.attempt().id(),event,audio)));
            assertEquals(d.attempt().id(),mapper.task(task.id()).winnerAttemptId());
            assertEquals(0,mapper.deliveryReview(task.id()));
            String attemptId=Long.toString(d.attempt().id());
            jdbc.update("update s_outbox set attempt_count=12 where event_type='VOICE_ATTEMPT_FACT' and aggregate_id=?",attemptId);
            int pending=jdbc.queryForObject("select count(*) from s_outbox where event_type='VOICE_ATTEMPT_FACT' and aggregate_id=?",Integer.class,attemptId);
            assertTrue(pending>0);
            assertEquals(pending,mapper.deliveryReview(task.id()));
            jdbc.update("update s_outbox set status='SENT' where event_type='VOICE_ATTEMPT_FACT' and aggregate_id=?",attemptId);
            assertEquals(0,mapper.deliveryReview(task.id()));
            assertEquals(0,jdbc.queryForObject("select count(*) from s_operation",Integer.class));
            assertThrows(RuntimeProblem.class,()->tx.execute(s->store.failure(d.attempt().id(),new VoiceProtocol.Event(event.eventId(),event.attemptId(),1,event.requestHash(),"worker","boot","FAILED","VOICE_QUEUE_FULL","BEFORE_DISPATCH",null,null,"TEST","1"),false)));

            // An in-flight business operation is stopped: late success settles, but cannot start a backup.
            jdbc.update("insert into s_principal(id,created_at,updated_at,account_id,application_id,principal_type,external_user_id,status,last_seen_at) values(1,now(3),now(3),7,8,'BUSINESS','fixture','ACTIVE',now(3))");
            jdbc.update("insert into s_session(id,created_at,updated_at,account_id,application_id,principal_id,session_snapshot_id,create_request_id,reference_operation_id,status,last_activity_at,expires_at,active_turn_id,connection_epoch) values(3,now(3),now(3),7,8,1,4,'fixture','fixture','ACTIVE',now(3),date_add(now(3),interval 1 hour),11,2)");
            jdbc.update("insert into s_turn(id,created_at,updated_at,account_id,session_id,turn_no,client_request_id,request_hash,turn_type,status,text_status,audio_status,playback_status,connection_epoch,input_source,include_in_history,last_event_seq,started_at) values(11,now(3),now(3),7,3,1,'fixture',unhex(repeat('00',32)),'SPEAK','RUNNING','NOT_REQUESTED','RUNNING','WAITING',2,'TEXT',0,0,now(3))");
            jdbc.update("insert into s_operation(id,created_at,updated_at,account_id,session_id,turn_id,client_request_id,operation_type,ordinal,status,playback_status,input_char_count,tts_reservation_known,tts_dispatch_state,tts_owner,tts_deadline_at) values(21,now(3),now(3),7,3,11,'segment','TTS',0,'RUNNING','WAITING',3,true,'PREPARED','boot',date_add(now(3),interval 1 minute))");
            jdbc.update("update s_operation set result_summary=json_object('segmentId','segment') where id=21");
            var primary=binding("3",binding("4",null));
            var principal=new RuntimePrincipal(7,8,3,4,Set.of("speak:write"),new VoiceRuntimeBinding(3,TtsProviderKind.OFFICIAL,"tone",5L,1L,primary));
            var work=new TtsSynthesisWork("11",1,"segment",0,text,principal);
            var business=tx.execute(s->store.create(7,"BUSINESS","11:0",text,primary,work,2L,9L,Instant.now().plusSeconds(60)));
            var first=tx.execute(s->store.createAttempt(business.id(),text,false));
            assertTrue(tx.<Boolean>execute(s->store.failure(first.attempt().id(),event(first,"FAILED","VOICE_QUEUE_FULL","BEFORE_DISPATCH"),true)));
            var backup=tx.execute(s->store.createAttempt(business.id(),text,true));
            assertThrows(RuntimeProblem.class,()->tx.execute(s->store.createAttempt(business.id(),text,true)));
            assertTrue(tx.<Boolean>execute(s->store.authorize(backup.attempt().id(),new VoiceProtocol.Permit(2,"worker","boot",backup.token()))));
            assertEquals("DISPATCHING",jdbc.queryForObject("select tts_dispatch_state from s_operation where id=21",String.class));
            jdbc.update("update s_session set active_turn_id=null where id=3");
            tx.executeWithoutResult(s->store.recover(mapper.task(business.id())));
            assertEquals("UNKNOWN",mapper.task(business.id()).status());
            assertEquals(1,mapper.queries().size());
            for(int i=0;i<12;i++) { assertEquals(1,mapper.claimQuery(backup.attempt().id()));jdbc.update("update s_voice_attempt set query_next_at=utc_timestamp(3) where id=?",backup.attempt().id()); }
            assertEquals(0,mapper.claimQuery(backup.attempt().id()));assertTrue(mapper.queries().isEmpty());
            assertEquals("REVIEW",jdbc.queryForObject("select settlement_outcome from s_operation where id=21",String.class));
            assertTrue(tx.<Boolean>execute(s->store.success(backup.attempt().id(),event(backup,"SUCCEEDED",null,"DEFINITIVE"),audio)));
            assertEquals("SETTLE",jdbc.queryForObject("select settlement_outcome from s_operation where id=21",String.class));
            assertEquals(backup.attempt().id(),mapper.task(business.id()).winnerAttemptId());
            assertThrows(Exception.class,()->jdbc.update("update s_voice_task set winner_attempt_id=? where id=?",d.attempt().id(),business.id()));
            assertThrows(Exception.class,()->jdbc.update("update s_voice_attempt set attempt_no=3 where id=?",backup.attempt().id()));
            assertEquals("tts:11:segment",jdbc.queryForObject("select json_unquote(json_extract(payload,'$.logicalOperationKey')) from s_outbox where event_type='VOICE_ATTEMPT_FACT' and json_unquote(json_extract(payload,'$.purpose'))='BUSINESS' limit 1",String.class));
            assertEquals(4,jdbc.queryForObject("select count(*) from s_outbox where event_type='VOICE_ATTEMPT_FACT'",Integer.class));
            assertEquals(1,jdbc.queryForObject("select count(*) from s_outbox where event_type='VOICE_ATTEMPT_FACT' and json_extract(payload,'$.attemptId')=? and json_unquote(json_extract(payload,'$.status'))='SUCCEEDED'",Integer.class,d.attempt().id()));
            // Exercise the real common arbiter, Spring transactions, permit and result read end to end.
            var properties=new VoiceRuntimeProperties();properties.setTemporaryAudioDirectory(audioDirectory.toString());
            var storage=new TemporaryWavStorage(properties);var client=org.mockito.Mockito.mock(VoiceExecutionClient.class);
            org.mockito.Mockito.when(client.binding(3)).thenReturn(binding);
            var voicesRef=new java.util.concurrent.atomic.AtomicReference<VoiceOrchestrationServiceImpl>();
            var buffer=java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            buffer.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1)
                .putInt(24000).putInt(48000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);
            byte[] result=buffer.array();
            org.mockito.Mockito.when(client.audio(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(result);
            org.mockito.Mockito.doAnswer(call->{
                VoiceProtocol.Request request=call.getArgument(0);long id=Long.parseLong(request.attemptId());
                assertTrue(voicesRef.get().authorize(id,new VoiceProtocol.Permit(request.taskRevision(),"fixture","fixture-boot",request.dispatchToken())).authorized());
                voicesRef.get().event(id,new VoiceProtocol.Event("fixture-completed",request.attemptId(),request.taskRevision(),request.requestHash(),"fixture","fixture-boot","SUCCEEDED",null,"DEFINITIVE",
                    new VoiceProtocol.Audio("audio/wav","PCM_S16LE",24000,1,16,48,1,VoiceProtocol.hash(result)),null,"TEST","1"));return null;
            }).when(client).submit(org.mockito.ArgumentMatchers.any());
            var pool=new VoiceExecutionPool(properties);
            try {
                var voices=new VoiceOrchestrationServiceImpl(store,client,org.mockito.Mockito.mock(TtsRuntimeAdapterRegistry.class),org.mockito.Mockito.mock(RuntimeAuthorization.class),storage,properties,pool,new ObjectMapper().findAndRegisterModules());voicesRef.set(voices);
                assertArrayEquals(result,voices.audition(7,3,"arbiter-e2e",text));
                assertArrayEquals(result,voices.audition(7,3,"arbiter-e2e",text));
                org.mockito.Mockito.verify(client,org.mockito.Mockito.times(1)).submit(org.mockito.ArgumentMatchers.any());
                assertEquals("SUCCEEDED",mapper.source(7,"AUDITION","3:arbiter-e2e").status());
            } finally { pool.close(); }
            // R04: real permits time out without callbacks; UNKNOWN/query evidence survives slot release.
            var inlinePool=mock(VoiceExecutionPool.class);
            doAnswer(call->{ ((Runnable)call.getArgument(0)).run();return null; }).when(inlinePool).execute(any(),any());
            var access=mock(RuntimeAuthorization.class);
            var voices=new VoiceOrchestrationServiceImpl(store,client,mock(TtsRuntimeAdapterRegistry.class),access,
                storage,properties,inlinePool,new ObjectMapper().findAndRegisterModules());voicesRef.set(voices);
            var timedOut=new ArrayList<VoiceProtocol.Request>();
            doAnswer(call->{
                VoiceProtocol.Request request=call.getArgument(0);timedOut.add(request);
                assertTrue(voices.authorize(Long.parseLong(request.attemptId()),new VoiceProtocol.Permit(1,"fixture","fixture-boot",request.dispatchToken())).authorized());
                jdbc.update("update s_voice_task set deadline_at=date_sub(utc_timestamp(3),interval 1 second) where id=?",Long.parseLong(request.taskId()));return null;
            }).when(client).submit(any());
            for(int i=0;i<129;i++) assertEquals("VOICE_OUTCOME_UNKNOWN",assertThrows(RuntimeProblem.class,
                ()->voices.audition(7,3,"timeout-"+timedOut.size(),text)).code());
            voices.recover();
            var contexts=VoiceOrchestrationServiceImpl.class.getDeclaredField("active");contexts.setAccessible(true);
            assertEquals(0,((Map<?,?>)contexts.get(voices)).size());
            assertEquals(129,jdbc.queryForObject("select count(*) from s_voice_task where source_key like '3:timeout-%' and status='UNKNOWN'",Integer.class));
            assertEquals(129,jdbc.queryForObject("select count(*) from s_voice_attempt a join s_voice_task t on t.id=a.task_id where t.source_key like '3:timeout-%' and a.state='UNKNOWN'",Integer.class));
            VoiceProtocol.Request late=timedOut.get(0);
            voices.event(Long.parseLong(late.attemptId()),new VoiceProtocol.Event("late-completed",late.attemptId(),1,late.requestHash(),
                "fixture","fixture-boot","SUCCEEDED",null,"DEFINITIVE",new VoiceProtocol.Audio("audio/wav","PCM_S16LE",24000,1,16,48,1,VoiceProtocol.hash(result)),null,"TEST","1"));
            assertArrayEquals(result,voices.audition(7,3,"timeout-0",text));assertEquals(129,timedOut.size());

            // R03: actual submission wrapper -> common arbiter -> runtime, after durable success/SETTLE.
            var persistent=spy(new PersistentRuntimeStore(jdbc,new ObjectMapper().findAndRegisterModules()));
            doThrow(new IllegalStateException("Injected registration DB failure"))
                .when(persistent).markAudioReady(anyLong(),anyInt(),any(),any(),anyLong(),anyLong());
            doThrow(new IllegalStateException("Injected cancellation DB failure")).when(persistent).cancelNotSubmitted(any(),any());
            doThrow(new IllegalStateException("Injected failure DB failure")).when(persistent).markAudioFailed(anyLong(),anyInt(),anyString());
            doThrow(new IllegalStateException("Injected turn DB failure")).when(persistent).fail(anyLong());
            var events=mock(RuntimeEventPublisher.class);
            var runtime=new SpeakOnlyRuntimeService(properties,mock(TemporaryAudioCleanupQueue.class),persistent,events);
            var businessPrincipal=new RuntimePrincipal(7,8,3,4,Set.of("speak:write"),new VoiceRuntimeBinding(3,TtsProviderKind.OFFICIAL,"tone",5L,1L,binding));
            var grant=new RuntimeAuthorization.Grant(businessPrincipal,10,"fixture-token","BUSINESS_KEY",1,Instant.now().plusSeconds(60));
            when(access.verify(10)).thenReturn(grant);
            jdbc.update("update s_session set next_turn_no=2 where id=3");
            var deliveryWork=runtime.start(businessPrincipal,"delivery-boundary",text,2).initialWork().get(0);
            var replay=runtime.start(businessPrincipal,"delivery-boundary",text,2);
            assertEquals(deliveryWork.turnId(),replay.turnId());assertTrue(replay.initialWork().isEmpty());
            assertTrue(runtime.hasTurn(deliveryWork.turnId()));
            var conflict=assertThrows(RuntimeProblem.class,()->runtime.start(businessPrincipal,"delivery-boundary",text+" changed",2));
            assertEquals("REQUEST_CONFLICT",conflict.code());assertTrue(runtime.hasTurn(deliveryWork.turnId()));
            assertEquals("RUNNING",jdbc.queryForObject("select status from s_turn where id=?",String.class,Long.parseLong(deliveryWork.turnId())));
            var restartedRuntime=new SpeakOnlyRuntimeService(properties,mock(TemporaryAudioCleanupQueue.class),persistent,events);
            assertTrue(restartedRuntime.start(businessPrincipal,"delivery-boundary",text,2).initialWork().isEmpty());
            assertEquals(1,jdbc.queryForObject("select count(*) from s_turn where session_id=3 and client_request_id='delivery-boundary'",Integer.class));
            assertEquals(1,jdbc.queryForObject("select count(*) from s_operation where turn_id=?",Integer.class,Long.parseLong(deliveryWork.turnId())));
            var epochs=mock(RuntimeConnectionEpochs.class);when(epochs.current(businessPrincipal,2)).thenReturn(true);
            var system=mock(com.ruoyi.session.business.BusinessSystemClient.class);
            when(system.reserveTts(anyLong(),anyLong(),anyString(),anyLong())).thenReturn(51L);
            var lifecycle=new SqlSessionTemplate(new TtsPersistenceConfiguration().ttsSqlSessionFactory(ds)).getMapper(com.ruoyi.session.runtime.mapper.TtsLifecycleMapper.class);
            var completed=new java.util.concurrent.atomic.AtomicReference<VoiceProtocol.Event>();
            doAnswer(call->{
                VoiceProtocol.Request request=call.getArgument(0);
                assertTrue(voices.authorize(Long.parseLong(request.attemptId()),new VoiceProtocol.Permit(1,"fixture","fixture-boot",request.dispatchToken())).authorized());
                var success=new VoiceProtocol.Event("delivery-completed",request.attemptId(),1,request.requestHash(),"fixture","fixture-boot","SUCCEEDED",null,"DEFINITIVE",
                    new VoiceProtocol.Audio("audio/wav","PCM_S16LE",24000,1,16,48,1,VoiceProtocol.hash(result)),null,"TEST","1");
                completed.set(success);voices.event(Long.parseLong(request.attemptId()),success);return null;
            }).when(client).submit(any());
            assertArrayEquals(result,voices.audition(7,3,"after-timeouts",text));
            long filesBefore;
            try(var files=java.nio.file.Files.list(audioDirectory)) { filesBefore=files.count(); }
            var submissions=new TtsSubmissionService(access,epochs,persistent,system,voices,runtime,lifecycle,properties);
            assertDoesNotThrow(()->submissions.submit(grant,2,List.of(deliveryWork)));
            assertFalse(runtime.hasTurn(deliveryWork.turnId()));
            verify(events).audioFailed(businessPrincipal,2,deliveryWork.turnId(),deliveryWork.segmentId(),0,"VOICE_AUDIO_DELIVERY_FAILED");
            verify(events,never()).audioSegment(any(),anyLong(),any());
            var delivered=mapper.source(7,"BUSINESS",deliveryWork.turnId()+":0");assertEquals("SUCCEEDED",delivered.status());
            assertEquals("SETTLE:PENDING",mapper.settlement(delivered.id()));
            voices.event(Long.parseLong(completed.get().attemptId()),completed.get());
            assertEquals(1,jdbc.queryForObject("select count(*) from s_outbox where event_type='VOICE_ATTEMPT_FACT' and aggregate_id=? and json_unquote(json_extract(payload,'$.status'))='SUCCEEDED'",Integer.class,completed.get().attemptId()));
            try(var files=java.nio.file.Files.list(audioDirectory)) { assertEquals(filesBefore,files.count()); }
            var finalizer=new TtsFinalizationWorker(lifecycle,system,submissions,persistent);
            finalizer.sweep();finalizer.sweep();
            verify(system,times(1)).finishTts(7,deliveryWork.turnId()+":0","SETTLE");
            assertEquals("SETTLE:DONE",mapper.settlement(delivered.id()));
            assertEquals(0,((Map<?,?>)contexts.get(voices)).size());
            System.out.println("Unified Voice MySQL: V9, audition isolation, source keys, one-shot permit, winner FK, fallback limit, recovery and late settlement passed");
            System.out.println("R03/R04: real submission/arbiter/runtime failure boundary, single SETTLE, WAV cleanup, 129 timeouts, retained UNKNOWN and late read-only result passed");
            System.out.println(jdbc.queryForList("explain select id from s_voice_attempt where state='UNKNOWN' and lease_expires_at<=utc_timestamp(3) order by lease_expires_at,id limit 32"));
        } finally { server.execute("drop database `"+schema+"`"); }
    }
    private static VoiceBinding binding(String id,VoiceBinding fallback)
    { return new VoiceBinding(id,"TEST_TONE","5",1,"test-tone","1","test-tone-v1","tone","zh-CN",Map.of(),null,null,fallback,fallback!=null,"1","http://127.0.0.1:8003"); }
    private static VoiceProtocol.Event event(VoiceAttemptStore.Dispatch d,String state,String code,String stage)
    { return new VoiceProtocol.Event(UUID.randomUUID().toString(),Long.toString(d.attempt().id()),d.attempt().attemptNo(),d.attempt().requestHash(),
        "SUCCEEDED".equals(state)?"worker":null,"SUCCEEDED".equals(state)?"boot":null,state,code,stage,null,null,"TEST","1"); }
}
