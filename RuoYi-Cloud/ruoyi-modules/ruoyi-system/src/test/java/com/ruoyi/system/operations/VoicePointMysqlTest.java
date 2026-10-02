package com.ruoyi.system.operations;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.ruoyi.system.operations.mapper.PointBillingMapper;
import com.ruoyi.system.operations.service.PointBillingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses a newly created scratch schema only. No platform database is migrated. */
@EnabledIfEnvironmentVariable(named="VOICE_TEST_MYSQL_URL", matches=".+")
class VoicePointMysqlTest
{
    @Test void priceLockConcurrentSegmentsBinaryKeysAndIdempotentFinish() throws Exception
    {
        String schema="ln_voice_point_test_"+UUID.randomUUID().toString().replace("-","");
        var admin=new DriverManagerDataSource(System.getenv("VOICE_TEST_MYSQL_URL"),
            System.getenv("VOICE_TEST_MYSQL_USER"),System.getenv("VOICE_TEST_MYSQL_PASSWORD"));
        var server=new JdbcTemplate(admin);
        server.execute("create database `"+schema+"`");
        try
        {
            var ds=new DriverManagerDataSource(System.getenv("VOICE_TEST_MYSQL_URL")+schema+"?serverTimezone=UTC",
                System.getenv("VOICE_TEST_MYSQL_USER"),System.getenv("VOICE_TEST_MYSQL_PASSWORD"));
            try(var connection=ds.getConnection()) {
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V24__versioned_point_billing.sql"));
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V25__tts_request_price_and_binary_keys.sql"));
            }
            var jdbc=new JdbcTemplate(ds);
            jdbc.update("insert into p_point_balance values (7,utc_timestamp(3),utc_timestamp(3),100000,0,0,1)");
            var factory=new SqlSessionFactoryBean(); factory.setDataSource(ds);
            factory.setMapperLocations(new ClassPathResource("mapper/operations/PointBillingMapper.xml"));
            var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(PointBillingMapper.class);
            var billing=new PointBillingService(mapper);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            long initial=tx.execute(s->billing.reserveTts(7,8,21,"11:0",100));
            assertEquals(21L,initial);
            jdbc.update("insert into p_point_rate_version values (999,utc_timestamp(3),2,5000,7,0,utc_timestamp(3),1)");
            var pool=Executors.newFixedThreadPool(2);
            try {
                var first=pool.submit(()->tx.execute(s->billing.reserveTts(7,8,22,"11:1",100)));
                var repeat=pool.submit(()->tx.execute(s->billing.reserveTts(7,8,23,"11:1",100)));
                assertEquals(first.get(10,TimeUnit.SECONDS),repeat.get(10,TimeUnit.SECONDS));
            } finally { pool.shutdownNow(); }
            assertEquals(1L,jdbc.queryForObject("select count(distinct rate_version_id) from p_point_reservation",Long.class));
            assertEquals(200L,jdbc.queryForObject("select reserved_cent from p_point_balance where account_id=7",Long.class));
            assertThrows(RuntimeException.class,()->tx.execute(s->billing.reserveTts(7,9,24,"11:2",100)));
            tx.execute(s->{billing.finish(7,"TTS_SEGMENT","11:0","REVIEW",-1);return null;});
            for(int i=0;i<2;i++) tx.execute(s->{billing.finish(7,"TTS_SEGMENT","11:0","SETTLE",-1);return null;});
            tx.execute(s->{billing.finish(7,"TTS_SEGMENT","11:0","REVIEW",-1);return null;});
            assertEquals(100L,jdbc.queryForObject("select used_cent from p_point_balance where account_id=7",Long.class));
            assertEquals(1L,jdbc.queryForObject("select count(*) from p_point_entry where entry_type='SETTLE'",Long.class));
            tx.execute(s->{billing.finish(7,"TTS_SEGMENT","11:1","RELEASE",-1);return null;});
            tx.execute(s->{billing.finish(7,"TTS_SEGMENT","11:1","RELEASE",-1);return null;});
            assertThrows(RuntimeException.class,()->tx.execute(s->{billing.finish(7,"TTS_SEGMENT","11:1","SETTLE",-1);return null;}));
            for(String key:java.util.List.of("Key","key","Key "))
                tx.execute(s->billing.reserve(7,jdbc.queryForObject("select uuid_short()",Long.class),"GENERATION",key,1,"GENERATION_ACTION",1));
            assertEquals(3L,jdbc.queryForObject("select count(*) from p_point_reservation where business_type='GENERATION'",Long.class));
            System.out.println("Voice point MySQL: V24/V25, concurrent request lock, binary keys, repeat settle/release passed");
            System.out.println("Price lookup EXPLAIN: "+jdbc.queryForList("explain select id from p_point_reservation where account_id=7 and tts_request_key='11' order by id limit 1"));
        }
        finally { server.execute("drop database `"+schema+"`"); }
    }
}
