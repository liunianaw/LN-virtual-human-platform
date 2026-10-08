package com.ruoyi.session.runtime;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.UUID;
import org.apache.ibatis.type.InstantTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="VOICE_TEST_MYSQL_URL", matches=".+")
class UtcPersistenceMysqlTest
{
    @Test void utcDatetimeRoundTripsAcrossJvmZones() throws Exception
    {
        String schema="ln_voice_utc_test_"+UUID.randomUUID().toString().replace("-","");
        String base=System.getenv("VOICE_TEST_MYSQL_URL");
        String user=System.getenv("VOICE_TEST_MYSQL_USER"),password=System.getenv("VOICE_TEST_MYSQL_PASSWORD");
        var admin=new JdbcTemplate(new DriverManagerDataSource(base,user,password));
        admin.execute("create database `"+schema+"`");
        TimeZone previous=TimeZone.getDefault();
        try {
            for(String jvmZone:new String[]{"Asia/Shanghai","UTC"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(jvmZone));
                for(String jdbcZone:new String[]{"Asia/Shanghai","UTC"}) {
                    var ds=new DriverManagerDataSource(base+schema+"?serverTimezone="+jdbcZone
                        +("UTC".equals(jdbcZone)?"&forceConnectionTimeZoneToSession=true":""),user,password);
                    var jdbc=new JdbcTemplate(ds);
                    jdbc.execute("create table if not exists probe(id int primary key,java_time datetime(3),sql_time datetime(3))");
                    jdbc.execute("delete from probe");
                    Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                    jdbc.update("insert into probe values(1,?,utc_timestamp(3))",now);
                    try(var connection=ds.getConnection();var statement=connection.prepareStatement("insert into probe values(2,?,utc_timestamp(3))")) {
                        new InstantTypeHandler().setParameter(statement,1,now,JdbcType.TIMESTAMP);statement.executeUpdate();
                    }
                    for(int id=1;id<=2;id++) {
                        String wall=jdbc.queryForObject("select cast(java_time as char) from probe where id=?",String.class,id);
                        Instant read=jdbc.queryForObject("select java_time from probe where id=?",(rs,row)->rs.getTimestamp(1).toInstant(),id);
                        long drift=jdbc.queryForObject("select timestampdiff(second,sql_time,java_time) from probe where id=?",Long.class,id);
                        System.out.printf("UTC matrix jvm=%s jdbc=%s writer=%d driftSeconds=%d readDeltaMs=%d%n",jvmZone,jdbcZone,id,drift,read.toEpochMilli()-now.toEpochMilli());
                        if("UTC".equals(jdbcZone)) {
                            assertEquals(LocalDateTime.ofInstant(now,ZoneOffset.UTC),LocalDateTime.parse(wall.replace(' ','T')));
                            assertEquals(now,read);assertTrue(Math.abs(drift)<2);
                            assertEquals(0L,jdbc.queryForObject("select timestampdiff(second,utc_timestamp(3),now(3))",Long.class));
                            jdbc.update("update probe set java_time=? where id=?",now.minusSeconds(5),id);
                            assertEquals(1,jdbc.queryForObject("select count(*) from probe where id=? and java_time<=utc_timestamp(3)",Integer.class,id));
                        }
                    }
                }
            }
        } finally {TimeZone.setDefault(previous);admin.execute("drop database `"+schema+"`");}
    }
}
