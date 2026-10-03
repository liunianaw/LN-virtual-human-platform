package com.ruoyi.system.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.voice.mapper.VoiceBindingMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="VOICE_TEST_MYSQL_URL", matches=".+")
class UnifiedVoiceBindingsMysqlTest
{
    @Test void completeMigrationAndCapabilityDrivenCandidateProtectsFallback() throws Exception
    {
        String schema="ln_voice_binding_test_"+UUID.randomUUID().toString().replace("-","");
        var admin=new DriverManagerDataSource(System.getenv("VOICE_TEST_MYSQL_URL"),System.getenv("VOICE_TEST_MYSQL_USER"),System.getenv("VOICE_TEST_MYSQL_PASSWORD"));
        var server=new JdbcTemplate(admin);server.execute("create database `"+schema+"`");
        try(var security=mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(true);
            var ds=new DriverManagerDataSource(System.getenv("VOICE_TEST_MYSQL_URL")+schema+"?serverTimezone=UTC",System.getenv("VOICE_TEST_MYSQL_USER"),System.getenv("VOICE_TEST_MYSQL_PASSWORD"));
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(ds).load();assertEquals(26,flyway.migrate().migrationsExecuted);flyway.validate();
            var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setMapperLocations(new ClassPathResource("mapper/system/VoiceBindingMapper.xml"),new ClassPathResource("mapper/system/OfficialVoiceMapper.xml"),new ClassPathResource("mapper/system/VoiceAttemptFactMapper.xml"),new ClassPathResource("mapper/application/ApplicationMapper.xml"),new ClassPathResource("mapper/developer/BusinessSessionMapper.xml"));
            var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(VoiceBindingMapper.class);
            var json=new ObjectMapper().findAndRegisterModules();var bindings=new VoiceBindingService(mapper,json);
            var service=new VoiceServiceImpl(new SqlSessionTemplate(factory.getObject()).getMapper(com.ruoyi.system.voice.mapper.OfficialVoiceMapper.class),json,mock(OfficialVoiceAuditionClient.class),mock(com.ruoyi.system.operations.OperationsService.class),bindings);
            jdbc.update("insert into p_official_service(id,created_at,updated_at,account_id,name,capability,provider_code,endpoint,model_id,secret_id,parameters,status,revision) values(501,now(3),now(3),1,'test','TTS','TEST_TONE','http://127.0.0.1:8003','test-tone',1,json_object(),'ACTIVE',1)");
            var input=new VoiceServiceImpl.OfficialVoiceInput("备用",null,501L,"1","tone","zh-CN",null,Map.of(),null,false,null,null);
            var backup=tx.execute(s->service.createOfficialCandidate(1,input,"Backup"));
            jdbc.update("update p_voice set status='PUBLISHED',current_version_id=? where id=?",backup.versionId(),backup.voiceId());
            var main=new VoiceServiceImpl.OfficialVoiceInput("主声音",null,501L,"1","tone","zh-CN",null,Map.of(),Long.parseLong(backup.versionId()),true,null,null);
            var primary=tx.execute(s->service.createOfficialCandidate(1,main,"Primary"));
            var saved=bindings.read(Long.parseLong(primary.versionId()));assertEquals(backup.versionId(),saved.fallback().voiceVersionId());
            assertEquals(1,jdbc.queryForObject("select count(*) from p_resource_reference where holder_type='VOICE_VERSION' and resource_id=? and state='CONFIRMED'",Integer.class,backup.versionId()));
            assertThrows(Exception.class,()->tx.execute(s->service.createOfficialCandidate(1,new VoiceServiceImpl.OfficialVoiceInput("非法",null,501L,"1","missing","zh-CN",null,Map.of(),null,false,null,null),"Bad")));
            assertThrows(Exception.class,()->tx.execute(s->service.createOfficialCandidate(1,new VoiceServiceImpl.OfficialVoiceInput("非法",null,501L,"1","tone","zh-CN",null,Map.of("unknown",1),null,false,null,null),"BadParameter")));
            jdbc.update("update p_official_service set revision=2 where id=501");
            assertEquals(1,bindings.available(Long.parseLong(primary.versionId()),false).serviceRevision());
            jdbc.update("update p_official_service set status='DISABLED' where id=501");
            assertThrows(Exception.class,()->bindings.available(Long.parseLong(primary.versionId()),false));
            var facts=new VoiceAttemptFactServiceImpl(new SqlSessionTemplate(factory.getObject()).getMapper(com.ruoyi.system.voice.mapper.VoiceAttemptFactMapper.class),json);
            var fact=new com.ruoyi.common.voice.VoiceAttemptFact("fact-1",1,1001,1002,"AUDITION",null,null,null,null,501,"TEST_TONE","test-tone","UNKNOWN",null,"TEST",null);
            tx.executeWithoutResult(t->facts.accept(fact));tx.executeWithoutResult(t->facts.accept(fact));
            var success=new com.ruoyi.common.voice.VoiceAttemptFact("fact-2",1,1001,1002,"AUDITION",null,null,null,null,501,"TEST_TONE","test-tone","SUCCEEDED",null,"TEST",null);
            tx.executeWithoutResult(t->facts.accept(success));
            assertEquals(1,jdbc.queryForObject("select count(*) from p_call_record where fact_kind='ATTEMPT'",Integer.class));
            assertEquals(0,jdbc.queryForObject("select count(*) from p_usage_daily",Integer.class));
            assertEquals("SUCCEEDED",jdbc.queryForObject("select status from p_call_record where voice_attempt_id=1002",String.class));
            assertThrows(Exception.class,()->tx.executeWithoutResult(t->facts.accept(new com.ruoyi.common.voice.VoiceAttemptFact("fact-1",1,1003,1002,"AUDITION",null,null,null,null,501,"TEST_TONE","test-tone","UNKNOWN",null,"TEST",null))));
            assertEquals(primary.voiceId(),service.getOfficial(Long.parseLong(primary.voiceId())).voiceId());
            System.out.println("Unified Voice System MySQL: V1-V26, capability input, frozen binding, fallback reference and emergency disable passed");
        } finally { server.execute("drop database `"+schema+"`"); }
    }
}
