package com.ruoyi.session.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import com.ruoyi.session.runtime.mapper.TtsLifecycleMapper;

@EnabledIfEnvironmentVariable(named="VOICE_TEST_MYSQL_URL", matches=".+")
class VoiceLifecycleMysqlTest
{
    @Test void realOperationConstraintsDispatchStopRecoveryAndCompletionCAS() throws Exception
    {
        String schema="ln_voice_session_test_"+UUID.randomUUID().toString().replace("-","");
        var admin=new DriverManagerDataSource(System.getenv("VOICE_TEST_MYSQL_URL"),
            System.getenv("VOICE_TEST_MYSQL_USER"),System.getenv("VOICE_TEST_MYSQL_PASSWORD"));
        var server=new JdbcTemplate(admin); server.execute("create database `"+schema+"`");
        try
        {
            var ds=new DriverManagerDataSource(System.getenv("VOICE_TEST_MYSQL_URL")+schema+"?serverTimezone=UTC",
                System.getenv("VOICE_TEST_MYSQL_USER"),System.getenv("VOICE_TEST_MYSQL_PASSWORD"));
            org.flywaydb.core.Flyway.configure().dataSource(ds).load().migrate();
            var jdbc=new JdbcTemplate(ds);
            jdbc.update("insert into s_principal (id,created_at,updated_at,account_id,application_id,principal_type,external_user_id,status,last_seen_at) values (1,now(3),now(3),7,8,'BUSINESS','fixture','ACTIVE',now(3))");
            jdbc.update("insert into s_session (id,created_at,updated_at,account_id,application_id,principal_id,session_snapshot_id,create_request_id,reference_operation_id,status,last_activity_at,expires_at,active_turn_id,connection_epoch) values (3,now(3),now(3),7,8,1,4,'fixture','fixture','ACTIVE',now(3),date_add(now(3),interval 1 hour),11,2)");
            jdbc.update("insert into s_turn (id,created_at,updated_at,account_id,session_id,turn_no,client_request_id,request_hash,turn_type,status,text_status,audio_status,playback_status,connection_epoch,input_source,include_in_history,last_event_seq,started_at) values (11,now(3),now(3),7,3,1,'fixture',unhex(repeat('00',32)),'SPEAK','RUNNING','NOT_REQUESTED','RUNNING','WAITING',2,'TEXT',0,0,now(3))");
            for(int i=0;i<4;i++) jdbc.update("insert into s_operation (id,created_at,updated_at,account_id,session_id,turn_id,client_request_id,operation_type,ordinal,status,playback_status,input_char_count,result_summary) values (?,now(3),now(3),7,3,11,?,'TTS',?,'QUEUED','WAITING',10,json_object('segmentId',?))",21+i,"segment"+i,i,"segment"+i);
            var mapper=new SqlSessionTemplate(new TtsPersistenceConfiguration().ttsSqlSessionFactory(ds)).getMapper(TtsLifecycleMapper.class);
            assertEquals(1,mapper.prepare(7,3,11,0,"segment0",2,"boot",Instant.now().plusSeconds(120)));
            assertEquals(0,mapper.prepare(7,3,11,0,"segment0",2,"boot",Instant.now().plusSeconds(120)));
            mapper.reserved(11,0,51);
            jdbc.update("update s_operation set status='RUNNING' where id=21");
            assertEquals(1,mapper.dispatch(11,0,"boot",2));
            assertEquals(0,mapper.dispatch(11,0,"boot",2));
            assertEquals(1,mapper.finish(11,0,"boot",true));
            assertEquals(0,mapper.finish(11,0,"boot",false));
            assertEquals("SETTLE",mapper.pending(8).get(0).outcome());
            assertEquals(0,mapper.done(21,"REVIEW"));
            assertEquals(1,mapper.done(21,"SETTLE"));
            mapper.prepare(7,3,11,1,"segment1",2,"boot",Instant.now().plusSeconds(120));
            jdbc.update("update s_session set active_turn_id=null where id=3");
            jdbc.update("update s_operation set status='UNKNOWN',playback_status='STOPPED' where id=22");
            assertEquals(0,mapper.dispatch(11,1,"boot",2));
            mapper.finish(11,1,"boot",false);
            assertEquals("RELEASE",mapper.pending(8).get(0).outcome());
            jdbc.update("update s_session set active_turn_id=11 where id=3");
            mapper.prepare(7,3,11,2,"segment2",2,"old-boot",Instant.now().plusSeconds(120));
            jdbc.update("update s_operation set status='RUNNING' where id=23");
            mapper.dispatch(11,2,"old-boot",2);
            assertEquals(1,mapper.recover("boot",32));
            assertEquals("REVIEW",jdbc.queryForObject("select settlement_outcome from s_operation where id=23",String.class));
            mapper.finish(11,2,"old-boot",true);
            assertEquals("SETTLE",jdbc.queryForObject("select settlement_outcome from s_operation where id=23",String.class));
            assertTrue(mapper.factsToRecover("boot",32).contains(23L));
            var store=new PersistentRuntimeStore(jdbc,new com.fasterxml.jackson.databind.ObjectMapper());
            var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
            tx.execute(s->{store.reconcileTtsFact(23);return null;});
            tx.execute(s->{store.reconcileTtsFact(23);return null;});
            assertEquals("SUCCEEDED",jdbc.queryForObject("select status from s_operation where id=23",String.class));
            assertEquals(1L,jdbc.queryForObject("select count(*) from s_outbox where event_type='CALL_FACT_RECORDED'",Long.class));
            for(int i=0;i<12;i++) mapper.retry(22,"RELEASE","TEST_UNAVAILABLE");
            assertEquals("REVIEW_REQUIRED",jdbc.queryForObject("select settlement_status from s_operation where id=22",String.class));
            assertEquals(0,mapper.prepare(8,3,11,3,"segment3",2,"boot",Instant.now().plusSeconds(120)));
            System.out.println("Voice lifecycle MySQL: V8, dispatch CAS, stop, boot recovery, late success, retry ceiling passed");
            System.out.println("Finalization EXPLAIN: "+jdbc.queryForList("explain select id from s_operation where settlement_status='PENDING' and settlement_next_at<=utc_timestamp(3) order by settlement_next_at,id limit 8"));
        }
        finally { server.execute("drop database `"+schema+"`"); }
    }

    @Test void completeFlywayChainMigratesAnEmptyIsolatedDatabase()
    {
        String schema="ln_voice_session_test_"+UUID.randomUUID().toString().replace("-","");
        var admin=new DriverManagerDataSource(System.getenv("VOICE_TEST_MYSQL_URL"),
            System.getenv("VOICE_TEST_MYSQL_USER"),System.getenv("VOICE_TEST_MYSQL_PASSWORD"));
        var server=new JdbcTemplate(admin);server.execute("create database `"+schema+"`");
        try {
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(System.getenv("VOICE_TEST_MYSQL_URL")+schema+"?serverTimezone=UTC",
                System.getenv("VOICE_TEST_MYSQL_USER"),System.getenv("VOICE_TEST_MYSQL_PASSWORD")).load();
            assertEquals(9,flyway.migrate().migrationsExecuted);
            flyway.validate();
        } finally { server.execute("drop database `"+schema+"`"); }
    }
}
