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
            var ds=new DriverManagerDataSource(System.getenv("VOICE_TEST_MYSQL_URL")+schema+"?serverTimezone=UTC",System.getenv("VOICE_TEST_MYSQL_USER"),System.getenv("VOICE_TEST_MYSQL_PASSWORD"));
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(ds).load();assertEquals(9,flyway.migrate().migrationsExecuted);flyway.validate();
            var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            var mapper=new SqlSessionTemplate(new TtsPersistenceConfiguration().ttsSqlSessionFactory(ds)).getMapper(VoiceTaskMapper.class);
            var factory=new org.springframework.aop.framework.ProxyFactory(new VoiceAttemptStore(mapper,new ObjectMapper().findAndRegisterModules()));
            factory.setProxyTargetClass(true);
            factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new DataSourceTransactionManager(ds),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            var store=(VoiceAttemptStore)factory.getProxy();assertNotNull(store.owner());
            var binding=binding("3",null);String text="你好🙂";
            var task=tx.execute(s->store.create(7,"AUDITION","3:Request",text,binding,null,null,null,Instant.now().plusSeconds(60)));
            assertNull(task.sessionId());assertNull(task.operationId());
            assertEquals(task.id(),tx.execute(s->store.create(7,"AUDITION","3:Request",text,binding,null,null,null,Instant.now().plusSeconds(60))).id());
            assertThrows(RuntimeProblem.class,()->tx.execute(s->store.create(7,"AUDITION","3:Request","different",binding,null,null,null,Instant.now().plusSeconds(60))));
            assertNotEquals(task.id(),tx.execute(s->store.create(7,"AUDITION","3:request",text,binding,null,null,null,Instant.now().plusSeconds(60))).id());
            var d=tx.execute(s->store.createAttempt(task.id(),text,false));
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
            System.out.println("Unified Voice MySQL: V9, audition isolation, source keys, one-shot permit, winner FK, fallback limit, recovery and late settlement passed");
            System.out.println(jdbc.queryForList("explain select id from s_voice_attempt where state='UNKNOWN' and lease_expires_at<=utc_timestamp(3) order by lease_expires_at,id limit 32"));
        } finally { server.execute("drop database `"+schema+"`"); }
    }
    private static VoiceBinding binding(String id,VoiceBinding fallback)
    { return new VoiceBinding(id,"TEST_TONE","5",1,"test-tone","1","test-tone-v1","tone","zh-CN",Map.of(),null,null,fallback,fallback!=null,"1","http://127.0.0.1:8003"); }
    private static VoiceProtocol.Event event(VoiceAttemptStore.Dispatch d,String state,String code,String stage)
    { return new VoiceProtocol.Event(UUID.randomUUID().toString(),Long.toString(d.attempt().id()),d.attempt().attemptNo(),d.attempt().requestHash(),
        "SUCCEEDED".equals(state)?"worker":null,"SUCCEEDED".equals(state)?"boot":null,state,code,stage,null,null,"TEST","1"); }
}
